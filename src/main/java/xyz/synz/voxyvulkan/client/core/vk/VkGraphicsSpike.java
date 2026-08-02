package xyz.synz.voxyvulkan.client.core.vk;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import xyz.synz.voxyvulkan.common.Logger;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Graphics interop spike, stage two.
 * <p>
 * Stage one proved a draw recorded into Minecraft's render pass reaches the screen. This draws a
 * lattice of cubes on the 16 block section grid using Minecraft's own camera matrices, in camera
 * relative space the way Sodium's terrain rendering works. That exercises everything real LOD
 * geometry needs - world anchoring, the projection convention, push constants, instancing and
 * depth testing against terrain Minecraft has already drawn.
 * <p>
 * The draw is recorded from inside Sodium's terrain hook, while the terrain pass is still open, so
 * it lands at the correct point in the frame. Run with {@code -Dvoxy.vkSpike=true}.
 */
public class VkGraphicsSpike {
    private static final int GRID_X = 9;
    private static final int GRID_Y = 5;
    private static final int GRID_Z = 9;
    private static final int INSTANCES = GRID_X * GRID_Y * GRID_Z;
    private static final float SECTION_SIZE = 16.0f;
    private static final int PUSH_CONSTANT_SIZE = 80;

    private static final Map<Long, VkGraphicsPipeline> PIPELINES = new HashMap<>();
    private static final Matrix4f VIEW_PROJ = new Matrix4f();
    private static boolean FAILED;
    private static boolean LOGGED_SUCCESS;

    /**
     * Called from Sodium's terrain pass, with Minecraft's render pass still open and its command
     * buffer recording. Must not throw - a failure here would take the whole frame down.
     */
    public static void drawInTerrainPass(Matrix4fc projection, Matrix4fc modelView,
                                         double cameraX, double cameraY, double cameraZ) {
        if (FAILED) {
            return;
        }
        try {
            var pass = VkRenderPassTracker.currentPass();
            if (pass == null || !pass.hasDepth || !VkRenderPassTracker.hasUsableTarget()) {
                return;
            }
            var cmd = pass.commandBuffer;
            if (cmd == null || pass.outputWidth <= 0 || pass.outputHeight <= 0) {
                return;
            }

            int colorFormat = VkRenderPassTracker.colorFormat();
            int depthFormat = VkRenderPassTracker.depthFormat();

            long key = ((long) colorFormat << 32) | (depthFormat & 0xFFFFFFFFL);
            var pipeline = PIPELINES.get(key);
            if (pipeline == null) {
                //Match Minecraft's depth convention, otherwise every fragment is silently discarded
                boolean reverseZ = DepthStencilState.DEFAULT.depthTest().equals(CompareOp.GREATER_THAN_OR_EQUAL);
                int compareOp = reverseZ ? VK_COMPARE_OP_GREATER_OR_EQUAL : VK_COMPARE_OP_LESS_OR_EQUAL;
                //Depth write off so we do not disturb the depth buffer later passes rely on
                pipeline = new VkGraphicsPipeline(
                        "voxy:vk/spike_sections.vert", "voxy:vk/spike_sections.frag",
                        colorFormat, depthFormat,
                        true, false, compareOp, PUSH_CONSTANT_SIZE);
                PIPELINES.put(key, pipeline);
                Logger.info("[vk-gfx-spike] built lattice pipeline in pass '" + safeLabel(pass)
                        + "', reverseZ=" + reverseZ);
            }

            VIEW_PROJ.set(projection).mul(modelView);

            //Anchor to the section grid so the lattice stays put in the world as the camera moves
            double originX = Math.floor(cameraX / SECTION_SIZE) * SECTION_SIZE;
            double originY = Math.floor(cameraY / SECTION_SIZE) * SECTION_SIZE;
            double originZ = Math.floor(cameraZ / SECTION_SIZE) * SECTION_SIZE;

            //Sodium renders camera relative, so these are offsets from the camera, not world coords
            float baseX = (float) (originX - cameraX) - (GRID_X / 2) * SECTION_SIZE;
            float baseY = (float) (originY - cameraY) - (GRID_Y / 2) * SECTION_SIZE;
            float baseZ = (float) (originZ - cameraZ) - (GRID_Z / 2) * SECTION_SIZE;

            pipeline.bind(cmd, pass.outputWidth, pass.outputHeight);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var push = stack.malloc(PUSH_CONSTANT_SIZE);
                VIEW_PROJ.get(0, push);
                push.putFloat(64, baseX).putFloat(68, baseY).putFloat(72, baseZ).putFloat(76, SECTION_SIZE);
                pipeline.pushConstants(cmd, push);
            }
            vkCmdDraw(cmd, 36, INSTANCES, 0, 0);

            if (!LOGGED_SUCCESS) {
                LOGGED_SUCCESS = true;
                Logger.info("[vk-gfx-spike] PASSED - drew " + INSTANCES + " world anchored cubes inside the terrain "
                        + "pass '" + safeLabel(pass) + "'. Cubes should be occluded by terrain and stay fixed as you move.");
            }
        } catch (Throwable t) {
            FAILED = true;
            Logger.error("[vk-gfx-spike] FAILED recording lattice draw, disabling graphics spike", t);
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
