package xyz.synz.voxyvulkan.client.mixin.minecraft;

import xyz.synz.voxyvulkan.client.config.VoxyConfig;
import xyz.synz.voxyvulkan.client.core.IVoxyRenderSystemHolder;
import xyz.synz.voxyvulkan.client.core.vk.VkLodRenderer;
import xyz.synz.voxyvulkan.common.Logger;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = FogRenderer.class, priority = 900)//We must execute before sodium
public class MixinFogRenderer {
    //Kill switch: -Dvoxy.fogPatch=false leaves Minecraft's fog completely untouched, so it can be
    //ruled in or out as the cause of something without rebuilding.
    @Unique
    private static final boolean VOXY$FOG_PATCH =
            !System.getProperty("voxy.fogPatch", "true").equalsIgnoreCase("false");
    @Unique
    private static String voxy$lastLogged = "";

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void voxy$modifyFog(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level, CallbackInfoReturnable<FogData> cir) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) return;
        if (!VOXY$FOG_PATCH) return;

        //On Vulkan the GL render system is never created, so check the Vulkan renderer too.
        //Without this, vanilla keeps fading terrain out at its own render distance and the LOD
        //terrain beyond it starts abruptly, leaving a bright foggy ring around the player.
        if (IVoxyRenderSystemHolder.getNullable() == null && VkLodRenderer.getActive() == null) {
            return;
        }
        var data = cir.getReturnValue();
        boolean fogIsDamnClose = data.environmentalEnd<10;
        if (!VoxyConfig.CONFIG.useEnvironmentalFog && !fogIsDamnClose) {
            //Kept distinct. Equal start and end divide by zero in the fog factor, and the NaN takes
            //the whole world with it - which is what happened above roughly y 255, where
            //environmental fog starts being applied.
            data.environmentalStart = 8_000_000f;
            data.environmentalEnd = 9_000_000f;
        }

        if (IVoxyRenderSystemHolder.getNullable() != null) {
            data.renderDistanceStart = 999999999;
            data.renderDistanceEnd = 999999999;
            return;
        }

        //Disable render distance fog outright, the same intent as the OpenGL path above, but with
        //start and end distinct so the fog factor's divide by (end - start) stays finite.
        //
        //Do NOT set this to the edge of the LOD range as a way of fading LODs out. That is real fog
        //with a real range: everything past the start washes out. On the ground terrain hides it,
        //but from high up you can see far enough that the whole world fades away.
        data.renderDistanceStart = 8_000_000f;
        data.renderDistanceEnd = 9_000_000f;

        //Report the values we hand back whenever they change, so a bad fog range is visible rather
        //than inferred. Also reports camera height, since the symptom is altitude dependent.
        String summary = "env " + (int) data.environmentalStart + "-" + (int) data.environmentalEnd
                + " rd " + (int) data.renderDistanceStart + "-" + (int) data.renderDistanceEnd
                + " skyEnd " + (int) data.skyEnd + " cloudEnd " + (int) data.cloudEnd;
        if (!summary.equals(voxy$lastLogged)) {
            voxy$lastLogged = summary;
            Logger.info("[vk-fog] y=" + (int) camera.position().y + " " + summary);
        }
    }
}
