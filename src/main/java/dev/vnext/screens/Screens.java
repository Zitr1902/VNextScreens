package dev.vnext.screens;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Экраны, о которых сообщил сервер. Всё, кроме потоков {@link Video}, — в главном потоке клиента. */
final class Screens {

    private static final Logger LOG = LoggerFactory.getLogger("VNextScreens");
    private static final byte PLAY = 1, STOP = 2, CLEAR = 3, PRELOAD = 4, UNLOAD = 5, ACCESS = 6, STATE = 7, REPORT = 10, ERROR = 11, WATCH = 12, ACTION = 13;
    private static final int FULL_BRIGHT = 0xF000F0;

    /** Экран: прямоугольник блоков, сторона, ссылка и когда видео было на нулевой секунде (часы клиента). */
    private static final class Screen {
        final String id;
        final int x1, y1, z1, x2, y2, z2;
        final String facing;
        final float soundDistance;
        String url;
        long zeroAtMillis;
        /** Две текстуры по очереди: новый кадр пишется в ту, что сейчас не рисуется. */
        final Identifier[] texIds = new Identifier[2];
        final DynamicTexture[] textures = new DynamicTexture[2];
        int shown = -1;          // какая текстура с последним кадром
        Video video;
        /** Перемотка или следующее видео: готовится без звука, пока играет старое, потом подменяет его. */
        Video next;

        Screen(String id, int x1, int y1, int z1, int x2, int y2, int z2, String facing, float soundDistance, String url, long positionMs) {
            this.id = id; this.x1 = x1; this.y1 = y1; this.z1 = z1; this.x2 = x2; this.y2 = y2; this.z2 = z2;
            this.facing = facing; this.soundDistance = soundDistance; this.url = url;
            this.zeroAtMillis = System.currentTimeMillis() - positionMs;
            String key = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
            for (int i = 0; i < 2; i++) texIds[i] = Identifier.fromNamespaceAndPath("vnextscreens", "screen/" + key + "_" + i);
        }

        Vec3 center() {
            return new Vec3((x1 + x2 + 1) / 2.0, (y1 + y2 + 1) / 2.0, (z1 + z2 + 1) / 2.0);
        }

        /** Видео включается ближе этого расстояния и выключается дальше него + 16. */
        double activeDistance() {
            return Math.max(96, soundDistance + 32);
        }
    }

    private static final Map<String, Screen> screens = new LinkedHashMap<>();

    /** Экран мира (присылает сервер всем с модом): без видео на нём рисуется чёрная плоскость. */
    record Geom(String id, int x1, int y1, int z1, int x2, int y2, int z2, String facing) {}
    private static final Map<String, Geom> known = new LinkedHashMap<>();
    /** Можно ли открывать меню кликом по экрану (группы из menu-groups на сервере). */
    private static boolean canControl;

    /** Что играет и очередь — для открытого меню (сервер шлёт, пока оно открыто). */
    record QueueItem(String title, String by, String url) {}
    static final class State {
        boolean playing;
        String title = "", by = "", url = "";
        long positionMs, durationMs, receivedAt;
        java.util.List<QueueItem> queue = java.util.List.of();

        long positionNow() {
            long p = positionMs + (System.currentTimeMillis() - receivedAt);
            return durationMs > 0 ? Math.min(p, durationMs) : p;
        }
    }
    private static final Map<String, State> states = new java.util.HashMap<>();

    static State state(String id) {
        return states.get(id);
    }
    /** Экраны, видео которых грузится заранее, пока игрок стоит в портале (включены, даже если далеко). */
    private static final java.util.Set<String> preload = new java.util.HashSet<>();

    private Screens() {}

    // ---------- сообщения от сервера ----------

    static void onPayload(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            switch (in.readByte()) {
                case PLAY -> {
                    String id = in.readUTF();
                    int x1 = in.readInt(), y1 = in.readInt(), z1 = in.readInt(), x2 = in.readInt(), y2 = in.readInt(), z2 = in.readInt();
                    String facing = in.readUTF();
                    float dist = in.readFloat();
                    String url = in.readUTF();
                    long pos = in.readLong();
                    // сервер ещё не знает длину — сообщить её, даже если экран далеко (старый плагин поле не шлёт)
                    boolean needInfo = in.available() > 0 && in.readBoolean();
                    if (needInfo) requestInfo(id, url);
                    Screen old = screens.get(id);
                    if (old != null && old.x1 == x1 && old.y1 == y1 && old.z1 == z1 && old.x2 == x2 && old.y2 == y2 && old.z2 == z2
                            && old.facing.equals(facing)) {
                        // тот же экран (перемотка или следующее видео): последний кадр остаётся, пока не придёт новый
                        old.url = url;
                        old.zeroAtMillis = System.currentTimeMillis() - pos;
                        if (old.next != null) { old.next.stop(); old.next = null; }   // нажали ещё раз — готовим заново
                        if (old.video != null && !old.video.ended()) {
                            old.next = startVideo(old);
                        } else if (old.video != null) {
                            old.video.stop();
                            old.video = null;
                        }
                    } else {
                        remove(id);
                        screens.put(id, new Screen(id, x1, y1, z1, x2, y2, z2, facing, dist, url, pos));
                    }
                    LOG.info("Экран {}: {} с {} мс", id, url, pos);
                }
                case STOP -> remove(in.readUTF());
                case CLEAR -> clear();
                case ACCESS -> {
                    canControl = in.readBoolean();
                    known.clear();
                    for (int i = in.readInt(); i > 0; i--) {
                        Geom g = new Geom(in.readUTF(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readUTF());
                        known.put(g.id(), g);
                    }
                    if (!canControl && Minecraft.getInstance().gui.screen() instanceof ControlScreen) Minecraft.getInstance().setScreenAndShow(null);
                }
                case STATE -> {
                    String id = in.readUTF();
                    State st = new State();
                    st.playing = in.readBoolean();
                    if (st.playing) {
                        st.title = in.readUTF();
                        st.by = in.readUTF();
                        st.url = in.readUTF();
                        st.positionMs = in.readLong();
                        st.durationMs = in.readLong();
                    }
                    java.util.List<QueueItem> q = new java.util.ArrayList<>();
                    for (int i = in.readInt(); i > 0; i--) q.add(new QueueItem(in.readUTF(), in.readUTF(), in.readUTF()));
                    st.queue = q;
                    st.receivedAt = System.currentTimeMillis();
                    states.put(id, st);
                }
                case PRELOAD -> preload.add(in.readUTF());
                // вышел из портала: дальше по расстоянию (далеко — видео выключится в tick)
                case UNLOAD -> preload.remove(in.readUTF());
                default -> {}
            }
        } catch (IOException e) {
            LOG.warn("Не разобрал сообщение экрана: {}", e.toString());
        }
    }

    static void clear() {
        preload.clear();
        known.clear();
        states.clear();
        for (String id : screens.keySet().toArray(String[]::new)) remove(id);
    }

    private static void remove(String id) {
        Screen s = screens.remove(id);
        if (s != null) deactivate(s);
    }

    private static void deactivate(Screen s) {
        if (s.video != null) { s.video.stop(); s.video = null; }
        if (s.next != null) { s.next.stop(); s.next = null; }
        for (int i = 0; i < 2; i++) {
            if (s.textures[i] == null) continue;
            Minecraft.getInstance().getTextureManager().release(s.texIds[i]);
            s.textures[i] = null;
        }
        s.shown = -1;
    }

    /** Разобрать ссылку в фоне (разбор кэшируется — видео потом возьмёт его же) и отправить серверу длину. */
    private static void requestInfo(String id, String url) {
        Minecraft mc = Minecraft.getInstance();
        String lang = mc.options.languageCode.split("_")[0];
        Thread t = new Thread(() -> {
            try {
                MediaResolver.Resolved r = MediaResolver.resolve(url, lang, false);
                // прямая ссылка: длину знает только сам файл; заодно видно, открывается ли он вообще
                if (r.durationMs() <= 0) {
                    long d = Ffmpeg.probeDurationMs(r.video());
                    if (d < 0) throw new MediaResolver.Failure("open", "ffmpeg не открыл " + r.video());
                    r = r.withDuration(d);
                }
                MediaResolver.Resolved info = r;
                mc.execute(() -> {
                    if (mc.getConnection() != null) report(id, url, info);
                });
            } catch (Exception e) {
                String reason = MediaResolver.reason(e);
                LOG.warn("Видео {} не откроется ({}): {}", url, reason, e.toString());
                mc.execute(() -> {
                    if (mc.getConnection() != null) error(id, url, reason);
                });
            }
        }, "VNextScreens-info");
        t.setDaemon(true);
        t.start();
    }

    /** Отключились от сервера: меню больше не открыть, пока новый сервер не разрешит. */
    static void disconnected() {
        canControl = false;
        clear();
        Previews.clear();
    }

    private interface Writer { void write(DataOutputStream out) throws IOException; }

    private static void send(Writer w) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            w.write(out);
        } catch (IOException e) {
            return;
        }
        if (Minecraft.getInstance().getConnection() != null) ClientPlayNetworking.send(new ScreenPayload(bytes.toByteArray()));
    }

    /** Меню открыто или закрыто: пока открыто, сервер шлёт что играет и очередь. */
    static void watch(String id, boolean open) {
        send(out -> { out.writeByte(WATCH); out.writeUTF(id); out.writeBoolean(open); });
    }

    /** Кнопка меню: play, skip, stop, seek, seekto, remove. Права проверяет сервер. */
    static void action(String id, String action, String arg) {
        send(out -> { out.writeByte(ACTION); out.writeUTF(id); out.writeUTF(action); out.writeUTF(arg); });
    }

    /**
     * ПКМ: смотрит ли игрок с правом на экран — тогда открывается меню и обычное действие не выполняется.
     * Без права — false, клик идёт как обычно.
     */
    static boolean onUse(Minecraft mc) {
        if (!canControl || mc.player == null || mc.gui.screen() != null || known.isEmpty()) return false;
        Vec3 o = mc.gameRenderer.mainCamera().position();
        Vec3 d = mc.player.getViewVector(1f);
        Geom best = null;
        double bestT = 128;
        for (Geom g : known.values()) {
            double t = hit(g, o, d);
            if (t > 0 && t < bestT) { best = g; bestT = t; }
        }
        if (best == null) return false;
        // что-то ближе экрана (блок, сущность) — кликнули по нему
        if (mc.hitResult != null && mc.hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS
                && mc.hitResult.getLocation().distanceTo(o) < bestT - 0.6) return false;
        mc.setScreenAndShow(new ControlScreen(best.id()));
        return true;
    }

    /** Расстояние до лицевой стороны экрана по лучу или -1 (мимо, сзади). */
    private static double hit(Geom g, Vec3 o, Vec3 d) {
        double off = 0.01;
        switch (g.facing()) {
            case "south", "north" -> {
                double z = g.facing().equals("south") ? g.z2() + 1 + off : g.z1() - off;
                if (Math.abs(d.z) < 1e-6 || (g.facing().equals("south") ? o.z < z : o.z > z)) return -1;
                double t = (z - o.z) / d.z;
                double x = o.x + d.x * t, y = o.y + d.y * t;
                return t > 0 && x >= g.x1() && x <= g.x2() + 1 && y >= g.y1() && y <= g.y2() + 1 ? t : -1;
            }
            default -> {
                double x = g.facing().equals("east") ? g.x2() + 1 + off : g.x1() - off;
                if (Math.abs(d.x) < 1e-6 || (g.facing().equals("east") ? o.x < x : o.x > x)) return -1;
                double t = (x - o.x) / d.x;
                double z = o.z + d.z * t, y = o.y + d.y * t;
                return t > 0 && z >= g.z1() && z <= g.z2() + 1 && y >= g.y1() && y <= g.y2() + 1 ? t : -1;
            }
        }
    }

    /** Серверу — видео не откроется и почему (он скажет модератору и пропустит его). */
    private static void error(String id, String url, String reason) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(ERROR);
            out.writeUTF(id);
            out.writeUTF(url);
            out.writeUTF(reason);
        } catch (IOException e) {
            return;
        }
        ClientPlayNetworking.send(new ScreenPayload(bytes.toByteArray()));
    }

    /** Серверу — длина и название видео: по длине он включает следующее из очереди. */
    private static void report(String id, String url, MediaResolver.Resolved r) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(REPORT);
            out.writeUTF(id);
            out.writeUTF(url);
            out.writeLong(r.durationMs());
            out.writeUTF(r.title());
        } catch (IOException e) {
            return;
        }
        ClientPlayNetworking.send(new ScreenPayload(bytes.toByteArray()));
    }

    // ---------- тик: включение по расстоянию и громкость ----------

    static void tick(Minecraft mc) {
        // вышли в меню — видео и звук не должны играть, даже если событие отключения не дошло
        if (mc.level == null) {
            if (!screens.isEmpty()) clear();
            return;
        }
        if (mc.player == null || screens.isEmpty()) return;
        Camera cam = mc.gameRenderer.mainCamera();
        Vec3 ear = cam.position();
        float yaw = (float) Math.toRadians(mc.player.getYRot());
        // вектор «вправо» от игрока (yaw 0 — смотрит на +z)
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        // общая громкость × свой ползунок «Видео на экранах»
        float master = (float) (mc.options.getSoundSourceVolume(SoundSource.MASTER) * Volume.get());
        for (Screen s : screens.values()) {
            Vec3 c = s.center();
            double d = ear.distanceTo(c);
            boolean wanted = preload.contains(s.id);
            if (s.video == null && (wanted || d < s.activeDistance())) {
                s.video = startVideo(s);
                s.video.muted = false;
            } else if (s.video != null && !wanted && d > s.activeDistance() + 16) {
                deactivate(s);
                continue;
            }
            if (s.video == null) continue;
            // новое готово (первый кадр и звук) или не открылось — подменяем старое
            if (s.next != null && (s.next.ready() || s.next.ended())) {
                s.video.stop();
                s.video = s.next;
                s.next = null;
                s.video.muted = false;
            }
            // громкость: полная в первой трети дальности, дальше плавно до нуля
            double full = s.soundDistance * 0.3;
            double k = d <= full ? 1 : d >= s.soundDistance ? 0
                    : 0.5 + 0.5 * Math.cos(Math.PI * (d - full) / (s.soundDistance - full));
            double gain = k * master;
            // панорама: источник справа — тише левое ухо, и наоборот (не до нуля, экран большой)
            double dx = c.x - ear.x, dz = c.z - ear.z, len = Math.hypot(dx, dz);
            double pan = len < 1e-3 ? 0 : (dx * rx + dz * rz) / len * Math.min(1, len / 8);
            float gl = (float) (gain * (1 - Math.max(0, pan) * 0.5)), gr = (float) (gain * (1 + Math.min(0, pan) * 0.5));
            s.video.gainL = gl;
            s.video.gainR = gr;
            if (s.next != null) { s.next.gainL = gl; s.next.gainR = gr; }
        }
    }

    /** Видео экрана с того места, где оно сейчас у всех; запускается без звука. */
    private static Video startVideo(Screen s) {
        long pos = Math.max(0, System.currentTimeMillis() - s.zeroAtMillis);
        // «ru_ru» → «ru»: звуковая дорожка на языке игры
        String lang = Minecraft.getInstance().options.languageCode.split("_")[0];
        return new Video(s.url, pos, lang);
    }

    // ---------- кадры в текстуру (начало кадра отрисовки) ----------

    static void uploadFrames(Minecraft mc) {
        for (Screen s : screens.values()) {
            Video v = s.video;
            if (v == null) continue;
            Video.Frame f = v.takeFrame();
            if (f == null) continue;
            try {
                int i = s.shown == 0 ? 1 : 0;
                if (s.textures[i] == null) {
                    s.textures[i] = new DynamicTexture(() -> "vnextscreens " + s.id, Video.W, Video.H, false);
                    mc.getTextureManager().register(s.texIds[i], s.textures[i]);
                }
                MemoryUtil.memCopy(MemoryUtil.memAddress(f.pixels()), s.textures[i].getPixels().getPointer(), (long) Video.W * Video.H * 4);
                s.textures[i].upload();
                s.shown = i;
            } finally {
                v.recycle(f);
            }
        }
    }

    // ---------- отрисовка ----------

    static void render(PoseStack pose, SubmitNodeCollector collector, Camera cam) {
        Vec3 c = cam.position();
        for (Screen s : screens.values()) {
            // есть кадр и видео не кончилось — видео; иначе (грузится, кончилось) — чёрный
            boolean video = s.shown >= 0 && (s.video == null || !s.video.ended());
            if (!video && known.containsKey(s.id)) continue;   // нарисуется ниже как пустой экран
            if (!video) {
                quadBlack(pose, collector, quad(s.x1, s.y1, s.z1, s.x2, s.y2, s.z2, s.facing, c));
                continue;
            }
            float[][] q = quad(s.x1, s.y1, s.z1, s.x2, s.y2, s.z2, s.facing, c);
            collector.submitCustomGeometry(pose, RenderTypes.text(s.texIds[s.shown]), (p, vc) -> {
                vertex(vc, p, q[0], 0, 0);
                vertex(vc, p, q[1], 0, 1);
                vertex(vc, p, q[2], 1, 1);
                vertex(vc, p, q[3], 1, 0);
            });
        }
        // экраны без видео — чёрные, а не голые блоки
        for (Geom g : known.values()) {
            Screen s = screens.get(g.id());
            if (s != null && s.shown >= 0 && (s.video == null || !s.video.ended())) continue;
            quadBlack(pose, collector, quad(g.x1(), g.y1(), g.z1(), g.x2(), g.y2(), g.z2(), g.facing(), c));
        }
    }

    private static final Identifier BLACK = Identifier.fromNamespaceAndPath("vnextscreens", "screen/black");
    private static DynamicTexture black;

    /** Чёрная плоскость (текстура 1×1 из одного непрозрачного чёрного пикселя). */
    private static void quadBlack(PoseStack pose, SubmitNodeCollector collector, float[][] q) {
        if (black == null) {
            black = new DynamicTexture(() -> "vnextscreens black", 1, 1, false);
            MemoryUtil.memPutInt(black.getPixels().getPointer(), 0xFF000000);   // RGBA: альфа 255
            black.upload();
            Minecraft.getInstance().getTextureManager().register(BLACK, black);
        }
        collector.submitCustomGeometry(pose, RenderTypes.text(BLACK), (p, vc) -> {
            vertex(vc, p, q[0], 0, 0);
            vertex(vc, p, q[1], 0, 1);
            vertex(vc, p, q[2], 1, 1);
            vertex(vc, p, q[3], 1, 0);
        });
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose p, float[] v, float u, float w) {
        vc.addVertex(p, v[0], v[1], v[2]).setColor(255, 255, 255, 255).setUv(u, w).setLight(FULL_BRIGHT);
    }

    /**
     * Углы плоскости перед лицевой гранью, относительно камеры: верх-лево, низ-лево, низ-право, верх-право
     * (как их видит зритель — против часовой стрелки).
     */
    private static float[][] quad(int x1, int y1, int z1, int x2, int y2, int z2, String facing, Vec3 cam) {
        final double off = 0.01;   // чуть перед блоками, чтобы не мерцало
        double top = y2 + 1, bot = y1;
        double[][] w = switch (facing) {
            // зритель смотрит на север, его лево — запад (−x)
            case "south" -> new double[][]{{x1, top, z2 + 1 + off}, {x1, bot, z2 + 1 + off}, {x2 + 1, bot, z2 + 1 + off}, {x2 + 1, top, z2 + 1 + off}};
            // смотрит на юг, лево — восток (+x)
            case "north" -> new double[][]{{x2 + 1, top, z1 - off}, {x2 + 1, bot, z1 - off}, {x1, bot, z1 - off}, {x1, top, z1 - off}};
            // смотрит на запад, лево — юг (+z)
            case "east" -> new double[][]{{x2 + 1 + off, top, z2 + 1}, {x2 + 1 + off, bot, z2 + 1}, {x2 + 1 + off, bot, z1}, {x2 + 1 + off, top, z1}};
            // смотрит на восток, лево — север (−z)
            default -> new double[][]{{x1 - off, top, z1}, {x1 - off, bot, z1}, {x1 - off, bot, z2 + 1}, {x1 - off, top, z2 + 1}};
        };
        float[][] r = new float[4][3];
        for (int i = 0; i < 4; i++) {
            r[i][0] = (float) (w[i][0] - cam.x);
            r[i][1] = (float) (w[i][1] - cam.y);
            r[i][2] = (float) (w[i][2] - cam.z);
        }
        return r;
    }
}
