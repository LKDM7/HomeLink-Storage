package fr.lkdm.homelink.storage.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Display preferences of one player. They never change what the server transports or how fast. */
public final class StorageClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue SHOW_PIPE_ITEMS;
    public static final ModConfigSpec.IntValue PIPE_ITEM_RENDER_DISTANCE;
    public static final ModConfigSpec.IntValue PIPE_MAX_RENDERED;
    public static final ModConfigSpec.BooleanValue PIPE_SUBTLE_EMISSION;
    public static final ModConfigSpec.BooleanValue REDUCED_PIPE_ANIMATIONS;
    public static final ModConfigSpec.BooleanValue PIPE_SOUNDS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("pipes");
        SHOW_PIPE_ITEMS = flag(builder, "show_pipe_items", "Draw the objects travelling inside Storage Pipes.", true);
        PIPE_ITEM_RENDER_DISTANCE = builder.comment("Blocks within which pipe contents are drawn.")
                .translation("config.homelink_storage.pipe_item_render_distance").defineInRange("pipe_item_render_distance", 32, 4, 128);
        PIPE_MAX_RENDERED = builder.comment("Most travelling objects kept for display.")
                .translation("config.homelink_storage.pipe_max_rendered_transfers").defineInRange("pipe_max_rendered_transfers", 128, 0, 1024);
        PIPE_SUBTLE_EMISSION = flag(builder, "pipe_subtle_emission", "Let the pipe status lights glow.", true);
        REDUCED_PIPE_ANIMATIONS = flag(builder, "reduced_pipe_animations", "No pulsing lights; objects still move at the real server speed.", false);
        PIPE_SOUNDS = flag(builder, "pipe_sounds", "Play a quiet sound when a cargo reaches its container.", true);
        builder.pop();
        SPEC = builder.build();
    }

    private static ModConfigSpec.BooleanValue flag(ModConfigSpec.Builder builder, String name, String comment, boolean initial) {
        return builder.comment(comment).translation("config.homelink_storage." + name).define(name, initial);
    }

    public static boolean get(ModConfigSpec.BooleanValue value) { return SPEC.isLoaded() ? value.get() : value.getDefault(); }
    public static int get(ModConfigSpec.IntValue value) { return SPEC.isLoaded() ? value.get() : value.getDefault(); }

    private StorageClientConfig() { }
}
