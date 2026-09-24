package fr.lkdm.homelink.storage.verification;

import com.mojang.logging.LogUtils;
import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homecore.api.security.Permission;
import fr.lkdm.homelink.storage.block.StorageBlock;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import fr.lkdm.homelink.storage.storage.index.StorageIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.items.ItemStackHandler;

public final class RobustnessChecks {
    public static void run(ServerPlayer player) {
        var level = player.serverLevel();
        BlockPos pos = new BlockPos(0, 5, 14);
        BlockPos barrelPos = new BlockPos(3, 5, 14);
        StorageBlockEntity controller = place(player, pos, StorageRegistries.CONTROLLER.get());
        controller.ensureHomeCore();
        level.setBlockAndUpdate(barrelPos, Blocks.BARREL.defaultBlockState());
        StorageBlockEntity link = place(player, barrelPos.above(), StorageRegistries.LINK.get());
        check(link.bind(controller), "Robustness link binding failed");
        controller.refreshIndex();
        check(signal(controller) == 0, "Empty controller comparator must be zero");
        Container barrel = (Container) level.getBlockEntity(barrelPos);
        for (int slot = 0; slot < 14; slot++) barrel.setItem(slot, new ItemStack(Items.DIAMOND));
        controller.refreshIndex();
        check(signal(controller) > 0 && signal(controller) < 15, "Partial comparator capacity invalid");
        for (int slot = 14; slot < 27; slot++) barrel.setItem(slot, new ItemStack(Items.DIAMOND));
        controller.refreshIndex();
        check(signal(controller) == 15, "Full controller comparator must be fifteen");
        var manager = DashboardAPI.networks(level.getServer());
        ResourceLocation policy = ResourceLocation.fromNamespaceAndPath(StorageValidation.MOD_ID, "reachability");
        manager.setReachabilityPolicy(policy, (network, device) -> !device.id().equals(controller.id()));
        try { check(!controller.permission(player, Permission.VIEW), "Storage bypassed HomeCore reachability denial"); }
        finally { manager.setReachabilityPolicy(policy, (network, device) -> true); }

        StorageBlockEntity replacement = place(player, new BlockPos(5, 5, 14), StorageRegistries.CONTROLLER.get());
        replacement.ensureHomeCore();
        check(link.bind(replacement), "Rebinding failed");
        check(controller.index().totalItems() == 0 && controller.index().inventoryCount() == 0, "Old controller index retained rebound inventory");
        replacement.refreshIndex();
        check(replacement.index().totalItems() == 27, "New controller missed rebound inventory");
        var network = controller.networkId();
        var identity = controller.id();
        level.destroyBlock(pos, false);
        check(manager.getNetwork(network).map(home -> !home.devices().contains(identity)).orElse(true), "Destroyed controller left HomeNetwork membership");
        check(DashboardAPI.devices(level.getServer()).get(identity).isEmpty(), "Destroyed controller left live registration");

        boolean rejected = false;
        try {
            new StorageIndex().update(BlockPos.ZERO, new ItemStackHandler(0) {
                @Override public int getSlots() { return Integer.MAX_VALUE; }
                @Override public ItemStack getStackInSlot(int slot) { throw new AssertionError("Oversized handler was iterated"); }
            });
        } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Oversized handler was not rejected");
        for (BlockPos fixture : java.util.List.of(link.getBlockPos(), barrelPos, replacement.getBlockPos())) level.destroyBlock(fixture, false);
        LogUtils.getLogger().info("STORAGE_ROBUSTNESS_CHECKS_OK comparator_0_mid_15=true immediate_rebind_cleanup=true network_removal=true reachability_denied=true giant_handler_no_iteration=true");
    }
    private static int signal(StorageBlockEntity controller) { return controller.getBlockState().getAnalogOutputSignal(controller.getLevel(), controller.getBlockPos()); }
    private static StorageBlockEntity place(ServerPlayer player, BlockPos pos, Block block) {
        player.serverLevel().setBlockAndUpdate(pos, block.defaultBlockState().setValue(StorageBlock.TARGET, Direction.DOWN));
        StorageBlockEntity entity = (StorageBlockEntity) player.serverLevel().getBlockEntity(pos);
        entity.setOwner(player.getUUID());
        return entity;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private RobustnessChecks() { }
}
