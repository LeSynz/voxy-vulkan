package xyz.synz.voxyvulkan.client.core.vk;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import xyz.synz.voxyvulkan.common.Logger;
import xyz.synz.voxyvulkan.common.world.other.Mapper;

import java.util.List;

/**
 * Per biome block colours, so grass, foliage and water are the colour of the biome they are in
 * rather than one shade worldwide.
 * <p>
 * Minecraft resolves these tints per block per position at render time, which a LOD renderer cannot
 * do - it has one quad covering many blocks and no world access. What it does have is the biome id
 * stored per voxel, so the colours are resolved once, up front, into a table the shader indexes:
 * a per state offset, and a run of one colour per biome at that offset.
 * <p>
 * The resolving logic is deliberately duplicated from {@code ModelFactory} rather than called into
 * it. That class is one of the OpenGL renderer's, and touching it at all risks running its class
 * initialisation on a backend where none of that exists.
 */
public final class VkBlockTints {
    private VkBlockTints() {}

    /** Marks a state as needing no tint at all, which is the overwhelming majority of them. */
    public static final int NO_TINT = -1;

    public record Tables(VkBuffer stateOffsets, VkBuffer colours, int tintedStates, int biomeCount) {
        public void free() {
            this.stateOffsets.free();
            this.colours.free();
        }
    }

    /**
     * A table that tints nothing, for when the real one cannot be built.
     * <p>
     * Exists so that a failure here degrades to untinted terrain rather than to no terrain. The
     * shader always reads these bindings, and a storage buffer binding cannot be left unbound, so
     * there has to be something valid to point at.
     */
    public static Tables empty() {
        var offsets = new VkBuffer(Integer.BYTES, true);
        MemoryUtil.memPutInt(offsets.mappedPointer(), NO_TINT);
        offsets.flush();
        var colours = new VkBuffer(Integer.BYTES, true);
        MemoryUtil.memPutInt(colours.mappedPointer(), 0xFFFFFFFF);
        colours.flush();
        return new Tables(offsets, colours, 0, 1);
    }

    /**
     * Resolves every tinted block state against every known biome.
     * <p>
     * Both buffers are always allocated, even when nothing is tinted, because a storage buffer
     * binding cannot be left empty and the shader always reads the offset table.
     */
    public static Tables build(Mapper mapper) {
        var stateEntries = mapper.getStateEntries();
        var biomeEntries = mapper.getBiomeEntries();

        int maxStateId = 0;
        for (var entry : stateEntries) {
            maxStateId = Math.max(maxStateId, entry.id);
        }
        int stateCount = maxStateId + 1;
        int biomeCount = Math.max(1, biomeEntries.length);

        //Resolve the biomes once. A biome the world knows about but the registry does not is left
        //null and falls back to no tint rather than failing the whole table.
        var biomes = new Biome[biomeCount];
        var level = Minecraft.getInstance().level;
        if (level != null) {
            var registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
            for (var entry : biomeEntries) {
                if (entry.id < 0 || entry.id >= biomeCount) {
                    continue;
                }
                try {
                    var found = registry.get(Identifier.parse(entry.biome));
                    if (found.isPresent()) {
                        biomes[entry.id] = found.orElseThrow().value();
                    }
                } catch (Throwable t) {
                    Logger.warn("[vk-lod] could not resolve biome '" + entry.biome + "' for tinting");
                }
            }
        }

        //First pass: which states are tinted at all, and where each one's colours will live
        int[] offsets = new int[stateCount];
        java.util.Arrays.fill(offsets, NO_TINT);
        var tinted = new java.util.ArrayList<BlockState>();
        var tintedIds = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (var entry : stateEntries) {
            List<BlockTintSource> sources;
            try {
                sources = getTintSources(entry.state);
            } catch (Throwable t) {
                continue;//A state that will not resolve simply goes untinted
            }
            if (sources == null || sources.isEmpty()) {
                continue;
            }
            offsets[entry.id] = tinted.size() * biomeCount;
            tinted.add(entry.state);
            tintedIds.add(entry.id);
        }

        var stateOffsets = new VkBuffer((long) stateCount * Integer.BYTES, true);
        long offsetPtr = stateOffsets.mappedPointer();
        for (int i = 0; i < stateCount; i++) {
            MemoryUtil.memPutInt(offsetPtr + (long) i * Integer.BYTES, offsets[i]);
        }
        stateOffsets.flush();

        int colourEntries = Math.max(1, tinted.size() * biomeCount);
        var colours = new VkBuffer((long) colourEntries * Integer.BYTES, true);
        long colourPtr = colours.mappedPointer();
        //White, so an unresolved entry multiplies to no change rather than to black
        for (int i = 0; i < colourEntries; i++) {
            MemoryUtil.memPutInt(colourPtr + (long) i * Integer.BYTES, 0xFFFFFFFF);
        }
        for (int i = 0; i < tinted.size(); i++) {
            var state = tinted.get(i);
            List<BlockTintSource> sources;
            try {
                sources = getTintSources(state);
            } catch (Throwable t) {
                continue;
            }
            if (sources == null) {
                continue;
            }
            for (int biomeId = 0; biomeId < biomeCount; biomeId++) {
                var biome = biomes[biomeId];
                if (biome == null) {
                    continue;
                }
                int rgb;
                try {
                    rgb = captureColourConstant(sources, state, biome);
                } catch (Throwable t) {
                    continue;
                }
                if (rgb == -1) {
                    continue;
                }
                //Stored ABGR so the shader unpacks with the same byte shifts as the block colours
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                MemoryUtil.memPutInt(colourPtr + ((long) i * biomeCount + biomeId) * Integer.BYTES,
                        0xFF000000 | (b << 16) | (g << 8) | r);
            }
        }
        colours.flush();

        Logger.info("[vk-lod] biome tints: " + tinted.size() + " tinted states across "
                + biomeCount + " biomes (" + colourEntries + " colours)");
        return new Tables(stateOffsets, colours, tinted.size(), biomeCount);
    }

    /**
     * Which block ids draw as translucent, so water and glass can be blended in a second pass
     * instead of being painted on as solid colour.
     * <p>
     * Minecraft has no state to layer lookup any more - the layer lives on each individual model
     * quad's material - so this collects a state's model parts and asks them. Fluids have no block
     * model at all and are handled separately, which is the case that actually matters here since
     * water is most of what a LOD renderer draws translucent.
     */
    public static boolean[] buildTranslucentStates(Mapper mapper) {
        var stateEntries = mapper.getStateEntries();
        int maxStateId = 0;
        for (var entry : stateEntries) {
            maxStateId = Math.max(maxStateId, entry.id);
        }
        var translucent = new boolean[maxStateId + 1];
        int count = 0;
        for (var entry : stateEntries) {
            boolean isTranslucent;
            try {
                isTranslucent = isTranslucent(entry.state);
            } catch (Throwable t) {
                continue;//Anything that will not resolve simply stays opaque
            }
            if (isTranslucent) {
                translucent[entry.id] = true;
                count++;
            }
        }
        Logger.info("[vk-lod] translucency: " + count + " of " + (maxStateId + 1)
                + " block states draw translucent");
        return translucent;
    }

    private static boolean isTranslucent(BlockState state) {
        var fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            //Water is translucent, lava is not - it is opaque and emissive
            return fluid.getType().isSame(net.minecraft.world.level.material.Fluids.WATER);
        }
        var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        if (model == null) {
            return false;
        }
        var parts = new java.util.ArrayList<BlockStateModelPart>();
        model.collectParts(new SingleThreadedRandomSource(42L), parts);
        for (var part : parts) {
            for (var direction : Direction.values()) {
                for (var quad : part.getQuads(direction)) {
                    if (quad.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT) {
                        return true;
                    }
                }
            }
            //Direction null is the bucket for quads that face no particular way
            for (var quad : part.getQuads(null)) {
                if (quad.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT) {
                    return true;
                }
            }
        }
        return false;
    }

    private static @Nullable List<BlockTintSource> getTintSources(BlockState block) {
        if (block.getBlock() instanceof LiquidBlock) {
            var tintSource = Minecraft.getInstance().getModelManager()
                    .getFluidStateModelSet().get(block.getFluidState()).tintSource();
            if (tintSource == null) {
                return null;
            }
            return List.of(tintSource);
        }
        var tints = Minecraft.getInstance().getBlockColors().getTintSources(block);
        if (tints.isEmpty()) {
            return null;
        }
        return tints;
    }

    /**
     * Asks a tint source what colour it would give this state in this biome, by handing it a world
     * that is entirely this one block and resolves every tint against the biome in question.
     */
    private static int captureColourConstant(List<BlockTintSource> tintSources, BlockState state, Biome biome) {
        var getter = new BlockAndTintGetter() {
            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return LevelLightEngine.EMPTY;
            }

            @Override
            public CardinalLighting cardinalLighting() {
                return CardinalLighting.DEFAULT;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                return colorResolver.getColor(biome, 0, 0);
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinY() {
                return 0;
            }
        };
        for (var source : tintSources) {
            if (source != null) {
                int colour = source.colorInWorld(state, getter, BlockPos.ZERO);
                if (colour != -1) {
                    return colour;
                }
            }
        }
        return -1;
    }
}
