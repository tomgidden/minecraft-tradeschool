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

/// Creates trades where librarians sell enchanted books they've learned.
/// Enchantment level is fixed at the level that was stored when the villager learned it.
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
            emeraldCost = Math.max(emeraldCost, one)
                + (emeraldCost > 0 ? one / config().extraEnchantmentPriceDivisor : 0);
        }
        emeraldCost = Math.max(config().minimumBookPrice(),
                               Math.min(config().maximumPrice, emeraldCost));

        // Create the trade offer
        // Format: Player pays emeralds → Gets enchanted book
        // Experience scales with the level the lesson was given at, as vanilla's book
        // trades do — theirs is fixed by which level's trade set they came from.
        //
        // Deliberately not the villager's *current* level: trades regenerate on every
        // promotion, so reading the current level would rewrite every past lesson to the
        // new rate. A librarian reaching master would find the novice trade it learned
        // first now paying a master's experience, which is both wrong and self-feeding.
        //
        // A flat value would not work either: 10 experience leaves level 1 but 100 is
        // needed to leave level 4, so anything generous enough for the latter makes the
        // former instant, and anything balanced for the former cannot finish the latter
        // within maxUses at all.
        // Emeralds and a blank book, the way vanilla sells enchanted books. The paper the
        // book is made from is the one thing a librarian cannot conjure, and requiring it
        // keeps a taught librarian on the same footing as an untaught one.
        return new MerchantOffer(
                new ItemCost(Items.EMERALD, emeraldCost),  // What player pays
                config().bookCost(),                        // …and a blank book
                enchantedBook,                              // What player gets
                config().maxUses,                           // Max uses before needing to restock
                config().xpForLevel(enchantmentKnowledge.getLearnedAtLevel()),
                config().learnedTradePriceMultiplier
        );
    }

    private static cx.gid.minecraft.tradeschool.config.TradingSection config() {
        return cx.gid.minecraft.tradeschool.config.Config.get().trading;
    }

        ///  Public estimate used to compute the emerald payment when a book is picked up.
    public static int estimateBookPrice(Holder<Enchantment> enchantment, int level) {
        return calculateEmeraldCost(enchantment, level);
    }

        /// Calculates emerald cost based on enchantment rarity and level.
    private static int calculateEmeraldCost(Holder<Enchantment> enchantment, int level) {
        var cfg = config();

        // Base cost varies by rarity. The bands are one ordered scale — common, uncommon,
        // rare, very rare — so they are indexed rather than named, which is what stops a
        // config setting "rare" below "uncommon" without the neighbouring values in view.
        int maxCost = enchantment.value().getMaxCost(level);
        int baseCost;

        if (maxCost <= 20) {
            baseCost = cfg.band(0, 5);
        } else if (maxCost <= 30) {
            baseCost = cfg.band(1, 10);
        } else if (maxCost <= 40) {
            baseCost = cfg.band(2, 15);
        } else {
            baseCost = cfg.band(3, 20);
        }

        // Add cost per level (higher levels cost more)
        int finalCost = baseCost + (level - 1) * cfg.bookPricePerLevel;

        return Math.max(cfg.minimumBookPrice(), Math.min(cfg.maximumPrice, finalCost));
    }

        /// Creates an enchanted book item with the given enchantment and level.
    public static ItemStack createEnchantedBook(java.util.Map<Holder<Enchantment>, Integer> learned) {
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        learned.forEach(enchantments::set);
        book.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
        return book;
    }
}
