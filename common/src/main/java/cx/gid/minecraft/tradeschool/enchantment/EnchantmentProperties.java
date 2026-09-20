package cx.gid.minecraft.tradeschool.enchantment;

import cx.gid.minecraft.tradeschool.Constants;
import net.minecraft.core.Holder;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.enchantment.Enchantment;

/// Utility class for inspecting enchantment properties at runtime.
/// Provides information about max levels, treasure status, tradability, and
/// rarity.
public class EnchantmentProperties
{
  public static int getMaxLevel(Holder<Enchantment> enchantment)
  {
    return enchantment.value().getMaxLevel();
  }

  /// Orders enchantments the way the game shows them in a tooltip.
  ///
  /// ### Why this is needed at all
  ///
  /// There is no such thing as an item's "own" enchantment order. `ItemEnchantments`
  /// holds its contents in an `Object2IntOpenHashMap`, so iterating one yields hash
  /// order; NBT cannot preserve an order either, since `CompoundTag` is a
  /// `HashMap` and `/data get` sorts keys alphabetically for display. An order
  /// that looks meaningful in any of those places is a coincidence of hashing.
  ///
  /// What the player actually sees is `ItemEnchantments.addToTooltip`, which walks
  /// the `minecraft:tooltip_order` tag and appends anything absent from it afterwards.
  /// That is the only order with any authority, so it is the one this mod repeats -- a
  /// message naming enchantments in a different order from the tooltip beside it reads as a
  /// different set of enchantments.
  ///
  /// Enchantments outside the tag -- another mod's, usually -- keep their existing relative
  /// order after those in it, matching what vanilla does with them.
  ///
  /// @param enchantments the enchantments to order; iteration order is used as the
  ///     tie-break, so pass an ordered map for a stable result
  /// @param registries   registry access, for reading the tag
  public static java.util.LinkedHashMap<Holder<Enchantment>, Integer> inTooltipOrder(
      java.util.Map<Holder<Enchantment>, Integer> enchantments,
      net.minecraft.core.HolderLookup.Provider registries
  )
  {
    java.util.Map<net.minecraft.resources.ResourceKey<Enchantment>, Integer> position =
        tooltipPositions(registries);

    java.util.List<java.util.Map.Entry<Holder<Enchantment>, Integer>> entries =
        new java.util.ArrayList<>(enchantments.entrySet());
    // Absent from the tag sorts last, preserving the caller's order among such entries
    // because sort() is stable.
    entries.sort(java.util.Comparator.comparingInt(e -> e.getKey().unwrapKey().map(key -> position.getOrDefault(key, Integer.MAX_VALUE)).orElse(Integer.MAX_VALUE)));

    java.util.LinkedHashMap<Holder<Enchantment>, Integer> ordered = new java.util.LinkedHashMap<>();
    for(var entry: entries) ordered.put(entry.getKey(), entry.getValue());
    return ordered;
  }

  /// Each enchantment's index within the tooltip order tag, keyed by registry key.
  ///
  /// Keyed by [net.minecraft.resources.ResourceKey] rather than by `Holder`:
  /// holders compare by identity, and the ones in the tag need not be the same objects as
  /// the ones on an item stack, so a holder-keyed lookup can silently miss and sort
  /// everything as "not in the tag".
  ///
  /// Rebuilt per call rather than cached: the tag is datapack-driven and changes on
  /// reload, and a stale cache would order things by whatever was loaded at startup. The tag
  /// is a few dozen entries and this runs once per message.
  private static java.util.Map<net.minecraft.resources.ResourceKey<Enchantment>, Integer>
      tooltipPositions(net.minecraft.core.HolderLookup.Provider registries)
  {
    java.util.Map<net.minecraft.resources.ResourceKey<Enchantment>, Integer> position =
        new java.util.HashMap<>();
    if(registries == null) return position;

    registries.lookup(net.minecraft.core.registries.Registries.ENCHANTMENT)
        .flatMap(lookup -> lookup.get(EnchantmentTags.TOOLTIP_ORDER))
        .ifPresent(tag -> {
          int index = 0;
          for(Holder<Enchantment> enchantment: tag) {
            var key = enchantment.unwrapKey().orElse(null);
            if(key != null) position.put(key, index++);
          }
        });
    return position;
  }

  public static boolean isTreasure(Holder<Enchantment> enchantment)
  {
    // Check if the enchantment is in the treasure tag
    return enchantment.is(EnchantmentTags.TREASURE);
  }

  /// Whether villagers may learn this enchantment.
  ///
  /// The blocked set is configurable (`unlearnable_enchantments`), defaulting to the
  /// three vanilla treasure enchantments no villager trades -- Soul Speed, Swift Sneak and
  /// Wind Burst. Empty the list to let villagers learn anything.
  public static boolean isTradable(Holder<Enchantment> enchantment)
  {
    var teaching = cx.gid.minecraft.tradeschool.config.Config.get().teaching;

    String enchantmentId =
        enchantment.unwrapKey().map(key -> key.identifier().toString()).orElse("unknown");

    for(String blocked: teaching.unlearnableEnchantments) {
      // Namespace optional in config, so "wind_burst" and "minecraft:wind_burst" both work.
      if(enchantmentId.equals(blocked)
         || enchantmentId.equals("minecraft:" + blocked)) {
        return false;
      }
    }
    return true;
  }

  public static boolean isTradableTreasure(Holder<Enchantment> enchantment)
  {
    return isTreasure(enchantment) && isTradable(enchantment);
  }

  /// Gets the effective level for pricing and learning requirements.
  /// Enchantments with max level 1 (like Silk Touch, Mending, Flame) should be
  /// treated as level 3 for pricing and villager level requirements.
  public static int getEffectiveLevel(Holder<Enchantment> enchantment, int actualLevel)
  {
    int maxLevel = getMaxLevel(enchantment);

    // Enchantments with max level 1 (eg. Silk Touch, Mending, Flame) should be
    // treated as level 3
    return maxLevel == 1 ? 3 : actualLevel;
  }

  /// Gets the minimum villager profession level required to learn this
  /// enchantment. Max-level-1 enchantments (Silk Touch, Mending, etc.) require
  /// at least level 3 (Journeyman).
  public static int getMinimumVillagerLevel(Holder<Enchantment> enchantment, int enchantmentLevel)
  {
    int effectiveLevel = getEffectiveLevel(enchantment, enchantmentLevel);

    // The villager must be at least the same level as the effective enchantment
    // level
    return Math.clamp(effectiveLevel, 1, 5);
  }

  /// Logs debug information about an enchantment's properties.
  public static void logEnchantmentInfo(Holder<Enchantment> enchantment)
  {
    String name =
        enchantment.unwrapKey().map(k -> k.toString()).orElse("unknown");
    int maxLevel     = getMaxLevel(enchantment);
    boolean treasure = isTreasure(enchantment);
    boolean tradable = isTradable(enchantment);

    Constants.LOGGER.debug(
        "Enchantment {}: maxLevel={}, treasure={}, tradable={}",
        name,
        maxLevel,
        treasure,
        tradable
    );
  }
}
