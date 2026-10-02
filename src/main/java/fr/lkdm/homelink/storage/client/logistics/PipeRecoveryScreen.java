package fr.lkdm.homelink.storage.client.logistics;

import fr.lkdm.homelink.storage.client.rendering.StorageTheme;
import fr.lkdm.homelink.storage.client.widget.StorageButton;
import fr.lkdm.homelink.storage.logistics.network.PipeStatus;
import fr.lkdm.homelink.storage.logistics.sync.PipePayloads;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * "Pipes / Recovery" view of a Controller: circuit figures, the pause switch and the cargo
 * that left its source but has no usable route. Taking one back is an explicit CONTROL action.
 */
public final class PipeRecoveryScreen extends Screen {
    private static final int ROWS = 5;
    private final @Nullable Screen parent;
    private final BlockPos controller;
    private CompoundTag data;
    private int left, top, frameWidth, frameHeight, scroll;
    private record Row(String id, ItemStack stack, int count, String state, PipeStatus reason, boolean uncertain) { }
    private final List<Row> rows = new ArrayList<>();

    public PipeRecoveryScreen(@Nullable Screen parent, BlockPos controller, CompoundTag data) {
        super(Component.translatable("screen.homelink_storage.pipes.title"));
        this.parent = parent;
        this.controller = controller.immutable();
        accept(data);
    }

    public BlockPos controller() { return controller; }
    public int rowCount() { return rows.size(); }

    private static MutableComponent text(String key, Object... args) { return Component.translatable("screen.homelink_storage.pipes." + key, args); }

    public void accept(CompoundTag data) {
        this.data = data;
        rows.clear();
        var registries = net.minecraft.client.Minecraft.getInstance().level == null ? null : net.minecraft.client.Minecraft.getInstance().level.registryAccess();
        for (Tag entry : data.getList("Rows", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) entry;
            ItemStack stack = registries == null ? ItemStack.EMPTY : ItemStack.parseOptional(registries, row.getCompound("Stack"));
            rows.add(new Row(row.getString("Id"), stack, row.getInt("Count"), row.getString("State"), PipeStatus.byName(row.getString("Reason")),row.getBoolean("Uncertain")));
        }
        scroll = Math.max(0, Math.min(scroll, rows.size() - ROWS));
        if (minecraft != null) rebuildWidgets();
    }

    private void send(String action, String target) {
        PacketDistributor.sendToServer(new PipePayloads.RecoveryAction(controller, action, target));
    }

    @Override protected void init() {
        frameWidth = Math.min(320, width - 8);
        frameHeight = Math.min(222, height - 8);
        left = (width - frameWidth) / 2;
        top = (height - frameHeight) / 2;
        boolean control = data.getBoolean("CanControl");
        boolean paused = data.getBoolean("Paused");
        var pause = (StorageButton) StorageButton.builder(text(paused ? "resume" : "pause"), ignored -> send(paused ? "resume" : "pause", ""))
                .bounds(left + 10, top + 34, 140, 18).build();
        pause.active = control;
        pause.selected(paused);
        pause.setTooltip(Tooltip.create(text("pause_help")));
        addRenderableWidget(pause);
        var refresh = StorageButton.builder(text("refresh"), ignored -> send("open", "")).bounds(left + frameWidth - 110, top + 34, 100, 18).build();
        addRenderableWidget(refresh);
        int listTop = top + 98;
        for (int i = 0; i < ROWS && scroll + i < rows.size(); i++) {
            Row row = rows.get(scroll + i);
            int y = listTop + i * 20;
            if (y + 18 > top + frameHeight - 30) break;
            var take = StorageButton.builder(text("retrieve"), ignored -> send("retrieve", row.id()))
                    .bounds(left + frameWidth - 86, y, 76, 18).build();
            take.active = control && !row.uncertain();
            take.setTooltip(Tooltip.create(text("retrieve_help")));
            addRenderableWidget(take);
        }
        addRenderableWidget(StorageButton.builder(Component.translatable("screen.homelink_storage.close"), ignored -> onClose())
                .bounds(left + frameWidth - 90, top + frameHeight - 24, 80, 18).build());
    }

    @Override public void onClose() {
        if (minecraft != null && parent != null && minecraft.player != null && minecraft.player.containerMenu != minecraft.player.inventoryMenu)
            minecraft.setScreen(parent);
        else super.onClose();
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        StorageTheme.frame(graphics, left, top, frameWidth, frameHeight);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, font.plainSubstrByWidth(title.getString() + " — " + data.getString("Name"), frameWidth - 24), left + 12, top + 11, StorageTheme.TEXT, false);
        String status = data.getString("Status");
        Component state = status.equals("NONE") ? text("no_circuit") : Component.translatable(PipeStatus.byName(status).key());
        if (data.getBoolean("Partial")) state = state.copy().append(" ").append(text("partial"));
        graphics.drawString(font, font.plainSubstrByWidth(text("state", state).getString(), frameWidth - 24), left + 12, top + 58, StorageTheme.ACCENT, false);
        graphics.drawString(font, font.plainSubstrByWidth(text("figures", data.getInt("Circuits"), data.getInt("Pipes"), data.getInt("Sources"),
                data.getInt("Destinations")).getString(), frameWidth - 24), left + 12, top + 70, StorageTheme.MUTED, false);
        graphics.drawString(font, font.plainSubstrByWidth(text("transit", data.getInt("InFlight"), data.getLong("InTransit"), data.getLong("PerMinute"),
                data.getInt("Blocked")).getString(), frameWidth - 24), left + 12, top + 82, StorageTheme.MUTED, false);
        int listTop = top + 98;
        StorageTheme.panel(graphics, left + 9, listTop - 2, frameWidth - 18, ROWS * 20 + 2);
        if (rows.isEmpty()) graphics.drawString(font, text("empty"), left + 14, listTop + 4, StorageTheme.MUTED, false);
        for (int i = 0; i < ROWS && scroll + i < rows.size(); i++) {
            Row row = rows.get(scroll + i);
            int y = listTop + i * 20;
            if (y + 18 > top + frameHeight - 30) break;
            graphics.renderItem(row.stack(), left + 12, y + 1);
            String label = row.count() + " × " + row.stack().getHoverName().getString();
            graphics.drawString(font, font.plainSubstrByWidth(label, frameWidth - 128), left + 32, y + 1, StorageTheme.TEXT, false);
            Component reason = text(row.state().equals("RECOVERY") ? "row_recovery" : "row_blocked", Component.translatable(row.reason().key()));
            graphics.drawString(font, font.plainSubstrByWidth(reason.getString(), frameWidth - 128), left + 32, y + 10, StorageTheme.WARNING, false);
        }
        int unreadable = data.getInt("Unreadable");
        String result = data.getString("Result");
        if (unreadable > 0) graphics.drawString(font, font.plainSubstrByWidth(text("unreadable", unreadable).getString(), frameWidth - 110), left + 12, top + frameHeight - 34, StorageTheme.OFFLINE, false);
        else if (!result.isEmpty()) graphics.drawString(font, font.plainSubstrByWidth(text("result." + result).getString(), frameWidth - 110),
                left + 12, top + frameHeight - 19, result.equals("denied") || result.equals("unavailable") ? StorageTheme.WARNING : StorageTheme.ONLINE, false);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int next = Math.max(0, Math.min(rows.size() - ROWS, scroll + (deltaY > 0 ? -1 : 1)));
        if (next != scroll) { scroll = next; rebuildWidgets(); }
        return true;
    }

    @Override public boolean isPauseScreen() { return false; }
}
