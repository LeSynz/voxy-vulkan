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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    private int indexBufferQuads;
    //Our own depth buffer. Sharing Minecraft's is what capped the view distance at its far plane.
    private final VkDepthTarget depthTarget = new VkDepthTarget(VK_FORMAT_D32_SFLOAT);
    private VkSampler depthSampler;
    private boolean failed;
    private boolean loggedDraw;
    private boolean loggedProjection;
    private RenderProperties properties;

    /**
     * Meshed quads per section, so a rebuild only pays for sections newly in range.
     * <p>
     * Only the mesh worker touches this, and only one mesh runs at a time, so it needs no locking.
     * Access ordered and bounded: travelling far enough would otherwise accumulate the whole world.
     * The entries are the meshed output, not the source data, so this is also what a future
     * incremental streamer allocates from rather than remeshing.
     */
    private final LinkedHashMap<Long, long[]> meshCache = new LinkedHashMap<>(4096, 0.75f, true);
    private long cachedQuads;
    //Bounded by quads rather than by section count, because sections vary enormously in size - a
    //fixed entry count is either a thrashing cache or an unbounded one depending on the terrain.
    //Twelve million quads is 96 MiB, about three times what a full range currently holds.
    private static final long MESH_CACHE_QUADS = 12_000_000L;

    /**
     * Sections storage has no data for, remembered so they are not asked for again.
     * <p>
     * The coarsest level sweeps tens of thousands of section positions and only a couple of hundred
     * exist - without this, every rebuild pays for all of those misses again. Worker thread only.
     * <p>
     * Note this makes newly ingested terrain invisible until the renderer restarts, which is already
     * true of meshed sections; both want invalidating from the world's dirty callback.
     */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet absentSections =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private static final int MAX_ABSENT_TRACKED = 1_000_000;

    /** Stores a meshed section, evicting least recently used entries to stay inside the budget. */
    private void cacheMesh(long sectionId, long[] mesh) {
        long[] previous = this.meshCache.put(sectionId, mesh);
        if (previous != null) {
            this.cachedQuads -= previous.length;
        }
        this.cachedQuads += mesh.length;
        var iterator = this.meshCache.entrySet().iterator();
        //Access ordered, so iteration starts at the least recently used
        while (this.cachedQuads > MESH_CACHE_QUADS && this.meshCache.size() > 1 && iterator.hasNext()) {
            var eldest = iterator.next();
            if (eldest.getKey() == sectionId) {
                continue;//Never evict what was just stored
            }
            this.cachedQuads -= eldest.getValue().length;
            iterator.remove();
        }
    }

    //Meshing happens off the render thread. One at a time: a second pass while one is running would
    //duplicate almost all of its work, and the newer camera position is only ever a few hundred
    //blocks different.
    private ExecutorService meshWorker;
    private final AtomicReference<MeshResult> completedMesh = new AtomicReference<>();
    private volatile boolean meshInFlight;
    private double meshedCentreX = Double.NaN;
    private double meshedCentreZ = Double.NaN;

    /** How far the camera may drift from the last mesh centre before the rings are rebuilt. */
    private static final double REMESH_DISTANCE = 128.0;

    /**
     * Buffers replaced by a rebuild, held until no recorded frame can still be reading them.
     * Freeing on swap is a use after free: Minecraft has several frames in flight at once.
     */
    private record RetiredBuffer(VkBuffer buffer, int framesLeft) {}
    private final List<RetiredBuffer> retired = new ArrayList<>();
    private static final int RETIRE_FRAMES = VulkanCommandEncoder.MAX_SUBMITS_IN_FLIGHT + 2;

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

    /** The result of a meshing pass: every quad packed back to back, plus where each section sits. */
    private record MeshResult(long[] quads, List<SectionDraw> draws, int maxQuadsPerSection,
                              double centreX, double centreZ, int sectionsMeshed, double millis,
                              int cacheHits, int cacheMisses) {}

    /**
     * Meshes the sections around a point and packs them, touching nothing owned by the GPU.
     * <p>
     * Deliberately free of Vulkan calls and of renderer state, because this is what runs on the
     * mesh worker thread. Everything it needs is either a parameter or the section cache, and
     * everything it produces goes back in the returned result for the render thread to upload.
     */
    private MeshResult buildMesh(double cameraX, double cameraY, double cameraZ) {
        long start = System.nanoTime();
        var allQuads = new LongArrayList();
        var builtDraws = new ArrayList<SectionDraw>();
        int sectionsMeshed = 0;
        int maxQuadsPerSection = 0;
        int cacheHits = 0;
        int cacheMisses = 0;
        double range = lodRangeBlocks();

        //Ring boundaries are still anchored at vanilla's edge, so the finest LOD level lands just
        //outside it rather than being wasted underneath it. Effective, not the raw option: on a
        //server the client setting can be far larger than the view distance actually being sent,
        //which would leave a ring of nothing between vanilla's real edge and where the LODs begin.
        //
        //Note this only positions the rings. Level 0 alone also meshes the disc inside vanilla's
        //radius - see the cylinder test below for why it, and only it, does.
        double vanillaRadius = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
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
            //How many sections this level would previously have drawn inside its own ring. This is
            //the number that says whether coarse geometry encroaching on the near field was the
            //cause of a given screenshot, rather than something that has to be inferred from one.
            int skippedInsideRing = 0;
            var cache = new HashMap<Long, long[]>();
            var coveredThisLevel = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

            for (int dx = -radiusXZ; dx <= radiusXZ; dx++) {
                for (int dz = -radiusXZ; dz <= radiusXZ; dz++) {
                    for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                        int sx = baseX + dx;
                        int sz = baseZ + dz;

                        //Each level is held to the inside of its own ring, geometrically. The
                        //cylinder matches how vanilla loads chunks - in a circle over the full
                        //column - and a box test would overreach by a factor of root two along the
                        //diagonals, cutting wedges out of the LODs.
                        //
                        //This used to be bounded at vanilla's edge for every level, leaving the
                        //ring inner edges to be enforced only by allChildrenCovered below. That is
                        //far too weak a guarantee: it skips a section only when all eight of its
                        //finer children were recorded as covered, and a child that does not exist
                        //in storage at all - which is most air columns - is not covered, so the
                        //check fails and a coarse section draws over perfectly good fine geometry.
                        //Survivable while the coarsest ring was a sliver, glaring once the range
                        //grew and level 4 could scan inward from vanilla's edge across 30k blocks.
                        //
                        //Level 0 is exempt, and only level 0. At one voxel per block it is
                        //indistinguishable from vanilla geometry, so it can sit under the near
                        //field harmlessly and take over the instant vanilla unloads a chunk - which
                        //is what stops a hole opening where you loaded in, since meshing runs once
                        //and would otherwise decline to cover that ground forever. Exempting the
                        //coarse levels does not work: one voxel of a level 4 section is 16 blocks
                        //and the section is 512 wide, so it looms over the near field rather than
                        //hiding under it, and the per pixel discard cannot save that - it hides LOD
                        //geometry only where vanilla drew, and a slab standing against the sky is
                        //exactly where vanilla drew nothing.
                        if (lvl > 0) {
                            double minX = (double) sx * blocksPerSection;
                            double minZ = (double) sz * blocksPerSection;
                            if (containedInCylinder(minX, minZ, blocksPerSection,
                                    cameraX, cameraZ, inner)) {
                                skippedInsideRing++;
                                continue;
                            }
                        }
                        //Still defer to the finer level where it genuinely had data, so the two
                        //rings do not overlap and z-fight along the seam they share
                        if (lvl > 0 && allChildrenCovered(coveredByFiner, lvl, sx, sy, sz)) {
                            continue;
                        }

                        long sectionId = WorldEngine.getWorldSectionId(lvl, sx, sy, sz);
                        //A section already meshed on an earlier pass is reused as is. Travelling
                        //only changes which sections are in range, not what any of them look like,
                        //so without this every rebuild would redo the entire world from storage.
                        long[] meshed = this.meshCache.get(sectionId);
                        if (meshed != null) {
                            cacheHits++;
                            //Cached and empty is still cached - it stands for a section that exists
                            //but produced no geometry, which is exactly what coverage means here
                            coveredThisLevel.add(sectionId);
                            if (meshed.length == 0) {
                                continue;
                            }
                        } else {
                            long[] data = sectionData(cache, lvl, sx, sy, sz);
                            if (data == null) {
                                continue;
                            }
                            cacheMisses++;
                            //Existing but empty still counts as covered, otherwise a coarser level
                            //would draw terrain over somewhere the finer level showed as air
                            coveredThisLevel.add(sectionId);

                            long[][] neighbours = new long[6][];
                            for (int f = 0; f < 6; f++) {
                                var o = VkSectionMesher.FACE_OFFSETS[f];
                                neighbours[f] = sectionData(cache, lvl, sx + o[0], sy + o[1], sz + o[2]);
                            }

                            var quads = VkSectionMesher.mesh(data, neighbours);
                            meshed = quads.toLongArray();
                            this.cacheMesh(sectionId, meshed);
                            if (meshed.length == 0) {
                                continue;
                            }
                        }

                        int first = allQuads.size();
                        allQuads.addElements(first, meshed);
                        maxQuadsPerSection = Math.max(maxQuadsPerSection, meshed.length);
                        builtDraws.add(new SectionDraw(first, meshed.length,
                                sx * blocksPerSection, sy * blocksPerSection, sz * blocksPerSection,
                                1 << lvl));
                        sectionsMeshed++;
                        meshedThisLevel++;
                    }
                }
            }
            if (meshedThisLevel > 0 || skippedInsideRing > 0) {
                Logger.info("[vk-lod] level " + lvl + ": meshed " + meshedThisLevel + " sections, ring "
                        + (int) inner + "-" + (int) outer + " blocks"
                        + (skippedInsideRing > 0 ? " (" + skippedInsideRing + " skipped inside ring)" : ""));
            }
            inner = outer;
            coveredByFiner = coveredThisLevel;
        }

        double ms = (System.nanoTime() - start) / 1_000_000.0;
        return new MeshResult(allQuads.toLongArray(), builtDraws, maxQuadsPerSection,
                cameraX, cameraZ, sectionsMeshed, ms, cacheHits, cacheMisses);
    }

    /**
     * Hands a finished mesh to the GPU. Render thread only.
     * <p>
     * The previous buffers are not freed here. Frames recorded before this one may still be reading
     * them, so they go on a deferred list and are released once those frames can no longer be in
     * flight - see {@link #retireBuffer}.
     */
    private void uploadMesh(MeshResult result) {
        //Recorded before the empty check. Leaving it unset on an empty result would keep the centre
        //at NaN, which reads as 'never meshed' and would start a fresh pass every single frame.
        this.meshedCentreX = result.centreX();
        this.meshedCentreZ = result.centreZ();

        long[] quads = result.quads();
        if (quads.length == 0) {
            Logger.warn("[vk-lod] meshed " + result.sectionsMeshed() + " sections but produced no quads");
            return;
        }

        long quadBytes = (long) quads.length * Long.BYTES;
        var newQuads = new VkBuffer(quadBytes, true);
        long dst = newQuads.mappedPointer();
        for (int i = 0; i < quads.length; i++) {
            MemoryUtil.memPutLong(dst + (long) i * Long.BYTES, quads[i]);
        }
        newQuads.flush();

        this.ensureIndexBuffer(result.maxQuadsPerSection());
        if (this.colourBuffer == null) {
            this.colourBuffer = this.buildColourTable();
        }

        this.retireBuffer(this.quadBuffer);
        this.quadBuffer = newQuads;
        this.draws.clear();
        this.draws.addAll(result.draws());

        int total = result.cacheHits() + result.cacheMisses();
        Logger.info("[vk-lod] meshed " + result.sectionsMeshed() + " saved sections into "
                + quads.length + " quads (" + (quadBytes >> 10) + " KiB) out to "
                + (int) lodRangeBlocks() + " blocks in " + String.format("%.1f", result.millis())
                + " ms (" + result.cacheHits() + "/" + total + " from cache, "
                + this.meshCache.size() + " sections / " + (this.cachedQuads / 1000) + "k quads cached, "
                + this.absentSections.size() + " known absent)");
    }

    /**
     * Allocates the shared quad index buffer, big enough for the largest single section.
     * <p>
     * One buffer serves every draw. Quads are indexed relative to the section rather than to the
     * whole world, and {@code vkCmdDrawIndexed}'s vertexOffset shifts {@code gl_VertexIndex} to the
     * section's own quads. Indexing the world absolutely instead needed six indices per quad across
     * every quad in range - a hundred megabytes at this range, rebuilt on every remesh.
     */
    private void ensureIndexBuffer(int maxQuadsPerSection) {
        if (this.indexBuffer != null && this.indexBufferQuads >= maxQuadsPerSection) {
            return;
        }
        int quads = Math.max(maxQuadsPerSection, 4096);
        this.retireBuffer(this.indexBuffer);
        long indexBytes = (long) quads * 6L * Integer.BYTES;
        this.indexBuffer = new VkBuffer(indexBytes,
                VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        long idst = this.indexBuffer.mappedPointer();
        for (int q = 0; q < quads; q++) {
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
        this.indexBufferQuads = quads;
        Logger.info("[vk-lod] shared index buffer sized for " + quads + " quads per section ("
                + (indexBytes >> 10) + " KiB)");
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
        if (this.absentSections.contains(key)) {
            cache.put(key, null);
            return null;
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
        if (data == null) {
            if (this.absentSections.size() >= MAX_ABSENT_TRACKED) {
                this.absentSections.clear();//Crude, but keeps a long session bounded
            }
            this.absentSections.add(key);
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

    /**
     * True when this section overlaps a vertical cylinder of the given radius around the camera,
     * which is the shape vanilla's loaded chunks actually form.
     */
    private static boolean containedInCylinder(double minX, double minZ, int size,
                                               double camX, double camZ, double radius) {
        if (radius <= 0) {
            return false;
        }
        //Farthest corner of the section footprint from the camera. Testing the nearest corner
        //instead - "does this section touch the inner radius" - drops every section straddling the
        //boundary, and the finer level only reaches the boundary itself, so the width of one
        //straddling section is left covered by nobody. That is a ring of missing terrain up to a
        //section wide, and because the grid alignment shifts as the camera moves, it opens and
        //closes on every rebuild rather than staying put and being obvious.
        double dx = Math.max(Math.abs(minX - camX), Math.abs(minX + size - camX));
        double dz = Math.max(Math.abs(minZ - camZ), Math.abs(minZ + size - camZ));
        return dx * dx + dz * dz < radius * radius;
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
     * Picks up a finished mesh and starts a new one when the camera has wandered far enough.
     * Render thread only.
     * <p>
     * Meshing used to run once, inline, at world load - which froze the rings around wherever you
     * loaded in, so travelling left the meshed region behind and never filled ahead. It cannot
     * simply be re-run on movement either: a full pass is seconds of work, and seconds on the render
     * thread is a freeze. So it runs on a worker and the result is swapped in when it arrives.
     */
    private void tickMeshing(double cameraX, double cameraY, double cameraZ) {
        var finished = this.completedMesh.getAndSet(null);
        if (finished != null) {
            this.uploadMesh(finished);
        }

        if (this.meshInFlight) {
            return;
        }
        boolean first = Double.isNaN(this.meshedCentreX);
        if (!first) {
            double dx = cameraX - this.meshedCentreX;
            double dz = cameraZ - this.meshedCentreZ;
            if (dx * dx + dz * dz < REMESH_DISTANCE * REMESH_DISTANCE) {
                return;
            }
        }
        //Claimed before the task is handed over, so a frame between submit and the worker starting
        //cannot queue a second pass over almost the same ground
        this.meshInFlight = true;
        if (this.meshWorker == null) {
            this.meshWorker = Executors.newSingleThreadExecutor(r -> {
                var thread = new Thread(r, "voxy-vk-mesh");
                thread.setDaemon(true);
                //Below the render thread: a rebuild finishing a frame later is invisible, a
                //rebuild stealing time from the frame is not
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
        }
        this.meshWorker.execute(() -> {
            try {
                this.completedMesh.set(this.buildMesh(cameraX, cameraY, cameraZ));
            } catch (Throwable t) {
                Logger.error("[vk-lod] meshing failed on the worker thread", t);
            } finally {
                this.meshInFlight = false;
            }
        });
    }

    /** Queues a buffer for release once no frame recorded against it can still be in flight. */
    private void retireBuffer(VkBuffer buffer) {
        if (buffer != null) {
            this.retired.add(new RetiredBuffer(buffer, RETIRE_FRAMES));
        }
    }

    /** Ages the retirement list by a frame and frees whatever has come due. Render thread only. */
    private void tickRetirement() {
        if (this.retired.isEmpty()) {
            return;
        }
        for (int i = this.retired.size() - 1; i >= 0; i--) {
            var entry = this.retired.get(i);
            if (entry.framesLeft() <= 0) {
                entry.buffer().free();
                this.retired.remove(i);
            } else {
                this.retired.set(i, new RetiredBuffer(entry.buffer(), entry.framesLeft() - 1));
            }
        }
    }

    /** Called from Sodium's terrain hook, then drawn as the 'Terrain' pass is submitted. */
    public void prepareFrame(Matrix4fc projection, Matrix4fc modelView,
                             double cameraX, double cameraY, double cameraZ) {
        if (this.failed) {
            return;
        }
        try {
            this.tickMeshing(cameraX, cameraY, cameraZ);
            if (this.properties == null) {
                this.properties = RenderProperties.getRenderProperties();
            }
            //Our own projection, reaching far past Minecraft's far plane. Safe now only because we
            //no longer share Minecraft's depth buffer - see computeProjection.
            var lodProjection = computeProjection(projection, this.properties);
            if (!isUsable(lodProjection)) {
                //computeProjection inverts Minecraft's game render state projection, and on the
                //earliest frames that matrix can still be unpopulated - inverting it gives back all
                //NaN, which would put every vertex nowhere. Sodium's own matrix is already valid at
                //that point, so fall back to it and try again next frame rather than drawing a
                //frame of nothing. This was visible as 'far plane: ours NaN' in the log.
                this.viewProj.set(projection).mul(modelView);
                this.camX = cameraX;
                this.camY = cameraY;
                this.camZ = cameraZ;
                this.pending = true;
                return;
            }
            if (!this.loggedProjection) {
                this.loggedProjection = true;
                //Report both far planes so a distance regression can be attributed rather than
                //guessed at. Under reverse Z an infinite far plane shows as m22 == 0.
                Logger.info("[vk-lod] far plane: vanilla " + describeFarPlane(projection)
                        + ", ours " + describeFarPlane(lodProjection)
                        + " (m22=" + lodProjection.m22() + " m32=" + lodProjection.m32() + ")"
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
            //Ages buffers a rebuild replaced. Done here rather than in prepareFrame because this
            //runs once per rendered frame, which is what the retirement count actually measures.
            this.tickRetirement();
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
            //The index buffer only ever describes one section's worth of quads, starting at zero.
            //vertexOffset slides gl_VertexIndex onto this section's quads, so the same indices serve
            //every draw - which is what keeps it a few hundred KiB instead of scaling with the world.
            vkCmdDrawIndexed(cmd, draw.quadCount() * 6, 1, 0, draw.firstQuad() * 4, 0);
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
        if (!Float.isFinite(m22) || !Float.isFinite(m32)) {
            return "<not finite>";
        }
        return Math.abs(m22) < 1.0e-9f
                ? "infinite"
                : String.format("%.0f blocks", Math.abs(m32 / m22));
    }

    /**
     * True when a projection is safe to render with. Checks the elements a bad matrix inversion
     * poisons first - one NaN anywhere in the chain spreads to all of them.
     */
    private static boolean isUsable(Matrix4fc projection) {
        return Float.isFinite(projection.m00()) && Float.isFinite(projection.m11())
                && Float.isFinite(projection.m22()) && Float.isFinite(projection.m32());
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
        //Stopped first and waited for. The worker holds no GPU objects, but it does write into the
        //mesh cache and the completed slot, and letting it run on past a teardown would have it
        //publishing a result to a renderer that no longer exists.
        if (this.meshWorker != null) {
            this.meshWorker.shutdownNow();
            try {
                if (!this.meshWorker.awaitTermination(5, TimeUnit.SECONDS)) {
                    Logger.warn("[vk-lod] mesh worker did not stop within 5s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            this.meshWorker = null;
        }
        this.meshCache.clear();
        this.cachedQuads = 0;
        this.absentSections.clear();
        this.completedMesh.set(null);
        this.meshInFlight = false;
        try {
            for (var entry : this.retired) {
                entry.buffer().free();
            }
            this.retired.clear();
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
