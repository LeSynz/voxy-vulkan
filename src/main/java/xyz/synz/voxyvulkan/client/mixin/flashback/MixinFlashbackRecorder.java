package xyz.synz.voxyvulkan.client.mixin.flashback;

import com.moulberry.flashback.record.FlashbackMeta;
import com.moulberry.flashback.record.Recorder;
import xyz.synz.voxyvulkan.client.VoxyClientInstance;
import xyz.synz.voxyvulkan.client.compat.IFlashbackMeta;
import xyz.synz.voxyvulkan.commonImpl.VoxyCommon;
import net.minecraft.core.RegistryAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Recorder.class, remap = false)
public class MixinFlashbackRecorder {
    @Shadow @Final private FlashbackMeta metadata;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$getStoragePath(RegistryAccess registryAccess, CallbackInfo retInf) {
        if (VoxyCommon.isAvailable()) {
            var instance = VoxyCommon.getInstance();
            if (instance instanceof VoxyClientInstance ci) {
                ((IFlashbackMeta)this.metadata).setVoxyPath(ci.getStorageBasePath().toFile());
            }
        }
    }
}
