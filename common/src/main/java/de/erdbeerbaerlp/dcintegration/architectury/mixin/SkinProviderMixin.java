package de.erdbeerbaerlp.dcintegration.architectury.mixin;

import de.erdbeerbaerlp.dcintegration.architectury.webhook.SkinAvatars;
import de.erdbeerbaerlp.dcintegration.common.threads.APITestTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The external core reloads its own settings; normalize again before each HTTP probe. */
@Mixin(value = APITestTask.class, remap = false)
public class SkinProviderMixin {
    @Inject(method = "run", at = @At("HEAD"), remap = false)
    private void useCrafthead(CallbackInfo ci) {
        SkinAvatars.normalizeCoreConfig();
    }
}
