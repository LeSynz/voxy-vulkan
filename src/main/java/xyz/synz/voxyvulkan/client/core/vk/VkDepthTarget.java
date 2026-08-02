package xyz.synz.voxyvulkan.client.core.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import xyz.synz.voxyvulkan.common.Logger;
import xyz.synz.voxyvulkan.common.util.TrackedObject;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A depth buffer of our own, at the size of Minecraft's render target.
 * <p>
 * LOD terrain cannot share Minecraft's depth buffer. We render with a far plane thirty times further
 * out, which puts our depth values on a completely different scale - depth tested against vanilla's
 * they are meaningless, and the two cannot be reconciled without giving up the distance. Voxy's
 * OpenGL path solves this by owning its depth buffer and deciding vanilla occlusion separately, and
 * this is that buffer.
 * <p>
 * Kept in {@code VK_IMAGE_LAYOUT_GENERAL} for its whole life. Minecraft does the same with every
 * image it owns - its render passes declare {@code GENERAL} for their attachments and it never emits
 * an image layout transition, only global memory barriers - so matching that means our image needs
 * exactly one transition, out of {@code UNDEFINED}, and never moves again.
 */
public class VkDepthTarget extends TrackedObject {
    private final int format;

    private long image = VK_NULL_HANDLE;
    private long imageView = VK_NULL_HANDLE;
    private long allocation = VK_NULL_HANDLE;
    private int width;
    private int height;
    private boolean layoutInitialised;

    public VkDepthTarget(int format) {
        this.format = format;
    }

    public long view() {
        return this.imageView;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    /**
     * Allocates the image, or reallocates it if the target has been resized.
     *
     * @return true if the image was (re)created, meaning anything holding its view must rebind
     */
    public boolean resize(int width, int height) {
        if (this.image != VK_NULL_HANDLE && this.width == width && this.height == height) {
            return false;
        }
        this.destroyImage();
        this.width = width;
        this.height = height;
        this.layoutInitialised = false;

        var ctx = VkContext.get();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(this.format)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().width(width).height(height).depth(1);

            var allocCreate = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO);
            LongBuffer handle = stack.mallocLong(1);
            PointerBuffer allocHandle = stack.mallocPointer(1);
            VkUtil.check(Vma.vmaCreateImage(ctx.allocator, imageInfo, allocCreate, handle, allocHandle,
                    VmaAllocationInfo.calloc(stack)), "vmaCreateImage for LOD depth target");
            this.image = handle.get(0);
            this.allocation = allocHandle.get(0);

            var viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(this.image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(this.format);
            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            VkUtil.check(vkCreateImageView(ctx.device, viewInfo, null, handle), "vkCreateImageView for LOD depth target");
            this.imageView = handle.get(0);
        }

        Logger.info("[vk-lod] allocated " + width + "x" + height + " LOD depth target");
        return true;
    }

    /**
     * Records the one transition the image ever needs, out of {@code UNDEFINED} and into
     * {@code GENERAL}, into Minecraft's frame command buffer. Doing it here rather than on our own
     * submit keeps it on the graphics queue and in frame order with the pass that first uses it.
     */
    public void ensureLayout(VkCommandBuffer cmd) {
        if (this.layoutInitialised) {
            return;
        }
        this.layoutInitialised = true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(this.image)
                    .srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                            | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            barrier.get(0).subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                    0, null, null, barrier);
        }
    }

    private void destroyImage() {
        if (this.image == VK_NULL_HANDLE) {
            return;
        }
        var ctx = VkContext.get();
        vkDestroyImageView(ctx.device, this.imageView, null);
        Vma.vmaDestroyImage(ctx.allocator, this.image, this.allocation);
        this.image = VK_NULL_HANDLE;
        this.imageView = VK_NULL_HANDLE;
        this.allocation = VK_NULL_HANDLE;
    }

    @Override
    public void free() {
        this.free0();
        this.destroyImage();
    }
}
