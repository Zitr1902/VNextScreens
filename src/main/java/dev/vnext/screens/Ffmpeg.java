package dev.vnext.screens;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * ffmpeg у игрока, качается при первом видео в .minecraft/vnextscreens/ffmpeg: на Windows и macOS —
 * сборка одним файлом в .gz (eugeneware/ffmpeg-static), на Linux — системный или BtbN (см. {@link #linux}).
 */
final class Ffmpeg {

    private static final Logger LOG = LoggerFactory.getLogger("VNextScreens");
    private static final String RELEASES = "https://github.com/eugeneware/ffmpeg-static/releases/latest/download/";
    private static final String BTBN = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/";
    private static volatile String path;

    private Ffmpeg() {}

    static synchronized String path() throws IOException {
        if (path != null) return path;
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        Path dir = FabricLoader.getInstance().getGameDir().resolve("vnextscreens").resolve("ffmpeg");
        if (!os.contains("win") && !os.contains("mac")) return path = linux(dir);
        String asset = asset();
        if (asset == null) {
            LOG.warn("Нет готового ffmpeg для {} {}, беру системный", System.getProperty("os.name"), System.getProperty("os.arch"));
            return path = "ffmpeg";
        }
        boolean win = asset.contains("win32");
        Path bin = dir.resolve(win ? "ffmpeg.exe" : "ffmpeg");
        if (!Files.isRegularFile(bin) || Files.size(bin) == 0) {
            Files.createDirectories(dir);
            LOG.info("Качаю ffmpeg: {}", asset);
            Path tmp = download(RELEASES + asset, dir);
            try (InputStream in = new GZIPInputStream(Files.newInputStream(tmp))) {
                Files.copy(in, bin, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            if (!win) bin.toFile().setExecutable(true);
            LOG.info("ffmpeg готов: {}", bin);
        }
        return path = bin.toAbsolutePath().toString();
    }

    /**
     * Linux: полностью статические сборки (eugeneware/johnvansickle) падают на первом же DNS-запросе,
     * поэтому сначала системный ffmpeg, а нет его — сборка BtbN (как у Dream Displays), распаковка через tar.
     */
    private static String linux(Path dir) throws IOException {
        if (works("ffmpeg")) {
            LOG.info("Беру системный ffmpeg");
            return "ffmpeg";
        }
        Path bin = dir.resolve("ffmpeg-btbn");
        if (Files.isRegularFile(bin) && works(bin.toString())) return bin.toAbsolutePath().toString();
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String asset = arch.contains("aarch64") || arch.contains("arm64")
                ? "ffmpeg-master-latest-linuxarm64-gpl.tar.xz" : "ffmpeg-master-latest-linux64-gpl.tar.xz";
        Files.createDirectories(dir);
        LOG.info("Системного ffmpeg нет, качаю {} (~150 МБ)", asset);
        Path tmp = download(BTBN + asset, dir);
        Path out = Files.createTempDirectory(dir, "unpack");
        try {
            Process tar = new ProcessBuilder("tar", "-xJf", tmp.toString(), "-C", out.toString(), "--wildcards", "*/bin/ffmpeg")
                    .redirectErrorStream(true).start();
            String log = new String(tar.getInputStream().readAllBytes()).trim();
            if (tar.waitFor() != 0) throw new IOException("tar: " + log);
            Path found;
            try (var walk = Files.walk(out)) {
                found = walk.filter(f -> f.getFileName().toString().equals("ffmpeg") && Files.isRegularFile(f)).findFirst()
                        .orElseThrow(() -> new IOException("в архиве нет bin/ffmpeg"));
            }
            Files.move(found, bin, StandardCopyOption.REPLACE_EXISTING);
            bin.toFile().setExecutable(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано", e);
        } finally {
            Files.deleteIfExists(tmp);
            try (var walk = Files.walk(out)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
        LOG.info("ffmpeg готов: {}", bin);
        return bin.toAbsolutePath().toString();
    }

    private static final Pattern DURATION = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+)\\.(\\d+)");

    /** Длина файла по ссылке, мс (ffmpeg пишет «Duration: 00:03:21.05»); 0 — неизвестна (поток), -1 — не открывается. */
    static long probeDurationMs(String url) {
        try {
            List<String> args = new ArrayList<>(List.of(path(), "-hide_banner", "-nostdin"));
            if (url.startsWith("http")) args.addAll(List.of("-headers", "User-Agent: " + Video.USER_AGENT + "\r\n", "-rw_timeout", "15000000"));
            args.addAll(List.of("-i", url));
            Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> {
                try (InputStream in = p.getInputStream()) {
                    return new String(in.readAllBytes());
                } catch (IOException e) {
                    return "";
                }
            });
            if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly();
            String text = out.get(5, TimeUnit.SECONDS);
            if (!text.contains("Stream #")) return -1;   // не видео: страница сайта, 403, нет такого файла
            Matcher m = DURATION.matcher(text);
            if (!m.find()) return 0;
            String frac = (m.group(4) + "00").substring(0, 3);
            return ((Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000
                    + Long.parseLong(frac);
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean works(String ffmpeg) {
        try {
            Process p = new ProcessBuilder(ffmpeg, "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Path download(String url, Path dir) throws IOException {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        Path tmp = Files.createTempFile(dir, "download", ".part");
        try {
            HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "VNextScreens").GET().build(), HttpResponse.BodyHandlers.ofFile(tmp));
            if (r.statusCode() != 200) throw new IOException("ffmpeg: HTTP " + r.statusCode() + " " + url);
            return tmp;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        } catch (InterruptedException e) {
            Files.deleteIfExists(tmp);
            Thread.currentThread().interrupt();
            throw new IOException("прервано", e);
        }
    }

    /** Сборка для Windows или macOS. */
    private static String asset() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.contains("aarch64") || arch.contains("arm64");
        boolean x64 = arch.contains("amd64") || arch.contains("x86_64");
        if (os.contains("win")) return x64 ? "ffmpeg-win32-x64.gz" : null;
        return arm64 ? "ffmpeg-darwin-arm64.gz" : "ffmpeg-darwin-x64.gz";
    }
}
