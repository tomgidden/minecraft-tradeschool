package cx.gid.minecraft.tradeschool;

import java.util.List;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/// Shared mixin interface for teaching state on villagers.
/// Implemented by VillagerPickupMixin (on Villager) and read by
/// AbstractVillagerTeachMixin (on AbstractVillager).
public interface IVillagerTeachState
{
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

  /// Rebuilds the ephemeral teach offers for whoever is currently trading.
  /// Implemented by VillagerPickupMixin, which owns the scan; called from
  /// VillagerTradesMixin, which owns the trade-update path. Returns true if the offer
  /// list changed, so the caller knows whether a resend is still accurate.
  boolean tradeschool$rebuildTeachOffersForTradingPlayer();
}
