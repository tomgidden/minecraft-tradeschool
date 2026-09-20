package cx.gid.minecraft.tradeschool.config;

import cx.gid.minecraft.common.config.Comment;
import java.util.List;

/// The Curse of Copyright: how often it is found, and who sells blanks.
///
/// There is no switch to disable enforcement. A curse villagers ignore is not a curse --
/// enforcement is the mechanic's identity rather than a feature of it, and a book bearing a
/// meaningless tooltip would be worse than no book. To remove it from play, stop it
/// appearing: an empty probability list and an availability of zero leave it reachable only
/// by command, where honouring it is still the correct behaviour.
public class CurseSection
{
  @Comment({"Chance a found enchanted item carries the curse, per structure tier", "(low, medium, high, top). A single value applies to every tier.", "Empty means the curse never appears in loot."})
  public List<Double> lootProbability = List.of();

  public BookTradeSection bookTrade = new BookTradeSection();

  public void applyDefaults()
  {
    if(lootProbability == null) lootProbability = List.of();
    if(bookTrade == null) bookTrade = new BookTradeSection();
  }

  /// The chance an item found at `tierIndex` carries the curse.
  ///
  /// A single value applies to every tier, which is the shipped configuration: the rule
  /// "one in ten of what you find is copyrighted" stays explainable, whereas a rate that
  /// climbed with structure value would make the curse feel like a punishment for
  /// exploring well. The list form is kept for operators who disagree.
  ///
  /// Out-of-range indices take the last value rather than falling to zero, so adding a
  /// tier cannot silently disable the curse there.
  ///
  /// @param tier the structure tier; null is treated as the lowest, since an unattributed
  ///             loot table is likelier to be an ordinary one than a vault
  public double lootProbabilityFor(
      @org.jetbrains.annotations.Nullable
      cx.gid.minecraft.tradeschool.loot.tier.StructureTier tier
  )
  {
    if(lootProbability == null || lootProbability.isEmpty()) return 0.0;
    int tierIndex = tier != null ? tier.ordinal() : 0;
    int index     = Math.min(tierIndex, lootProbability.size() - 1);
    Double value  = lootProbability.get(index);
    return value != null ? value : 0.0;
  }

  ///  Blank curse books sold by librarians -- a shop's supply of copyright stamps.
  public static class BookTradeSection
  {
    @Comment("Minimum librarian level that stocks blank curse books. 0 disables the trade.")
    public int requiredLevel = 0;

    @Comment({"Fraction of eligible librarians carrying it, decided from the villager's", "UUID rather than per restock, so one either always has it or never does.", "0 means none do."})
    public double availability = 0.0;

    @Comment({"Emerald cost of one blank book. null prices it as an ordinary enchanted", "book instead of a flat rate."})
    public Integer bookPrice = null;

    @Comment({"Villager experience per blank book sold. Only ever offered at level 5,", "where there is nothing left to level up to, so this exists for operators", "who lower required_level rather than to affect progression."})
    public int xp = 30;

    @Comment({"How much the price responds to reputation and demand. 0.05 matches", "vanilla's commodity trades -- this is stock for a shop rather than a", "prize, so it should stay cheap and predictable."})
    public float priceMultiplier = 0.05F;
  }
}
