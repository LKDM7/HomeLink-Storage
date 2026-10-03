package fr.lkdm.homelink.storage.client.screen;

import fr.lkdm.homecore.api.client.ui.HomeLinkTheme;
import fr.lkdm.homecore.api.client.ui.HomeLinkUi;
import fr.lkdm.homecore.api.client.ui.HomeLinkButton;
import fr.lkdm.homecore.api.client.ui.HomeLinkScreenLayout;
import fr.lkdm.homelink.storage.client.rendering.StorageStatusColors;

import fr.lkdm.homelink.storage.client.recipe.RecipeViewerBridge;
import fr.lkdm.homelink.storage.client.widget.StorageManualView;
import fr.lkdm.homelink.storage.menu.StorageMenu;
import fr.lkdm.homelink.storage.network.StorageData;
import fr.lkdm.homelink.storage.storage.inventory.StorageWithdrawal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
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
    /** Fixed by the opened block: Controller = management, Terminal = items. */
    private final boolean management;
    private EditBox controllerName;
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
    private EditBox quantity;
    private int lastClickIndex = -1;
    private long lastClickTime;

    /** Grid item under the cursor with its screen area, for tooltips and recipe viewers. */
    public record Hovered(ItemStack stack, int x, int y, int width, int height) {}

    public StorageScreen(StorageMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        management = menu.managesNetwork();
        imageWidth = 380;
        imageHeight = 220;
    }

    private static net.minecraft.network.chat.MutableComponent text(String key, Object... args) {
        return Component.translatable("screen.homelink_storage." + key, args);
    }

    private Button button(String key, int x, int y, int width, Runnable action) {
        Button control = addRenderableWidget(HomeLinkButton.builder(text(key), button -> action.run())
                .bounds(leftPos + x, topPos + y, width, HomeLinkTheme.CONTROL_HEIGHT).build());
        control.setTooltip(Tooltip.create(text(key)));
        return control;
    }

    public boolean manualOpen() { return manualOpen; }
    public StorageManualView manual() { return manual; }
    public void toggleManual() { manualOpen = !manualOpen; rebuildWidgets(); }

    private EditBox input(EditBox edit) {
        return addRenderableWidget(HomeLinkUi.input(edit));
    }

    private int rightX() { return imageWidth / 2 + 8; }
    private int rightWidth() { return imageWidth - rightX() - 12; }
    private int listWidth() { return rightX() - 18; }
    private int listEnd() { return 10 + listWidth(); }

    private String clip(Component value, int width) { return HomeLinkUi.clip(font, value.getString(), width); }

    @Override protected void init() {
        imageWidth = HomeLinkScreenLayout.fit(width, height, 380, 220).width();
        super.init();
        String query = search == null ? "" : search.getValue();
        var help = button("manual", imageWidth - 34, 6, 20, this::toggleManual);
        ((HomeLinkButton) help).selected(manualOpen);
        help.setTooltip(Tooltip.create(Component.translatable("manual.homelink_storage.title")));
        if (manualOpen) {
            if (manual == null) manual = new StorageManualView(font);
            manual.init(leftPos + 10, topPos + 44, imageWidth - 20, imageHeight - 77,
                    this::addRenderableWidget, this::rebuildWidgets);
            button("manual_back", 10, 194, listWidth(), this::toggleManual);
            button("close", rightX(), 194, rightWidth(), this::onClose);
            return;
        }
        if (management) {
            // Controller: network management only, no item list and no withdrawal.
            String draftController = controllerName == null ? menu.clientName() : controllerName.getValue();
            controllerName = input(new EditBox(font, leftPos + 10, topPos + 42, imageWidth - 102, 18, text("controller_name")));
            controllerName.setMaxLength(64);
            controllerName.setHint(text("controller_name"));
            controllerName.setValue(draftController);
            button("rename_controller", imageWidth - 88, 42, 76, () -> menu.send("rename_controller", "", controllerName.getValue()));
            String draftName = name == null ? selectedLocation == null ? "" : selectedLocation.name() : name.getValue();
            String draftZone = zoneName == null ? "" : zoneName.getValue();
            name = input(new EditBox(font, leftPos + rightX(), topPos + 74, rightWidth(), 18, text("name")));
            name.setMaxLength(64);
            name.setHint(text("name"));
            name.setValue(draftName);
            button("rename_inventory", rightX(), 96, rightWidth(), () -> {
                if (selectedLocation != null) menu.send("rename_inventory", selectedLocation.linkId().toString(), name.getValue());
            });
            button("assign_zone", rightX(), 118, rightWidth(), () -> {
                assignmentZone = cycleZone(assignmentZone, false);
                if (selectedLocation != null && !assignmentZone.isEmpty())
                    menu.send("set_zone", selectedLocation.linkId().toString(), assignmentZone);
            });
            zoneName = input(new EditBox(font, leftPos + rightX(), topPos + 146, rightWidth(), 18, text("new_zone")));
            zoneName.setMaxLength(48);
            zoneName.setHint(text("new_zone"));
            zoneName.setValue(draftZone);
            button("create_zone", rightX(), 168, rightWidth(), () -> menu.send("create_zone", "", zoneName.getValue()));
            button("forget_offline", rightX(), 194, rightWidth(), () -> {
                if (selectedLocation != null) menu.send("forget_inventory", selectedLocation.linkId().toString(), "");
            });
            int refreshWidth = listWidth() * 56 / 180;
            int pipesWidth = listWidth() * 58 / 180;
            button("refresh", 10, 194, refreshWidth, () -> menu.send("refresh", "", ""));
            // Pipes / recovery view of this Controller, answered by the server after its own checks.
            button("pipes", 14 + refreshWidth, 194, pipesWidth, () -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new fr.lkdm.homelink.storage.logistics.sync.PipePayloads.RecoveryAction(menu.position(), "open", "")));
            button("close", 18 + refreshWidth + pipesWidth, 194,
                    listWidth() - refreshWidth - pipesWidth - 8, this::onClose);
        } else {
            // Terminal: find and take items; configuration lives on the Controller.
            boolean viewer = RecipeViewerBridge.available();
            if (search == null && RecipeViewerBridge.synchronizedSearch()) query = RecipeViewerBridge.viewerSearch();
            search = input(new EditBox(font, leftPos + 10, topPos + 42, imageWidth - (viewer ? 44 : 22), 18, text("search")));
            search.setMaxLength(128);
            search.setHint(text("search"));
            search.setValue(query);
            search.setResponder(value -> {
                dirty = true; scroll = 0;
                if (RecipeViewerBridge.synchronizedSearch()) RecipeViewerBridge.pushSearch(value);
            });
            if (viewer) {
                var sync = (HomeLinkButton) button("recipe_sync", imageWidth - 30, 42, 18, () -> {
                    RecipeViewerBridge.setSynchronizedSearch(!RecipeViewerBridge.synchronizedSearch());
                    if (RecipeViewerBridge.synchronizedSearch()) RecipeViewerBridge.pushSearch(search.getValue());
                    rebuildWidgets();
                });
                sync.selected(RecipeViewerBridge.synchronizedSearch());
                sync.setMessage(Component.literal("⇄"));
                sync.setTooltip(Tooltip.create(text(RecipeViewerBridge.synchronizedSearch() ? "recipe_sync_on" : "recipe_sync_off")));
            }
            int filterWidth = (listWidth() - 4) / 2;
            ((HomeLinkButton) button(byCount ? "sort_count" : "sort_name", 10, 64, filterWidth, () -> { byCount = !byCount; dirty = true; rebuildWidgets(); })).selected(byCount);
            ((HomeLinkButton) button(variants ? "variants" : "aggregate", 14 + filterWidth, 64, filterWidth, () -> { variants = !variants; dirty = true; rebuildWidgets(); })).selected(variants);
            var zoneButton = addRenderableWidget(HomeLinkButton.builder(zone.isEmpty() ? text("all_zones") : Component.literal(zoneLabel(zone)), button -> {
                zone = cycleZone(zone, true); dirty = true; scroll = 0; rebuildWidgets();
            }).bounds(leftPos + rightX() - 4, topPos + 64, rightWidth() + 4, HomeLinkTheme.CONTROL_HEIGHT).build());
            zoneButton.setTooltip(Tooltip.create(zoneButton.getMessage()));
            int closeWidth = listWidth() * 80 / 180;
            int quantityWidth = rightWidth() * 54 / 170;
            int takeWidth = rightWidth() * 88 / 170;
            int locateWidth = imageWidth - 34 - closeWidth - quantityWidth - takeWidth;
            int locateX = 14 + closeWidth;
            int quantityX = locateX + locateWidth + 4;
            button("close", 10, 194, closeWidth, this::onClose);
            button("locate", locateX, 194, locateWidth, () -> {
                if (selectedLocation != null && !pendingMode()) menu.send("locate", selectedLocation.linkId().toString(), "");
            });
            String draftQuantity = quantity == null ? "64" : quantity.getValue();
            quantity = input(new EditBox(font, leftPos + quantityX, topPos + 194, quantityWidth, 18, text("quantity")));
            quantity.setMaxLength(4);
            quantity.setFilter(value -> value.chars().allMatch(Character::isDigit));
            quantity.setValue(draftQuantity);
            quantity.setTooltip(Tooltip.create(text("quantity_hint", StorageWithdrawal.MAX_REQUEST)));
            button("take", quantityX + quantityWidth + 4, 194, takeWidth, this::withdrawQuantity);
        }
        dirty = true;
    }

    /**
     * Requests the selected variant from any inventory of the network. The selected inventory,
     * if any, is emptied first; the server bounds the amount by stock and free space.
     */
    public void withdraw(int amount) {
        if (selected == null || amount < 1) return;
        if (pendingMode()) {
            menu.send("withdraw_pending", "", selected.id() + ":" + Math.min(amount, StorageWithdrawal.MAX_REQUEST));
            return;
        }
        if (selected.id() < 0) {
            variants = true; dirty = true; rebuildWidgets(); refreshRows();
            if (minecraft.player != null) minecraft.player.displayClientMessage(text("choose_variant"), true);
            return;
        }
        String preferred = selectedLocation == null ? "" : selectedLocation.linkId().toString();
        menu.send("withdraw_any", preferred, selected.id() + ":" + Math.min(amount, StorageWithdrawal.MAX_REQUEST));
    }

    private void withdrawQuantity() {
        if (quantity == null || quantity.getValue().isEmpty()) return;
        withdraw((int) Math.min(Long.parseLong(quantity.getValue()), StorageWithdrawal.MAX_REQUEST));
    }

    private void select(View row) {
        selected = row; selectedLocation = null; locationScroll = 0; refreshLocations();
    }

    /** Applies the grid mouse shortcuts to a row; returns the requested amount, or 0 for a plain selection. */
    public int clickRow(int index, int button, boolean shift, boolean control, boolean doubleClick) {
        if (index < 0 || index >= rows.size()) return 0;
        View row = rows.get(index);
        select(row);
        int amount = clickAmount(button, shift, control, doubleClick, row.count(), row.stack().getMaxStackSize());
        if (amount > 0) withdraw(amount);
        return amount;
    }

    /** Grid shortcuts: Ctrl 1 item, Shift/double-click a stack, right-click half a stack, middle-click everything. */
    public static int clickAmount(int button, boolean shift, boolean control, boolean doubleClick, long available, int maxStack) {
        if (button == 0 && control) return 1;
        if (button == 0 && (shift || doubleClick)) return maxStack;
        if (button == 1) return (int) Math.max(1, (Math.min(available, maxStack) + 1) / 2);
        if (button == 2) return StorageWithdrawal.MAX_REQUEST;
        return 0;
    }

    public Hovered hovered(double mouseX, double mouseY) {
        if (manualOpen || management || mouseX < leftPos + 10 || mouseX >= leftPos + listEnd()
                || mouseY < topPos + 85 || mouseY >= topPos + 187) return null;
        int line = (int) (mouseY - topPos - 85) / 17;
        int index = scroll + line;
        if (line >= 6 || index >= rows.size()) return null;
        return new Hovered(rows.get(index).stack(), leftPos + 12, topPos + 85 + line * 17, 16, 16);
    }

    /** Filter entry listing objects still waiting in the Deposits instead of the network. */
    public static final String PENDING_ZONE = "\u0001pending";

    private boolean pendingMode() { return PENDING_ZONE.equals(zone); }

    private long pendingTotal() { return menu.clientPending().stream().mapToLong(StorageData.Pending::count).sum(); }

    private String cycleZone(String current, boolean includeAll) {
        List<String> ids = new ArrayList<>(menu.clientZones().keySet());
        if (includeAll) ids.add(0, "");
        if (includeAll && (!menu.clientPending().isEmpty() || PENDING_ZONE.equals(current))) ids.add(PENDING_ZONE);
        return ids.isEmpty() ? "" : ids.get((ids.indexOf(current) + 1) % ids.size());
    }

    private String zoneLabel(String id) {
        if (PENDING_ZONE.equals(id)) return text("pending_zone", pendingTotal()).getString();
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

    public boolean pendingModeForTest() { return pendingMode(); }

    public void setZoneForTest(String id) {
        zone = id;
        dirty = true;
        refreshRows();
    }

    public int displayedRowCount() { refreshRows(); return rows.size(); }

    @Override protected void containerTick() {
        refreshRows();
        // The name arrives with the first snapshot, after the screen opened.
        if (controllerName != null && !controllerName.isFocused() && controllerName.getValue().isEmpty() && !menu.clientName().isEmpty())
            controllerName.setValue(menu.clientName());
    }

    private void refreshRows() {
        if (revision != menu.clientRevision()) { revision = menu.clientRevision(); dirty = true; }
        if (!dirty) return;
        dirty = false;
        rows.clear();
        if (pendingMode() && menu.clientPending().isEmpty()) zone = "";
        if (pendingMode()) { refreshPendingRows(); return; }
        Map<BlockPos, StorageData.Location> byPosition = new HashMap<>();
        for (StorageData.Location location : menu.clientLocations()) byPosition.putIfAbsent(location.position(), location);
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        Map<String, View> aggregate = new LinkedHashMap<>();
        // Aggregated rows withdraw the plain variant when one exists; other mixes need an explicit variant.
        Map<String, Long> plainVariant = new HashMap<>();
        for (StorageData.Row row : menu.clientRows()) {
            ItemStack stack = row.stack();
            String registry = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (!matches(stack, query)) continue;
            Map<BlockPos, Long> filteredLocations = new LinkedHashMap<>();
            row.locations().forEach((pos, count) -> {
                StorageData.Location location = byPosition.get(pos);
                if (zone.isEmpty() || (location != null && zone.equals(location.zone()))) filteredLocations.put(pos, count);
            });
            long count = filteredLocations.values().stream().mapToLong(Long::longValue).sum();
            if (count == 0) continue;
            if (variants) rows.add(new View(row.id(), stack, count, filteredLocations));
            else {
                if (stack.isComponentsPatchEmpty()) plainVariant.put(registry, row.id());
                View old = aggregate.get(registry);
                if (old == null) aggregate.put(registry, new View(row.id(), stack, count, filteredLocations));
                else {
                    filteredLocations.forEach((pos, quantity) -> old.locations().merge(pos, quantity, Long::sum));
                    aggregate.put(registry, new View(-1, old.stack().getItem().getDefaultInstance(), old.count() + count, old.locations()));
                }
            }
        }
        if (!variants) aggregate.forEach((registry, view) -> rows.add(view.id() >= 0 ? view
                : new View(plainVariant.getOrDefault(registry, -1L), view.stack(), view.count(), view.locations())));
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

    private static boolean matches(ItemStack stack, String query) {
        String registry = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return query.isEmpty() || (query.startsWith("#")
                ? stack.getTags().anyMatch(tag -> tag.location().toString().contains(query.substring(1)))
                : registry.contains(query) || stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query));
    }

    /** Exact variants waiting in the Deposits; their "locations" are the Deposits holding them. */
    private void refreshPendingRows() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        for (StorageData.Pending waiting : menu.clientPending())
            if (matches(waiting.stack(), query)) rows.add(new View(waiting.index(), waiting.stack(), waiting.count(), new LinkedHashMap<>(waiting.deposits())));
        Comparator<View> order = Comparator.comparing(view -> view.stack().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER);
        if (byCount) order = Comparator.comparingLong(View::count).reversed().thenComparing(order);
        rows.sort(order);
        scroll = Math.min(scroll, Math.max(0, rows.size() - 6));
        if (selected != null) {
            ItemStack previous = selected.stack();
            selected = rows.stream().filter(view -> ItemStack.isSameItemSameComponents(view.stack(), previous)).findFirst().orElse(null);
        }
        if (selected == null && !rows.isEmpty()) selected = rows.get(0);
        refreshLocations();
    }

    private void refreshLocations() {
        locations.clear();
        if (pendingMode()) {
            if (selected != null) selected.locations().keySet().forEach(pos -> locations.add(new StorageData.Location(
                    new java.util.UUID(0, pos.asLong()), pos, text("deposit_label").getString(), PENDING_ZONE, "PENDING")));
            selectedLocation = locations.isEmpty() ? null : locations.get(0);
            locationScroll = 0;
            return;
        }
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
        HomeLinkUi.frame(graphics, leftPos, topPos, imageWidth, imageHeight);
        if (manualOpen) { manual.render(graphics); return; }
        HomeLinkUi.panel(graphics, leftPos + 9, topPos + 84, listWidth() + 2, 106);
        if (!management) HomeLinkUi.panel(graphics, leftPos + rightX() - 3, topPos + 84, rightWidth() + 4, 106);
        if (!management) for (int i = 0; i < 6 && scroll + i < rows.size(); i++) {
            View row = rows.get(scroll + i);
            int y = topPos + 85 + i * 17;
            if (row == selected) {
                graphics.fill(leftPos + 10, y, leftPos + listEnd(), y + 17, HomeLinkTheme.HOVER);
                graphics.fill(leftPos + 10, y + 2, leftPos + 12, y + 15, HomeLinkTheme.ACCENT);
            }
            graphics.renderItem(row.stack(), leftPos + 12, y);
            String count = HomeLinkUi.clip(font, Long.toString(row.count()), listWidth() / 2);
            int countX = listEnd() - 4 - font.width(count);
            graphics.drawString(font, clip(row.stack().getHoverName(), Math.min(104, countX - 37)), leftPos + 31, y + 4, HomeLinkTheme.TEXT, false);
            graphics.drawString(font, count, leftPos + countX, y + 4, HomeLinkTheme.ACCENT, false);
        }
        int locationX = management ? 12 : rightX() + 2;
        int locationWidth = management ? listWidth() - 12 : rightWidth() - 10;
        int locationY = management ? 86 : 119;
        int height = management ? 17 : 23;
        for (int i = 0; i < (management ? 6 : 3) && locationScroll + i < locations.size(); i++) {
            StorageData.Location location = locations.get(locationScroll + i);
            int y = topPos + locationY + i * height;
            if (location == selectedLocation) graphics.fill(leftPos + locationX - 2, y - 1,
                    leftPos + locationX + locationWidth + 8, y + height - 1, HomeLinkTheme.HOVER);
            graphics.drawString(font, HomeLinkUi.clip(font, inventoryLabel(location), locationWidth), leftPos + locationX, y + 1, HomeLinkTheme.TEXT, false);
            if (!management) {
                long quantity = selected == null ? 0 : selected.locations().getOrDefault(location.position(), 0L);
                String info = quantity + " · " + zoneLabel(location.zone());
                graphics.drawString(font, HomeLinkUi.clip(font, info, locationWidth), leftPos + locationX, y + 11, HomeLinkTheme.MUTED, false);
            }
        }
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        String heading = menu.clientName().isBlank() ? title.getString() : menu.clientName();
        graphics.drawString(font, HomeLinkUi.clip(font, heading, imageWidth - 74), 14, 11, HomeLinkTheme.TEXT, false);
        var stats = menu.clientStats();
        HomeLinkUi.statusDot(graphics, imageWidth - 50, 11,
                stats.connected() && menu.clientPowered() ? HomeLinkTheme.ONLINE : HomeLinkTheme.OFFLINE);
        if (manualOpen) {
            graphics.drawString(font, clip(Component.translatable("manual.homelink_storage.title"), imageWidth - 20), 10, 32, HomeLinkTheme.ACCENT, false);
            return;
        }
        Component summary = stats.connected() && !menu.clientPowered() ? text("no_power") : stats.connected() ? text("stats", stats.items(), stats.unique(), stats.inventories(),
                stats.slots() == 0 ? 0 : (int) (100L * stats.occupied() / stats.slots())) : text("disconnected");
        long waiting = management ? 0 : pendingTotal();
        String badge = waiting > 0 ? clip(text("pending_badge", waiting), (imageWidth - 20) / 2) : "";
        int badgeWidth = badge.isEmpty() ? 0 : font.width(badge) + 8;
        graphics.drawString(font, clip(summary, imageWidth - 20 - badgeWidth), 10, 32, HomeLinkTheme.MUTED, false);
        if (!badge.isEmpty()) graphics.drawString(font, badge, imageWidth - 10 - font.width(badge), 32, HomeLinkTheme.WARNING, false);
        if (management) {
            graphics.drawString(font, clip(text("inventories"), listWidth() - 4), 12, 70, HomeLinkTheme.MUTED, false);
            if (selectedLocation != null) graphics.drawString(font, clip(statusLabel(selectedLocation), rightWidth()), rightX(), 62, StorageStatusColors.color(selectedLocation.status()), false);
        } else if (selected != null) {
            graphics.drawString(font, clip(selected.stack().getHoverName(), rightWidth()), rightX(), 88, HomeLinkTheme.TEXT, false);
            int statusWidth = selectedLocation == null ? 0 : Math.min(rightWidth() / 2, font.width(statusLabel(selectedLocation)));
            graphics.drawString(font, clip(text("total", selected.count()), rightWidth() - statusWidth - 4), rightX(), 102, HomeLinkTheme.ACCENT, false);
            if (selectedLocation != null) {
                String status = clip(statusLabel(selectedLocation), statusWidth);
                graphics.drawString(font, status, imageWidth - 12 - font.width(status), 102, StorageStatusColors.color(selectedLocation.status()), false);
            }
        } else graphics.drawString(font, text("empty"), 12, 94, HomeLinkTheme.MUTED, false);
        if (selectedLocation != null && !management) {
            BlockPos pos = selectedLocation.position();
            int distance = minecraft.player == null ? 0 : (int) Math.sqrt(minecraft.player.distanceToSqr(pos.getCenter()));
            graphics.drawString(font, clip(text("position", pos.getX(), pos.getY(), pos.getZ(), distance), rightWidth()), rightX(), 181, HomeLinkTheme.MUTED, false);
        }
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        Hovered hovered = hovered(mouseX, mouseY);
        if (hovered != null) {
            List<Component> lines = new ArrayList<>(getTooltipFromContainerItem(hovered.stack()));
            int row = scroll + (mouseY - topPos - 85) / 17;
            lines.add(text("total", rows.get(row).count()));
            lines.add(text("click_hint").withStyle(ChatFormatting.DARK_GRAY));
            lines.add(text("click_hint_more").withStyle(ChatFormatting.DARK_GRAY));
            graphics.renderTooltip(font, lines, hovered.stack().getTooltipImage(), mouseX, mouseY);
        } else {
            Component heading = menu.clientName().isBlank() ? title : Component.literal(menu.clientName());
            if (clippedTooltip(graphics, heading, 14, 11, imageWidth - 74, mouseX, mouseY) || manualOpen) return;
            if (management && selectedLocation != null) {
                clippedTooltip(graphics, statusLabel(selectedLocation), rightX(), 62, rightWidth(), mouseX, mouseY);
            } else if (selected != null) {
                if (clippedTooltip(graphics, selected.stack().getHoverName(), rightX(), 88, rightWidth(), mouseX, mouseY)) return;
                int statusWidth = selectedLocation == null ? 0 : Math.min(rightWidth() / 2, font.width(statusLabel(selectedLocation)));
                if (clippedTooltip(graphics, text("total", selected.count()), rightX(), 102, rightWidth() - statusWidth - 4, mouseX, mouseY)) return;
                if (selectedLocation != null) {
                    if (clippedTooltip(graphics, statusLabel(selectedLocation), imageWidth - 12 - statusWidth, 102, statusWidth, mouseX, mouseY)) return;
                    BlockPos pos = selectedLocation.position();
                    int distance = minecraft.player == null ? 0 : (int) Math.sqrt(minecraft.player.distanceToSqr(pos.getCenter()));
                    clippedTooltip(graphics, text("position", pos.getX(), pos.getY(), pos.getZ(), distance), rightX(), 181, rightWidth(), mouseX, mouseY);
                }
            }
        }
    }

    private boolean clippedTooltip(GuiGraphics graphics, Component value, int x, int y, int available, int mouseX, int mouseY) {
        if (font.width(value) <= available || mouseX < leftPos + x || mouseX >= leftPos + x + available
                || mouseY < topPos + y || mouseY >= topPos + y + 9) return false;
        graphics.renderTooltip(font, value, mouseX, mouseY);
        return true;
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (manualOpen) return super.mouseClicked(mouseX, mouseY, button);
        int x = (int) mouseX - leftPos;
        int y = (int) mouseY - topPos;
        if (!management && x >= 10 && x < listEnd() && y >= 85 && y < 187) {
            int index = scroll + (y - 85) / 17;
            if (index < rows.size() && button <= 2) {
                long now = Util.getMillis();
                boolean doubleClick = button == 0 && index == lastClickIndex && now - lastClickTime < 250;
                lastClickIndex = button == 0 && !doubleClick ? index : -1;
                lastClickTime = now;
                clickRow(index, button, hasShiftDown(), hasControlDown(), doubleClick);
            }
            return true;
        }
        if (button == 0) {
            int locationX = management ? 10 : rightX();
            int locationWidth = management ? listWidth() - 8 : rightWidth() + 2;
            int locationY = management ? 86 : 119;
            int height = management ? 17 : 23;
            if (x >= locationX && x < locationX + locationWidth && y >= locationY && y < locationY + height * (management ? 6 : 3)) {
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
        if (mouseX >= leftPos + 10 && mouseX <= leftPos + imageWidth - 10 && mouseY >= topPos + 84 && mouseY <= topPos + 190) {
            int change = deltaY > 0 ? -1 : 1;
            if (management || mouseX >= leftPos + rightX() - 4) locationScroll = Math.max(0, Math.min(locationScroll + change, locations.size() - (management ? 6 : 3)));
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
        if (quantity != null && getFocused() == quantity && quantity.isFocused() && (keyCode == 257 || keyCode == 335)) {
            withdrawQuantity();
            return true;
        }
        if (getFocused() instanceof EditBox edit && edit.isFocused() && keyCode != 256 && keyCode != 258) return edit.keyPressed(keyCode, scanCode, modifiers);
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
