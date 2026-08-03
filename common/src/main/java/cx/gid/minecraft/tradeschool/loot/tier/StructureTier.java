package cx.gid.minecraft.tradeschool.loot.tier;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/// Defines the tier of a structure/loot location, which determines the maximum
/// enchantment level that can appear in that location.
///
/// The levels here are fallbacks. The values actually used come from
/// `max_enchantment_level_by_tier` in `loot_distribution.json`, so a datapack
/// can raise or lower the ceiling for a whole tier without touching each structure — but an
/// enum constant cannot read loaded data at construction time, so each carries the shipped
/// value for use before the tables load, and for a datapack that omits the block.
public enum StructureTier implements StringRepresentable {
    LOW("low", 2),      // Villages, fishing - basic enchantments only
    MEDIUM("medium", 3),   // Shipwrecks, temples - intermediate enchantments
    HIGH("high", 4),     // Strongholds, bastions - advanced enchantments
    TOP("top", 5);      // Ancient Cities, End Cities - all enchantment levels

    public static final Codec<StructureTier> CODEC = StringRepresentable.fromEnum(StructureTier::values);

    private final String name;
    private final int maxEnchantmentLevel;

    StructureTier(String name, int maxEnchantmentLevel) {
        this.name = name;
        this.maxEnchantmentLevel = maxEnchantmentLevel;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

        /// Gets the maximum enchantment level that can appear in structures of this tier,
    /// as configured by the loot tables, falling back to the level shipped with this enum.
    ///
    /// @return The maximum enchantment level (1-5)
    public int getMaxEnchantmentLevel() {
        return cx.gid.minecraft.tradeschool.loot.LootDistributionManager.getInstance()
            .getConfig().maxEnchantmentLevelFor(this, maxEnchantmentLevel);
    }
}
