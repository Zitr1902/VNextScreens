package dev.vnext.screens;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;

/** Мод VNext Screens: видео на экранах, которыми управляет плагин VNextScreens; меню — ПКМ по экрану (если можно). */
public class VNextScreensClient implements ClientModInitializer {

    /** Для миксина ПКМ: открыть меню экрана, если игрок смотрит на экран и ему можно. */
    public static boolean onUse(Minecraft mc) {
        return Screens.onUse(mc);
    }

    /** Для миксина экрана «Музыка и звуки». */
    public static net.minecraft.client.OptionInstance<Double> volumeOption() {
        return Volume.OPTION;
    }

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.clientboundPlay().register(ScreenPayload.TYPE, ScreenPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ScreenPayload.TYPE, ScreenPayload.CODEC);   // длина видео серверу
        ClientPlayNetworking.registerGlobalReceiver(ScreenPayload.TYPE, (payload, ctx) -> Screens.onPayload(payload.data()));

        ClientTickEvents.END_CLIENT_TICK.register(Screens::tick);
        // новый мир или переход на другой сервер за прокси — старые экраны не к месту
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((mc, level) -> Screens.clear());
        // сразу, а не задачей на потом: при выходе в меню звук иначе успевает перезапуститься и играет дальше
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            if (mc.isSameThread()) Screens.disconnected();
            else mc.execute(Screens::disconnected);
        });

        LevelRenderEvents.START_MAIN.register(ctx -> Screens.uploadFrames(Minecraft.getInstance()));
        LevelRenderEvents.BEFORE_GIZMOS.register(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) Screens.render(ctx.poseStack(), ctx.submitNodeCollector(), mc.gameRenderer.mainCamera());
        });
    }
}
