package fr.lkdm.homelink.storage.logistics.filter;

import org.jetbrains.annotations.Nullable;

/** WHITELIST: only the selected item types pass. BLACKLIST: every type except the selected ones. */
public enum FilterMode {
    WHITELIST,
    BLACKLIST;

    public FilterMode toggled() { return this == WHITELIST ? BLACKLIST : WHITELIST; }

    public static @Nullable FilterMode byId(int id) {
        return id >= 0 && id < values().length ? values()[id] : null;
    }
}
