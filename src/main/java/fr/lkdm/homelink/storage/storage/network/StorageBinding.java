package fr.lkdm.homelink.storage.storage.network;

import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Server-only selection uses authenticated player state, never client supplied ownership. */
public final class StorageBinding {
    public static void interact(ServerPlayer player, StorageBlockEntity source) {
        if (!source.canAccess(player)) { message(player, "denied"); return; }
        if (!player.isShiftKeyDown()) {
            // Terminal: items. Controller: management. Links and Repeaters just report their state.
            if (source.isTerminal() || source.isController()) player.openMenu(source, source.getBlockPos());
            else {
                player.displayClientMessage(status(source), true);
                if (source.isCoverageNode()) net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, coverage(source));
            }
            return;
        }
        if (!source.permission(player, fr.lkdm.homecore.api.security.Permission.CONFIGURE)) { message(player, "denied"); return; }
        if (source.isController()) {
            CompoundTag selected = new CompoundTag();
            selected.putLong("Position", source.getBlockPos().asLong());
            selected.putString("Dimension", player.level().dimension().location().toString());
            selected.putUUID("Id", source.id());
            player.getPersistentData().put("HomeLinkStorageSelection", selected);
            message(player, "selected"); return;
        }
        CompoundTag selected = player.getPersistentData().getCompound("HomeLinkStorageSelection");
        bindSelected(player, source, selected);
    }
    public static void bindSelected(ServerPlayer player, StorageBlockEntity source, CompoundTag selected) {
        if (!source.permission(player, fr.lkdm.homecore.api.security.Permission.CONFIGURE)) { message(player, "denied"); return; }
        if (!selected.hasUUID("Id") || !selected.getString("Dimension").equals(player.level().dimension().location().toString())) {
            message(player, "select_first"); return;
        }
        BlockPos pos = BlockPos.of(selected.getLong("Position"));
        if (!player.serverLevel().hasChunkAt(pos) || !(player.serverLevel().getBlockEntity(pos) instanceof StorageBlockEntity controller)
                || !controller.isController() || !controller.id().equals(selected.getUUID("Id")) || !controller.permission(player, fr.lkdm.homecore.api.security.Permission.CONFIGURE)) {
            message(player, "unavailable"); return;
        }
        if (source.bind(controller)) message(player, "bound"); else message(player, "limit");
    }
    /** Aggregate state only: no item, inventory name or position is disclosed outside the Terminal. */
    public static Component status(StorageBlockEntity source) {
        StorageBlockEntity controller = source.controller();
        if (source.isController()) {
            var index = source.index();
            long online = source.connections().values().stream()
                    .filter(connection -> connection.status == StorageInventoryAdapter.Status.ONLINE).count();
            if (source.connections().isEmpty()) return text("controller_empty", source.getDisplayName());
            return text("controller_status", source.getDisplayName(), online, source.connections().size(), index.totalItems(),
                    index.totalSlots() == 0 ? 0 : 100L * index.occupiedSlots() / index.totalSlots());
        }
        if (source.controllerPos() == null) return text("node_unbound");
        if (controller == null) return text("node_controller_unavailable");
        if (!controller.isCoverageActive(source.id())) return text("node_offline", controller.getDisplayName());
        long covered = controller.connections().values().stream()
                .filter(connection -> source.id().equals(connection.sourceId) && connection.status == StorageInventoryAdapter.Status.ONLINE).count();
        return text(source.isRepeater() ? "repeater_status" : "link_status", controller.getDisplayName(), covered);
    }
    /**
     * Chunk columns covered by the clicked node and by the other nodes of its network.
     * Only chunk and node positions are disclosed, to a player who may already view the network.
     */
    public static fr.lkdm.homelink.storage.network.StoragePackets.Coverage coverage(StorageBlockEntity source) {
        var chunks = new java.util.ArrayList<fr.lkdm.homelink.storage.network.CoverageState.Chunk>();
        var own = new net.minecraft.world.level.ChunkPos(source.getBlockPos());
        StorageBlockEntity controller = source.controller();
        boolean focusActive = false;
        if (controller != null) for (CoverageNode node : controller.coverageNodes().values()) {
            boolean focus = node.id.equals(source.id());
            boolean active = controller.isCoverageActive(node.id);
            if (focus) { focusActive = active; continue; }
            chunks.add(new fr.lkdm.homelink.storage.network.CoverageState.Chunk(node.chunk().x, node.chunk().z, node.position, active, node.repeater, false));
        }
        chunks.add(new fr.lkdm.homelink.storage.network.CoverageState.Chunk(own.x, own.z, source.getBlockPos(), focusActive, source.isRepeater(), true));
        return new fr.lkdm.homelink.storage.network.StoragePackets.Coverage(source.getLevel().dimension().location().toString(), chunks);
    }
    private static Component text(String key, Object... args) { return Component.translatable("message.homelink_storage." + key, args); }
    private static void message(ServerPlayer player, String key) { player.displayClientMessage(Component.translatable("message.homelink_storage." + key), true); }
    private StorageBinding() {}
}
