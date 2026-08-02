package xyz.synz.voxyvulkan.client.core;

import xyz.synz.voxyvulkan.client.core.rendering.hierachical.AsyncNodeManager;
import xyz.synz.voxyvulkan.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import xyz.synz.voxyvulkan.client.core.rendering.hierachical.NodeCleaner;

import java.util.function.BooleanSupplier;

public class RenderPipelineFactory {
    public static AbstractRenderPipeline createPipeline(RenderProperties properties, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        //Iris has no Vulkan rendering path, so this fork only ever builds the normal pipeline
        return new NormalRenderPipeline(properties, nodeManager, nodeCleaner, traversal, frexSupplier);
    }
}
