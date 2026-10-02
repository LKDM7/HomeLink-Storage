package fr.lkdm.homelink.storage.logistics.network;

import java.util.Locale;

/** Readable state of a circuit, a face or a cargo. The lamp groups them into four pipe lights. */
public enum PipeStatus {
    NO_CONTROLLER(Lamp.OFF),
    CONTROLLER_OFFLINE(Lamp.OFF),
    CONTROLLER_CONFLICT(Lamp.WARNING),
    NEEDS_CONFIGURATION(Lamp.ACTIVE),
    NO_POWER(Lamp.OFF),
    IDLE(Lamp.ACTIVE),
    TRANSFERRING(Lamp.ACTIVE),
    NO_ROUTE(Lamp.WARNING),
    DESTINATION_FULL(Lamp.WARNING),
    CHUNK_UNLOADED(Lamp.WARNING),
    PORT_REJECTED(Lamp.WARNING),
    ACCESS_DENIED(Lamp.WARNING),
    RECOVERY_REQUIRED(Lamp.WARNING),
    PAUSED(Lamp.OFF),
    /** Too many pipes or container faces for the configured limits. */
    LIMIT_EXCEEDED(Lamp.WARNING),
    /** The same physical container is both a source and a destination of the circuit. */
    LOOP_CONFLICT(Lamp.WARNING),
    /** The circuit is being rebuilt after a change; nothing departs meanwhile. */
    UPDATING(Lamp.ACTIVE);

    /** Pipe light: soft when the network works, amber when blocked, dimmed when out of service. */
    public enum Lamp { OFF, ACTIVE, WARNING }

    private final Lamp lamp;

    PipeStatus(Lamp lamp) { this.lamp = lamp; }

    public Lamp lamp() { return lamp; }

    public String key() { return "pipe_status.homelink_storage." + name().toLowerCase(Locale.ROOT); }

    public static PipeStatus byName(String name) {
        for (PipeStatus status : values()) if (status.name().equals(name)) return status;
        return NO_CONTROLLER;
    }
}
