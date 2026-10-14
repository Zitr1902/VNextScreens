package dev.vnext.screens;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Меню экрана (открывается ПКМ по экрану, только у кого есть право): что играет с превью и перемотка
 * ползунком, −10/+10 с, пропустить, стоп, ссылка с проверкой (откроется ли видео), очередь с превью,
 * пометкой недоступных и удалением. Каждое нажатие — пакет серверу, он же проверяет права; что играет
 * и очередь сервер присылает, пока окно открыто.
 */
final class ControlScreen extends Screen {

    private static final int W = 340, ROWS = 4, ROW_H = 24;
    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, DARK = 0xFF666666, GREEN = 0xFF3EE08A, RED = 0xFFFF5252;
    private static final long CONFIRM_MS = 3000, CHECK_DELAY_MS = 500;
    // превью: у текущего видео, у ссылки в поле, в строках очереди
    private static final int NOW_W = 96, NOW_H = 54, URL_W = 32, URL_H = 18, ROW_W = 36, ROW_PH = 20;

    private final String id;
    private int left, top, page;
    private Seek seek;
    private EditBox url;
    private Button back, forward, skip, stop, add, prev, next;
    private final Button[] remove = new Button[ROWS];
    /** Подтверждение «стоп» и «убрать из очереди»: второй клик в течение 3 с. */
    private long stopArmed;
    private String removeArmed = "";
    private long removeArmedAt;
    /** Проверка ссылки в поле: какую проверяем и когда её последний раз меняли. */
    private String checking = "";
    private long editedAt;

    ControlScreen(String id) {
        super(Component.translatable("vnextscreens.menu.title"));
        this.id = id;
    }

    // ---------- разметка ----------

    private int seekY() { return top + 82; }
    private int buttonsY() { return top + 106; }
    private int urlY() { return top + 132; }
    private int queueY() { return top + 176; }
    private int rowY(int i) { return queueY() + 12 + i * ROW_H; }
    private int panelH() { return rowY(ROWS) + 26 - top; }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = 0;
        top = Math.max(4, (height - panelH()) / 2);
        String typed = url == null ? "" : url.getValue();

        seek = addRenderableWidget(new Seek(left + 10, seekY(), W - 20, 20));

        int y = buttonsY(), x = left + 10;
        back = addRenderableWidget(Button.builder(Component.literal("−10 с"), b -> Screens.action(id, "seek", "-10000")).bounds(x, y, 60, 20).build());
        forward = addRenderableWidget(Button.builder(Component.literal("+10 с"), b -> Screens.action(id, "seek", "10000")).bounds(x + 64, y, 60, 20).build());
        skip = addRenderableWidget(Button.builder(Component.translatable("vnextscreens.menu.skip"), b -> Screens.action(id, "skip", ""))
                .bounds(x + 128, y, 100, 20).build());
        stop = addRenderableWidget(Button.builder(Component.translatable("vnextscreens.menu.stop"), b -> {
            if (System.currentTimeMillis() - stopArmed < CONFIRM_MS) {
                stopArmed = 0;
                Screens.action(id, "stop", "");
            } else {
                stopArmed = System.currentTimeMillis();
            }
        }).bounds(x + 232, y, W - 20 - 232, 20).build());

        url = new EditBox(font, x, urlY(), W - 20 - 84, 20, Component.translatable("vnextscreens.menu.url"));
        url.setMaxLength(1024);
        url.setHint(Component.translatable("vnextscreens.menu.url").withColor(DARK));
        url.setValue(typed);
        url.setResponder(v -> editedAt = System.currentTimeMillis());
        addRenderableWidget(url);
        add = addRenderableWidget(Button.builder(Component.translatable("vnextscreens.menu.add"), b -> addUrl())
                .bounds(left + W - 10 - 80, urlY(), 80, 20).build());

        for (int i = 0; i < ROWS; i++) {
            int row = i;
            remove[i] = addRenderableWidget(Button.builder(Component.literal("✕"), b -> removeRow(row))
                    .bounds(left + W - 10 - 20, rowY(i) + 2, 20, 20).build());
        }
        int py = rowY(ROWS) + 2;
        prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> page--).bounds(left + W / 2 - 46, py, 20, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal(">"), b -> page++).bounds(left + W / 2 + 26, py, 20, 20).build());
        Screens.watch(id, true);
        refresh();
    }

    // ---------- действия ----------

    private void addUrl() {
        String u = url.getValue().trim();
        if (u.isEmpty() || !add.active) return;
        Screens.action(id, "play", u);
        url.setValue("");
        checking = "";
    }

    private void removeRow(int row) {
        Screens.State st = Screens.state(id);
        int index = page * ROWS + row;
        if (st == null || index >= st.queue.size()) return;
        String key = index + ":" + st.queue.get(index).url();
        if (key.equals(removeArmed) && System.currentTimeMillis() - removeArmedAt < CONFIRM_MS) {
            removeArmed = "";
            Screens.action(id, "remove", key);
        } else {
            removeArmed = key;
            removeArmedAt = System.currentTimeMillis();
        }
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        // Enter в поле ссылки — добавить
        if (url != null && url.isFocused() && (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
            addUrl();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void tick() {
        // ссылку перестали менять — проверяем, откроется ли
        String u = url.getValue().trim();
        if (!u.equals(checking) && System.currentTimeMillis() - editedAt > CHECK_DELAY_MS) checking = u;
        refresh();
    }

    /** Проверка ссылки в поле (null — нечего проверять). */
    private Previews.Info typed() {
        return checking.startsWith("http://") || checking.startsWith("https://") ? Previews.get(checking) : null;
    }

    /** Кнопки и ползунок — по последнему состоянию от сервера. */
    private void refresh() {
        Screens.State st = Screens.state(id);
        boolean playing = st != null && st.playing;
        back.active = forward.active = skip.active = stop.active = playing;
        seek.active = playing && st.durationMs > 0;
        seek.sync(st);
        boolean armed = System.currentTimeMillis() - stopArmed < CONFIRM_MS;
        stop.setMessage(armed ? Component.translatable("vnextscreens.menu.confirm").withColor(RED)
                : Component.translatable("vnextscreens.menu.stop"));
        // недоступное видео не добавить (пока проверяется — можно)
        Previews.Info t = typed();
        String u = url.getValue().trim();
        add.active = !u.isEmpty() && (t == null || !u.equals(checking) || t.status != Previews.Status.FAIL);
        List<Screens.QueueItem> q = st == null ? List.of() : st.queue;
        int pages = Math.max(1, (q.size() + ROWS - 1) / ROWS);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < ROWS; i++) {
            int index = page * ROWS + i;
            remove[i].visible = index < q.size();
            boolean on = index < q.size() && removeArmed.equals(index + ":" + q.get(index).url())
                    && System.currentTimeMillis() - removeArmedAt < CONFIRM_MS;
            remove[i].setMessage(on ? Component.literal("?").withColor(RED) : Component.literal("✕"));
        }
        prev.visible = next.visible = pages > 1;
        prev.active = page > 0;
        next.active = page < pages - 1;
    }

    // ---------- отрисовка ----------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(left, top, left + W, top + panelH(), 0xE00C0F14);
        g.outline(left, top, W, panelH(), 0xFF2A8558);
        super.extractRenderState(g, mouseX, mouseY, delta);

        g.centeredText(font, getTitle().copy().withColor(GREEN), left + W / 2, top + 7, WHITE);
        Screens.State st = Screens.state(id);
        int tx = left + 10 + NOW_W + 8, tw = W - 20 - NOW_W - 8;
        if (st == null || !st.playing) {
            preview(g, null, left + 10, top + 20, NOW_W, NOW_H);
            g.text(font, Component.translatable(st == null ? "vnextscreens.menu.loading" : "vnextscreens.menu.nothing"), tx, top + 40, GRAY);
        } else {
            Previews.Info info = Previews.get(st.url);
            preview(g, info, left + 10, top + 20, NOW_W, NOW_H);
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(net.minecraft.network.chat.FormattedText.of(st.title), tw);
            for (int i = 0; i < Math.min(3, lines.size()); i++) g.text(font, lines.get(i), tx, top + 22 + i * 10, WHITE);
            if (!st.by.isEmpty()) g.text(font, Component.translatable("vnextscreens.menu.by", st.by), tx, top + 64, GRAY);
        }

        // проверка ссылки в поле
        Previews.Info t = typed();
        int cy = urlY() + 23;
        if (t != null) {
            preview(g, t, left + 10, cy, URL_W, URL_H);
            int sx = left + 10 + URL_W + 6, sw = W - 20 - URL_W - 6;
            Component line = switch (t.status) {
                case CHECKING -> Component.translatable("vnextscreens.menu.checking").withColor(GRAY);
                case OK -> Component.literal("✓ ").withColor(GREEN).append(Component.literal(
                        font.plainSubstrByWidth(t.title.isEmpty() ? checking : t.title, sw - 60)).withColor(WHITE))
                        .append(Component.literal(t.durationMs > 0 ? "  " + time(t.durationMs) : "").withColor(GRAY));
                case FAIL -> Component.literal("✗ ").withColor(RED).append(reason(t.reason).withColor(RED));
            };
            g.text(font, line, sx, cy + 5, WHITE);
        }

        List<Screens.QueueItem> q = st == null ? List.of() : st.queue;
        g.text(font, Component.translatable("vnextscreens.menu.queue", q.size()).withColor(GREEN), left + 10, queueY(), WHITE);
        if (q.isEmpty()) g.text(font, Component.translatable("vnextscreens.menu.empty"), left + 10, rowY(0) + 6, DARK);
        for (int i = 0; i < ROWS; i++) {
            int index = page * ROWS + i;
            if (index >= q.size()) break;
            Screens.QueueItem it = q.get(index);
            Previews.Info info = Previews.get(it.url());
            int y = rowY(i) + 2;
            preview(g, info, left + 10, y, ROW_W, ROW_PH);
            String num = (index + 1) + ". ";
            int x = left + 10 + ROW_W + 6, w = W - 20 - ROW_W - 6 - 26;
            g.text(font, num, x, y + 1, GRAY);
            g.text(font, font.plainSubstrByWidth(it.title(), w - font.width(num)), x + font.width(num), y + 1, WHITE);
            // недоступное — красным с причиной, иначе кто поставил и длина
            boolean fail = info.status == Previews.Status.FAIL;
            String sub = fail ? "✗ " + reason(info.reason).getString() : it.by() + (info.durationMs > 0 ? " · " + time(info.durationMs) : "");
            g.text(font, font.plainSubstrByWidth(sub, w), x, y + 11, fail ? RED : DARK);
        }
        if (prev.visible) {
            int pages = Math.max(1, (q.size() + ROWS - 1) / ROWS);
            g.centeredText(font, (page + 1) + " / " + pages, left + W / 2, rowY(ROWS) + 8, GRAY);
        }
    }

    /** Превью или тёмная заглушка с «…» (грузится) / «✗» (недоступно). */
    private void preview(GuiGraphicsExtractor g, Previews.Info info, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF000000);
        if (info != null && info.texture != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, info.texture, x, y, 0, 0, w, h, Previews.W, Previews.H, Previews.W, Previews.H);
        } else if (info != null) {
            String mark = info.status == Previews.Status.FAIL ? "✗" : "…";
            g.centeredText(font, mark, x + w / 2, y + (h - 8) / 2, info.status == Previews.Status.FAIL ? RED : DARK);
        }
        g.outline(x, y, w, h, 0xFF2A2F36);
    }

    private static net.minecraft.network.chat.MutableComponent reason(String code) {
        return Component.translatableWithFallback("vnextscreens.reason." + code, code);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        Screens.watch(id, false);
    }

    private static String time(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }

    /** Ползунок времени: тянешь — перемотка, когда отпустишь. */
    private final class Seek extends AbstractSliderButton {
        private boolean dragging;
        private long duration;

        Seek(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty(), 0);
        }

        void sync(Screens.State st) {
            duration = st != null && st.playing ? st.durationMs : 0;
            if (dragging) {
                updateMessage();
                return;
            }
            value = duration > 0 ? Math.min(1, (double) st.positionNow() / duration) : 0;
            if (st == null || !st.playing) setMessage(Component.literal("—"));
            else setMessage(Component.literal(time(st.positionNow()) + (duration > 0 ? " / " + time(duration) : "")));
        }

        @Override
        protected void updateMessage() {
            if (duration > 0) setMessage(Component.literal(time((long) (value * duration)) + " / " + time(duration)));
        }

        @Override
        protected void applyValue() {
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            dragging = true;
            super.onClick(event, doubleClick);
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            if (dragging && duration > 0) Screens.action(id, "seekto", Long.toString((long) (value * duration)));
            dragging = false;
        }
    }
}
