package fr.lkdm.homelink.storage.client.screen;

import fr.lkdm.homelink.storage.client.rendering.StorageTheme;
import fr.lkdm.homelink.storage.client.widget.StorageButton;
import fr.lkdm.homelink.storage.menu.DepositMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** The Controller's graphite instrument frame, with an ordinary chest inventory. */
public final class DepositScreen extends AbstractContainerScreen<DepositMenu> {
    private static final String[] STATES = { "not_connected", "controller_offline", "idle", "sorting", "blocked" };
    public DepositScreen(DepositMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 300;
        imageHeight = 224;
        inventoryLabelX = 17;
        inventoryLabelY = 104;
    }
    @Override protected void init() {
        super.init();
        addRenderableWidget(StorageButton.builder(Component.translatable("screen.homelink_storage.close"), button -> onClose())
                .bounds(leftPos + 214, topPos + 200, 74, 18).build());
    }
    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        StorageTheme.frame(graphics, leftPos, topPos, imageWidth, imageHeight);
        StorageTheme.panel(graphics, leftPos + 190, topPos + 37, 98, 151);
        for (var slot : menu.slots) StorageTheme.panel(graphics, leftPos + slot.x - 1, topPos + slot.y - 1, 18, 18);
        graphics.fill(leftPos + 199, topPos + 49, leftPos + 203, topPos + 53, statusColor());
    }
    private int state() { return Math.clamp(menu.status(), 0, STATES.length - 1); }
    private int statusColor() {
        return switch (state()) {
            case 2, 3 -> StorageTheme.ONLINE;
            case 4 -> StorageTheme.WARNING;
            case 1 -> StorageTheme.OFFLINE;
            default -> StorageTheme.MUTED;
        };
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 12, 11, StorageTheme.TEXT, false);
        graphics.drawString(font, Component.translatable("screen.homelink_storage.deposit.contents"), 17, 37, StorageTheme.ACCENT, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, StorageTheme.MUTED, false);
        graphics.drawWordWrap(font, Component.translatable("screen.homelink_storage.deposit." + STATES[state()]),
                209, 47, 72, statusColor());
        graphics.fill(199, 88, 279, 89, StorageTheme.LINE);
        if (state() == 0) {
            graphics.drawWordWrap(font, Component.translatable("screen.homelink_storage.deposit.connect_hint"), 199, 94, 80, StorageTheme.MUTED);
        } else {
            var controller = menu.controllerName().isEmpty()
                    ? Component.translatable("block.homelink_storage.storage_controller") : Component.literal(menu.controllerName());
            var label = Component.translatable("screen.homelink_storage.deposit.controller", controller);
            int y = 94;
            for (var line : font.split(label, 80)) {
                if (y > 165) break;
                graphics.drawString(font, line, 199, y, StorageTheme.TEXT, false);
                y += 10;
            }
        }
        graphics.drawString(font, Component.translatable("screen.homelink_storage.deposit.cadence"), 12, 205, StorageTheme.MUTED, false);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
