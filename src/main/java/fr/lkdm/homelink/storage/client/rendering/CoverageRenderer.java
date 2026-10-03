package fr.lkdm.homelink.storage.client.rendering;

import fr.lkdm.homecore.api.client.ui.HomeLinkTheme;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.lkdm.homelink.storage.HomeLinkStorage;
import fr.lkdm.homelink.storage.network.CoverageState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Local outline of the chunk columns a Link or Repeater covers. The clicked node is drawn
 * in copper, the rest of its network in green, and offline nodes in red. Nothing is
 * requested from the world: the chunk bounds are pure arithmetic.
 */
@EventBusSubscriber(modid = HomeLinkStorage.MOD_ID, value = Dist.CLIENT)
public final class CoverageRenderer {
    private static final double MAX_DISTANCE = 160;
    /** Vertical extent of the fence around the viewer, in blocks below and above the feet. */
    private static final int BELOW = 6, ABOVE = 18;

    private CoverageRenderer() {}

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft client = Minecraft.getInstance();
        CoverageState.View view = CoverageState.current();
        if (view == null || client.level == null || client.player == null) return;
        if (!client.level.dimension().location().toString().equals(view.dimension())) { CoverageState.clear(); return; }
        Vec3 camera = event.getCamera().getPosition();
        double time = client.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float pulse = 0.75F + 0.25F * (float) Math.sin(time * 0.12);
        double feet = Math.floor(client.player.getY());
        double low = Math.max(client.level.getMinBuildHeight(), feet - BELOW);
        double high = Math.min(client.level.getMaxBuildHeight(), feet + ABOVE);
        PoseStack pose = event.getPoseStack();
        var buffers = client.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        for (CoverageState.Chunk chunk : view.chunks()) {
            double x0 = chunk.x() << 4, z0 = chunk.z() << 4, x1 = x0 + 16, z1 = z0 + 16;
            double dx = Mth.clamp(camera.x, x0, x1) - camera.x, dz = Mth.clamp(camera.z, z0, z1) - camera.z;
            if (dx * dx + dz * dz > MAX_DISTANCE * MAX_DISTANCE) continue;
            int color = chunk.focus() ? (chunk.active() ? HomeLinkTheme.ACCENT : HomeLinkTheme.OFFLINE)
                    : chunk.active() ? HomeLinkTheme.ONLINE : HomeLinkTheme.OFFLINE;
            float alpha = chunk.focus() ? pulse : 0.7F;
            // Fence: a bright rail at the viewer's feet, fainter rails every eight blocks, corner posts.
            double ground = Math.max(low, feet) + 0.05;
            rectangle(pose, lines, x0, z0, x1, z1, ground, color, alpha);
            for (double y = ground + 8; y < high; y += 8) rectangle(pose, lines, x0, z0, x1, z1, y, color, alpha * 0.45F);
            for (double[] corner : new double[][] {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}})
                line(pose, lines, corner[0], low, corner[1], corner[0], high, corner[1], color, alpha);
            // Ground grid every four blocks on the viewer's level, so the covered area reads at a glance.
            for (int step = 4; step < 16; step += 4) {
                line(pose, lines, x0 + step, ground, z0, x0 + step, ground, z1, color, alpha * 0.3F);
                line(pose, lines, x0, ground, z0 + step, x1, ground, z0 + step, color, alpha * 0.3F);
            }
            box(pose, lines, chunk.node(), color, chunk.focus() ? pulse : 0.8F);
        }
        // Translucent curtains on the chunk borders, readable even where one-pixel lines are not.
        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        double ground = Math.max(low, feet) + 0.05;
        for (CoverageState.Chunk chunk : view.chunks()) {
            float x0 = chunk.x() << 4, z0 = chunk.z() << 4, x1 = x0 + 16, z1 = z0 + 16;
            double dx = Mth.clamp(camera.x, x0, x1) - camera.x, dz = Mth.clamp(camera.z, z0, z1) - camera.z;
            if (dx * dx + dz * dz > MAX_DISTANCE * MAX_DISTANCE) continue;
            int color = chunk.focus() ? (chunk.active() ? HomeLinkTheme.ACCENT : HomeLinkTheme.OFFLINE)
                    : chunk.active() ? HomeLinkTheme.ONLINE : HomeLinkTheme.OFFLINE;
            float alpha = (chunk.focus() ? 0.35F : 0.2F) * pulse;
            float y0 = (float) ground, y1 = (float) ground + 3;
            wall(pose, quads, x0, z0, x1, z0, y0, y1, color, alpha);
            wall(pose, quads, x1, z0, x1, z1, y0, y1, color, alpha);
            wall(pose, quads, x1, z1, x0, z1, y0, y1, color, alpha);
            wall(pose, quads, x0, z1, x0, z0, y0, y1, color, alpha);
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
        buffers.endBatch(RenderType.debugQuads());
    }

    /** A vertical strip, opaque at the bottom and fading out at the top. */
    private static void wall(PoseStack pose, VertexConsumer quads, float xa, float za, float xb, float zb,
                             float y0, float y1, int color, float alpha) {
        int r = color >> 16 & 0xFF, g = color >> 8 & 0xFF, b = color & 0xFF, a = (int) (Mth.clamp(alpha, 0, 1) * 255);
        var last = pose.last();
        quads.addVertex(last, xa, y0, za).setColor(r, g, b, a);
        quads.addVertex(last, xb, y0, zb).setColor(r, g, b, a);
        quads.addVertex(last, xb, y1, zb).setColor(r, g, b, 0);
        quads.addVertex(last, xa, y1, za).setColor(r, g, b, 0);
    }

    private static void box(PoseStack pose, VertexConsumer lines, BlockPos node, int color, float alpha) {
        double x0 = node.getX() - 0.03, y0 = node.getY() - 0.03, z0 = node.getZ() - 0.03;
        double x1 = node.getX() + 1.03, y1 = node.getY() + 1.03, z1 = node.getZ() + 1.03;
        rectangle(pose, lines, x0, z0, x1, z1, y0, color, alpha);
        rectangle(pose, lines, x0, z0, x1, z1, y1, color, alpha);
        for (double[] corner : new double[][] {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}})
            line(pose, lines, corner[0], y0, corner[1], corner[0], y1, corner[1], color, alpha);
    }

    private static void rectangle(PoseStack pose, VertexConsumer lines, double x0, double z0, double x1, double z1, double y, int color, float alpha) {
        line(pose, lines, x0, y, z0, x1, y, z0, color, alpha);
        line(pose, lines, x1, y, z0, x1, y, z1, color, alpha);
        line(pose, lines, x1, y, z1, x0, y, z1, color, alpha);
        line(pose, lines, x0, y, z1, x0, y, z0, color, alpha);
    }

    private static void line(PoseStack pose, VertexConsumer lines, double x0, double y0, double z0,
                             double x1, double y1, double z1, int color, float alpha) {
        float nx = (float) (x1 - x0), ny = (float) (y1 - y0), nz = (float) (z1 - z0);
        float length = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        if (length == 0) return;
        nx /= length; ny /= length; nz /= length;
        int r = color >> 16 & 0xFF, g = color >> 8 & 0xFF, b = color & 0xFF, a = (int) (Mth.clamp(alpha, 0, 1) * 255);
        var last = pose.last();
        lines.addVertex(last, (float) x0, (float) y0, (float) z0).setColor(r, g, b, a).setNormal(last, nx, ny, nz);
        lines.addVertex(last, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a).setNormal(last, nx, ny, nz);
    }
}
