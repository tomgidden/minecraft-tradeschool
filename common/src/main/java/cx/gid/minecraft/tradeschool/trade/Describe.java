package cx.gid.minecraft.tradeschool.trade;

import cx.gid.minecraft.tradeschool.enchantment.ModEnchantments;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.Map;

/// How the mod names villagers, items and enchantments in the things it says.
///
/// Every message used to phrase these itself, so the same villager was "the librarian" in
/// one line, "This librarian" in another and "librarian" in a third, and one book's
/// enchantments were joined with commas here and "and" there. Centralising the phrasing is
/// the only way that stays fixed.
///
/// Colour is applied sparingly and always to the same things: the villager's name and role,
/// the item type, and each enchantment. Never to whole sentences — the point of the colour
/// is to let the eye find the specifics, which fails if everything is coloured.
public final class Describe {

        ///  Highlight colour for a nameable thing, and the reset that must follow it.
    private static final String HL = "§e";
    private static final String OFF = "§r";

    private Describe() {}

        /// A villager, as the start of a sentence: "Dave the Librarian", or "This Librarian"
    /// when unnamed.
    ///
    /// "This" rather than "The" because the player is standing in front of one particular
    /// villager, often among several — "the librarian" invites the question "which?".
    public static String villager(Villager villager, String professionLabel) {
        String role = HL + capitalise(professionLabel) + OFF;
        var custom = villager.getCustomName();
        if (custom != null && !custom.getString().isBlank()) {
            return HL + custom.getString() + OFF + " the " + role;
        }
        return "This " + role;
    }

        /// The same, but mid-sentence, where "This Librarian" would read oddly after a verb:
    /// "You can teach this Librarian…".
    public static String villagerLower(Villager villager, String professionLabel) {
        var custom = villager.getCustomName();
        if (custom != null && !custom.getString().isBlank()) {
            return villager(villager, professionLabel);
        }
        return "this " + HL + capitalise(professionLabel) + OFF;
    }

        /// An item and what is on it: "Enchanted Book with 'Sharpness I', 'Unbreaking I'".
    ///
    /// Enchantments are comma-separated rather than joined with "and". A list that changes
    /// its separator based on length is harder to skim, and this text often appears beside
    /// an inventory the player is searching.
    ///
    /// **Order is whatever the stack yields.** [ItemEnchantments] stores its
    /// contents in an `Object2IntOpenHashMap`, so iteration follows hash order — not
    /// insertion, and not alphabetical. Where the intended order is known, use
    /// [#item(net.minecraft.world.item.Item, Map)] instead; a stack cannot carry that
    /// information, so anything built from one is at the mercy of the hash.
    public static String item(ItemStack stack) {
        String name = HL + itemName(stack.getItem()) + OFF;
        ItemEnchantments enchantments = ModEnchantments.effectiveEnchantments(stack);
        if (enchantments.isEmpty()) return name;

        StringBuilder out = new StringBuilder(name).append(" with ");
        boolean first = true;
        for (Holder<Enchantment> e : enchantments.keySet()) {
            if (!first) out.append(", ");
            out.append(enchantment(e, enchantments.getLevel(e)));
            first = false;
        }
        return out.toString();
    }

        /// As [#item(ItemStack)], but stating the enchantments in the order given.
    ///
    /// The order an item's enchantments are described in should match everywhere the player
    /// sees them, and the only place it is genuinely known is the ordered map built when the
    /// item was analysed. Round-tripping that map through an [ItemStack] to describe it
    /// loses the order, which is how the same lesson came to be announced two different ways
    /// in the title and in chat.
    public static String item(net.minecraft.world.item.Item item,
                              Map<Holder<Enchantment>, Integer> learned) {
        String name = HL + itemName(item) + OFF;
        if (learned == null || learned.isEmpty()) return name;
        return name + " with " + enchantments(learned);
    }

        ///  As [#item], from a learned enchantment map rather than a stack.
    public static String enchantments(Map<Holder<Enchantment>, Integer> learned) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Holder<Enchantment>, Integer> e : learned.entrySet()) {
            if (!first) out.append(", ");
            out.append(enchantment(e.getKey(), e.getValue()));
            first = false;
        }
        return out.toString();
    }

        /// One enchantment, highlighted: `Sharpness I`.
    ///
    /// The colour change does the work quotes used to. Quoting as well as colouring states
    /// the same boundary twice, and these lists are long enough — several enchantments, each
    /// with a numeral — that the saved characters matter on a subtitle, which does not wrap.
    public static String enchantment(Holder<Enchantment> ench, int level) {
        String name = capitalise(ench.unwrapKey()
            .map(k -> k.identifier().getPath().replace('_', ' '))
            .orElse("unknown"));
        // A single-level enchantment has no "I" to state; "Mending I" is not a thing.
        String suffix = ench.value().getMaxLevel() == 1 ? "" : " " + roman(level);
        return HL + name + suffix + OFF;
    }

        ///  How a player refers to what they are handing over: "book", or the item's name.
    public static String shortNoun(ItemStack stack) {
        return stack.getItem() == Items.ENCHANTED_BOOK ? "book" : itemName(stack.getItem());
    }

    public static String itemName(net.minecraft.world.item.Item item) {
        return capitalise(net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(item).getPath().replace('_', ' '));
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (String word : s.split(" ")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III";
            case 4 -> "IV"; case 5 -> "V"; default -> String.valueOf(n);
        };
    }
}
