package cx.gid.minecraft.tradeschool.trade;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.data.EnchantmentKnowledge;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

/**
 * Creates trades where librarians sell enchanted books they've learned.
 * Enchantment level is fixed at the level that was stored when the villager learned it.
 */
public class LearnedTradeFactory {

    private final EnchantmentKnowledge enchantmentKnowledge;

    public LearnedTradeFactory(EnchantmentKnowledge enchantmentKnowledge) {
        this.enchantmentKnowledge = enchantmentKnowledge;
    }

    @Nullable
    public MerchantOffer getOffer(ServerLevel level, Entity trader, RandomSource random) {
        if (!(trader instanceof Villager villager)) {
            return null;
        }

        // The book carries every enchantment the villager learned from the original, at
        // the levels fixed when they learned them.
        var learned = enchantmentKnowledge.getEnchantments();
        if (learned.isEmpty()) return null;

        ItemStack enchantedBook = createEnchantedBook(learned);

        // Priced on the dearest enchantment, plus a little for each extra: a book with two
        // useful enchantments is worth more than either alone, but charging the full price
        // of both would make multi-enchantment books worse value than buying separately.
        int emeraldCost = 0;
        for (var entry : learned.entrySet()) {
            int one = calculateEmeraldCost(entry.getKey(), entry.getValue());
            emeraldCost = Math.max(emeraldCost, one) + (emeraldCost > 0 ? one / 2 : 0);
        }
        emeraldCost = Math.max(5, Math.min(64, emeraldCost));

        // Create the trade offer
        // Format: Player pays emeralds → Gets enchanted book
        return new MerchantOffer(
                new ItemCost(Items.EMERALD, emeraldCost),  // What player pays
                enchantedBook,                              // What player gets
                config().learnedTradeMaxUses,               // Max uses before needing to restock
                5,                                          // Villager XP gained
                0.05F                                       // Price multiplier
        );
    }

    private static cx.gid.minecraft.tradeschool.loot.config.TradeConfig config() {
        return cx.gid.minecraft.tradeschool.loot.LootDistributionManager.getInstance()
            .getConfig().global.trade;
    }

    /** Public estimate used to compute the emerald payment when a book is picked up. */
    public static int estimateBookPrice(Holder<Enchantment> enchantment, int level) {
        return calculateEmeraldCost(enchantment, level);
    }

    /**
     * Calculates emerald cost based on enchantment rarity and level.
     */
    private static int calculateEmeraldCost(Holder<Enchantment> enchantment, int level) {
        var cfg = config();

        // Base cost varies by rarity
        int maxCost = enchantment.value().getMaxCost(level);
        int baseCost;

        if (maxCost <= 20) {
            baseCost = cfg.bookPriceCommon;
        } else if (maxCost <= 30) {
            baseCost = cfg.bookPriceUncommon;
        } else if (maxCost <= 40) {
            baseCost = cfg.bookPriceRare;
        } else {
            baseCost = cfg.bookPriceVeryRare;
        }

        // Add cost per level (higher levels cost more)
        int finalCost = baseCost + (level - 1) * cfg.bookPricePerLevel;

        // Clamp to reasonable range (5-64 emeralds)
        return Math.max(5, Math.min(64, finalCost));
    }

    /**
     * Creates an enchanted book item with the given enchantment and level.
     */
    private ItemStack createEnchantedBook(java.util.Map<Holder<Enchantment>, Integer> learned) {
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        learned.forEach(enchantments::set);
        book.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
        return book;
    }
}
