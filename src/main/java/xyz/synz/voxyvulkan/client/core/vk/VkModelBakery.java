package xyz.synz.voxyvulkan.client.core.vk;

import org.lwjgl.system.MemoryUtil;
import xyz.synz.voxyvulkan.client.core.model.ModelBakerySubsystem;
import xyz.synz.voxyvulkan.common.Logger;
import xyz.synz.voxyvulkan.common.world.other.Mapper;

/**
 * Drives voxy's model bakery on the Vulkan backend and keeps the block-state-to-model table the
 * shader needs.
 * <p>
 * On OpenGL the bakery is started and fed by {@code VoxyRenderSystem}, which never exists here, so
 * nothing would ever ask for a block to be baked. This asks for all of them - every state the world
 * mapper knows about - and republishes the resulting id table whenever more models land.
 * <p>
 * Construction happens off the render thread on purpose. The bakery's first act is to wait for
 * Minecraft's block atlas, which on this backend arrives through an asynchronous copy that only
 * completes if the render thread is free to keep drawing.
 */
public class VkModelBakery {
    private final Mapper mapper;
    private final VkModelStore store;
    private ModelBakerySubsystem bakery;

    /** Block id to model id, as a storage buffer the vertex shader indexes by the quad's state. */
    private VkBuffer modelIdBuffer;
    private int modelIdCapacity;
    private int publishedModels;
    private volatile boolean ready;
    private volatile boolean failed;

    public VkModelBakery(Mapper mapper, VkModelStore store) {
        this.mapper = mapper;
        this.store = store;
    }

    public boolean isReady() {
        return this.ready && !this.failed;
    }

    public boolean hasFailed() {
        return this.failed;
    }

    public VkBuffer modelIdBuffer() {
        return this.modelIdBuffer;
    }

    public int publishedModels() {
        return this.publishedModels;
    }

    /**
     * Builds the bakery and queues every known block state. Blocks until the atlas has been read
     * back, so this must not run on the render thread.
     */
    public void startBlocking() {
        try {
            this.bakery = new ModelBakerySubsystem(this.mapper, this.store);
            var states = this.mapper.getStateEntries();
            int requested = 0;
            for (var entry : states) {
                if (entry.id == 0) {
                    continue;//Air, which has nothing to bake
                }
                try {
                    this.bakery.requestBlockBake(entry.id);
                    requested++;
                } catch (Throwable t) {
                    //One unbakeable state must not stop the rest
                }
            }
            //Biomes drive the tint colours baked into each model
            for (var biome : this.mapper.getBiomeEntries()) {
                this.bakery.addBiome(biome);
            }
            Logger.info("[vk-model] bakery started, " + requested + " block states queued");
            this.ready = true;
        } catch (Throwable t) {
            this.failed = true;
            Logger.error("[vk-model] model bakery failed to start, staying on map colours", t);
        }
    }

    /**
     * Pushes finished bakes to the GPU and refreshes the id table. Render thread only, because the
     * uploads it drains touch GPU resources.
     */
    public void tick() {
        if (this.bakery == null || this.failed) {
            return;
        }
        try {
            this.bakery.tick(0);
            this.refreshModelIds();
        } catch (Throwable t) {
            //One bad model should not cost every other model its texture, so a few failures are
            //tolerated before the whole atlas is given up on
            this.uploadFailures++;
            Logger.error("[vk-model] model upload failed (" + this.uploadFailures + " of "
                    + MAX_UPLOAD_FAILURES + " tolerated)", t);
            if (this.uploadFailures >= MAX_UPLOAD_FAILURES) {
                this.failed = true;
                Logger.error("[vk-model] too many upload failures, staying on map colours");
            }
        }
    }

    private int uploadFailures;
    private static final int MAX_UPLOAD_FAILURES = 8;

    /**
     * Copies the factory's block-to-model table into a storage buffer.
     * <p>
     * Rewritten wholesale rather than incrementally: it is a few hundred kilobytes, it only changes
     * while models are still arriving, and tracking which entries moved would cost more than the
     * copy. Unbaked states stay -1, which the shader reads as 'fall back to the flat colour'.
     */
    private void refreshModelIds() {
        int[] mappings = this.bakery.factory._unsafeRawAccess();
        int stateCount = this.mapper.getBlockStateCount();
        if (stateCount <= 0) {
            return;
        }
        if (this.modelIdBuffer == null) {
            //Allocated once, at the size the factory's own table already assumes, and never grown.
            //Reallocating would mean freeing a buffer that frames still in flight are reading from,
            //and four megabytes is not worth a use after free to save.
            this.modelIdCapacity = mappings.length;
            this.modelIdBuffer = new VkBuffer((long) this.modelIdCapacity * Integer.BYTES, true);
            //Filled once so that anything past the known states reads as 'no model' rather than as
            //whatever the allocation happened to contain
            long pointer = this.modelIdBuffer.mappedPointer();
            for (int i = 0; i < this.modelIdCapacity; i++) {
                MemoryUtil.memPutInt(pointer + (long) i * Integer.BYTES, -1);
            }
            this.modelIdBuffer.flush();
        }

        //Only the states that exist, and only when the count actually moved. Rewriting the whole
        //million entry table every frame is four megabytes of pointless writes once baking is done.
        int limit = Math.min(this.modelIdCapacity, Math.min(stateCount, mappings.length));
        int baked = 0;
        for (int state = 0; state < limit; state++) {
            if (mappings[state] != -1) {
                baked++;
            }
        }
        if (baked == this.publishedModels) {
            return;
        }

        long pointer = this.modelIdBuffer.mappedPointer();
        for (int state = 0; state < limit; state++) {
            MemoryUtil.memPutInt(pointer + (long) state * Integer.BYTES, mappings[state]);
        }
        this.modelIdBuffer.flush();

        if (this.publishedModels == 0 || baked / 1000 != this.publishedModels / 1000) {
            Logger.info("[vk-model] " + baked + " of " + stateCount + " block states baked");
        }
        this.publishedModels = baked;
    }

    public void shutdown() {
        try {
            if (this.bakery != null) {
                this.bakery.shutdown();
                this.bakery = null;
            }
        } catch (Throwable t) {
            Logger.error("[vk-model] error shutting down the model bakery", t);
        }
        if (this.modelIdBuffer != null) {
            this.modelIdBuffer.free();
            this.modelIdBuffer = null;
        }
    }
}
