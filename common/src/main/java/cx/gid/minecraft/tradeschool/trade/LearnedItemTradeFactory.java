package cx.gid.minecraft.tradeschool.trade;

import cx.gid.minecraft.tradeschool.data.ItemKnowledge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import org.jetbrains.annotations.Nullable;

/**
 * Creates trades that sell learned items.
 * The villager offers enchanted items they've been taught,
 * with prices based on material, type, and enchantments.
 *
 * For Weaponsmiths, Toolsmiths, Armourers, and Fletchers.
 */
public class LearnedItemTradeFactory implements VillagerTrades.ItemListing {

    private final ItemKnowledge itemKnowledge;

    public LearnedItemTradeFactory(ItemKnowledge itemKnowledge) {
        this.itemKnowledge = itemKnowledge;
    }

    @Override
    @Nullable
    public MerchantOffer getOffer(ServerLevel level, Entity trader, RandomSource random) {
        if (!(trader instanceof Villager)) {
            return null;
        }

        // Create the enchanted item from knowledge
        ItemStack offeredItem = itemKnowledge.createItemStack();

        // Calculate price based on material and enchantments
        int price = ItemPricingCalculator.calculateSellingPrice(itemKnowledge);

        // Ensure price is within valid range (1-64 emeralds)
        price = Math.max(1, Math.min(64, price));

        // Create the trade offer
        // Format: Player pays emeralds → Gets enchanted item
        return new MerchantOffer(
                new ItemCost(Items.EMERALD, price),
                offeredItem.copy(),
                12, // Max uses - can sell this item multiple times
                5, // Villager XP gain
                0.2F // Price multiplier (slight discount for repeated trades)
        );
    }
}
