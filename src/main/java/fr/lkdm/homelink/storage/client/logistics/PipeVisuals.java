package fr.lkdm.homelink.storage.client.logistics;

import fr.lkdm.homelink.storage.HomeLinkStorage;
import fr.lkdm.homelink.storage.config.StorageClientConfig;
import fr.lkdm.homelink.storage.logistics.network.PipeGraph;
import fr.lkdm.homelink.storage.logistics.sync.PipePayloads;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Client pictures of cargo travelling in pipes. They follow the route the server sent at the
 * server speed and own nothing: the server ends them on delivery, recovery or drop. Pictures
 * of unloaded chunks, of a left world or past their arrival without news are removed.
 */
@EventBusSubscriber(modid = HomeLinkStorage.MOD_ID, value = Dist.CLIENT)
public final class PipeVisuals {
    /** Ticks a picture may wait at the end of its route before the client gives up on it. */
    private static final int STALE_TICKS = 200;

    public static final class Visual {
        final UUID id;
        final ItemStack display;
        final List<BlockPos> route;
        final Direction exit;
        Direction entry;
        int index;
        float progress;
        final int ticksPerBlock;
        boolean paused;
        int idle;
        int lease;

        Visual(PipePayloads.TransitStart start) {
            id = start.id();
            display = start.display();
            route = List.copyOf(start.route());
            entry = Direction.from3DDataValue(start.entry());
            exit = Direction.from3DDataValue(start.exit());
            index = Math.max(0, Math.min(start.index(), route.size() - 1));
            ticksPerBlock = Math.max(1, start.ticksPerBlock());
            progress = Math.min(start.progress(), ticksPerBlock);
            paused = start.paused();
        }

        public ItemStack display() { return display; }
        public UUID id() { return id; }

        /** Position inside the segment holding the picture, in block-local coordinates. */
        public Vec3 local(float partialTick) {
            float t = Math.min(1F, (progress + (paused || progress >= ticksPerBlock ? 0 : partialTick)) / ticksPerBlock);
            Direction out = index < route.size() - 1 ? PipeGraph.side(route.get(index), route.get(index + 1)) : exit;
            if (out == null) out = exit;
            Vec3 a = face(entry), b = face(out), c = new Vec3(0.5, 0.5, 0.5);
            double u = 1 - t;
            // Quadratic curve through the segment centre: stays inside the tube in corners and T junctions.
            return a.scale(u * u).add(c.scale(2 * u * t)).add(b.scale(t * t));
        }

        /** Travel direction at the picture, for orienting it. */
        public Direction heading() {
            Direction out = index < route.size() - 1 ? PipeGraph.side(route.get(index), route.get(index + 1)) : exit;
            return progress < ticksPerBlock / 2F ? entry.getOpposite() : out == null ? exit : out;
        }

        private static Vec3 face(Direction side) {
            return new Vec3(0.5 + side.getStepX() * 0.5, 0.5 + side.getStepY() * 0.5, 0.5 + side.getStepZ() * 0.5);
        }

        BlockPos at() { return route.get(index); }
    }

    private static final Map<UUID, Visual> VISUALS = new LinkedHashMap<>();
    private static Map<BlockPos, List<Visual>> byPipe = Map.of();

    public static void start(PipePayloads.TransitStart start) {
        int maximum = StorageClientConfig.get(StorageClientConfig.PIPE_MAX_RENDERED);
        if (!VISUALS.containsKey(start.id()) && VISUALS.size() >= maximum) return;
        VISUALS.put(start.id(), new Visual(start));
        index();
    }

    public static void remove(PipePayloads.TransitRemove remove) {
        Visual visual = VISUALS.remove(remove.id());
        index();
        var minecraft = Minecraft.getInstance();
        if (visual != null && remove.delivered() && minecraft.level != null && StorageClientConfig.get(StorageClientConfig.PIPE_SOUNDS)) {
            BlockPos end = visual.route.get(visual.route.size() - 1).relative(visual.exit);
            minecraft.level.playLocalSound(end, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.06F, 1.6F, false);
        }
    }

    public static List<Visual> at(BlockPos pipe) { return byPipe.getOrDefault(pipe, List.of()); }
    public static int count() { return VISUALS.size(); }
    public static boolean carrying(BlockPos pipe) { return byPipe.containsKey(pipe); }

    private static void index() {
        Map<BlockPos, List<Visual>> map = new HashMap<>();
        for (Visual visual : VISUALS.values()) map.computeIfAbsent(visual.at(), ignored -> new ArrayList<>(2)).add(visual);
        byPipe = map;
    }

    public static void clear() { VISUALS.clear(); byPipe = Map.of(); }

    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null) { if (!VISUALS.isEmpty()) clear(); return; }
        if (minecraft.isPaused() || VISUALS.isEmpty()) return;
        boolean moved = false;
        Iterator<Visual> iterator = VISUALS.values().iterator();
        while (iterator.hasNext()) {
            Visual visual = iterator.next();
            if (++visual.lease > 60) { iterator.remove(); moved = true; continue; }
            if (!minecraft.level.hasChunkAt(visual.at())) { iterator.remove(); moved = true; continue; }
            if (visual.paused) continue;
            visual.progress++;
            while (visual.progress >= visual.ticksPerBlock && visual.index < visual.route.size() - 1) {
                visual.progress -= visual.ticksPerBlock;
                BlockPos from = visual.route.get(visual.index);
                visual.index++;
                Direction entry = PipeGraph.side(visual.route.get(visual.index), from);
                if (entry != null) visual.entry = entry;
                moved = true;
            }
            if (visual.progress >= visual.ticksPerBlock) {
                visual.progress = visual.ticksPerBlock;
                if (++visual.idle > STALE_TICKS) { iterator.remove(); moved = true; }
            }
        }
        if (moved) index();
    }

    @SubscribeEvent static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    @SubscribeEvent static void levelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) clear();
    }

    private PipeVisuals() { }
}
