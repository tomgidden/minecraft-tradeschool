package cx.gid.minecraft.tradeschool.data;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * Represents an item that has been validated and is waiting to be picked up by a villager.
 */
public class PendingPickup {
    public final ItemEntity itemEntity;
    public final ItemStack itemStack;
    public final boolean isBook;
    public final Holder<Enchantment> enchantment; // For books
    public final int enchantmentLevel; // For books
    public int ticksRemaining;

    // Constructor for enchanted books
    public PendingPickup(ItemEntity entity, ItemStack stack, Holder<Enchantment> ench, int level, int delay) {
        this.itemEntity = entity;
        this.itemStack = stack.copy();
        this.isBook = true;
        this.enchantment = ench;
        this.enchantmentLevel = level;
        this.ticksRemaining = delay;
    }

    // Constructor for enchanted items
    public PendingPickup(ItemEntity entity, ItemStack stack, int delay) {
        this.itemEntity = entity;
        this.itemStack = stack.copy();
        this.isBook = false;
        this.enchantment = null;
        this.enchantmentLevel = 0;
        this.ticksRemaining = delay;
    }
}
