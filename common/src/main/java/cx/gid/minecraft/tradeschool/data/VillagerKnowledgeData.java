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

    // Map: profession level (1-5) → learned item (for Weaponsmiths/Toolsmiths/Armourers/Fletchers)
    // Max 5 entries (one per profession level)
    private final Map<Integer, ItemKnowledge> itemKnowledgeByLevel;

    public VillagerKnowledgeData(UUID uuid) {
        this.villagerUUID = uuid;
        this.hasInitialTrade = false;
        this.knowledgeByLevel = new HashMap<>();
        this.itemKnowledgeByLevel = new HashMap<>();
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
     * Returns null if no item learned at that level yet.
     */
    public ItemKnowledge getItemKnowledgeAtLevel(int professionLevel) {
        return itemKnowledgeByLevel.get(professionLevel);
    }

    /**
     * Teaches the villager a new item at their current profession level.
     * If an item already exists at this level, it will be replaced.
     *
     * @param professionLevel The villager's current profession level (1-5)
     * @param itemKnowledge The item knowledge to learn
     * @return true if taught successfully
     */
    public boolean teachItem(int professionLevel, ItemKnowledge itemKnowledge) {
        itemKnowledgeByLevel.put(professionLevel, itemKnowledge);
        return true;
    }

    /**
     * Gets all learned items up to and including the given profession level.
     *
     * @param maxLevel The villager's current profession level
     * @return List of all learned items from level 1 to maxLevel
     */
    public List<ItemKnowledge> getItemKnowledgeUpToLevel(int maxLevel) {
        return itemKnowledgeByLevel.entrySet().stream()
                .filter(entry -> entry.getKey() <= maxLevel)
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    /**
     * Checks if the villager has learned any items.
     */
    public boolean hasLearnedAnyItems() {
        return !itemKnowledgeByLevel.isEmpty();
    }

    /**
     * Gets the total number of items learned (max 5).
     */
    public int getLearnedItemCount() {
        return itemKnowledgeByLevel.size();
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
     * Checks if the villager already knows a specific item type (at any level).
     * Used to prevent teaching duplicate items.
     *
     * @param item The item type to check
     * @return true if the villager already knows this item type
     */
    public boolean alreadyKnowsItem(net.minecraft.world.item.Item item) {
        for (ItemKnowledge knowledge : itemKnowledgeByLevel.values()) {
            if (knowledge.getBaseItem().value().equals(item)) {
                return true;
            }
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

        // Serialize learned items
        ListTag itemKnowledgeList = new ListTag();
        for (Map.Entry<Integer, ItemKnowledge> entry : itemKnowledgeByLevel.entrySet()) {
            CompoundTag itemTag = new CompoundTag();
            itemTag.putInt("Level", entry.getKey());
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
            int level = itemTag.getInt("Level").orElse(1);
            CompoundTag itemNbt = itemTag.getCompound("Knowledge").orElse(new CompoundTag());
            ItemKnowledge itemKnowledge = ItemKnowledge.fromNbt(
                    itemNbt,
                    registryAccess
            );
            data.itemKnowledgeByLevel.put(level, itemKnowledge);
        }

        return data;
    }
}
