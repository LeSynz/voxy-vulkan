package xyz.synz.voxyvulkan.client.core.vk;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import xyz.synz.voxyvulkan.common.Logger;

import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Reads Minecraft's block atlas back off the GPU, which the model bakery needs as plain pixels to
 * rasterise block models against.
 * <p>
 * The OpenGL path does this with one blocking {@code glGetTextureImage}. Blaze3D's backend agnostic
 * equivalent is {@code copyTextureToBuffer}, which is <em>asynchronous</em> - it takes a completion
 * callback that only runs once the render thread has driven the frame far enough for the copy to
 * finish. Waiting for it on the render thread therefore deadlocks: the thread that must make
 * progress is the one blocked.
 * <p>
 * So the copy is started from the render thread and never waited for there. The bakery runs on its
 * own thread and blocks on {@link #await} instead, which is safe precisely because the render thread
 * is free to keep going and eventually fire the callback.
 */
public final class VkAtlasDownloader {
    private VkAtlasDownloader() {}

    private static final CountDownLatch DONE = new CountDownLatch(1);
    private static volatile int[] pixels;
    private static volatile int width;
    private static volatile int height;
    private static volatile boolean failed;
    private static boolean started;

    /**
     * Kicks off the copy. Render thread only, returns immediately, safe to call every frame.
     * <p>
     * Called from a point that runs before the world has necessarily finished loading, so an atlas
     * that is not there yet is not a failure - it simply leaves {@code started} false and is
     * retried on the next frame. Only a real error latches, because latching on 'too early' would
     * mean the bakery waits out its timeout for something nobody ever asked for.
     */
    public static synchronized void start() {
        if (started) {
            return;
        }
        com.mojang.blaze3d.textures.GpuTexture texture;
        try {
            var managed = Minecraft.getInstance().getTextureManager()
                    .getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"));
            texture = managed == null ? null : managed.getTexture();
        } catch (Throwable t) {
            return;//Not loaded yet
        }
        if (texture == null || texture.isClosed()) {
            return;
        }
        started = true;
        try {
            if (texture.getFormat() != GpuFormat.RGBA8_UNORM) {
                throw new IllegalStateException("Block atlas is not RGBA8: " + texture.getFormat());
            }
            int w = texture.getWidth(0);
            int h = texture.getHeight(0);
            long bytes = (long) w * h * 4L;

            var device = RenderSystem.getDevice();
            var buffer = device.createBuffer(() -> "voxy block atlas readback",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);

            Logger.info("[vk-atlas] downloading block atlas " + w + "x" + h
                    + " (" + (bytes >> 20) + " MiB)");
            device.createCommandEncoder().copyTextureToBuffer(texture, buffer, 0, () -> {
                try (var view = buffer.map(true, false)) {
                    var out = new int[w * h];
                    //Reinterpreted rather than reassembled byte by byte. The rasteriser is fed by
                    //glGetTextureImage on the OpenGL path, which writes R,G,B,A into memory and
                    //leaves Java to read that back as one int - so on a little endian machine it
                    //sees 0xAABBGGRR. Building 0xAARRGGBB here instead swaps red and blue, which
                    //shows up as blue foliage and purple water. Matching the memory layout exactly
                    //is both simpler and the only way to be certain it agrees.
                    view.data().order(ByteOrder.nativeOrder()).asIntBuffer().get(out);
                    pixels = out;
                    width = w;
                    height = h;
                    Logger.info("[vk-atlas] block atlas ready, " + out.length + " pixels");
                } catch (Throwable t) {
                    failed = true;
                    Logger.error("[vk-atlas] failed reading back the block atlas", t);
                } finally {
                    buffer.close();
                    DONE.countDown();
                }
            }, 0);
        } catch (Throwable t) {
            failed = true;
            Logger.error("[vk-atlas] could not start the block atlas download", t);
            DONE.countDown();
        }
    }

    /**
     * Blocks until the atlas has arrived. **Never call this from the render thread** - that is the
     * thread which has to run for the copy to complete.
     *
     * @return true if pixels are available
     */
    public static boolean await(long timeoutSeconds) {
        try {
            if (!DONE.await(timeoutSeconds, TimeUnit.SECONDS)) {
                Logger.error("[vk-atlas] timed out waiting for the block atlas after "
                        + timeoutSeconds + "s");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !failed && pixels != null;
    }

    public static int[] pixels() {
        return pixels;
    }

    public static int width() {
        return width;
    }

    public static int height() {
        return height;
    }
}
