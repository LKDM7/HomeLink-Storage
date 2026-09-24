package fr.lkdm.homelink.storage.verification;

import com.mojang.logging.LogUtils;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.config.StorageConfig;
import fr.lkdm.homelink.storage.menu.StorageMenu;
import fr.lkdm.homelink.storage.network.StoragePackets;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import fr.lkdm.homelink.storage.storage.network.InventoryConnection;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Exercises the production packet builder, wire codec and client cache inside Minecraft. */
public final class PacketChecks {
    public static void run(ServerPlayer player) {
        var level = player.serverLevel();
        BlockPos position = player.blockPosition().above(8);
        check(level.hasChunkAt(position) && level.isEmptyBlock(position), "Packet fixture requires loaded empty space");
        level.setBlockAndUpdate(position, StorageRegistries.CONTROLLER.get().defaultBlockState());
        int originalBudget = StorageConfig.MAX_PACKET_BYTES.get();
        try {
            StorageConfig.MAX_PACKET_BYTES.set(32768);
            var controller = (StorageBlockEntity) level.getBlockEntity(position);
            check(controller != null, "Packet fixture controller missing");
            controller.setOwner(player.getUUID());
            controller.ensureHomeCore();
            controller.setLogicalName("界".repeat(64));
            while (controller.zones().size() < 64) controller.createZone("界".repeat(64));
            var field = StorageBlockEntity.class.getDeclaredField("connections");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, InventoryConnection> connections = (Map<UUID, InventoryConnection>) field.get(controller);
            for (int i = 0; i < 512; i++) {
                UUID id = UUID.randomUUID();
                var connection = new InventoryConnection(id, new BlockPos(i, 20, 0));
                connection.name = "界".repeat(64);
                connection.zone = controller.zones().keySet().iterator().next();
                connections.put(id, connection);
                ItemStackHandler handler = new ItemStackHandler(1);
                handler.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 1_000_000_000));
                controller.index().update(new BlockPos(i, 20, 0), handler);
            }
            for (int i = 0; i < 128; i++) {
                var handler = new ItemStackHandler(1);
                ItemStack stack = new ItemStack(Items.DIAMOND, 64);
                stack.set(DataComponents.CUSTOM_NAME, Component.literal("Variant " + i));
                CompoundTag complex = new CompoundTag(); complex.putString("fixture", "x".repeat(20_000));
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(complex));
                handler.setStackInSlot(0, stack);
                controller.index().update(new BlockPos(i, 21, 0), handler);
            }
            StorageMenu sender = new StorageMenu(93, player.getInventory(), position);
            StorageMenu receiver = new StorageMenu(93, player.getInventory(), position);
            Method prepare = StorageMenu.class.getDeclaredMethod("prepare", StorageBlockEntity.class);
            Method pending = StorageMenu.class.getDeclaredMethod("hasPending");
            Method next = StorageMenu.class.getDeclaredMethod("nextPacket");
            prepare.setAccessible(true); pending.setAccessible(true); next.setAccessible(true);
            prepare.invoke(sender, controller);
            int fragments = 0, maxBytes = 0;
            while ((Boolean) pending.invoke(sender)) {
                check(++fragments < 2048, "Packet queue failed to make progress");
                CompoundTag fragment = (CompoundTag) next.invoke(sender);
                check(fragment.getList("Rows", 10).size() <= StorageConfig.NETWORK_ROWS.get(), "Row fragment exceeds configured count");
                var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
                try {
                    StoragePackets.Data.CODEC.encode(wire, new StoragePackets.Data(93, fragment));
                    maxBytes = Math.max(maxBytes, wire.readableBytes());
                    check(wire.readableBytes() <= StorageConfig.MAX_PACKET_BYTES.get(), "Encoded data exceeds byte budget");
                    var decoded = StoragePackets.Data.CODEC.decode(wire);
                    check(decoded.menuId() == 93 && !wire.isReadable(), "Data codec did not consume exact payload");
                    receiver.applyData(decoded.data(), player.registryAccess());
                } finally { wire.release(); }
            }
            check(fragments > 2, "Large snapshot was not fragmented");
            check(receiver.clientRows().size() == 129, "Large-component variants lost their distinct identities");
            check(receiver.clientLocations().size() == 512 && receiver.clientZones().size() == 64,
                    "Fragmented metadata lost inventory locations or zones");
            var iron = receiver.clientRows().stream().filter(row -> row.stack().is(Items.IRON_INGOT)).findFirst().orElseThrow();
            check(iron.count() == 512_000_000_000L && iron.locations().size() == 512,
                    "Long quantities or 512-location variant did not survive wire codec");
            for (var row : receiver.clientRows()) if (row.stack().is(Items.DIAMOND)) {
                check(row.count() == 64 && row.locations().size() == 1, "Complex variant lost counts/locations");
                check(!row.stack().has(DataComponents.CUSTOM_DATA), "Oversized components leaked into client snapshot");
                var name = row.stack().getHoverName();
                check(name.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents,
                        "Complex variant warning is not a localized component: " + name);
                var warning = (net.minecraft.network.chat.contents.TranslatableContents) name.getContents();
                check(warning.getKey().equals("screen.homelink_storage.complex_variant")
                                && warning.getArgs().length == 1
                                && String.valueOf(warning.getArgs()[0]).equals(Long.toString(row.id())),
                        "Complex variant simplification lacks correct warning key or variant identity: " + name);
            }
            check(receiver.clientStats().items() == 512_000_008_192L, "Snapshot header truncated long total");
            var commandWire = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
            try {
                var command = new StoragePackets.Command(93, "rename_inventory", UUID.randomUUID().toString(), "界".repeat(64));
                StoragePackets.Command.CODEC.encode(commandWire, command);
                check(command.equals(StoragePackets.Command.CODEC.decode(commandWire)) && !commandWire.isReadable(), "Command codec roundtrip failed");
            } finally { commandWire.release(); }
            LogUtils.getLogger().info("STORAGE_PACKET_CHECKS_OK fragments={} max_bytes={} variants=129 locations=512 large_components=true long_counts=true wire_roundtrip=true", fragments, maxBytes);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Packet reflection harness failed", failure); }
        finally {
            StorageConfig.MAX_PACKET_BYTES.set(originalBudget);
            level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private PacketChecks() {}
}
