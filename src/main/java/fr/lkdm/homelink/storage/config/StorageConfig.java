package fr.lkdm.homelink.storage.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-owned limits; clients receive the server config through NeoForge. */
public final class StorageConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue VERIFY_INTERVAL;
    public static final ModConfigSpec.IntValue RESCAN_INTERVAL;
    public static final ModConfigSpec.IntValue SCANS_PER_TICK;
    public static final ModConfigSpec.IntValue MAX_INVENTORIES;
    public static final ModConfigSpec.IntValue MAX_COVERAGE_NODES;
    public static final ModConfigSpec.IntValue MAX_DISCOVERY_CANDIDATES;
    public static final ModConfigSpec.IntValue MAX_SLOTS;
    public static final ModConfigSpec.IntValue MAX_VARIANTS;
    public static final ModConfigSpec.IntValue LOCATE_DURATION;
    public static final ModConfigSpec.IntValue NETWORK_ROWS;
    public static final ModConfigSpec.IntValue MAX_PACKET_BYTES;
    public static final ModConfigSpec.IntValue MAX_COMPONENT_BYTES;
    public static final ModConfigSpec.DoubleValue WARNING_THRESHOLD;
    public static final ModConfigSpec.DoubleValue FULL_THRESHOLD;
    public static final ModConfigSpec.DoubleValue HYSTERESIS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        VERIFY_INTERVAL = integer(builder, "verify_interval", "Ticks between connection checks.", 40, 5, 1200);
        RESCAN_INTERVAL = integer(builder, "rescan_interval", "Ticks between inventory consistency scans.", 100, 20, 2400);
        SCANS_PER_TICK = integer(builder, "scans_per_tick", "Maximum inventory scans per controller per tick.", 4, 1, 16);
        MAX_INVENTORIES = integer(builder, "max_inventories", "Maximum discovered inventories per controller.", 256, 1, 512);
        MAX_COVERAGE_NODES = integer(builder, "max_coverage_nodes", "Maximum links and repeaters per controller.", 64, 1, 256);
        MAX_DISCOVERY_CANDIDATES = integer(builder, "max_discovery_candidates", "Maximum block entities inspected during one chunk discovery pass.", 4096, 256, 65536);
        MAX_SLOTS = integer(builder, "max_slots", "Maximum slots accepted from one inventory capability.", 4096, 27, 16384);
        MAX_VARIANTS = integer(builder, "max_variants", "Maximum distinct item/component variants per index.", 16384, 256, 32768);
        LOCATE_DURATION = integer(builder, "locate_duration", "Duration of the client marker in ticks.", 200, 20, 1200);
        NETWORK_ROWS = integer(builder, "network_rows", "Maximum item rows per synchronization fragment.", 16, 1, 64);
        MAX_PACKET_BYTES = integer(builder, "max_packet_bytes", "Maximum encoded synchronization fragment size.", 65536, 32768, 262144);
        MAX_COMPONENT_BYTES = integer(builder, "max_component_bytes", "Maximum encoded components per synchronized item.", 8192, 1024, 8192);
        WARNING_THRESHOLD = decimal(builder, "warning_threshold", "Occupied slot percentage that triggers warning.", 90, 1, 100);
        FULL_THRESHOLD = decimal(builder, "full_threshold", "Occupied slot percentage that triggers full event.", 100, 1, 100);
        HYSTERESIS = decimal(builder, "hysteresis", "Percentage points below a threshold required to rearm events.", 5, 1, 25);
        SPEC = builder.build();
    }

    private StorageConfig() {}

    private static ModConfigSpec.IntValue integer(ModConfigSpec.Builder builder, String name, String comment,
                                                  int initial, int min, int max) {
        return builder.comment(comment).translation("config.homelink_storage." + name).defineInRange(name, initial, min, max);
    }

    private static ModConfigSpec.DoubleValue decimal(ModConfigSpec.Builder builder, String name, String comment,
                                                     double initial, double min, double max) {
        return builder.comment(comment).translation("config.homelink_storage." + name).defineInRange(name, initial, min, max);
    }

    public static int maxSlots() { return SPEC.isLoaded() ? MAX_SLOTS.get() : 4096; }
    public static int maxVariants() { return SPEC.isLoaded() ? MAX_VARIANTS.get() : 16384; }
}
