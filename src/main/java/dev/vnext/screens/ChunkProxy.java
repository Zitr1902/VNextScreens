package dev.vnext.screens;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Локальный прокси для ссылок googlevideo. На открытый диапазон («с байта N и до конца») YouTube
 * отдаёт ~0.5 МБ и рвёт соединение — ffmpeg тогда еле успевает за реальным временем. Здесь
 * ffmpeg читает 127.0.0.1 как обычный файл (с перемоткой), а прокси качает у googlevideo куски
 * с явным диапазоном, как yt-dlp. Браузерный User-Agent не шлётся: на него googlevideo отвечает 403.
 */
final class ChunkProxy {

    private static final Logger LOG = LoggerFactory.getLogger("VNextScreens");
    private static final long CHUNK = 4L << 20;
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d*)");
    private static final Map<String, String> urls = new ConcurrentHashMap<>();
    private static final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static HttpServer server;

    private ChunkProxy() {}

    /** Ссылка googlevideo → адрес на прокси; остальные как есть. */
    static String wrap(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost();
        // только файлы (videoplayback), манифесты ffmpeg читает сам
        if (host == null || !host.endsWith(".googlevideo.com") || !String.valueOf(uri.getPath()).contains("videoplayback")) return url;
        try {
            int port = start();
            String id = UUID.randomUUID().toString();
            if (urls.size() > 64) urls.clear();   // ссылки живут несколько часов, старые не нужны
            urls.put(id, url);
            return "http://127.0.0.1:" + port + "/" + id;
        } catch (IOException e) {
            LOG.warn("Прокси не запустился, читаю напрямую: {}", e.toString());
            return url;
        }
    }

    private static synchronized int start() throws IOException {
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "VNextScreens-proxy");
                t.setDaemon(true);
                return t;
            }));
            server.createContext("/", ChunkProxy::handle);
            server.start();
        }
        return server.getAddress().getPort();
    }

    private static void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String url = urls.get(ex.getRequestURI().getPath().substring(1));
            if (url == null) { ex.sendResponseHeaders(404, -1); return; }
            long total = total(url);
            if (total <= 0) {
                LOG.warn("Прокси: googlevideo не сказал размер файла");
                ex.sendResponseHeaders(502, -1);
                return;
            }
            long from = 0, to = total - 1;
            String range = ex.getRequestHeaders().getFirst("Range");
            Matcher m = range == null ? null : RANGE.matcher(range);
            if (m != null && m.find()) {
                from = Long.parseLong(m.group(1));
                if (!m.group(2).isEmpty()) to = Math.min(to, Long.parseLong(m.group(2)));
            }
            if (from > to) { ex.sendResponseHeaders(416, -1); return; }
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().set("Accept-Ranges", "bytes");
            ex.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + total);
            boolean head = "HEAD".equals(ex.getRequestMethod());
            ex.sendResponseHeaders(range != null ? 206 : 200, head ? -1 : to - from + 1);
            if (head) return;
            OutputStream out = ex.getResponseBody();
            for (long pos = from; pos <= to; ) {
                long end = Math.min(to, pos + CHUNK - 1);
                pos += fetch(url, pos, end, out);
            }
        } catch (IOException e) {
            // ffmpeg закрыл соединение (перемотка или стоп) — это нормально; ответ googlevideo — в лог
            if (e.getMessage() != null && e.getMessage().startsWith("googlevideo")) LOG.warn("Прокси: {}", e.getMessage());
        }
    }

    /** Кусок [from, end] в out, с повторами; возвращает, сколько байт отдано. */
    private static long fetch(String url, long from, long end, OutputStream out) throws IOException {
        IOException last = null;
        long done = 0;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Range", "bytes=" + (from + done) + "-" + end)
                        .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() != 206 && r.statusCode() != 200) {
                    r.body().close();
                    throw new IOException("googlevideo ответил " + r.statusCode());
                }
                try (InputStream in = r.body()) {
                    byte[] buf = new byte[64 << 10];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                    }
                }
                if (from + done > end) return done;
                last = new IOException("кусок оборвался на " + (from + done));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("прервано", e);
            } catch (IOException e) {
                // ошибка записи в ffmpeg — дальше не нужно
                if (done > 0 && e.getMessage() != null && e.getMessage().contains("Broken pipe")) throw e;
                last = e;
            }
        }
        throw last;
    }

    /** Размер файла: параметр clen в ссылке, иначе из Content-Range первого байта. */
    private static long total(String url) throws IOException {
        Matcher m = Pattern.compile("[?&]clen=(\\d+)").matcher(url);
        if (m.find()) return Long.parseLong(m.group(1));
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("Range", "bytes=0-0")
                    .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.discarding());
            String cr = r.headers().firstValue("Content-Range").orElse("");
            int slash = cr.lastIndexOf('/');
            return slash >= 0 ? Long.parseLong(cr.substring(slash + 1).trim()) : -1;
        } catch (InterruptedException | NumberFormatException e) {
            return -1;
        }
    }
}
