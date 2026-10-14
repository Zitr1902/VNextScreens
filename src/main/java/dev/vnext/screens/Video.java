package dev.vnext.screens;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Одно проигрывание: ссылка разбирается, затем два процесса ffmpeg — картинка (RGBA в stdout)
 * и звук (PCM в stdout), как у Dream Displays. Звук играет через OpenAL самого Minecraft (то же
 * устройство, что у игры). Главные часы — звук, сглаженные: ровно идут по системному времени и
 * подтягиваются к позиции звука. Без звука часы идут по системному времени.
 */
final class Video {

    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    static final int W = 1280, H = 720, FPS = 60;
    /** Кадров в запасе (~0.2 с при 60 к/с). */
    private static final int BUFFERED = 12;
    private static final int RATE = 48000, BYTES_PER_MS = RATE * 4 / 1000;
    private static final Logger LOG = LoggerFactory.getLogger("VNextScreens");

    /** Кадр: время в видео (мс) и пиксели RGBA. */
    record Frame(long ptsMs, ByteBuffer pixels) {}

    private final String url;
    private final String lang;
    private final long startMs;                 // с какой секунды видео запускаем ffmpeg
    private final BlockingQueue<ByteBuffer> free = new ArrayBlockingQueue<>(BUFFERED);
    private final BlockingQueue<Frame> ready = new ArrayBlockingQueue<>(BUFFERED);
    private final List<Process> procs = new ArrayList<>();
    private volatile boolean stopped;
    private volatile boolean videoEnded;

    // часы
    private volatile int alSource;              // источник OpenAL, 0 — звука ещё нет
    private volatile long audioBaseMs = -1;     // время видео в момент, когда звук начал играть
    private volatile long playedFrames;         // сэмплов в уже доигранных буферах OpenAL
    private volatile long wallBaseNanos = -1;   // запасные часы, если звука нет
    private volatile long wallBaseMs;
    private long anchorMs = -1, anchorNanos;    // сглаживание
    private long lateSince = -1;                // с какого момента картинка отстаёт от звука
    private volatile String ffmpegPath;
    private volatile long durationMs;           // 0 — неизвестна
    private int audioRestarts;                  // сколько раз звук перезапускался (не больше AUDIO_RESTARTS)
    private static final int AUDIO_RESTARTS = 10;

    // громкость для левого/правого канала, считается в тике клиента
    volatile float gainL, gainR;
    /** Без звука (готовится на замену играющему); снимается сразу, через громкость источника. */
    volatile boolean muted = true;

    Video(String url, long startMs, String lang) {
        this.url = url;
        this.lang = lang;
        this.startMs = startMs;
        for (int i = 0; i < BUFFERED; i++) free.add(ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder()));
        Thread t = new Thread(this::run, "VNextScreens-start");
        t.setDaemon(true);
        t.start();
    }

    /** Позиция звука по OpenAL (шагами микширования), мс; -1 — звука нет. */
    private long rawAudioMs() {
        int src = alSource;
        if (src == 0 || audioBaseMs < 0) return -1;
        long offset = AL10.alGetSourcei(src, AL11.AL_SAMPLE_OFFSET) & 0xffffffffL;
        return audioBaseMs + (playedFrames + offset) * 1000 / RATE;
    }

    /**
     * Текущее время видео, мс; -1 — ещё не началось. Позиция звука обновляется рывками, поэтому
     * часы идут ровно от последней привязки и перепривязываются, только если звук ушёл вперёд
     * или заметно отстал (заминка звука — картинка ждёт его).
     */
    synchronized long clockMs() {
        long now = System.nanoTime();
        long raw = rawAudioMs();
        if (raw < 0) {
            if (wallBaseNanos >= 0) return wallBaseMs + (now - wallBaseNanos) / 1_000_000;
            return -1;
        }
        long pred = anchorMs < 0 ? raw : anchorMs + (now - anchorNanos) / 1_000_000;
        if (anchorMs < 0 || raw > pred + 5 || raw < pred - 150) {
            anchorMs = raw;
            anchorNanos = now;
            return raw;
        }
        return pred;
    }

    /** Можно показывать: есть кадр и часы идут (звук начал играть или его нет). */
    boolean ready() {
        return !ready.isEmpty() && (alSource != 0 || wallBaseNanos >= 0);
    }

    boolean ended() {
        return videoEnded && ready.isEmpty();
    }

    /**
     * Самый свежий кадр, до которого дошли часы (остальные выбрасываются), или null.
     * Вызывающий обязан вернуть буфер через {@link #recycle}.
     */
    Frame takeFrame() {
        long now = clockMs();
        if (now < 0) return null;
        Frame best = null;
        Frame f;
        while ((f = ready.peek()) != null && f.ptsMs <= now) {
            ready.poll();
            if (best != null) recycle(best);
            best = f;
        }
        if (best == null) return null;
        // картинка догоняет звук (на старте она приходит позже): опоздавшие кадры не показываем,
        // иначе видео заметно «проматывается». Не догнала за 2 с — показываем как есть.
        long t = System.nanoTime();
        if (now - best.ptsMs > 120) {
            if (lateSince < 0) lateSince = t;
            if (t - lateSince < 2_000_000_000L) {
                recycle(best);
                return null;
            }
        } else {
            lateSince = -1;
        }
        return best;
    }

    void recycle(Frame f) {
        f.pixels.clear();
        free.offer(f.pixels);
    }

    void stop() {
        stopped = true;
        synchronized (procs) {
            for (Process p : procs) p.destroyForcibly();
        }
    }

    // ---------- запуск ----------

    private static final int ATTEMPTS = 3;

    private void run() {
        long t0 = System.nanoTime();
        try {
            String ffmpeg = Ffmpeg.path();
            for (int attempt = 1; attempt <= ATTEMPTS && !stopped; attempt++) {
                // ссылки каждый раз свежие: googlevideo время от времени отвечает 403 на только что выданную
                MediaResolver.Resolved r = MediaResolver.resolve(url, lang, attempt > 1);
                if (stopped) return;
                ffmpegPath = ffmpeg;
                durationMs = r.durationMs();
                // пока разбирали ссылку и качали ffmpeg, видео у остальных ушло вперёд
                long seek = startMs + (System.nanoTime() - t0) / 1_000_000;
                Process video = start(videoArgs(ffmpeg, ChunkProxy.wrap(r.video()), seek), "картинка");
                Process audio = start(audioArgs(ffmpeg, ChunkProxy.wrap(r.audio()), seek), "звук");
                long launched = System.nanoTime();
                InputStream vin = new BufferedInputStream(video.getInputStream(), 1 << 16);
                InputStream ain = new BufferedInputStream(audio.getInputStream(), 1 << 16);
                var vOk = CompletableFuture.supplyAsync(() -> hasData(vin));
                var aOk = CompletableFuture.supplyAsync(() -> hasData(ain));
                boolean v = vOk.get(30, TimeUnit.SECONDS), a = aOk.get(30, TimeUnit.SECONDS);
                if (v && (a || attempt == ATTEMPTS)) {
                    daemon(() -> readVideo(vin, seek), "VNextScreens-video");
                    if (a) daemon(() -> playAudio(ain, seek, launched), "VNextScreens-audio");
                    else LOG.warn("Звук так и не открылся — видео без звука");
                    return;
                }
                video.destroyForcibly();
                audio.destroyForcibly();
                if (attempt < ATTEMPTS) LOG.warn("Видео не открылось (попытка {} из {}), пробую со свежей ссылкой", attempt, ATTEMPTS);
            }
            if (!stopped) LOG.warn("Не удалось открыть видео {} за {} попытки", url, ATTEMPTS);
        } catch (Throwable e) {
            if (!stopped) LOG.warn("Не удалось запустить видео {}: {}", url, e.toString());
        }
        videoEnded = true;
    }

    /** Дождаться первых байт из ffmpeg (не забирая их); false — процесс закрыл вывод, ничего не дав. */
    private static boolean hasData(InputStream in) {
        try {
            in.mark(1);
            int b = in.read();
            in.reset();
            return b >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    private Process start(List<String> args, String what) throws IOException {
        Process p = new ProcessBuilder(args).start();
        synchronized (procs) {
            procs.add(p);
            if (stopped) p.destroyForcibly();
        }
        daemon(() -> logErrors(p, what), "VNextScreens-ffmpeg-log");
        return p;
    }

    private static List<String> input(String ffmpeg, String src, long seekMs) {
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostats"));
        if (src.startsWith("http://127.0.0.1:")) {
            a.addAll(List.of("-rw_timeout", "30000000"));      // свой прокси (googlevideo), см. ChunkProxy
        } else if (src.startsWith("http")) {
            a.addAll(List.of("-headers", "User-Agent: " + USER_AGENT + "\r\n", "-rw_timeout", "15000000"));
            // долгие медленные соединения рвутся — ffmpeg переподключается и продолжает
            // с того же байта (опции как у Dream Displays)
            a.addAll(List.of("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "10",
                    "-reconnect_on_network_error", "1", "-reconnect_on_http_error", "5xx"));
            // HLS (Rutube): вместе с -reconnect это открывает плейлист ~23 с вместо 0.7 — только для файлов
            if (!src.contains(".m3u8")) a.addAll(List.of("-multiple_requests", "1"));
        }
        a.addAll(List.of("-probesize", "1M", "-analyzeduration", "1000000"));
        if (seekMs > 0) a.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", seekMs / 1000.0)));
        a.addAll(List.of("-i", src));
        return a;
    }

    private static List<String> videoArgs(String ffmpeg, String src, long seekMs) {
        List<String> a = input(ffmpeg, src, seekMs);
        a.addAll(List.of("-an",
                "-vf", "scale=w=" + W + ":h=" + H + ":force_original_aspect_ratio=decrease:flags=bilinear,"
                        + "pad=" + W + ":" + H + ":(ow-iw)/2:(oh-ih)/2:color=black,format=rgba",
                "-r", String.valueOf(FPS), "-f", "rawvideo", "-pix_fmt", "rgba", "-"));
        return a;
    }

    private static List<String> audioArgs(String ffmpeg, String src, long seekMs) {
        List<String> a = input(ffmpeg, src, seekMs);
        a.addAll(List.of("-vn", "-f", "s16le", "-ar", String.valueOf(RATE), "-ac", "2", "-"));
        return a;
    }

    // ---------- картинка ----------

    private void readVideo(InputStream src, long seekMs) {
        try (ReadableByteChannel in = Channels.newChannel(src)) {
            for (long i = 0; !stopped; i++) {
                ByteBuffer buf = free.poll(1, TimeUnit.SECONDS);
                if (buf == null) { i--; continue; }
                buf.clear();
                while (buf.hasRemaining()) {
                    if (in.read(buf) < 0) { free.offer(buf); return; }
                }
                buf.flip();
                ready.put(new Frame(seekMs + i * 1000 / FPS, buf));
                // если звука так и не появилось — часы по первому кадру
                if (i == 0 && audioBaseMs < 0 && wallBaseNanos < 0) startWallClockLater(seekMs);
            }
        } catch (IOException | InterruptedException e) {
            if (!stopped) LOG.warn("Картинка оборвалась: {}", e.toString());
        } finally {
            videoEnded = true;
        }
    }

    /** Звуку даётся 3 с на старт; не начался — видео идёт по системным часам. */
    private void startWallClockLater(long seekMs) {
        daemon(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            if (audioBaseMs < 0 && !stopped) {
                wallBaseMs = seekMs;
                wallBaseNanos = System.nanoTime();
                LOG.info("Звука нет, видео идёт по системным часам");
            }
        }, "VNextScreens-wallclock");
    }

    // ---------- звук ----------

    private static final int CHUNK = 2048 * 4;   // байт на буфер OpenAL (~43 мс)
    private static final int BUFFERS = 8;

    private void playAudio(InputStream pcm, long seekMs, long launchedNanos) {
        int src = 0;
        int[] bufs = new int[BUFFERS];
        ByteBuffer direct = MemoryUtil.memAlloc(CHUNK);
        byte[] chunk = new byte[CHUNK];
        try (InputStream in = pcm) {
            int n = in.readNBytes(chunk, 0, CHUNK);
            if (n <= 0 || stopped) return;
            // ffmpeg запускался с seekMs, но до первого звука прошло время — догоняем, выбрасывая его
            long lateMs = (System.nanoTime() - launchedNanos) / 1_000_000;
            long skip = Math.max(0, lateMs * BYTES_PER_MS - n);
            skip -= skip % 4;
            while (skip > 0) {
                long s = in.skip(skip);
                if (s <= 0) break;
                skip -= s;
            }
            n = in.readNBytes(chunk, 0, CHUNK);
            if (n <= 0 || stopped) return;

            src = AL10.alGenSources();
            AL10.alGenBuffers(bufs);
            // стерео не позиционируется: громкость и панораму считаем сами
            AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(src, AL10.AL_POSITION, 0, 0, 0);
            AL10.alSourcef(src, AL10.AL_ROLLOFF_FACTOR, 0);
            boolean mute = muted;
            AL10.alSourcef(src, AL10.AL_GAIN, mute ? 0 : 1);
            int queued = 0;
            for (int b : bufs) {
                if (n <= 0) break;
                queue(src, b, direct, chunk, n);
                queued++;
                n = in.readNBytes(chunk, 0, CHUNK);
            }
            audioBaseMs = seekMs + lateMs;
            playedFrames = 0;
            AL10.alSourcePlay(src);
            alSource = src;
            boolean eof = n <= 0;
            while (!stopped && queued > 0) {
                if (mute != muted) {
                    mute = muted;
                    AL10.alSourcef(src, AL10.AL_GAIN, mute ? 0 : 1);
                }
                int done = AL10.alGetSourcei(src, AL10.AL_BUFFERS_PROCESSED);
                // источник пропал: Minecraft перезапустил звуковой движок (сменилось/переподключилось устройство)
                if (!AL10.alIsSource(src)) throw new IOException("OpenAL: источник потерян (перезапуск звукового движка)");
                if (done == 0) {
                    if (AL10.alGetSourcei(src, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) AL10.alSourcePlay(src); // заминка
                    Thread.sleep(5);
                    continue;
                }
                for (int i = 0; i < done; i++) {
                    int b = AL10.alSourceUnqueueBuffers(src);
                    queued--;
                    playedFrames += AL10.alGetBufferi(b, AL10.AL_SIZE) / 4;
                    if (eof) continue;
                    if (n <= 0) n = in.readNBytes(chunk, 0, CHUNK);
                    if (n <= 0) { eof = true; continue; }
                    queue(src, b, direct, chunk, n - n % 4);
                    queued++;
                    n = 0;
                }
            }
        } catch (Exception e) {
            if (!stopped) LOG.warn("Звук оборвался: {}", e.toString());
        } finally {
            boolean started = src != 0;
            if (src != 0) {
                alSource = 0;
                AL10.alSourceStop(src);
                AL10.alSourcei(src, AL10.AL_BUFFER, 0);
                AL10.alDeleteSources(src);
                AL10.alDeleteBuffers(bufs);
                // звук кончился раньше картинки — дальше по системным часам
                if (!stopped) {
                    wallBaseMs = audioBaseMs + playedFrames * 1000 / RATE;
                    wallBaseNanos = System.nanoTime();
                }
            }
            MemoryUtil.memFree(direct);
            // звук кончился раньше видео (обрыв соединения, ошибка OpenAL) — перезапускаем только звук
            if (started && !stopped && !videoEnded) {
                long pos = clockMs();
                if (durationMs <= 0 || pos < durationMs - 3000) {
                    LOG.warn("Звук закончился раньше видео на {} мс — перезапускаю звук", pos);
                    daemon(this::restartAudio, "VNextScreens-audio-restart");
                }
            }
        }
    }

    /** Новый ffmpeg для звука со свежей ссылкой — с текущего места видео; картинка не прерывается. */
    private void restartAudio() {
        synchronized (this) {
            if (++audioRestarts > AUDIO_RESTARTS) {
                LOG.warn("Звук перезапускался {} раз — дальше без звука", AUDIO_RESTARTS);
                return;
            }
        }
        for (int attempt = 1; attempt <= ATTEMPTS && !stopped && !videoEnded; attempt++) {
            try {
                Thread.sleep(500L * attempt);
                MediaResolver.Resolved r = MediaResolver.resolve(url, lang, true);
                long seek = Math.max(0, clockMs());
                Process audio = start(audioArgs(ffmpegPath, ChunkProxy.wrap(r.audio()), seek), "звук");
                long launched = System.nanoTime();
                InputStream ain = new BufferedInputStream(audio.getInputStream(), 1 << 16);
                if (CompletableFuture.supplyAsync(() -> hasData(ain)).get(30, TimeUnit.SECONDS)) {
                    LOG.info("Звук снова идёт с {} мс", seek);
                    playAudio(ain, seek, launched);
                    return;
                }
                audio.destroyForcibly();
            } catch (Exception e) {
                if (!stopped) LOG.warn("Перезапуск звука не удался (попытка {}): {}", attempt, e.toString());
            }
        }
    }

    /** Громкость по каналам и буфер в очередь источника. */
    private void queue(int src, int buf, ByteBuffer direct, byte[] b, int len) {
        float gl = gainL, gr = gainR;
        direct.clear();
        for (int i = 0; i + 3 < len; i += 4) {
            int left = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            int right = (short) ((b[i + 2] & 0xff) | (b[i + 3] << 8));
            direct.putShort((short) Math.max(-32768, Math.min(32767, Math.round(left * gl))));
            direct.putShort((short) Math.max(-32768, Math.min(32767, Math.round(right * gr))));
        }
        direct.flip();
        AL10.alBufferData(buf, AL10.AL_FORMAT_STEREO16, direct, RATE);
        AL10.alSourceQueueBuffers(src, buf);
    }

    // ---------- служебное ----------

    private void logErrors(Process p, String what) {
        try (InputStream err = p.getErrorStream()) {
            String text = new String(err.readAllBytes()).trim();
            int code = p.waitFor();
            if (stopped) return;
            if (!text.isEmpty()) LOG.warn("ffmpeg ({}): {}", what, text);
            if (code != 0) LOG.warn("ffmpeg ({}) завершился с кодом {}", what, code);
        } catch (IOException | InterruptedException ignored) {
        }
    }

    private static void daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }
}
