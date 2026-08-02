package xyz.synz.voxyvulkan.client.core.vk;

import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import xyz.synz.voxyvulkan.common.Logger;

import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;

/**
 * Tracks Minecraft's currently open render pass and its attachment formats.
 * <p>
 * Voxy's geometry has to be recorded <em>into</em> the terrain pass, while it is still open. That
 * means reaching the encoder from inside Sodium's terrain hook rather than waiting for the pass to
 * be submitted - by submission time the terrain pass is already gone and the next pass to come
 * along is something unrelated like clouds or particles.
 * <p>
 * Formats have to come from the live pass because a dynamic rendering pipeline is only compatible
 * with a pass whose attachment formats match exactly.
 */
public class VkRenderPassTracker {
    private static VulkanCommandEncoder encoder;
    private static int colorFormat;
    private static int depthFormat;
    //The attachments themselves, not just their formats. LOD geometry is drawn in a pass of our own
    //once Minecraft's terrain pass has ended, which means re-declaring its colour attachment as
    //ours, and sampling its depth attachment to find out where vanilla terrain already covers the
    //screen. Both need the actual image views Minecraft rendered with.
    private static long colorView = VK_NULL_HANDLE;
    private static long depthView = VK_NULL_HANDLE;

    //Pass inventory logging. It identified the 'Terrain' pass, so it is off unless asked for -
    //re-enable with -Dvoxy.vkLogPasses=true when hunting for a different pass to hook.
    private static final java.util.Set<String> SEEN = new java.util.HashSet<>();
    private static boolean logPasses = System.getProperty("voxy.vkLogPasses", "false").equalsIgnoreCase("true");

    public static void setEncoder(VulkanCommandEncoder value) {
        encoder = value;
    }

    public static void onCreateRenderPass(RenderPassDescriptor descriptor) {
        colorFormat = 0;
        depthFormat = 0;
        colorView = VK_NULL_HANDLE;
        depthView = VK_NULL_HANDLE;

        if (logPasses) {
            try {
                String label = descriptor.label() == null ? "<none>" : String.valueOf(descriptor.label().get());
                int colors = descriptor.colorAttachments() == null ? 0 : descriptor.colorAttachments().size();
                boolean depth = descriptor.depthAttachment() != null && descriptor.depthAttachment().textureView() != null;
                if (SEEN.add(label)) {
                    Logger.info("[vk-pass] '" + label + "' colorAttachments=" + colors + " depth=" + depth);
                    if (SEEN.size() > 40) {
                        logPasses = false;
                    }
                }
            } catch (Throwable ignored) {
                //Diagnostics must never break a frame
            }
        }

        var colorAttachments = descriptor.colorAttachments();
        //Our pipelines declare exactly one colour attachment, anything else is incompatible
        if (colorAttachments != null && colorAttachments.size() == 1) {
            var view = colorAttachments.getFirst().textureView();
            if (view != null && view.texture() != null) {
                colorFormat = VulkanConst.toVk(view.texture().getFormat());
                if (view instanceof VulkanGpuTextureView vk) {
                    colorView = vk.vkImageView();
                }
            }
        }
        var depth = descriptor.depthAttachment();
        if (depth != null && depth.textureView() != null && depth.textureView().texture() != null) {
            depthFormat = VulkanConst.toVk(depth.textureView().texture().getFormat());
            if (depth.textureView() instanceof VulkanGpuTextureView vk) {
                depthView = vk.vkImageView();
            }
        }
    }

    /** The pass Minecraft currently has open, or null if none is active. */
    public static VulkanRenderPass currentPass() {
        return encoder == null ? null : encoder.currentRenderPass;
    }

    public static int colorFormat() {
        return colorFormat;
    }

    public static int depthFormat() {
        return depthFormat;
    }

    /** The colour image view of the pass being recorded, so we can render into it ourselves. */
    public static long colorView() {
        return colorView;
    }

    /** The depth image view of the pass being recorded, which we sample rather than depth test. */
    public static long depthView() {
        return depthView;
    }

    public static boolean hasUsableTarget() {
        return colorFormat != 0 && depthFormat != 0
                && colorView != VK_NULL_HANDLE && depthView != VK_NULL_HANDLE;
    }
}
