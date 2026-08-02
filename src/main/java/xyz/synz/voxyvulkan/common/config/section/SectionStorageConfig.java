package xyz.synz.voxyvulkan.common.config.section;

import xyz.synz.voxyvulkan.common.config.ConfigBuildCtx;
import xyz.synz.voxyvulkan.common.config.Serialization;

public abstract class SectionStorageConfig {
    static {
        Serialization.CONFIG_TYPES.add(SectionStorageConfig.class);
    }

    public abstract SectionStorage build(ConfigBuildCtx ctx);
}
