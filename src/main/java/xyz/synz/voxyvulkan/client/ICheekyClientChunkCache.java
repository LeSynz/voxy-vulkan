package xyz.synz.voxyvulkan.client;

import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

public interface ICheekyClientChunkCache {
    @Nullable
    LevelChunk voxy$cheekyGetChunk(int x, int z);
}
