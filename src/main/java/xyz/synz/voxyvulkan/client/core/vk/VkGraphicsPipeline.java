package xyz.synz.voxyvulkan.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import xyz.synz.voxyvulkan.client.core.gl.shader.ShaderType;
import xyz.synz.voxyvulkan.common.util.TrackedObject;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A graphics pipeline built for dynamic rendering, so it can be recorded straight into the render
 * pass Minecraft already has open rather than needing a VkRenderPass object of our own.
 * <p>
 * Attachment formats must match the pass we draw into or the pipeline is incompatible, so they are
 * passed in from Minecraft's render target.
 */
public class VkGraphicsPipeline extends TrackedObject {
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final int bindingCount;
    private final int textureCount;

    public VkGraphicsPipeline(String vertexShaderId, String fragmentShaderId,
                              int colorFormat, int depthFormat,
                              boolean depthTest, boolean depthWrite, int depthCompareOp,
                              int pushConstantSize) {
        this(vertexShaderId, fragmentShaderId, colorFormat, depthFormat,
                depthTest, depthWrite, depthCompareOp, pushConstantSize, 0);
    }

    /**
     * @param bindingCount storage buffers the shaders declare. Voxy's geometry is GPU driven - quads
     *                     live in an SSBO indexed by {@code gl_VertexIndex >> 2} rather than in a
     *                     vertex buffer - so the vertex stage needs storage buffer access.
     */
    public VkGraphicsPipeline(String vertexShaderId, String fragmentShaderId,
                              int colorFormat, int depthFormat,
                              boolean depthTest, boolean depthWrite, int depthCompareOp,
                              int pushConstantSize, int bindingCount) {
        this(vertexShaderId, fragmentShaderId, colorFormat, depthFormat,
                depthTest, depthWrite, depthCompareOp, pushConstantSize, bindingCount, 0);
    }

    /**
     * @param textureCount combined image samplers, bound after the storage buffers. Voxy samples its
     *                     model atlas in the fragment stage, so block textures arrive this way.
     */
    public VkGraphicsPipeline(String vertexShaderId, String fragmentShaderId,
                              int colorFormat, int depthFormat,
                              boolean depthTest, boolean depthWrite, int depthCompareOp,
                              int pushConstantSize, int bindingCount, int textureCount) {
        this(vertexShaderId, fragmentShaderId, colorFormat, depthFormat, depthTest, depthWrite,
                depthCompareOp, pushConstantSize, bindingCount, textureCount, VK_CULL_MODE_NONE);
    }

    public VkGraphicsPipeline(String vertexShaderId, String fragmentShaderId,
                              int colorFormat, int depthFormat,
                              boolean depthTest, boolean depthWrite, int depthCompareOp,
                              int pushConstantSize, int bindingCount, int textureCount, int cullMode) {
        this.bindingCount = bindingCount;
        this.textureCount = textureCount;
        var ctx = VkContext.get();
        ByteBuffer vertSpirv = VkShaderCompiler.compile(vertexShaderId, ShaderType.VERTEX, List.of());
        ByteBuffer fragSpirv = VkShaderCompiler.compile(fragmentShaderId, ShaderType.FRAGMENT, List.of());

        long vertModule = VK_NULL_HANDLE;
        long fragModule = VK_NULL_HANDLE;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);

            VkUtil.check(vkCreateShaderModule(ctx.device,
                    VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(vertSpirv), null, handle),
                    "vkCreateShaderModule for " + vertexShaderId);
            vertModule = handle.get(0);
            VkUtil.check(vkCreateShaderModule(ctx.device,
                    VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(fragSpirv), null, handle),
                    "vkCreateShaderModule for " + fragmentShaderId);
            fragModule = handle.get(0);

            var layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            int totalBindings = bindingCount + textureCount;
            if (totalBindings > 0) {
                var bindings = VkDescriptorSetLayoutBinding.calloc(totalBindings, stack);
                for (int i = 0; i < bindingCount; i++) {
                    bindings.get(i)
                            .binding(i)
                            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .descriptorCount(1)
                            .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
                }
                for (int i = 0; i < textureCount; i++) {
                    bindings.get(bindingCount + i)
                            .binding(bindingCount + i)
                            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .descriptorCount(1)
                            .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
                }
                VkUtil.check(vkCreateDescriptorSetLayout(ctx.device,
                        VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),
                        null, handle), "vkCreateDescriptorSetLayout");
                this.descriptorSetLayout = handle.get(0);
                layoutInfo.pSetLayouts(stack.longs(this.descriptorSetLayout));
            } else {
                this.descriptorSetLayout = VK_NULL_HANDLE;
            }
            if (pushConstantSize > 0) {
                layoutInfo.pPushConstantRanges(VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                        .offset(0)
                        .size(pushConstantSize));
            }
            VkUtil.check(vkCreatePipelineLayout(ctx.device, layoutInfo, null, handle), "vkCreatePipelineLayout");
            this.pipelineLayout = handle.get(0);

            var stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(stack.UTF8("main"));

            //No vertex buffers - geometry is generated from gl_VertexIndex for now
            var vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
            var inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            //Viewport and scissor are dynamic so the pipeline survives window resizes
            var viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                    .viewportCount(1).scissorCount(1);
            var dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

            var rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode(cullMode)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .lineWidth(1.0f);

            var multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            var depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                    .depthTestEnable(depthTest)
                    .depthWriteEnable(depthWrite)
                    .depthCompareOp(depthCompareOp)
                    .minDepthBounds(0.0f)
                    .maxDepthBounds(1.0f);

            var blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blendAttachment.get(0)
                    .blendEnable(false)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                            | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            var colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
                    .pAttachments(blendAttachment);

            //Dynamic rendering: declare the attachment formats instead of a VkRenderPass
            var rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR)
                    .pColorAttachmentFormats(stack.ints(colorFormat))
                    .depthAttachmentFormat(depthFormat);

            var pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default()
                    .pNext(rendering)
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterizer)
                    .pMultisampleState(multisampling)
                    .pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlend)
                    .pDynamicState(dynamicState)
                    .layout(this.pipelineLayout)
                    .renderPass(VK_NULL_HANDLE);

            VkUtil.check(vkCreateGraphicsPipelines(ctx.device, VK_NULL_HANDLE, pipelineInfo, null, handle),
                    "vkCreateGraphicsPipelines for " + vertexShaderId);
            this.pipeline = handle.get(0);
        } finally {
            if (vertModule != VK_NULL_HANDLE) vkDestroyShaderModule(ctx.device, vertModule, null);
            if (fragModule != VK_NULL_HANDLE) vkDestroyShaderModule(ctx.device, fragModule, null);
            MemoryUtil.memFree(vertSpirv);
            MemoryUtil.memFree(fragSpirv);
        }
    }

    /** Binds the pipeline and sets viewport/scissor to cover the given target. */
    public void bind(VkCommandBuffer cmd, int width, int height) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipeline);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var viewport = VkViewport.calloc(1, stack)
                    .x(0).y(0)
                    .width(width).height(height)
                    .minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(cmd, 0, viewport);

            var scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).offset(VkOffset2D.calloc(stack).set(0, 0));
            scissor.get(0).extent(VkExtent2D.calloc(stack).set(width, height));
            vkCmdSetScissor(cmd, 0, scissor);
        }
    }

    public void pushConstants(VkCommandBuffer cmd, ByteBuffer data) {
        vkCmdPushConstants(cmd, this.pipelineLayout,
                VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, data);
    }

    private long cachedSet = VK_NULL_HANDLE;
    private long[] cachedBuffers;

    /**
     * Binds storage buffers in the order the shader declares them.
     * <p>
     * The descriptor set is allocated once and reused. Allocating per draw exhausts the pool within
     * seconds, and descriptor sets are not implicitly freed. When the bound resources actually change
     * the set is rewritten in place, which is safe here because bindings are stable across frames -
     * the one exception being a window resize, which changes the bound image views while earlier
     * frames may still be in flight. Minecraft idles the device to rebuild its swapchain, so that
     * window is closed in practice rather than by anything done here.
     * <p>
     * Once geometry buffers start changing per frame this wants either per frame pool resets or
     * VK_KHR_push_descriptor, which this device already has enabled.
     */
    public void bindBuffers(VkCommandBuffer cmd, VkBuffer... buffers) {
        this.bindResources(cmd, buffers, null, null);
    }

    /**
     * Binds storage buffers followed by combined image samplers, in declaration order.
     * Textures occupy binding indices after the buffers.
     */
    public void bindResources(VkCommandBuffer cmd, VkBuffer[] buffers, VkTexture[] textures, VkSampler[] samplers) {
        long[] views = null;
        if (textures != null) {
            views = new long[textures.length];
            for (int i = 0; i < textures.length; i++) {
                views[i] = textures[i].imageView;
            }
        }
        this.bindResources(cmd, buffers, views, samplers, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    /**
     * Binds raw image views rather than textures we own.
     * <p>
     * Needed because some of what voxy samples belongs to Minecraft - its depth buffer above all -
     * and those images arrive as bare handles with a layout Minecraft chose. Minecraft keeps every
     * image it owns in {@code VK_IMAGE_LAYOUT_GENERAL}, so that is what its views must be declared
     * as here; binding them as {@code SHADER_READ_ONLY_OPTIMAL} is a layout mismatch and undefined.
     */
    public void bindResources(VkCommandBuffer cmd, VkBuffer[] buffers, long[] imageViews,
                              VkSampler[] samplers, int imageLayout) {
        int bufferLen = buffers == null ? 0 : buffers.length;
        int textureLen = imageViews == null ? 0 : imageViews.length;
        if (bufferLen != this.bindingCount) {
            throw new IllegalArgumentException("Expected " + this.bindingCount + " buffers but got " + bufferLen);
        }
        if (textureLen != this.textureCount) {
            throw new IllegalArgumentException("Expected " + this.textureCount + " textures but got " + textureLen);
        }
        if (this.bindingCount == 0 && this.textureCount == 0) {
            return;
        }
        var ctx = VkContext.get();

        boolean unchanged = this.cachedSet != VK_NULL_HANDLE && this.cachedBuffers != null
                && this.cachedBuffers.length == bufferLen + textureLen;
        if (unchanged) {
            for (int i = 0; i < bufferLen; i++) {
                if (this.cachedBuffers[i] != buffers[i].buffer) {
                    unchanged = false;
                    break;
                }
            }
            for (int i = 0; unchanged && i < textureLen; i++) {
                if (this.cachedBuffers[bufferLen + i] != imageViews[i]) {
                    unchanged = false;
                }
            }
        }
        if (unchanged) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipelineLayout, 0,
                        stack.longs(this.cachedSet), null);
            }
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (this.cachedSet == VK_NULL_HANDLE) {
                var setAlloc = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(ctx.descriptorPool())
                        .pSetLayouts(stack.longs(this.descriptorSetLayout));
                LongBuffer setHandle = stack.mallocLong(1);
                VkUtil.check(vkAllocateDescriptorSets(ctx.device, setAlloc, setHandle), "vkAllocateDescriptorSets");
                this.cachedSet = setHandle.get(0);
            }
            long descriptorSet = this.cachedSet;

            this.cachedBuffers = new long[bufferLen + textureLen];
            for (int i = 0; i < bufferLen; i++) {
                this.cachedBuffers[i] = buffers[i].buffer;
            }
            for (int i = 0; i < textureLen; i++) {
                this.cachedBuffers[bufferLen + i] = imageViews[i];
            }

            var writes = VkWriteDescriptorSet.calloc(bufferLen + textureLen, stack);
            for (int i = 0; i < bufferLen; i++) {
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
            for (int i = 0; i < textureLen; i++) {
                var imageInfo = VkDescriptorImageInfo.calloc(1, stack);
                imageInfo.get(0)
                        .sampler(samplers[i].sampler)
                        .imageView(imageViews[i])
                        .imageLayout(imageLayout);
                writes.get(bufferLen + i).sType$Default()
                        .dstSet(descriptorSet)
                        .dstBinding(bufferLen + i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(imageInfo)
                        .descriptorCount(1);
            }
            vkUpdateDescriptorSets(ctx.device, writes, null);

            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipelineLayout, 0,
                    stack.longs(descriptorSet), null);
        }
    }

    @Override
    public void free() {
        this.free0();
        var ctx = VkContext.get();
        vkDestroyPipeline(ctx.device, this.pipeline, null);
        vkDestroyPipelineLayout(ctx.device, this.pipelineLayout, null);
        if (this.descriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(ctx.device, this.descriptorSetLayout, null);
        }
    }
}
