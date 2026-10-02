package fr.lkdm.homelink.storage.verification;

import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.client.logistics.*;
import fr.lkdm.homelink.storage.logistics.filter.*;
import fr.lkdm.homelink.storage.logistics.network.*;
import fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlockEntity;
import fr.lkdm.homelink.storage.logistics.sync.PipePayloads;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Uses the real connection payload, real registry catalogue, GUI widgets and moving ledger cargo. */
public final class PipeClientChecks {
    private static final BlockPos BASE=new BlockPos(20,5,4);
    private static int ticks,phase,scale;
    private static boolean sawCargo;
    public static void prepare(ServerPlayer player) {
        var level=player.serverLevel(); player.closeContainer();
        for(int x=19;x<=42;x++)for(int z=-7;z<=13;z++)level.setBlockAndUpdate(new BlockPos(x,4,z),Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(BASE,Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(BASE.east(8),Blocks.BARREL.defaultBlockState());
        BlockPos cp=BASE.east(4).north();level.removeBlock(cp,false);
        for(int i=1;i<=7;i++)level.setBlockAndUpdate(BASE.east(i),StorageRegistries.PIPE.get().defaultBlockState());
        for(int i=1;i<=7;i++) {
            BlockPos pos=BASE.east(i);var state=level.getBlockState(pos);
            for(Direction side:Direction.values())state=state.setValue(fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlock.SIDES.get(side),
                    fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlock.classify(level,pos,side));
            level.setBlockAndUpdate(pos,state);
        }
        for(int i:new int[]{1,7}){
            var pipe=(StoragePipeBlockEntity)level.getBlockEntity(BASE.east(i));pipe.setOwner(player.getUUID());
            pipe.configOrCreate(i==1?Direction.WEST:Direction.EAST).apply(i==1?FlowMode.EXTRACT:FlowMode.INSERT,
                    FilterMode.BLACKLIST,Set.of(),player.getUUID(),null,null,"minecraft:barrel");pipe.configChanged();
        }
        // A separate model display covers elbows, a T, a crossing and a vertical rise.
        Set<BlockPos> display = Set.of(
                new BlockPos(21,5,-2),new BlockPos(22,5,-2),new BlockPos(23,5,-2),new BlockPos(23,5,-3),new BlockPos(23,5,-4),
                new BlockPos(25,5,-2),new BlockPos(26,5,-2),new BlockPos(27,5,-2),new BlockPos(26,5,-3),
                new BlockPos(29,5,-3),new BlockPos(29,6,-3),new BlockPos(29,7,-3),new BlockPos(28,7,-3),
                new BlockPos(26,5,-5),new BlockPos(25,5,-5),new BlockPos(27,5,-5),new BlockPos(26,5,-4),new BlockPos(26,5,-6));
        for (BlockPos pos : display) level.setBlockAndUpdate(pos,StorageRegistries.PIPE.get().defaultBlockState());
        for (BlockPos pos : display) {
            var state=level.getBlockState(pos);
            for(Direction side:Direction.values())state=state.setValue(fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlock.SIDES.get(side),
                    fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlock.classify(level,pos,side));
            level.setBlockAndUpdate(pos,state);
        }
        // Real chest/barrel shapes verify the socket on every attachment direction.
        BlockPos[] containers = {new BlockPos(35,5,-2),new BlockPos(39,5,0),new BlockPos(35,5,3),
                new BlockPos(40,5,3),new BlockPos(36,7,6),new BlockPos(40,5,6)};
        Direction[] faces = {Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST,Direction.UP,Direction.DOWN};
        for(int i=0;i<faces.length;i++) {
            var container=containers[i];
            level.setBlockAndUpdate(container,(i%2==0?Blocks.CHEST:Blocks.BARREL).defaultBlockState());
            var pipe=container.relative(faces[i].getOpposite());
            level.setBlockAndUpdate(pipe,StorageRegistries.PIPE.get().defaultBlockState());
            var state=level.getBlockState(pipe);
            for(Direction side:Direction.values())state=state.setValue(fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlock.SIDES.get(side),
                    fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlock.classify(level,pipe,side));
            level.setBlockAndUpdate(pipe,state);
        }
        var source=(Container)level.getBlockEntity(BASE);
        for(int i=0;i<source.getContainerSize();i++)source.setItem(i,new ItemStack(i%2==0?Items.IRON_INGOT:Items.COPPER_BLOCK,64));
        var manager=PipeNetworkManager.get(level);manager.settleNow();manager.tick();
        com.mojang.logging.LogUtils.getLogger().info("PIPE_CLIENT_FIXTURE mode={} status={}",
                ((StoragePipeBlockEntity)level.getBlockEntity(BASE.east())).config(Direction.WEST).mode(),manager.component(BASE.east()).status());
        player.teleportTo(24.5,5,9);player.setYRot(180);player.setXRot(12);
        // A real server view arrives through the same handler as a right click.
        PipeInteraction.open(player,new PipePayloads.OpenFace(BASE.east(),(byte)Direction.WEST.get3DDataValue()));
    }
    public static boolean tick(Minecraft mc) {
        sawCargo |= PipeVisuals.count()>0;
        if(++ticks<20)return false;
        if(phase==0){
            if(!(mc.screen instanceof PipeScreen screen) || !screen.shows(BASE.east(),Direction.WEST)) {
                if(ticks%40==0) net.neoforged.neoforge.network.PacketDistributor.sendToServer(new PipePayloads.OpenFace(BASE.east(),(byte)Direction.WEST.get3DDataValue()));
                return false;
            }
            capture(mc,"storage-pipe-connection.png");
            check(screen.draftMode()==FlowMode.EXTRACT,"Wrong container direction: "+screen.draftMode());
            check(screen.autonomous(),"Standalone GUI still requires a Controller");
            screen.setFilterTab(true);screen.toggle(ResourceLocation.withDefaultNamespace("diamond"));screen.toggle(ResourceLocation.withDefaultNamespace("emerald"));
            check(screen.draftItems().size()==2,"Multiple selection failed");
            check(ItemCatalog.all().size()>1000 && ItemCatalog.search("minecraft:diamond").size()>1,"Registry catalogue/search incomplete");
            screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(b->b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("screen.homelink_storage.pipe.selected_only").getString())).findFirst().orElseThrow().onPress();
            phase=1;ticks=0;
        }else if(phase==1){
            var screen=(PipeScreen)mc.screen;capture(mc,"storage-pipe-filter.png");
            var search=screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
            screen.setFocused(search);search.setFocused(true);screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_E,0,0);screen.charTyped('e',0);
            check(mc.screen==screen && search.getValue().equals("e"),"Inventory key escaped search");search.setValue("");
            screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(b->b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("screen.homelink_storage.pipe.apply").getString())).findFirst().orElseThrow().onPress();
            phase=2;ticks=0;
        }else if(phase==2){
            var screen=(PipeScreen)mc.screen;
            check(screen.result().equals("saved") && screen.draftItems().size()==2,"Real Apply request failed");
            org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),1280,960);
            scale=mc.options.guiScale().get();mc.options.guiScale().set(2);mc.resizeDisplay();phase=3;ticks=0;
        }else if(phase>=3 && phase<=5){
            check(mc.getWindow().getGuiScale()==phase-1,"Requested GUI scale was clamped: "+mc.getWindow().getGuiScale());
            capture(mc,"storage-pipe-filter-scale-"+(phase-1)+".png");
            if(phase<5){mc.options.guiScale().set(phase);mc.resizeDisplay();phase++;ticks=0;}
            else{mc.options.guiScale().set(scale);mc.resizeDisplay();mc.setScreen(null);mc.options.fov().set(55);camera(mc,29.5,6.8,10,138,21);mc.options.hideGui=true;phase=6;ticks=0;}
        }else if(phase==6){
            var state=mc.level.getBlockState(BASE.east(2));
            var model=mc.getBlockRenderer().getBlockModel(state);
            var layers=model.getRenderTypes(state,net.minecraft.util.RandomSource.create(0),net.neoforged.neoforge.client.model.data.ModelData.EMPTY);
            check(layers.contains(net.minecraft.client.renderer.RenderType.solid()) && layers.contains(net.minecraft.client.renderer.RenderType.translucent()),
                    "Metal and glass did not retain separate chunk layers");
            var pipeItem=new ItemStack(StorageRegistries.PIPE.get().asItem());
            check(mc.getItemRenderer().getModel(pipeItem,mc.level,null,0).getRenderPasses(pipeItem,false).size()==2,
                    "Pipe item did not retain separate render passes");
            check(sawCargo,"No live transit visuals received");capture(mc,"storage-pipe-transit.png");
            camera(mc,31,8.5,5.5,151,24);phase=7;ticks=0;
        }else if(phase==7){
            capture(mc,"storage-pipe-model-junctions.png");camera(mc,42,9,12,153,27);phase=8;ticks=0;
        }else if(phase==8){
            capture(mc,"storage-pipe-connectors-six-faces.png");camera(mc,36.8,5.9,1.8,149,39);phase=9;ticks=0;
        }else if(phase==9){
            capture(mc,"storage-pipe-connector-chest.png");mc.options.hideGui=false;
            com.mojang.logging.LogUtils.getLogger().info("STORAGE_PIPE_CLIENT_CHECKS_OK standalone=true real_payload=true catalogue=true multiple_selection=true apply=true search_keyboard=true gui_scales=2,3,4 live_cargo=true split_layers=true item_passes=2 connectors=six_faces");return true;
        }
        return false;
    }
    private static void camera(Minecraft mc,double x,double y,double z,float yaw,float pitch){
        var id=mc.player.getUUID();
        mc.getSingleplayerServer().execute(()->{
            var player=mc.getSingleplayerServer().getPlayerList().getPlayer(id);
            if(player!=null){player.getAbilities().flying=true;player.onUpdateAbilities();player.teleportTo(x,y,z);}
        });
        mc.player.setYRot(yaw);mc.player.setXRot(pitch);
    }
    private static void capture(Minecraft mc,String name){net.minecraft.client.Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),m->com.mojang.logging.LogUtils.getLogger().info("PIPE_SCREENSHOT {}",m.getString()));}
    private static void check(boolean ok,String reason){if(!ok)throw new IllegalStateException(reason);}
}
