package fr.lkdm.homelink.storage.logistics.transit;

import com.mojang.logging.LogUtils;
import fr.lkdm.homelink.storage.config.StorageConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * The single authority over extracted cargo of one dimension, saved with the world.
 *
 * <p>Pipes hold settings only; this ledger holds every stack that left a source and has not
 * reached a container yet. Each cargo is owned by one Controller UUID. Entries that cannot be
 * read back (an item of a removed mod, a newer format) are preserved verbatim and reported,
 * never deleted. A ledger and the chunks are saved together by a clean save, but they are not
 * one disk transaction: a crash between them is outside the conservation guarantee.</p>
 */
public final class TransitLedger extends SavedData {
    public static final String NAME = "homelink_storage_transit";
    // v2 adds autonomous ownership; older readers must preserve it rather than treat it as an orphan Controller.
    public static final int FORMAT = 2;
    private final Map<UUID, TransitPacket> packets = new LinkedHashMap<>();
    private final ListTag unreadable = new ListTag();

    public static TransitLedger get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(TransitLedger::new, TransitLedger::load, null), NAME);
    }

    public Collection<TransitPacket> packets() { return Collections.unmodifiableCollection(packets.values()); }
    public @Nullable TransitPacket get(UUID id) { return packets.get(id); }
    public int unreadable() { return unreadable.size(); }

    public List<TransitPacket> owned(UUID controller) {
        List<TransitPacket> result = new ArrayList<>();
        for (TransitPacket packet : packets.values()) if (packet.controller.equals(controller)) result.add(packet);
        return result;
    }

    public int count(UUID controller) {
        int count = 0;
        for (TransitPacket packet : packets.values()) if (packet.controller.equals(controller)) count++;
        return count;
    }

    public void add(TransitPacket packet) {
        if (packets.putIfAbsent(packet.id, packet) != null) throw new IllegalStateException("Duplicate cargo " + packet.id);
        setDirty();
    }

    /** Removes a cargo whose stack has been delivered, dropped or handed to a player. */
    public @Nullable TransitPacket remove(UUID id) {
        TransitPacket removed = packets.remove(id);
        if (removed != null) setDirty();
        return removed;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Format", FORMAT);
        ListTag list = new ListTag();
        for (TransitPacket packet : packets.values()) list.add(packet.save(registries));
        tag.put("Packets", list);
        tag.put("Unreadable", unreadable.copy());
        return tag;
    }

    public static TransitLedger load(CompoundTag tag, HolderLookup.Provider registries) {
        TransitLedger ledger = new TransitLedger();
        for (Tag kept : tag.getList("Unreadable", Tag.TAG_COMPOUND)) ledger.unreadable.add(kept.copy());
        int format = tag.getInt("Format");
        ListTag list = tag.getList("Packets", Tag.TAG_COMPOUND);
        if (format > FORMAT) {
            // A newer Storage wrote this ledger: keep every entry untouched for that version.
            for (Tag entry : list) ledger.unreadable.add(entry.copy());
            LogUtils.getLogger().error("Storage transit ledger format {} is newer than {}; {} cargo entries kept aside unchanged", format, FORMAT, list.size());
            return ledger;
        }
        int route = StorageConfig.pipe(StorageConfig.PIPE_MAX_ROUTE);
        for (Tag entry : list) {
            CompoundTag compound = (CompoundTag) entry;
            var packet = TransitPacket.load(compound, registries, route);
            if (packet.isEmpty() || ledger.packets.containsKey(packet.get().id)) {
                ledger.unreadable.add(compound.copy());
                continue;
            }
            ledger.packets.put(packet.get().id, packet.get());
        }
        if (!ledger.unreadable.isEmpty())
            LogUtils.getLogger().warn("Storage transit ledger keeps {} unreadable cargo entries aside; they are saved unchanged", ledger.unreadable.size());
        return ledger;
    }
}
