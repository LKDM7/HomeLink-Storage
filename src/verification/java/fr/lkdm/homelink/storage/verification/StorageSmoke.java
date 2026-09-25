package fr.lkdm.homelink.storage.verification;

import com.mojang.logging.LogUtils;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.client.screen.StorageScreen;
import fr.lkdm.homelink.storage.menu.StorageMenu;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Exercises placement, persistence, real menu transport and client rendering in a disposable world. */
@EventBusSubscriber(modid = StorageValidation.MOD_ID, value = Dist.CLIENT)
public final class StorageSmoke {
    private static final BlockPos TERMINAL = new BlockPos(2, 5, 0);
    private static int phase;
    private static long deadline;
    private static int visibleTicks;
    private static long withdrawRequestedAt;
    private static volatile boolean serverDone;
    private static volatile Throwable serverFailure;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("storage.smoke") || phase == 99) return;
        Minecraft client = Minecraft.getInstance();
        try {
            if (phase == 0) {
                if (client.screen instanceof AccessibilityOnboardingScreen screen) { screen.onClose(); return; }
                if (!(client.screen instanceof TitleScreen)) return;
                phase = 1;
                deadline = System.nanoTime() + 240_000_000_000L;
                GameRules rules = new GameRules();
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                rules.getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, null);
                client.createWorldOpenFlows().createFreshLevel("storage-validation-" + System.currentTimeMillis(),
                        new LevelSettings("Storage Validation", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                                true, rules, WorldDataConfiguration.DEFAULT),
                        new WorldOptions(731L, false, false),
                        access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                                .value().createWorldDimensions(), new TitleScreen());
                return;
            }
            check(System.nanoTime() < deadline, "Timed out at phase " + phase);
            if (serverFailure != null) throw new IllegalStateException("Server verification failed", serverFailure);
            if (client.player == null || client.getSingleplayerServer() == null) return;
            switch (phase) {
                case 1 -> {
                    phase = 2;
                    onServer(client, StorageSmoke::placeAndOpen);
                }
                case 2 -> {
                    if (!serverDone || !(client.screen instanceof StorageScreen)) return;
                    check(client.player.containerMenu instanceof StorageMenu menu && menu.position().equals(TERMINAL), "Wrong client menu or position");
                    StorageMenu menu = (StorageMenu) client.player.containerMenu;
                    if (!TerminalChecks.snapshotReady(menu)) return;
                    if (!HomeCoreChecks.clientReady()) return;
                    TerminalChecks.verifySnapshot(menu, (StorageScreen) client.screen);
                    for (String key : new String[]{"screen.homelink_storage.search", "screen.homelink_storage.all_zones", "screen.homelink_storage.close"}) {
                        check(!net.minecraft.network.chat.Component.translatable(key).getString().equals(key), "Missing client translation: " + key);
                    }
                    for (Block block : blocks()) {
                        for (var state : block.getStateDefinition().getPossibleStates())
                            check(client.getBlockRenderer().getBlockModel(state) != client.getModelManager().getMissingModel(), "Missing oriented model " + state);
                        check(client.getBlockRenderer().getBlockModel(block.defaultBlockState()) != client.getModelManager().getMissingModel(), "Missing block model " + block);
                        check(client.getItemRenderer().getModel(block.asItem().getDefaultInstance(), client.level, client.player, 0) != client.getModelManager().getMissingModel(), "Missing item model " + block);
                    }
                    StorageScreen screen = (StorageScreen) client.screen;
                    screen.toggleManual();
                    visibleTicks = 0;
                    phase = 20;
                }
                case 20 -> {
                    if (!(client.screen instanceof StorageScreen screen) || !screen.manualOpen() || screen.manual() == null) return;
                    if (++visibleTicks < 20) return;
                    check(screen.manual().chapter() == 0, "Manual did not open on its first chapter");
                    check(!Component.translatable("manual.homelink_storage.start.body").getString().equals("manual.homelink_storage.start.body"), "French manual body is missing");
                    net.minecraft.client.Screenshot.grab(client.gameDirectory, "storage-manual.png", client.getMainRenderTarget(),
                            message -> LogUtils.getLogger().info("STORAGE_MANUAL_SCREENSHOT {}", message.getString()));
                    screen.manual().keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT);
                    visibleTicks = 0;
                    phase = 21;
                }
                case 21 -> {
                    if (!(client.screen instanceof StorageScreen screen) || screen.manual() == null || screen.manual().chapter() != 1) return;
                    if (++visibleTicks < 5) return;
                    screen.toggleManual();
                    check(!screen.manualOpen(), "Manual did not return to storage view");
                    LogUtils.getLogger().info("STORAGE_MANUAL_CHECKS_OK open=true chapters=5 navigation=true french=true");
                    phase = 6;
                    onServer(client, TerminalChecks::mutate);
                }
                case 6 -> {
                    if (!serverDone || !(client.screen instanceof StorageScreen screen)) return;
                    StorageMenu menu = (StorageMenu) client.player.containerMenu;
                    if (!TerminalChecks.deltaReady(menu)) return;
                    TerminalChecks.verifyDelta(menu, screen);
                    check(!menu.managesNetwork(), "Terminal opened in management mode");
                    phase = 90;
                    onServer(client, StorageSmoke::openController);
                }
                case 90 -> {
                    // Zones and names are managed from the Controller's own screen.
                    if (!serverDone || !(client.screen instanceof StorageScreen)
                            || !(client.player.containerMenu instanceof StorageMenu menu) || !menu.managesNetwork()) return;
                    if (!TerminalChecks.managementComplete(menu)) return;
                    check(menu.clientRows().isEmpty() && menu.clientStats().items() > 0, "Controller screen received item rows");
                    visibleTicks = 0;
                    phase = 91;
                }
                case 91 -> {
                    if (++visibleTicks < 20) return;
                    net.minecraft.client.Screenshot.grab(client.gameDirectory, "storage-controller.png", client.getMainRenderTarget(), message -> LogUtils.getLogger().info("STORAGE_CONTROLLER_SCREENSHOT {}", message.getString()));
                    LogUtils.getLogger().info("STORAGE_CONTROLLER_SCREEN_CHECKS_OK management=true item_rows=0");
                    phase = 92;
                    onServer(client, StorageSmoke::openTerminal);
                }
                case 92 -> {
                    if (!serverDone || !(client.screen instanceof StorageScreen)
                            || !(client.player.containerMenu instanceof StorageMenu menu) || menu.managesNetwork()
                            || menu.clientRows().isEmpty() || menu.clientStats().items() != 161) return;
                    if (++visibleTicks < 25) return;
                    WithdrawalChecks.request((StorageScreen) client.screen);
                    withdrawRequestedAt = client.level.getGameTime();
                    phase = 22;
                }
                case 22 -> {
                    if (!WithdrawalChecks.received((StorageMenu) client.player.containerMenu)) return;
                    long refreshTicks = client.level.getGameTime() - withdrawRequestedAt;
                    LogUtils.getLogger().info("STORAGE_WITHDRAW_REFRESH_TICKS {}", refreshTicks);
                    check(refreshTicks <= 4, "Terminal count refreshed too slowly after a withdrawal: " + refreshTicks + " ticks");
                    phase = 23;
                    onServer(client, WithdrawalChecks::verify);
                }
                case 23 -> {
                    if (!serverDone) return;
                    RecipeViewerChecks.run((StorageScreen) client.screen);
                    phase = 7;
                }
                case 7 -> {
                    if (++visibleTicks < 30) return;
                    net.minecraft.client.Screenshot.grab(client.gameDirectory, "storage-terminal.png", client.getMainRenderTarget(), message -> LogUtils.getLogger().info("STORAGE_SCREENSHOT {}", message.getString()));
                    LocateChecks.requestValid((StorageMenu) client.player.containerMenu);
                    phase = 8;
                }
                case 8 -> {
                    if (!LocateChecks.received()) return;
                    client.player.closeContainer();
                    client.player.setYRot(90);
                    client.player.setXRot(20);
                    visibleTicks = 0;
                    phase = 9;
                }
                case 9 -> {
                    if (++visibleTicks < 10) return;
                    net.minecraft.client.Screenshot.grab(client.gameDirectory, "storage-locate.png", client.getMainRenderTarget(), message -> LogUtils.getLogger().info("STORAGE_LOCATE_SCREENSHOT {}", message.getString()));
                    phase = 93;
                    // A real right-click on the fixture Link sends its coverage zone to this client.
                    onServer(client, player -> player.gameMode.useItemOn(player, player.serverLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                            new BlockHitResult(new BlockPos(7, 5, 7).getCenter(), Direction.UP, new BlockPos(7, 5, 7), false)));
                }
                case 93 -> {
                    if (!serverDone) return;
                    var view = fr.lkdm.homelink.storage.network.CoverageState.current();
                    if (view == null) return;
                    check(client.player.containerMenu == client.player.inventoryMenu, "Link right-click opened a menu");
                    var focus = view.chunks().stream().filter(fr.lkdm.homelink.storage.network.CoverageState.Chunk::focus).toList();
                    check(focus.size() == 1 && focus.get(0).x() == 0 && focus.get(0).z() == 0 && focus.get(0).active()
                            && focus.get(0).node().equals(new BlockPos(7, 5, 7)), "Link coverage zone not received: " + view.chunks());
                    visibleTicks = 0;
                    phase = 95;
                    // Stand outside the covered chunk, facing its southern border, for a representative capture.
                    onServer(client, player -> player.connection.teleport(8.5, 6, 23.5, 180, 25));
                }
                case 95 -> {
                    if (!serverDone || client.player.getZ() < 20) return;
                    phase = 94;
                }
                case 94 -> {
                    if (++visibleTicks < 10) return;
                    net.minecraft.client.Screenshot.grab(client.gameDirectory, "storage-coverage.png", client.getMainRenderTarget(), message -> LogUtils.getLogger().info("STORAGE_COVERAGE_SCREENSHOT {}", message.getString()));
                    LogUtils.getLogger().info("STORAGE_COVERAGE_ZONE_CHECKS_OK real_click=true packet=true chunk=0,0 active=true menu=false");
                    onServer(client, player -> player.connection.teleport(2.5, 6, 3.5, 90, 20));
                    phase = 96;
                }
                case 96 -> {
                    if (!serverDone || client.player.getZ() > 5) return;
                    // A real client right-click on the same Link hides its zone at once.
                    BlockPos link = new BlockPos(7, 5, 7);
                    check(fr.lkdm.homelink.storage.network.CoverageState.showing(link), "Coverage zone expired before the toggle check");
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(link.getCenter(), Direction.UP, link, false));
                    check(!fr.lkdm.homelink.storage.network.CoverageState.showing(link), "Second right-click did not hide the zone immediately");
                    visibleTicks = 0;
                    phase = 97;
                }
                case 97 -> {
                    // The server still answers the click: that answer must not show the zone again.
                    if (++visibleTicks < 20) return;
                    check(fr.lkdm.homelink.storage.network.CoverageState.current() == null, "Server answer showed the hidden zone again");
                    check(client.player.containerMenu == client.player.inventoryMenu, "Link toggle opened a menu");
                    LogUtils.getLogger().info("STORAGE_COVERAGE_TOGGLE_CHECKS_OK instant_hide=true server_answer_ignored=true");
                    phase = 10;
                }
                case 10 -> {
                    if (!LocateChecks.expired()) return;
                    phase = 11;
                    onServer(client, StorageSmoke::openTerminal);
                }
                case 11 -> {
                    if (!serverDone || !(client.screen instanceof StorageScreen)) return;
                    LocateChecks.requestUnknown((StorageMenu) client.player.containerMenu);
                    visibleTicks = 0;
                    phase = 12;
                }
                case 12 -> {
                    if (++visibleTicks < 12) return;
                    LocateChecks.verifyNoTarget();
                    phase = 13;
                    onServer(client, LocateChecks::removeChest);
                }
                case 13 -> {
                    if (!serverDone) return;
                    LocateChecks.requestMissing((StorageMenu) client.player.containerMenu);
                    visibleTicks = 0;
                    phase = 14;
                }
                case 14 -> {
                    if (++visibleTicks < 12) return;
                    LocateChecks.verifyNoTarget();
                    phase = 15;
                    onServer(client, LocateChecks::addUnloadedFixture);
                }
                case 15 -> {
                    if (!serverDone) return;
                    LocateChecks.requestUnloaded((StorageMenu) client.player.containerMenu);
                    visibleTicks = 0;
                    phase = 16;
                }
                case 16 -> {
                    if (++visibleTicks < 12) return;
                    LocateChecks.verifyNoTarget();
                    phase = 17;
                    onServer(client, LocateChecks::verifyNoChunkLoad);
                }
                case 17 -> {
                    if (!serverDone) return;
                    LogUtils.getLogger().info("STORAGE_LOCATE_CHECKS_OK request_response=true expires=true unknown_rejected=true missing_rejected=true unloaded_rejected=true no_chunk_force=true");
                    phase = 3;
                    onServer(client, player -> player.teleportTo(40.5, 6, 0.5));
                }
                case 3 -> {
                    if (!serverDone || client.player.containerMenu instanceof StorageMenu) return;
                    check(!(client.screen instanceof StorageScreen), "Screen remained open after moving away");
                    phase = 4;
                    onServer(client, player -> { player.teleportTo(2.5, 6, 3.5); openTerminal(player); });
                }
                case 4 -> {
                    if (!serverDone || !(client.screen instanceof StorageScreen)) return;
                    phase = 5;
                    onServer(client, player -> player.serverLevel().destroyBlock(TERMINAL, false));
                }
                case 5 -> {
                    if (!serverDone || client.player.containerMenu instanceof StorageMenu) return;
                    check(!(client.screen instanceof StorageScreen), "Screen remained open after removal");
                    LogUtils.getLogger().info("STORAGE_SMOKE_OK block_item_placement=4 uuid_nbt_roundtrip=4 client_models=8 terminal_screen=true distance_close=true removal_close=true");
                    phase = 40;
                    onServer(client, DepositClientChecks::prepare);
                }
                case 40 -> {
                    if (!serverDone || !DepositClientChecks.ready(client)) return;
                    DepositClientChecks.click(client);
                    client.getToasts().clear();
                    visibleTicks = 0;
                    phase = 41;
                }
                case 41 -> {
                    if (!DepositClientChecks.moved(client) || ++visibleTicks < 20) return;
                    net.minecraft.client.Screenshot.grab(client.gameDirectory, "storage-deposit.png", client.getMainRenderTarget(),
                            message -> LogUtils.getLogger().info("STORAGE_DEPOSIT_SCREENSHOT {}", message.getString()));
                    phase = 42;
                    onServer(client, DepositClientChecks::verifyAndRemove);
                }
                case 42 -> {
                    if (!serverDone || client.player.containerMenu instanceof fr.lkdm.homelink.storage.menu.DepositMenu) return;
                    check(!(client.screen instanceof fr.lkdm.homelink.storage.client.screen.DepositScreen), "Deposit screen remained open after removal");
                    LogUtils.getLogger().info("STORAGE_DEPOSIT_CLIENT_CHECKS_OK menu_transport=true slots=27 status=true models=true translations=true shift_click_packet=true server_inventory=true removal_close=true");
                    phase = 43;
                    onServer(client, DepositClientChecks::prepareModelPreview);
                }
                case 43 -> {
                    if (!serverDone) return;
                    client.options.hideGui = true;
                    client.getToasts().clear();
                    visibleTicks = 0;
                    phase = 44;
                }
                case 44 -> {
                    if (++visibleTicks < 40) return;
                    net.minecraft.client.Screenshot.grab(client.gameDirectory, "storage-deposit-model.png", client.getMainRenderTarget(),
                            message -> LogUtils.getLogger().info("STORAGE_DEPOSIT_MODEL_SCREENSHOT {}", message.getString()));
                    client.options.hideGui = false;
                    phase = 45;
                    visibleTicks = 0;
                    DepositClientChecks.showKeyPreview(client);
                }
                case 45 -> {
                    if (++visibleTicks < 20) return;
                    net.minecraft.client.Screenshot.grab(client.gameDirectory, "storage-link-key.png", client.getMainRenderTarget(),
                            message -> LogUtils.getLogger().info("STORAGE_LINK_KEY_MODEL_SCREENSHOT {}", message.getString()));
                    client.setScreen(null);
                    if (Boolean.getBoolean("storage.dashboardSmoke")) {
                        phase = 30;
                        onServer(client, player -> {
                            try { Class.forName("fr.lkdm.homelink.storage.verification.DashboardChecks").getMethod("prepare", ServerPlayer.class).invoke(null, player); }
                            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                        });
                    } else finish(client);
                }
                case 30 -> {
                    if (serverDone && Boolean.TRUE.equals(Class.forName("fr.lkdm.homelink.storage.verification.DashboardChecks")
                            .getMethod("tick", Minecraft.class).invoke(null, client))) finish(client);
                }
                default -> throw new IllegalStateException("Unexpected verification phase");
            }
        } catch (Throwable failure) {
            LogUtils.getLogger().error("STORAGE_SMOKE_FAILED phase=" + phase, failure);
            finish(client);
        }
    }

    private static List<Block> blocks() {
        return List.of(StorageRegistries.CONTROLLER.get(), StorageRegistries.TERMINAL.get(), StorageRegistries.LINK.get(), StorageRegistries.REPEATER.get());
    }

    private static void placeAndOpen(ServerPlayer player) {
        var level = player.serverLevel();
        player.teleportTo(2.5, 6, 3.5);
        for (int x = -2; x <= 7; x++) for (int z = -2; z <= 5; z++) {
            level.setBlockAndUpdate(new BlockPos(x, 4, z), Blocks.STONE.defaultBlockState());
        }
        for (int index = 0; index < blocks().size(); index++) {
            Block block = blocks().get(index);
            BlockPos position = new BlockPos(index * 2, 5, 0);
            level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
            ItemStack stack = block.asItem().getDefaultInstance();
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            BlockHitResult hit = new BlockHitResult(new Vec3(position.getX() + 0.5, position.getY(), position.getZ() + 0.5), Direction.UP, position.below(), false);
            var result = ((BlockItem) stack.getItem()).place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit));
            check(result.consumesAction() && level.getBlockState(position).is(block), "BlockItem placement failed: " + block);
            check(level.getBlockEntity(position) instanceof StorageBlockEntity, "Block entity missing");
            StorageBlockEntity entity = (StorageBlockEntity) level.getBlockEntity(position);
            UUID identity = entity.id();
            var saved = entity.saveWithFullMetadata(level.registryAccess());
            var restored = new StorageBlockEntity(position, level.getBlockState(position));
            restored.loadWithComponents(saved, level.registryAccess());
            check(identity.equals(restored.id()), "Persistent UUID lost during NBT reload");
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        InventoryChecks.run(player);
        ControllerChecks.run(player);
        TerminalMountChecks.run(player);
        LinkKeyChecks.run(player);
        DoubleChestChecks.run(player);
        WithdrawalChecks.run(player);
        IndexChecks.run(player);
        HomeCoreChecks.run(player);
        RobustnessChecks.run(player);
        RecipeChecks.run(player);
        PacketChecks.run(player);
        TerminalChecks.prepare(player, TERMINAL);
        openTerminal(player);
    }

    private static void openController(ServerPlayer player) {
        BlockPos controller = new BlockPos(0, 5, 0);
        player.gameMode.useItemOn(player, player.serverLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(controller.getCenter(), Direction.SOUTH, controller, false));
        check(player.containerMenu instanceof StorageMenu menu && menu.managesNetwork(), "Controller interaction did not open its management menu");
    }

    private static void openTerminal(ServerPlayer player) {
        player.gameMode.useItemOn(player, player.serverLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(TERMINAL.getCenter(), Direction.SOUTH, TERMINAL, false));
        check(player.containerMenu instanceof StorageMenu, "Terminal interaction did not open server menu");
    }

    private static void onServer(Minecraft client, Consumer<ServerPlayer> task) {
        serverDone = false;
        UUID identity = client.player.getUUID();
        var server = client.getSingleplayerServer();
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(identity);
                check(player != null, "Connected server player missing");
                task.accept(player);
                serverDone = true;
            } catch (Throwable failure) { serverFailure = failure; }
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void finish(Minecraft client) {
        phase = 99;
        if (client.level != null) client.level.disconnect();
        client.disconnect();
        LogUtils.getLogger().info("STORAGE_SMOKE_SHUTDOWN_OK");
        client.stop();
    }

    private StorageSmoke() { }
}
