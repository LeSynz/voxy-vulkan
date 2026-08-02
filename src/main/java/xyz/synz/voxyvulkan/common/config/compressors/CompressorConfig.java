package xyz.synz.voxyvulkan.common.config.compressors;

import xyz.synz.voxyvulkan.common.config.ConfigBuildCtx;
import xyz.synz.voxyvulkan.common.config.Serialization;

public abstract class CompressorConfig {
    static {
        Serialization.CONFIG_TYPES.add(CompressorConfig.class);
    }

    public abstract StorageCompressor build(ConfigBuildCtx ctx);
}
