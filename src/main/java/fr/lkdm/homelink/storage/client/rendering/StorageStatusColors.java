package fr.lkdm.homelink.storage.client.rendering;

import fr.lkdm.homecore.api.client.ui.HomeLinkStatusTone;
import fr.lkdm.homecore.api.client.ui.HomeLinkTheme;

/** Storage domain states mapped to the shared UI tones. */
public final class StorageStatusColors {
    private StorageStatusColors() { }

    public static int color(String status) {
        HomeLinkStatusTone tone = switch (status) {
            case "ONLINE", "CONNECTED" -> HomeLinkStatusTone.ONLINE;
            case "WARNING", "UNLOADED" -> HomeLinkStatusTone.WARNING;
            case "OFFLINE", "ERROR", "INVALID", "INVENTORY_MISSING" -> HomeLinkStatusTone.OFFLINE;
            default -> HomeLinkStatusTone.NEUTRAL;
        };
        return HomeLinkTheme.statusColor(tone);
    }
}
