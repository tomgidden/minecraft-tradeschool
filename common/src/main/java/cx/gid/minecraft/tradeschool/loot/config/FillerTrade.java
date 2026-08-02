package cx.gid.minecraft.tradeschool.loot.config;

import cx.gid.minecraft.tradeschool.Constants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import org.jetbrains.annotations.Nullable;

/**
 * One commodity buy trade, filling a profession level that would otherwise be empty.
 *
 * Stripping teachable item types leaves some levels with no trades at all — a villager in
 * that state earns no experience and can never reach the level where teaching unlocks, so
 * these exist to keep progression possible rather than for flavour.
 *
 * Always buy-side ({@code count} × material → 1 emerald): that is vanilla's own idiom for
 * low-value profession trades, and it gives the player an emerald income now that the
 * gear sales are gone.
 */
public class FillerTrade {
    /** Profession id fragment, e.g. "armorer". Matched with contains(), as elsewhere. */
    public String profession;

    /**
     * The exact villager level this unlocks at — not a minimum. Vanilla appends each
     * level's trades on promotion rather than rebuilding, so a {@code >=} test would
     * re-add the trade at every subsequent rank.
     */
    public int level = 1;

    /** Item the villager buys, e.g. "minecraft:coal". */
    public String material;

    /** How many of it buy one emerald. */
    public int count = 15;

    /** Uses before the villager must restock. */
    public int maxUses = 16;

    /** Villager experience per trade. Vanilla uses 2 at level 1. */
    public int xp = 2;

    public FillerTrade() {
        // Required for Gson deserialization
    }

    public boolean matches(String professionId, int professionLevel) {
        return professionLevel == level
            && profession != null
            && professionId.contains(profession);
    }

    /** Builds the offer, or null if {@link #material} does not name a real item. */
    @Nullable
    public MerchantOffer toOffer() {
        Item item = resolveMaterial();
        if (item == null) return null;
        return new MerchantOffer(
            new ItemCost(item, Math.max(1, count)),
            new ItemStack(Items.EMERALD, 1),
            Math.max(1, maxUses),
            xp,
            0.05F);
    }

    @Nullable
    private Item resolveMaterial() {
        if (material == null) return null;
        Identifier id = Identifier.tryParse(material);
        if (id == null) {
            Constants.LOGGER.warn("Filler trade has unparseable material '{}'", material);
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) {
            Constants.LOGGER.warn("Filler trade references unknown item '{}'", material);
        }
        return item;
    }
}
