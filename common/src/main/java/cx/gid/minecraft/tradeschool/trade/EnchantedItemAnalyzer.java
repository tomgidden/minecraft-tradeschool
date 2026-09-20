package cx.gid.minecraft.tradeschool.trade;

import cx.gid.minecraft.tradeschool.data.ItemKnowledge;
import cx.gid.minecraft.tradeschool.enchantment.EnchantmentProperties;
import cx.gid.minecraft.tradeschool.loot.LootDistributionManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/// Analyzes enchanted items to extract enchantment data and validate materials.
public class EnchantedItemAnalyzer
{
  // Material tier indices 0–4, mapping to villager levels 1–5.
  // Netherite is intentionally excluded -- too rare to be teachable.
  //
  // Weapons/Tools: wood=1, stone=2, copper=3, iron=4, diamond=5
  //   Gold is special: valid at any level, kept as gold (not downgraded).
  //
  // Armor: leather=1, copper/gold=2, chainmail=3, iron=4, diamond=5
  //   Gold armor: valid at level 2+, kept as gold. At level 1 downgraded to leather.
  //   Chainmail: aliases to copper for downgrade purposes (same tier 2).
  private static final int[] TIER_MIN_VILLAGER_LEVEL = {1, 2, 3, 4, 5};

  private static final Item[][] WEAPON_TIERS = {
      {Items.WOODEN_SWORD, Items.STONE_SWORD, Items.COPPER_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD},
      {Items.WOODEN_AXE, Items.STONE_AXE, Items.COPPER_AXE, Items.IRON_AXE, Items.DIAMOND_AXE},
  };

  private static final Item[][] TOOL_TIERS = {
      {Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.COPPER_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE},
      {Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.COPPER_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL},
      {Items.WOODEN_HOE, Items.STONE_HOE, Items.COPPER_HOE, Items.IRON_HOE, Items.DIAMOND_HOE},
  };

  // Armor: leather=tier0, copper=tier1, chainmail=tier2, iron=tier3, diamond=tier4
  // Gold armor aliases to tier1 (copper tier) -- valid at level 2+, kept as gold when offered.
  private static final Item[][] ARMOR_TIERS = {
      {Items.LEATHER_HELMET, Items.COPPER_HELMET, Items.CHAINMAIL_HELMET, Items.IRON_HELMET, Items.DIAMOND_HELMET},
      {Items.LEATHER_CHESTPLATE, Items.COPPER_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE, Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE},
      {Items.LEATHER_LEGGINGS, Items.COPPER_LEGGINGS, Items.CHAINMAIL_LEGGINGS, Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS},
      {Items.LEATHER_BOOTS, Items.COPPER_BOOTS, Items.CHAINMAIL_BOOTS, Items.IRON_BOOTS, Items.DIAMOND_BOOTS},
  };

  // Gold weapon/tool items -- valid at any level, kept as gold.
  private static final java.util.Set<Item> GOLD_WEAPONS_TOOLS = java.util.Set.of(
      Items.GOLDEN_SWORD, Items.GOLDEN_AXE,
      Items.GOLDEN_PICKAXE, Items.GOLDEN_SHOVEL, Items.GOLDEN_HOE
  );

  // Gold armor items -- valid at level 2+, kept as gold (not downgraded to copper).
  private static final java.util.Set<Item> GOLD_ARMOR = java.util.Set.of(
      Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS
  );

  // Netherite, kept deliberately out of the tier rows above.
  //
  // Handled like gold: a separate set, valid only at level 5, and never a
  // downgrade *target*. Putting it in the rows as a sixth tier would make it the
  // highestAffordable result for a master villager offered diamond, which would
  // silently upgrade the player's diamond gear into netherite. It also has no
  // villager level left to map to -- diamond already occupies level 5, the cap.
  //
  // Only reachable at all when teaching.allow_netherite says so; see
  // isNetheriteForProfession.
  private static final java.util.Set<Item> NETHERITE_WEAPONS = java.util.Set.of(
      Items.NETHERITE_SWORD
  );

  // Axes count as tools, not weapons, for the allow_netherite switch.
  private static final java.util.Set<Item> NETHERITE_TOOLS = java.util.Set.of(
      Items.NETHERITE_AXE, Items.NETHERITE_PICKAXE,
      Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE
  );

  private static final java.util.Set<Item> NETHERITE_ARMOR = java.util.Set.of(
      Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE,
      Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS
  );

  // Where netherite lands when the villager is not a master. One-way by design:
  // there is no diamond-to-netherite direction anywhere in this class.
  private static final Map<Item, Item> NETHERITE_TO_DIAMOND = Map.of(
      Items.NETHERITE_SWORD, Items.DIAMOND_SWORD,
      Items.NETHERITE_AXE, Items.DIAMOND_AXE,
      Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE,
      Items.NETHERITE_SHOVEL, Items.DIAMOND_SHOVEL,
      Items.NETHERITE_HOE, Items.DIAMOND_HOE,
      Items.NETHERITE_HELMET, Items.DIAMOND_HELMET,
      Items.NETHERITE_CHESTPLATE, Items.DIAMOND_CHESTPLATE,
      Items.NETHERITE_LEGGINGS, Items.DIAMOND_LEGGINGS,
      Items.NETHERITE_BOOTS, Items.DIAMOND_BOOTS
  );

  // Chainmail aliases -- same tier as copper (tier index 1 for armor)
  private static final Map<Item, Item> CHAINMAIL_TO_COPPER = Map.of(
      Items.CHAINMAIL_HELMET, Items.COPPER_HELMET,
      Items.CHAINMAIL_CHESTPLATE, Items.COPPER_CHESTPLATE,
      Items.CHAINMAIL_LEGGINGS, Items.COPPER_LEGGINGS,
      Items.CHAINMAIL_BOOTS, Items.COPPER_BOOTS
  );

  // Components to strip from learned knowledge (customisation only, not gameplay attributes).
  // FUTURE: consider preserving trim/dye once exploit analysis is complete.
  // Components that count as "customisation stripped" for the full-learn check.
  // ITEM_NAME and LORE are excluded: the /give command and some vanilla items set these
  // as default components, so their presence doesn't indicate player customisation.
  // We never copy these into ItemKnowledge, so the villager always sells a clean item regardless.
  private static final java.util.List<net.minecraft.core.component.DataComponentType<?>> STRIP_COMPONENTS = List.of(
      DataComponents.TRIM,
      DataComponents.DYED_COLOR,
      DataComponents.CUSTOM_NAME,
      DataComponents.CUSTOM_MODEL_DATA
  );

  // Components to preserve in learned knowledge (gameplay attributes).
  private static final java.util.List<net.minecraft.core.component.DataComponentType<?>> PRESERVE_COMPONENTS = List.of(
      DataComponents.DAMAGE,
      DataComponents.REPAIR_COST
  );

  /// The outcome of analysing an item.
  ///
  /// `learnedDescription` names the item and its enchantments -- "Enchanted Book with
  /// Sharpness I, Unbreaking I" -- and nothing more. Sentences are the caller's business:
  /// this class once built one here too, which meant the same lesson was worded one way in
  /// chat and another in the title, and callers wanting just the item resorted to picking
  /// it back out with a regex that broke whenever the sentence changed. Phrasing now lives
  /// in [Describe] and the message files.
  public record AnalysisResult(ItemKnowledge knowledge, String learnedDescription) {
  }

  /// Returns true if this item is gold for weapons/tools (valid at any villager level, kept as gold).
  public static boolean isGoldWeaponOrTool(Item item)
  {
    return GOLD_WEAPONS_TOOLS.contains(item);
  }

  /// Returns true if this item is gold armor (valid at armorer level 2+, kept as gold).
  public static boolean isGoldArmor(Item item)
  {
    return GOLD_ARMOR.contains(item);
  }

  ///  Returns the tier index (0–4) for an armor/weapon/tool item, or -1 if not in tiers.
  private static int getArmorTierIndex(Item item)
  {
    Item resolved = CHAINMAIL_TO_COPPER.getOrDefault(item, item);
    for(Item[] row: ARMOR_TIERS) {
      for(int i = 0; i < row.length; i++) {
        if(row[i] == resolved) return i;
      }
    }
    return -1;
  }

  private static int getWeaponTierIndex(Item item)
  {
    for(Item[] row: WEAPON_TIERS) {
      for(int i = 0; i < row.length; i++) {
        if(row[i] == item) return i;
      }
    }
    return -1;
  }

  private static int getToolTierIndex(Item item)
  {
    for(Item[] row: TOOL_TIERS) {
      for(int i = 0; i < row.length; i++) {
        if(row[i] == item) return i;
      }
    }
    return -1;
  }

  ///  Returns the tier index of the item (weapons/tools/armor), or -1.
  public static int getTierIndex(Item item)
  {
    int w = getWeaponTierIndex(item);
    if(w >= 0) return w;
    int t = getToolTierIndex(item);
    if(t >= 0) return t;
    int a = getArmorTierIndex(item);
    if(a >= 0) return a;
    if(isGoldWeaponOrTool(item)) return 0; // gold weapons/tools: valid at level 1
    if(isGoldArmor(item)) return 1; // gold armor: valid at level 2 (tier index 1)
    if(item == Items.BOW || item == Items.CROSSBOW) return 3;
    if(item == Items.SHIELD) return 2; // shield: armorer level 3 (tier index 2)
    // Netherite shares diamond's tier, and so its villager level: 5 is the cap,
    // and there is no sixth rank to charge for the upgrade. Only reachable when
    // teaching.allow_netherite admits it.
    if(isNetherite(item)) return 4;
    return -1;
  }

  /// Downgrades an item's material to the highest tier the villager can handle.
  /// Gold weapons/tools are always kept as-is.
  /// Gold armor is kept if villagerLevel >= 2, else downgraded to leather.
  /// Returns the same item if it's already at or below the villager's max tier.
  public static Item downgradeItemToLevel(Item item, int villagerLevel)
  {
    // Netherite: keep for a master, else drop to the diamond equivalent. Handled
    // here rather than in the tier rows precisely so the reverse cannot happen --
    // nothing below ever downgrades *up* into netherite, because it is not a
    // member of any row for highestAffordable to find.
    if(isNetherite(item)) {
      if(villagerLevel >= 5) return item;
      Item diamond = NETHERITE_TO_DIAMOND.get(item);
      return diamond == null ? item : downgradeItemToLevel(diamond, villagerLevel);
    }

    // Gold weapons/tools: keep at any level
    if(isGoldWeaponOrTool(item)) return item;

    // Gold armor: keep at level 2+, else leather
    if(isGoldArmor(item)) {
      if(villagerLevel >= 2) return item;
      // Find the leather equivalent in same slot
      for(Item[] row: ARMOR_TIERS) {
        for(Item cell: row) {
          if(GOLD_ARMOR.contains(item)) {
            // match by slot position -- gold helmet -> leather helmet etc.
            int slot = getArmorSlot(item);
            if(slot >= 0) return ARMOR_TIERS[slot][0]; // tier 0 = leather
          }
        }
      }
      return item;
    }

    // Ranged / shield: no material downgrade
    if(item == Items.BOW || item == Items.CROSSBOW || item == Items.SHIELD) {
      return item;
    }

    // Weapons
    for(Item[] row: WEAPON_TIERS) {
      for(int i = 0; i < row.length; i++) {
        if(row[i] == item) return highestAffordable(row, villagerLevel, i);
      }
    }

    // Tools
    for(Item[] row: TOOL_TIERS) {
      for(int i = 0; i < row.length; i++) {
        if(row[i] == item) return highestAffordable(row, villagerLevel, i);
      }
    }

    // Armor (resolve chainmail to copper for downgrade target)
    Item resolved = CHAINMAIL_TO_COPPER.getOrDefault(item, item);
    for(Item[] row: ARMOR_TIERS) {
      for(int i = 0; i < row.length; i++) {
        if(row[i] == resolved) return highestAffordable(row, villagerLevel, i);
      }
    }

    return item;
  }

  private static int getArmorSlot(Item item)
  {
    Item[] helmets  = {Items.LEATHER_HELMET, Items.COPPER_HELMET, Items.CHAINMAIL_HELMET, Items.IRON_HELMET, Items.DIAMOND_HELMET, Items.GOLDEN_HELMET};
    Item[] chests   = {Items.LEATHER_CHESTPLATE, Items.COPPER_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE, Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.GOLDEN_CHESTPLATE};
    Item[] leggings = {Items.LEATHER_LEGGINGS, Items.COPPER_LEGGINGS, Items.CHAINMAIL_LEGGINGS, Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS, Items.GOLDEN_LEGGINGS};
    Item[] boots    = {Items.LEATHER_BOOTS, Items.COPPER_BOOTS, Items.CHAINMAIL_BOOTS, Items.IRON_BOOTS, Items.DIAMOND_BOOTS, Items.GOLDEN_BOOTS};
    for(Item i: helmets)
      if(i == item) return 0;
    for(Item i: chests)
      if(i == item) return 1;
    for(Item i: leggings)
      if(i == item) return 2;
    for(Item i: boots)
      if(i == item) return 3;
    return -1;
  }

  private static Item highestAffordable(Item[] row, int villagerLevel, int inputTierIndex)
  {
    Item best = row[0];
    for(int i = 0; i <= inputTierIndex && i < row.length; i++) {
      if(row[i] != null && TIER_MIN_VILLAGER_LEVEL[i] <= villagerLevel) {
        best = row[i];
      }
    }
    return best;
  }

  ///  Returns true if the item is netherite gear this profession must *refuse*.
  ///
  /// Every netherite path in the mod funnels through here -- the refusal message, the
  /// preview, the pickup scan and the offer loop -- so `teaching.allow_netherite` is
  /// consulted at this one point and the rest follows. A category that is switched on
  /// returns false, and the item carries on into the ordinary teachable path.
  ///
  /// Axes are tools here, not weapons, so enabling weapons alone will not admit one.
  public static boolean isNetheriteForProfession(Item item, String profession)
  {
    var allow = cx.gid.minecraft.tradeschool.config.Config.get().teaching.allowNetherite;

    if(profession.contains("weaponsmith")) {
      if(NETHERITE_WEAPONS.contains(item)) return !allow.weapons;
      // A weaponsmith deals in axes too, but for this switch an axe is a tool.
      if(NETHERITE_TOOLS.contains(item)) return !allow.tools;
      return false;
    }
    if(profession.contains("toolsmith"))
      return NETHERITE_TOOLS.contains(item) && !allow.tools;
    if(profession.contains("armorer"))
      return NETHERITE_ARMOR.contains(item) && !allow.armor;
    return false;
  }

  ///  Returns true if this item is netherite gear, regardless of profession or config.
  public static boolean isNetherite(Item item)
  {
    return NETHERITE_WEAPONS.contains(item) || NETHERITE_TOOLS.contains(item)
        || NETHERITE_ARMOR.contains(item);
  }

  ///  Returns true if the item is one this profession handles (any tier, excluding netherite).
  public static boolean isItemForProfession(Item item, String profession)
  {
    if(profession.contains("librarian")) return item == Items.ENCHANTED_BOOK;
    if(profession.contains("weaponsmith")) return isWeapon(item);
    if(profession.contains("toolsmith")) return isTool(item);
    if(profession.contains("armorer")) return isArmor(item) || item == Items.SHIELD;
    if(profession.contains("fletcher")) return isRangedWeapon(item);
    return false;
  }

  /// Returns true if the item is teachable at any level for the given profession.
  /// Excludes netherite and items not relevant to the profession.
  public static boolean isTeachableForProfession(Item item, String profession)
  {
    if(isNetheriteForProfession(item, profession)) return false;
    return isItemForProfession(item, profession);
  }

  /// Returns the minimum villager level required to teach this item without downgrade.
  /// For shields: level 3 (armorer journeyman).
  public static int getMinVillagerLevelForItem(Item item)
  {
    if(item == Items.SHIELD) return 3;
    if(isGoldWeaponOrTool(item)) return 1;
    if(isGoldArmor(item)) return 2;
    int tier = getTierIndex(item);
    if(tier < 0) return Integer.MAX_VALUE;
    return TIER_MIN_VILLAGER_LEVEL[tier];
  }

  /// Analyzes an item stack, computing what the villager would learn.
  /// - Downgrades material to villager level
  /// - Caps enchantment levels to villager level
  /// - Strips customisation components (trim, dye, name, lore) from knowledge
  /// - Preserves gameplay components (damage, repair_cost) in knowledge
  /// - Returns both the ItemKnowledge and a human-readable learn message
  public static AnalysisResult analyzeItem(ItemStack stack, int villagerLevel, String professionLabel, net.minecraft.core.HolderLookup.Provider registries)
  {
    Item originalItem      = stack.getItem();
    Item learnedItem       = downgradeItemToLevel(originalItem, villagerLevel);
    boolean itemDowngraded = learnedItem != originalItem;

    // Process enchantments (cap to villager level, skip untradable/unlearnable)
    ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
    // For librarians, use stored enchantments
    if(enchantments.isEmpty()) {
      enchantments = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
    }

    // Filled in whatever order the stack yields -- which is hash order, since
    // ItemEnchantments is backed by an Object2IntOpenHashMap -- and sorted into tooltip
    // order once complete. Sorting here rather than at each message means the trade
    // item, the chat line and the title all carry the same sequence, and it is the
    // sequence the player already sees in tooltips.
    Map<Holder<Enchantment>, Integer> learnedEnchantments = new LinkedHashMap<>();

    for(Holder<Enchantment> ench: enchantments.keySet()) {
      int originalLevel     = enchantments.getLevel(ench);
      int maxLevel          = EnchantmentProperties.getMaxLevel(ench);
      boolean isSingleLevel = maxLevel == 1;
      int minVillagerLevel  = isSingleLevel
           ? cx.gid.minecraft.tradeschool.config.Config.get()
                .teaching.singleLevelEnchantmentMinimumLevel
           : 1;

      if(!EnchantmentProperties.isTradable(ench)) {
        continue;
      }

      if(villagerLevel < minVillagerLevel) {
        continue;
      }

      int learnedLevel = isSingleLevel ? 1 : Math.min(originalLevel, villagerLevel);
      learnedEnchantments.put(ench, learnedLevel);
    }

    learnedEnchantments = EnchantmentProperties.inTooltipOrder(learnedEnchantments, registries);

    // Preserved gameplay components (damage, repair_cost) -- included in learned trade
    DataComponentPatch.Builder preserved = DataComponentPatch.builder();
    for(var type: PRESERVE_COMPONENTS) {
      addIfPresent(stack, preserved, type);
    }
    // Note: trim, dye, name, lore are stripped -- villager sells clean items.
    // FUTURE: re-evaluate once trim/dye exploit analysis is complete.

    Holder<Item> itemHolder = net.minecraft.core.registries.BuiltInRegistries.ITEM.wrapAsHolder(learnedItem);
    ItemKnowledge knowledge = new ItemKnowledge(itemHolder, learnedEnchantments, villagerLevel, preserved.build());
    // Described from the ordered map rather than from the stack: the stack's
    // ItemEnchantments is hash-ordered, so building the text from it would state the
    // enchantments in a different order here than everywhere else.
    return new AnalysisResult(knowledge, Describe.item(learnedItem, learnedEnchantments));
  }

  @SuppressWarnings("unchecked")
  private static <T> void addIfPresent(ItemStack stack, DataComponentPatch.Builder builder, net.minecraft.core.component.DataComponentType<T> type)
  {
    T value = stack.get(type);
    if(value != null) builder.set(type, value);
  }

  /// Determines whether the teach result is a "full learn" -- no material downgrade and no
  /// enchantment level was capped. Customisation stripping (trim, dye, name) does NOT count
  /// as a downgrade: the villager always sells a clean item, and the player gets emeralds.
  /// Full learn → player gets emeralds. Partial learn → player gets the result item.
  public static boolean isFullLearn(ItemStack offered, ItemKnowledge result)
  {
    Item learnedItem = result.getBaseItem().value();
    if(learnedItem != offered.getItem()) return false; // material downgraded

    // Check if any enchantment was capped (offered level > learned level)
    ItemEnchantments offeredEnchs = offered.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
    if(offeredEnchs.isEmpty()) {
      offeredEnchs = offered.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
    }
    Map<Holder<Enchantment>, Integer> learnedEnchs = result.getEnchantments();
    for(var ench: offeredEnchs.keySet()) {
      int offeredLevel     = offeredEnchs.getLevel(ench);
      Integer learnedLevel = learnedEnchs.get(ench);
      if(learnedLevel == null || learnedLevel < offeredLevel) return false;
    }

    return true;
  }

  // ── item type predicates ──────────────────────────────────────────────────

  private static boolean isWeapon(Item item)
  {
    if(NETHERITE_WEAPONS.contains(item)) return true;
    // A weaponsmith deals in axes, and netherite axes live in NETHERITE_TOOLS.
    if(item == Items.NETHERITE_AXE) return true;
    if(GOLD_WEAPONS_TOOLS.contains(item) && (item == Items.GOLDEN_SWORD || item == Items.GOLDEN_AXE)) return true;
    for(Item[] row: WEAPON_TIERS)
      for(Item i: row)
        if(i == item) return true;
    return false;
  }

  /// Axes are both a weapon and a tool, as in vanilla: toolsmiths and weaponsmiths each
  /// deal in them. They live in WEAPON_TIERS for tier/downgrade purposes, so the tool
  /// predicate has to name them explicitly.
  private static boolean isAxe(Item item)
  {
    return item == Items.WOODEN_AXE || item == Items.STONE_AXE || item == Items.COPPER_AXE
        || item == Items.IRON_AXE || item == Items.DIAMOND_AXE || item == Items.GOLDEN_AXE;
  }

  private static boolean isTool(Item item)
  {
    if(NETHERITE_TOOLS.contains(item)) return true;
    if(isAxe(item)) return true;
    if(GOLD_WEAPONS_TOOLS.contains(item) && item != Items.GOLDEN_SWORD && item != Items.GOLDEN_AXE) return true;
    for(Item[] row: TOOL_TIERS)
      for(Item i: row)
        if(i == item) return true;
    return false;
  }

  private static boolean isArmor(Item item)
  {
    if(NETHERITE_ARMOR.contains(item)) return true;
    if(GOLD_ARMOR.contains(item)) return true;
    if(CHAINMAIL_TO_COPPER.containsKey(item)) return true;
    for(Item[] row: ARMOR_TIERS)
      for(Item i: row)
        if(i == item) return true;
    return false;
  }

  private static boolean isRangedWeapon(Item item)
  {
    // Tipped arrows are deliberately excluded: their effect lives in POTION_CONTENTS,
    // not in an enchantment component, so the teaching pipeline would see an unenchanted
    // item and have the villager sell plain arrows with the potion stripped off.
    return item == Items.BOW || item == Items.CROSSBOW;
  }

  // ── string helpers ────────────────────────────────────────────────────────

  public static String itemDisplayName(Item item)
  {
    String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(item)
                    .getPath()
                    .replace('_', ' ');
    return capitalise(id);
  }

  private static String capitalise(String s)
  {
    if(s.isEmpty()) return s;
    String[] words   = s.split(" ");
    StringBuilder sb = new StringBuilder();
    for(String w: words) {
      if(!w.isEmpty()) {
        if(sb.length() > 0) sb.append(' ');
        sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
      }
    }
    return sb.toString();
  }
}
