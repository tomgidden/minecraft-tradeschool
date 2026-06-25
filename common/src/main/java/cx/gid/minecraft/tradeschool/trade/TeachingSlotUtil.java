package cx.gid.minecraft.tradeschool.trade;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Utility for identifying which inventory slot was consumed during a teach trade.
 */
public final class TeachingSlotUtil {

    private TeachingSlotUtil() {}

    /**
     * Given the list of slots and stored item copies recorded at UI-open time for one offer group,
     * determines which slot was consumed by the trade (empty, or count reduced, or item changed).
     *
     * Returns the stored ItemStack copy for the consumed slot so damage/repair_cost are preserved.
     * Falls back to the first stored copy if no slot change is detected (e.g., fast trade).
     */
    public static ItemStack findConsumedStack(ServerPlayer sp, List<Integer> slots, List<ItemStack> storedStacks) {
        Inventory inv = sp.getInventory();
        for (int i = 0; i < slots.size(); i++) {
            int slotIdx = slots.get(i);
            ItemStack stored = storedStacks.get(i);
            ItemStack current = inv.getItem(slotIdx);
            if (current.isEmpty() || current.getCount() < stored.getCount()
                    || !ItemStack.isSameItem(current, stored)) {
                return stored;
            }
        }
        return storedStacks.isEmpty() ? ItemStack.EMPTY : storedStacks.get(0);
    }
}
