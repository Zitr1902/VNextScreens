package dev.vnext.screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Для меню: проверка ссылки (откроется ли видео и почему нет) и превью — обложка YouTube/Rutube или
 * кадр из самого видео. Картинку ffmpeg сразу ужимает в RGBA {@value #W}×{@value #H}, она становится
 * текстурой. Всё в фоне, результат кэшируется по ссылке.
 */
final class Previews {

    static final int W = 160, H = 90;
    private static final int MAX = 48;
    private static final Logger LOG = LoggerFactory.getLogger("VNextScreens");
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "VNextScreens-preview");
        t.setDaemon(true);
        return t;
    });

    enum Status { CHECKING, OK, FAIL }

    /** Что известно о ссылке. Поля меняет фоновый поток, читает меню. */
    static final class Info {
        volatile Status status = Status.CHECKING;
        volatile String title = "", reason = "";
        volatile long durationMs;
        /** Текстура превью (null — ещё нет или не будет). Только из главного потока. */
        Identifier texture;
        DynamicTexture dynamic;
    }

    /** Порядок доступа — старые выкидываются первыми. Только из главного потока. */
    private static final Map<String, Info> cache = new LinkedHashMap<>(16, 0.75f, true);
    private static int counter;

    private Previews() {}

    /** Известное о ссылке; первая просьба запускает проверку и превью в фоне. Только из главного потока. */
    static Info get(String url) {
        Info info = cache.get(url);
        if (info != null) return info;
        Info created = new Info();
        cache.put(url, created);
        trim();
        String lang = Minecraft.getInstance().options.languageCode.split("_")[0];
        POOL.execute(() -> load(url, lang, created));
        return created;
    }

    private static void trim() {
        while (cache.size() > MAX) {
            var it = cache.entrySet().iterator();
            release(it.next().getValue());
            it.remove();
        }
    }

    /** Отключились от сервера — превью больше не нужны. */
    static void clear() {
        cache.values().forEach(Previews::release);
        cache.clear();
    }

    private static void release(Info info) {
        if (info.texture != null) Minecraft.getInstance().getTextureManager().release(info.texture);
        info.texture = null;
        info.dynamic = null;
    }

    private static void load(String url, String lang, Info info) {
        MediaResolver.Resolved r;
        try {
            r = MediaResolver.resolve(url, lang, false);
            long d = r.durationMs();
            // прямая ссылка: ffmpeg заодно скажет, видео ли это вообще
            if (d <= 0) {
                d = Ffmpeg.probeDurationMs(r.video());
                if (d < 0) throw new MediaResolver.Failure("open", "ffmpeg не открыл " + r.video());
            }
            info.title = r.title();
            info.durationMs = d;
            info.status = Status.OK;
        } catch (Exception e) {
            info.reason = MediaResolver.reason(e);
            info.status = Status.FAIL;
            return;
        }
        byte[] rgba = frame(r);
        if (rgba == null) return;
        Minecraft.getInstance().execute(() -> {
            // пока грузили, ссылку могли выкинуть из кэша
            if (cache.get(url) != info) return;
            DynamicTexture t = new DynamicTexture(() -> "vnextscreens preview", W, H, false);
            long ptr = t.getPixels().getPointer();
            for (int i = 0; i < rgba.length; i++) MemoryUtil.memPutByte(ptr + i, rgba[i]);
            t.upload();
            Identifier id = Identifier.fromNamespaceAndPath("vnextscreens", "preview/" + (counter++));
            Minecraft.getInstance().getTextureManager().register(id, t);
            info.dynamic = t;
            info.texture = id;
        });
    }

    /** Обложка или кадр на 3-й секунде, ужатые в W×H RGBA; null — не вышло. */
    private static byte[] frame(MediaResolver.Resolved r) {
        try {
            List<String> a = new ArrayList<>(List.of(Ffmpeg.path(), "-hide_banner", "-loglevel", "error", "-nostdin"));
            String src = r.thumb().isEmpty() ? ChunkProxy.wrap(r.video()) : r.thumb();
            if (src.startsWith("http") && !src.startsWith("http://127.0.0.1:"))
                a.addAll(List.of("-headers", "User-Agent: " + Video.USER_AGENT + "\r\n", "-rw_timeout", "15000000"));
            if (r.thumb().isEmpty()) a.addAll(List.of("-ss", "3"));
            a.addAll(List.of("-i", src, "-frames:v", "1",
                    "-vf", "scale=" + W + ":" + H + ":force_original_aspect_ratio=decrease,pad=" + W + ":" + H + ":(ow-iw)/2:(oh-ih)/2:color=black,format=rgba",
                    "-f", "rawvideo", "-pix_fmt", "rgba", "-"));
            Process p = new ProcessBuilder(a).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] out;
            try (InputStream in = p.getInputStream()) {
                out = in.readNBytes(W * H * 4);
            }
            if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly();
            return out.length == W * H * 4 ? out : null;
        } catch (Exception e) {
            LOG.debug("Превью не получилось: {}", e.toString());
            return null;
        }
    }
}
