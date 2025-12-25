package cx.gid.minecraft.tradeschool.loot.builder;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.enchantment.ModEnchantments;
import cx.gid.minecraft.tradeschool.loot.config.CurseConfig;
import cx.gid.minecraft.tradeschool.loot.tier.StructureTier;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Utility for creating enchanted book ItemStacks with proper DataComponents.
 */
public class EnchantedBookBuilder {

    /**
     * Creates an enchanted book with the specified enchantment and level.
     * Does NOT add Curse of Copyright - use createWithCurse() for loot generation.
     *
     * @param enchantmentId The enchantment ID (e.g., "minecraft:sharpness")
     * @param level The enchantment level (1-5)
     * @param registries The registry access for looking up enchantments
     * @return An enchanted book ItemStack
     */
    public static ItemStack create(
        String enchantmentId,
        int level,
        HolderLookup.Provider registries
    ) {
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);

        try {
            // Parse enchantment ID and create resource key
            Identifier enchantmentLoc = Identifier.parse(enchantmentId);
            ResourceKey<Enchantment> enchantmentKey =
                ResourceKey.create(Registries.ENCHANTMENT, enchantmentLoc);

            // Get enchantment holder from registry
            var enchantmentRegistry = registries.lookupOrThrow(Registries.ENCHANTMENT);
            Holder<Enchantment> enchantmentHolder = enchantmentRegistry.getOrThrow(enchantmentKey);

            // Create ItemEnchantments and add the enchantment
            ItemEnchantments.Mutable enchantments =
                new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            enchantments.set(enchantmentHolder, level);

            // Set STORED_ENCHANTMENTS component (for enchanted books)
            book.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());

            Constants.LOGGER.debug("Created enchanted book: {} level {}", enchantmentId, level);

        } catch (Exception e) {
            Constants.LOGGER.error("Failed to create enchanted book for {} level {}",
                enchantmentId, level, e);
        }

        return book;
    }

    /**
     * Creates an enchanted book with the specified enchantment and level,
     * with a chance to add Curse of Copyright based on structure tier.
     * Used for loot table generation.
     *
     * @param enchantmentId The enchantment ID (e.g., "minecraft:sharpness")
     * @param level The enchantment level (1-5)
     * @param tier The structure tier (affects curse probability)
     * @param random Random source for curse probability check
     * @param registries The registry access for looking up enchantments
     * @return An enchanted book ItemStack, possibly cursed
     */
    public static ItemStack createWithCurse(
        String enchantmentId,
        int level,
        StructureTier tier,
        RandomSource random,
        HolderLookup.Provider registries
    ) {
        // Create the base book with primary enchantment
        ItemStack book = create(enchantmentId, level, registries);

        // Roll for curse
        double curseProbability = CurseConfig.getCurseProbability(tier);
        if (random.nextDouble() < curseProbability) {
            try {
                // Get curse enchantment holder
                var enchantmentRegistry = registries.lookupOrThrow(Registries.ENCHANTMENT);
                Holder<Enchantment> curseHolder = enchantmentRegistry.getOrThrow(ModEnchantments.CURSE_OF_COPYRIGHT);

                // Add curse to existing enchantments
                ItemEnchantments existing = book.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
                ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(existing);
                mutable.set(curseHolder, 1);
                book.set(DataComponents.STORED_ENCHANTMENTS, mutable.toImmutable());

                Constants.LOGGER.debug("Added Curse of Copyright to {} level {} (tier: {}, probability: {}%)",
                    enchantmentId, level, tier, curseProbability * 100);

            } catch (Exception e) {
                Constants.LOGGER.error("Failed to add Curse of Copyright to book", e);
            }
        }

        return book;
    }
}
