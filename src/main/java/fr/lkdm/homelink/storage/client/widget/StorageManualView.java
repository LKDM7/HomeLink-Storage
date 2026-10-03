package fr.lkdm.homelink.storage.client.widget;

import fr.lkdm.homecore.api.client.ui.HomeLinkTheme;
import fr.lkdm.homecore.api.client.ui.HomeLinkUi;
import fr.lkdm.homecore.api.client.ui.HomeLinkButton;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** Local translated reference pages. Opening or scrolling the manual sends no requests. */
public final class StorageManualView {
    private static final String[] CHAPTERS = {"start", "connect", "search", "monitor", "trouble", "pipes"};
    private record Line(FormattedCharSequence text, boolean heading) { }
    private final Font font;
    private List<Line> lines = List.of();
    private int chapter, offset, x, y, width, height;
    private Button up, down;
    private Runnable rebuild;

    public StorageManualView(Font font) { this.font = font; }
    public int chapter() { return chapter; }
    public int offset() { return offset; }
    private Component text(String key) { return Component.translatable("manual.homelink_storage." + key); }

    public void init(int x, int y, int width, int height, Consumer<AbstractWidget> add, Runnable rebuild) {
        this.x = x; this.y = y; this.width = width; this.height = height; this.rebuild = rebuild;
        List<Line> wrapped = new ArrayList<>();
        for (String paragraph : text(CHAPTERS[chapter] + ".body").getString().split("\n\n")) {
            if (!wrapped.isEmpty()) wrapped.add(new Line(FormattedCharSequence.EMPTY, false));
            for (String row : paragraph.split("\n")) {
                boolean heading = row.startsWith("# ");
                var content = Component.empty();
                String[] spans = (heading ? row.substring(2) : row).split("\\*\\*", -1);
                for (int i = 0; i < spans.length; i++) {
                    boolean emphasis = heading || i % 2 == 1;
                    content.append(Component.literal(spans[i]).withStyle(style -> style
                            .withColor(emphasis ? HomeLinkTheme.ACCENT : HomeLinkTheme.TEXT).withBold(heading)));
                }
                for (var line : font.split(content, width - 28)) wrapped.add(new Line(line, heading));
            }
        }
        lines = List.copyOf(wrapped);
        offset = Math.min(offset, maxOffset());
        Button previous = HomeLinkButton.builder(Component.literal("<"), ignored -> changeChapter(-1)).bounds(x, y, 24, HomeLinkTheme.CONTROL_HEIGHT).build();
        previous.active = chapter > 0;
        previous.setTooltip(Tooltip.create(text("previous")));
        add.accept(previous);
        Button next = HomeLinkButton.builder(Component.literal(">"), ignored -> changeChapter(1)).bounds(x + width - 24, y, 24, HomeLinkTheme.CONTROL_HEIGHT).build();
        next.active = chapter < CHAPTERS.length - 1;
        next.setTooltip(Tooltip.create(text("next")));
        add.accept(next);
        up = HomeLinkButton.builder(text("up"), ignored -> scroll(-visibleLines())).bounds(x, y + height - 18, (width - 4) / 2, HomeLinkTheme.CONTROL_HEIGHT).build();
        down = HomeLinkButton.builder(text("down"), ignored -> scroll(visibleLines())).bounds(x + (width - 4) / 2 + 4, y + height - 18, (width - 4) / 2, HomeLinkTheme.CONTROL_HEIGHT).build();
        add.accept(up); add.accept(down);
        updateButtons();
    }

    private int visibleLines() { return Math.max(1, (height - 52) / 14); }
    private int maxOffset() { return Math.max(0, lines.size() - visibleLines()); }
    private void updateButtons() { up.active = offset > 0; down.active = offset < maxOffset(); }
    private void scroll(int amount) { offset = Math.max(0, Math.min(maxOffset(), offset + amount)); updateButtons(); }
    private void changeChapter(int delta) {
        int next = Math.max(0, Math.min(CHAPTERS.length - 1, chapter + delta));
        if (next != chapter) { chapter = next; offset = 0; rebuild.run(); }
    }

    public void render(GuiGraphics graphics) {
        String title = (chapter + 1) + "/" + CHAPTERS.length + "  " + text(CHAPTERS[chapter] + ".title").getString();
        graphics.drawCenteredString(font, font.plainSubstrByWidth(title, width - 60), x + width / 2, y + 5, HomeLinkTheme.ACCENT);
        HomeLinkUi.panel(graphics, x, y + 23, width, height - 45);
        graphics.enableScissor(x + 4, y + 26, x + width - 4, y + height - 23);
        for (int i = offset; i < Math.min(lines.size(), offset + visibleLines()); i++) {
            Line line = lines.get(i);
            int rowY = y + 28 + (i - offset) * 14;
            if (line.heading()) {
                graphics.fill(x + 6, rowY - 2, x + width - 9, rowY + 11, HomeLinkTheme.HEADER);
                graphics.fill(x + 6, rowY - 2, x + 8, rowY + 11, HomeLinkTheme.ACCENT);
            }
            graphics.drawString(font, line.text(), x + 12, rowY, HomeLinkTheme.TEXT, false);
        }
        graphics.disableScissor();
        if (maxOffset() > 0) {
            int track = Math.max(1, height - 55);
            int thumb = Math.max(6, track * visibleLines() / lines.size());
            int top = y + 28 + (track - thumb) * offset / maxOffset();
            graphics.fill(x + width - 5, top, x + width - 3, top + thumb, HomeLinkTheme.ACCENT);
        }
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (mouseX < x || mouseX >= x + width || mouseY < y + 23 || mouseY >= y + height - 22) return false;
        scroll(-(int) (amount * 3));
        return true;
    }

    public boolean keyPressed(int key) {
        switch (key) {
            case GLFW.GLFW_KEY_PAGE_UP -> scroll(-visibleLines());
            case GLFW.GLFW_KEY_PAGE_DOWN -> scroll(visibleLines());
            case GLFW.GLFW_KEY_HOME -> scroll(-lines.size());
            case GLFW.GLFW_KEY_END -> scroll(lines.size());
            case GLFW.GLFW_KEY_LEFT -> changeChapter(-1);
            case GLFW.GLFW_KEY_RIGHT -> changeChapter(1);
            default -> { return false; }
        }
        return true;
    }
}
