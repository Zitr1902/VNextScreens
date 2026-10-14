package dev.vnext.screens;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Сообщение от плагина VNextScreens по каналу vnext:screen. Плагин шлёт «сырые» байты
 * (DataOutputStream), поэтому здесь они просто забираются целиком и разбираются в {@link Screens}.
 */
public record ScreenPayload(byte[] data) implements CustomPacketPayload {

    public static final Type<ScreenPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("vnext", "screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenPayload> CODEC = StreamCodec.of(
            (buf, p) -> buf.writeBytes(p.data),
            buf -> {
                byte[] b = new byte[buf.readableBytes()];
                buf.readBytes(b);
                return new ScreenPayload(b);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
