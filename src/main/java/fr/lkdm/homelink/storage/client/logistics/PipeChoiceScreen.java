package fr.lkdm.homelink.storage.client.logistics;

import fr.lkdm.homelink.storage.client.rendering.StorageTheme;
import fr.lkdm.homelink.storage.client.widget.StorageButton;
import fr.lkdm.homelink.storage.logistics.sync.PipePayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/** Small chooser shown when the centre of a pipe touching several containers is clicked. */
public final class PipeChoiceScreen extends Screen {
    private final BlockPos pos;
    private final CompoundTag data;
    private int left, top, frameWidth, frameHeight;

    public PipeChoiceScreen(BlockPos pos, CompoundTag data) {
        super(Component.translatable("screen.homelink_storage.pipe.choose"));
        this.pos = pos.immutable();
        this.data = data;
    }

    @Override protected void init() {
        var faces = data.getList("Faces", Tag.TAG_COMPOUND);
        frameWidth = Math.min(240, width - 8);
        frameHeight = 46 + faces.size() * 22 + 26;
        left = (width - frameWidth) / 2;
        top = Math.max(4, (height - frameHeight) / 2);
        for (int i = 0; i < faces.size(); i++) {
            CompoundTag face = faces.getCompound(i);
            Direction side = Direction.from3DDataValue(face.getByte("Face"));
            String name = face.getString("Name");
            if (name.isEmpty()) {
                ResourceLocation id = ResourceLocation.tryParse(face.getString("Block"));
                name = id == null ? "?" : BuiltInRegistries.BLOCK.get(id).getName().getString();
            }
            Component label = Component.translatable("direction.homelink_storage." + side.getSerializedName()).append(" — ").append(name);
            var button = StorageButton.builder(label, ignored -> PacketDistributor.sendToServer(
                    new PipePayloads.OpenFace(pos, (byte) side.get3DDataValue()))).bounds(left + 10, top + 38 + i * 22, frameWidth - 20, 18).build();
            button.setTooltip(Tooltip.create(label));
            addRenderableWidget(button);
        }
        addRenderableWidget(StorageButton.builder(Component.translatable("screen.homelink_storage.close"), ignored -> onClose())
                .bounds(left + frameWidth - 90, top + frameHeight - 24, 80, 18).build());
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        StorageTheme.frame(graphics, left, top, frameWidth, frameHeight);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, font.plainSubstrByWidth(title.getString(), frameWidth - 24), left + 12, top + 11, StorageTheme.TEXT, false);
    }

    @Override public boolean isPauseScreen() { return false; }
}
