package fr.lkdm.homelink.storage.storage.network;

import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Server-only selection uses authenticated player state, never client supplied ownership. */
public final class StorageBinding {
    public static void interact(ServerPlayer player, StorageBlockEntity source) {
        if (!source.canAccess(player)) { message(player, "denied"); return; }
        if (!player.isShiftKeyDown()) { player.openMenu(source, source.getBlockPos()); return; }
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
    private static void message(ServerPlayer player, String key) { player.displayClientMessage(Component.translatable("message.homelink_storage." + key), true); }
    private StorageBinding() {}
}
