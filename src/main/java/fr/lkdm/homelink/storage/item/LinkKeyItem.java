package fr.lkdm.homelink.storage.item;

import fr.lkdm.homecore.api.security.Permission;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.storage.network.StorageBinding;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import java.util.List;

/** Carries a controller address, never an authorization token. */
public final class LinkKeyItem extends Item {
    public LinkKeyItem(Properties properties) { super(properties.stacksTo(1)); }

    public static void connect(ServerPlayer player, StorageBlockEntity source, ItemStack stack) {
        if (!source.permission(player, Permission.CONFIGURE)) {
            player.displayClientMessage(Component.translatable("message.homelink_storage.denied"), true);
            return;
        }
        if (source.isController()) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", source.id());
            tag.putLong("Position", source.getBlockPos().asLong());
            tag.putString("Dimension", player.level().dimension().location().toString());
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            player.displayClientMessage(Component.translatable("message.homelink_storage.key_selected"), true);
        } else {
            StorageBinding.bindSelected(player, source, stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag());
        }
    }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.homelink_storage.link_key"));
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (tag.hasUUID("Id")) {
            var pos = net.minecraft.core.BlockPos.of(tag.getLong("Position"));
            tooltip.add(Component.translatable("tooltip.homelink_storage.key_target", tag.getString("Dimension"), pos.getX(), pos.getY(), pos.getZ()));
        }
    }
}
