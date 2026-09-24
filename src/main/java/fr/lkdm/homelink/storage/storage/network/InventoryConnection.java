package fr.lkdm.homelink.storage.storage.network;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter;

/** Discovered logical inventory. Its identity is independent from its coverage node. */
public final class InventoryConnection {
    /** Stable inventory identifier used by menus and commands. */
    public final UUID linkId;
    /** Link or repeater that most recently provided coverage. */
    public UUID sourceId;
    /** Position of the coverage node. Kept for diagnostics and unloaded state. */
    public BlockPos linkPos;
    /** Canonical logical inventory identity (for example the first half of a double chest). */
    public BlockPos inventoryPos;
    /** Actual block position used to query the capability. */
    public BlockPos accessPos;
    public String name = "";
    public String zone = "misc";
    public StorageInventoryAdapter.Status status = StorageInventoryAdapter.Status.UNLOADED;
    public InventoryConnection(UUID inventoryId, UUID sourceId, BlockPos sourcePos, BlockPos inventoryPos, BlockPos accessPos) {
        this.linkId = inventoryId;
        this.sourceId = sourceId;
        this.linkPos = sourcePos.immutable();
        this.inventoryPos = inventoryPos.immutable();
        this.accessPos = accessPos.immutable();
    }

    /** Compatibility constructor used by bounded packet fixtures. */
    public InventoryConnection(UUID inventoryId, BlockPos sourcePos) {
        this(inventoryId, inventoryId, sourcePos, sourcePos, sourcePos);
    }
}
