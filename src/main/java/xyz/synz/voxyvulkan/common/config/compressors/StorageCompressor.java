package xyz.synz.voxyvulkan.common.config.compressors;

import xyz.synz.voxyvulkan.common.util.MemoryBuffer;

public interface StorageCompressor {
    MemoryBuffer compress(MemoryBuffer saveData);

    MemoryBuffer decompress(MemoryBuffer saveData);

    void close();
}
