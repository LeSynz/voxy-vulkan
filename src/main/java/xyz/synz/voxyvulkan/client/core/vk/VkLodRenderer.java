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

    private record SectionDraw(int firstQuad, int quadCount, int opaqueCount,
                               int originX, int originY, int originZ, float scale) {}

    private final WorldEngine world;
    private final Matrix4f viewProj = new Matrix4f();
    private final Map<Long, VkGraphicsPipeline> pipelines = new HashMap<>();
    private final Map<Long, VkGraphicsPipeline> translucentPipelines = new HashMap<>();
    /** How see through blended LOD geometry is. Water at distance, mostly. */
    private static final float TRANSLUCENT_ALPHA = 0.75f;
    private final List<SectionDraw> draws = new ArrayList<>();

    private VkBuffer quadBuffer;
    private VkBuffer indexBuffer;
    private VkBuffer colourBuffer;
    private int indexBufferQuads;
    //Our own depth buffer. Sharing Minecraft's is what capped the view distance at its far plane.
    private final VkDepthTarget depthTarget = new VkDepthTarget(VK_FORMAT_D32_SFLOAT);
    private VkSampler depthSampler;
    private VkSampler lightSampler;
    private VkTexture fallbackLightmap;
    private VkBlockTints.Tables tints;

    //The baked block model atlas. Terrain draws with flat colours until this is ready and simply
    //starts using textures once it is, so a slow or failed bake costs detail rather than terrain.
    private VkModelStore modelStore;
    private VkModelBakery modelBakery;
    private VkBuffer fallbackModelIds;
    private VkTexture fallbackAtlas;
    private boolean bakeryStartRequested;
    private boolean triedTints;
    private boolean failed;
    private boolean loggedDraw;
    private boolean loggedProjection;
    private boolean loggedLightStats;
    private RenderProperties properties;

    /**
     * Meshed quads per section, so a rebuild only pays for sections newly in range.
     * <p>
     * Only the mesh worker touches this, and only one mesh runs at a time, so it needs no locking.
     * Access ordered and bounded: travelling far enough would otherwise accumulate the whole world.
     * The entries are the meshed output, not the source data, so this is also what a future
     * incremental streamer allocates from rather than remeshing.
     */
    private final LinkedHashMap<Long, VkSectionMesher.Mesh> meshCache = new LinkedHashMap<>(4096, 0.75f, true);
    private long cachedQuads;

    /**
     * Which block ids draw translucent, resolved once on the mesh worker.
     * <p>
     * Built lazily rather than in the constructor because it walks every block state's model, and
     * the renderer is created before Minecraft has necessarily finished loading them.
     */
    private volatile boolean[] translucentStates;

    /**
     * Which sideways faces a section has to keep, packed into the four spare low bits of its id.
     * <p>
     * A section on a ring edge is meshed differently from the same section in the middle of one, and
     * which edges it is on moves with the camera - so the variant has to be part of the cache key or
     * the two keep overwriting each other. {@code getWorldSectionId} leaves bits 0..3 unused and
     * says so, which is exactly the four X and Z directions. A mask of zero is the ordinary interior
     * mesh, which is the overwhelming majority and still hits the same entry it always did.
     */
    private static final int[] EDGE_MASK_FACES = {0, 1, 4, 5};
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

    /** Sections ingest has rewritten since the last mesh. Written from ingest threads. */
    private final java.util.Set<Long> dirtySections =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Stores a meshed section, evicting least recently used entries to stay inside the budget. */
    private void cacheMesh(long cacheKey, VkSectionMesher.Mesh mesh) {
        var previous = this.meshCache.put(cacheKey, mesh);
        if (previous != null) {
            this.cachedQuads -= previous.quads().length;
        }
        this.cachedQuads += mesh.quads().length;
        var iterator = this.meshCache.entrySet().iterator();
        //Access ordered, so iteration starts at the least recently used
        while (this.cachedQuads > MESH_CACHE_QUADS && this.meshCache.size() > 1 && iterator.hasNext()) {
            var eldest = iterator.next();
            if (eldest.getKey() == cacheKey) {
                continue;//Never evict what was just stored
            }
            this.cachedQuads -= eldest.getValue().quads().length;
            iterator.remove();
        }
    }

    /**
     * Which of a section's four sideways neighbours are not going to be drawn at this level.
     * <p>
     * Meshing culls a face whenever the neighbouring section's data says solid, which is right in
     * the middle of a ring and wrong at its edge: the neighbour exists in storage but is being drawn
     * by a different level, at a different resolution. Voxy builds coarser levels by treating a
     * region as solid if any block in it is, so their surfaces sit above the finer level's - and with
     * the sideways face culled there is nothing spanning the step, so you see straight through it.
     * <p>
     * Returned per direction rather than as one flag. Exposing all four sides of every edge section
     * put a full skin on three sides that did not need one, which inflated the cache enough to start
     * evicting the interior meshes it depends on.
     */
    private static int ringEdgeMask(int sx, int sz, int baseX, int baseZ, int radiusXZ,
                                    int blocksPerSection, double camX, double camZ, double inner) {
        int mask = 0;
        for (int i = 0; i < 4; i++) {
            int nx = sx + (i == 0 ? -1 : i == 1 ? 1 : 0);
            int nz = sz + (i == 2 ? -1 : i == 3 ? 1 : 0);
            boolean undrawn = Math.abs(nx - baseX) > radiusXZ || Math.abs(nz - baseZ) > radiusXZ
                    //Inside this level's ring, so owned by a finer one
                    || containedInCylinder((double) nx * blocksPerSection, (double) nz * blocksPerSection,
                            blocksPerSection, camX, camZ, inner);
            if (undrawn) {
                mask |= 1 << i;
            }
        }
        return mask;
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
     * How often a cold first mesh publishes what it has so far. Each publish copies the quads built
     * to that point, so this trades a handful of extra uploads - only ever on a cold start, when
     * there is nothing on screen to lose - for terrain appearing in about a second.
     */
    private static final int PROGRESSIVE_SECTION_INTERVAL = 400;
    /** Floor on how often newly ingested terrain alone may trigger a rebuild. */
    private static final long DIRTY_REMESH_INTERVAL_MS = 3_000;
    private long lastDirtyRemesh;

    /**
     * Buffers replaced by a rebuild, held until no recorded frame can still be reading them.
     * Freeing on swap is a use after free: Minecraft has several frames in flight at once.
     */
    private record RetiredBuffer(VkBuffer buffer, int framesLeft) {}
    private final List<RetiredBuffer> retired = new ArrayList<>();
    private static final int RETIRE_FRAMES = VulkanCommandEncoder.MAX_SUBMITS_IN_FLIGHT + 2;

    public VkLodRenderer(WorldEngine world) {
        this.world = world;
        //Ingest writes new terrain into storage as chunks load, and both caches would otherwise
        //hold the old answer forever - a meshed section never remeshed, and worse, a section that
        //did not exist when first asked for remembered as absent permanently. That is why a chunk
        //loaded and then left behind never appeared as LOD.
        world.setDirtyCallback((section, updateFlags, neighborMsk) -> this.onSectionDirty(section.key));
        Logger.info("[vk-lod] Vulkan LOD renderer created, world engine attached");
    }

    /**
     * Marks a section as needing remeshing. Called from ingest threads, so it only enqueues - the
     * caches belong to the mesh worker and are drained at the start of the next pass.
     */
    private void onSectionDirty(long sectionId) {
        this.dirtySections.add(sectionId);
    }

    /** Drops cached answers for sections ingest has changed. Mesh worker only. */
    private int drainDirtySections() {
        if (this.dirtySections.isEmpty()) {
            return 0;
        }
        int dropped = 0;
        for (var iterator = this.dirtySections.iterator(); iterator.hasNext(); ) {
            long id = iterator.next();
            iterator.remove();
            this.absentSections.remove(id);
            //A section is meshed against its neighbours and cached per edge variant, so every
            //variant of it goes, along with the six neighbours whose faces were culled against it
            dropped += this.forgetSection(id);
            int lvl = WorldEngine.getLevel(id);
            int x = WorldEngine.getX(id), y = WorldEngine.getY(id), z = WorldEngine.getZ(id);
            for (var offset : VkSectionMesher.FACE_OFFSETS) {
                this.forgetSection(WorldEngine.getWorldSectionId(
                        lvl, x + offset[0], y + offset[1], z + offset[2]));
            }
            //Coarser levels are derived from this one, so they are stale too
            for (int parent = lvl + 1; parent <= MAX_LOD_LEVEL; parent++) {
                int shift = parent - lvl;
                long parentId = WorldEngine.getWorldSectionId(
                        parent, x >> shift, y >> shift, z >> shift);
                this.absentSections.remove(parentId);
                this.forgetSection(parentId);
            }
        }
        return dropped;
    }

    /** Removes every cached edge variant of one section, returning how many were held. */
    private int forgetSection(long sectionId) {
        int removed = 0;
        //The low four bits are the edge mask, so the variants are sectionId through sectionId | 15
        for (int mask = 0; mask < 16; mask++) {
            var previous = this.meshCache.remove(sectionId | mask);
            if (previous != null) {
                this.cachedQuads -= previous.quads().length;
                removed++;
            }
        }
        return removed;
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
                              int cacheHits, int cacheMisses, int edgeSections, int invalidated,
                              int partialThroughLevel) {}

    /**
     * Meshes the sections around a point and packs them, touching nothing owned by the GPU.
     * <p>
     * Deliberately free of Vulkan calls and of renderer state, because this is what runs on the
     * mesh worker thread. Everything it needs is either a parameter or the section cache, and
     * everything it produces goes back in the returned result for the render thread to upload.
     */
    private MeshResult buildMesh(double cameraX, double cameraY, double cameraZ, boolean progressive) {
        long start = System.nanoTime();
        var allQuads = new LongArrayList();
        var builtDraws = new ArrayList<SectionDraw>();
        int sectionsMeshed = 0;
        int maxQuadsPerSection = 0;
        int cacheHits = 0;
        int cacheMisses = 0;
        int edgeSections = 0;
        int invalidated = this.drainDirtySections();
        double range = lodRangeBlocks();
        //Resolved on this thread, once. Voxy's own bakery walks block models off the render thread
        //too, so this is the same access pattern rather than a new one.
        boolean[] translucentTable = this.translucentStates;
        if (translucentTable == null) {
            try {
                translucentTable = VkBlockTints.buildTranslucentStates(this.world.getMapper());
                //Only kept on success. A failure here usually means Minecraft has not finished
                //loading models yet, and caching that answer would make everything opaque forever.
                this.translucentStates = translucentTable;
                //Anything meshed before the table existed was split as fully opaque, so it would
                //keep drawing water as solid no matter how many rebuilds ran over it
                if (!this.meshCache.isEmpty()) {
                    this.meshCache.clear();
                    this.cachedQuads = 0;
                }
            } catch (Throwable t) {
                Logger.warn("[vk-lod] translucent block states not resolvable yet, drawing opaque"
                        + " for now: " + t);
                translucentTable = null;
            }
        }

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
                        int edgeMask = ringEdgeMask(sx, sz, baseX, baseZ, radiusXZ,
                                blocksPerSection, cameraX, cameraZ, lvl > 0 ? inner : 0);
                        //The mask rides in the id's spare low bits, so an edge variant and the
                        //ordinary interior mesh are separate entries in the one cache
                        long cacheKey = sectionId | edgeMask;
                        if (edgeMask != 0) {
                            edgeSections++;
                        }
                        //A section already meshed on an earlier pass is reused as is. Travelling
                        //only changes which sections are in range, not what any of them look like,
                        //so without this every rebuild would redo the entire world from storage.
                        var meshed = this.meshCache.get(cacheKey);
                        if (meshed != null) {
                            cacheHits++;
                            //Cached and empty is still cached - it stands for a section that exists
                            //but produced no geometry, which is exactly what coverage means here
                            coveredThisLevel.add(sectionId);
                            if (meshed.quads().length == 0) {
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
                            //Left null where the neighbour is not being drawn at this level, which
                            //the mesher reads as air and so keeps the face, closing the step down to
                            //whatever the coarser level drew. Y is never masked - the sections above
                            //and below are always the same level as this one.
                            for (int i = 0; i < 4; i++) {
                                if ((edgeMask & (1 << i)) != 0) {
                                    neighbours[EDGE_MASK_FACES[i]] = null;
                                }
                            }

                            meshed = VkSectionMesher.mesh(data, neighbours, translucentTable);
                            this.cacheMesh(cacheKey, meshed);
                            if (meshed.quads().length == 0) {
                                continue;
                            }
                        }

                        int first = allQuads.size();
                        allQuads.addElements(first, meshed.quads());
                        maxQuadsPerSection = Math.max(maxQuadsPerSection, meshed.quads().length);
                        builtDraws.add(new SectionDraw(first, meshed.quads().length, meshed.opaqueCount(),
                                sx * blocksPerSection, sy * blocksPerSection, sz * blocksPerSection,
                                1 << lvl));
                        sectionsMeshed++;
                        meshedThisLevel++;

                        //Publishing only at level boundaries still meant six seconds of empty world,
                        //because level 0 alone sweeps thousands of storage positions with no warm
                        //cache. Emitting partway through it puts terrain up in about a second.
                        if (progressive && sectionsMeshed % PROGRESSIVE_SECTION_INTERVAL == 0) {
                            this.completedMesh.set(new MeshResult(allQuads.toLongArray(),
                                    new ArrayList<>(builtDraws), maxQuadsPerSection, cameraX, cameraZ,
                                    sectionsMeshed, (System.nanoTime() - start) / 1_000_000.0,
                                    cacheHits, cacheMisses, edgeSections, invalidated, lvl));
                        }
                    }
                }
            }
            if (meshedThisLevel > 0 || skippedInsideRing > 0) {
                Logger.info("[vk-lod] level " + lvl + ": meshed " + meshedThisLevel + " sections, ring "
                        + (int) inner + "-" + (int) outer + " blocks"
                        + (skippedInsideRing > 0 ? " (" + skippedInsideRing + " skipped inside ring)" : ""));
            }
            //On a cold start there is nothing on screen until the whole sweep finishes, which is
            //the better part of ten seconds because no cache is warm yet. Publishing each level as
            //it lands puts the nearest terrain up in about a second and fills outwards, at the cost
            //of re-uploading the buffer once per level - which is only paid when there is nothing
            //to show anyway.
            if (progressive && lvl < MAX_LOD_LEVEL && !allQuads.isEmpty()) {
                this.completedMesh.set(new MeshResult(allQuads.toLongArray(),
                        new ArrayList<>(builtDraws), maxQuadsPerSection, cameraX, cameraZ,
                        sectionsMeshed, (System.nanoTime() - start) / 1_000_000.0,
                        cacheHits, cacheMisses, edgeSections, invalidated, lvl));
            }
            inner = outer;
            coveredByFiner = coveredThisLevel;
        }

        double ms = (System.nanoTime() - start) / 1_000_000.0;
        return new MeshResult(allQuads.toLongArray(), builtDraws, maxQuadsPerSection,
                cameraX, cameraZ, sectionsMeshed, ms, cacheHits, cacheMisses, edgeSections,
                invalidated, -1);
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
        //Attempted once. Resolving tints walks every block state against every biome and touches a
        //lot of Minecraft, so a failure here drops back to untinted rather than taking the renderer
        //down - grass being one shade is a far smaller problem than no terrain at all.
        if (!this.triedTints) {
            this.triedTints = true;
            try {
                this.tints = VkBlockTints.build(this.world.getMapper());
            } catch (Throwable t) {
                Logger.error("[vk-lod] could not build biome tint tables, rendering untinted", t);
                //Falls back to a table that tints nothing rather than leaving the binding null,
                //which would stop the draw happening at all
                this.tints = VkBlockTints.empty();
            }
        }

        this.retireBuffer(this.quadBuffer);
        this.quadBuffer = newQuads;
        this.draws.clear();
        this.draws.addAll(result.draws());
        this.lastQuadCount = quads.length;
        this.lastMeshMillis = result.millis();

        //Lighting reads index zero as fully lit, because that is what an unpopulated value looks
        //like. This says which it actually is: if almost every quad reports zero the stored light
        //is not there and the world is being drawn unlit rather than lit.
        if (!this.loggedLightStats) {
            this.loggedLightStats = true;
            int sampled = 0;
            int lit = 0;
            int step = Math.max(1, quads.length / 4096);
            for (int i = 0; i < quads.length; i += step) {
                sampled++;
                if (((quads[i] >>> 55) & 0xFF) != 0) {
                    lit++;
                }
            }
            Logger.info("[vk-lod] light data: " + lit + " of " + sampled
                    + " sampled quads carry a non zero light value"
                    + (lit == 0 ? " - LIGHTING IS BEING SKIPPED, terrain will draw fullbright" : ""));
        }

        int total = result.cacheHits() + result.cacheMisses();
        if (result.partialThroughLevel() >= 0) {
            Logger.info("[vk-lod] partial: levels 0-" + result.partialThroughLevel() + " up, "
                    + result.sectionsMeshed() + " sections, " + quads.length + " quads at "
                    + String.format("%.0f", result.millis()) + " ms");
            return;
        }
        Logger.info("[vk-lod] meshed " + result.sectionsMeshed() + " saved sections into "
                + quads.length + " quads (" + (quadBytes >> 10) + " KiB) out to "
                + (int) lodRangeBlocks() + " blocks in " + String.format("%.1f", result.millis())
                + " ms (" + result.cacheHits() + "/" + total + " from cache, "
                + this.meshCache.size() + " cached / " + (this.cachedQuads / 1000) + "k quads, "
                + result.edgeSections() + " ring edge, "
                + this.absentSections.size() + " known absent"
                + (result.invalidated() > 0 ? ", " + result.invalidated() + " invalidated" : "") + ")");
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
        this.tickModelBakery();

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
            boolean moved = dx * dx + dz * dz >= REMESH_DISTANCE * REMESH_DISTANCE;
            //Ingest rewriting terrain is the other reason to rebuild, but it fires constantly while
            //chunks stream in, so it is rate limited rather than acted on the moment it arrives
            long now = System.currentTimeMillis();
            boolean dirty = !this.dirtySections.isEmpty()
                    && now - this.lastDirtyRemesh >= DIRTY_REMESH_INTERVAL_MS;
            if (!moved && !dirty) {
                return;
            }
            if (dirty) {
                this.lastDirtyRemesh = now;
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
        //Only publish level by level when there is nothing on screen yet, so the extra uploads are
        //paid exactly when they buy something
        boolean progressive = this.quadBuffer == null;
        this.meshWorker.execute(() -> {
            try {
                this.completedMesh.set(this.buildMesh(cameraX, cameraY, cameraZ, progressive));
            } catch (Throwable t) {
                Logger.error("[vk-lod] meshing failed on the worker thread", t);
            } finally {
                this.meshInFlight = false;
            }
        });
    }

    /**
     * Starts the model bakery once, then pumps its finished bakes to the GPU. Render thread only.
     * <p>
     * The bakery is constructed on a worker rather than here, because the first thing it does is
     * block waiting for Minecraft's block atlas, and that only arrives if this thread stays free.
     */
    private void tickModelBakery() {
        if (!this.bakeryStartRequested) {
            this.bakeryStartRequested = true;
            try {
                this.modelStore = new VkModelStore();
                this.modelBakery = new VkModelBakery(this.world.getMapper(), this.modelStore);
                var starter = new Thread(this.modelBakery::startBlocking, "voxy-vk-bakery-start");
                starter.setDaemon(true);
                starter.start();
            } catch (Throwable t) {
                Logger.error("[vk-model] could not create the model store, staying on map colours", t);
                this.modelStore = null;
                this.modelBakery = null;
            }
        }
        if (this.modelBakery != null && this.modelBakery.isReady()) {
            this.modelBakery.tick();
            if (!this.loggedTextured && this.modelBakery.publishedModels() > 0) {
                this.loggedTextured = true;
                Logger.info("[vk-model] textured LOD rendering active, first "
                        + this.modelBakery.publishedModels() + " models baked");
            }
        }
    }

    private boolean loggedTextured;

    /** True once there are baked models to sample instead of flat colours. */
    private boolean modelsUsable() {
        return this.modelBakery != null && this.modelBakery.isReady()
                && !this.modelBakery.hasFailed() && this.modelBakery.modelIdBuffer() != null
                && this.modelStore != null;
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
    private long passLightmapView;
    private boolean drawQueued;

    /**
     * Minecraft's level lightmap as a raw Vulkan image view, or null if it is not available yet.
     * <p>
     * This is the same 16x16 texture vanilla terrain samples, so LOD terrain picks up block and sky
     * light identically rather than needing a lighting model of its own.
     */
    private static long lightmapImageView() {
        try {
            var view = Minecraft.getInstance().gameRenderer.levelLightmap();
            if (view instanceof com.mojang.blaze3d.vulkan.VulkanGpuTextureView vk) {
                return vk.vkImageView();
            }
        } catch (Throwable ignored) {
            //Not available this early in a frame, or the dimension is mid swap
        }
        return VK_NULL_HANDLE;
    }

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
        if (this.failed) {
            return;
        }
        //Ahead of the drawQueued check on purpose. This is the only place we are certain no render
        //pass is open, which a buffer copy requires, and it has to happen even on the early frames
        //where there is no mesh to draw yet - otherwise the bakery waits for an atlas that was
        //never asked for. Never waited for on this thread: the copy only completes because this
        //thread keeps drawing. See VkAtlasDownloader.
        VkAtlasDownloader.start();
        if (!this.drawQueued) {
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
                //Linear across the lightmap, matching how vanilla samples it, so light levels blend
                this.lightSampler = new VkSampler(VK_FILTER_LINEAR, VK_FILTER_LINEAR,
                        VK_SAMPLER_MIPMAP_MODE_NEAREST, 0.0f, 0.0f);
            }
            if (this.tints == null) {
                return;//The first upload has not happened yet, so there is nothing to draw anyway
            }
            //Fetched per frame rather than cached: Minecraft rebuilds the lightmap view whenever the
            //dimension or resource pack changes, and a stale view is a dangling handle
            long lightmapView = lightmapImageView();
            if (lightmapView == VK_NULL_HANDLE) {
                //A single white pixel stands in, so terrain still draws - at full brightness rather
                //than not at all. Skipping the frame instead would mean that if the lightmap never
                //turned up, nothing would ever render and the cause would be invisible.
                if (this.fallbackLightmap == null) {
                    this.fallbackLightmap = VkTexture.singlePixel(0xFFFFFFFF);
                    Logger.warn("[vk-lod] level lightmap unavailable, lighting LODs at full brightness");
                }
                lightmapView = this.fallbackLightmap.imageView;
            }
            this.passLightmapView = lightmapView;

            //Stand ins for the model bindings, so the draw is identical whether or not the bakery
            //has finished. A single -1 reads as 'no baked model' for every state the shader asks about.
            if (this.fallbackModelIds == null) {
                this.fallbackModelIds = new VkBuffer(Integer.BYTES, true);
                MemoryUtil.memPutInt(this.fallbackModelIds.mappedPointer(), -1);
                this.fallbackModelIds.flush();
                this.fallbackAtlas = VkTexture.singlePixel(0xFFFFFFFF);
            }

            long key = ((long) this.passColorFormat << 32) | (this.passDepthFormat & 0xFFFFFFFFL);
            var pipeline = this.pipelines.get(key);
            if (pipeline == null) {
                int compareOp = this.properties.isReverseZ()
                        ? VK_COMPARE_OP_GREATER_OR_EQUAL : VK_COMPARE_OP_LESS_OR_EQUAL;
                //Depth writes on: LOD geometry should occlude itself correctly. The depth format is
                //our own target's, not the pass's, since that is what the pipeline renders against.
                //Four storage buffers (quads, block colours, tint offsets, tint colours) then two
                //samplers (Minecraft's depth, Minecraft's lightmap)
                //Five storage buffers (quads, block colours, tint offsets, tint colours, model ids)
                //then three samplers (Minecraft's depth, Minecraft's lightmap, the model atlas)
                pipeline = new VkGraphicsPipeline(
                        "voxy:vk/lod_quads.vert", "voxy:vk/lod_quads.frag",
                        this.passColorFormat, VK_FORMAT_D32_SFLOAT,
                        true, true, compareOp, PUSH_CONSTANT_SIZE, 5, 3, VK_CULL_MODE_NONE);
                this.pipelines.put(key, pipeline);
                //Same shaders, blended, and writing no depth - translucent surfaces must not hide
                //the translucent surfaces behind them, only be hidden by opaque ones in front
                this.translucentPipelines.put(key, new VkGraphicsPipeline(
                        "voxy:vk/lod_quads.vert", "voxy:vk/lod_quads.frag",
                        this.passColorFormat, VK_FORMAT_D32_SFLOAT,
                        true, false, compareOp, PUSH_CONSTANT_SIZE, 5, 3, VK_CULL_MODE_NONE, true));
                Logger.info("[vk-lod] built LOD pipelines, colour format " + this.passColorFormat
                        + ", own depth target " + width + "x" + height);
            }
            var translucentPipeline = this.translucentPipelines.get(key);

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
                //Opaque first so it fills the depth buffer, then the blended pass on top of it
                this.recordDraws(cmd, pipeline, stack, width, height, false);
                this.recordDraws(cmd, translucentPipeline, stack, width, height, true);
                vkCmdEndRenderingKHR(cmd);

                //And our colour writes have to land before Minecraft's next pass touches the target
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            }
        } catch (Throwable t) {
            this.fail(t);
        }
    }

    private void recordDraws(VkCommandBuffer cmd, VkGraphicsPipeline pipeline, MemoryStack stack,
                             int width, int height, boolean translucentPass) {
        pipeline.bind(cmd, width, height);
        //Until the bakery has produced anything there is still a binding to fill, so a table of all
        //-1 and a one pixel atlas stand in. The shader reads -1 as 'no model, use the flat colour',
        //so the fallbacks are what make textured and untextured the same code path.
        boolean textured = this.modelsUsable();
        pipeline.bindResources(cmd,
                new VkBuffer[]{this.quadBuffer, this.colourBuffer,
                        this.tints.stateOffsets(), this.tints.colours(),
                        textured ? this.modelBakery.modelIdBuffer() : this.fallbackModelIds},
                new long[]{this.passDepthView, this.passLightmapView,
                        textured ? this.modelStore.atlas.imageView : this.fallbackAtlas.imageView},
                new VkSampler[]{this.depthSampler, this.lightSampler,
                        textured ? this.modelStore.sampler : this.lightSampler},
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
        push.putFloat(84, this.tints.biomeCount());
        //Alpha the fragment shader writes. Opaque geometry is fully opaque; the blended pass uses a
        //flat value rather than a per block one, because the map colour table carries no alpha.
        push.putFloat(88, translucentPass ? TRANSLUCENT_ALPHA : 1.0f);
        push.putFloat(92, 0f);
        for (var draw : this.draws) {
            //Each section's quads are packed opaque first, translucent after, so a pass is a
            //contiguous slice of the same buffer rather than a separate one
            int first = translucentPass ? draw.firstQuad() + draw.opaqueCount() : draw.firstQuad();
            int count = translucentPass ? draw.quadCount() - draw.opaqueCount() : draw.opaqueCount();
            if (count <= 0) {
                continue;
            }
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
            vkCmdDrawIndexed(cmd, count * 6, 1, 0, first * 4, 0);
            drawn++;
            quadsDrawn += count;
        }

        if (!this.loggedDraw && !translucentPass) {
            this.loggedDraw = true;
            Logger.info("[vk-lod] RENDERING " + drawn + " of " + this.draws.size()
                    + " sections after frustum culling (" + quadsDrawn + " opaque quads)");
        }
    }

    /**
     * Forces the rings to be rebuilt after the configured render distance changes.
     * <p>
     * The meshes themselves stay cached - a section looks the same whatever the range is - so this
     * only has to make the next frame decide it has moved far enough to rebuild.
     */
    public void onRenderDistanceChanged() {
        this.meshedCentreX = Double.NaN;
        this.meshedCentreZ = Double.NaN;
        Logger.info("[vk-lod] render distance changed, rebuilding to "
                + (int) lodRangeBlocks() + " blocks");
    }

    /** Fills the F3 screen with what this renderer is actually doing. Render thread only. */
    public void addDebugInfo(List<String> debug) {
        debug.add(String.format("Voxy-VK: %d sections, %d quads, %.1f MiB geometry",
                this.draws.size(), this.lastQuadCount, this.lastQuadCount * 8.0 / (1024 * 1024)));
        debug.add(String.format("Voxy-VK mesh: %.0f ms last, %s, range %d",
                this.lastMeshMillis, this.meshInFlight ? "rebuilding" : "idle",
                (int) lodRangeBlocks()));
        debug.add(String.format("Voxy-VK cache: %d sections, %dk quads, %d absent, %d dirty",
                this.meshCache.size(), this.cachedQuads / 1000,
                this.absentSections.size(), this.dirtySections.size()));
        debug.add("Voxy-VK models: " + (this.modelBakery == null ? "not started"
                : this.modelBakery.hasFailed() ? "failed, using map colours"
                : !this.modelBakery.isReady() ? "baking..."
                : this.modelBakery.publishedModels() + " baked"));
    }

    private int lastQuadCount;
    private double lastMeshMillis;

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
        //Dropped before the caches, so ingest cannot enqueue into a renderer being torn down
        try {
            this.world.setDirtyCallback(null);
        } catch (Throwable ignored) {
            //Teardown must not be blocked by the world already being gone
        }
        this.dirtySections.clear();
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
            for (var pipeline : this.translucentPipelines.values()) {
                pipeline.free();
            }
            this.translucentPipelines.clear();
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
            if (this.lightSampler != null) {
                this.lightSampler.free();
                this.lightSampler = null;
            }
            if (this.fallbackLightmap != null) {
                this.fallbackLightmap.free();
                this.fallbackLightmap = null;
            }
            if (this.fallbackModelIds != null) {
                this.fallbackModelIds.free();
                this.fallbackModelIds = null;
            }
            if (this.fallbackAtlas != null) {
                this.fallbackAtlas.free();
                this.fallbackAtlas = null;
            }
            if (this.modelBakery != null) {
                this.modelBakery.shutdown();
                this.modelBakery = null;
            }
            if (this.modelStore != null) {
                this.modelStore.free();
                this.modelStore = null;
            }
            if (this.tints != null) {
                this.tints.free();
                this.tints = null;
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
