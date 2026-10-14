package dev.vnext.screens.mixin;

import dev.vnext.screens.VNextScreensClient;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Arrays;

/** Ползунок «Видео на экранах» — в общую сетку с остальными категориями звука. */
@Mixin(SoundOptionsScreen.class)
abstract class SoundOptionsScreenMixin {

    @ModifyArg(method = "addOptions", index = 0, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/gui/components/OptionsList;addSmall([Lnet/minecraft/client/OptionInstance;)V"))
    private OptionInstance<?>[] vnextscreens$addVolume(OptionInstance<?>[] options) {
        OptionInstance<?>[] out = Arrays.copyOf(options, options.length + 1);
        out[options.length] = VNextScreensClient.volumeOption();
        return out;
    }
}
