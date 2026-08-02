package xyz.synz.voxyvulkan.common.config.storage.other;

import xyz.synz.voxyvulkan.common.config.ConfigBuildCtx;
import xyz.synz.voxyvulkan.common.config.compressors.CompressorConfig;
import xyz.synz.voxyvulkan.common.config.compressors.StorageCompressor;
import xyz.synz.voxyvulkan.common.config.storage.StorageBackend;
import xyz.synz.voxyvulkan.common.util.MemoryBuffer;

//Compresses the section data
public class CompressionStorageAdaptor extends DelegatingStorageAdaptor {
    private final StorageCompressor compressor;
    public CompressionStorageAdaptor(StorageCompressor compressor, StorageBackend delegate) {
        super(delegate);
        this.compressor = compressor;
    }


    //TODO: figure out a nicer way w.r.t scratch buffer shit
    @Override
    public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
        var data = this.delegate.getSectionData(key, scratch);
        if (data == null) {
            return null;
        }
        return this.compressor.decompress(data);
    }

    @Override
    public void setSectionData(long key, MemoryBuffer data) {
        var cdata = this.compressor.compress(data);
        this.delegate.setSectionData(key, cdata);
        //Note that the data isnt freed (data cache in the compressors are used)
    }

    @Override
    public void close() {
        this.compressor.close();
        super.close();
    }

    public static class Config extends DelegateStorageConfig {
        public CompressorConfig compressor;

        @Override
        public StorageBackend build(ConfigBuildCtx ctx) {
            return new CompressionStorageAdaptor(this.compressor.build(ctx), this.delegate.build(ctx));
        }

        public static String getConfigTypeName() {
            return "CompressionAdaptor";
        }
    }
}
