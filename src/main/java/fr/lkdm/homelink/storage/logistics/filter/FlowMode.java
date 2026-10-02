package fr.lkdm.homelink.storage.logistics.filter;

import fr.lkdm.homecore.api.item.ItemPortType;
import org.jetbrains.annotations.Nullable;

/**
 * Direction of one pipe face, always seen from the container it touches.
 * INSERT: pipe to container, the container receives. EXTRACT: container to pipe, objects leave it.
 */
public enum FlowMode {
    INSERT,
    EXTRACT;

    /**
     * Whether a HomeCore port allows this direction. A plain item handler (no port) gives no
     * absolute answer: its slots decide item by item when the transfer is simulated.
     */
    public boolean allowedBy(@Nullable ItemPortType port) {
        if (port == null) return true;
        return this == INSERT ? port.canReceive() : port.canSend();
    }

    public FlowMode toggled() { return this == INSERT ? EXTRACT : INSERT; }

    public static @Nullable FlowMode byId(int id) {
        return id >= 0 && id < values().length ? values()[id] : null;
    }
}
