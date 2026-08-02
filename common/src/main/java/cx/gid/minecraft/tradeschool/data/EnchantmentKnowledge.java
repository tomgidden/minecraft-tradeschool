package cx.gid.minecraft.tradeschool.data;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One lesson a librarian has learned: the enchantments from a single book.
 *
 * A book may carry several enchantments, and the villager learns all of them that it is
 * capable of learning — earlier versions kept only one, arbitrarily chosen, which quietly
 * discarded the rest of a Protection + Unbreaking book.
 *
 * Order is preserved ({@link LinkedHashMap}) so the villager's trade lists its
 * enchantments the same way every time. A {@code HashMap} here made the order shift
 * between openings, which matters more than it sounds: a player hunting for the right book
 * in a full inventory is matching on the name.
 */
public class EnchantmentKnowledge {

    /** Enchantment to level, in the order they were learned. */
    private final Map<Holder<Enchantment>, Integer> enchantments;

    /** The villager's profession level (1–5) when this was taught. */
    private final int learnedAtLevel;

    public EnchantmentKnowledge(Map<Holder<Enchantment>, Integer> enchantments, int learnedAtLevel) {
        this.enchantments = new LinkedHashMap<>(enchantments);
        this.learnedAtLevel = learnedAtLevel;
    }

    /** Convenience for a single-enchantment lesson. */
    public EnchantmentKnowledge(Holder<Enchantment> enchantment, int learnedAtLevel, int enchantmentLevel) {
        this(Map.of(enchantment, enchantmentLevel), learnedAtLevel);
    }

    public Map<Holder<Enchantment>, Integer> getEnchantments() {
        return enchantments;
    }

    /**
     * The first enchantment learned, for callers that can only show one.
     *
     * @deprecated Prefer {@link #getEnchantments()}; this loses everything after the first.
     */
    @Deprecated
    public Holder<Enchantment> getEnchantment() {
        return enchantments.keySet().iterator().next();
    }

    public int getLearnedAtLevel() {
        return learnedAtLevel;
    }

    /**
     * The stored level of the first enchantment. Fixed when learned, so levelling the
     * villager up does not retroactively improve an old lesson.
     *
     * @deprecated Prefer {@link #getEnchantments()}.
     */
    @Deprecated
    public int getEnchantmentLevel() {
        return enchantments.values().iterator().next();
    }

    /** True if this lesson already covers the same enchantments at the same levels. */
    public boolean matches(Map<Holder<Enchantment>, Integer> other) {
        return enchantments.equals(other);
    }

    public CompoundTag toNbt(HolderLookup.Provider registryAccess) {
        CompoundTag nbt = new CompoundTag();
        ListTag list = new ListTag();

        for (Map.Entry<Holder<Enchantment>, Integer> entry : enchantments.entrySet()) {
            entry.getKey().unwrapKey().ifPresent(key -> {
                CompoundTag one = new CompoundTag();
                one.putString("Id", key.identifier().toString());
                one.putInt("Level", entry.getValue());
                list.add(one);
            });
        }

        nbt.put("Enchantments", list);
        nbt.putInt("LearnedAtLevel", learnedAtLevel);
        return nbt;
    }

    /**
     * Reads a lesson, accepting both the current list form and the single-enchantment
     * form written before multi-enchantment books were supported. Villagers taught by an
     * older build therefore keep what they knew rather than losing it on upgrade.
     */
    public static EnchantmentKnowledge fromNbt(CompoundTag nbt, HolderLookup.Provider registryAccess) {
        int learnedAtLevel = nbt.getInt("LearnedAtLevel").orElse(1);
        var registry = registryAccess.lookupOrThrow(Registries.ENCHANTMENT);
        Map<Holder<Enchantment>, Integer> found = new LinkedHashMap<>();

        var list = nbt.getList("Enchantments");
        if (list.isPresent() && !list.get().isEmpty()) {
            for (Tag tag : list.get()) {
                if (!(tag instanceof CompoundTag one)) continue;
                String id = one.getString("Id").orElse(null);
                if (id == null) continue;
                resolve(registry, id).ifPresent(
                    holder -> found.put(holder, one.getInt("Level").orElse(1)));
            }
        } else {
            // Pre-multi-enchantment format: a single "Enchantment" string.
            String id = nbt.getString("Enchantment").orElse(null);
            if (id != null) {
                resolve(registry, id).ifPresent(
                    holder -> found.put(holder, nbt.getInt("EnchantmentLevel").orElse(1)));
            }
        }

        if (found.isEmpty()) {
            // An enchantment removed by a datapack or MC update leaves nothing to sell.
            // Returning null lets the caller drop the lesson rather than crash on load.
            return null;
        }
        return new EnchantmentKnowledge(found, learnedAtLevel);
    }

    private static java.util.Optional<Holder<Enchantment>> resolve(
            HolderLookup.RegistryLookup<Enchantment> registry, String id) {
        try {
            Identifier location = Identifier.parse(id);
            return registry.get(ResourceKey.create(Registries.ENCHANTMENT, location))
                .map(h -> (Holder<Enchantment>) h);
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }
}
