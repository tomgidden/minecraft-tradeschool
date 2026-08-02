package cx.gid.minecraft.tradeschool.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

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
        return teachEnchantments(professionLevel, Map.of(enchantment, enchantmentLevel));
    }

    /**
     * Teaches every enchantment from one book at the villager's current level.
     *
     * A book is one lesson however many enchantments it carries, so a Protection +
     * Unbreaking book produces a single trade selling both. Taking only the first — as
     * earlier versions did — silently threw away half of what the player handed over.
     *
     * @return false if this level's lesson is already used by something different, in
     *     which case nothing is stored and the caller should refund.
     */
    public boolean teachEnchantments(int professionLevel, Map<Holder<Enchantment>, Integer> enchantments) {
        if (enchantments.isEmpty()) return false;

        // One lesson per profession level. Refuse rather than overwrite: silently
        // replacing an earlier lesson loses knowledge the player paid for, and leaves
        // them holding a book that appeared to be accepted.
        EnchantmentKnowledge existing = knowledgeByLevel.get(professionLevel);
        if (existing != null && !existing.matches(enchantments)) {
            return false;
        }
        knowledgeByLevel.put(professionLevel,
            new EnchantmentKnowledge(enchantments, professionLevel));
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
            if (knowledge.getEnchantments().containsKey(enchantment)) {
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
    /**
     * Writes the villager's lessons.
     *
     * One flat list of learned items. Earlier versions kept enchantments and items in
     * separate lists with different key schemes, wrapped each record in a redundant
     * {@code Level}/{@code Knowledge} pair that duplicated a value already inside the
     * record, and stored the villager's UUID again beside the one the entity already has.
     * None of that carried information.
     */
    public CompoundTag toNbt(HolderLookup.Provider registryAccess) {
        CompoundTag nbt = new CompoundTag();
        nbt.putBoolean("HasInitialTrade", hasInitialTrade);

        ListTag learned = new ListTag();
        for (Map.Entry<Integer, EnchantmentKnowledge> entry : knowledgeByLevel.entrySet()) {
            learned.add(toLearnedItem(entry.getKey(), entry.getValue())
                .toNbt(registryAccess));
        }
        for (Map.Entry<String, ItemKnowledge> entry : itemKnowledgeByProfessionLevel.entrySet()) {
            ItemKnowledge k = entry.getValue();
            learned.add(new LearnedItem(k.createItemStack(), k.getLearnedAtLevel(), -1)
                .toNbt(registryAccess));
        }
        nbt.put("LearnedItems", learned);

        return nbt;
    }

    /** Renders an enchantment lesson as the book the villager sells. */
    private static LearnedItem toLearnedItem(int level, EnchantmentKnowledge knowledge) {
        ItemStack book = new ItemStack(net.minecraft.world.item.Items.ENCHANTED_BOOK);
        ItemEnchantments.Mutable enchantments =
            new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        knowledge.getEnchantments().forEach(enchantments::set);
        book.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
        return new LearnedItem(book, level, -1);
    }

    /**
     * Deserializes data from NBT.
     */
    /**
     * Reads a villager's lessons, accepting the current flat format and the older split
     * one. A villager taught under 26.0.1 keeps what it knew rather than forgetting on
     * upgrade — the population is small, and silently losing their work to a format
     * change is a worse outcome than carrying the migration.
     *
     * @param uuid the villager's own UUID; previously stored again in this tag, which was
     *     redundant since the data hangs off the entity that has it.
     */
    public static VillagerKnowledgeData fromNbt(CompoundTag nbt, HolderLookup.Provider registryAccess,
                                                UUID uuid) {
        VillagerKnowledgeData data = new VillagerKnowledgeData(uuid);
        data.hasInitialTrade = nbt.getBoolean("HasInitialTrade").orElse(false);

        // Current format: one flat list of stacks.
        boolean readFlat = false;
        for (Tag tag : nbt.getList("LearnedItems").orElse(new ListTag())) {
            if (!(tag instanceof CompoundTag entry)) continue;
            if (!entry.contains("Item")) break;  // old shape; fall through to migration
            readFlat = true;
            LearnedItem learned = LearnedItem.fromNbt(entry, registryAccess);
            if (learned != null) data.absorb(learned);
        }
        if (readFlat) return data;

        migrateLegacy(nbt, registryAccess, data);
        return data;
    }

    /** Back-compat overload for callers that have not been given the villager's UUID. */
    public static VillagerKnowledgeData fromNbt(CompoundTag nbt, HolderLookup.Provider registryAccess) {
        long most = nbt.getLong("VillagerUUIDMost").orElse(0L);
        long least = nbt.getLong("VillagerUUIDLeast").orElse(0L);
        return fromNbt(nbt, registryAccess, new UUID(most, least));
    }

    /**
     * Reads the 26.0.1 shape: two lists, each record wrapped in a Level/Knowledge pair.
     */
    private static void migrateLegacy(CompoundTag nbt, HolderLookup.Provider registryAccess,
                                      VillagerKnowledgeData data) {
        for (Tag tag : nbt.getList("LearnedEnchantments").orElse(new ListTag())) {
            if (!(tag instanceof CompoundTag wrapper)) continue;
            int level = wrapper.getInt("Level").orElse(1);
            CompoundTag inner = wrapper.getCompound("Knowledge").orElse(new CompoundTag());
            EnchantmentKnowledge knowledge = EnchantmentKnowledge.fromNbt(inner, registryAccess);
            if (knowledge != null) data.knowledgeByLevel.put(level, knowledge);
        }

        for (Tag tag : nbt.getList("LearnedItems").orElse(new ListTag())) {
            if (!(tag instanceof CompoundTag wrapper)) continue;
            String key = wrapper.getString("ProfessionLevelKey").orElse(null);
            if (key == null) key = String.valueOf(wrapper.getInt("Level").orElse(1));
            CompoundTag inner = wrapper.getCompound("Knowledge").orElse(new CompoundTag());
            ItemKnowledge item = ItemKnowledge.fromNbt(inner, registryAccess);
            if (item != null) data.itemKnowledgeByProfessionLevel.put(key, item);
        }
    }

    /**
     * Files a stored lesson back into the in-memory maps.
     *
     * The saved form is uniform, but the runtime still distinguishes enchantment lessons
     * (librarians) from item lessons (everyone else), so an enchanted book goes to one map
     * and anything else to the other. Merging those two maps properly is 26.1 work.
     */
    private void absorb(LearnedItem learned) {
        ItemStack stack = learned.getResult();
        int level = learned.getLearnedAt();

        if (stack.getItem() == net.minecraft.world.item.Items.ENCHANTED_BOOK) {
            ItemEnchantments stored =
                stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
            if (stored.isEmpty()) return;
            Map<Holder<Enchantment>, Integer> map = new LinkedHashMap<>();
            for (Holder<Enchantment> e : stored.keySet()) map.put(e, stored.getLevel(e));
            knowledgeByLevel.put(level, new EnchantmentKnowledge(map, level));
        } else {
            ItemEnchantments enchs =
                stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            Map<Holder<Enchantment>, Integer> map = new LinkedHashMap<>();
            for (Holder<Enchantment> e : enchs.keySet()) map.put(e, enchs.getLevel(e));

            var patch = net.minecraft.core.component.DataComponentPatch.builder();
            copyIfPresent(stack, patch, DataComponents.DAMAGE);
            copyIfPresent(stack, patch, DataComponents.REPAIR_COST);

            itemKnowledgeByProfessionLevel.put(String.valueOf(level), new ItemKnowledge(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.wrapAsHolder(stack.getItem()),
                map, level, patch.build()));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void copyIfPresent(ItemStack stack,
            net.minecraft.core.component.DataComponentPatch.Builder builder,
            net.minecraft.core.component.DataComponentType<T> type) {
        T value = stack.get(type);
        if (value != null) builder.set(type, value);
    }
}
