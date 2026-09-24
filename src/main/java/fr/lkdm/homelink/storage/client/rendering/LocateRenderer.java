package fr.lkdm.homelink.storage.client.rendering;

import fr.lkdm.homelink.storage.HomeLinkStorage;
import fr.lkdm.homelink.storage.network.LocateState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** Local particles and a through-wall HUD guide; never requests a chunk or changes a block. */
@EventBusSubscriber(modid = HomeLinkStorage.MOD_ID, value = Dist.CLIENT)
public final class LocateRenderer {
    private static int particleTick;

    private LocateRenderer() {}

    private static LocateState.Target active(Minecraft minecraft) {
        LocateState.Target target = LocateState.current();
        if (target == null) return null;
        if (minecraft.level == null || minecraft.player == null
                || !minecraft.level.dimension().location().toString().equals(target.dimension())) {
            LocateState.clear();
            return null;
        }
        return target;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocateState.Target target = active(minecraft);
        if (target == null || minecraft.isPaused() || ++particleTick % 4 != 0) return;
        BlockPos pos = target.position();
        if (!minecraft.level.hasChunkAt(pos)
                || minecraft.player.distanceToSqr(pos.getCenter()) > 96 * 96) return;
        // Eight short-lived points per four ticks make a pillar and a rim.
        // Particles exist only in this client's level, so other players see nothing.
        for (int step = 0; step < 4; step++) {
            double angle = (particleTick * 0.13) + step * Math.PI / 2;
            minecraft.level.addParticle(ParticleTypes.END_ROD,
                    pos.getX() + 0.5 + Math.cos(angle) * 0.7,
                    pos.getY() + 1.05,
                    pos.getZ() + 0.5 + Math.sin(angle) * 0.7,
                    0, 0.015, 0);
            minecraft.level.addParticle(ParticleTypes.END_ROD,
                    pos.getX() + 0.5, pos.getY() + 1.3 + step * 0.6, pos.getZ() + 0.5,
                    0, 0.005, 0);
        }
    }

    @SubscribeEvent
    public static void hud(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocateState.Target target = active(minecraft);
        if (target == null || minecraft.options.hideGui) return;
        BlockPos pos = target.position();
        double dx = pos.getX() + 0.5 - minecraft.player.getX();
        double dz = pos.getZ() + 0.5 - minecraft.player.getZ();
        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90;
        double relative = Mth.wrapDegrees(yaw - minecraft.player.getYRot());
        String direction = Math.abs(relative) < 22.5 ? "ahead"
                : Math.abs(relative) > 157.5 ? "behind" : relative > 0 ? "right" : "left";
        int distance = (int) Math.ceil(Math.sqrt(minecraft.player.distanceToSqr(pos.getCenter())));
        int elevation = pos.getY() - minecraft.player.blockPosition().getY();
        Component heading = Component.translatable("locate.homelink_storage.guide", distance,
                Component.translatable("locate.homelink_storage." + direction));
        Component coordinates = Component.translatable("locate.homelink_storage.coordinates",
                pos.getX(), pos.getY(), pos.getZ(), elevation > 0 ? "+" + elevation : Integer.toString(elevation));
        GuiGraphics graphics = event.getGuiGraphics();
        int width = Math.max(minecraft.font.width(heading), minecraft.font.width(coordinates)) + 20;
        int x = (graphics.guiWidth() - width) / 2;
        int y = 30;
        graphics.fill(x, y, x + width, y + 34, 0xD9102028);
        graphics.fill(x, y, x + width, y + 2, 0xFF86EAC6);
        graphics.drawCenteredString(minecraft.font, heading, graphics.guiWidth() / 2, y + 7, 0x86EAC6);
        graphics.drawCenteredString(minecraft.font, coordinates, graphics.guiWidth() / 2, y + 20, 0xE3F2EF);
    }
}
