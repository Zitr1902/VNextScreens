package dev.vnext.screens;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Свой ползунок «Видео на экранах» во вкладке «Музыка и звуки»; значение в config/vnextscreens.properties. */
final class Volume {

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("vnextscreens.properties");
    private static volatile double value = load();

    static final OptionInstance<Double> OPTION = new OptionInstance<>(
            "soundCategory.vnextscreens",
            OptionInstance.noTooltip(),
            (caption, v) -> v == 0
                    ? Component.translatable("options.generic_value", caption, Component.translatable("options.off"))
                    : Component.translatable("options.percent_value", caption, (int) Math.round(v * 100)),
            OptionInstance.UnitDouble.INSTANCE,
            value,
            v -> { value = v; save(); });

    private Volume() {}

    static double get() {
        return value;
    }

    private static double load() {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(FILE)) {
            p.load(r);
            return Math.max(0, Math.min(1, Double.parseDouble(p.getProperty("volume", "1"))));
        } catch (IOException | NumberFormatException e) {
            return 1;
        }
    }

    private static void save() {
        Properties p = new Properties();
        p.setProperty("volume", String.valueOf(value));
        try (Writer w = Files.newBufferedWriter(FILE)) {
            p.store(w, "VNext Screens");
        } catch (IOException ignored) {
        }
    }
}
