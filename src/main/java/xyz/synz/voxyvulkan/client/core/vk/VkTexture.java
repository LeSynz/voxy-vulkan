package xyz.synz.voxyvulkan.client.core.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.*;
import xyz.synz.voxyvulkan.common.util.TrackedObject;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A sampled 2D image with mip levels, the Vulkan counterpart to
 * {@link xyz.synz.voxyvulkan.client.core.gl.GlTexture}.
 * <p>
 * Voxy's model atlas is one large RGBA8 image written a model at a time as sub-images, so this is
 * built around {@link #uploadSubImage} rather than whole image uploads. Unlike GL, the image has to
 * be moved between layouts explicitly: transfer destination while being written, shader read only
 * while being sampled.
 */
public class VkTexture extends TrackedObject {
    public final long image;
    public final long imageView;
    private final long allocation;
    private final int width;
    private final int height;
    private final int levels;
    private final int format;
    private int currentLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    private static long ESTIMATED_TOTAL_SIZE;

    public VkTexture(int format, int levels, int width, int height) {
        this.format = format;
        this.levels = levels;
        this.width = width;
        this.height = height;

        var ctx = VkContext.get();
        var vulkan = VkInterop.device();
        int graphicsFamily = vulkan.graphicsQueue().queueFamilyIndex();
        int computeFamily = vulkan.computeQueue().queueFamilyIndex();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            var imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(levels)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().width(width).height(height).depth(1);

            //We upload on the compute queue but sample on the graphics queue. Sharing the image
            //across both families avoids needing an explicit ownership transfer on every upload.
            if (graphicsFamily != computeFamily) {
                imageInfo.sharingMode(VK_SHARING_MODE_CONCURRENT)
                        .pQueueFamilyIndices(stack.ints(graphicsFamily, computeFamily));
            } else {
                imageInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            }

            var allocCreate = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO);
            LongBuffer handle = stack.mallocLong(1);
            PointerBuffer allocHandle = stack.mallocPointer(1);
            VkUtil.check(Vma.vmaCreateImage(ctx.allocator, imageInfo, allocCreate, handle, allocHandle,
                    VmaAllocationInfo.calloc(stack)), "vmaCreateImage");
            this.image = handle.get(0);
            this.allocation = allocHandle.get(0);

            var viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(this.image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);
            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(levels)
                    .baseArrayLayer(0).layerCount(1);
            VkUtil.check(vkCreateImageView(ctx.device, viewInfo, null, handle), "vkCreateImageView");
            this.imageView = handle.get(0);
        }

        ESTIMATED_TOTAL_SIZE += this.getEstimatedSize();

        //Start in the layout the shader expects, so an unwritten atlas is still safe to sample
        VkContext.get().submitBlocking(cmd -> transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
    }

    /**
     * A one pixel texture of a single colour, for standing in where a real one is not available.
     * Sampling it always returns that colour, so a missing texture degrades to a constant rather
     * than to a binding that cannot be made and a draw that therefore never happens.
     */
    public static VkTexture singlePixel(int argb) {
        var texture = new VkTexture(VK_FORMAT_R8G8B8A8_UNORM, 1, 1, 1);
        long scratch = MemoryUtil.nmemAlloc(4);
        try {
            //Stored RGBA in memory order, which is what R8G8B8A8_UNORM reads
            MemoryUtil.memPutByte(scratch, (byte) ((argb >> 16) & 0xFF));
            MemoryUtil.memPutByte(scratch + 1, (byte) ((argb >> 8) & 0xFF));
            MemoryUtil.memPutByte(scratch + 2, (byte) (argb & 0xFF));
            MemoryUtil.memPutByte(scratch + 3, (byte) ((argb >>> 24) & 0xFF));
            texture.uploadSubImage(0, 0, 0, 1, 1, scratch);
        } finally {
            MemoryUtil.nmemFree(scratch);
        }
        //Left in GENERAL rather than the usual SHADER_READ_ONLY_OPTIMAL. This stands in for one of
        //Minecraft's textures, and Minecraft keeps everything it owns in GENERAL, so it is bound
        //through the same descriptor write and has to agree with the layout that write declares.
        VkContext.get().submitBlocking(cmd -> texture.transition(cmd, VK_IMAGE_LAYOUT_GENERAL));
        return texture;
    }

    public long getEstimatedSize() {
        //Base level plus the mip chain, which converges to about 4/3 of the base
        return (long) this.width * this.height * 4L * 4L / 3L;
    }

    /**
     * Uploads a rectangle into one mip level. Data must be tightly packed RGBA8.
     * <p>
     * Synchronous: this stages through a host visible buffer and waits. Model uploads are
     * infrequent enough that batching them is a later optimisation, not a correctness issue.
     */
    public void uploadSubImage(int level, int x, int y, int w, int h, long dataAddress) {
        if (w <= 0 || h <= 0) {
            return;
        }
        long bytes = (long) w * h * 4L;
        var staging = new VkBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        try {
            MemoryUtil.memCopy(dataAddress, staging.mappedPointer(), bytes);
            staging.flush();

            VkContext.get().submitBlocking(cmd -> {
                transition(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    var region = VkBufferImageCopy.calloc(1, stack)
                            .bufferOffset(0)
                            .bufferRowLength(0)//tightly packed
                            .bufferImageHeight(0);
                    region.get(0).imageSubresource()
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .mipLevel(level)
                            .baseArrayLayer(0)
                            .layerCount(1);
                    region.get(0).imageOffset().set(x, y, 0);
                    region.get(0).imageExtent().set(w, h, 1);
                    vkCmdCopyBufferToImage(cmd, staging.buffer, this.image,
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                }
                transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            });
        } finally {
            staging.free();
        }
    }

    /** Moves the whole image to a new layout, recording the barrier into the given command buffer. */
    public void transition(VkCommandBuffer cmd, int newLayout) {
        if (this.currentLayout == newLayout) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .oldLayout(this.currentLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(this.image)
                    .srcAccessMask(accessFor(this.currentLayout))
                    .dstAccessMask(accessFor(newLayout));
            barrier.get(0).subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(this.levels)
                    .baseArrayLayer(0).layerCount(1);

            vkCmdPipelineBarrier(cmd,
                    stageFor(this.currentLayout), stageFor(newLayout),
                    0, null, null, barrier);
        }
        this.currentLayout = newLayout;
    }

    private static int accessFor(int layout) {
        return switch (layout) {
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> VK_ACCESS_TRANSFER_WRITE_BIT;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> VK_ACCESS_SHADER_READ_BIT;
            //GENERAL is what images shared with Minecraft sit in, and they can be read by anything
            case VK_IMAGE_LAYOUT_GENERAL -> VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
            default -> 0;
        };
    }

    private static int stageFor(int layout) {
        return switch (layout) {
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> VK_PIPELINE_STAGE_TRANSFER_BIT;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            case VK_IMAGE_LAYOUT_GENERAL -> VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            default -> VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        };
    }

    /**
     * Copies many staged regions into the image in one submission.
     * <p>
     * The per region {@link #uploadSubImage} allocates a staging buffer, submits and waits every
     * time, which is fine for the odd upload and ruinous for a model atlas - seven thousand models
     * of six faces and four mips would be tens of thousands of round trips to the GPU.
     *
     * Copies into the image's current layout rather than moving it to {@code TRANSFER_DST} and back.
     * A layout transition applies to the whole image, so doing one here would be changing the layout
     * of a texture that frames already in flight are sampling. Copying straight into
     * {@code GENERAL} is legal, and writing a region no draw refers to yet is safe.
     *
     * @param regions flattened as {mipLevel, x, y, width, height, byteOffsetIntoStaging} per region
     * @param layout the image's current layout, which the copy targets directly
     */
    public void uploadRegions(VkBuffer staging, int[] regions, int regionCount, int layout) {
        if (regionCount == 0) {
            return;
        }
        //Allocated off the memory stack rather than on it. A batch runs to a couple of thousand
        //regions and each descriptor is 56 bytes, which comfortably exceeds LWJGL's 64 KiB stack -
        //it does not grow, it throws. Freed as soon as the command is recorded, since recording
        //copies the contents into the command buffer.
        var copies = VkBufferImageCopy.calloc(regionCount);
        try {
            for (int i = 0; i < regionCount; i++) {
                int base = i * 6;
                var copy = copies.get(i)
                        .bufferOffset(Integer.toUnsignedLong(regions[base + 5]))
                        .bufferRowLength(0)//tightly packed
                        .bufferImageHeight(0);
                copy.imageSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(regions[base])
                        .baseArrayLayer(0)
                        .layerCount(1);
                copy.imageOffset().set(regions[base + 1], regions[base + 2], 0);
                copy.imageExtent().set(regions[base + 3], regions[base + 4], 1);
            }
            VkContext.get().submitBlocking(cmd ->
                    vkCmdCopyBufferToImage(cmd, staging.buffer, this.image, layout, copies));
        } finally {
            copies.free();
        }
    }

    @Override
    public void free() {
        this.free0();
        var ctx = VkContext.get();
        vkDestroyImageView(ctx.device, this.imageView, null);
        Vma.vmaDestroyImage(ctx.allocator, this.image, this.allocation);
        ESTIMATED_TOTAL_SIZE -= this.getEstimatedSize();
    }

    public static long getEstimatedTotalSize() {
        return ESTIMATED_TOTAL_SIZE;
    }
}
