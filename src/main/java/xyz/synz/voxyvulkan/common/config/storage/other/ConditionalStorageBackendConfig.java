package xyz.synz.voxyvulkan.common.config.storage.other;

import xyz.synz.voxyvulkan.common.config.ConfigBuildCtx;
import xyz.synz.voxyvulkan.common.config.storage.StorageBackend;
import xyz.synz.voxyvulkan.common.config.storage.StorageConfig;
import org.apache.commons.lang3.NotImplementedException;

//A conditional storage backend depending on build time config, this enables conditional backends depending on the
// dimension as an example
public class ConditionalStorageBackendConfig extends StorageConfig {
    @Override
    public StorageBackend build(ConfigBuildCtx ctx) {
        throw new NotImplementedException();
    }

    public static String getConfigTypeName() {
        return "ConditionalConfig";
    }
}
