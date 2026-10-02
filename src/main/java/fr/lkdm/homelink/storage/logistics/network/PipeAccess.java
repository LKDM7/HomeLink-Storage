package fr.lkdm.homelink.storage.logistics.network;

import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homecore.api.device.DashboardDevice;
import fr.lkdm.homecore.api.network.NetworkMember;
import fr.lkdm.homecore.api.security.Permission;
import fr.lkdm.homecore.api.security.PermissionValidator;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.logistics.filter.PipeFaceConfig;
import fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlockEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side permission rules of the pipes. Identities always come from the authenticated
 * player, never from a packet. An attached circuit uses the Controller's HomeNetwork rights
 * (VIEW to look, CONFIGURE to change faces, CONTROL for pause and recovery); a circuit with no
 * Controller belongs to the pipe owner. Being able to place a pipe gives no right on anything.
 */
public final class PipeAccess {
    public enum Decision { ALLOWED, DENIED, CONFLICT, UNAVAILABLE }

    /** Most distance, in blocks from the pipe centre, for opening or editing a face. */
    public static final double REACH = 8.0;

    public static boolean near(ServerPlayer player, BlockPos pos) {
        return player.level() instanceof ServerLevel && !player.isSpectator() && player.isAlive()
                && player.distanceToSqr(pos.getCenter()) <= REACH * REACH;
    }

    public static Decision check(ServerPlayer player, StoragePipeBlockEntity pipe, Permission permission) {
        if (!(pipe.getLevel() instanceof ServerLevel level) || player.level() != level || player.isSpectator()) return Decision.DENIED;
        PipeComponent component = PipeNetworkManager.get(level).component(pipe.getBlockPos());
        if (component == null) return Decision.UNAVAILABLE;
        if (component.controllers.size() > 1) return Decision.CONFLICT;
        if (component.controllers.size() == 1) {
            var entry = component.controllers.entrySet().iterator().next();
            StorageBlockEntity controller = PipeNetworkManager.controllerAt(level, entry.getKey(), entry.getValue());
            if (controller == null) return Decision.UNAVAILABLE;
            return controller.permission(player, permission) ? Decision.ALLOWED : Decision.DENIED;
        }
        // An unloaded border could hide the Controller that should decide.
        if (component.incomplete) return Decision.UNAVAILABLE;
        UUID owner = pipe.owner();
        return owner != null && owner.equals(player.getUUID()) || player.hasPermissions(2) ? Decision.ALLOWED : Decision.DENIED;
    }

    /**
     * Whether a face armed earlier still counts for this Controller: armed for it (or, before the
     * circuit was attached, by its owner), in its current HomeNetwork, by a player who still may
     * configure it. Moving the Controller to another network or revoking the right needs a new
     * validation.
     */
    public static boolean armedFor(ServerLevel level, PipeFaceConfig config, StorageBlockEntity controller) {
        if (!config.armed() || config.armedBy() == null) return false;
        UUID armedController = config.armedController();
        if (armedController == null ? !config.armedBy().equals(controller.owner()) : !armedController.equals(controller.id())) return false;
        if (armedController != null && !java.util.Objects.equals(config.armedNetwork(), controller.networkId())) return false;
        return mayConfigure(level, controller, config.armedBy());
    }

    /** CONFIGURE right of a possibly offline player on the Controller's network. */
    static boolean mayConfigure(ServerLevel level, StorageBlockEntity controller, UUID player) {
        UUID network = controller.networkId();
        if (network == null) return player.equals(controller.owner());
        ServerPlayer online = level.getServer().getPlayerList().getPlayer(player);
        if (online != null) return DashboardAPI.hasPermission(online, network, Permission.CONFIGURE);
        // Offline: HomeCore's default role grants, read from the persistent network record.
        return DashboardAPI.networks(level.getServer()).getNetwork(network)
                .map(home -> new PermissionValidator().hasPermission(home, player, Permission.CONFIGURE)).orElse(false);
    }

    /** HomeCore machines by position, for one round of face checks. */
    static Map<BlockPos, DashboardDevice> devices(ServerLevel level) {
        Map<BlockPos, DashboardDevice> result = new HashMap<>();
        for (DashboardDevice device : DashboardAPI.devices(level.getServer()).getAll()) {
            if (device.dimension().filter(level.dimension()::equals).isEmpty()) continue;
            device.position().ifPresent(pos -> result.putIfAbsent(pos.immutable(), device));
        }
        return result;
    }

    /**
     * A capability is not a permission: a HomeCore machine must share the Controller's network or
     * owner. Plain containers expose no access list; claim protection mods are not consulted.
     */
    static boolean targetAllowed(ServerLevel level, Map<BlockPos, DashboardDevice> devices, BlockPos target, StorageBlockEntity controller) {
        @Nullable DashboardDevice device = devices.get(target);
        if (!level.isLoaded(target)) return false;
        var entity = LoadedBlocks.entity(level, target);
        if (entity instanceof StorageBlockEntity storage && storage.owner() != null) {
            if (storage.owner().equals(controller.owner())) return true;
            var bound = storage.controller();
            return bound != null && controller.networkId() != null && controller.networkId().equals(bound.networkId());
        }
        if (device == null && entity != null && DashboardAPI.providers().contains(entity.getType())) {
            // Known HomeCore machine not yet registered this tick: absence is not permission.
            try { device = DashboardAPI.providers().discover(entity).orElse(null); }
            catch (RuntimeException invalidProvider) { return false; }
            if (device == null) return false;
        }
        if (device == null || device.id().equals(controller.id())) return true;
        final DashboardDevice targetDevice = device;
        UUID network = controller.networkId();
        if (network != null && DashboardAPI.networks(level.getServer()).getNetwork(network)
                .map(home -> home.devices().contains(targetDevice.id())).orElse(false)) return true;
        if (device instanceof NetworkMember member) {
            if (network != null && member.homeNetwork().filter(network::equals).isPresent()) return true;
            return controller.owner() != null && member.owner().filter(controller.owner()::equals).isPresent();
        }
        return false;
    }

    private PipeAccess() { }

    static boolean autonomousAllowed(ServerLevel level, PipeComponent.Face face, UUID owner) {
        if (owner == null || !face.config.armed() || face.config.armedController() != null
                || !owner.equals(face.config.armedBy())) return false;
        if (!(LoadedBlocks.entity(level, face.endpoint.pipe()) instanceof StoragePipeBlockEntity pipe)
                || !owner.equals(pipe.owner())) return false;
        var entity=LoadedBlocks.entity(level, face.endpoint.target());
        if(entity instanceof StorageBlockEntity storage) return owner.equals(storage.owner());
        DashboardDevice device=devices(level).get(face.endpoint.target());
        if(device==null && entity!=null && DashboardAPI.providers().contains(entity.getType())) {
            try { device=DashboardAPI.providers().discover(entity).orElse(null); }
            catch(RuntimeException failure){return false;}
            if(device==null)return false;
        }
        return device==null || device instanceof NetworkMember member && member.owner().filter(owner::equals).isPresent();
    }
}
