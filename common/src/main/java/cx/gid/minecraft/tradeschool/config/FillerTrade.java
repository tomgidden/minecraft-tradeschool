package cx.gid.minecraft.tradeschool.config;

import cx.gid.minecraft.common.config.Comment;
import cx.gid.minecraft.tradeschool.Constants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import org.jetbrains.annotations.Nullable;

/// One commodity trade, filling a profession level left empty by removing teachable items.
///
/// Always buy-side (`count` of the item for one emerald): that is vanilla's own idiom
/// for low-value profession trades, and it gives the player an emerald income now that the
/// gear sales are gone.
public class FillerTrade
{
  @Comment("Profession this applies to, matched loosely -- \"armorer\" matches minecraft:armorer.")
  public String profession;

  @Comment({"The exact villager level this unlocks at, not a minimum.", "Vanilla appends each level's trades on promotion rather than rebuilding, so a", "minimum would re-add the trade at every rank above it."})
  public int level = 1;

  @Comment("Item the villager buys, e.g. \"minecraft:coal\".")
  public String item;

  @Comment("How many of it buy one emerald.")
  public int count = 15;

  @Comment("Uses before the villager must restock.")
  public int maxUses = 16;

  @Comment("Villager experience per trade. Vanilla grants 2 at level 1.")
  public int xp = 2;

  @Comment({"How much the price responds to reputation and demand.", "0.05 is vanilla's figure for commodity trades like these."})
  public float priceMultiplier = 0.05F;

  public FillerTrade()
  {
    // Required for Gson deserialization
  }

  public boolean matches(String professionId, int professionLevel)
  {
    return professionLevel == level
        && profession != null
        && professionId.contains(profession);
  }

  ///  Builds the offer, or null if [#item] does not name a real item.
  @Nullable
  public MerchantOffer toOffer()
  {
    Item resolved = resolveItem();
    if(resolved == null) return null;
    return new MerchantOffer(
        new ItemCost(resolved, Math.max(1, count)),
        new ItemStack(Items.EMERALD, 1),
        Math.max(1, maxUses),
        xp,
        priceMultiplier
    );
  }

  @Nullable
  private Item resolveItem()
  {
    if(item == null) return null;
    Identifier id = Identifier.tryParse(item);
    if(id == null) {
      Constants.LOGGER.warn("Filler trade has unparseable item '{}'", item);
      return null;
    }
    Item found = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    if(found == null) {
      Constants.LOGGER.warn("Filler trade references unknown item '{}'", item);
    }
    return found;
  }
}
