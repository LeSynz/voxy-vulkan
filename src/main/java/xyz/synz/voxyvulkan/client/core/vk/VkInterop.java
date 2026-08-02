package xyz.synz.voxyvulkan.client.core.vk;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.vulkan.VkDevice;

/**
 * Reaches past the backend-agnostic {@link com.mojang.blaze3d.systems.GpuDevice} facade to the raw
 * Vulkan objects Minecraft created.
 * <p>
 * Blaze3D's public API exposes no compute shaders, no storage buffers and no barriers, so a
 * GPU-driven renderer like voxy cannot be expressed through it. What it does expose are the handles
 * below, which are enough to drive our own pipelines alongside Minecraft's.
 */
public final class VkInterop {
    private VkInterop() {}

    public static boolean isVulkanBackend() {
        var device = RenderSystem.tryGetDevice();
        return device != null && device.getDeviceInfo().backendName().equals("Vulkan");
    }

    /** The backing {@link VulkanDevice}. Only valid when {@link #isVulkanBackend()}. */
    public static VulkanDevice device() {
        var device = RenderSystem.tryGetDevice();
        if (device == null) {
            throw new IllegalStateException("Render device is not initialized yet");
        }
        //GpuDevice#backend is private, opened via voxy.accesswidener
        if (!(device.backend instanceof VulkanDevice vulkan)) {
            throw new IllegalStateException("Backend is not Vulkan: " + device.getDeviceInfo().backendName());
        }
        return vulkan;
    }

    public static VkDevice vkDevice() {
        return device().vkDevice();
    }

    /** Minecraft's VMA allocator handle. Shared, so allocations interop with MC's own resources. */
    public static long allocator() {
        return device().vma();
    }
}
