package cx.gid.minecraft.tradeschool;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Shared mixin interface for teaching state on villagers.
 * Implemented by VillagerPickupMixin (on Villager) and read by
 * AbstractVillagerTeachMixin (on AbstractVillager).
 */
public interface IVillagerTeachState {
    UUID tradeschool$getTeachingPlayer();
    void tradeschool$setTeachingPlayer(UUID uuid);

    // Indices in getOffers() where teach offers were inserted, parallel to the two lists below.
    List<Integer> tradeschool$getTeachOfferIndices();
    void tradeschool$setTeachOfferIndices(List<Integer> indices);

    // For each teach offer: all inventory slot indices containing qualifying items at UI-open time.
    List<List<Integer>> tradeschool$getTeachItemSlotGroups();
    void tradeschool$setTeachItemSlotGroups(List<List<Integer>> groups);

    // For each teach offer: stored ItemStack copies (parallel to slot groups) at UI-open time.
    List<List<ItemStack>> tradeschool$getTeachItemStackGroups();
    void tradeschool$setTeachItemStackGroups(List<List<ItemStack>> groups);

    // Set by MerchantResultSlotMixin just before notifyTrade fires, from the trade input slot.
    // This is the most reliable way to know exactly which item was submitted.
    ItemStack tradeschool$getLastSubmittedItem();
    void tradeschool$setLastSubmittedItem(ItemStack stack);
}
