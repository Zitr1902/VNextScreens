package dev.vnext.screens.mixin;

import dev.vnext.screens.VNextScreensClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ПКМ по экрану у игрока с правом — меню экрана вместо обычного действия. */
@Mixin(Minecraft.class)
abstract class MinecraftUseMixin {

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void vnextscreens$openMenu(CallbackInfo ci) {
        if (VNextScreensClient.onUse((Minecraft) (Object) this)) ci.cancel();
    }
}
