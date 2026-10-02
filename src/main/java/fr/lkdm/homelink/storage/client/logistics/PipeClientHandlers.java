package fr.lkdm.homelink.storage.client.logistics;

import fr.lkdm.homelink.storage.logistics.sync.PipePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;

/** Client entry points of the pipe payloads; only ever reached on a physical client. */
public final class PipeClientHandlers {
    public static void start(PipePayloads.TransitStart payload) { PipeVisuals.start(payload); }

    public static void remove(PipePayloads.TransitRemove payload) { PipeVisuals.remove(payload); }

    public static void view(PipePayloads.FaceView payload) {
        if (payload.data() == null) return;
        var minecraft = Minecraft.getInstance();
        Direction side = Direction.from3DDataValue(payload.data().getByte("Face"));
        if (minecraft.screen instanceof PipeScreen screen && screen.shows(payload.pos(), side)) screen.accept(payload.data(), false);
        else minecraft.setScreen(new PipeScreen(payload.pos(), payload.data()));
    }

    public static void choice(PipePayloads.FaceChoice payload) {
        if (payload.data() != null) Minecraft.getInstance().setScreen(new PipeChoiceScreen(payload.pos(), payload.data()));
    }

    public static void recovery(PipePayloads.RecoveryView payload) {
        if (payload.data() == null) return;
        var minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof PipeRecoveryScreen screen && screen.controller().equals(payload.controller())) screen.accept(payload.data());
        else minecraft.setScreen(new PipeRecoveryScreen(minecraft.screen, payload.controller(), payload.data()));
    }

    private PipeClientHandlers() { }
}
