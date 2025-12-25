package cx.gid.minecraft.tradeschool.enchantment;

import cx.gid.minecraft.tradeschool.Constants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * Registry for custom enchantments added by Trade School.
 */
public class ModEnchantments {
    /**
     * Curse of Copyright - prevents librarians from learning this enchantment.
     * Can still be applied to items by players.
     */
    public static final ResourceKey<Enchantment> CURSE_OF_COPYRIGHT =
        ResourceKey.create(
            Registries.ENCHANTMENT,
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "curse_of_copyright")
        );

    /**
     * Checks if an enchantment holder is the Curse of Copyright.
     */
    public static boolean isCurseOfCopyright(Holder<Enchantment> enchantment) {
        return enchantment.unwrapKey()
            .map(key -> key.equals(CURSE_OF_COPYRIGHT))
            .orElse(false);
    }
}
