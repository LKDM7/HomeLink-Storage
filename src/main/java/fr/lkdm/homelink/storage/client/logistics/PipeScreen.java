package fr.lkdm.homelink.storage.client.logistics;

import fr.lkdm.homelink.storage.client.rendering.StorageTheme;
import fr.lkdm.homelink.storage.client.widget.StorageButton;
import fr.lkdm.homelink.storage.logistics.filter.FilterMode;
import fr.lkdm.homelink.storage.logistics.filter.FlowMode;
import fr.lkdm.homelink.storage.logistics.network.PipeStatus;
import fr.lkdm.homelink.storage.logistics.sync.PipePayloads;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Settings of one pipe face, in two tabs: CONNEXION (direction seen from the container) and
 * FILTRE (whitelist or blacklist of item types). Everything is a local draft until Apply; the
 * server answers with the stored state, or a conflict if someone saved the face meanwhile.
 */
public final class PipeScreen extends Screen {
    private static final int SLOT = 18;
    private final BlockPos pos;
    private final Direction side;
    private CompoundTag data;
    private boolean filterTab;
    private int left, top, frameWidth, frameHeight;
    // Server state
    private boolean armed, canEdit;
    private FlowMode savedMode;
    private FilterMode savedFilter = FilterMode.BLACKLIST;
    private Set<ResourceLocation> savedItems = new LinkedHashSet<>();
    private long revision;
    private String port = "GENERIC", result = "";
    private PipeStatus status = PipeStatus.UPDATING;
    // Draft
    private FlowMode mode;
    private FilterMode filter = FilterMode.BLACKLIST;
    private final LinkedHashSet<ResourceLocation> items = new LinkedHashSet<>();
    private boolean selectedOnly;
    private EditBox search;
    private String query = "";
    private int scroll;
    private List<Grid> grid = List.of();
    private boolean gridDirty = true;
    private int maxFilter = 256;
    private int keyboardCell = -1;

    /** One cell of the filter grid; {@code entry} is null for a selected ID no longer registered. */
    private record Grid(ResourceLocation id, ItemStack icon, ItemCatalog.Entry entry) { }

    public PipeScreen(BlockPos pos, CompoundTag data) {
        super(Component.translatable("block.homelink_storage.storage_pipe"));
        this.pos = pos.immutable();
        this.side = Direction.from3DDataValue(data.getByte("Face"));
        accept(data, true);
    }

    public boolean shows(BlockPos pos, Direction side) { return this.pos.equals(pos) && this.side == side; }
    public boolean filterTab() { return filterTab; }
    public void setFilterTab(boolean value) { filterTab = value; rebuildWidgets(); }
    public Set<ResourceLocation> draftItems() { return java.util.Collections.unmodifiableSet(items); }
    public FlowMode draftMode() { return mode; }
    public FilterMode draftFilter() { return filter; }
    public String result() { return result; }
    public boolean autonomous() { return data.getBoolean("Autonomous"); }
    public int visibleCells() { refreshGrid(); return grid.size(); }

    private static MutableComponent text(String key, Object... args) {
        return Component.translatable("screen.homelink_storage.pipe." + key, args);
    }

    /** New server state. A conflict keeps the player's draft so nothing typed is lost. */
    public void accept(CompoundTag data, boolean reset) {
        this.data = data;
        armed = data.getBoolean("Armed");
        canEdit = data.getBoolean("CanEdit");
        int saved = data.getInt("Mode");
        savedMode = saved < 0 ? null : FlowMode.byId(saved);
        FilterMode filterMode = FilterMode.byId(data.getInt("Filter"));
        savedFilter = filterMode == null ? FilterMode.BLACKLIST : filterMode;
        savedItems = new LinkedHashSet<>();
        for (Tag entry : data.getList("Items", Tag.TAG_STRING)) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getAsString());
            if (id != null) savedItems.add(id);
        }
        revision = data.getLong("Revision");
        port = data.getString("Port");
        status = PipeStatus.byName(data.getString("Status"));
        maxFilter = Math.max(1, data.getInt("MaxFilter"));
        result = data.getString("Result");
        boolean conflict = result.startsWith("conflict");
        if (reset || !conflict) {
            mode = savedMode;
            filter = savedFilter;
            items.clear();
            items.addAll(savedItems);
        }
        gridDirty = true;
        if (minecraft != null) rebuildWidgets();
    }

    private boolean dirty() {
        return !Objects.equals(mode, savedMode) || filter != savedFilter || !items.equals(savedItems);
    }

    private boolean allowed(FlowMode candidate) {
        return switch (port) {
            case "INPUT" -> candidate == FlowMode.INSERT;
            case "OUTPUT" -> candidate == FlowMode.EXTRACT;
            case "NONE" -> false;
            default -> true;
        };
    }

    @Override protected void init() {
        frameWidth = Math.min(320, width - 8);
        frameHeight = Math.min(222, height - 8);
        left = (width - frameWidth) / 2;
        top = (height - frameHeight) / 2;
        StorageButton connection = add(text("tab_connection"), left + 10, top + 34, 96, () -> setFilterTab(false));
        connection.selected(!filterTab);
        StorageButton filterButton = add(text("tab_filter"), left + 110, top + 34, 96, () -> setFilterTab(true));
        filterButton.selected(filterTab);
        if (filterTab) initFilter(); else initConnection();
        int footer = top + frameHeight - 24;
        int third = (frameWidth - 28) / 3;
        StorageButton apply = add(text("apply"), left + 10, footer, third, this::apply);
        apply.active = canEdit && mode != null && allowed(mode) && (dirty() || !armed);
        StorageButton cancel = add(text("cancel"), left + 14 + third, footer, third, () -> {
            mode = savedMode; filter = savedFilter; items.clear(); items.addAll(savedItems); result = ""; gridDirty = true; rebuildWidgets();
        });
        cancel.active = dirty();
        add(Component.translatable("screen.homelink_storage.close"), left + 18 + third * 2, footer, frameWidth - 28 - third * 2, this::onClose);
    }

    private StorageButton add(Component label, int x, int y, int width, Runnable action) {
        StorageButton button = (StorageButton) StorageButton.builder(label, ignored -> action.run()).bounds(x, y, width, 18).build();
        button.setTooltip(Tooltip.create(label));
        return addRenderableWidget(button);
    }

    private void initConnection() {
        FlowMode shown = mode == null ? (allowed(FlowMode.EXTRACT) ? FlowMode.EXTRACT : FlowMode.INSERT) : mode;
        StorageButton direction = add(Component.literal("[ ").append(text(shown == FlowMode.INSERT ? "insert" : "extract")).append(" ]"),
                left + 10, top + 88, frameWidth - 20, () -> {
                    FlowMode next = mode == null ? shown : mode.toggled();
                    if (allowed(next)) mode = next;
                    rebuildWidgets();
                });
        direction.selected(mode != null);
        boolean oneWay = !allowed(FlowMode.INSERT) || !allowed(FlowMode.EXTRACT);
        direction.active = canEdit && !"NONE".equals(port) && (!oneWay || mode == null);
        Component hint = switch (port) {
            case "INPUT" -> text("port_input_only");
            case "OUTPUT" -> text("port_output_only");
            case "NONE" -> text("port_none");
            case "GENERIC" -> text("port_generic");
            default -> text("port_both");
        };
        direction.setTooltip(Tooltip.create(Component.empty().append(text(shown == FlowMode.INSERT ? "insert_help" : "extract_help"))
                .append("\n").append(hint.copy().withStyle(ChatFormatting.GRAY))));
    }

    private void initFilter() {
        StorageButton modeButton = add(text(filter == FilterMode.WHITELIST ? "whitelist" : "blacklist"), left + 10, top + 58, 110, () -> {
            filter = filter.toggled();
            rebuildWidgets();
        });
        modeButton.active = canEdit;
        modeButton.setTooltip(Tooltip.create(text(filter == FilterMode.WHITELIST ? "whitelist_help" : "blacklist_help")));
        search = new EditBox(font, left + 126, top + 58, frameWidth - 136, 18, text("search"));
        search.setMaxLength(64);
        search.setHint(text("search").withStyle(ChatFormatting.DARK_GRAY));
        search.setTextColor(StorageTheme.TEXT);
        search.setValue(query);
        search.setResponder(value -> { query = value; scroll = 0; gridDirty = true; });
        addRenderableWidget(search);
        StorageButton only = add(text("selected_only"), left + 10, top + 80, 110, () -> { selectedOnly = !selectedOnly; scroll = 0; gridDirty = true; rebuildWidgets(); });
        only.selected(selectedOnly);
        StorageButton clear = add(text("clear"), left + frameWidth - 110, top + 80, 100, () -> { items.clear(); gridDirty = true; rebuildWidgets(); });
        clear.active = canEdit && !items.isEmpty();
    }

    private void apply() {
        if (!canEdit || mode == null || !allowed(mode)) return;
        List<String> ids = new ArrayList<>();
        for (ResourceLocation id : items) ids.add(id.toString());
        PacketDistributor.sendToServer(new PipePayloads.ApplyFace(pos, (byte) side.get3DDataValue(), revision,
                (byte) mode.ordinal(), (byte) filter.ordinal(), ids));
    }

    // ---------------------------------------------------------------- filter grid

    private int columns() { return Math.max(1, (frameWidth - 26) / SLOT); }
    private int gridTop() { return top + 104; }
    private int rows() { return Math.max(1, (top + frameHeight - 42 - gridTop()) / SLOT); }

    private void refreshGrid() {
        if (!gridDirty) return;
        gridDirty = false;
        List<Grid> cells = new ArrayList<>();
        if (selectedOnly) {
            String text = query.trim().toLowerCase(Locale.ROOT);
            for (ResourceLocation id : items) {
                ItemCatalog.Entry entry = ItemCatalog.byId(id);
                if (!text.isEmpty() && (entry == null ? !id.toString().contains(text) : !entry.search().contains(text))) continue;
                cells.add(new Grid(id, entry == null ? new ItemStack(Items.BARRIER) : entry.icon(), entry));
            }
        } else for (ItemCatalog.Entry entry : ItemCatalog.search(query)) cells.add(new Grid(entry.id(), entry.icon(), entry));
        grid = cells;
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private int maxScroll() { return Math.max(0, (grid.size() + columns() - 1) / columns() - rows()); }

    private int cellAt(double mouseX, double mouseY) {
        int x = (int) mouseX - left - 10, y = (int) mouseY - gridTop();
        if (x < 0 || y < 0 || x >= columns() * SLOT || y >= rows() * SLOT) return -1;
        int index = (scroll + y / SLOT) * columns() + x / SLOT;
        return index < grid.size() ? index : -1;
    }

    /** Adds or removes one ID; the server validates the list again on Apply. */
    public void toggle(ResourceLocation id) {
        if (!canEdit) return;
        if (!items.remove(id)) {
            if (items.size() >= maxFilter) { result = "filter_too_many"; return; }
            items.add(id);
        }
        if (selectedOnly) gridDirty = true;
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- rendering

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        StorageTheme.frame(graphics, left, top, frameWidth, frameHeight);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        String container = data.getString("Name").isEmpty() ? blockName() : data.getString("Name");
        graphics.drawString(font, font.plainSubstrByWidth(title.getString() + " — " + container, frameWidth - 40), left + 12, top + 11, StorageTheme.TEXT, false);
        int lamp = switch (status.lamp()) { case ACTIVE -> StorageTheme.ONLINE; case WARNING -> StorageTheme.WARNING; default -> StorageTheme.OFFLINE; };
        graphics.fill(left + frameWidth - 22, top + 11, left + frameWidth - 14, top + 19, 0xFF1D1F20);
        graphics.fill(left + frameWidth - 20, top + 13, left + frameWidth - 16, top + 17, lamp);
        if (filterTab) renderFilter(graphics, mouseX, mouseY); else renderConnection(graphics, container);
        if (!result.isEmpty()) {
            int color = result.equals("saved") ? StorageTheme.ONLINE : StorageTheme.WARNING;
            Component message = text("result." + result);
            graphics.drawString(font, font.plainSubstrByWidth(message.getString(), frameWidth - 24), left + 12, top + frameHeight - 38, color, false);
        }
    }

    private String blockName() {
        ResourceLocation id = ResourceLocation.tryParse(data.getString("Block"));
        return id == null ? "?" : BuiltInRegistries.BLOCK.get(id).getName().getString();
    }

    private void line(GuiGraphics graphics, Component text, int y, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(text.getString(), frameWidth - 24), left + 12, y, color, false);
    }

    private void renderConnection(GuiGraphics graphics, String container) {
        int y = top + 60;
        line(graphics, text("container", container), y, StorageTheme.TEXT);
        line(graphics, text("side", Component.translatable("direction.homelink_storage." + side.getSerializedName())), y + 12, StorageTheme.MUTED);
        y = top + 112;
        if (mode == null) line(graphics, text("to_configure"), y, StorageTheme.WARNING);
        else line(graphics, text(mode == FlowMode.INSERT ? "insert_help" : "extract_help"), y, StorageTheme.ACCENT);
        if (dirty() || !armed && mode != null) line(graphics, text(canEdit ? "modified" : "read_only"), y + 12, StorageTheme.MUTED);
        else if (!canEdit) line(graphics, text("read_only"), y + 12, StorageTheme.MUTED);
        y += 28;
        if (y + 10 < top + frameHeight - 40) {
            StorageTheme.panel(graphics, left + 10, y - 3, frameWidth - 20, 28);
            line(graphics, text("status", Component.translatable(status.key())), y, StorageTheme.status(switch (status.lamp()) {
                case ACTIVE -> "ONLINE"; case WARNING -> "WARNING"; default -> "OFFLINE"; }));
            String controller = data.getString("Controller");
            PipeStatus circuit = PipeStatus.byName(data.getString("Circuit"));
            Component controllerLine = data.getBoolean("Autonomous") ? text("autonomous") : !controller.isEmpty() ? text("controller", controller)
                    : circuit == PipeStatus.NO_CONTROLLER ? text("no_controller")
                    : text("controller", Component.translatable(circuit.key()));
            line(graphics, controllerLine, y + 12, StorageTheme.MUTED);
        }
    }

    private void renderFilter(GuiGraphics graphics, int mouseX, int mouseY) {
        refreshGrid();
        String count = text("selected", items.size(), maxFilter).getString();
        graphics.drawString(font, count, left + 126 + (frameWidth - 246 - font.width(count)) / 2, top + 85, StorageTheme.ACCENT, false);
        int columns = columns(), rows = rows(), x0 = left + 10, y0 = gridTop();
        StorageTheme.panel(graphics, x0 - 1, y0 - 1, columns * SLOT + 2, rows * SLOT + 2);
        int hovered = cellAt(mouseX, mouseY);
        for (int row = 0; row < rows; row++) for (int column = 0; column < columns; column++) {
            int index = (scroll + row) * columns + column;
            if (index >= grid.size()) break;
            Grid cell = grid.get(index);
            int x = x0 + column * SLOT, y = y0 + row * SLOT;
            boolean selected = items.contains(cell.id());
            if (index == hovered || index == keyboardCell) graphics.fill(x, y, x + SLOT, y + SLOT, StorageTheme.HOVER);
            graphics.renderItem(cell.icon(), x + 1, y + 1);
            if (selected) {
                graphics.renderOutline(x, y, SLOT, SLOT, StorageTheme.ACCENT);
                graphics.fill(x + SLOT - 7, y + 1, x + SLOT - 1, y + 7, 0xFF1D1F20);
                graphics.drawString(font, "✔", x + SLOT - 7, y, StorageTheme.ACCENT, false);
            }
        }
        if (grid.isEmpty()) graphics.drawString(font, text("no_match"), x0 + 4, y0 + 5, StorageTheme.MUTED, false);
        int max = maxScroll();
        if (max > 0) {
            int track = rows * SLOT, thumb = Math.max(8, track * rows / (rows + max)), offset = (track - thumb) * scroll / max;
            graphics.fill(x0 + columns * SLOT + 3, y0 + offset, x0 + columns * SLOT + 5, y0 + offset + thumb, StorageTheme.ACCENT);
        }
        if (hovered >= 0) {
            Grid cell = grid.get(hovered);
            List<Component> tooltip = new ArrayList<>();
            if (cell.entry() == null) tooltip.add(text("missing", cell.id().toString()).withStyle(ChatFormatting.RED));
            else {
                tooltip.add(cell.icon().getHoverName());
                tooltip.add(Component.literal(cell.id().toString()).withStyle(ChatFormatting.DARK_GRAY));
            }
            if (canEdit) tooltip.add(text(items.contains(cell.id()) ? "click_unselect" : "click_select").withStyle(ChatFormatting.GRAY));
            graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    // ---------------------------------------------------------------- input

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (filterTab && button == 0) {
            refreshGrid();
            int cell = cellAt(mouseX, mouseY);
            if (cell >= 0) { keyboardCell = cell; setFocused(null); toggle(grid.get(cell).id()); return true; }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (filterTab) {
            refreshGrid();
            scroll = Math.max(0, Math.min(maxScroll(), scroll + (deltaY > 0 ? -1 : 1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Typed letters stay in the search field: no game key binding acts while it has focus.
        if (search != null && search.isFocused() && keyCode != GLFW.GLFW_KEY_ESCAPE && keyCode != GLFW.GLFW_KEY_TAB) {
            search.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if (filterTab && (keyCode == GLFW.GLFW_KEY_PAGE_DOWN || keyCode == GLFW.GLFW_KEY_PAGE_UP)) {
            refreshGrid();
            scroll = Math.max(0, Math.min(maxScroll(), scroll + (keyCode == GLFW.GLFW_KEY_PAGE_DOWN ? rows() : -rows())));
            return true;
        }
        if (filterTab && getFocused() == null) {
            refreshGrid();
            int move = switch (keyCode) {
                case GLFW.GLFW_KEY_LEFT -> -1;
                case GLFW.GLFW_KEY_RIGHT -> 1;
                case GLFW.GLFW_KEY_UP -> -columns();
                case GLFW.GLFW_KEY_DOWN -> columns();
                default -> 0;
            };
            if (move != 0 && !grid.isEmpty()) {
                keyboardCell = Math.max(0,Math.min(grid.size()-1,keyboardCell < 0 ? scroll*columns() : keyboardCell+move));
                int row = keyboardCell/columns();
                if (row < scroll) scroll = row;
                if (row >= scroll+rows()) scroll = row-rows()+1;
                return true;
            }
            if ((keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER) && keyboardCell >= 0 && keyboardCell < grid.size()) {
                toggle(grid.get(keyboardCell).id()); return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public boolean isPauseScreen() { return false; }
}
