package fr.lkdm.homelink.storage.logistics.filter;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Settings of one pipe face touching a container, stored with the pipe under its direction.
 *
 * <p>A face only takes part in transport once a player allowed to configure it has applied a
 * direction ({@link #armed()}). Arming records who did it and for which Controller and
 * HomeNetwork: a face armed for another Controller, or before the circuit was attached, needs a
 * new validation. Replacing the container disarms the face but keeps its direction and filter
 * as a draft; unloading a chunk changes nothing.</p>
 */
public final class PipeFaceConfig {
    private @Nullable FlowMode mode;
    private FilterMode filterMode = FilterMode.BLACKLIST;
    private final Set<ResourceLocation> items = new LinkedHashSet<>();
    private boolean armed;
    private @Nullable UUID armedBy;
    private @Nullable UUID armedController;
    private @Nullable UUID armedNetwork;
    /** Block of the container when the face was armed; another block means the container was replaced. */
    private String target = "";
    private long revision;

    public @Nullable FlowMode mode() { return mode; }
    public FilterMode filterMode() { return filterMode; }
    public Set<ResourceLocation> items() { return java.util.Collections.unmodifiableSet(items); }
    public PipeFilter filter() { return new PipeFilter(filterMode, items); }
    public boolean armed() { return armed && mode != null; }
    public @Nullable UUID armedBy() { return armedBy; }
    public @Nullable UUID armedController() { return armedController; }
    public @Nullable UUID armedNetwork() { return armedNetwork; }
    public String target() { return target; }
    public long revision() { return revision; }

    /** Applies a validated edit and arms the face for the given managing context. */
    public void apply(FlowMode mode, FilterMode filterMode, Set<ResourceLocation> items, UUID player,
                      @Nullable UUID controller, @Nullable UUID network, String target) {
        this.mode = mode;
        this.filterMode = filterMode;
        this.items.clear();
        this.items.addAll(items);
        this.armed = true;
        this.armedBy = player;
        this.armedController = controller;
        this.armedNetwork = network;
        this.target = target;
        revision++;
    }

    /** Stops transport on this face until a new validation; the draft settings stay. */
    public boolean disarm() {
        if (!armed) return false;
        armed = false;
        revision++;
        return true;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (mode != null) tag.putString("Mode", mode.name());
        tag.putString("Filter", filterMode.name());
        ListTag list = new ListTag();
        for (ResourceLocation id : items) list.add(StringTag.valueOf(id.toString()));
        tag.put("Items", list);
        tag.putBoolean("Armed", armed);
        if (armedBy != null) tag.putUUID("ArmedBy", armedBy);
        if (armedController != null) tag.putUUID("ArmedController", armedController);
        if (armedNetwork != null) tag.putUUID("ArmedNetwork", armedNetwork);
        tag.putString("Target", target);
        tag.putLong("Revision", revision);
        return tag;
    }

    /** Reads saved settings, dropping malformed entries; an unknown item ID is kept as missing. */
    public static PipeFaceConfig load(CompoundTag tag, int maximum) {
        PipeFaceConfig config = new PipeFaceConfig();
        config.mode = parse(FlowMode.class, tag.getString("Mode"));
        FilterMode filter = parse(FilterMode.class, tag.getString("Filter"));
        config.filterMode = filter == null ? FilterMode.BLACKLIST : filter;
        boolean invalid = filter == null;
        for (Tag entry : tag.getList("Items", Tag.TAG_STRING)) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getAsString());
            if (id != null) config.items.add(id);
            else invalid = true;
        }
        // Lowering the admission limit must not silently widen a saved blacklist.
        config.armed = tag.getBoolean("Armed") && config.mode != null && !invalid && config.items.size() <= maximum;
        config.armedBy = tag.hasUUID("ArmedBy") ? tag.getUUID("ArmedBy") : null;
        config.armedController = tag.hasUUID("ArmedController") ? tag.getUUID("ArmedController") : null;
        config.armedNetwork = tag.hasUUID("ArmedNetwork") ? tag.getUUID("ArmedNetwork") : null;
        if (config.armedBy == null) config.armed = false;
        config.target = tag.getString("Target");
        config.revision = Math.max(0, tag.getLong("Revision"));
        return config;
    }

    private static <E extends Enum<E>> @Nullable E parse(Class<E> type, String name) {
        for (E value : type.getEnumConstants()) if (value.name().equals(name)) return value;
        return null;
    }
}
