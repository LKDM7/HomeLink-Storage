package fr.lkdm.homelink.storage.logistics.pipe;

import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/** What one side of a pipe visibly joins. Visual only: transport always re-checks the live world. */
public enum PipeConnection implements StringRepresentable {
    NONE,
    PIPE,
    CONTROLLER,
    CONTAINER;

    @Override public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }
}
