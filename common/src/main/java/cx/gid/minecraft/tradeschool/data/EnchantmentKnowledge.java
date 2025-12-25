package cx.gid.minecraft.tradeschool.data;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * Represents a single learned enchantment type.
 * Stores the enchantment, the profession level it was learned at, and the enchantment level.
 */
public class EnchantmentKnowledge {
    private final Holder<Enchantment> enchantment;
    private final int learnedAtLevel;   // 1-5 (novice to master)
    private final int enchantmentLevel; // The level of the enchantment (capped at time of learning)

    public EnchantmentKnowledge(Holder<Enchantment> enchantment, int learnedAtLevel, int enchantmentLevel) {
        this.enchantment = enchantment;
        this.learnedAtLevel = learnedAtLevel;
        this.enchantmentLevel = enchantmentLevel;
    }

    public Holder<Enchantment> getEnchantment() {
        return enchantment;
    }

    public int getLearnedAtLevel() {
        return learnedAtLevel;
    }

    /**
     * Gets the enchantment level that was stored when this enchantment was learned.
     * This level is fixed at the time of learning and does not change as the villager levels up.
     */
    public int getEnchantmentLevel() {
        return enchantmentLevel;
    }

    /**
     * Serializes this knowledge to NBT.
     */
    public CompoundTag toNbt(HolderLookup.Provider registryAccess) {
        CompoundTag nbt = new CompoundTag();

        // Store enchantment as resource key
        enchantment.unwrapKey().ifPresent(key -> {

            // ResourceKey.toString() gives "ResourceKey[namespace:path]"
            // We need just the path part. The resource key stores registry + id
            // Extract just the ID part by using the key's identifier
            String keyStr = key.toString();
            
            // Format is like: ResourceKey[minecraft:enchantment / minecraft:sharpness]
            // We want: minecraft:sharpness
            int slashIndex = keyStr.lastIndexOf(" / ");
            int endBracket = keyStr.lastIndexOf("]");
            if (slashIndex != -1 && endBracket != -1) {
                String enchantmentId = keyStr.substring(slashIndex + 3, endBracket);
                nbt.putString("Enchantment", enchantmentId);
            }
        });

        nbt.putInt("LearnedAtLevel", learnedAtLevel);
        nbt.putInt("EnchantmentLevel", enchantmentLevel);
        return nbt;
    }

    /**
     * Deserializes knowledge from NBT.
     */
    public static EnchantmentKnowledge fromNbt(CompoundTag nbt, HolderLookup.Provider registryAccess) {
        String enchantmentId = nbt.getString("Enchantment").orElse("minecraft:protection");
        int learnedAtLevel = nbt.getInt("LearnedAtLevel").orElse(1);
        int enchantmentLevel = nbt.getInt("EnchantmentLevel").orElse(1);

        // Look up enchantment in registry
        Identifier enchantmentLoc = Identifier.parse(enchantmentId);
        ResourceKey<Enchantment> enchantmentKey = ResourceKey.create(Registries.ENCHANTMENT, enchantmentLoc);

        // Get the enchantment holder from registry
        var enchantmentRegistry = registryAccess.lookupOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> enchantmentHolder = enchantmentRegistry.getOrThrow(enchantmentKey);

        return new EnchantmentKnowledge(enchantmentHolder, learnedAtLevel, enchantmentLevel);
    }
}
