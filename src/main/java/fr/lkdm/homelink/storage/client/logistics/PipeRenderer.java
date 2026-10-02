package fr.lkdm.homelink.storage.client.logistics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.lkdm.homelink.storage.config.StorageClientConfig;
import fr.lkdm.homelink.storage.logistics.network.PipeStatus;
import fr.lkdm.homelink.storage.logistics.pipe.PipeConnection;
import fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlock;
import fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the cargo pictures inside a pipe and its small status light. Rendered with block
 * entities, before translucent terrain, so the glass blends over the objects. The light is an
 * emissive quad: no block light is recomputed and no block state changes to animate it.
 */
public final class PipeRenderer implements BlockEntityRenderer<StoragePipeBlockEntity> {
    private static final ResourceLocation LAMP = ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");
    private static final float LAMP_HALF_WIDTH = 0.75F / 16;
    private static final float LAMP_HALF_HEIGHT = 0.25F / 16;
    private static final float CORE_OUT = (float) (StoragePipeBlock.CORE_MAX / 16.0) + 0.002F;

    public PipeRenderer(BlockEntityRendererProvider.Context context) { }

    @Override public void render(StoragePipeBlockEntity pipe, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var minecraft = Minecraft.getInstance();
        boolean carrying = PipeVisuals.carrying(pipe.getBlockPos());
        lamp(pipe, pose, buffers, light, carrying, partialTick);
        if (!carrying || !StorageClientConfig.get(StorageClientConfig.SHOW_PIPE_ITEMS)) return;
        boolean reduced = StorageClientConfig.get(StorageClientConfig.REDUCED_PIPE_ANIMATIONS);
        var renderer = minecraft.getItemRenderer();
        for (PipeVisuals.Visual visual : PipeVisuals.at(pipe.getBlockPos())) {
            Vec3 at = visual.local(partialTick);
            pose.pushPose();
            pose.translate(at.x, at.y - 0.12, at.z);
            Direction heading = visual.heading();
            if (heading.getAxis().isHorizontal()) pose.mulPose(Axis.YP.rotationDegrees(-heading.toYRot()));
            if (!reduced && pipe.getLevel() != null)
                pose.mulPose(Axis.YP.rotationDegrees((pipe.getLevel().getGameTime() % 360 + partialTick) * 2F));
            var model = renderer.getModel(visual.display(), pipe.getLevel(), null, 0);
            // Ground transforms draw blocks at half the size of flat items: even both out to about 4.5 pixels.
            float scale = model.isGui3d() ? 0.72F : 0.45F;
            pose.scale(scale, scale, scale);
            renderer.renderStatic(visual.display(), ItemDisplayContext.GROUND, light, OverlayTexture.NO_OVERLAY, pose, buffers,
                    pipe.getLevel(), visual.id().hashCode());
            pose.popPose();
        }
    }

    private void lamp(StoragePipeBlockEntity pipe, PoseStack pose, MultiBufferSource buffers, int light, boolean carrying, float partialTick) {
        PipeStatus.Lamp lamp = pipe.lamp();
        boolean emissive = StorageClientConfig.get(StorageClientConfig.PIPE_SUBTLE_EMISSION) && lamp != PipeStatus.Lamp.OFF;
        int rgb;
        float alpha;
        switch (lamp) {
            case WARNING -> { rgb = 0xE0A043; alpha = 0.95F; }
            case ACTIVE -> { rgb = carrying ? 0xD2B181 : 0xA1BD92; alpha = 0.75F; }
            default -> { rgb = 0x4A4D50; alpha = 0.9F; }
        }
        if (carrying && lamp == PipeStatus.Lamp.ACTIVE && !StorageClientConfig.get(StorageClientConfig.REDUCED_PIPE_ANIMATIONS) && pipe.getLevel() != null) {
            // Transit: a slow, subtle pulse.
            float phase = (pipe.getLevel().getGameTime() % 40 + partialTick) / 40F;
            alpha *= 0.75F + 0.25F * (float) Math.sin(phase * Math.PI * 2);
        }
        VertexConsumer consumer = buffers.getBuffer(emissive ? RenderType.entityTranslucentEmissive(LAMP) : RenderType.entityTranslucent(LAMP));
        int packed = emissive ? LightTexture.FULL_BRIGHT : light;
        var state = pipe.getBlockState();
        Direction.Axis tubeAxis = StoragePipeBlock.straightAxis(state);
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF, a = (int) (Math.min(1F, alpha) * 255);
        PoseStack.Pose last = pose.last();
        for (Direction side : Direction.values()) {
            if (StoragePipeBlock.side(state, side) != PipeConnection.NONE) continue;
            quad(consumer, last, side, tubeAxis, r, g, b, a, packed);
        }
    }

    /** Small indicator strip in the recessed pad on a closed side of the junction. */
    private static void quad(VertexConsumer consumer, PoseStack.Pose pose, Direction side, Direction.Axis tubeAxis, int r, int g, int b, int a, int light) {
        float c = 0.5F, out = CORE_OUT - 0.5F;
        float w = LAMP_HALF_WIDTH, h = LAMP_HALF_HEIGHT;
        float nx = side.getStepX(), ny = side.getStepY(), nz = side.getStepZ();
        // Two in-plane axes of the side.
        Direction.Axis axis = side.getAxis();
        float[][] corners = new float[4][3];
        float[][] offsets = {{-w, -h}, {w, -h}, {w, h}, {-w, h}};
        for (int i = 0; i < 4; i++) {
            float u = offsets[i][0], v = offsets[i][1];
            float x = c + nx * out, y = c + ny * out, z = c + nz * out;
            switch (axis) {
                case X -> { y += u; z += v; }
                case Y -> { x += u; z += v; }
                case Z -> { x += u; y += v; }
            }
            if (tubeAxis != null) {
                // The straight model's pad sits beside the near collar, not in mid-glass.
                float[] at = {c + nx * out, c + ny * out, c + nz * out};
                for (Direction.Axis plane : Direction.Axis.values())
                    if (plane != axis && plane != tubeAxis) at[plane.ordinal()] += u;
                at[tubeAxis.ordinal()] = (tubeAxis == Direction.Axis.Z ? 1F : 15F) / 16 + v;
                x = at[0]; y = at[1]; z = at[2];
            }
            corners[i] = new float[]{x, y, z};
        }
        // Both windings, so the light shows whatever the render type culls.
        int[] order = {0, 1, 2, 3, 3, 2, 1, 0};
        float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int i : order)
            consumer.addVertex(pose, corners[i][0], corners[i][1], corners[i][2]).setColor(r, g, b, a).setUv(uv[i][0], uv[i][1])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
    }

    @Override public int getViewDistance() { return StorageClientConfig.get(StorageClientConfig.PIPE_ITEM_RENDER_DISTANCE); }

    @Override public AABB getRenderBoundingBox(StoragePipeBlockEntity pipe) { return new AABB(pipe.getBlockPos()); }
}
