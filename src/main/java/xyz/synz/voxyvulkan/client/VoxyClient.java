package xyz.synz.voxyvulkan.client;

import com.mojang.blaze3d.systems.RenderSystem;
import xyz.synz.voxyvulkan.client.core.gl.Capabilities;
import xyz.synz.voxyvulkan.client.core.rendering.util.SharedIndexBuffer;
import xyz.synz.voxyvulkan.client.core.vk.VkComputeSpike;
import xyz.synz.voxyvulkan.client.core.vk.VkInterop;
import xyz.synz.voxyvulkan.common.Logger;
import xyz.synz.voxyvulkan.commonImpl.VoxyCommon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

public class VoxyClient implements ClientModInitializer {
    private static final HashSet<String> FREX = new HashSet<>();
    private static FileLock EXCLUSIVE_LOCK;
    private static boolean GRAPHICS_SPIKE;
    private static boolean VULKAN_MODE;

    public static boolean isGraphicsSpikeEnabled() {
        return GRAPHICS_SPIKE;
    }

    /** True when running the Vulkan LOD path instead of the OpenGL VoxyRenderSystem. */
    public static boolean isVulkanMode() {
        return VULKAN_MODE;
    }

    //26.2 can run on either the OpenGL or the Vulkan backend. Voxy's renderer is built directly
    //on OpenGL 4.6, so on any other backend we have to bail out here, before Capabilities' clinit
    //calls GL.getCapabilities() and throws (there is no GL context on the render thread).
    public static boolean isBackendSupported() {
        var device = RenderSystem.tryGetDevice();
        return device != null && device.getDeviceInfo().backendName().equals("OpenGL");
    }

    public static void initVoxyClient() {
        if (!isBackendSupported()) {
            var device = RenderSystem.tryGetDevice();
            var backend = device == null ? "<none>" : device.getDeviceInfo().backendName();
            Logger.error("Voxy requires the OpenGL graphics backend, but Minecraft is running on '" + backend
                    + "'. Voxy has been disabled. Set the Graphics API back to OpenGL in Video Settings to use Voxy.");

            if (VkInterop.isVulkanBackend()) {
                //Voxy's world storage, section data and ingest are backend agnostic - nothing under
                //common/world or commonImpl touches OpenGL - so the instance can run on Vulkan and
                //read saved LODs. Only VoxyRenderSystem is GL bound, and MixinLevelRenderer skips
                //creating it here, using VkLodRenderer instead.
                VULKAN_MODE = true;
                VoxyCommon.setInstanceFactory(VoxyClientInstance::new);
                Logger.info("[vk-lod] Vulkan backend: world engine enabled, GL renderer skipped");

                if (System.getProperty("voxy.vkSpike", "false").equalsIgnoreCase("true")) {
                    VkComputeSpike.run();
                    //Checked per render pass, so resolve it once here rather than reading the property hot
                    GRAPHICS_SPIKE = true;
                    Logger.info("[vk-gfx-spike] enabled, will draw a world anchored lattice in the terrain pass");
                }
            }
            return;
        }

        Capabilities.init();//Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        if (!systemSupported) {
             Logger.error("Voxy is unsupported on your system.");
        }

        if (systemSupported && System.getProperty("voxy.exclusiveLock", "false").equalsIgnoreCase("true")) {
            //Try acquire the lock file
            var vf = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy");
            if (!vf.toFile().isDirectory()) {
                vf.toFile().mkdir();
            }
            try {
                FileOutputStream fis = new FileOutputStream(vf.resolve("voxy.lock").toFile());
                EXCLUSIVE_LOCK = fis.getChannel().lock(0, Long.MAX_VALUE, false);
            } catch (NonWritableChannelException | IOException e) {
                //If some error write to log and unsupport
                Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
                systemSupported = false;
            }

        }

        if (systemSupported) {

            SharedIndexBuffer.INSTANCE.id();

            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }

        }
    }

    @Override
    public void onInitializeClient() {
        DebugEntries.init();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            if (VoxyCommon.isAvailable()) {
                dispatcher.register(VoxyCommands.register());
            }
        });

        FabricLoader.getInstance()
                .getEntrypoints("frex_flawless_frames", Consumer.class)
                .forEach(api -> ((Consumer<Function<String,Consumer<Boolean>>>)api).accept(name->active->{if (active) {
                    FREX.add(name);
                } else {
                    FREX.remove(name);
                }}));
    }

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;// getOcclusionDebugState() != 0;
    }
}