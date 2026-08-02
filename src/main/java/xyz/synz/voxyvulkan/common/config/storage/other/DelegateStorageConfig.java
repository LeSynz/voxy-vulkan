package xyz.synz.voxyvulkan.common.config.storage.other;

import xyz.synz.voxyvulkan.common.config.storage.StorageConfig;

import java.util.List;

public abstract class DelegateStorageConfig extends StorageConfig {
    public StorageConfig delegate;

    @Override
    public List<StorageConfig> getChildStorageConfigs() {
        return List.of(this.delegate);
    }
}
