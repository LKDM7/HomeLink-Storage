package fr.lkdm.homelink.storage.network;

import fr.lkdm.homecore.api.security.RateLimiter;
import fr.lkdm.homelink.storage.HomeLinkStorage;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Reuses HomeCore's bounded security primitive across menu changes and reconnects. */
@EventBusSubscriber(modid = HomeLinkStorage.MOD_ID)
public final class StorageRequestBudget {
    private static final Map<MinecraftServer, RateLimiter> SERVERS = new WeakHashMap<>();
    public static synchronized boolean acquire(ServerPlayer player) {
        return SERVERS.computeIfAbsent(player.serverLevel().getServer(), ignored -> new RateLimiter()).tryAcquire(player.getUUID());
    }
    @SubscribeEvent public static synchronized void stopped(ServerStoppedEvent event) {
        RateLimiter limiter = SERVERS.remove(event.getServer());
        if (limiter != null) limiter.clear();
    }
    private StorageRequestBudget() {}
}
