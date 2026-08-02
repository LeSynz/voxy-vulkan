package xyz.synz.voxyvulkan.client.core.vk;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import xyz.synz.voxyvulkan.common.world.other.Mapper;

/**
 * Turns a saved {@link WorldSection} into voxy's packed quad format.
 * <p>
 * Two things keep the quad count sane, and both matter enormously:
 * <ul>
 *     <li><b>Neighbour awareness.</b> Treating "outside this section" as air makes every section
 *     emit a full 32x32 skin on all six sides - 6144 hidden faces each, which dominated everything
 *     else. Adjacent sections are consulted so those faces are correctly culled.</li>
 *     <li><b>Greedy merging.</b> A flat 32x32 floor is one quad rather than 1024. The packed format
 *     stores size in 4 bits per axis, so runs are capped at 16x16.</li>
 * </ul>
 * Packed layout matches {@code quad_format.glsl}: face[0..2], sizeX-1[3..6], sizeY-1[7..10],
 * posZ[11..15], posY[16..20], posX[21..25], stateId[26..41].
 */
public class VkSectionMesher {
    public static final int SECTION_WIDTH = 32;
    private static final int MAX_RUN = 16;//4 bits of size, stored as size-1

    /** Neighbour offsets per face, ordered axis*2 + direction. */
    public static final int[][] FACE_OFFSETS = {
            {-1, 0, 0}, {1, 0, 0},
            {0, -1, 0}, {0, 1, 0},
            {0, 0, -1}, {0, 0, 1},
    };

    private static int index(int x, int y, int z) {
        return (y << 10) | (z << 5) | x;
    }

    /**
     * @param neighbours data arrays of the six adjacent sections, indexed by face, null where the
     *                   neighbour is not loaded. A missing neighbour is treated as air, which
     *                   over-draws that boundary rather than leaving a hole.
     */
    private static boolean solid(long[] data, long[][] neighbours, int x, int y, int z) {
        if (x >= 0 && y >= 0 && z >= 0 && x < SECTION_WIDTH && y < SECTION_WIDTH && z < SECTION_WIDTH) {
            return !Mapper.isAir(data[index(x, y, z)]);
        }
        int face;
        if (x < 0) face = 0;
        else if (x >= SECTION_WIDTH) face = 1;
        else if (y < 0) face = 2;
        else if (y >= SECTION_WIDTH) face = 3;
        else if (z < 0) face = 4;
        else face = 5;

        long[] neighbour = neighbours == null ? null : neighbours[face];
        if (neighbour == null) {
            return false;
        }
        return !Mapper.isAir(neighbour[index(x & 31, y & 31, z & 31)]);
    }

    private static long packQuad(int face, int sizeX, int sizeY, int x, int y, int z, int stateId) {
        long quad = 0;
        quad |= (long) (face & 0x7);
        quad |= (long) ((sizeX - 1) & 0xF) << 3;
        quad |= (long) ((sizeY - 1) & 0xF) << 7;
        quad |= (long) (z & 0x1F) << 11;
        quad |= (long) (y & 0x1F) << 16;
        quad |= (long) (x & 0x1F) << 21;
        quad |= (long) (stateId & 0xFFFF) << 26;
        return quad;
    }

    public static LongArrayList mesh(long[] data, long[][] neighbours) {
        var quads = new LongArrayList();

        //Reused per slice: 0 means no face here, otherwise stateId+1
        int[] mask = new int[SECTION_WIDTH * SECTION_WIDTH];

        for (int face = 0; face < 6; face++) {
            int axis = face >> 1;
            int[] off = FACE_OFFSETS[face];

            for (int slice = 0; slice < SECTION_WIDTH; slice++) {
                java.util.Arrays.fill(mask, 0);

                for (int v = 0; v < SECTION_WIDTH; v++) {
                    for (int u = 0; u < SECTION_WIDTH; u++) {
                        int x = axis == 0 ? slice : u;
                        int y = axis == 0 ? u : (axis == 1 ? slice : v);
                        int z = axis == 2 ? slice : v;
                        if (axis == 1) {
                            x = u;
                            z = v;
                        }

                        long id = data[index(x, y, z)];
                        if (Mapper.isAir(id)) {
                            continue;
                        }
                        if (solid(data, neighbours, x + off[0], y + off[1], z + off[2])) {
                            continue;//hidden by its neighbour
                        }
                        mask[v * SECTION_WIDTH + u] = Mapper.getBlockId(id) + 1;
                    }
                }

                greedyEmit(quads, mask, face, axis, slice);
            }
        }
        return quads;
    }

    /** Merges the mask into as few rectangles as the packed format allows, then emits them. */
    private static void greedyEmit(LongArrayList quads, int[] mask, int face, int axis, int slice) {
        for (int v = 0; v < SECTION_WIDTH; v++) {
            for (int u = 0; u < SECTION_WIDTH; ) {
                int value = mask[v * SECTION_WIDTH + u];
                if (value == 0) {
                    u++;
                    continue;
                }

                //Extend along u while the state matches
                int width = 1;
                while (width < MAX_RUN && u + width < SECTION_WIDTH
                        && mask[v * SECTION_WIDTH + u + width] == value) {
                    width++;
                }

                //Extend along v while every cell of the row matches
                int height = 1;
                outer:
                while (height < MAX_RUN && v + height < SECTION_WIDTH) {
                    for (int k = 0; k < width; k++) {
                        if (mask[(v + height) * SECTION_WIDTH + u + k] != value) {
                            break outer;
                        }
                    }
                    height++;
                }

                //Consume the rectangle so it is not emitted again
                for (int dv = 0; dv < height; dv++) {
                    for (int du = 0; du < width; du++) {
                        mask[(v + dv) * SECTION_WIDTH + u + du] = 0;
                    }
                }

                int x = axis == 0 ? slice : u;
                int y = axis == 0 ? u : (axis == 1 ? slice : v);
                int z = axis == 2 ? slice : v;
                if (axis == 1) {
                    x = u;
                    z = v;
                }
                quads.add(packQuad(face, width, height, x, y, z, value - 1));
                u += width;
            }
        }
    }
}
