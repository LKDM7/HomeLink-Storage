package fr.lkdm.homelink.storage.logistics;

import static org.junit.jupiter.api.Assertions.*;
import fr.lkdm.homecore.api.item.ItemPortType;
import fr.lkdm.homelink.storage.logistics.filter.*;
import fr.lkdm.homelink.storage.logistics.network.PipeGraph;
import fr.lkdm.homelink.storage.logistics.transit.TransferEngine;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

class PipeLogicTest {
    private static final ResourceLocation IRON = ResourceLocation.withDefaultNamespace("iron_ingot");
    private static final ResourceLocation GOLD = ResourceLocation.withDefaultNamespace("gold_ingot");
    @Test void emptyFiltersAndMultipleSelection() {
        assertTrue(PipeFilter.OPEN.allows(IRON));
        assertFalse(new PipeFilter(FilterMode.WHITELIST, Set.of()).allows(IRON));
        for (var mode : FilterMode.values()) {
            var filter = new PipeFilter(mode, Set.of(IRON, GOLD));
            assertEquals(mode == FilterMode.WHITELIST, filter.allows(IRON));
            assertEquals(mode == FilterMode.WHITELIST, filter.allows(GOLD));
            assertEquals(mode == FilterMode.BLACKLIST, filter.allows(ResourceLocation.withDefaultNamespace("diamond")));
        }
    }
    @Test void validateDuplicatesMissingIdsAndLimits() {
        assertEquals(1, PipeFilter.validate(List.of(IRON.toString(), IRON.toString()), Set.of(), id -> true, 1).items().size());
        assertEquals(PipeFilter.Problem.TOO_MANY, PipeFilter.validate(List.of(IRON.toString(), GOLD.toString()), Set.of(), id -> true, 1).problem());
        assertEquals(PipeFilter.Problem.INVALID_ID, PipeFilter.validate(List.of("NOT AN ID"), Set.of(), id -> true, 256).problem());
        assertEquals(PipeFilter.Problem.UNKNOWN_ID, PipeFilter.validate(List.of("missing:item"), Set.of(), id -> false, 256).problem());
        assertTrue(PipeFilter.validate(List.of("missing:item"), Set.of(ResourceLocation.parse("missing:item")), id -> false, 256).ok());
        assertFalse(PipeFilter.registered(ResourceLocation.withDefaultNamespace("air")));
    }
    @Test void directionIsSeenFromContainer() {
        assertTrue(FlowMode.INSERT.allowedBy(ItemPortType.INPUT));
        assertFalse(FlowMode.EXTRACT.allowedBy(ItemPortType.INPUT));
        assertTrue(FlowMode.EXTRACT.allowedBy(ItemPortType.OUTPUT));
        assertFalse(FlowMode.INSERT.allowedBy(ItemPortType.OUTPUT));
        for (var mode : FlowMode.values()) { assertTrue(mode.allowedBy(ItemPortType.BOTH)); assertTrue(mode.allowedBy(null)); }
    }
    @Test void savedConfigurationPreservesMissingIdsAndDisarmsAfterLimitReduction() {
        var face = new PipeFaceConfig();
        var missing = ResourceLocation.parse("missing:item");
        var player = UUID.randomUUID();
        face.apply(FlowMode.EXTRACT, FilterMode.BLACKLIST, Set.of(IRON, missing), player, null, null, "minecraft:chest");
        var normal = PipeFaceConfig.load(face.save(), 256);
        assertTrue(normal.armed()); assertEquals(face.items(), normal.items());
        var reduced = PipeFaceConfig.load(face.save(), 1);
        assertFalse(reduced.armed()); assertEquals(face.items(), reduced.items());
        assertFalse(reduced.filter().allows(IRON));
        long revision = face.revision();
        face.disarm(); assertEquals(revision + 1, face.revision());
        assertEquals(Set.of(IRON, missing), face.items());
    }
    @Test void shortestRoutesHandleAnglesCyclesAndDeadBranches() {
        var a = new BlockPos(0,64,0); var b = a.east(); var c = b.south(); var d = a.south();
        var pipes = Set.of(a,b,c,d,b.east());
        var route = PipeGraph.route(pipes,a,c,3);
        assertNotNull(route); assertEquals(3,route.size()); assertEquals(3,new HashSet<>(route).size());
        assertEquals(route,PipeGraph.route(pipes,a,c,3));
        assertNull(PipeGraph.route(pipes,a,c,2));
        assertNull(PipeGraph.route(pipes,a,a.above(),512));
        assertTrue(PipeGraph.valid(pipes,route,0));
        assertFalse(PipeGraph.valid(Set.of(a,c),route,0));
        assertEquals(List.of(a),PipeGraph.route(pipes,a,a,1));
        assertEquals(route,PipeGraph.tree(pipes,a,3).to(c));
    }
    @Test void endpointKeysDoNotCollideAcrossLargeCoordinates() {
        var a=new fr.lkdm.homelink.storage.logistics.network.PipeEndpoint.Key(new BlockPos(0,64,0),net.minecraft.core.Direction.UP);
        var b=new fr.lkdm.homelink.storage.logistics.network.PipeEndpoint.Key(new BlockPos(8_388_608,64,0),net.minecraft.core.Direction.UP);
        assertNotEquals(a,b);
    }
    @Test void rotatingWindowsReachLargeInventoriesWithoutBypassingSlotRules() {
        var target=new ItemStackHandler(400) {
            @Override public boolean isItemValid(int slot,ItemStack stack){return slot==399 && stack.is(Items.DIAMOND);}
        };
        var first=new fr.lkdm.homelink.storage.logistics.transit.HandlerWindow(target,0,64);
        var last=new fr.lkdm.homelink.storage.logistics.transit.HandlerWindow(target,384,64);
        assertEquals(0,TransferEngine.room(first,new ItemStack(Items.DIAMOND,16),List.of()));
        assertEquals(16,TransferEngine.room(last,new ItemStack(Items.DIAMOND,16),List.of()));
        assertTrue(TransferEngine.insert(last,new ItemStack(Items.DIAMOND,16)).isEmpty());
        assertEquals(16,target.getStackInSlot(399).getCount());
    }
    @Test void noCapacityDoesNotExtractAndOwnReservationsReduceRoom() {
        var source = new ItemStackHandler(1); source.setStackInSlot(0,new ItemStack(Items.IRON_INGOT,32));
        var target = new ItemStackHandler(1); target.setStackInSlot(0,new ItemStack(Items.IRON_INGOT,64));
        assertEquals(0, TransferEngine.room(target,source.getStackInSlot(0),List.of()));
        assertEquals(32,source.getStackInSlot(0).getCount());
        target.setStackInSlot(0,new ItemStack(Items.IRON_INGOT,40));
        assertEquals(8,TransferEngine.room(target,new ItemStack(Items.IRON_INGOT,16),List.of(new ItemStack(Items.IRON_INGOT,16))));
    }
    @Test void weakerRealExtractionAndPartialArrivalConserveExactVariants() {
        var source = new ItemStackHandler(1) {
            @Override public ItemStack extractItem(int slot,int amount,boolean simulate) {
                return super.extractItem(slot,simulate ? amount : Math.min(3,amount),simulate);
            }
        };
        var named = new ItemStack(Items.IRON_INGOT,12);
        named.set(DataComponents.CUSTOM_NAME,Component.literal("Cargo A"));
        source.setStackInSlot(0,named.copy());
        var expected = source.extractItem(0,10,true);
        var cargo = TransferEngine.extract(source,0,10,expected);
        assertEquals(3,cargo.getCount()); assertTrue(TransferEngine.sameVariant(named,cargo));
        var target = new ItemStackHandler(1); target.setStackInSlot(0,named.copyWithCount(63));
        var remainder = TransferEngine.insert(target,cargo);
        assertEquals(2,remainder.getCount());
        assertEquals(75,source.getStackInSlot(0).getCount()+target.getStackInSlot(0).getCount()+remainder.getCount());
        assertTrue(TransferEngine.sameVariant(named,remainder));
    }
    @Test void nonStackableVariantsAreNeverMerged() {
        var a = new ItemStack(Items.DIAMOND_SWORD); a.set(DataComponents.DAMAGE,17);
        var b = new ItemStack(Items.DIAMOND_SWORD); b.set(DataComponents.CUSTOM_NAME,Component.literal("B"));
        var target = new ItemStackHandler(1); target.setStackInSlot(0,a.copy());
        assertEquals(0,TransferEngine.room(target,b,List.of()));
        assertTrue(TransferEngine.sameVariant(b,TransferEngine.insert(target,b)));
        assertTrue(TransferEngine.sameVariant(a,target.getStackInSlot(0)));
    }
}
