package cx.gid.minecraft.tradeschool.config;

import cx.gid.minecraft.common.config.Comment;

/// Gossip granted to the player for teaching a villager.
///
/// A villager's opinion is the weighted sum of each gossip type it holds, and those types
/// price trades differently: MAJOR_POSITIVE is weight 5 and never decays -- it is what
/// curing a zombie villager grants -- while MINOR_POSITIVE is weight 1, caps at 25 and
/// fades slowly. Negatives are offered too, since an operator may want to discourage
/// industrial-scale teaching rather than reward it.
///
/// All default to zero so that setting one says nothing about the others.
public class ReputationSection
{
  @Comment({"MINOR_POSITIVE per lesson. Weight 1, caps at 25, decays slowly.", "Improves prices noticeably without reaching cure-tier discounts."})
  public int minorPositive = 0;

  @Comment({"MAJOR_POSITIVE per lesson. Weight 5, caps at 20, never decays.", "This is curing's currency; granting much of it makes teaching permanent."})
  public int majorPositive = 0;

  @Comment("MINOR_NEGATIVE per lesson. Weight -1. Discourages repeat teaching.")
  public int minorNegative = 0;

  @Comment("MAJOR_NEGATIVE per lesson. Weight -5.")
  public int majorNegative = 0;

  @Comment({"TRADING gossip per lesson. Weight 1, caps at 25, decays faster than", "MINOR_POSITIVE. An ordinary trade grants 2."})
  public int trading = 0;
}
