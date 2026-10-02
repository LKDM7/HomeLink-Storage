package fr.lkdm.homelink.storage.verification;

import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.config.StorageConfig;
import fr.lkdm.homelink.storage.logistics.filter.*;
import fr.lkdm.homelink.storage.logistics.network.*;
import fr.lkdm.homelink.storage.logistics.pipe.*;
import fr.lkdm.homelink.storage.logistics.transit.*;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real blocks/handlers/ledger/manager. Fixtures inject HE; EnergyGameTests tests energy production. */
@GameTestHolder(StorageValidation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PipeGameTests {
    private static int fixture;
    private static final UUID OWNER = UUID.fromString("63875b62-a8e8-42f8-8cef-cbe526c99c74");

    @GameTest(template="empty") public static void realJourneyHasDelayAndConservesComponents(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            ItemStack cargo = new ItemStack(Items.IRON_INGOT,16);
            cargo.set(DataComponents.CUSTOM_NAME,Component.literal("Physical cargo"));
            f.source.setItem(0,cargo.copy()); f.arm(); f.depart();
            h.assertTrue(f.source.countItem(Items.IRON_INGOT)==0 && f.target.isEmpty(),"Extraction must precede delivery");
            h.assertTrue(f.owned().size()==1,"One authoritative cargo");
            f.steps(23); h.assertTrue(f.target.isEmpty(),"Delivery before 3 x 8 ticks");
            f.steps(1); h.assertTrue(f.target.countItem(Items.IRON_INGOT)==16 && f.owned().isEmpty(),"Missing delivery");
            h.assertTrue(ItemStack.isSameItemSameComponents(cargo,f.target.getItem(0)),"Lost components");
            h.assertTrue(f.manager.telemetry(f.controller.id()).deliveredPerMinute()==16,"Delivery telemetry");
        }
        h.succeed();
    }
    @GameTest(template="empty") public static void unarmedAndBothFiltersPreventExtraction(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            f.source.setItem(0,new ItemStack(Items.IRON_INGOT,16)); f.depart();
            h.assertTrue(f.owned().isEmpty(),"Unconfigured face extracted");
            f.arm(); f.face(f.first,Direction.WEST).apply(FlowMode.EXTRACT,FilterMode.WHITELIST,Set.of(),OWNER,f.controller.id(),f.controller.networkId(),"minecraft:barrel");
            f.manager.facesChanged(f.first); f.manager.settleNow(); f.depart();
            h.assertTrue(f.owned().isEmpty(),"Empty source whitelist passed");
            f.arm(); f.face(f.last,Direction.EAST).apply(FlowMode.INSERT,FilterMode.WHITELIST,Set.of(),OWNER,f.controller.id(),f.controller.networkId(),"minecraft:barrel");
            f.manager.settleNow(); f.depart();
            h.assertTrue(f.source.countItem(Items.IRON_INGOT)==16 && f.owned().isEmpty(),"Destination filter bypassed");
        } h.succeed();
    }
    @GameTest(template="empty") public static void fullDestinationDoesNotChargeOrExtract(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            f.source.setItem(0,new ItemStack(Items.IRON_INGOT,16)); f.fillTarget(64); f.arm();
            long energy=f.controller.energyPort().stored(); f.depart();
            h.assertTrue(f.owned().isEmpty() && f.source.countItem(Items.IRON_INGOT)==16,"Extracted without room");
            h.assertTrue(f.controller.energyPort().stored()==energy,"Failed routing charged HE");
            h.assertTrue(f.manager.telemetry(f.controller.id()).deliveredPerMinute()==0,"Failed delivery counted");
        } h.succeed();
    }
    @GameTest(template="empty") public static void partialArrivalKeepsRemainder(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            f.source.setItem(0,new ItemStack(Items.IRON_INGOT,16)); f.arm(); f.depart();
            f.fillTarget(64); f.target.setItem(0,new ItemStack(Items.IRON_INGOT,60)); f.steps(24);
            h.assertTrue(f.owned().size()==1 && f.owned().getFirst().stack().getCount()==12,"Partial arrival lost remainder");
            h.assertTrue(f.target.countItem(Items.IRON_INGOT)==27*64,"Wrong actual insertion");
            h.assertTrue(f.manager.telemetry(f.controller.id()).deliveredPerMinute()==4,"Telemetry counts attempts");
        } h.succeed();
    }
    @GameTest(template="empty") public static void breakingCurrentPipeRecoversOnlyOnce(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            f.source.setItem(0,new ItemStack(Items.IRON_INGOT,16)); f.arm(); f.depart(); f.steps(9);
            var packet=f.owned().getFirst(); f.level.removeBlock(packet.position(),false);
            h.assertTrue(packet.state==TransitPacket.State.RECOVERY && packet.stack().getCount()==16,"Broken pipe lost cargo");
            f.manager.pipeRemoved(f.first.east());
            h.assertTrue(f.owned().size()==1,"Repeated break duplicated recovery");
        } h.succeed();
    }
    @GameTest(template="empty") public static void ledgerRoundtripDoesNotReextract(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            f.source.setItem(0,new ItemStack(Items.DIAMOND_SWORD)); f.arm(); f.depart(); f.steps(7);
            var saved=f.manager.ledger().save(new net.minecraft.nbt.CompoundTag(),f.level.registryAccess());
            var restored=TransitLedger.load(saved,f.level.registryAccess());
            var packet=f.owned().getFirst(); var read=restored.get(packet.id);
            h.assertTrue(read!=null && read.progress==7 && read.controller.equals(packet.controller),"Ledger lost state");
            h.assertTrue(ItemStack.isSameItemSameComponents(read.stack(),packet.stack()) && f.source.isEmpty(),"Ledger changed possession");
        } h.succeed();
    }
    @GameTest(template="empty") public static void twoControllersConflictAndControllerIsNotBridge(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            f.source.setItem(0,new ItemStack(Items.IRON_INGOT,16)); f.arm();
            BlockPos second=f.last.south(); f.level.setBlockAndUpdate(second,StorageRegistries.CONTROLLER.get().defaultBlockState());
            f.manager.pipeChanged(f.last); f.manager.settleNow(); f.depart();
            h.assertTrue(f.owned().isEmpty() && f.manager.component(f.first).status()==PipeStatus.CONTROLLER_CONFLICT,"Two controllers transferred");
            f.level.removeBlock(second,false);
            BlockPos isolated=f.controller.getBlockPos().south(); f.level.setBlockAndUpdate(isolated,StorageRegistries.PIPE.get().defaultBlockState());
            f.manager.settleNow();
            h.assertTrue(f.manager.component(isolated)!=f.manager.component(f.first),"Controller bridged separate circuits");
            f.level.removeBlock(isolated,false);
        } h.succeed();
    }
    @GameTest(template="empty") public static void pauseAndNetworkChangeHoldCargo(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            f.source.setItem(0,new ItemStack(Items.IRON_INGOT,16)); f.arm(); f.depart();
            var packet=f.owned().getFirst(); f.controller.setPipesPaused(true); f.steps(30);
            h.assertTrue(packet.progress==0 && f.target.isEmpty(),"Paused cargo moved");
            f.controller.setPipesPaused(false); f.steps(3); h.assertTrue(packet.progress==3,"Cargo failed to resume");
            packet.network=UUID.randomUUID(); f.steps(30);
            h.assertTrue(packet.progress==3 && f.target.isEmpty(),"Adopted cargo from another network");
        } h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=500) public static void unloadingCircuitKeepsSingleCargoOwner(GameTestHelper h) {
        var f=new Fixture(h);f.source.setItem(0,new ItemStack(Items.IRON_INGOT,16));f.arm();f.depart();
        var id=f.owned().getFirst().id;
        f.controller.setPipesPaused(true);
        h.runAfterDelay(320,()->{
            try {
                h.assertTrue(!f.level.isLoaded(f.base),"Fixture chunk failed to unload; cannot claim unload coverage");
                var packet=f.manager.ledger().get(id);
                h.assertTrue(packet!=null && packet.stack().getCount()==16,"Unload lost ledger owner");
                f.level.getChunkAt(f.base);f.manager.settleNow();
                var controller=(StorageBlockEntity)f.level.getBlockEntity(f.controller.getBlockPos());
                controller.setPipeCircuits(1);controller.energyPort().insert(1000,false);
                var source=(Container)f.level.getBlockEntity(f.base);
                h.assertTrue(source.isEmpty() && f.manager.ledger().owned(controller.id()).size()==1,"Reload re-extracted cargo");
                h.succeed();
            } finally {f.close();}
        });
    }
    @GameTest(template="empty") public static void configurationRevisionAndPermissionsAreServerOwned(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var pipe=(StoragePipeBlockEntity)f.level.getBlockEntity(f.first);
            var state=pipe.getBlockState().setValue(StoragePipeBlock.SIDES.get(Direction.WEST),PipeConnection.CONTAINER);
            f.level.setBlockAndUpdate(f.first,state);f.manager.settleNow();
            var player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(f.level,new com.mojang.authlib.GameProfile(OWNER,"PipeOwner"));
            player.setPos(f.first.getX()+0.5,f.first.getY(),f.first.getZ()+1.5);
            var request=new fr.lkdm.homelink.storage.logistics.sync.PipePayloads.ApplyFace(f.first,(byte)Direction.WEST.get3DDataValue(),0,
                    (byte)FlowMode.EXTRACT.ordinal(),(byte)FilterMode.WHITELIST.ordinal(),List.of("minecraft:iron_ingot","minecraft:diamond"));
            PipeInteraction.apply(player,request);
            h.assertTrue(pipe.config(Direction.WEST).armed() && pipe.config(Direction.WEST).revision()==1,"Owner edit rejected");
            PipeInteraction.apply(player,new fr.lkdm.homelink.storage.logistics.sync.PipePayloads.ApplyFace(f.first,(byte)Direction.WEST.get3DDataValue(),0,(byte)0,(byte)1,List.of()));
            h.assertTrue(pipe.config(Direction.WEST).revision()==1 && pipe.config(Direction.WEST).items().size()==2,"Stale revision overwrote newer edit");
            var stranger=net.neoforged.neoforge.common.util.FakePlayerFactory.get(f.level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"PipeStranger"));
            stranger.setPos(player.position());
            h.assertTrue(PipeAccess.check(stranger,pipe,fr.lkdm.homecore.api.security.Permission.CONFIGURE)==PipeAccess.Decision.DENIED,"Stranger could configure private circuit");
            player.setPos(f.first.getX()+30,f.first.getY(),f.first.getZ());
            h.assertTrue(!PipeAccess.near(player,f.first),"Distance validation absent");
        }h.succeed();
    }
    @GameTest(template="empty") public static void doubleChestSourceDestinationConflict(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var chest=Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING,Direction.NORTH);
            f.level.setBlockAndUpdate(f.base,chest.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.LEFT));
            f.level.setBlockAndUpdate(f.first,chest.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
            var left=f.base.south();var right=f.first.south();
            f.level.setBlockAndUpdate(left,StorageRegistries.PIPE.get().defaultBlockState());
            f.level.setBlockAndUpdate(right,StorageRegistries.PIPE.get().defaultBlockState());
            ((Container)f.level.getBlockEntity(f.base)).setItem(0,new ItemStack(Items.IRON_INGOT,16));
            f.face(left,Direction.NORTH).apply(FlowMode.EXTRACT,FilterMode.BLACKLIST,Set.of(),OWNER,f.controller.id(),f.controller.networkId(),"minecraft:chest");
            f.face(right,Direction.NORTH).apply(FlowMode.INSERT,FilterMode.BLACKLIST,Set.of(),OWNER,f.controller.id(),f.controller.networkId(),"minecraft:chest");
            f.manager.settleNow();
            var a=PipeEndpoint.resolve(f.level,left,Direction.NORTH);var b=PipeEndpoint.resolve(f.level,right,Direction.NORTH);
            h.assertTrue(a!=null && b!=null && a.endpoint().identity().equals(b.endpoint().identity()),"Double chest physical identity differs");
            f.depart();
            h.assertTrue(f.owned().isEmpty() && ((Container)f.level.getBlockEntity(f.base)).getItem(0).getCount()==16,"Double chest loop extracted cargo");
        }h.succeed();
    }
    @GameTest(template="empty") public static void facesOnOnePipeStayIndependent(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            f.level.removeBlock(f.first.east(),false);f.level.removeBlock(f.last,false);
            f.level.setBlockAndUpdate(f.first.east(),Blocks.BARREL.defaultBlockState());
            var receiver=(Container)f.level.getBlockEntity(f.first.east());
            f.level.setBlockAndUpdate(f.first.south(),StorageRegistries.CONTROLLER.get().defaultBlockState());
            var controller=(StorageBlockEntity)f.level.getBlockEntity(f.first.south());controller.setOwner(OWNER);controller.setPipeCircuits(1);controller.energyPort().insert(1000,false);
            f.face(f.first,Direction.WEST).apply(FlowMode.EXTRACT,FilterMode.BLACKLIST,Set.of(),OWNER,controller.id(),null,"minecraft:barrel");
            f.face(f.first,Direction.EAST).apply(FlowMode.INSERT,FilterMode.WHITELIST,Set.of(net.minecraft.resources.ResourceLocation.withDefaultNamespace("iron_ingot")),OWNER,controller.id(),null,"minecraft:barrel");
            f.source.setItem(0,new ItemStack(Items.IRON_INGOT,16));f.manager.pipeChanged(f.first);f.manager.settleNow();
            f.manager.dispatchSoon(controller.id());f.manager.tick();f.steps(8);
            h.assertTrue(receiver.countItem(Items.IRON_INGOT)==16,"Single pipe did not keep independent faces");
            h.assertTrue(f.face(f.first,Direction.WEST).mode()==FlowMode.EXTRACT && f.face(f.first,Direction.WEST).items().isEmpty(),"East edit overwrote west");
        }h.succeed();
    }
    @GameTest(template="empty") public static void controllerBudgetSharedAcrossComponents(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            BlockPos other=f.base.east(2).south(2);
            f.level.setBlockAndUpdate(other.west(),Blocks.BARREL.defaultBlockState());f.level.setBlockAndUpdate(other.east(),Blocks.BARREL.defaultBlockState());
            f.level.setBlockAndUpdate(other,StorageRegistries.PIPE.get().defaultBlockState());
            f.face(other,Direction.WEST).apply(FlowMode.EXTRACT,FilterMode.BLACKLIST,Set.of(),OWNER,f.controller.id(),null,"minecraft:barrel");
            f.face(other,Direction.EAST).apply(FlowMode.INSERT,FilterMode.BLACKLIST,Set.of(),OWNER,f.controller.id(),null,"minecraft:barrel");
            f.source.setItem(0,new ItemStack(Items.IRON_INGOT,32));((Container)f.level.getBlockEntity(other.west())).setItem(0,new ItemStack(Items.IRON_INGOT,32));
            f.arm();f.depart();h.assertTrue(f.owned().size()==1,"Components multiplied departure budget");
            f.depart();h.assertTrue(f.owned().size()==2 && f.owned().stream().map(p->p.sourceIdentity).distinct().count()==2,"Round robin starved a source");
            for(BlockPos pos:List.of(other,other.west(),other.east()))f.level.removeBlock(pos,false);
        }h.succeed();
    }
    @GameTest(template="empty") public static void destroyingControllerDropsCargoOnce(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            f.source.setItem(0,new ItemStack(Items.IRON_INGOT,32));f.arm();f.depart();f.depart();
            h.assertTrue(f.owned().size()==2,"Fixture needs two cargo packets");
            // The remote fixture chunk is loaded, not entity-ticking; observe actual spawn events.
            var drops=new java.util.concurrent.atomic.AtomicInteger();
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> listener=event->{
                if(event.getLevel()==f.level && event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item
                        && item.distanceToSqr(f.base.getCenter())<100 && item.getItem().is(Items.IRON_INGOT))drops.addAndGet(item.getItem().getCount());
            };
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
            try {f.level.removeBlock(f.controller.getBlockPos(),false);f.manager.controllerDestroyed(f.controller);}
            finally {net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);}
            h.assertTrue(f.owned().isEmpty() && drops.get()==32,"Controller break duplicated or lost cargo: "+drops.get());
        }h.succeed();
    }
    @GameTest(template="empty") public static void powerLossPausesAndResumeChargesOnlyOneDeparture(GameTestHelper h) {
        int cost=StorageConfig.CONTROLLER_ENERGY.get();StorageConfig.CONTROLLER_ENERGY.set(1200);
        try(var f=new Fixture(h)) {
            f.controller.setHomeNetwork(null);f.source.setItem(0,new ItemStack(Items.IRON_INGOT,16));f.arm();
            StorageBlockEntity.tick(f.level,f.controller.getBlockPos(),f.controller.getBlockState(),f.controller);
            long energy=f.controller.energyPort().stored();f.depart();
            h.assertTrue(f.controller.energyPort().stored()==energy-1,"Departure did not cost exactly 1 HE");
            f.controller.energyPort().setStored(0);StorageBlockEntity.tick(f.level,f.controller.getBlockPos(),f.controller.getBlockState(),f.controller);
            f.steps(24);h.assertTrue(f.owned().size()==1 && f.target.isEmpty(),"Unpowered transit moved");
            f.controller.energyPort().insert(100,false);StorageBlockEntity.tick(f.level,f.controller.getBlockPos(),f.controller.getBlockState(),f.controller);
            long resumed=f.controller.energyPort().stored();f.steps(24);
            h.assertTrue(f.target.countItem(Items.IRON_INGOT)==16 && f.controller.energyPort().stored()==resumed,"Resume lost items or charged twice");
        }finally{StorageConfig.CONTROLLER_ENERGY.set(cost);}h.succeed();
    }
    @GameTest(template="empty") public static void removingLaterSegmentCannotTeleportCargo(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            f.source.setItem(0,new ItemStack(Items.IRON_INGOT,16));f.arm();f.depart();f.level.removeBlock(f.last,false);f.steps(24);
            h.assertTrue(f.target.isEmpty() && f.owned().size()==1 && f.owned().getFirst().stack().getCount()==16,"Cargo crossed a missing segment");
            f.level.setBlockAndUpdate(f.last,StorageRegistries.PIPE.get().defaultBlockState());f.arm();f.steps(24);
            h.assertTrue(f.target.countItem(Items.IRON_INGOT)+f.owned().stream().mapToInt(p->p.stack().getCount()).sum()==16,"Reconstruction duplicated cargo");
        }h.succeed();
    }
    @GameTest(template="empty") public static void depositPortsAllOrientations(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            BlockPos centre=f.base.south(4);
            for(Direction front:Direction.Plane.HORIZONTAL) {
                f.level.setBlockAndUpdate(centre,StorageRegistries.DEPOSIT.get().defaultBlockState()
                        .setValue(fr.lkdm.homelink.storage.block.StorageBlock.TARGET,front));
                for(Direction side:Direction.values()) {
                    BlockPos pos=centre.relative(side);f.level.setBlockAndUpdate(pos,StorageRegistries.PIPE.get().defaultBlockState());
                    var live=PipeEndpoint.resolve(f.level,pos,side.getOpposite());
                    boolean open=side!=Direction.DOWN && side!=front;
                    h.assertTrue((live!=null)==open,"Wrong Deposit face "+front+"/"+side);
                    if(live!=null)h.assertTrue(FlowMode.INSERT.allowedBy(live.endpoint().port()) && !FlowMode.EXTRACT.allowedBy(live.endpoint().port()),"Deposit INPUT bypassed");
                    f.level.removeBlock(pos,false);
                }
            }
            f.level.removeBlock(centre,false);
        }h.succeed();
    }
    @GameTest(template="empty") public static void actualFarmAndQuarryPortsToDeposit(GameTestHelper h) {
        for(String id:List.of("homelink_farm:farmbot_station","homelink_quarry:quarry_i")) {
            var key=net.minecraft.resources.ResourceLocation.parse(id);
            if(!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(key))continue;
            try(var f=new Fixture(h)) {
                var machine=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(key);
                var state=machine.defaultBlockState();
                var facing=net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
                if(state.hasProperty(facing))state=state.setValue(facing,Direction.WEST);
                f.level.setBlockAndUpdate(f.base,state);
                var entity=f.level.getBlockEntity(f.base);
                var data=entity.saveWithFullMetadata(f.level.registryAccess());
                var stock=new net.neoforged.neoforge.items.ItemStackHandler(27);stock.setStackInSlot(0,new ItemStack(Items.IRON_INGOT,16));
                data.put(id.startsWith("homelink_farm:")?"Output":"buffer",stock.serializeNBT(f.level.registryAccess()));
                data.putUUID("owner",OWNER);data.putUUID("Owner",OWNER);
                entity.loadWithComponents(data,f.level.registryAccess());f.level.invalidateCapabilities(f.base);
                f.level.setBlockAndUpdate(f.base.east(4),StorageRegistries.DEPOSIT.get().defaultBlockState()
                        .setValue(fr.lkdm.homelink.storage.block.StorageBlock.TARGET,Direction.NORTH));
                var deposit=(fr.lkdm.homelink.storage.blockentity.DepositBlockEntity)f.level.getBlockEntity(f.base.east(4));deposit.setOwner(OWNER);
                f.face(f.first,Direction.WEST).apply(FlowMode.EXTRACT,FilterMode.BLACKLIST,Set.of(),OWNER,f.controller.id(),f.controller.networkId(),id);
                f.face(f.last,Direction.EAST).apply(FlowMode.INSERT,FilterMode.BLACKLIST,Set.of(),OWNER,f.controller.id(),f.controller.networkId(),"homelink_storage:storage_deposit");
                f.manager.pipeChanged(f.first);f.manager.pipeChanged(f.last);f.manager.settleNow();f.depart();
                h.assertTrue(f.owned().size()==1,"Real intermod port did not extract: "+id);
                f.steps(24);
                h.assertTrue(deposit.inventory().getStackInSlot(0).getCount()==16 && f.owned().isEmpty(),"Intermod cargo not delivered: "+id);
                com.mojang.logging.LogUtils.getLogger().info("STORAGE_PIPE_INTERMOD_OK source={} destination=storage_deposit items=16",id);
            }
        }h.succeed();
    }

    @GameTest(template="empty") public static void standaloneDeliversWithoutControllerOrEnergy(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            f.autonomous();f.source.setItem(0,new ItemStack(Items.IRON_INGOT,16));f.manager.tick();
            h.assertTrue(f.autoCargo().size()==1 && f.source.isEmpty() && f.target.isEmpty(),"Autonomous departure failed");
            f.steps(23);h.assertTrue(f.target.isEmpty(),"Autonomous delivery teleported");
            f.steps(1);h.assertTrue(f.target.getItem(0).getCount()==16 && f.autoCargo().isEmpty(),"Autonomous delivery failed");
        }h.succeed();
    }
    @GameTest(template="empty") public static void standaloneHonorsBothFiltersAndExplicitArming(GameTestHelper h) {
        for(int scenario=0;scenario<3;scenario++) try(var f=new Fixture(h)) {
            f.autonomous();f.source.setItem(0,new ItemStack(Items.IRON_INGOT,16));
            if(scenario==0)f.face(f.first,Direction.WEST).disarm();
            if(scenario==1)f.face(f.first,Direction.WEST).apply(FlowMode.EXTRACT,FilterMode.WHITELIST,Set.of(),f.autoOwner,null,null,"minecraft:barrel");
            if(scenario==2)f.face(f.last,Direction.EAST).apply(FlowMode.INSERT,FilterMode.WHITELIST,Set.of(),f.autoOwner,null,null,"minecraft:barrel");
            f.manager.settleNow();f.manager.tick();h.assertTrue(f.source.getItem(0).getCount()==16 && f.autoCargo().isEmpty(),"Standalone filter/arming bypass in scenario "+scenario);
        }h.succeed();
    }
    @GameTest(template="empty") public static void standaloneBrokenPipeDropsExactlyOnce(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            f.autonomous();f.source.setItem(0,new ItemStack(Items.IRON_INGOT,16));f.manager.tick();
            h.assertTrue(f.autoCargo().size()==1,"No standalone cargo for break test");
            var count=new java.util.concurrent.atomic.AtomicInteger();
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> listener=event->{
                if(event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item && item.getItem().is(Items.IRON_INGOT)
                        && item.position().distanceToSqr(f.first.getCenter())<4)count.addAndGet(item.getItem().getCount());
            };
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
            try { f.level.removeBlock(f.first,false);f.manager.pipeRemoved(f.first);f.manager.tick();
                h.assertTrue(count.get()==16 && f.autoCargo().isEmpty() && f.source.isEmpty(),"Standalone break duplicated/lost cargo");
            }finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);}
        }h.succeed();
    }
    @GameTest(template="empty") public static void standaloneRejectsAnotherOwnersEndpoint(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            f.autonomous();f.source.setItem(0,new ItemStack(Items.IRON_INGOT,16));
            ((StoragePipeBlockEntity)f.level.getBlockEntity(f.last)).setOwner(UUID.randomUUID());
            f.manager.settleNow();f.manager.tick();
            h.assertTrue(f.autoCargo().isEmpty() && f.source.getItem(0).getCount()==16,"Mixed-owner circuit bypassed ownership");
        }h.succeed();
    }
    @GameTest(template="empty") public static void standaloneLedgerRestoresExactCargo(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            f.autonomous();var stack=new ItemStack(Items.DIAMOND_SWORD);stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Autonomous sword"));
            f.source.setItem(0,stack.copy());f.manager.tick();
            var saved=f.manager.ledger().save(new net.minecraft.nbt.CompoundTag(),f.level.registryAccess());
            var copy=fr.lkdm.homelink.storage.logistics.transit.TransitLedger.load(saved,f.level.registryAccess());
            var packet=copy.get(f.autoCargo().getFirst().id);
            h.assertTrue(packet!=null && f.autoOwner.equals(packet.autonomousOwner) && net.minecraft.world.item.ItemStack.isSameItemSameComponents(stack,packet.stack()),"Autonomous ledger context/components lost");
            f.steps(24);h.assertTrue(f.target.getItem(0).getHoverName().getString().equals("Autonomous sword"),"Autonomous exact variant lost");
        }h.succeed();
    }
    private static final class Fixture implements AutoCloseable {
        final UUID autoOwner=UUID.randomUUID();
        final ServerLevel level; final BlockPos base,first,last; final StorageBlockEntity controller;
        final Container source,target; final PipeNetworkManager manager;
        Fixture(GameTestHelper h) {
            level=h.getLevel(); base=new BlockPos(1602+32*fixture++,80,1602); first=base.east(); last=base.east(3);
            level.getChunkAt(base);
            level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(base).inflate(7)).forEach(net.minecraft.world.entity.Entity::discard);
            level.setBlockAndUpdate(base,Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(base.east(4),Blocks.BARREL.defaultBlockState());
            source=(Container)level.getBlockEntity(base); target=(Container)level.getBlockEntity(base.east(4));
            level.setBlockAndUpdate(base.east(2).south(),StorageRegistries.CONTROLLER.get().defaultBlockState());
            controller=(StorageBlockEntity)level.getBlockEntity(base.east(2).south()); controller.setOwner(OWNER);
            for(int i=1;i<=3;i++) level.setBlockAndUpdate(base.east(i),StorageRegistries.PIPE.get().defaultBlockState());
            manager=PipeNetworkManager.get(level); manager.settleNow(); controller.setPipeCircuits(1);
            controller.energyPort().insert(1000,false);
        }
        PipeFaceConfig face(BlockPos pos,Direction side) { return ((StoragePipeBlockEntity)level.getBlockEntity(pos)).configOrCreate(side); }
        void arm() {
            face(first,Direction.WEST).apply(FlowMode.EXTRACT,FilterMode.BLACKLIST,Set.of(),OWNER,controller.id(),controller.networkId(),"minecraft:barrel");
            face(last,Direction.EAST).apply(FlowMode.INSERT,FilterMode.BLACKLIST,Set.of(),OWNER,controller.id(),controller.networkId(),"minecraft:barrel");
            manager.settleNow();
        }
        void depart() { manager.dispatchSoon(controller.id()); manager.tick(); }
        void steps(int ticks) { for(int i=0;i<ticks;i++) manager.tick(); }
        List<TransitPacket> owned(){return manager.ledger().owned(controller.id());}
        List<TransitPacket> autoCargo(){return manager.ledger().packets().stream().filter(p->autoOwner.equals(p.autonomousOwner)).toList();}
        void autonomous(){
            level.removeBlock(controller.getBlockPos(),false);
            for(int i=1;i<=3;i++)((StoragePipeBlockEntity)level.getBlockEntity(base.east(i))).setOwner(autoOwner);
            face(first,Direction.WEST).apply(FlowMode.EXTRACT,FilterMode.BLACKLIST,Set.of(),autoOwner,null,null,"minecraft:barrel");
            face(last,Direction.EAST).apply(FlowMode.INSERT,FilterMode.BLACKLIST,Set.of(),autoOwner,null,null,"minecraft:barrel");
            manager.settleNow();
        }
        void fillTarget(int count){for(int i=0;i<target.getContainerSize();i++) target.setItem(i,new ItemStack(Items.IRON_INGOT,count));}
        @Override public void close(){for(int x=0;x<=4;x++) for(int z=0;z<=1;z++) level.removeBlock(base.offset(x,0,z),false);}
    }
}
