package cx.gid.minecraft.tradeschool.enchantment;

import cx.gid.minecraft.tradeschool.Constants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/// Registry for custom enchantments added by Trade School.
public class ModEnchantments {
        /// Curse of Copyright - prevents librarians from learning this enchantment.
    /// Can still be applied to items by players.
    public static final ResourceKey<Enchantment> CURSE_OF_COPYRIGHT =
        ResourceKey.create(
            Registries.ENCHANTMENT,
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "curse_of_copyright")
        );

        /// Checks if an enchantment holder is the Curse of Copyright.
    public static boolean isCurseOfCopyright(Holder<Enchantment> enchantment) {
        return enchantment.unwrapKey()
            .map(key -> key.equals(CURSE_OF_COPYRIGHT))
            .orElse(false);
    }

        /// Returns the enchantments carried by a stack, reading STORED_ENCHANTMENTS for
    /// enchanted books and ENCHANTMENTS for everything else.
    public static ItemEnchantments effectiveEnchantments(ItemStack stack) {
        ItemEnchantments enchs = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (enchs.isEmpty()) {
            enchs = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        }
        return enchs;
    }

        /// True if the stack is an enchanted book whose *only* enchantment is the Curse of
    /// Copyright. Such a book is the shopkeeper's stock-in-trade: a librarian can learn to
    /// produce it, letting SMP players stamp the curse onto the books they sell.
    public static boolean isPureCurseBook(ItemStack stack) {
        if (stack.getItem() != Items.ENCHANTED_BOOK) return false;
        ItemEnchantments enchs = effectiveEnchantments(stack);
        return enchs.size() == 1 && isCurseOfCopyright(enchs.keySet().iterator().next());
    }

        /// True if the stack carries the curse *alongside* other enchantments — the case the
    /// curse exists to block. A book of nothing but the curse is explicitly not "cursed"
    /// in this sense; see [#isPureCurseBook].
    public static boolean isCurseProtected(ItemStack stack) {
        if (isPureCurseBook(stack)) return false;
        for (Holder<Enchantment> e : effectiveEnchantments(stack).keySet()) {
            if (isCurseOfCopyright(e)) return true;
        }
        return false;
    }
}
