package xyz.synz.voxyvulkan.client.core.vk;

import org.lwjgl.system.MemoryUtil;
import xyz.synz.voxyvulkan.client.core.model.IModelSink;
import xyz.synz.voxyvulkan.client.core.model.ModelFactory;
import xyz.synz.voxyvulkan.client.core.model.ModelStore;
import xyz.synz.voxyvulkan.common.Logger;
import xyz.synz.voxyvulkan.common.util.MemoryBuffer;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

/**
 * Where baked block models live on the Vulkan side: the texture atlas plus the two buffers the
 * shader reads model data and per biome colours from.
 * <p>
 * The atlas is laid out exactly as the OpenGL store lays it out, because the shader arithmetic that
 * finds a face inside it is shared. A model occupies one tile of a 256 by 256 grid, and each tile
 * holds that model's six faces as a 3 by 2 arrangement of {@code MODEL_TEXTURE_SIZE} squares.
 * <p>
 * Uploads go straight into host visible memory rather than through a staging stream. The OpenGL path
 * needs {@code UploadStream} because its buffers live in device only memory; ours are mapped, so a
 * memcpy is the whole operation.
 */
public class VkModelStore implements IModelSink {
    /** Models addressable by the 16 bit id the packed quad format carries. */
    private static final int MAX_MODELS = 1 << 16;

    public final VkBuffer modelBuffer;
    public final VkBuffer modelColourBuffer;
    public final VkTexture atlas;
    public final VkSampler sampler;

    public VkModelStore() {
        this.modelBuffer = new VkBuffer((long) ModelStore.MODEL_SIZE * MAX_MODELS, true);
        this.modelColourBuffer = new VkBuffer(4L * MAX_MODELS, true);

        int width = ModelFactory.MODEL_TEXTURE_SIZE * 3 * 256;
        int height = ModelFactory.MODEL_TEXTURE_SIZE * 2 * 256;
        this.atlas = new VkTexture(VK_FORMAT_R8G8B8A8_UNORM, ModelFactory.LAYERS, width, height);
        //VkTexture settles in SHADER_READ_ONLY_OPTIMAL, but this is bound alongside images borrowed
        //from Minecraft, and one descriptor write declares a single layout for all of them. Moved to
        //GENERAL now so it agrees even before the first model has been uploaded.
        VkContext.get().submitBlocking(cmd ->
                this.atlas.transition(cmd, org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL));
        //Nearest magnification keeps blocks crisp, linear between mips stops distant terrain
        //shimmering. Clamped to the atlas's own mip count so a distant LOD cannot sample past it.
        this.sampler = VkSampler.blockSampler(ModelFactory.LAYERS - 1);

        Logger.info("[vk-model] model store ready: " + width + "x" + height + " atlas, "
                + ModelFactory.LAYERS + " mips, "
                + (((long) width * height * 4L) >> 20) + " MiB base level");
    }

    @Override
    public void uploadModel(int modelId, MemoryBuffer data) {
        if (modelId < 0 || modelId >= MAX_MODELS) {
            Logger.error("[vk-model] model id out of range: " + modelId);
            return;
        }
        data.cpyTo(this.modelBuffer.mappedPointer() + (long) modelId * ModelStore.MODEL_SIZE);
    }

    @Override
    public void uploadModelField(int modelId, int byteOffset, int value) {
        if (modelId < 0 || modelId >= MAX_MODELS) {
            return;
        }
        MemoryUtil.memPutInt(this.modelBuffer.mappedPointer()
                + (long) modelId * ModelStore.MODEL_SIZE + byteOffset, value);
    }

    @Override
    public void uploadBiomeColours(int firstIndex, MemoryBuffer data) {
        long offset = firstIndex * 4L;
        if (offset + data.size > this.modelColourBuffer.size()) {
            Logger.error("[vk-model] biome colour upload would overrun the buffer, dropping it");
            return;
        }
        data.cpyTo(this.modelColourBuffer.mappedPointer() + offset);
    }

    /**
     * Texture uploads are staged and sent in one batch per flush.
     * <p>
     * Submitting each region on its own means a command buffer, a fence and a wait for every mip of
     * every face of every model. At a few thousand block states that is tens of thousands of round
     * trips, each one stalling until the GPU catches up, which is far slower than the baking itself.
     */
    private static final int STAGED_MODELS = 512;
    private static final int MAX_REGIONS = STAGED_MODELS * ModelFactory.LAYERS;
    /**
     * Bytes one model actually contributes: the mip levels that are uploaded, and no more.
     * <p>
     * Derived from the same loop that writes them rather than from the bakery's buffer size. The
     * bakery sizes its buffer with every mip down to a single pixel, but only {@code LAYERS} of them
     * are ever uploaded - so using that figure here over-estimates each model, lets one more through
     * than the region array has room for, and overruns it by exactly one model's worth.
     */
    private static final int MODEL_STAGED_BYTES = computeStagedBytes();
    private VkBuffer staging;
    private final int[] pendingRegions = new int[MAX_REGIONS * 6];
    private int pendingRegionCount;
    private int stagedBytes;

    private static int computeStagedBytes() {
        int total = 0;
        for (int lvl = 0; lvl < ModelFactory.LAYERS; lvl++) {
            total += ((ModelFactory.MODEL_TEXTURE_SIZE * 3) >> lvl)
                    * ((ModelFactory.MODEL_TEXTURE_SIZE * 2) >> lvl) * 4;
        }
        return total;
    }

    @Override
    public void uploadModelTexture(int modelId, long address) {
        if (modelId < 0 || modelId >= MAX_MODELS) {
            return;
        }
        if (this.staging == null) {
            this.staging = new VkBuffer((long) STAGED_MODELS * MODEL_STAGED_BYTES,
                    org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        }
        //Flushed early if this model would not fit, so the staging buffer stays a fixed size. Both
        //limits are checked: bytes and region slots run out together only if the two constants agree
        //exactly, and relying on that is what overran the array the first time.
        if (this.pendingRegionCount + ModelFactory.LAYERS > MAX_REGIONS
                || this.stagedBytes + MODEL_STAGED_BYTES > this.staging.size()) {
            this.flushTextureBatch();
        }

        int x = (modelId & 0xFF) * ModelFactory.MODEL_TEXTURE_SIZE * 3;
        int y = ((modelId >> 8) & 0xFF) * ModelFactory.MODEL_TEXTURE_SIZE * 2;
        long source = address;
        for (int lvl = 0; lvl < ModelFactory.LAYERS; lvl++) {
            int levelWidth = (ModelFactory.MODEL_TEXTURE_SIZE * 3) >> lvl;
            int levelHeight = (ModelFactory.MODEL_TEXTURE_SIZE * 2) >> lvl;
            int levelBytes = levelWidth * levelHeight * 4;

            MemoryUtil.memCopy(source, this.staging.mappedPointer() + this.stagedBytes, levelBytes);
            int base = this.pendingRegionCount * 6;
            this.pendingRegions[base] = lvl;
            this.pendingRegions[base + 1] = x >> lvl;
            this.pendingRegions[base + 2] = y >> lvl;
            this.pendingRegions[base + 3] = levelWidth;
            this.pendingRegions[base + 4] = levelHeight;
            this.pendingRegions[base + 5] = this.stagedBytes;
            this.pendingRegionCount++;
            this.stagedBytes += levelBytes;

            //Each level is a quarter of the previous one, matching how the bakery packed them
            source += (long) (ModelFactory.MODEL_TEXTURE_SIZE * ModelFactory.MODEL_TEXTURE_SIZE * 3 * 2 * 4) >> (lvl << 1);
        }
    }

    private void flushTextureBatch() {
        if (this.pendingRegionCount == 0) {
            return;
        }
        this.regionsAtLastFlushCheck = 0;
        this.staging.flush();
        //Left in GENERAL because that is how it is bound - the descriptor write declares one layout
        //for every image, and the ones borrowed from Minecraft are all GENERAL.
        this.atlas.uploadRegions(this.staging, this.pendingRegions, this.pendingRegionCount,
                org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL);
        this.pendingRegionCount = 0;
        this.stagedBytes = 0;
    }

    @Override
    public void flushUploads() {
        //A batch is sent when it fills, or once baking has gone quiet for a tick. Flushing whatever
        //has accumulated every single tick would mean a blocking submit per frame for as long as
        //models keep arriving, each one stalling the render thread on a fence for a handful of
        //models - the batching would buy nothing.
        if (this.pendingRegionCount > 0 && this.pendingRegionCount == this.regionsAtLastFlushCheck) {
            this.flushTextureBatch();
        }
        this.regionsAtLastFlushCheck = this.pendingRegionCount;
        this.modelBuffer.flush();
        this.modelColourBuffer.flush();
    }

    private int regionsAtLastFlushCheck;

    public void free() {
        this.modelBuffer.free();
        this.modelColourBuffer.free();
        if (this.staging != null) {
            this.staging.free();
            this.staging = null;
        }
        this.atlas.free();
        this.sampler.free();
    }
}
