package cx.gid.minecraft.tradeschool.enchantment;

import cx.gid.minecraft.tradeschool.Constants;
import net.minecraft.core.Holder;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * Utility class for inspecting enchantment properties at runtime.
 * Provides information about max levels, treasure status, tradability, and
 * rarity.
 */
public class EnchantmentProperties {

  public static int getMaxLevel(Holder<Enchantment> enchantment) {
    return enchantment.value().getMaxLevel();
  }

  public static boolean isTreasure(Holder<Enchantment> enchantment) {
    // Check if the enchantment is in the treasure tag
    return enchantment.is(EnchantmentTags.TREASURE);
  }

  /**
   * Whether villagers may learn this enchantment.
   *
   * The blocked set is configurable ({@code unlearnable_enchantments}), defaulting to the
   * three vanilla treasure enchantments no villager trades — Soul Speed, Swift Sneak and
   * Wind Burst. Empty the list to let villagers learn anything.
   */
  public static boolean isTradable(Holder<Enchantment> enchantment) {
    var global = cx.gid.minecraft.tradeschool.loot.LootDistributionManager.getInstance()
        .getConfig().global;

    String enchantmentId =
        enchantment.unwrapKey().map(key -> key.identifier().toString()).orElse("unknown");

    for (String blocked : global.unlearnableEnchantments) {
      // Namespace optional in config, so "wind_burst" and "minecraft:wind_burst" both work.
      if (enchantmentId.equals(blocked)
          || enchantmentId.equals("minecraft:" + blocked)) {
        return false;
      }
    }
    return true;
  }

  public static boolean isTradableTreasure(Holder<Enchantment> enchantment) {
    return isTreasure(enchantment) && isTradable(enchantment);
  }

  /**
   * Gets the effective level for pricing and learning requirements.
   * Enchantments with max level 1 (like Silk Touch, Mending, Flame) should be
   * treated as level 3 for pricing and villager level requirements.
   */
  public static int getEffectiveLevel(Holder<Enchantment> enchantment,
                                      int actualLevel) {
    int maxLevel = getMaxLevel(enchantment);

    // Enchantments with max level 1 (eg. Silk Touch, Mending, Flame) should be
    // treated as level 3
    return maxLevel == 1 ? 3 : actualLevel;
  }

  /**
   * Gets the minimum villager profession level required to learn this
   * enchantment. Max-level-1 enchantments (Silk Touch, Mending, etc.) require
   * at least level 3 (Journeyman).
   */
  public static int getMinimumVillagerLevel(Holder<Enchantment> enchantment,
                                            int enchantmentLevel) {
    int effectiveLevel = getEffectiveLevel(enchantment, enchantmentLevel);

    // The villager must be at least the same level as the effective enchantment
    // level
    return Math.clamp(effectiveLevel, 1, 5);
  }

  /**
   * Logs debug information about an enchantment's properties.
   */
  public static void logEnchantmentInfo(Holder<Enchantment> enchantment) {
    String name =
        enchantment.unwrapKey().map(k -> k.toString()).orElse("unknown");
    int maxLevel = getMaxLevel(enchantment);
    boolean treasure = isTreasure(enchantment);
    boolean tradable = isTradable(enchantment);

    Constants.LOGGER.debug(
        "Enchantment {}: maxLevel={}, treasure={}, tradable={}", name, maxLevel,
        treasure, tradable);
  }
}
