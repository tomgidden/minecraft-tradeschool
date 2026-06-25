package cx.gid.minecraft.tradeschool.mixin;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.IVillagerTeachState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.Merchant;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the payment item from trade slot 0 just before notifyTrade fires.
 * At this point the item is still present in the MerchantContainer, so we can
 * copy it and store it on the villager for use in AbstractVillagerTeachMixin.
 */
@Mixin(MerchantResultSlot.class)
public abstract class MerchantResultSlotMixin {

    @Shadow @Final private MerchantContainer slots;
    @Shadow @Final private Merchant merchant;

    @Inject(method = "onTake", at = @At("HEAD"))
    private void onTakeHead(Player player, ItemStack stack, CallbackInfo ci) {
        if (!(merchant instanceof Villager villager)) return;
        if (!(villager instanceof IVillagerTeachState state)) return;
        if (state.tradeschool$getTeachOfferIndices().isEmpty()) return;

        // slot 0 = payment slot 1, still populated before notifyTrade consumes it
        ItemStack submitted = slots.getItem(0).copy();
        state.tradeschool$setLastSubmittedItem(submitted);

        int submittedDamage = submitted.getOrDefault(DataComponents.DAMAGE, 0);
        int submittedRepairCost = submitted.getOrDefault(DataComponents.REPAIR_COST, 0);

        // Debug: log submitted item
        ItemEnchantments enchs = submitted.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (enchs.isEmpty()) enchs = submitted.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        Constants.LOGGER.info("[TradeSchool] Trade submitted: item={} enchants={} damage={} repair_cost={}",
            submitted.getItem(), enchs, submittedDamage, submittedRepairCost);

        // If the result is the same item type as the submitted item (partial-learn return,
        // not emeralds), copy damage and repair_cost from the submitted item onto the result.
        // The offer was pre-built from whichever copy was scanned first at UI-open time,
        // but the player must get back an item matching what they actually submitted.
        if (!stack.isEmpty() && stack.getItem() == submitted.getItem()) {
            if (submittedDamage > 0) {
                stack.set(DataComponents.DAMAGE, submittedDamage);
            } else {
                stack.remove(DataComponents.DAMAGE);
            }
            if (submittedRepairCost > 0) {
                stack.set(DataComponents.REPAIR_COST, submittedRepairCost);
            } else {
                stack.remove(DataComponents.REPAIR_COST);
            }
            Constants.LOGGER.info("[TradeSchool] Result item damage/repair_cost corrected to match submitted: damage={} repair_cost={}",
                submittedDamage, submittedRepairCost);
        }
    }
}
