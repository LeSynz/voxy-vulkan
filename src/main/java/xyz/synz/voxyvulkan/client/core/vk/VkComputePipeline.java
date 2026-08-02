package xyz.synz.voxyvulkan.client.core.vk;

import xyz.synz.voxyvulkan.client.core.gl.shader.ShaderType;
import xyz.synz.voxyvulkan.common.util.TrackedObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A compute pipeline plus its descriptor layout, replacing voxy's GL compute programs.
 * <p>
 * GL bound SSBOs to global indexed targets and pushed loose {@code glUniform} values. Vulkan has
 * neither, so bindings become a descriptor set of storage buffers and uniforms become push
 * constants. Binding indices map one to one onto the {@code layout(binding = N)} in the shader.
 */
public class VkComputePipeline extends TrackedObject {
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final int bindingCount;
    private final int pushConstantSize;

    public VkComputePipeline(String shaderId, int bindingCount) {
        this(shaderId, bindingCount, 0, List.of());
    }

    /**
     * @param bindingCount     number of storage buffers the shader declares
     * @param pushConstantSize bytes of push constants, 0 for none
     */
    public VkComputePipeline(String shaderId, int bindingCount, int pushConstantSize, List<String> defines) {
        this.bindingCount = bindingCount;
        this.pushConstantSize = pushConstantSize;

        var ctx = VkContext.get();
        ByteBuffer spirv = VkShaderCompiler.compile(shaderId, ShaderType.COMPUTE, defines);
        long shaderModule = VK_NULL_HANDLE;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);

            var moduleInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            VkUtil.check(vkCreateShaderModule(ctx.device, moduleInfo, null, handle), "vkCreateShaderModule for " + shaderId);
            shaderModule = handle.get(0);

            var bindings = VkDescriptorSetLayoutBinding.calloc(bindingCount, stack);
            for (int i = 0; i < bindingCount; i++) {
                bindings.get(i)
                        .binding(i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            var setLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
            VkUtil.check(vkCreateDescriptorSetLayout(ctx.device, setLayoutInfo, null, handle), "vkCreateDescriptorSetLayout");
            this.descriptorSetLayout = handle.get(0);

            var layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(this.descriptorSetLayout));
            if (pushConstantSize > 0) {
                var range = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                        .offset(0)
                        .size(pushConstantSize);
                layoutInfo.pPushConstantRanges(range);
            }
            VkUtil.check(vkCreatePipelineLayout(ctx.device, layoutInfo, null, handle), "vkCreatePipelineLayout");
            this.pipelineLayout = handle.get(0);

            var stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));
            var pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(this.pipelineLayout);
            VkUtil.check(vkCreateComputePipelines(ctx.device, VK_NULL_HANDLE, pipelineInfo, null, handle),
                    "vkCreateComputePipelines for " + shaderId);
            this.pipeline = handle.get(0);
        } finally {
            if (shaderModule != VK_NULL_HANDLE) {
                vkDestroyShaderModule(ctx.device, shaderModule, null);
            }
            MemoryUtil.memFree(spirv);
        }
    }

    /**
     * Binds the pipeline and its buffers, then dispatches. Buffer order must match the shader's
     * {@code layout(binding = N)} indices.
     */
    public void dispatch(VkCommandBuffer cmd, int groupsX, int groupsY, int groupsZ, VkBuffer... buffers) {
        this.dispatch(cmd, groupsX, groupsY, groupsZ, null, buffers);
    }

    public void dispatch(VkCommandBuffer cmd, int groupsX, int groupsY, int groupsZ,
                         ByteBuffer pushConstants, VkBuffer... buffers) {
        if (buffers.length != this.bindingCount) {
            throw new IllegalArgumentException("Expected " + this.bindingCount + " buffers but got " + buffers.length);
        }
        var ctx = VkContext.get();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var setAlloc = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(ctx.descriptorPool())
                    .pSetLayouts(stack.longs(this.descriptorSetLayout));
            LongBuffer setHandle = stack.mallocLong(1);
            VkUtil.check(vkAllocateDescriptorSets(ctx.device, setAlloc, setHandle), "vkAllocateDescriptorSets");
            long descriptorSet = setHandle.get(0);

            var writes = VkWriteDescriptorSet.calloc(this.bindingCount, stack);
            for (int i = 0; i < this.bindingCount; i++) {
                var bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                bufferInfo.get(0).buffer(buffers[i].buffer).offset(0).range(buffers[i].size());
                writes.get(i).sType$Default()
                        .dstSet(descriptorSet)
                        .dstBinding(i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(bufferInfo)
                        //pBufferInfo does not derive descriptorCount, it must be set explicitly
                        .descriptorCount(1);
            }
            vkUpdateDescriptorSets(ctx.device, writes, null);

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0,
                    stack.longs(descriptorSet), null);
            if (pushConstants != null) {
                if (this.pushConstantSize == 0) {
                    throw new IllegalStateException("Pipeline was not created with push constants");
                }
                vkCmdPushConstants(cmd, this.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pushConstants);
            }
            vkCmdDispatch(cmd, groupsX, groupsY, groupsZ);
        }
    }

    /**
     * Barrier between two dependent dispatches. GL ordered these implicitly, Vulkan does not, so
     * every read-after-write between dispatches needs one of these.
     */
    public static void computeBarrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, barrier, null, null);
        }
    }

    /** Barrier making dispatch results visible to host reads of a mapped buffer. */
    public static void hostReadBarrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                    0, barrier, null, null);
        }
    }

    @Override
    public void free() {
        this.free0();
        var ctx = VkContext.get();
        vkDestroyPipeline(ctx.device, this.pipeline, null);
        vkDestroyPipelineLayout(ctx.device, this.pipelineLayout, null);
        vkDestroyDescriptorSetLayout(ctx.device, this.descriptorSetLayout, null);
    }
}
