package fr.lkdm.homelink.storage.verification;

import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.logistics.filter.*;
import fr.lkdm.homelink.storage.logistics.network.PipeNetworkManager;
import fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlockEntity;
import fr.lkdm.homelink.storage.logistics.transit.TransitPacket;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(StorageValidation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PipePersistenceGameTests {
    @GameTest(template="empty") public static void standaloneSurvivesProcessRestart(GameTestHelper h) {
        String pass=System.getProperty("storage.persistencePass","");
        if(pass.isEmpty()){h.succeed();return;}
        var level=h.getLevel();var manager=PipeNetworkManager.get(level);
        BlockPos base=new BlockPos(11002,80,11002);BlockPos pos=base.east();
        UUID owner=UUID.fromString("f6d13789-e585-4de4-9285-304b368697dd");
        level.getChunkAt(base);
        if(pass.equals("write")) {
            for(var old:List.copyOf(manager.ledger().packets())) if(owner.equals(old.autonomousOwner))manager.ledger().remove(old.id);
            level.removeBlock(pos,false);
            level.setBlockAndUpdate(base,Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(base.east(2),Blocks.BARREL.defaultBlockState());
            ((Container)level.getBlockEntity(base)).clearContent();
            ((Container)level.getBlockEntity(base.east(2))).clearContent();
            level.setBlockAndUpdate(pos,StorageRegistries.PIPE.get().defaultBlockState());
            var pipe=(StoragePipeBlockEntity)level.getBlockEntity(pos);pipe.setOwner(owner);
            for(Direction side:List.of(Direction.WEST,Direction.EAST))pipe.configOrCreate(side).apply(side==Direction.WEST?FlowMode.EXTRACT:FlowMode.INSERT,FilterMode.BLACKLIST,Set.of(),owner,null,null,"minecraft:barrel");
            var stack=new ItemStack(Items.IRON_INGOT,16);stack.set(DataComponents.CUSTOM_NAME,Component.literal("Standalone restart"));
            ((Container)level.getBlockEntity(base)).setItem(0,stack);
            manager.settleNow();manager.dispatchStandaloneSoon(owner);manager.tick();
            h.assertTrue(manager.ledger().packets().stream().anyMatch(p->owner.equals(p.autonomousOwner)),"No standalone departure to save: component="+manager.component(pos).status()+" faces="+manager.component(pos).faces().stream().map(f->f.endpoint.side()+":"+f.config.mode()+":"+f.status+":"+f.usable).toList()+" source="+((Container)level.getBlockEntity(base)).getItem(0));
            // Block both delivery and return, so the fixture remains stable until clean shutdown.
            for(Direction side:List.of(Direction.WEST,Direction.EAST))pipe.configOrCreate(side).apply(side==Direction.WEST?FlowMode.EXTRACT:FlowMode.INSERT,FilterMode.WHITELIST,Set.of(),owner,null,null,"minecraft:barrel");
            pipe.configChanged();manager.settleNow();for(int i=0;i<8;i++)manager.tick();
            level.getServer().saveEverything(false,true,true);
        } else {
            // BlockEntity.onLoad notifications run after the synchronous getChunk call completes.
            h.runAfterDelay(2,()->{
            var cargo=manager.ledger().packets().stream().filter(p->owner.equals(p.autonomousOwner)).toList();
            h.assertTrue(cargo.size()==1 && cargo.getFirst().stack().getCount()==16 && cargo.getFirst().stack().getHoverName().getString().equals("Standalone restart"),"Standalone restart lost exact cargo");
            h.assertTrue(((Container)level.getBlockEntity(base)).isEmpty(),"Standalone restart re-extracted source");
            var pipe=(StoragePipeBlockEntity)level.getBlockEntity(pos);
            for(Direction side:List.of(Direction.WEST,Direction.EAST))pipe.configOrCreate(side).apply(side==Direction.WEST?FlowMode.EXTRACT:FlowMode.INSERT,FilterMode.BLACKLIST,Set.of(),owner,null,null,"minecraft:barrel");
            pipe.configChanged();manager.settleNow();cargo.getFirst().nextAttempt=0;
            // Retry scheduling and queued chunk notifications use real server time.
            // Repeated manager.tick() calls within one game tick cannot advance that time.
            h.succeedWhen(()->{
                h.assertTrue(((Container)level.getBlockEntity(base.east(2))).getItem(0).getCount()==16
                        && manager.ledger().get(cargo.getFirst().id)==null,"Standalone restart could not resume delivery");
                com.mojang.logging.LogUtils.getLogger().info("STORAGE_PIPE_STANDALONE_PERSISTENCE_READ_OK items=16");
            });
            });return;
        }
        com.mojang.logging.LogUtils.getLogger().info("STORAGE_PIPE_STANDALONE_PERSISTENCE_{}_OK items=16",pass.toUpperCase(java.util.Locale.ROOT));
        h.succeed();
    }
    private static final BlockPos BASE=new BlockPos(10002,80,10002);
    private static final UUID OWNER=UUID.fromString("ad8a6e67-158c-4453-bb6f-a2853b2365e3");
    @GameTest(template="empty") public static void cargoSurvivesProcessRestart(GameTestHelper h) {
        String pass=System.getProperty("storage.persistencePass","");
        if(pass.isEmpty()){h.succeed();return;}
        var level=h.getLevel(); var manager=PipeNetworkManager.get(level);
        level.getChunkAt(BASE); // Fixture only. Transport never obtains tickets.
        if(pass.equals("write")) {
            for(int x=0;x<=4;x++) for(int z=0;z<=1;z++) level.removeBlock(BASE.offset(x,0,z),false);
            level.setBlockAndUpdate(BASE,Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(BASE.east(4),Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(BASE.east(2).south(),StorageRegistries.CONTROLLER.get().defaultBlockState());
            var controller=(StorageBlockEntity)level.getBlockEntity(BASE.east(2).south());
            controller.setOwner(OWNER); controller.setHomeNetwork(null);
            for(int i=1;i<=3;i++)level.setBlockAndUpdate(BASE.east(i),StorageRegistries.PIPE.get().defaultBlockState());
            var source=(Container)level.getBlockEntity(BASE);
            var stack=new ItemStack(Items.IRON_INGOT,32); stack.set(DataComponents.CUSTOM_NAME,Component.literal("Restart cargo"));source.setItem(0,stack);
            for(int i:new int[]{1,3}) {
                var pipe=(StoragePipeBlockEntity)level.getBlockEntity(BASE.east(i)); pipe.setOwner(OWNER);
                pipe.configOrCreate(i==1?Direction.WEST:Direction.EAST).apply(i==1?FlowMode.EXTRACT:FlowMode.INSERT,
                        FilterMode.BLACKLIST,Set.of(),OWNER,controller.id(),null,"minecraft:barrel");pipe.configChanged();
            }
            manager.settleNow();controller.setPipeCircuits(1);controller.energyPort().insert(1000,false);
            manager.dispatchSoon(controller.id());manager.tick();for(int i=0;i<9;i++)manager.tick();
            manager.dispatchSoon(controller.id());manager.tick();
            level.removeBlock(BASE.east(2),false);controller.setPipesPaused(true);
            h.assertTrue(manager.ledger().owned(controller.id()).size()==2,"Expected two real departures");
            level.getServer().saveEverything(false,true,true);
            com.mojang.logging.LogUtils.getLogger().info("STORAGE_PIPE_PERSISTENCE_WRITE_OK cargo=32 active_and_recovery=true");
        } else {
            var controller=(StorageBlockEntity)level.getBlockEntity(BASE.east(2).south());
            h.assertTrue(controller!=null && controller.pipesPaused(),"Pipe Controller not persisted");
            var packets=manager.ledger().owned(controller.id());
            h.assertTrue(packets.size()==2 && packets.stream().mapToInt(p->p.stack().getCount()).sum()==32,"Cargo not preserved across process restart");
            h.assertTrue(packets.stream().filter(p->p.state==TransitPacket.State.RECOVERY).count()==1,"Recovery ownership changed");
            h.assertTrue(packets.stream().filter(p->p.state==TransitPacket.State.ACTIVE).count()==1,"Active cargo lost");
            for(var p:packets)h.assertTrue(p.stack().getHoverName().getString().equals("Restart cargo"),"Components lost across restart");
            h.assertTrue(((Container)level.getBlockEntity(BASE)).isEmpty(),"Cargo re-extracted or returned fictitiously");
            var pipe=(StoragePipeBlockEntity)level.getBlockEntity(BASE.east());
            h.assertTrue(pipe.config(Direction.WEST).armed() && pipe.config(Direction.WEST).mode()==FlowMode.EXTRACT,"Face configuration lost");
            com.mojang.logging.LogUtils.getLogger().info("STORAGE_PIPE_PERSISTENCE_READ_OK cargo=32 active_and_recovery=true");
        }
        h.succeed();
    }
}
