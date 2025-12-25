package cx.gid.minecraft.tradeschool.data;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a learned item template for Weaponsmiths, Toolsmiths, and Armourers.
 * Stores the base item type, all enchantments with their levels (capped at time of learning),
 * and preserves item attributes like damage, repair cost, trim, custom name, etc.
 */
public class ItemKnowledge {
    private final Holder<Item> baseItem;
    private final Map<Holder<Enchantment>, Integer> enchantments; // Enchantment -> level
    private final int learnedAtLevel; // 1-5 (novice to master)
    private final DataComponentPatch preservedComponents; // Stores additional item attributes

    public ItemKnowledge(Holder<Item> baseItem, Map<Holder<Enchantment>, Integer> enchantments, int learnedAtLevel) {
        this(baseItem, enchantments, learnedAtLevel, DataComponentPatch.EMPTY);
    }

    public ItemKnowledge(Holder<Item> baseItem, Map<Holder<Enchantment>, Integer> enchantments, int learnedAtLevel, DataComponentPatch preservedComponents) {
        this.baseItem = baseItem;
        this.enchantments = new HashMap<>(enchantments);
        this.learnedAtLevel = learnedAtLevel;
        this.preservedComponents = preservedComponents;
    }

    public Holder<Item> getBaseItem() {
        return baseItem;
    }

    public Map<Holder<Enchantment>, Integer> getEnchantments() {
        return new HashMap<>(enchantments);
    }

    public int getLearnedAtLevel() {
        return learnedAtLevel;
    }

    /**
     * Creates an ItemStack from this knowledge.
     * Applies all learned enchantments and preserved attributes to the item.
     *
     * @return An ItemStack with the base item, all enchantments, and preserved attributes
     */
    public ItemStack createItemStack() {
        ItemStack stack = new ItemStack(baseItem);

        // Apply enchantments
        if (!enchantments.isEmpty()) {
            ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            for (Map.Entry<Holder<Enchantment>, Integer> entry : enchantments.entrySet()) {
                mutable.set(entry.getKey(), entry.getValue());
            }
            stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
        }

        // Apply preserved components (damage, repair cost, trim, custom name, etc.)
        // Note: We don't preserve enchantments here since we already set them above
        if (!preservedComponents.isEmpty()) {
            stack.applyComponents(preservedComponents);
        }

        return stack;
    }

    /**
     * Serializes this knowledge to NBT.
     */
    public CompoundTag toNbt(HolderLookup.Provider registryAccess) {
        CompoundTag nbt = new CompoundTag();

        // Store base item as resource key
        baseItem.unwrapKey().ifPresent(key -> {
            String keyStr = key.toString();
            // Format: ResourceKey[minecraft:item / minecraft:diamond_chestplate]
            int slashIndex = keyStr.lastIndexOf(" / ");
            int endBracket = keyStr.lastIndexOf("]");
            if (slashIndex != -1 && endBracket != -1) {
                String itemId = keyStr.substring(slashIndex + 3, endBracket);
                nbt.putString("BaseItem", itemId);
            }
        });

        nbt.putInt("LearnedAtLevel", learnedAtLevel);

        // Store enchantments as list
        ListTag enchantmentList = new ListTag();
        for (Map.Entry<Holder<Enchantment>, Integer> entry : enchantments.entrySet()) {
            CompoundTag enchTag = new CompoundTag();

            entry.getKey().unwrapKey().ifPresent(key -> {
                String keyStr = key.toString();
                int slashIndex = keyStr.lastIndexOf(" / ");
                int endBracket = keyStr.lastIndexOf("]");
                if (slashIndex != -1 && endBracket != -1) {
                    String enchantmentId = keyStr.substring(slashIndex + 3, endBracket);
                    enchTag.putString("Enchantment", enchantmentId);
                }
            });

            enchTag.putInt("Level", entry.getValue());
            enchantmentList.add(enchTag);
        }
        nbt.put("Enchantments", enchantmentList);

        // Store preserved components (damage, repair cost, trim, etc.)
        if (!preservedComponents.isEmpty()) {
            Tag componentsTag = DataComponentPatch.CODEC.encodeStart(registryAccess.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), preservedComponents)
                .getOrThrow();
            nbt.put("PreservedComponents", componentsTag);
        }

        return nbt;
    }

    /**
     * Deserializes knowledge from NBT.
     */
    public static ItemKnowledge fromNbt(CompoundTag nbt, HolderLookup.Provider registryAccess) {
        String itemId = nbt.getString("BaseItem").orElse("minecraft:iron_sword");
        int learnedAtLevel = nbt.getInt("LearnedAtLevel").orElse(1);

        // Look up base item in registry
        Identifier itemLoc = Identifier.parse(itemId);
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, itemLoc);
        var itemRegistry = registryAccess.lookupOrThrow(Registries.ITEM);
        Holder<Item> itemHolder = itemRegistry.getOrThrow(itemKey);

        // Load enchantments
        Map<Holder<Enchantment>, Integer> enchantments = new HashMap<>();
        ListTag enchantmentList = nbt.getList("Enchantments").orElse(new ListTag());
        var enchantmentRegistry = registryAccess.lookupOrThrow(Registries.ENCHANTMENT);

        for (Tag tag : enchantmentList) {
            CompoundTag enchTag = (CompoundTag) tag;
            String enchantmentId = enchTag.getString("Enchantment").orElse("");
            int level = enchTag.getInt("Level").orElse(1);

            if (!enchantmentId.isEmpty()) {
                try {
                    Identifier enchLoc = Identifier.parse(enchantmentId);
                    ResourceKey<Enchantment> enchKey = ResourceKey.create(Registries.ENCHANTMENT, enchLoc);
                    Holder<Enchantment> enchHolder = enchantmentRegistry.getOrThrow(enchKey);
                    enchantments.put(enchHolder, level);
                } catch (Exception e) {
                    // Skip invalid enchantments
                }
            }
        }

        // Load preserved components (damage, repair cost, trim, etc.)
        DataComponentPatch preservedComponents = DataComponentPatch.EMPTY;
        if (nbt.contains("PreservedComponents")) {
            try {
                preservedComponents = DataComponentPatch.CODEC.decode(
                    registryAccess.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),
                    nbt.get("PreservedComponents")
                ).getOrThrow().getFirst();
            } catch (Exception e) {
                // Skip if components can't be loaded
            }
        }

        return new ItemKnowledge(itemHolder, enchantments, learnedAtLevel, preservedComponents);
    }
}
