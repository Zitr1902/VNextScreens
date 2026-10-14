package dev.vnext.screens;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ссылка → прямые адреса потоков. YouTube разбирается у игрока через NewPipeExtractor (как у
 * Dream Displays): прямые ссылки googlevideo привязаны к IP того, кто их получил, поэтому
 * получать их должен тот же компьютер, что и смотрит. Остальные ссылки отдаются ffmpeg как есть.
 */
final class MediaResolver {

    /**
     * Куда идти за картинкой и за звуком (может быть один и тот же адрес), длина (0 — неизвестна), название
     * и обложка для меню (пусто — нет, тогда кадр из самого видео).
     */
    record Resolved(String video, String audio, long durationMs, String title, String thumb) {
        Resolved withDuration(long ms) {
            return new Resolved(video, audio, ms, title, thumb);
        }
    }

    /** Почему видео не откроется — серверу, а он скажет модератору (коды — ключи reasons.* в конфиге плагина). */
    static final class Failure extends IOException {
        final String reason;

        Failure(String reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    /** Код причины для любой ошибки разбора. */
    static String reason(Throwable e) {
        if (e instanceof Failure f) return f.reason;
        String name = e.getClass().getSimpleName(), msg = String.valueOf(e.getMessage());
        if (name.contains("AgeRestricted") || msg.contains("confirm your age") || msg.toLowerCase(Locale.ROOT).contains("age-restricted")) return "age";
        if (msg.contains("LOGIN_REQUIRED")) return "login";
        if (name.contains("Private")) return "private";
        if (name.contains("ContentNotAvailable") || name.contains("GeographicRestriction") || name.contains("Paid")) return "unavailable";
        if (msg.contains("URL not accepted")) return "unsupported";
        return "other";
    }

    /** Разобранные ссылки на 20 минут: перемотка перезапускает ffmpeg, и разбирать заново не нужно. */
    private record Cached(Resolved value, long until) {}
    private static final java.util.Map<String, Cached> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long TTL_MS = 20 * 60_000;

    private static final int MAX_HEIGHT = 720;
    private static volatile boolean initialized;

    private MediaResolver() {}

    /** lang — язык игры («ru»): на нём выбирается дорожка, если у видео их несколько. */
    static Resolved resolve(String url, String lang, boolean fresh) throws Exception {
        String key = lang + " " + url;
        Cached c = cache.get(key);
        if (!fresh && c != null && c.until > System.currentTimeMillis()) return c.value;
        Resolved r = resolveNow(url, lang);
        cache.put(key, new Cached(r, System.currentTimeMillis() + TTL_MS));
        return r;
    }

    private static Resolved resolveNow(String url, String lang) throws Exception {
        String host = URI.create(url).getHost();
        host = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (host.equals("youtu.be") || host.endsWith("youtube.com")) return youtube(youtubeWatch(url), lang);
        if (host.equals("rutube.ru") || host.endsWith(".rutube.ru")) return rutube(url);
        if (host.equals("twitch.tv") || host.endsWith(".twitch.tv")) throw new Failure("unsupported", "Twitch не поддерживается");
        return new Resolved(url, url, 0, "", "");
    }

    private static final java.util.regex.Pattern YT_ID = java.util.regex.Pattern.compile(
            "(?:youtu\\.be/|[?&]v=|/(?:live|shorts|embed|v)/)([A-Za-z0-9_-]{11})");

    /**
     * Любая ссылка на ролик YouTube → https://www.youtube.com/watch?v=ID. NewPipe не принимает ссылки
     * из миксов и плейлистов («watch?v=…&list=RD…»), а модераторы копируют именно такие.
     */
    static String youtubeWatch(String url) {
        java.util.regex.Matcher m = YT_ID.matcher(url);
        return m.find() ? "https://www.youtube.com/watch?v=" + m.group(1) : url;
    }

    private static final java.util.regex.Pattern RUTUBE_ID = java.util.regex.Pattern.compile("/(?:video|play/embed|live/video)/(?:private/)?([0-9a-f]{32})");

    /** Rutube: страница ролика → HLS-плейлист через их API (ffmpeg сам выбирает лучшее качество). */
    private static Resolved rutube(String url) throws Exception {
        java.util.regex.Matcher m = RUTUBE_ID.matcher(url);
        if (!m.find()) throw new Failure("unsupported", "не ссылка на ролик Rutube");
        String body = HTTP.send(HttpRequest.newBuilder(URI.create("https://rutube.ru/api/play/options/" + m.group(1) + "/?format=json"))
                .header("User-Agent", Video.USER_AGENT).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        com.google.gson.JsonObject acl = o.has("acl_access") ? o.getAsJsonObject("acl_access") : null;
        if (acl != null && !acl.get("allowed").getAsBoolean()) throw new Failure("unavailable", "Rutube: " + acl.get("err_text"));
        String title = o.has("title") && !o.get("title").isJsonNull() ? o.get("title").getAsString() : "";
        com.google.gson.JsonObject live = o.has("live_streams") && o.get("live_streams").isJsonObject() ? o.getAsJsonObject("live_streams") : null;
        if (live != null && live.has("hls") && live.getAsJsonArray("hls").size() > 0) throw new Failure("live", "эфир Rutube");
        com.google.gson.JsonObject vb = o.has("video_balancer") && o.get("video_balancer").isJsonObject() ? o.getAsJsonObject("video_balancer") : null;
        String m3u8 = vb == null ? null : vb.has("m3u8") ? vb.get("m3u8").getAsString() : vb.has("default") ? vb.get("default").getAsString() : null;
        if (m3u8 == null) throw new Failure("unavailable", "Rutube не отдал видео");
        long duration = o.has("duration") && !o.get("duration").isJsonNull() ? o.get("duration").getAsLong() : 0;   // уже в мс
        String one = variant(m3u8);
        String thumb = o.has("thumbnail_url") && !o.get("thumbnail_url").isJsonNull() ? o.get("thumbnail_url").getAsString() : "";
        return new Resolved(one, one, duration, title, thumb);
    }

    private static final java.util.regex.Pattern WIDTH = java.util.regex.Pattern.compile("RESOLUTION=(\\d+)x\\d+");

    /**
     * Из общего плейлиста — одно качество до 720p (шириной до 1280). Иначе ffmpeg по очереди открывает
     * все дюжину вариантов, и видео стартует ~14 с вместо одной.
     */
    private static String variant(String master) {
        try {
            String body = HTTP.send(HttpRequest.newBuilder(URI.create(master)).header("User-Agent", Video.USER_AGENT)
                    .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            String[] lines = body.split("\\R");
            String best = null;
            int bestW = 0;
            for (int i = 0; i + 1 < lines.length; i++) {
                if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
                java.util.regex.Matcher m = WIDTH.matcher(lines[i]);
                if (!m.find()) continue;
                int w = Integer.parseInt(m.group(1));
                String uri = lines[i + 1].trim();
                if (w <= 1280 && w > bestW && !uri.isEmpty() && !uri.startsWith("#")) {
                    best = URI.create(master).resolve(uri).toString();
                    bestW = w;
                }
            }
            return best != null ? best : master;
        } catch (Exception e) {
            return master;
        }
    }

    private static Resolved youtube(String url, String lang) throws Exception {
        init();
        StreamExtractor ex = NewPipe.getServiceByUrl(url).getStreamExtractor(url);
        ex.fetchPage();
        // адаптивные дорожки: картинка без звука до 720p и отдельный звук
        VideoStream video = ex.getVideoOnlyStreams().stream()
                .filter(MediaResolver::progressive)
                .filter(s -> s.getHeight() > 0 && s.getHeight() <= MAX_HEIGHT)
                // 720p, затем 60 кадров (если у ролика есть), затем H.264 — его ffmpeg декодирует легче VP9/AV1
                .max(Comparator.<VideoStream>comparingInt(VideoStream::getHeight)
                        .thenComparingInt(s -> Math.min(s.getFps(), Video.FPS))
                        .thenComparingInt(s -> s.getCodec() != null && s.getCodec().startsWith("avc1") ? 1 : 0)
                        .thenComparingInt(s -> s.getFormat() == MediaFormat.MPEG_4 ? 1 : 0))
                .orElse(null);
        // дорожка: на языке игры, иначе оригинальная; аудиоописание — в последнюю очередь
        AudioStream audio = ex.getAudioStreams().stream()
                .filter(MediaResolver::progressive)
                .max(Comparator.<AudioStream>comparingInt(s -> track(s, lang))
                        .thenComparingInt(s -> s.getFormat() == MediaFormat.M4A ? 1 : 0)
                        .thenComparingInt(AudioStream::getAverageBitrate))
                .orElse(null);
        long length = ex.getLength() * 1000;
        String title = ex.getName() == null ? "" : ex.getName();
        // прямой эфир YouTube отдаёт только по HLS, а его ffmpeg открывает через раз и с задержкой в десятки секунд
        org.schabi.newpipe.extractor.stream.StreamType type = ex.getStreamType();
        if (type == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM || type == org.schabi.newpipe.extractor.stream.StreamType.AUDIO_LIVE_STREAM)
            throw new Failure("live", "прямой эфир YouTube");
        // обложка 320×180 есть у любого ролика
        java.util.regex.Matcher id = YT_ID.matcher(url);
        String thumb = id.find() ? "https://i.ytimg.com/vi/" + id.group(1) + "/mqdefault.jpg" : "";
        if (video != null && audio != null) return new Resolved(video.getContent(), audio.getContent(), length, title, thumb);
        // YouTube часто отдаёт только общий поток 360p — берём его и для картинки, и для звука
        VideoStream muxed = ex.getVideoStreams().stream()
                .filter(MediaResolver::progressive)
                .max(Comparator.comparingInt(VideoStream::getHeight))
                .orElseThrow(() -> new Failure("unavailable", "YouTube не отдал ни одного потока"));
        return new Resolved(video != null ? video.getContent() : muxed.getContent(),
                audio != null ? audio.getContent() : muxed.getContent(), length, title, thumb);
    }

    private static int track(AudioStream s, String lang) {
        int score = 0;
        Locale l = s.getAudioLocale();
        if (l != null && lang != null && l.getLanguage().equalsIgnoreCase(lang)) score += 4;
        if (s.getAudioTrackType() == AudioTrackType.ORIGINAL) score += 2;
        if (s.getAudioTrackType() == AudioTrackType.DESCRIPTIVE) score -= 8;
        return score;
    }

    private static boolean progressive(Stream s) {
        return s.isUrl() && s.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP;
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static synchronized void init() {
        if (initialized) return;
        NewPipe.init(new Http());
        initialized = true;
    }

    /** HTTP для NewPipeExtractor на встроенном клиенте Java. */
    private static final class Http extends Downloader {
        private final HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        @Override
        public Response execute(Request request) throws IOException {
            byte[] body = request.dataToSend();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(request.url()))
                    .timeout(Duration.ofSeconds(15))
                    .method(request.httpMethod(), body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            boolean ua = false;
            for (Map.Entry<String, List<String>> h : request.headers().entrySet()) {
                if (h.getKey().equalsIgnoreCase("user-agent")) ua = true;
                for (String v : h.getValue()) b.header(h.getKey(), v);
            }
            if (!ua) b.header("User-Agent", Video.USER_AGENT);
            try {
                HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
                return new Response(r.statusCode(), "", r.headers().map(), r.body(), r.uri().toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("прервано", e);
            }
        }
    }
}
