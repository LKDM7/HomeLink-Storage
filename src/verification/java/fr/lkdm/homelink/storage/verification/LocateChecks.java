package fr.lkdm.homelink.storage.verification;

import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.menu.StorageMenu;
import fr.lkdm.homelink.storage.network.LocateState;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/** Exercises locate requests through the real menu transport. */
public final class LocateChecks {
    private static final BlockPos CHEST = new BlockPos(0, 5, 3);
    private static final BlockPos CONTROLLER = new BlockPos(0, 5, 0);
    private static final BlockPos FAR = new BlockPos(1_000_000, 5, 1_000_000);
    private static final UUID UNLOADED_LINK = UUID.fromString("4df75110-f331-4d56-a72d-001f8a1f5925");
    private static final UUID UNLOADED_NODE = UUID.fromString("bd8c84a6-2e1d-4af4-b158-6c536a64b07c");
    private static String chestLink;
    private static long expiresAt;

    public static void requestValid(StorageMenu menu) {
        LocateState.clear();
        chestLink = menu.clientLocations().stream().filter(location -> location.position().equals(CHEST)).findFirst().orElseThrow().linkId().toString();
        menu.send("locate", chestLink, "");
    }

    public static boolean received() {
        var target = LocateState.current();
        if (target == null) return false;
        check(target.position().equals(CHEST), "Locate response targets the wrong inventory");
        check(target.dimension().equals("minecraft:overworld"), "Locate response has wrong dimension");
        check(target.expiresAtNanos() > System.nanoTime(), "Locate response already expired");
        expiresAt = target.expiresAtNanos();
        return true;
    }

    public static boolean expired() {
        if (LocateState.current() != null) return false;
        check(System.nanoTime() >= expiresAt, "Marker disappeared before its TTL elapsed");
        return true;
    }

    public static void requestUnknown(StorageMenu menu) {
        menu.send("locate", "95e46618-bffa-4dac-b96e-f5011795b2bd", "");
    }

    public static void removeChest(ServerPlayer player) {
        player.serverLevel().destroyBlock(CHEST, false);
        controller(player).refreshConnections();
        controller(player).refreshIndex();
    }

    public static void requestMissing(StorageMenu menu) { menu.send("locate", chestLink, ""); }

    public static void addUnloadedFixture(ServerPlayer player) {
        check(!player.serverLevel().hasChunkAt(FAR), "Unloaded locate fixture unexpectedly loaded");
        StorageBlockEntity controller = controller(player);
        CompoundTag saved = controller.saveWithFullMetadata(player.registryAccess());
        CompoundTag link = new CompoundTag();
        link.putUUID("Id", UNLOADED_LINK);
        link.putUUID("Source", UNLOADED_NODE);
        link.putLong("SourcePos", FAR.asLong());
        link.putLong("InventoryPos", FAR.below().asLong());
        link.putLong("AccessPos", FAR.below().asLong());
        link.putString("Name", "Unloaded locate fixture");
        link.putString("Zone", "misc");
        saved.getList("Inventories", 10).add(link);
        CompoundTag node = new CompoundTag(); node.putUUID("Id", UNLOADED_NODE); node.putLong("Pos", FAR.asLong()); node.putBoolean("Repeater", true);
        saved.getList("Coverage", 10).add(node);
        controller.loadWithComponents(saved, player.registryAccess());
        controller.refreshConnections();
        check(controller.connections().containsKey(UNLOADED_LINK), "Unloaded fixture must remain a known inventory, not an unknown UUID");
    }

    public static void requestUnloaded(StorageMenu menu) { menu.send("locate", UNLOADED_LINK.toString(), ""); }

    public static void verifyNoTarget() { check(LocateState.current() == null, "Rejected locate request created a marker"); }
    public static void verifyNoChunkLoad(ServerPlayer player) { check(!player.serverLevel().hasChunkAt(FAR), "Locate forced an unloaded chunk"); }

    private static StorageBlockEntity controller(ServerPlayer player) { return (StorageBlockEntity) player.serverLevel().getBlockEntity(CONTROLLER); }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private LocateChecks() { }
}
