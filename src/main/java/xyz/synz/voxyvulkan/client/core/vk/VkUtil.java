package xyz.synz.voxyvulkan.client.core.vk;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

public class VkUtil {
    public static void check(int result, String what) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(what + " failed with VkResult " + result + " (" + describe(result) + ")");
        }
    }

    private static String describe(int result) {
        return switch (result) {
            case -1 -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case -2 -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case -3 -> "VK_ERROR_INITIALIZATION_FAILED";
            case -4 -> "VK_ERROR_DEVICE_LOST";
            case -5 -> "VK_ERROR_MEMORY_MAP_FAILED";
            case -6 -> "VK_ERROR_LAYER_NOT_PRESENT";
            case -7 -> "VK_ERROR_EXTENSION_NOT_PRESENT";
            case -8 -> "VK_ERROR_FEATURE_NOT_PRESENT";
            case -9 -> "VK_ERROR_INCOMPATIBLE_DRIVER";
            case -11 -> "VK_ERROR_FORMAT_NOT_SUPPORTED";
            case -12 -> "VK_ERROR_FRAGMENTED_POOL";
            case -1000069000 -> "VK_ERROR_OUT_OF_POOL_MEMORY";
            default -> "unknown";
        };
    }
}
