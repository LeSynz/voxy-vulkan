package xyz.synz.voxyvulkan.client.mixin.minecraft;

import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.synz.voxyvulkan.client.VoxyClient;
import xyz.synz.voxyvulkan.client.core.vk.VkLodRenderer;
import xyz.synz.voxyvulkan.client.core.vk.VkQuadSpike;
import xyz.synz.voxyvulkan.client.core.vk.VkRenderPassTracker;

/**
 * Three hooks into Minecraft's Vulkan command recording:
 * <ul>
 *     <li>pass creation, where the attachments our pipeline must match are still visible</li>
 *     <li>pass submission entry, the last moment the pass object identifies itself</li>
 *     <li>pass submission exit, where the pass has ended but the frame's command buffer is still
 *         recording - the one window in which a pass of our own can be opened</li>
 * </ul>
 */
@Mixin(VulkanCommandEncoder.class)
public class MixinVulkanCommandEncoder {
    @Inject(method = "createRenderPass", at = @At("RETURN"), remap = false)
    private void voxy$capturePassFormats(RenderPassDescriptor descriptor, CallbackInfoReturnable<?> cir) {
        //The LOD renderer needs the live pass and its attachment formats, so this tracks whenever
        //the Vulkan path is active rather than only under the spike flag
        if (VoxyClient.isVulkanMode()) {
            VkRenderPassTracker.setEncoder((VulkanCommandEncoder) (Object) this);
            VkRenderPassTracker.onCreateRenderPass(descriptor);
        }
    }

    @Inject(method = "submitRenderPass", at = @At("HEAD"), remap = false)
    private void voxy$drawBeforePassEnds(CallbackInfo ci) {
        var lod = VkLodRenderer.getActive();
        if (lod != null) {
            var active = ((VulkanCommandEncoder) (Object) this).currentRenderPass;
            if (active != null) {
                lod.onSubmitRenderPass(active);
            }
        }
        if (!VoxyClient.isGraphicsSpikeEnabled()) {
            return;
        }
        //Fallback for when the terrain hook found no open pass to record into
        var pass = ((VulkanCommandEncoder) (Object) this).currentRenderPass;
        if (pass != null) {
            VkQuadSpike.onSubmitRenderPass(pass);
        }
    }

    @Inject(method = "submitRenderPass", at = @At("RETURN"), remap = false)
    private void voxy$drawAfterPassEnds(CallbackInfo ci) {
        var lod = VkLodRenderer.getActive();
        if (lod != null) {
            lod.onRenderPassEnded();
        }
    }
}
