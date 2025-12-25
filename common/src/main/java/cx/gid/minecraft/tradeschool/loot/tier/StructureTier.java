package cx.gid.minecraft.tradeschool.loot.tier;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Defines the tier of a structure/loot location, which determines the maximum
 * enchantment level that can appear in that location.
 */
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

    /**
     * Gets the maximum enchantment level that can appear in structures of this tier.
     *
     * @return The maximum enchantment level (1-5)
     */
    public int getMaxEnchantmentLevel() {
        return maxEnchantmentLevel;
    }
}
