package cx.gid.minecraft.tradeschool.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Stores the learned enchantments and items for a single villager.
 * Persisted to NBT and attached to villager entities.
 * Librarians learn enchantments, while Weaponsmiths/Toolsmiths/Armourers/Fletchers learn items.
 */
public class VillagerKnowledgeData {
    private final UUID villagerUUID;
    private boolean hasInitialTrade; // Track if they've gotten their UUID-based trade

    // Map: profession level (1-5) → learned enchantment (for Librarians)
    // Max 5 entries (one per profession level)
    private final Map<Integer, EnchantmentKnowledge> knowledgeByLevel;

    // Map: "professionId:level" → learned item (for Weaponsmiths/Toolsmiths/Armourers/Fletchers)
    // Keyed by profession so a villager that changes job doesn't carry over knowledge.
    private final Map<String, ItemKnowledge> itemKnowledgeByProfessionLevel;

    public VillagerKnowledgeData(UUID uuid) {
        this.villagerUUID = uuid;
        this.hasInitialTrade = false;
        this.knowledgeByLevel = new HashMap<>();
        this.itemKnowledgeByProfessionLevel = new HashMap<>();
    }

    public UUID getVillagerUUID() {
        return villagerUUID;
    }

    public boolean hasInitialTrade() {
        return hasInitialTrade;
    }

    public void setHasInitialTrade(boolean hasInitialTrade) {
        this.hasInitialTrade = hasInitialTrade;
    }

    /**
     * Returns the enchantment learned at a specific profession level.
     * Returns null if no enchantment learned at that level yet.
     */
    public EnchantmentKnowledge getKnowledgeAtLevel(int professionLevel) {
        return knowledgeByLevel.get(professionLevel);
    }

    /**
     * Teaches the villager a new enchantment at their current profession level.
     * If an enchantment already exists at this level, it will be replaced.
     *
     * @param professionLevel The villager's current profession level (1-5)
     * @param enchantment The enchantment to learn
     * @param enchantmentLevel The level of the enchantment (capped by villager level)
     * @return true if taught successfully
     */
    public boolean teachEnchantment(int professionLevel, Holder<Enchantment> enchantment, int enchantmentLevel) {
        knowledgeByLevel.put(professionLevel, new EnchantmentKnowledge(enchantment, professionLevel, enchantmentLevel));
        return true;
    }

    /**
     * Gets all learned enchantments up to and including the given profession level.
     * This is used to generate all trades the villager can offer at their current level.
     *
     * @param maxLevel The villager's current profession level
     * @return List of all learned enchantments from level 1 to maxLevel
     */
    public List<EnchantmentKnowledge> getKnowledgeUpToLevel(int maxLevel) {
        return knowledgeByLevel.entrySet().stream()
                .filter(entry -> entry.getKey() <= maxLevel)
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    /**
     * Checks if the villager has learned any enchantments.
     */
    public boolean hasLearnedAny() {
        return !knowledgeByLevel.isEmpty();
    }

    /**
     * Gets the total number of enchantments learned (max 5).
     */
    public int getLearnedCount() {
        return knowledgeByLevel.size();
    }

    /**
     * Returns the item learned at a specific profession level.
     * Profession is locked on first trade so no professionId needed in the key.
     */
    public ItemKnowledge getItemKnowledgeAtLevel(int professionLevel) {
        // New key format: just the level
        ItemKnowledge k = itemKnowledgeByProfessionLevel.get(String.valueOf(professionLevel));
        if (k != null) return k;
        // Back-compat: try old "professionId:level" keys
        for (Map.Entry<String, ItemKnowledge> entry : itemKnowledgeByProfessionLevel.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith(":" + professionLevel)) return entry.getValue();
        }
        return null;
    }

    /** Back-compat overload that accepts a professionId (now ignored). */
    public ItemKnowledge getItemKnowledgeAtLevel(String professionId, int professionLevel) {
        return getItemKnowledgeAtLevel(professionLevel);
    }

    /**
     * Teaches the villager a new item at their current profession level.
     * Keyed by level only — profession is locked on first trade.
     */
    public boolean teachItem(int professionLevel, ItemKnowledge itemKnowledge) {
        itemKnowledgeByProfessionLevel.put(String.valueOf(professionLevel), itemKnowledge);
        return true;
    }

    /** Back-compat overload that accepts a professionId (now ignored). */
    public boolean teachItem(String professionId, int professionLevel, ItemKnowledge itemKnowledge) {
        return teachItem(professionLevel, itemKnowledge);
    }

    /**
     * Gets all learned items up to and including the given profession level.
     */
    public List<ItemKnowledge> getItemKnowledgeUpToLevel(int maxLevel) {
        List<ItemKnowledge> result = new ArrayList<>();
        for (int lvl = 1; lvl <= maxLevel; lvl++) {
            ItemKnowledge k = getItemKnowledgeAtLevel(lvl);
            if (k != null) result.add(k);
        }
        return result;
    }

    /** Back-compat overload that accepts a professionId (now ignored). */
    public List<ItemKnowledge> getItemKnowledgeUpToLevel(String professionId, int maxLevel) {
        return getItemKnowledgeUpToLevel(maxLevel);
    }

    /**
     * Checks if the villager has learned any items for the given profession.
     */
    public boolean hasLearnedAnyItems(String professionId) {
        return itemKnowledgeByProfessionLevel.keySet().stream().anyMatch(k -> k.startsWith(professionId + ":"));
    }

    public boolean hasLearnedAnyItems() {
        return !itemKnowledgeByProfessionLevel.isEmpty();
    }

    /**
     * Gets the total number of items learned across all professions.
     */
    public int getLearnedItemCount() {
        return itemKnowledgeByProfessionLevel.size();
    }

    /**
     * Checks if the villager already knows a specific enchantment (at any level).
     * Used to prevent teaching duplicate enchantments.
     *
     * @param enchantment The enchantment to check
     * @return true if the villager already knows this enchantment
     */
    public boolean alreadyKnowsEnchantment(Holder<Enchantment> enchantment) {
        for (EnchantmentKnowledge knowledge : knowledgeByLevel.values()) {
            if (knowledge.getEnchantment().equals(enchantment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the villager already knows a specific item type for the given profession (at any level).
     */
    public boolean alreadyKnowsItem(String professionId, net.minecraft.world.item.Item item) {
        for (Map.Entry<String, ItemKnowledge> entry : itemKnowledgeByProfessionLevel.entrySet()) {
            if (entry.getKey().startsWith(professionId + ":") && entry.getValue().getBaseItem().value().equals(item)) {
                return true;
            }
        }
        return false;
    }

    public boolean alreadyKnowsItem(net.minecraft.world.item.Item item) {
        for (ItemKnowledge knowledge : itemKnowledgeByProfessionLevel.values()) {
            if (knowledge.getBaseItem().value().equals(item)) return true;
        }
        return false;
    }

    /**
     * Serializes this data to NBT for persistence.
     */
    public CompoundTag toNbt(HolderLookup.Provider registryAccess) {
        CompoundTag nbt = new CompoundTag();

        // Store UUID as two longs
        nbt.putLong("VillagerUUIDMost", villagerUUID.getMostSignificantBits());
        nbt.putLong("VillagerUUIDLeast", villagerUUID.getLeastSignificantBits());
        nbt.putBoolean("HasInitialTrade", hasInitialTrade);

        // Serialize learned enchantments
        ListTag knowledgeList = new ListTag();
        for (Map.Entry<Integer, EnchantmentKnowledge> entry : knowledgeByLevel.entrySet()) {
            CompoundTag knowledgeTag = new CompoundTag();
            knowledgeTag.putInt("Level", entry.getKey());
            knowledgeTag.put("Knowledge", entry.getValue().toNbt(registryAccess));
            knowledgeList.add(knowledgeTag);
        }
        nbt.put("LearnedEnchantments", knowledgeList);

        // Serialize learned items (key = "professionId:level")
        ListTag itemKnowledgeList = new ListTag();
        for (Map.Entry<String, ItemKnowledge> entry : itemKnowledgeByProfessionLevel.entrySet()) {
            CompoundTag itemTag = new CompoundTag();
            itemTag.putString("ProfessionLevelKey", entry.getKey());
            itemTag.put("Knowledge", entry.getValue().toNbt(registryAccess));
            itemKnowledgeList.add(itemTag);
        }
        nbt.put("LearnedItems", itemKnowledgeList);

        return nbt;
    }

    /**
     * Deserializes data from NBT.
     */
    public static VillagerKnowledgeData fromNbt(CompoundTag nbt, HolderLookup.Provider registryAccess) {
        // Reconstruct UUID from two longs
        long most = nbt.getLong("VillagerUUIDMost").orElse(0L);
        long least = nbt.getLong("VillagerUUIDLeast").orElse(0L);
        UUID uuid = new UUID(most, least);

        VillagerKnowledgeData data = new VillagerKnowledgeData(uuid);
        data.hasInitialTrade = nbt.getBoolean("HasInitialTrade").orElse(false);

        // Deserialize learned enchantments
        ListTag knowledgeList = nbt.getList("LearnedEnchantments").orElse(new ListTag());
        for (Tag tag : knowledgeList) {
            CompoundTag knowledgeTag = (CompoundTag) tag;
            int level = knowledgeTag.getInt("Level").orElse(1);
            CompoundTag knowledgeNbt = knowledgeTag.getCompound("Knowledge").orElse(new CompoundTag());
            EnchantmentKnowledge knowledge = EnchantmentKnowledge.fromNbt(
                    knowledgeNbt,
                    registryAccess
            );
            data.knowledgeByLevel.put(level, knowledge);
        }

        // Deserialize learned items
        ListTag itemKnowledgeList = nbt.getList("LearnedItems").orElse(new ListTag());
        for (Tag tag : itemKnowledgeList) {
            CompoundTag itemTag = (CompoundTag) tag;
            // Support both old format (Level int) and new format (ProfessionLevelKey string)
            String key = itemTag.getString("ProfessionLevelKey").orElse(null);
            if (key == null) {
                int level = itemTag.getInt("Level").orElse(1);
                key = "unknown:" + level;
            }
            CompoundTag itemNbt = itemTag.getCompound("Knowledge").orElse(new CompoundTag());
            ItemKnowledge itemKnowledge = ItemKnowledge.fromNbt(itemNbt, registryAccess);
            data.itemKnowledgeByProfessionLevel.put(key, itemKnowledge);
        }

        return data;
    }
}
