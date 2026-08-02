package cx.gid.minecraft.tradeschool.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * One thing a villager has been taught to make.
 *
 * Stored as an {@link ItemStack} rather than as a bespoke record of "base item plus
 * enchantment map". That choice does most of the work here:
 *
 * <ul>
 *   <li>A book with several enchantments is ordinary, not a special case. The previous
 *       structure held a single enchantment, so a Protection + Unbreaking book quietly
 *       lost half of itself.</li>
 *   <li>Anything else the mod might later preserve — trims, dye, custom names — comes
 *       free, because it is already a component on the stack.</li>
 *   <li>It matches the shape of what it becomes. A learned item is turned into an
 *       {@code Offers.Recipes} entry, and those store a stack in exactly this form, so
 *       the saved data reads the same way as the trade it produces.</li>
 * </ul>
 *
 * Price is deliberately <em>not</em> stored. It is derived from the item whenever trades
 * are built, so changing the pricing config affects villagers already taught rather than
 * only new ones.
 */
public class LearnedItem {

    /** What the villager makes: item, count, and every component that survived learning. */
    private final ItemStack result;

    /** The villager's profession level (1–5) when this was taught. */
    private final int learnedAt;

    /**
     * Where this trade sat in the offer list when first added, or -1 if unknown.
     *
     * Vanilla appends each level's trades on promotion and never reorders, so without a
     * remembered position a learned trade drifts to the bottom every time the list is
     * rebuilt. Clamped to the list length on use, since a datapack or game update may
     * leave the recorded index pointing past the end.
     */
    private final int pos;

    public LearnedItem(ItemStack result, int learnedAt, int pos) {
        this.result = result.copy();
        this.learnedAt = learnedAt;
        this.pos = pos;
    }

    public ItemStack getResult() {
        return result.copy();
    }

    public int getLearnedAt() {
        return learnedAt;
    }

    public int getPos() {
        return pos;
    }

    /** A copy with the position filled in, for recording where a trade first landed. */
    public LearnedItem withPos(int newPos) {
        return new LearnedItem(result, learnedAt, newPos);
    }

    public CompoundTag toNbt(HolderLookup.Provider registryAccess) {
        CompoundTag nbt = new CompoundTag();
        var ops = registryAccess.createSerializationContext(NbtOps.INSTANCE);
        ItemStack.CODEC.encodeStart(ops, result).result()
            .ifPresent(tag -> nbt.put("Item", tag));
        nbt.putInt("LearnedAt", learnedAt);
        if (pos >= 0) nbt.putInt("Pos", pos);
        return nbt;
    }

    /** @return the lesson, or null if the item no longer exists in this game version. */
    public static LearnedItem fromNbt(CompoundTag nbt, HolderLookup.Provider registryAccess) {
        var ops = registryAccess.createSerializationContext(NbtOps.INSTANCE);
        Tag itemTag = nbt.get("Item");
        if (itemTag == null) return null;

        ItemStack stack = ItemStack.CODEC.parse(ops, itemTag).result().orElse(null);
        if (stack == null || stack.isEmpty()) return null;

        return new LearnedItem(stack,
            nbt.getInt("LearnedAt").orElse(1),
            nbt.getInt("Pos").orElse(-1));
    }
}
