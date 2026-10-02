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
    public static final ModConfigSpec.IntValue CONTROLLER_ENERGY;
    public static final ModConfigSpec.IntValue INVENTORY_ENERGY;
    public static final ModConfigSpec.IntValue DEPOSIT_ENERGY;
    public static final ModConfigSpec.BooleanValue PIPES_ENABLED;
    public static final ModConfigSpec.IntValue PIPE_MAX_NODES;
    public static final ModConfigSpec.IntValue PIPE_MAX_ENDPOINTS;
    public static final ModConfigSpec.IntValue PIPE_MAX_ROUTE;
    public static final ModConfigSpec.IntValue PIPE_MAX_IN_FLIGHT;
    public static final ModConfigSpec.IntValue PIPE_ITEMS_PER_TRANSFER;
    public static final ModConfigSpec.IntValue PIPE_DISPATCH_INTERVAL;
    public static final ModConfigSpec.IntValue PIPE_DEPARTURES;
    public static final ModConfigSpec.IntValue PIPE_TRAVEL_TICKS;
    public static final ModConfigSpec.IntValue PIPE_ENERGY;
    public static final ModConfigSpec.IntValue PIPE_SLOT_CHECKS;
    public static final ModConfigSpec.IntValue PIPE_GRAPH_BUDGET;
    public static final ModConfigSpec.IntValue PIPE_MAX_FILTER;
    public static final ModConfigSpec.IntValue PIPE_RETRY_INTERVAL;
    public static final ModConfigSpec.IntValue PIPE_RETURN_DELAY;

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
        // HomeLink Energy (HE) per minute (1200 ticks). For scale: a Solar Panel I averages 100 HE per minute over a day.
        CONTROLLER_ENERGY = integer(builder, "controller_energy", "HE per minute a Controller uses to run its network (Terminals, Links, Repeaters). Without it the network stops. 0 = free.", 40, 0, 1_000_000);
        INVENTORY_ENERGY = integer(builder, "inventory_energy", "Extra HE per minute for every inventory connected to the Controller.", 2, 0, 1_000_000);
        DEPOSIT_ENERGY = integer(builder, "deposit_energy", "HE per minute a connected Deposit uses to sort; without it sorting stops. 0 = free.", 20, 0, 1_000_000);
        builder.comment("Storage Pipes: physical item transport coordinated by the Controller.").push("pipes");
        PIPES_ENABLED = builder.comment("Whether Storage Pipes start new transfers. Cargo already in transit is kept either way.")
                .translation("config.homelink_storage.pipes_enabled").define("pipes_enabled", true);
        PIPE_MAX_NODES = integer(builder, "pipe_max_nodes_per_component", "Largest pipe circuit; a bigger one stops dispatching.", 4096, 16, 16384);
        PIPE_MAX_ENDPOINTS = integer(builder, "pipe_max_endpoints_per_component", "Most container faces one circuit may serve.", 256, 1, 1024);
        PIPE_MAX_ROUTE = integer(builder, "pipe_max_route_length", "Longest route in pipe segments.", 512, 1, 4096);
        PIPE_MAX_IN_FLIGHT = integer(builder, "pipe_max_transfers_in_flight_per_controller", "Cargo a Controller may hold in its pipes at once; a full queue only blocks new departures.", 64, 1, 1024);
        PIPE_ITEMS_PER_TRANSFER = integer(builder, "pipe_items_per_transfer", "Most objects in one cargo, never above the stack size.", 16, 1, 64);
        PIPE_DISPATCH_INTERVAL = integer(builder, "pipe_dispatch_interval_ticks", "Ticks between dispatch rounds of one Controller.", 20, 1, 1200);
        PIPE_DEPARTURES = integer(builder, "pipe_departures_per_interval_per_controller", "Cargo departures per round, shared by every circuit of a Controller.", 1, 1, 64);
        PIPE_TRAVEL_TICKS = integer(builder, "pipe_travel_ticks_per_block", "Ticks a cargo needs to cross one pipe segment (8 = 2.5 blocks per second).", 8, 1, 200);
        PIPE_ENERGY = integer(builder, "pipe_energy_per_departure", "HE the Controller pays for each departing cargo. 0 = free (tests, server choice).", 1, 0, 1_000_000);
        PIPE_SLOT_CHECKS = integer(builder, "pipe_slot_checks_per_tick_per_controller", "Source slots examined in one dispatch round.", 128, 1, 4096);
        PIPE_GRAPH_BUDGET = integer(builder, "pipe_graph_work_budget_per_tick", "Pipe segments explored per tick and per dimension while circuits are rebuilt.", 512, 16, 65536);
        PIPE_MAX_FILTER = integer(builder, "pipe_max_filter_entries_per_face", "Most item types in one face filter.", 256, 1, 1024);
        PIPE_RETRY_INTERVAL = integer(builder, "pipe_retry_interval_ticks", "Ticks between two attempts of a blocked cargo.", 20, 1, 1200);
        PIPE_RETURN_DELAY = integer(builder, "pipe_return_delay_ticks", "Ticks a blocked cargo waits before trying to go back to its source.", 200, 20, 72000);
        builder.pop();
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

    /** Pipe limits with their defaults when the server config is not loaded (unit tests). */
    public static int pipe(ModConfigSpec.IntValue value) { return SPEC.isLoaded() ? value.get() : value.getDefault(); }
    public static boolean pipesEnabled() { return !SPEC.isLoaded() || PIPES_ENABLED.get(); }
}
