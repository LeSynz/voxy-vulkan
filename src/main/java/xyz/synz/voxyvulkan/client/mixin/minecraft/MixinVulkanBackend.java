package xyz.synz.voxyvulkan.client.mixin.minecraft;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import xyz.synz.voxyvulkan.common.Logger;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Set;

/**
 * Minecraft only enables the handful of Vulkan features its own renderer needs, and features are
 * immutable once {@code vkCreateDevice} has run. Voxy needs two more:
 * <ul>
 *     <li>{@code shaderInt64} - used by 6 of voxy's shaders</li>
 *     <li>{@code drawIndirectCount} - the replacement for {@code glMultiDrawElementsIndirectCount},
 *         which voxy treats as a hard requirement</li>
 * </ul>
 * The extension and feature sets reaching this method are mutable copies, so we can add to them.
 * Anything the physical device does not advertise is skipped - asking for an unsupported feature
 * would fail device creation and leave the game unable to start at all.
 */
@Mixin(VulkanBackend.class)
public class MixinVulkanBackend {
    @Inject(
            method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At("HEAD"),
            remap = false
    )
    private static void voxy$requestFeatures(Collection<String> extensions,
                                             VulkanPhysicalDevice physicalDevice,
                                             Set<VulkanFeature> features,
                                             CallbackInfoReturnable<VkDevice> cir) {
        request(physicalDevice, features,
                new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "shaderInt64",
                        VkPhysicalDeviceFeatures.SHADERINT64));
        request(physicalDevice, features,
                new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "drawIndirectCount",
                        VkPhysicalDeviceVulkan12Features.DRAWINDIRECTCOUNT));
    }

    private static void request(VulkanPhysicalDevice physicalDevice, Set<VulkanFeature> features, VulkanFeature feature) {
        if (VulkanBackend.isFeatureSupported(physicalDevice.vkPhysicalDevice(), feature)) {
            features.add(feature);
            Logger.info("[voxy-vk] requested device feature " + feature.name());
        } else {
            Logger.warn("[voxy-vk] device does not support " + feature.name() + ", voxy will need a fallback path");
        }
    }
}
