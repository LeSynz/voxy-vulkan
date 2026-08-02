package xyz.synz.voxyvulkan.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import xyz.synz.voxyvulkan.common.util.TrackedObject;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A sampler, the Vulkan counterpart to GL's sampler objects.
 * <p>
 * Block textures want nearest filtering with linear mip interpolation, matching voxy's GL sampler
 * setup, and the mip range is clamped to the terrain atlas's own mip count so distant LODs do not
 * sample past what the atlas actually contains.
 */
public class VkSampler extends TrackedObject {
    public final long sampler;

    /** Nearest magnification with linear mip blending, as voxy uses for block textures. */
    public static VkSampler blockSampler(int maxLod) {
        return new VkSampler(VK_FILTER_NEAREST, VK_FILTER_NEAREST,
                VK_SAMPLER_MIPMAP_MODE_LINEAR, 0.0f, maxLod);
    }

    public VkSampler(int magFilter, int minFilter, int mipmapMode, float minLod, float maxLod) {
        var ctx = VkContext.get();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(magFilter)
                    .minFilter(minFilter)
                    .mipmapMode(mipmapMode)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .mipLodBias(0.0f)
                    .anisotropyEnable(false)
                    .compareEnable(false)
                    .minLod(minLod)
                    .maxLod(maxLod)
                    .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK)
                    .unnormalizedCoordinates(false);

            LongBuffer handle = stack.mallocLong(1);
            VkUtil.check(vkCreateSampler(ctx.device, info, null, handle), "vkCreateSampler");
            this.sampler = handle.get(0);
        }
    }

    @Override
    public void free() {
        this.free0();
        vkDestroySampler(VkContext.get().device, this.sampler, null);
    }
}
