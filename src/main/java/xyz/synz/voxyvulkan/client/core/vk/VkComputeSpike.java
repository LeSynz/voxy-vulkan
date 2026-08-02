package xyz.synz.voxyvulkan.client.core.vk;

import xyz.synz.voxyvulkan.common.Logger;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

/**
 * Validates the Vulkan layer by running one of voxy's real compute shaders through it.
 * <p>
 * {@code util/memcpy.comp} is a good first target: three storage buffers, bindings supplied by
 * defines, and no loose uniforms (which Vulkan GLSL rejects outright). If this passes, the
 * {@link VkContext}/{@link VkBuffer}/{@link VkComputePipeline} stack is sound enough to port the
 * rest of the compute shaders onto. Run with {@code -Dvoxy.vkSpike=true}.
 */
public class VkComputeSpike {
    private static final int COPY_ELEMENTS = 64;   //uvec2 elements to copy
    private static final int DEST_OFFSET = 64;     //in uvec2 elements
    private static final int OUTPUT_ELEMENTS = 128;
    private static final int UVEC2_BYTES = 8;

    public static void run() {
        try {
            Logger.info("[vk-spike] starting Vulkan compute layer test");
            boolean ok = execute();
            if (ok) {
                Logger.info("[vk-spike] PASSED - voxy's memcpy.comp ran correctly through the Vulkan layer");
            } else {
                Logger.error("[vk-spike] FAILED - dispatch ran but results were wrong");
            }
        } catch (Throwable t) {
            Logger.error("[vk-spike] FAILED with an exception", t);
        }
    }

    private static boolean execute() {
        var ctx = VkContext.get();
        Logger.info("[vk-spike] device=" + VkInterop.device().getDeviceInfo().name()
                + " computeQueueFamily=" + ctx.computeQueue.queueFamilyIndex());

        VkComputePipeline pipeline = null;
        VkBuffer header = null;
        VkBuffer input = null;
        VkBuffer output = null;
        try {
            pipeline = new VkComputePipeline("voxy:util/memcpy.comp", 3, 0, List.of(
                    "INPUT_HEADER_BUFFER_BINDING 0",
                    "INPUT_DATA_BUFFER_BINDING 1",
                    "OUTPUT_BUFFER_BINDING 2"
            ));
            Logger.info("[vk-spike] compiled and built pipeline for voxy:util/memcpy.comp");

            header = new VkBuffer(16, true);
            input = new VkBuffer((long) COPY_ELEMENTS * UVEC2_BYTES, true);
            output = new VkBuffer((long) OUTPUT_ELEMENTS * UVEC2_BYTES, true);
            output.zero();

            //One job: copy COPY_ELEMENTS uvec2s from index 0 to index DEST_OFFSET
            long headerPtr = header.mappedPointer();
            MemoryUtil.memPutInt(headerPtr, 0);
            MemoryUtil.memPutInt(headerPtr + 4, DEST_OFFSET);
            MemoryUtil.memPutInt(headerPtr + 8, COPY_ELEMENTS);
            MemoryUtil.memPutInt(headerPtr + 12, 0);

            long inputPtr = input.mappedPointer();
            for (int i = 0; i < COPY_ELEMENTS; i++) {
                MemoryUtil.memPutInt(inputPtr + (long) i * UVEC2_BYTES, i * 2 + 1);
                MemoryUtil.memPutInt(inputPtr + (long) i * UVEC2_BYTES + 4, i * 2 + 2);
            }
            header.flush();
            input.flush();

            final var pipe = pipeline;
            final var h = header;
            final var in = input;
            final var out = output;
            ctx.submitBlocking(cmd -> {
                pipe.dispatch(cmd, 1, 1, 1, h, in, out);
                VkComputePipeline.hostReadBarrier(cmd);
            });
            output.invalidate();

            long outputPtr = output.mappedPointer();
            int errors = 0;
            for (int i = 0; i < OUTPUT_ELEMENTS; i++) {
                //Only the destination window should have been written
                boolean inWindow = i >= DEST_OFFSET && i < DEST_OFFSET + COPY_ELEMENTS;
                int src = i - DEST_OFFSET;
                int expectedX = inWindow ? src * 2 + 1 : 0;
                int expectedY = inWindow ? src * 2 + 2 : 0;

                int actualX = MemoryUtil.memGetInt(outputPtr + (long) i * UVEC2_BYTES);
                int actualY = MemoryUtil.memGetInt(outputPtr + (long) i * UVEC2_BYTES + 4);
                if (actualX != expectedX || actualY != expectedY) {
                    if (errors < 5) {
                        Logger.error("[vk-spike] mismatch at " + i + ": expected ("
                                + expectedX + "," + expectedY + ") got (" + actualX + "," + actualY + ")");
                    }
                    errors++;
                }
            }
            return errors == 0;
        } finally {
            if (pipeline != null) pipeline.free();
            if (header != null) header.free();
            if (input != null) input.free();
            if (output != null) output.free();
        }
    }
}
