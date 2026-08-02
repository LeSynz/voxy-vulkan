package xyz.synz.voxyvulkan.client.core.vk;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import xyz.synz.voxyvulkan.client.config.VoxyConfig;
import xyz.synz.voxyvulkan.client.core.RenderProperties;
import net.minecraft.client.Minecraft;
import xyz.synz.voxyvulkan.common.Logger;
import xyz.synz.voxyvulkan.common.world.WorldEngine;
import xyz.synz.voxyvulkan.common.world.WorldSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The Vulkan LOD renderer, replacing {@link xyz.synz.voxyvulkan.client.core.VoxyRenderSystem} on
 * this backend.
 * <p>
 * Voxy's world storage, section data and ingest are entirely backend agnostic - nothing under
 * {@code common/world} or {@code commonImpl} touches OpenGL - so saved LODs are readable as soon as
 * the instance is allowed to start. This meshes those sections into voxy's packed quad format and
 * feeds them to the geometry path, one draw per section with its origin in a push constant.
 * <p>
 * Deliberately simple for now: a fixed set of sections meshed once, no greedy merging, no occlusion
 * culling, no streaming, flat colours instead of the model atlas. Those are all improvements on a
 * working picture rather than prerequisites for one.
 */
public class VkLodRenderer {
    private static VkLodRenderer ACTIVE;

    private static final int SECTION_WIDTH = 32;
    private static final int PUSH_CONSTANT_SIZE = 96;
    //How far to mesh, in sections, at the chosen LOD level
    private static final int MAX_LOD_LEVEL = WorldEngine.MAX_LOD_LAYER;
    //Sections of ring width per LOD level, measured in that level's own section size. The coarsest
    //level ignores this and stretches to whatever is left of the configured range.
    private static final int RING_SECTIONS = 5;

    /**
     * How far LOD terrain reaches, in blocks.
     * <p>
     * {@code sectionRenderDistance} counts top level sections, which are 512 blocks across, so the
     * default of 16 asks for 8192 blocks - four times what sharing Minecraft's 2048 block far plane
     * ever allowed. {@code -Dvoxy.lodRange=<blocks>} overrides it, which is worth having because
     * range and meshing cost move together: if a launch regresses, this says which of the two.
     */
    public static float lodRangeBlocks() {
        String override = System.getProperty("voxy.lodRange");
        if (override != null) {
            try {
                return Float.parseFloat(override);
            } catch (NumberFormatException ignored) {
                //Fall through to the configured value
            }
        }
        return VoxyConfig.CONFIG.sectionRenderDistance * (SECTION_WIDTH << MAX_LOD_LEVEL);
    }

    private record SectionDraw(int firstQuad, int quadCount, int originX, int originY, int originZ, float scale) {}

    private final WorldEngine world;
    private final Matrix4f viewProj = new Matrix4f();
    private final Map<Long, VkGraphicsPipeline> pipelines = new HashMap<>();
    private final List<SectionDraw> draws = new ArrayList<>();

    private VkBuffer quadBuffer;
    private VkBuffer indexBuffer;
    private VkBuffer colourBuffer;
    //Our own depth buffer. Sharing Minecraft's is what capped the view distance at its far plane.
    private final VkDepthTarget depthTarget = new VkDepthTarget(VK_FORMAT_D32_SFLOAT);
    private VkSampler depthSampler;
    private boolean meshed;
    private boolean failed;
    private boolean loggedDraw;
    private boolean loggedProjection;
    private RenderProperties properties;

    public VkLodRenderer(WorldEngine world) {
        this.world = world;
        Logger.info("[vk-lod] Vulkan LOD renderer created, world engine attached");
    }

    public static void setActive(VkLodRenderer renderer) {
        if (ACTIVE != null) {
            ACTIVE.shutdown();
        }
        ACTIVE = renderer;
    }

    public static VkLodRenderer getActive() {
        return ACTIVE;
    }

    public static void clearActive() {
        if (ACTIVE != null) {
            ACTIVE.shutdown();
            ACTIVE = null;
        }
    }

    /**
     * Meshes saved sections around the camera once, packs every quad into one buffer and records
     * where each section's quads start so they can be drawn with their own origin.
     */
    private void meshAround(double cameraX, double cameraY, double cameraZ) {
        long start = System.nanoTime();
        var allQuads = new LongArrayList();
        int sectionsMeshed = 0;
        double range = lodRangeBlocks();

        //Rings start where vanilla stops, not at the camera. Anchoring them to the camera meant
        //level 0's ring sat entirely inside vanilla and contributed nothing, while a single level 4
        //section is 512 blocks wide so the one containing the player was never "entirely inside"
        //vanilla's radius - it drew anyway, underground and all, over the top of everything.
        //Effective, not the raw option: on a server the client setting can be far larger than the
        //view distance actually being sent, which leaves a ring of nothing between vanilla's real
        //edge and where the LODs begin.
        double vanillaRadius = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        //Start slightly inside vanilla's edge so the two overlap by a section rather than risking
        //a seam, since vanilla now occludes us correctly anyway.
        double inner = Math.max(0, vanillaRadius - SECTION_WIDTH);
        //Which sections the previous, finer level actually had data for. A coarse section is only
        //skipped when all eight of its children existed - otherwise it fills the hole. Voxy does not
        //store every level everywhere, so hard rings leave gaps wherever the fine data is absent.
        var coveredByFiner = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

        //Each LOD level covers twice the span of the one below it, so meshing the same section
        //radius at every level gives concentric rings of decreasing detail. Sections already
        //covered by a finer level are skipped so the rings do not overlap and z-fight.
        for (int lvl = 0; lvl <= MAX_LOD_LEVEL; lvl++) {
            int blocksPerSection = SECTION_WIDTH << lvl;
            //This level's ring runs from wherever the previous one ended out to `outer`. The
            //coarsest level takes whatever range is left rather than its own five sections, since
            //there is nothing beyond it to hand the remainder to - fixed rings for every level would
            //cap the view distance at their sum instead of at what was configured.
            double outer = lvl == MAX_LOD_LEVEL
                    ? range
                    : Math.min(range, inner + RING_SECTIONS * (double) blocksPerSection);
            if (outer <= inner) {
                break;//The configured range ran out; coarser levels have nothing left to cover
            }
            int radiusXZ = (int) Math.ceil(outer / blocksPerSection);
            int baseX = (int) Math.floor(cameraX) >> (5 + lvl);
            int baseZ = (int) Math.floor(cameraZ) >> (5 + lvl);

            //Cover the world's full height rather than a window around the camera. A camera
            //relative Y window meant flying up left the ground unmeshed, and the world column is
            //only a few sections tall anyway so there is nothing to save by limiting it.
            var level = Minecraft.getInstance().level;
            int worldMinY = level == null ? -64 : level.getMinY();
            int worldMaxY = level == null ? 320 : level.getMaxY();
            int minSectionY = Math.floorDiv(worldMinY, blocksPerSection);
            int maxSectionY = Math.floorDiv(worldMaxY, blocksPerSection);

            int meshedThisLevel = 0;
            var cache = new HashMap<Long, long[]>();
            var coveredThisLevel = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

            for (int dx = -radiusXZ; dx <= radiusXZ; dx++) {
                for (int dz = -radiusXZ; dz <= radiusXZ; dz++) {
                    for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                        int sx = baseX + dx;
                        int sz = baseZ + dz;

                        double minX = (double) sx * blocksPerSection;
                        double minY = (double) sy * blocksPerSection;
                        double minZ = (double) sz * blocksPerSection;
                        //Vanilla owns its own radius. It loads chunks in a circle and for the full
                        //column height, so this is a cylinder test - a box test overreaches by a
                        //factor of root two along the diagonals, cutting wedges out of the LODs
                        //where vanilla never actually reached.
                        if (intersectsCylinder(minX, minZ, blocksPerSection,
                                cameraX, cameraZ, vanillaRadius - SECTION_WIDTH)) {
                            continue;
                        }
                        //Only defer to the finer level where it genuinely had data
                        if (lvl > 0 && allChildrenCovered(coveredByFiner, lvl, sx, sy, sz)) {
                            continue;
                        }

                        long[] data = sectionData(cache, lvl, sx, sy, sz);
                        if (data == null) {
                            continue;
                        }
                        //Existing but empty still counts as covered, otherwise a coarser level would
                        //draw terrain over somewhere the finer level correctly showed as air
                        coveredThisLevel.add(WorldEngine.getWorldSectionId(lvl, sx, sy, sz));

                        long[][] neighbours = new long[6][];
                        for (int f = 0; f < 6; f++) {
                            var o = VkSectionMesher.FACE_OFFSETS[f];
                            neighbours[f] = sectionData(cache, lvl, sx + o[0], sy + o[1], sz + o[2]);
                        }

                        var quads = VkSectionMesher.mesh(data, neighbours);
                        if (quads.isEmpty()) {
                            continue;
                        }
                        int first = allQuads.size();
                        allQuads.addAll(quads);
                        this.draws.add(new SectionDraw(first, quads.size(),
                                sx * blocksPerSection, sy * blocksPerSection, sz * blocksPerSection,
                                1 << lvl));
                        sectionsMeshed++;
                        meshedThisLevel++;
                    }
                }
            }
            if (meshedThisLevel > 0) {
                Logger.info("[vk-lod] level " + lvl + ": meshed " + meshedThisLevel + " sections, ring "
                        + (int) inner + "-" + (int) outer + " blocks");
            }
            inner = outer;
            coveredByFiner = coveredThisLevel;
        }

        int quadCount = allQuads.size();
        if (quadCount == 0) {
            Logger.warn("[vk-lod] meshed " + sectionsMeshed + " sections but produced no quads");
            return;
        }

        //Upload quads
        long quadBytes = (long) quadCount * Long.BYTES;
        this.quadBuffer = new VkBuffer(quadBytes, true);
        long dst = this.quadBuffer.mappedPointer();
        for (int i = 0; i < quadCount; i++) {
            MemoryUtil.memPutLong(dst + (long) i * Long.BYTES, allQuads.getLong(i));
        }
        this.quadBuffer.flush();

        //Shared index buffer covering every quad, so per section draws can use a first index offset
        long indexBytes = (long) quadCount * 6L * Integer.BYTES;
        this.indexBuffer = new VkBuffer(indexBytes,
                VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        long idst = this.indexBuffer.mappedPointer();
        for (int q = 0; q < quadCount; q++) {
            int v = q * 4;
            long o = idst + (long) q * 6L * Integer.BYTES;
            MemoryUtil.memPutInt(o, v);
            MemoryUtil.memPutInt(o + 4, v + 1);
            MemoryUtil.memPutInt(o + 8, v + 2);
            MemoryUtil.memPutInt(o + 12, v + 2);
            MemoryUtil.memPutInt(o + 16, v + 3);
            MemoryUtil.memPutInt(o + 20, v);
        }
        this.indexBuffer.flush();

        this.colourBuffer = this.buildColourTable();

        double ms = (System.nanoTime() - start) / 1_000_000.0;
        //Range is logged alongside the cost because the two move together: this is the one number
        //that says whether a long load is the distance doing its job or something going wrong.
        Logger.info("[vk-lod] meshed " + sectionsMeshed + " saved sections into " + quadCount
                + " quads (" + (quadBytes >> 10) + " KiB) out to " + (int) range + " blocks in "
                + String.format("%.1f", ms) + " ms");
    }

    /**
     * Section data, loaded once per section per level. Every section is consulted seven times -
     * itself plus as a neighbour of each of its six neighbours - so caching here is what keeps
     * multi level meshing from being seven times more expensive than it needs to be.
     */
    private long[] sectionData(HashMap<Long, long[]> cache, int lvl, int x, int y, int z) {
        long key = WorldEngine.getWorldSectionId(lvl, x, y, z);
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        long[] data = null;
        var section = this.world.acquireIfExists(lvl, x, y, z);
        if (section != null) {
            try {
                data = section.copyData();
            } finally {
                section.release();
            }
        }
        cache.put(key, data);
        return data;
    }

    //Four side planes of the view frustum, each (a,b,c,d). Near and far are deliberately excluded:
    //Minecraft uses reverse Z, which swaps them under the usual extraction and silently culls
    //distant geometry. Nothing is meshed beyond a fixed radius anyway, so they buy us nothing.
    private final float[] planes = new float[16];

    private void extractSidePlanes(Matrix4f m) {
        //Rows of the matrix, JOML stores column major so element (row r, col c) is m<c><r>
        float r0x = m.m00(), r0y = m.m10(), r0z = m.m20(), r0w = m.m30();
        float r1x = m.m01(), r1y = m.m11(), r1z = m.m21(), r1w = m.m31();
        float r3x = m.m03(), r3y = m.m13(), r3z = m.m23(), r3w = m.m33();

        set(0, r3x + r0x, r3y + r0y, r3z + r0z, r3w + r0w);//left
        set(1, r3x - r0x, r3y - r0y, r3z - r0z, r3w - r0w);//right
        set(2, r3x + r1x, r3y + r1y, r3z + r1z, r3w + r1w);//bottom
        set(3, r3x - r1x, r3y - r1y, r3z - r1z, r3w - r1w);//top
    }

    private void set(int i, float a, float b, float c, float d) {
        int o = i * 4;
        this.planes[o] = a;
        this.planes[o + 1] = b;
        this.planes[o + 2] = c;
        this.planes[o + 3] = d;
    }

    /** Tests an axis aligned box against the four side planes, using the positive vertex test. */
    private boolean inFrustum(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        for (int i = 0; i < 4; i++) {
            int o = i * 4;
            float a = this.planes[o], b = this.planes[o + 1], c = this.planes[o + 2], d = this.planes[o + 3];
            float px = a >= 0 ? maxX : minX;
            float py = b >= 0 ? maxY : minY;
            float pz = c >= 0 ? maxZ : minZ;
            if (a * px + b * py + c * pz + d < 0) {
                return false;//entirely outside this plane
            }
        }
        return true;
    }

    /**
     * Builds a colour per block id from Minecraft's map colours - the same ones maps use to draw
     * terrain. Not the real block textures, but genuinely per block and recognisable, unlike the
     * hashed state ids they replace. The model atlas supersedes this once it is ported.
     */
    private VkBuffer buildColourTable() {
        var mapper = this.world.getMapper();
        int maxId = 0;
        var entries = mapper.getStateEntries();
        for (var e : entries) {
            maxId = Math.max(maxId, e.id);
        }
        int count = maxId + 1;

        var buffer = new VkBuffer((long) count * Integer.BYTES, true);
        long ptr = buffer.mappedPointer();
        for (int i = 0; i < count; i++) {
            MemoryUtil.memPutInt(ptr + (long) i * Integer.BYTES, 0xFF808080);
        }
        int resolved = 0;
        for (var e : entries) {
            try {
                var colour = e.state.getMapColor(null, null);
                int rgb = colour == null ? 0x808080 : colour.col;
                if (rgb == 0) {
                    continue;//air and similar, leave the default
                }
                //Store as ABGR so the shader can unpack with simple byte shifts
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                MemoryUtil.memPutInt(ptr + (long) e.id * Integer.BYTES,
                        0xFF000000 | (b << 16) | (g << 8) | r);
                resolved++;
            } catch (Throwable ignored) {
                //Some states need a real level to resolve their colour, the default covers them
            }
        }
        buffer.flush();
        Logger.info("[vk-lod] built block colour table: " + resolved + " of " + count + " states resolved");
        return buffer;
    }

    /**
     * Rebuilds Minecraft's projection with a far plane far enough away to actually see LODs.
     * <p>
     * Minecraft's own far plane is 2048 blocks, which clips distant sections regardless of how much
     * we mesh. This is the same override voxy's OpenGL path uses: keep Minecraft's raw projection,
     * including whatever view bobbing and similar it has folded in, and rewrite only the depth
     * range. The near plane is pushed out to 16 blocks deliberately, so vanilla terrain owns the
     * near field and LOD geometry does not fight it.
     * <p>
     * This was tried once before and reverted, because a longer far plane put our depth values on a
     * different scale to vanilla's and vanilla terrain stopped occluding us. That is now handled: we
     * render against a depth buffer of our own and decide vanilla occlusion by sampling Minecraft's
     * depth rather than comparing against it. Extending the far plane without that is what broke.
     */
    private static Matrix4f computeProjection(Matrix4fc base, RenderProperties properties) {
        var rawMCProj = Minecraft.getInstance().gameRenderer.gameRenderState()
                .levelRenderState.cameraRenderState.projectionMatrix;
        //Whatever extra transform Minecraft folded into the matrix we were handed
        var extraProjection = rawMCProj.invert(new Matrix4f()).mul(base);

        //At tiny vanilla render distances the loaded chunks do not reach 16 blocks in every
        //direction, and the near plane then clips into open sky. Voxy pulls it in for that case.
        float near = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16 <= 32 ? 8f : 16f;
        float far = 16 * 3000f;

        //Reverse depth swaps which plane is which
        if (properties.isReverseZ()) {
            float tmp = near;
            near = far;
            far = tmp;
        }

        return extraProjection.mulLocal(
                new Matrix4f(rawMCProj)
                        .m22((properties.isZero2One() ? far : (far + near)) / (near - far))
                        .m32((properties.isZero2One() ? far : (far + far)) * near / (near - far))
        );
    }

    /** True when every one of this section's eight finer children was present at the level below. */
    private static boolean allChildrenCovered(it.unimi.dsi.fastutil.longs.LongOpenHashSet covered,
                                              int lvl, int sx, int sy, int sz) {
        int child = lvl - 1;
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                for (int dz = 0; dz < 2; dz++) {
                    long key = WorldEngine.getWorldSectionId(child, sx * 2 + dx, sy * 2 + dy, sz * 2 + dz);
                    if (!covered.contains(key)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * True when this section overlaps a vertical cylinder of the given radius around the camera,
     * which is the shape vanilla's loaded chunks actually form.
     */
    private static boolean intersectsCylinder(double minX, double minZ, int size,
                                              double camX, double camZ, double radius) {
        if (radius <= 0) {
            return false;
        }
        //Closest point of the section footprint to the camera
        double closestX = Math.max(minX, Math.min(camX, minX + size));
        double closestZ = Math.max(minZ, Math.min(camZ, minZ + size));
        double dx = closestX - camX;
        double dz = closestZ - camZ;
        return dx * dx + dz * dz < radius * radius;
    }

    /** Called from Sodium's terrain hook, then drawn as the 'Terrain' pass is submitted. */
    public void prepareFrame(Matrix4fc projection, Matrix4fc modelView,
                             double cameraX, double cameraY, double cameraZ) {
        if (this.failed) {
            return;
        }
        try {
            if (!this.meshed) {
                this.meshed = true;
                this.meshAround(cameraX, cameraY, cameraZ);
            }
            if (this.properties == null) {
                this.properties = RenderProperties.getRenderProperties();
            }
            //Our own projection, reaching far past Minecraft's 2048 block far plane. Safe now only
            //because we no longer share Minecraft's depth buffer - see computeProjection.
            var lodProjection = computeProjection(projection, this.properties);
            if (!this.loggedProjection) {
                this.loggedProjection = true;
                //Report both far planes so a distance regression can be attributed rather than
                //guessed at. Under reverse Z an infinite far plane shows as m22 == 0.
                Logger.info("[vk-lod] far plane: vanilla " + describeFarPlane(projection)
                        + ", ours " + describeFarPlane(lodProjection)
                        + ", reverseZ=" + this.properties.isReverseZ()
                        + ", LOD range " + (int) lodRangeBlocks() + " blocks");
            }

            this.viewProj.set(lodProjection).mul(modelView);
            this.camX = cameraX;
            this.camY = cameraY;
            this.camZ = cameraZ;
            this.pending = true;
        } catch (Throwable t) {
            this.fail(t);
        }
    }

    private double camX, camY, camZ;
    private boolean pending;

    //Everything the terrain pass has that we need after it has ended. LOD geometry no longer goes
    //inside Minecraft's pass - it needs a depth attachment of our own, and a pass can't swap one
    //mid-flight - so the pass is inspected on the way in and drawn from once it is out.
    private VkCommandBuffer passCmd;
    private long passColorView;
    private long passDepthView;
    private int passColorFormat;
    private int passDepthFormat;
    private int passWidth;
    private int passHeight;
    private boolean drawQueued;

    /**
     * Called as Minecraft's terrain pass is submitted, while its attachments are still identifiable.
     * Only records what the draw will need - see {@link #onRenderPassEnded()} for why it cannot draw
     * here any more.
     */
    public void onSubmitRenderPass(VulkanRenderPass pass) {
        if (this.failed || !this.pending || this.quadBuffer == null) {
            return;
        }
        try {
            if (!pass.hasDepth || !VkRenderPassTracker.hasUsableTarget() || pass.commandBuffer == null) {
                return;
            }
            if (!"Terrain".equals(safeLabel(pass))) {
                return;
            }
            this.pending = false;

            this.passCmd = pass.commandBuffer;
            this.passColorView = VkRenderPassTracker.colorView();
            this.passDepthView = VkRenderPassTracker.depthView();
            this.passColorFormat = VkRenderPassTracker.colorFormat();
            this.passDepthFormat = VkRenderPassTracker.depthFormat();
            this.passWidth = pass.outputWidth;
            this.passHeight = pass.outputHeight;
            this.drawQueued = true;
        } catch (Throwable t) {
            this.fail(t);
        }
    }

    /**
     * Draws the LODs in a render pass of our own, immediately after Minecraft's terrain pass has
     * ended and while its command buffer is still recording.
     * <p>
     * A pass of our own is the whole point: it keeps Minecraft's colour attachment, so nothing has
     * to be composited back, but swaps in a depth buffer we own. That is what frees the projection
     * from Minecraft's 2048 block far plane. Vanilla terrain still occludes us, but by the fragment
     * shader sampling Minecraft's depth to ask whether vanilla drew here at all, rather than by a
     * depth comparison the two scales can no longer support.
     */
    public void onRenderPassEnded() {
        if (this.failed || !this.drawQueued) {
            return;
        }
        this.drawQueued = false;
        try {
            var cmd = this.passCmd;
            int width = this.passWidth;
            int height = this.passHeight;
            if (cmd == null || width <= 0 || height <= 0) {
                return;
            }

            this.depthTarget.resize(width, height);
            if (this.depthSampler == null) {
                //texelFetch does no filtering, but GLSL still wants a combined image sampler
                this.depthSampler = new VkSampler(VK_FILTER_NEAREST, VK_FILTER_NEAREST,
                        VK_SAMPLER_MIPMAP_MODE_NEAREST, 0.0f, 0.0f);
            }

            long key = ((long) this.passColorFormat << 32) | (this.passDepthFormat & 0xFFFFFFFFL);
            var pipeline = this.pipelines.get(key);
            if (pipeline == null) {
                int compareOp = this.properties.isReverseZ()
                        ? VK_COMPARE_OP_GREATER_OR_EQUAL : VK_COMPARE_OP_LESS_OR_EQUAL;
                //Depth writes on: LOD geometry should occlude itself correctly. The depth format is
                //our own target's, not the pass's, since that is what the pipeline renders against.
                pipeline = new VkGraphicsPipeline(
                        "voxy:vk/lod_quads.vert", "voxy:vk/lod_quads.frag",
                        this.passColorFormat, VK_FORMAT_D32_SFLOAT,
                        true, true, compareOp, PUSH_CONSTANT_SIZE, 2, 1, VK_CULL_MODE_NONE);
                this.pipelines.put(key, pipeline);
                Logger.info("[vk-lod] built LOD pipeline, colour format " + this.passColorFormat
                        + ", own depth target " + width + "x" + height);
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                this.depthTarget.ensureLayout(cmd);
                //Minecraft's terrain depth writes have to be visible to our fragment shader before it
                //samples them. Its own barrier helper, so the stage and access masks match what the
                //rest of the frame is synchronised with.
                VulkanCommandEncoder.memoryBarrier(cmd, stack);

                var colorAttachment = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                        .imageView(this.passColorView)
                        //Minecraft keeps every image it owns in GENERAL and never transitions them
                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);

                var depthAttachment = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                        .imageView(this.depthTarget.view())
                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                        //Nothing reads our depth after the pass, so there is no reason to write it out
                        .storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
                depthAttachment.clearValue().depthStencil().depth(this.properties.clearDepth());

                var renderingInfo = VkRenderingInfo.calloc(stack).sType$Default()
                        .layerCount(1)
                        .pColorAttachments(colorAttachment)
                        .pDepthAttachment(depthAttachment);
                renderingInfo.renderArea().offset().set(0, 0);
                renderingInfo.renderArea().extent().set(width, height);

                vkCmdBeginRenderingKHR(cmd, renderingInfo);
                this.recordDraws(cmd, pipeline, stack, width, height);
                vkCmdEndRenderingKHR(cmd);

                //And our colour writes have to land before Minecraft's next pass touches the target
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            }
        } catch (Throwable t) {
            this.fail(t);
        }
    }

    private void recordDraws(VkCommandBuffer cmd, VkGraphicsPipeline pipeline, MemoryStack stack,
                             int width, int height) {
        pipeline.bind(cmd, width, height);
        pipeline.bindResources(cmd, new VkBuffer[]{this.quadBuffer, this.colourBuffer},
                new long[]{this.passDepthView}, new VkSampler[]{this.depthSampler},
                VK_IMAGE_LAYOUT_GENERAL);
        vkCmdBindIndexBuffer(cmd, this.indexBuffer.buffer, 0, VK_INDEX_TYPE_UINT32);

        //Cull sections outside the view frustum. Without this every section is drawn every
        //frame including everything behind the camera and underground, which is what made this
        //cost 6x the frame time.
        this.extractSidePlanes(this.viewProj);
        int drawn = 0;
        long quadsDrawn = 0;

        var push = stack.malloc(PUSH_CONSTANT_SIZE);
        this.viewProj.get(0, push);
        //The depth value that means Minecraft drew nothing here, which is what the fragment shader
        //tests against to decide whether vanilla terrain already owns the pixel
        push.putFloat(80, this.properties.clearDepth());
        push.putFloat(84, 0f);
        push.putFloat(88, 0f);
        push.putFloat(92, 0f);
        for (var draw : this.draws) {
            //Sodium renders camera relative, so section origins are offsets from the camera
            float ox = (float) (draw.originX() - this.camX);
            float oy = (float) (draw.originY() - this.camY);
            float oz = (float) (draw.originZ() - this.camZ);
            float extent = SECTION_WIDTH * draw.scale();
            if (!this.inFrustum(ox, oy, oz, ox + extent, oy + extent, oz + extent)) {
                continue;
            }

            push.putFloat(64, ox);
            push.putFloat(68, oy);
            push.putFloat(72, oz);
            push.putFloat(76, draw.scale());
            pipeline.pushConstants(cmd, push);
            vkCmdDrawIndexed(cmd, draw.quadCount() * 6, 1, draw.firstQuad() * 6, 0, 0);
            drawn++;
            quadsDrawn += draw.quadCount();
        }

        if (!this.loggedDraw) {
            this.loggedDraw = true;
            Logger.info("[vk-lod] RENDERING " + drawn + " of " + this.draws.size()
                    + " sections after frustum culling (" + quadsDrawn + " quads)");
        }
    }

    /** Reads a projection's far plane back out of it, for logging. */
    private static String describeFarPlane(Matrix4fc projection) {
        float m22 = projection.m22();
        float m32 = projection.m32();
        return Math.abs(m22) < 1.0e-9f
                ? "infinite"
                : String.format("%.0f blocks", Math.abs(m32 / m22));
    }

    private void fail(Throwable t) {
        this.failed = true;
        this.pending = false;
        Logger.error("[vk-lod] failed, disabling Vulkan LOD rendering", t);
    }

    private static String safeLabel(VulkanRenderPass pass) {
        try {
            return pass.label == null ? "<none>" : String.valueOf(pass.label.get());
        } catch (Throwable t) {
            return "<unavailable>";
        }
    }

    public void shutdown() {
        try {
            for (var pipeline : this.pipelines.values()) {
                pipeline.free();
            }
            this.pipelines.clear();
            if (this.quadBuffer != null) {
                this.quadBuffer.free();
                this.quadBuffer = null;
            }
            if (this.indexBuffer != null) {
                this.indexBuffer.free();
                this.indexBuffer = null;
            }
            if (this.colourBuffer != null) {
                this.colourBuffer.free();
                this.colourBuffer = null;
            }
            this.depthTarget.free();
            if (this.depthSampler != null) {
                this.depthSampler.free();
                this.depthSampler = null;
            }
            this.drawQueued = false;
            this.passCmd = null;
            this.draws.clear();
        } catch (Throwable t) {
            Logger.error("[vk-lod] error during shutdown", t);
        }
        Logger.info("[vk-lod] Vulkan LOD renderer shut down");
    }
}
