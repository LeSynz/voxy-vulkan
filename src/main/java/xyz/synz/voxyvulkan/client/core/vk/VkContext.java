package xyz.synz.voxyvulkan.client.core.vk;

import com.mojang.blaze3d.vulkan.VulkanQueue;
import xyz.synz.voxyvulkan.common.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Owns the long lived Vulkan objects voxy needs: the device handles borrowed from Minecraft, plus
 * our own command and descriptor pools.
 * <p>
 * Voxy runs its culling and traversal on the dedicated compute queue rather than interleaving into
 * Minecraft's frame command buffer, which keeps the two renderers from having to agree about
 * command buffer state.
 */
public class VkContext {
    private static VkContext INSTANCE;

    public final VkDevice device;
    public final long allocator;
    public final VulkanQueue computeQueue;

    private final long commandPool;
    private final long descriptorPool;

    //Sized to comfortably cover voxy's 43 storage buffer bindings with room for per dispatch sets
    private static final int MAX_DESCRIPTOR_SETS = 512;
    private static final int MAX_STORAGE_BUFFERS = 4096;
    private static final int MAX_SAMPLERS = 256;

    private VkContext() {
        var vulkan = VkInterop.device();
        this.device = vulkan.vkDevice();
        this.allocator = vulkan.vma();
        this.computeQueue = vulkan.computeQueue();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);

            var poolInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .queueFamilyIndex(this.computeQueue.queueFamilyIndex())
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            VkUtil.check(vkCreateCommandPool(this.device, poolInfo, null, handle), "vkCreateCommandPool");
            this.commandPool = handle.get(0);

            var sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(MAX_STORAGE_BUFFERS);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_SAMPLERS);
            var descriptorInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(MAX_DESCRIPTOR_SETS)
                    .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .pPoolSizes(sizes);
            VkUtil.check(vkCreateDescriptorPool(this.device, descriptorInfo, null, handle), "vkCreateDescriptorPool");
            this.descriptorPool = handle.get(0);
        }

        Logger.info("[voxy-vk] context created on compute queue family " + this.computeQueue.queueFamilyIndex());
    }

    public static VkContext get() {
        if (INSTANCE == null) {
            INSTANCE = new VkContext();
        }
        return INSTANCE;
    }

    public static boolean isCreated() {
        return INSTANCE != null;
    }

    public long descriptorPool() {
        return this.descriptorPool;
    }

    /**
     * Records and submits a single command buffer, blocking until the GPU has finished it.
     * <p>
     * Synchronous by design for now - correctness first. Async submission with per frame fences is
     * a later optimisation, and belongs with the render loop integration rather than here.
     */
    public void submitBlocking(Consumer<VkCommandBuffer> recorder) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var allocInfo = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(this.commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pointer = stack.mallocPointer(1);
            VkUtil.check(vkAllocateCommandBuffers(this.device, allocInfo, pointer), "vkAllocateCommandBuffers");
            var cmd = new VkCommandBuffer(pointer.get(0), this.device);

            long fence = VK_NULL_HANDLE;
            try {
                var begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                VkUtil.check(vkBeginCommandBuffer(cmd, begin), "vkBeginCommandBuffer");
                recorder.accept(cmd);
                VkUtil.check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");

                LongBuffer handle = stack.mallocLong(1);
                VkUtil.check(vkCreateFence(this.device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle), "vkCreateFence");
                fence = handle.get(0);

                var submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmd));
                synchronized (this.computeQueue) {//vkQueueSubmit needs external synchronisation per queue
                    VkUtil.check(vkQueueSubmit(this.computeQueue.vkQueue(), submit, fence), "vkQueueSubmit");
                }
                VkUtil.check(vkWaitForFences(this.device, fence, true, Long.MAX_VALUE), "vkWaitForFences");
            } finally {
                if (fence != VK_NULL_HANDLE) vkDestroyFence(this.device, fence, null);
                vkFreeCommandBuffers(this.device, this.commandPool, cmd);
            }
        }
    }

    public void destroy() {
        vkDestroyDescriptorPool(this.device, this.descriptorPool, null);
        vkDestroyCommandPool(this.device, this.commandPool, null);
        INSTANCE = null;
    }
}
