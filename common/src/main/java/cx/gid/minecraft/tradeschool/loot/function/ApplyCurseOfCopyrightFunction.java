package cx.gid.minecraft.tradeschool.loot.function;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.enchantment.ModEnchantments;
import cx.gid.minecraft.tradeschool.loot.tier.StructureTier;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

import java.util.List;

/// Loot item function that applies Curse of Copyright to enchanted books and gear. Applied
/// dynamically at loot generation time.
///
/// The rate comes from `curse_of_copyright.loot_probability`, which is stated per
/// structure tier. The shipped configuration gives every tier the same value — one in ten of
/// what you find is copyrighted, wherever you found it, which is a rule players can hold in
/// their heads — but an operator can vary it, so the `tier` field is read rather than
/// ignored.
public class ApplyCurseOfCopyrightFunction extends LootItemConditionalFunction {
    // "tier" is optional: tables cursed by prefix rather than looked up as structures have
    // no tier to record. A required field would fail to encode for exactly those, which is
    // most of what gets cursed. Datapacks written when it was mandatory still decode.
    public static final MapCodec<ApplyCurseOfCopyrightFunction> CODEC = RecordCodecBuilder.mapCodec(
        instance -> commonFields(instance)
            .and(StructureTier.CODEC.optionalFieldOf("tier")
                .forGetter(f -> java.util.Optional.ofNullable(f.tier)))
            .apply(instance, ApplyCurseOfCopyrightFunction::new)
    );

    @org.jetbrains.annotations.Nullable
    private final StructureTier tier;

    protected ApplyCurseOfCopyrightFunction(List<LootItemCondition> conditions,
                                            java.util.Optional<StructureTier> tier) {
        super(conditions);
        this.tier = tier.orElse(null);
    }

    @Override
    public MapCodec<? extends LootItemConditionalFunction> codec() {
        return ModLootFunctions.APPLY_CURSE_OF_COPYRIGHT;
    }

    @Override
    protected ItemStack run(ItemStack stack, LootContext context) {
        try {
            // Check if this is an enchanted book (stored enchantments) or regular item (enchantments)
            ItemEnchantments storedEnchantments = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
            ItemEnchantments regularEnchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);

            boolean isBook = !storedEnchantments.isEmpty();
            boolean isEnchantedItem = !regularEnchantments.isEmpty();

            // Only apply curse to items that are already enchanted
            if (!isBook && !isEnchantedItem) {
                return stack; // Not enchanted, skip
            }

            Constants.LOGGER.debug("ApplyCurseOfCopyrightFunction checking {} (tier: {}, isBook: {}, isEnchanted: {})",
                stack.getItem(), tier, isBook, isEnchantedItem);

            // Check probability based on tier
            double probability = cx.gid.minecraft.tradeschool.config.Config.get().curseOfCopyright.lootProbabilityFor(tier);
            double roll = context.getRandom().nextDouble();

            if (roll >= probability) {
                Constants.LOGGER.debug("Curse NOT applied (rolled {} >= {})", roll, probability);
                return stack; // No curse applied
            }

            // Get curse enchantment holder — may be absent if datapacks not fully loaded (e.g. game tests)
            var enchantmentRegistry = context.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            var curseHolderOpt = enchantmentRegistry.get(ModEnchantments.CURSE_OF_COPYRIGHT);
            if (curseHolderOpt.isEmpty()) {
                Constants.LOGGER.debug("Curse of Copyright enchantment not found in registry, skipping");
                return stack;
            }
            Holder<Enchantment> curseHolder = curseHolderOpt.get();

            // Add curse to existing enchantments
            if (isBook) {
                ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(storedEnchantments);
                mutable.set(curseHolder, 1);
                stack.set(DataComponents.STORED_ENCHANTMENTS, mutable.toImmutable());
            } else {
                ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(regularEnchantments);
                mutable.set(curseHolder, 1);
                stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
            }

            Constants.debug("Applied Curse of Copyright to {} (tier: {}, probability: {}%, rolled: {})",
                stack.getItem(), tier, probability * 100, roll);

        } catch (Exception e) {
            Constants.LOGGER.error("Failed to apply Curse of Copyright to loot item", e);
        }

        return stack;
    }

        /// Creates a function for a loot table of no particular tier.
    ///
    /// Used for the vanilla tables both loaders curse wholesale, which are matched by
    /// prefix rather than looked up as structures and so have no tier to offer. They take
    /// the first configured rate — with the shipped flat configuration that is simply the
    /// rate, and an operator who varies it by tier has said nothing about tables that are
    /// not structures.
    public static LootItemConditionalFunction.Builder<?> applyCurse() {
        return applyCurse(null);
    }

        /// Creates a function for a loot table of known tier.
    ///
    /// @param tier the structure's tier, or null if it has none
    public static LootItemConditionalFunction.Builder<?> applyCurse(
            @org.jetbrains.annotations.Nullable StructureTier tier) {
        return simpleBuilder(conditions ->
            new ApplyCurseOfCopyrightFunction(conditions, java.util.Optional.ofNullable(tier)));
    }
}
