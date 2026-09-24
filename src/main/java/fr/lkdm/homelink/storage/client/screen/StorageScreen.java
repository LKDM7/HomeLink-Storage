package fr.lkdm.homelink.storage.client.screen;

import fr.lkdm.homelink.storage.client.rendering.StorageTheme;
import fr.lkdm.homelink.storage.client.widget.StorageButton;
import fr.lkdm.homelink.storage.client.widget.StorageManualView;
import fr.lkdm.homelink.storage.menu.StorageMenu;
import fr.lkdm.homelink.storage.network.StorageData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Searches the synchronized cache and sends bounded withdrawal requests to the server. */
public final class StorageScreen extends AbstractContainerScreen<StorageMenu> {
    private record View(long id, ItemStack stack, long count, Map<BlockPos, Long> locations) {}
    private final List<View> rows = new ArrayList<>();
    private final List<StorageData.Location> locations = new ArrayList<>();
    private EditBox search;
    private EditBox name;
    private EditBox zoneName;
    private boolean management;
    private boolean byCount;
    private boolean variants;
    private boolean dirty = true;
    private String zone = "";
    private String assignmentZone = "";
    private long revision = Long.MIN_VALUE;
    private int scroll;
    private int locationScroll;
    private View selected;
    private StorageData.Location selectedLocation;
    private StorageManualView manual;
    private boolean manualOpen;

    public StorageScreen(StorageMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 380;
        imageHeight = 220;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("screen.homelink_storage." + key, args);
    }

    private Button button(String key, int x, int y, int width, Runnable action) {
        Button control = addRenderableWidget(StorageButton.builder(text(key), button -> action.run())
                .bounds(leftPos + x, topPos + y, width, 18).build());
        control.setTooltip(Tooltip.create(text(key)));
        return control;
    }

    public boolean manualOpen() { return manualOpen; }
    public StorageManualView manual() { return manual; }
    public void toggleManual() { manualOpen = !manualOpen; rebuildWidgets(); }

    private EditBox input(EditBox edit) {
        edit.setTextColor(StorageTheme.TEXT);
        edit.setTextColorUneditable(StorageTheme.MUTED);
        return addRenderableWidget(edit);
    }

    @Override protected void init() {
        super.init();
        String query = search == null ? "" : search.getValue();
        var help = button("manual", imageWidth - 34, 6, 20, this::toggleManual);
        ((StorageButton) help).selected(manualOpen);
        help.setTooltip(Tooltip.create(Component.translatable("manual.homelink_storage.title")));
        if (manualOpen) {
            if (manual == null) manual = new StorageManualView(font);
            manual.init(leftPos + 10, topPos + 44, imageWidth - 20, imageHeight - 77,
                    this::addRenderableWidget, this::rebuildWidgets);
            button("manual_back", 10, 194, 180, this::toggleManual);
            button("close", 198, 194, 170, this::onClose);
            return;
        }
        if (management) {
            String draftName = name == null ? selectedLocation == null ? "" : selectedLocation.name() : name.getValue();
            String draftZone = zoneName == null ? "" : zoneName.getValue();
            name = input(new EditBox(font, leftPos + 198, topPos + 74, 170, 18, text("name")));
            name.setMaxLength(64);
            name.setHint(text("name"));
            name.setValue(draftName);
            button("rename_inventory", 198, 96, 170, () -> {
                if (selectedLocation != null) menu.send("rename_inventory", selectedLocation.linkId().toString(), name.getValue());
            });
            button("assign_zone", 198, 118, 170, () -> {
                assignmentZone = cycleZone(assignmentZone, false);
                if (selectedLocation != null && !assignmentZone.isEmpty())
                    menu.send("set_zone", selectedLocation.linkId().toString(), assignmentZone);
            });
            zoneName = input(new EditBox(font, leftPos + 198, topPos + 146, 170, 18, text("new_zone")));
            zoneName.setMaxLength(48);
            zoneName.setHint(text("new_zone"));
            zoneName.setValue(draftZone);
            button("create_zone", 198, 168, 82, () -> menu.send("create_zone", "", zoneName.getValue()));
            button("rename_controller", 284, 168, 84, () -> menu.send("rename_controller", "", name.getValue()));
            button("forget_offline", 198, 194, 170, () -> {
                if (selectedLocation != null) menu.send("forget_inventory", selectedLocation.linkId().toString(), "");
            });
        } else {
            search = input(new EditBox(font, leftPos + 10, topPos + 42, 276, 18, text("search")));
            search.setMaxLength(128);
            search.setHint(text("search"));
            search.setValue(query);
            search.setResponder(value -> { dirty = true; scroll = 0; });
            ((StorageButton) button(byCount ? "sort_count" : "sort_name", 10, 64, 88, () -> { byCount = !byCount; dirty = true; rebuildWidgets(); })).selected(byCount);
            ((StorageButton) button(variants ? "variants" : "aggregate", 102, 64, 88, () -> { variants = !variants; dirty = true; rebuildWidgets(); })).selected(variants);
            var zoneButton = addRenderableWidget(StorageButton.builder(zone.isEmpty() ? text("all_zones") : Component.literal(zoneLabel(zone)), button -> {
                zone = cycleZone(zone, true); dirty = true; scroll = 0; rebuildWidgets();
            }).bounds(leftPos + 194, topPos + 64, 174, 18).build());
            zoneButton.setTooltip(Tooltip.create(zoneButton.getMessage()));
            button("locate", 138, 194, 80, () -> {
                if (selectedLocation != null) menu.send("locate", selectedLocation.linkId().toString(), "");
            });
            button("take_one", 222, 194, 70, () -> withdraw(1));
            button("take_stack", 296, 194, 72, () -> withdraw(64));
        }
        ((StorageButton) button(management ? "browse" : "manage", 292, 42, 76, () -> { management = !management; dirty = true; rebuildWidgets(); })).selected(management);
        button("refresh", 10, 194, management ? 86 : 60, () -> menu.send("refresh", "", ""));
        button("close", management ? 102 : 74, 194, management ? 88 : 60, this::onClose);
        dirty = true;
    }

    public void withdraw(int amount) {
        if (selected == null || selectedLocation == null) return;
        if (selected.id() < 0) {
            variants = true; dirty = true; rebuildWidgets(); refreshRows();
            if (minecraft.player != null) minecraft.player.displayClientMessage(text("choose_variant"), true);
            return;
        }
        menu.send("withdraw", selectedLocation.linkId().toString(), selected.id() + ":" + amount);
    }

    private String cycleZone(String current, boolean includeAll) {
        List<String> ids = new ArrayList<>(menu.clientZones().keySet());
        if (includeAll) ids.add(0, "");
        return ids.isEmpty() ? "" : ids.get((ids.indexOf(current) + 1) % ids.size());
    }

    private String zoneLabel(String id) {
        String value = menu.clientZones().getOrDefault(id, id);
        if (value.isBlank()) return Component.translatable("zone.homelink_storage." + id).getString();
        return value.startsWith("zone.homelink_storage.") ? Component.translatable(value).getString() : value;
    }

    private String inventoryLabel(StorageData.Location location) {
        if (!location.name().isBlank()) return location.name();
        BlockPos pos = location.position();
        return text("inventory_at", pos.getX(), pos.getY(), pos.getZ()).getString();
    }

    private Component statusLabel(StorageData.Location location) {
        return Component.translatable("status.homelink_storage." + location.status().toLowerCase(Locale.ROOT));
    }

    public void setSearchForTest(String value) {
        if (search != null) search.setValue(value);
        dirty = true;
        refreshRows();
    }

    public void setZoneForTest(String id) {
        zone = id;
        dirty = true;
        refreshRows();
    }

    public int displayedRowCount() { refreshRows(); return rows.size(); }

    @Override protected void containerTick() {
        refreshRows();
    }

    private void refreshRows() {
        if (revision != menu.clientRevision()) { revision = menu.clientRevision(); dirty = true; }
        if (!dirty) return;
        dirty = false;
        rows.clear();
        Map<BlockPos, StorageData.Location> byPosition = new HashMap<>();
        for (StorageData.Location location : menu.clientLocations()) byPosition.putIfAbsent(location.position(), location);
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        Map<String, View> aggregate = new LinkedHashMap<>();
        for (StorageData.Row row : menu.clientRows()) {
            ItemStack stack = row.stack();
            String registry = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            boolean match = query.isEmpty() || (query.startsWith("#")
                    ? stack.getTags().anyMatch(tag -> tag.location().toString().contains(query.substring(1)))
                    : registry.contains(query) || stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query));
            if (!match) continue;
            Map<BlockPos, Long> filteredLocations = new LinkedHashMap<>();
            row.locations().forEach((pos, count) -> {
                StorageData.Location location = byPosition.get(pos);
                if (zone.isEmpty() || (location != null && zone.equals(location.zone()))) filteredLocations.put(pos, count);
            });
            long count = filteredLocations.values().stream().mapToLong(Long::longValue).sum();
            if (count == 0) continue;
            if (variants) rows.add(new View(row.id(), stack, count, filteredLocations));
            else {
                View old = aggregate.get(registry);
                if (old == null) aggregate.put(registry, new View(row.id(), stack, count, filteredLocations));
                else {
                    filteredLocations.forEach((pos, quantity) -> old.locations().merge(pos, quantity, Long::sum));
                    aggregate.put(registry, new View(-1, old.stack().getItem().getDefaultInstance(), old.count() + count, old.locations()));
                }
            }
        }
        if (!variants) rows.addAll(aggregate.values());
        Comparator<View> order = Comparator.comparing(view -> view.stack().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER);
        if (byCount) order = Comparator.comparingLong(View::count).reversed().thenComparing(order);
        rows.sort(order);
        scroll = Math.min(scroll, Math.max(0, rows.size() - 6));
        if (selected != null) {
            ItemStack previous = selected.stack();
            selected = rows.stream().filter(view -> variants ? ItemStack.isSameItemSameComponents(view.stack(), previous)
                    : view.stack().is(previous.getItem())).findFirst().orElse(null);
        }
        if (selected == null && !rows.isEmpty()) selected = rows.get(0);
        refreshLocations();
    }

    private void refreshLocations() {
        locations.clear();
        java.util.Set<BlockPos> seenPositions = new java.util.HashSet<>();
        for (StorageData.Location location : menu.clientLocations()) {
            if (management || (selected != null && selected.locations().containsKey(location.position())
                    && seenPositions.add(location.position()))) locations.add(location);
        }
        locations.sort(Comparator.comparing(this::inventoryLabel, String.CASE_INSENSITIVE_ORDER));
        if (selectedLocation != null) {
            var oldId = selectedLocation.linkId();
            selectedLocation = locations.stream().filter(value -> value.linkId().equals(oldId)).findFirst().orElse(null);
        }
        if (selectedLocation == null && !locations.isEmpty()) selectedLocation = locations.get(0);
        locationScroll = Math.min(locationScroll, Math.max(0, locations.size() - (management ? 6 : 3)));
    }

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        refreshRows();
        StorageTheme.frame(graphics, leftPos, topPos, imageWidth, imageHeight);
        if (manualOpen) { manual.render(graphics); return; }
        StorageTheme.panel(graphics, leftPos + 9, topPos + 84, 182, 106);
        if (!management) StorageTheme.panel(graphics, leftPos + 195, topPos + 84, 174, 106);
        if (!management) for (int i = 0; i < 6 && scroll + i < rows.size(); i++) {
            View row = rows.get(scroll + i);
            int y = topPos + 85 + i * 17;
            if (row == selected) {
                graphics.fill(leftPos + 10, y, leftPos + 190, y + 17, StorageTheme.HOVER);
                graphics.fill(leftPos + 10, y + 2, leftPos + 12, y + 15, StorageTheme.ACCENT);
            }
            graphics.renderItem(row.stack(), leftPos + 12, y);
            graphics.drawString(font, font.plainSubstrByWidth(row.stack().getHoverName().getString(), 104), leftPos + 31, y + 4, StorageTheme.TEXT, false);
            String count = Long.toString(row.count());
            graphics.drawString(font, count, leftPos + 186 - font.width(count), y + 4, StorageTheme.ACCENT, false);
        }
        int locationX = management ? 12 : 200;
        int locationY = management ? 86 : 119;
        int height = management ? 17 : 23;
        for (int i = 0; i < (management ? 6 : 3) && locationScroll + i < locations.size(); i++) {
            StorageData.Location location = locations.get(locationScroll + i);
            int y = topPos + locationY + i * height;
            if (location == selectedLocation) graphics.fill(leftPos + locationX - 2, y - 1, leftPos + locationX + 168, y + height - 1, StorageTheme.HOVER);
            graphics.drawString(font, font.plainSubstrByWidth(inventoryLabel(location), 160), leftPos + locationX, y + 1, StorageTheme.TEXT, false);
            if (!management) {
                long quantity = selected == null ? 0 : selected.locations().getOrDefault(location.position(), 0L);
                String info = quantity + " · " + zoneLabel(location.zone());
                graphics.drawString(font, font.plainSubstrByWidth(info, 160), leftPos + locationX, y + 11, StorageTheme.MUTED, false);
            }
        }
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        String heading = menu.clientName().isBlank() ? title.getString() : menu.clientName();
        graphics.drawString(font, font.plainSubstrByWidth(heading, imageWidth - 74), 14, 11, StorageTheme.TEXT, false);
        var stats = menu.clientStats();
        graphics.fill(imageWidth - 50, 11, imageWidth - 42, 19, 0xFF1D1F20);
        graphics.fill(imageWidth - 48, 13, imageWidth - 44, 17, stats.connected() ? StorageTheme.ONLINE : StorageTheme.OFFLINE);
        if (manualOpen) {
            graphics.drawString(font, Component.translatable("manual.homelink_storage.title"), 10, 32, StorageTheme.ACCENT, false);
            return;
        }
        Component summary = stats.connected() ? text("stats", stats.items(), stats.unique(), stats.inventories(),
                stats.slots() == 0 ? 0 : (int) (100L * stats.occupied() / stats.slots())) : text("disconnected");
        graphics.drawString(font, font.plainSubstrByWidth(summary.getString(), imageWidth - 20), 10, 32, StorageTheme.MUTED, false);
        if (management) {
            graphics.drawString(font, text("inventories"), 12, 70, StorageTheme.MUTED, false);
            if (selectedLocation != null) graphics.drawString(font, statusLabel(selectedLocation), 198, 62, StorageTheme.status(selectedLocation.status()), false);
        } else if (selected != null) {
            graphics.drawString(font, font.plainSubstrByWidth(selected.stack().getHoverName().getString(), 170), 198, 88, StorageTheme.TEXT, false);
            graphics.drawString(font, text("total", selected.count()), 198, 102, StorageTheme.ACCENT, false);
            if (selectedLocation != null) {
                String status = statusLabel(selectedLocation).getString();
                graphics.drawString(font, status, 368 - font.width(status), 102, StorageTheme.status(selectedLocation.status()), false);
            }
        } else graphics.drawString(font, text("empty"), 12, 94, StorageTheme.MUTED, false);
        if (selectedLocation != null && !management) {
            BlockPos pos = selectedLocation.position();
            int distance = minecraft.player == null ? 0 : (int) Math.sqrt(minecraft.player.distanceToSqr(pos.getCenter()));
            graphics.drawString(font, text("position", pos.getX(), pos.getY(), pos.getZ(), distance), 198, 181, StorageTheme.MUTED, false);
        }
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!manualOpen && !management && mouseX >= leftPos + 10 && mouseX < leftPos + 190
                && mouseY >= topPos + 85 && mouseY < topPos + 187) {
            int index = scroll + (mouseY - topPos - 85) / 17;
            if (index < rows.size()) graphics.renderTooltip(font, rows.get(index).stack(), mouseX, mouseY);
        }
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (manualOpen) return super.mouseClicked(mouseX, mouseY, button);
        if (button == 0) {
            int x = (int) mouseX - leftPos;
            int y = (int) mouseY - topPos;
            if (!management && x >= 10 && x < 190 && y >= 85 && y < 187) {
                int index = scroll + (y - 85) / 17;
                if (index < rows.size()) { selected = rows.get(index); selectedLocation = null; locationScroll = 0; refreshLocations(); }
                return true;
            }
            int locationX = management ? 10 : 198;
            int locationY = management ? 86 : 119;
            int height = management ? 17 : 23;
            if (x >= locationX && x < locationX + 172 && y >= locationY && y < locationY + height * (management ? 6 : 3)) {
                int index = locationScroll + (y - locationY) / height;
                if (index < locations.size()) {
                    selectedLocation = locations.get(index);
                    assignmentZone = selectedLocation.zone();
                    if (management && name != null) name.setValue(selectedLocation.name());
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (manualOpen) return manual.mouseScrolled(mouseX, mouseY, deltaY) || super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
        if (mouseX >= leftPos + 10 && mouseX <= leftPos + 370 && mouseY >= topPos + 84 && mouseY <= topPos + 190) {
            int change = deltaY > 0 ? -1 : 1;
            if (management || mouseX >= leftPos + 194) locationScroll = Math.max(0, Math.min(locationScroll + change, locations.size() - (management ? 6 : 3)));
            else scroll = Math.max(0, Math.min(scroll + change, rows.size() - 6));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (manualOpen) {
            if (keyCode == 256) { toggleManual(); return true; }
            if (manual.keyPressed(keyCode)) return true;
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (getFocused() instanceof EditBox edit && edit.isFocused() && keyCode != 256) return edit.keyPressed(keyCode, scanCode, modifiers);
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
