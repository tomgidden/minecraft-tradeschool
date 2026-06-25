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

        // Use the stored enchantment level (fixed at time of learning)
        int offeredLevel = enchantmentKnowledge.getEnchantmentLevel();

        // Create enchanted book with the stored level
        ItemStack enchantedBook = createEnchantedBook(
            enchantmentKnowledge.getEnchantment(),
            offeredLevel
        );

        // Calculate emerald cost based on enchantment and level
        int emeraldCost = calculateEmeraldCost(
            enchantmentKnowledge.getEnchantment(),
            offeredLevel
        );

        // Create the trade offer
        // Format: Player pays emeralds → Gets enchanted book
        return new MerchantOffer(
                new ItemCost(Items.EMERALD, emeraldCost),  // What player pays
                enchantedBook,                              // What player gets
                12,                                         // Max uses before needing to restock
                5,                                          // Villager XP gained
                0.05F                                       // Price multiplier
        );
    }

    /** Public estimate used to compute the emerald payment when a book is picked up. */
    public static int estimateBookPrice(Holder<Enchantment> enchantment, int level) {
        int maxCost = enchantment.value().getMaxCost(level);
        int baseCost = maxCost <= 20 ? 5 : maxCost <= 30 ? 10 : maxCost <= 40 ? 15 : 20;
        return Math.max(5, Math.min(64, baseCost + (level - 1) * 5));
    }

    /**
     * Calculates emerald cost based on enchantment rarity and level.
     */
    private int calculateEmeraldCost(Holder<Enchantment> enchantment, int level) {
        // Base cost varies by rarity
        int maxCost = enchantment.value().getMaxCost(level);
        int baseCost;

        if (maxCost <= 20) {
            baseCost = 5;   // Common enchantments
        } else if (maxCost <= 30) {
            baseCost = 10;  // Uncommon enchantments
        } else if (maxCost <= 40) {
            baseCost = 15;  // Rare enchantments
        } else {
            baseCost = 20;  // Very rare enchantments
        }

        // Add cost per level (higher levels cost more)
        int levelMultiplier = (level - 1) * 5;
        int finalCost = baseCost + levelMultiplier;

        // Clamp to reasonable range (5-64 emeralds)
        return Math.max(5, Math.min(64, finalCost));
    }

    /**
     * Creates an enchanted book item with the given enchantment and level.
     */
    private ItemStack createEnchantedBook(Holder<Enchantment> enchantment, int level) {
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);

        // Use ItemEnchantments to properly set stored enchantments
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(enchantment, level);

        book.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());

        return book;
    }
}
