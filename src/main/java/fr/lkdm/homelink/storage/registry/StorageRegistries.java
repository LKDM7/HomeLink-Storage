package fr.lkdm.homelink.storage.registry;

import fr.lkdm.homelink.storage.HomeLinkStorage;
import fr.lkdm.homelink.storage.block.StorageBlock;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.menu.StorageMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class StorageRegistries {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(HomeLinkStorage.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(HomeLinkStorage.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, HomeLinkStorage.MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, HomeLinkStorage.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, HomeLinkStorage.MOD_ID);
    public static final DeferredBlock<fr.lkdm.homelink.storage.block.DepositBlock> DEPOSIT = BLOCKS.register("storage_deposit", () ->
            new fr.lkdm.homelink.storage.block.DepositBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0F, 6.0F).requiresCorrectToolForDrops()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<fr.lkdm.homelink.storage.blockentity.DepositBlockEntity>> DEPOSIT_ENTITY = ENTITIES.register("storage_deposit", () ->
            BlockEntityType.Builder.of(fr.lkdm.homelink.storage.blockentity.DepositBlockEntity::new, DEPOSIT.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<fr.lkdm.homelink.storage.menu.DepositMenu>> DEPOSIT_MENU = MENUS.register("storage_deposit", () -> IMenuTypeExtension.create(fr.lkdm.homelink.storage.menu.DepositMenu::new));
    public static final DeferredBlock<StorageBlock> CONTROLLER = block("storage_controller");
    public static final DeferredBlock<StorageBlock> TERMINAL = block("storage_terminal");
    public static final DeferredBlock<StorageBlock> LINK = block("storage_link");
    public static final DeferredBlock<StorageBlock> REPEATER = block("storage_repeater");
    public static final net.neoforged.neoforge.registries.DeferredItem<fr.lkdm.homelink.storage.item.LinkKeyItem> LINK_KEY =
            ITEMS.registerItem("link_key", fr.lkdm.homelink.storage.item.LinkKeyItem::new);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StorageBlockEntity>> STORAGE_ENTITY = ENTITIES.register("storage", () ->
            BlockEntityType.Builder.of(StorageBlockEntity::new, CONTROLLER.get(), TERMINAL.get(), LINK.get(), REPEATER.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<StorageMenu>> STORAGE_MENU = MENUS.register("storage", () -> IMenuTypeExtension.create(StorageMenu::new));

    static {
        ITEMS.registerSimpleBlockItem(DEPOSIT);
        ITEMS.registerSimpleBlockItem(CONTROLLER);
        ITEMS.registerSimpleBlockItem(TERMINAL);
        ITEMS.registerSimpleBlockItem(LINK);
        ITEMS.registerSimpleBlockItem(REPEATER);
        TABS.register("storage", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.homelink_storage"))
                .icon(() -> CONTROLLER.get().asItem().getDefaultInstance())
                .displayItems((params, output) -> { output.accept(CONTROLLER); output.accept(TERMINAL); output.accept(LINK); output.accept(REPEATER); output.accept(LINK_KEY); output.accept(DEPOSIT); }).build());
    }

    private static DeferredBlock<StorageBlock> block(String name) {
        return BLOCKS.register(name, () -> new StorageBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0F, 6.0F)
                .lightLevel(state -> state.getValue(StorageBlock.LIT) ? 7 : 0).requiresCorrectToolForDrops()));
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); MENUS.register(bus); TABS.register(bus);
    }
    private StorageRegistries() {}
}
