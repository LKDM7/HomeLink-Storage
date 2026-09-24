package fr.lkdm.homelink.storage.client.rendering;

import net.minecraft.client.gui.GuiGraphics;

/** HomeLink graphite palette and hardware frame, matching the Dashboard's native interface. */
public final class StorageTheme {
    public static final int BACKGROUND = 0xFF303234;
    public static final int HEADER = 0xFF45474A;
    public static final int SURFACE = 0xFF252729;
    public static final int HOVER = 0xFF4A4D50;
    public static final int LINE = 0xFF626568;
    public static final int ACCENT = 0xFFD2B181;
    public static final int TEXT = 0xFFE7E5E0;
    public static final int MUTED = 0xFFAFB1AD;
    public static final int ONLINE = 0xFFA1BD92;
    public static final int WARNING = 0xFFD3B16F;
    public static final int OFFLINE = 0xFFD19A8F;

    public static int status(String value) {
        return switch (value) {
            case "ONLINE", "CONNECTED" -> ONLINE;
            case "WARNING", "UNLOADED" -> WARNING;
            case "OFFLINE", "ERROR", "INVALID", "INVENTORY_MISSING" -> OFFLINE;
            default -> MUTED;
        };
    }

    public static void frame(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x - 3, y - 3, x + width + 3, y + height + 3, 0xFF141617);
        graphics.fill(x - 2, y - 2, x + width + 2, y + height + 2, 0xFF6B6E70);
        graphics.fill(x, y, x + width, y + height, BACKGROUND);
        graphics.fill(x, y, x + width, y + 29, HEADER);
        graphics.renderOutline(x, y, width, height, LINE);
        graphics.fill(x + 10, y + height - 29, x + width - 10, y + height - 28, LINE);
        screw(graphics, x + 4, y + 4);
        screw(graphics, x + width - 8, y + 4);
        screw(graphics, x + 4, y + height - 8);
        screw(graphics, x + width - 8, y + height - 8);
    }

    public static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, SURFACE);
        graphics.fill(x, y, x + width, y + 1, 0xFF17191A);
        graphics.fill(x, y, x + 1, y + height, 0xFF17191A);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFF535659);
    }

    public static void screw(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 4, y + 4, 0xFF242628);
        graphics.fill(x, y, x + 3, y + 1, 0xFF727578);
        graphics.fill(x + 1, y + 2, x + 3, y + 3, 0xFF858887);
    }

    private StorageTheme() { }
}
