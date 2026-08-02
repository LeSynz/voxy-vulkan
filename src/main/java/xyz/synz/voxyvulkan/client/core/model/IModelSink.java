package xyz.synz.voxyvulkan.client.core.model;

import xyz.synz.voxyvulkan.common.util.MemoryBuffer;

/**
 * Where a finished model bake goes.
 * <p>
 * The bakery itself is almost entirely CPU - a software rasteriser turning block models into face
 * textures, plus mip generation and a good deal of intricate bit packing - and none of that cares
 * which graphics API is underneath. Only the last step does. Naming that step as an interface lets
 * the OpenGL and Vulkan renderers share every part of the bakery that is actually hard, instead of
 * the Vulkan side reimplementing it and inevitably diverging.
 */
public interface IModelSink {
    /** Stores one model's {@link ModelStore#MODEL_SIZE} byte struct at its slot. */
    void uploadModel(int modelId, MemoryBuffer data);

    /** Stores a run of per biome colour ints, starting at the given int index. */
    void uploadBiomeColours(int firstIndex, MemoryBuffer data);

    /**
     * Stores a model's face textures: a 3 by 2 grid of {@code MODEL_TEXTURE_SIZE} tiles, followed
     * by each mip level of the same, packed back to back from {@code address}.
     */
    void uploadModelTexture(int modelId, long address);

    /**
     * Patches a single int inside a model's already stored struct.
     * <p>
     * Adding a biome rewrites one field of every model that uses biome colours, rather than
     * rebuilding those models, so the whole struct does not have to be kept around to re-send.
     */
    void uploadModelField(int modelId, int byteOffset, int value);

    /** Called before a batch of uploads, for backends that need to set pixel store state. */
    default void beginUploads() {}

    /** Called once a batch of uploads is done, for backends that need to flush. */
    default void flushUploads() {}
}
