package xyz.synz.voxyvulkan.client.core.vk;

import xyz.synz.voxyvulkan.common.util.TrackedObject;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A VMA backed device buffer, the Vulkan counterpart to {@link xyz.synz.voxyvulkan.client.core.gl.GlBuffer}.
 * <p>
 * Allocated through Minecraft's own VMA allocator so our memory sits in the same heaps and budget
 * tracking as the rest of the game's resources.
 */
public class VkBuffer extends TrackedObject {
    public final long buffer;
    public final long allocation;
    private final long size;
    private final long mappedPointer;

    private static int COUNT;
    private static long TOTAL_SIZE;

    //Every buffer can be a transfer target so zero()/fill() always work
    private static final int DEFAULT_USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK_BUFFER_USAGE_TRANSFER_DST_BIT
            | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;

    public VkBuffer(long size) {
        this(size, DEFAULT_USAGE, false);
    }

    public VkBuffer(long size, boolean hostVisible) {
        this(size, DEFAULT_USAGE, hostVisible);
    }

    public VkBuffer(long size, int usage, boolean hostVisible) {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive, got " + size);
        }
        this.size = size;

        var ctx = VkContext.get();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            var allocCreate = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO);
            if (hostVisible) {
                allocCreate.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT
                        | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            }

            LongBuffer handle = stack.mallocLong(1);
            PointerBuffer allocHandle = stack.mallocPointer(1);
            var allocationInfo = VmaAllocationInfo.calloc(stack);
            VkUtil.check(Vma.vmaCreateBuffer(ctx.allocator, bufferInfo, allocCreate, handle, allocHandle, allocationInfo),
                    "vmaCreateBuffer");

            this.buffer = handle.get(0);
            this.allocation = allocHandle.get(0);
            this.mappedPointer = hostVisible ? allocationInfo.pMappedData() : 0L;
        }

        COUNT++;
        TOTAL_SIZE += size;
    }

    public long size() {
        return this.size;
    }

    /** Host pointer for persistently mapped buffers, or 0 if this buffer is device local. */
    public long mappedPointer() {
        if (this.mappedPointer == 0) {
            throw new IllegalStateException("Buffer is not host visible");
        }
        return this.mappedPointer;
    }

    public boolean isHostVisible() {
        return this.mappedPointer != 0;
    }

    public VkBuffer zero() {
        return this.fill(0);
    }

    public VkBuffer fill(int value) {
        //vkCmdFillBuffer needs a 4 byte multiple, round down rather than overrun the allocation
        long fillSize = this.size & ~3L;
        if (fillSize != 0) {
            VkContext.get().submitBlocking(cmd -> vkCmdFillBuffer(cmd, this.buffer, 0, fillSize, value));
        }
        return this;
    }

    public VkBuffer zeroRange(long offset, long length) {
        VkContext.get().submitBlocking(cmd -> vkCmdFillBuffer(cmd, this.buffer, offset, length & ~3L, 0));
        return this;
    }

    /** Makes host writes to a mapped buffer visible to the device. */
    public void flush() {
        Vma.vmaFlushAllocation(VkContext.get().allocator, this.allocation, 0, VK_WHOLE_SIZE);
    }

    /** Makes device writes visible to the host after a dispatch has completed. */
    public void invalidate() {
        Vma.vmaInvalidateAllocation(VkContext.get().allocator, this.allocation, 0, VK_WHOLE_SIZE);
    }

    @Override
    public void free() {
        this.free0();
        Vma.vmaDestroyBuffer(VkContext.get().allocator, this.buffer, this.allocation);

        COUNT--;
        TOTAL_SIZE -= this.size;
    }

    public static int getCount() {
        return COUNT;
    }

    public static long getTotalSize() {
        return TOTAL_SIZE;
    }
}
