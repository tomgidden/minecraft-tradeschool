package cx.gid.minecraft.tradeschool.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/// One thing a villager has been taught to make.
///
/// Stored as an [ItemStack] rather than as a bespoke record of "base item plus
/// enchantment map". That choice does most of the work here:
///
///     * A book with several enchantments is ordinary, not a special case. The previous
///       structure held a single enchantment, so a Protection + Unbreaking book quietly
///       lost half of itself.
///     * Anything else the mod might later preserve — trims, dye, custom names — comes
///       free, because it is already a component on the stack.
///     * It matches the shape of what it becomes. A learned item is turned into an
///       `Offers.Recipes` entry, and those store a stack in exactly this form, so
///       the saved data reads the same way as the trade it produces.
///
/// Price is deliberately *not* stored. It is derived from the item whenever trades
/// are built, so changing the pricing config affects villagers already taught rather than
/// only new ones.
public class LearnedItem {

        ///  What the villager makes: item, count, and every component that survived learning.
    private final ItemStack result;

        ///  The villager's profession level (1–5) when this was taught.
    private final int learnedAt;

    public LearnedItem(ItemStack result, int learnedAt) {
        this.result = result.copy();
        this.learnedAt = learnedAt;
    }

    public ItemStack getResult() {
        return result.copy();
    }

    public int getLearnedAt() {
        return learnedAt;
    }

    public CompoundTag toNbt(HolderLookup.Provider registryAccess) {
        CompoundTag nbt = new CompoundTag();
        var ops = registryAccess.createSerializationContext(NbtOps.INSTANCE);
        ItemStack.CODEC.encodeStart(ops, result).result()
            .ifPresent(tag -> nbt.put("Item", tag));
        nbt.putInt("LearnedAt", learnedAt);
        return nbt;
    }

        ///  @return the lesson, or null if the item no longer exists in this game version.
    public static LearnedItem fromNbt(CompoundTag nbt, HolderLookup.Provider registryAccess) {
        var ops = registryAccess.createSerializationContext(NbtOps.INSTANCE);
        Tag itemTag = nbt.get("Item");
        if (itemTag == null) return null;

        ItemStack stack = ItemStack.CODEC.parse(ops, itemTag).result().orElse(null);
        if (stack == null || stack.isEmpty()) return null;

        // "Pos" may be present in worlds saved by 26.0.2 development builds; it is
        // ignored. Offer order now follows from never rebuilding the list, so a recorded
        // index has nothing left to correct.
        return new LearnedItem(stack, nbt.getInt("LearnedAt").orElse(1));
    }
}
