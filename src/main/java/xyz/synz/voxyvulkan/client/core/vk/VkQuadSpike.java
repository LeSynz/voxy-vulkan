package xyz.synz.voxyvulkan.client.core.vk;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import xyz.synz.voxyvulkan.common.Logger;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The end of the feasibility chain: renders quads in voxy's real packed format through the Vulkan
 * path, decoded by voxy's own {@code quad_format.glsl}.
 * <p>
 * This is the shape the actual LOD renderer takes. Voxy's geometry is GPU driven - there is no
 * vertex buffer. Quads are 64 bit packed values in a storage buffer, four vertices each, drawn
 * through a shared quad index buffer. Proving that path works end to end means the remaining work
 * is feeding it real section data rather than solving anything new.
 * <p>
 * Run with {@code -Dvoxy.vkSpike=true}.
 */
public class VkQuadSpike {
    private static final int SECTION_SIZE = 32;//voxy sections are 32 blocks, quad positions are 5 bit
    private static final int PUSH_CONSTANT_SIZE = 80;

    private static final Map<Long, VkGraphicsPipeline> PIPELINES = new HashMap<>();
    private static final Matrix4f VIEW_PROJ = new Matrix4f();

    private static VkBuffer quadBuffer;
    private static VkBuffer indexBuffer;
    private static int quadCount;
    private static boolean FAILED;
    private static boolean LOGGED_SUCCESS;

    /**
     * Packs a quad exactly as voxy's format does, so the shader's decode is the real one.
     * Layout: face[0..2], sizeX-1[3..6], sizeY-1[7..10], posZ[11..15], posY[16..20], posX[21..25],
     * stateId[26..41], biomeId[46..54], lightId[55..62].
     */
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

    private static void buildGeometry() {
        //A rolling heightfield of upward facing quads across one section, so it reads as terrain
        //rather than an abstract pattern, and exercises a realistic quad count.
        int[] heights = new int[SECTION_SIZE * SECTION_SIZE];
        int count = 0;
        for (int x = 0; x < SECTION_SIZE; x++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                double h = 8 + 6 * Math.sin(x * 0.35) * Math.cos(z * 0.35);
                heights[x * SECTION_SIZE + z] = Math.max(0, Math.min(SECTION_SIZE - 1, (int) h));
                count++;
            }
        }

        quadCount = count;
        long bytes = (long) quadCount * Long.BYTES;
        var staging = MemoryUtil.memAlloc((int) bytes);
        try {
            int i = 0;
            for (int x = 0; x < SECTION_SIZE; x++) {
                for (int z = 0; z < SECTION_SIZE; z++) {
                    int y = heights[x * SECTION_SIZE + z];
                    //face 2 = +Y in voxy's axis*2+direction encoding
                    staging.putLong(i * Long.BYTES, packQuad(2, 1, 1, x, y, z, x * 7 + z * 13));
                    i++;
                }
            }
            quadBuffer = new VkBuffer(bytes, true);
            MemoryUtil.memCopy(MemoryUtil.memAddress(staging), quadBuffer.mappedPointer(), bytes);
            quadBuffer.flush();
        } finally {
            MemoryUtil.memFree(staging);
        }

        //Shared quad index buffer: 4 vertices per quad, 6 indices, same pattern as SharedIndexBuffer
        int indices = quadCount * 6;
        var indexStaging = MemoryUtil.memAlloc(indices * Integer.BYTES);
        try {
            for (int q = 0; q < quadCount; q++) {
                int v = q * 4;
                int o = q * 6 * Integer.BYTES;
                indexStaging.putInt(o, v);
                indexStaging.putInt(o + 4, v + 1);
                indexStaging.putInt(o + 8, v + 2);
                indexStaging.putInt(o + 12, v + 2);
                indexStaging.putInt(o + 16, v + 3);
                indexStaging.putInt(o + 20, v);
            }
            indexBuffer = new VkBuffer((long) indices * Integer.BYTES,
                    VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
            MemoryUtil.memCopy(MemoryUtil.memAddress(indexStaging), indexBuffer.mappedPointer(),
                    (long) indices * Integer.BYTES);
            indexBuffer.flush();
        } finally {
            MemoryUtil.memFree(indexStaging);
        }

        Logger.info("[vk-quad-spike] built " + quadCount + " quads in voxy's packed format ("
                + bytes + " bytes) and " + indices + " indices");
    }

    private static boolean loggedEntry;
    private static boolean deferred;
    private static float baseX, baseY, baseZ;

    /**
     * Called from Sodium's terrain hook. Minecraft may already have submitted the terrain pass by
     * this point, in which case there is no open pass to record into and we fall back to drawing
     * during the next pass submission instead.
     */
    public static void drawInTerrainPass(Matrix4fc projection, Matrix4fc modelView,
                                         double cameraX, double cameraY, double cameraZ) {
        if (FAILED) {
            return;
        }
        try {
            var pass = VkRenderPassTracker.currentPass();
            if (!loggedEntry) {
                loggedEntry = true;
                Logger.info("[vk-quad-spike] terrain hook fired. activePass=" + (pass != null)
                        + " usableTarget=" + VkRenderPassTracker.hasUsableTarget()
                        + (pass != null ? (" hasDepth=" + pass.hasDepth) : ""));
            }

            //Matrices are only conveniently available here, so capture them regardless of where we draw
            VIEW_PROJ.set(projection).mul(modelView);
            double oX = Math.floor(cameraX / SECTION_SIZE) * SECTION_SIZE;
            double oY = Math.floor(cameraY / SECTION_SIZE) * SECTION_SIZE;
            double oZ = Math.floor(cameraZ / SECTION_SIZE) * SECTION_SIZE;
            baseX = (float) (oX - cameraX);
            baseY = (float) (oY - cameraY);
            baseZ = (float) (oZ - cameraZ);

            if (pass == null || !pass.hasDepth || !VkRenderPassTracker.hasUsableTarget()
                    || pass.commandBuffer == null) {
                //No open pass here, so let the next submission carry the draw
                deferred = true;
                return;
            }
            record(pass);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * Sodium's terrain hook runs with no blaze3d pass open, so the draw is recorded as the world's
     * 'Terrain' pass is submitted instead. Targeting that pass by name rather than "the next pass
     * with depth" keeps the geometry at the right point in the frame - previously it landed in
     * whatever came next, which was clouds, particles or even another mod's overlay pass.
     */
    public static void onSubmitRenderPass(VulkanRenderPass pass) {
        if (FAILED || !deferred) {
            return;
        }
        try {
            if (!pass.hasDepth || !VkRenderPassTracker.hasUsableTarget() || pass.commandBuffer == null) {
                return;
            }
            if (!"Terrain".equals(safeLabel(pass))) {
                return;
            }
            deferred = false;
            record(pass);
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void fail(Throwable t) {
        FAILED = true;
        deferred = false;
        Logger.error("[vk-quad-spike] FAILED, disabling quad spike", t);
    }

    private static void record(VulkanRenderPass pass) {
        try {
            var cmd = pass.commandBuffer;
            if (cmd == null || pass.outputWidth <= 0 || pass.outputHeight <= 0) {
                return;
            }

            if (quadBuffer == null) {
                buildGeometry();
            }

            int colorFormat = VkRenderPassTracker.colorFormat();
            int depthFormat = VkRenderPassTracker.depthFormat();
            long key = ((long) colorFormat << 32) | (depthFormat & 0xFFFFFFFFL);
            var pipeline = PIPELINES.get(key);
            if (pipeline == null) {
                boolean reverseZ = DepthStencilState.DEFAULT.depthTest().equals(CompareOp.GREATER_THAN_OR_EQUAL);
                int compareOp = reverseZ ? VK_COMPARE_OP_GREATER_OR_EQUAL : VK_COMPARE_OP_LESS_OR_EQUAL;
                pipeline = new VkGraphicsPipeline(
                        "voxy:vk/spike_quads.vert", "voxy:vk/spike_quads.frag",
                        colorFormat, depthFormat,
                        true, false, compareOp, PUSH_CONSTANT_SIZE, 1);
                PIPELINES.put(key, pipeline);
                Logger.info("[vk-quad-spike] built quad pipeline in pass '" + safeLabel(pass) + "'");
            }

            pipeline.bind(cmd, pass.outputWidth, pass.outputHeight);
            pipeline.bindBuffers(cmd, quadBuffer);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var push = stack.malloc(PUSH_CONSTANT_SIZE);
                VIEW_PROJ.get(0, push);
                push.putFloat(64, baseX).putFloat(68, baseY).putFloat(72, baseZ).putFloat(76, 0.0f);
                pipeline.pushConstants(cmd, push);
            }
            vkCmdBindIndexBuffer(cmd, indexBuffer.buffer, 0, VK_INDEX_TYPE_UINT32);
            vkCmdDrawIndexed(cmd, quadCount * 6, 1, 0, 0, 0);

            if (!LOGGED_SUCCESS) {
                LOGGED_SUCCESS = true;
                Logger.info("[vk-quad-spike] PASSED - drew " + quadCount + " quads decoded from voxy's packed "
                        + "format via quad_format.glsl in pass '" + safeLabel(pass) + "'. This is the LOD geometry path.");
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static String safeLabel(VulkanRenderPass pass) {
        try {
            return pass.label == null ? "<none>" : String.valueOf(pass.label.get());
        } catch (Throwable t) {
            return "<unavailable>";
        }
    }
}
