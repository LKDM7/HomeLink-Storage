package fr.lkdm.homelink.storage.client.widget;

import fr.lkdm.homelink.storage.client.rendering.StorageTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Dashboard-style beveled control retaining vanilla keyboard and narration behavior. */
public final class StorageButton extends Button {
    private boolean selected;
    private StorageButton(Builder builder) { super(builder); }
    public StorageButton selected(boolean value) { selected = value; return this; }

    public static Builder builder(Component message, OnPress press) {
        return new Builder(message, press) {
            @Override public Button build() { return new StorageButton(this); }
        };
    }

    @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX(), y = getY();
        int background = selected ? 0xFF292B2D : active && isHoveredOrFocused() ? 0xFF5A5D60 : active ? 0xFF474A4D : 0xFF36383A;
        graphics.fill(x, y, x + width, y + height, 0xFF181A1B);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, background);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, selected ? 0xFF202224 : active ? 0xFF74787A : 0xFF484B4D);
        graphics.fill(x + 1, y + 2, x + 2, y + height - 1, selected ? 0xFF202224 : 0xFF626669);
        if (isFocused() && active) graphics.renderOutline(x, y, width, height, StorageTheme.ACCENT);
        var font = Minecraft.getInstance().font;
        String label = getMessage().getString();
        int available = Math.max(0, width - 8);
        if (font.width(label) > available) label = font.plainSubstrByWidth(label, Math.max(0, available - font.width("…"))) + "…";
        graphics.drawString(font, label, x + (width - font.width(label)) / 2, y + (height - 8) / 2,
                selected ? StorageTheme.ACCENT : active ? StorageTheme.TEXT : 0xFF91948F, false);
    }
}
