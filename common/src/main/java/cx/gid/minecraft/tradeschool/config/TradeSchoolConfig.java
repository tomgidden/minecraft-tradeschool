package cx.gid.minecraft.tradeschool.config;

import cx.gid.minecraft.common.config.Comment;

/// Everything an operator can tune, grouped by what it affects.
///
/// ### Small groups on purpose
///
/// Settings are merged onto the defaults key by key, at any depth, so a config
/// naming one field of a block keeps the block's other values. That is what
/// makes a sparse config possible, but it also means a large block can quietly
/// contribute values the operator never considered. Keeping groups small and
/// shallow limits how much any one partial statement drags in with it.
///
/// The values here are deliberately *benign* — zero for amounts, false for
/// switches — rather than the intended balance. They exist to be harmless when
/// inherited. The intended values are written into `config/tradeschool.json` on
/// first run and whenever the schema version advances, so an operator sees the
/// real balance in the file they edit, and a setting they never touched still
/// behaves as designed.
///
/// Without that split, a config saying `{"reputation": {"major_positive": 1}}`
/// would silently keep an inherited `minor_positive: 5` and give an operator
/// the opposite of what they appeared to ask for.
public class TradeSchoolConfig {

  @Comment("Whether the mod does anything at all.")
  public boolean enabled = false;

  @Comment({"Verbose per-event logging: teach scans, loot modifications, trade generation.",
            "These fire constantly; leave off unless diagnosing something."})
  public boolean debug = false;

  public LootSection loot = new LootSection();
  public TeachingSection teaching = new TeachingSection();
  public CurseSection curseOfCopyright = new CurseSection();
  public InteractionSection interaction = new InteractionSection();
  public FeedbackSection feedback = new FeedbackSection();
  public TradingSection trading = new TradingSection();

  ///  Replaces sub-objects a config set explicitly to null, which would NPE at
  ///  use.
  public void applyDefaults()
  {
    if (loot == null)             loot = new LootSection();
    if (teaching == null)         teaching = new TeachingSection();
    if (curseOfCopyright == null) curseOfCopyright = new CurseSection();
    if (interaction == null)      interaction = new InteractionSection();
    if (feedback == null)         feedback = new FeedbackSection();
    if (trading == null)          trading = new TradingSection();

    loot.applyDefaults();
    teaching.applyDefaults();
    curseOfCopyright.applyDefaults();
    trading.applyDefaults();
  }
}
