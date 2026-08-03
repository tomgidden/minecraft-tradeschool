package cx.gid.minecraft.tradeschool.trade;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.data.ItemKnowledge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import org.jetbrains.annotations.Nullable;

/// Creates trades that sell learned items.
/// The villager offers enchanted items they've been taught,
/// with prices based on material, type, and enchantments.
///
/// For Weaponsmiths, Toolsmiths, Armourers, and Fletchers.
public class LearnedItemTradeFactory {

    private final ItemKnowledge itemKnowledge;

    public LearnedItemTradeFactory(ItemKnowledge itemKnowledge) {
        this.itemKnowledge = itemKnowledge;
    }

    @Nullable
    public MerchantOffer getOffer(ServerLevel level, Entity trader, RandomSource random) {
        if (!(trader instanceof Villager)) {
            return null;
        }

        // Create the enchanted item from knowledge
        ItemStack offeredItem = itemKnowledge.createItemStack();

        Constants.debug("LearnedItemTrade: offering {} with {} enchantments for {} emeralds (baseItem={})",
            offeredItem.getItem(), offeredItem.getEnchantments().size(),
            ItemPricingCalculator.calculateSellingPrice(itemKnowledge),
            itemKnowledge.getBaseItem().unwrapKey().map(k -> k.toString()).orElse("?"));

        // Calculate price based on material and enchantments
        int price = ItemPricingCalculator.calculateSellingPrice(itemKnowledge);

        var config = cx.gid.minecraft.tradeschool.config.Config.get().trading;
        price = Math.max(config.minimumItemPrice(), Math.min(config.maximumPrice, price));

        // Create the trade offer
        // Format: Player pays emeralds → Gets enchanted item
        // Same settings as a learned book: this is the gear equivalent, and a smith whose
        // lessons advanced them at a different rate from a librarian's would be arbitrary.
        // Experience is fixed by the level the lesson was given at, not the villager's
        // current one — see LearnedTradeFactory for why that distinction matters.
        return new MerchantOffer(
                new ItemCost(Items.EMERALD, price),
                offeredItem.copy(),
                config.maxUses,
                config.xpForLevel(itemKnowledge.getLearnedAtLevel()),
                config.learnedTradePriceMultiplier
        );
    }
}
