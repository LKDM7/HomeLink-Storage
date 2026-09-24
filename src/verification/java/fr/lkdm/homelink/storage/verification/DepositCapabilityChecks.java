package fr.lkdm.homelink.storage.verification;

import fr.lkdm.homelink.storage.blockentity.DepositBlockEntity;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import fr.lkdm.homelink.storage.storage.inventory.StorageInsertion;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Verification-only capability providers, never packaged in the released mod. */
@GameTestHolder(StorageValidation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DepositCapabilityChecks {
    static final Map<BlockPos, IItemHandler> HANDLERS = new HashMap<>();

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void insertionRevalidatesCapabilityCallbacks(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos base = new BlockPos(640, 80, 640);
        BlockPos sink = base.east(2);
        BlockPos source = base.south(2);
        level.getChunkAt(base);
        try {
            level.setBlockAndUpdate(base, StorageRegistries.CONTROLLER.get().defaultBlockState());
            var controller = (StorageBlockEntity) level.getBlockEntity(base);
            controller.setOwner(UUID.fromString("1c131578-8711-4a43-bc6e-a9bb8e354961"));
            level.setBlockAndUpdate(base.above(), StorageRegistries.LINK.get().defaultBlockState());
            ((StorageBlockEntity) level.getBlockEntity(base.above())).bind(controller);
            level.setBlockAndUpdate(source, StorageRegistries.DEPOSIT.get().defaultBlockState());
            var deposit = (DepositBlockEntity) level.getBlockEntity(source);
            deposit.bind(controller);
            for (int mode = 0; mode < 5; mode++) {
                final int behavior = mode;
                deposit.inventory().setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 32));
                level.setBlockAndUpdate(sink.below(), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(sink, Blocks.OAK_SIGN.defaultBlockState());
                ItemStackHandler handler = new ItemStackHandler(1) {
                    boolean callback;
                    @Override public ItemStack insertItem(int slot, ItemStack offered, boolean simulate) {
                        if (simulate) {
                            if (!callback) {
                                callback = true;
                                if (behavior == 2) level.removeBlock(sink, false);
                                if (behavior == 3) deposit.inventory().setStackInSlot(0, new ItemStack(Items.DIAMOND, 9));
                                if (behavior == 4) helper.assertTrue(StorageInsertion.deposit(controller, deposit, 0) == 0,
                                        "Recursive callback bypassed source transfer guard");
                            }
                            return ItemStack.EMPTY;
                        }
                        if (behavior == 1) return offered.copy(); // Late refusal after successful simulation.
                        int amount = Math.min(7, offered.getCount());
                        super.insertItem(slot, offered.copyWithCount(amount), false);
                        return offered.copyWithCount(offered.getCount() - amount);
                    }
                };
                handler.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 1));
                HANDLERS.put(sink, handler);
                level.invalidateCapabilities(sink);
                controller.refreshIndex();
                int inserted = StorageInsertion.deposit(controller, deposit, 0);
                int remaining = deposit.inventory().getStackInSlot(0).getCount();
                if (behavior == 0 || behavior == 4) {
                    helper.assertTrue(inserted == 7 && remaining == 25 && handler.getStackInSlot(0).getCount() == 8,
                            "Actual insertion remainder did not determine exact source debit (mode " + behavior + ")");
                } else if (behavior == 3) {
                    helper.assertTrue(inserted == 0 && remaining == 9 && deposit.inventory().getStackInSlot(0).is(Items.DIAMOND)
                            && handler.getStackInSlot(0).getCount() == 1, "Source mutation during simulation was not revalidated");
                } else helper.assertTrue(inserted == 0 && remaining == 32 && handler.getStackInSlot(0).getCount() == 1,
                        "Removed destination or late refusal lost items (mode " + behavior + ")");
                HANDLERS.remove(sink);
                level.invalidateCapabilities(sink);
            }
            helper.succeed();
        } finally {
            HANDLERS.remove(sink); level.invalidateCapabilities(sink);
            for (BlockPos pos : new BlockPos[]{sink, sink.below(), source, base.above(), base}) level.removeBlock(pos, false);
        }
    }
}
