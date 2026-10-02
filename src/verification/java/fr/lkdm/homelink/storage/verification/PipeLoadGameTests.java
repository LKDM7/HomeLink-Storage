package fr.lkdm.homelink.storage.verification;

import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.config.StorageConfig;
import fr.lkdm.homelink.storage.logistics.filter.*;
import fr.lkdm.homelink.storage.logistics.network.PipeNetworkManager;
import fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Measures actual server manager calls, excluding fixture construction. Not an FPS/TPS benchmark. */
@GameTestHolder(StorageValidation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PipeLoadGameTests {
    @GameTest(template="empty",timeoutTicks=300) public static void thousandPipesAndFullSharedQueues(GameTestHelper h) {
        var level=h.getLevel();var manager=PipeNetworkManager.get(level);
        List<BlockPos> placed=new ArrayList<>();List<StorageBlockEntity> controllers=new ArrayList<>();
        UUID owner=UUID.fromString("0f9517c5-adb4-4e40-882a-14f1a12783e6");
        int travel=StorageConfig.PIPE_TRAVEL_TICKS.get();StorageConfig.PIPE_TRAVEL_TICKS.set(200);
        try {
            // Explicit loading is confined to the fixture, not transport or production discovery.
            for(int x=19984;x<=20080;x+=16)for(int z=19984;z<=20048;z+=16)level.getChunk(x>>4,z>>4);
            for(int c=0;c<4;c++) {
                BlockPos base=new BlockPos(20002+c*14,80,20002);
                BlockPos cp=base.offset(5,-1,12);level.setBlockAndUpdate(cp,StorageRegistries.CONTROLLER.get().defaultBlockState());placed.add(cp);
                var controller=(StorageBlockEntity)level.getBlockEntity(cp);controller.setOwner(owner);controller.setHomeNetwork(null);controller.setPipeCircuits(1);
                controller.energyPort().insert(1000,false);controllers.add(controller);
                for(int x=0;x<10;x++)for(int z=0;z<25;z++) {
                    BlockPos pipe=base.offset(x,0,z);level.setBlockAndUpdate(pipe,StorageRegistries.PIPE.get().defaultBlockState());placed.add(pipe);
                }
                for(int i=0;i<25;i++) {
                    BlockPos pipe=base.offset(i%10,0,i);BlockPos target=pipe.above();
                    level.setBlockAndUpdate(target,Blocks.BARREL.defaultBlockState());placed.add(target);
                    boolean source=i%2==0;
                    ((StoragePipeBlockEntity)level.getBlockEntity(pipe)).configOrCreate(Direction.UP).apply(source?FlowMode.EXTRACT:FlowMode.INSERT,
                            FilterMode.BLACKLIST,Set.of(),owner,controller.id(),null,"minecraft:barrel");
                    if(source){var inventory=(Container)level.getBlockEntity(target);inventory.setItem(0,new ItemStack(Items.IRON_INGOT,64));inventory.setItem(1,new ItemStack(Items.IRON_INGOT,64));}
                }
            }
            long started=System.nanoTime();int rebuildTicks=0;
            while(!manager.settled() && rebuildTicks<100){manager.tick();rebuildTicks++;}
            double rebuildMs=(System.nanoTime()-started)/1_000_000.0;
            h.assertTrue(manager.settled(),"Topology did not settle within bounded work");
            h.assertTrue(controllers.stream().mapToInt(c->manager.telemetry(c.id()).pipes()).sum()==1000,"Missing pipe components");
            long total=0,max=0;
            for(int tick=0;tick<80;tick++){
                for(var c:controllers)manager.dispatchSoon(c.id());
                long before=System.nanoTime();manager.tick();long elapsed=System.nanoTime()-before;total+=elapsed;max=Math.max(max,elapsed);
            }
            int count=controllers.stream().mapToInt(c->manager.ledger().count(c.id())).sum();
            h.assertTrue(count==256,"Expected 64 cargos per Controller, got "+count);
            long cargo=controllers.stream().flatMap(c->manager.ledger().owned(c.id()).stream()).mapToLong(p->p.stack().getCount()).sum();
            h.assertTrue(cargo==4096,"Load cargo conservation failed");
            com.mojang.logging.LogUtils.getLogger().info("STORAGE_PIPE_LOAD_OK nodes=1000 endpoints=100 controllers=4 cargo_packets={} cargo_items={} rebuild_ticks={} rebuild_ms={} manager_ticks=80 mean_ms={} max_ms={}",count,cargo,rebuildTicks,rebuildMs,total/80_000_000.0,max/1_000_000.0);
        }finally{
            for(BlockPos pos:placed)level.removeBlock(pos,false);
            level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(19984,70,19984,20090,90,20064)).forEach(net.minecraft.world.entity.Entity::discard);
            StorageConfig.PIPE_TRAVEL_TICKS.set(travel);
        }
        h.succeed();
    }
}
