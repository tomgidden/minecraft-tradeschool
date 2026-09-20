package cx.gid.minecraft.tradeschool.config;

/// Whether villagers can be taught netherite gear, per profession.
///
/// Off by default, and deliberately absent from the generated config file: the
/// spec gives these a `#default` but no `#initial`, so nothing is written into
/// `config/tradeschool.json` and an operator only finds them by looking. That is
/// intentional. Netherite is the one tier the player cannot get back from a
/// villager in vanilla, and a visible switch reads as an invitation.
///
/// Split by profession rather than one flag because the three trades are
/// separately balanced: an armorer selling netherite plate is a different
/// proposition from a toolsmith selling a netherite hoe.
///
/// Axes count as tools here, not weapons, so a netherite axe follows
/// [#tools] for both toolsmiths and weaponsmiths. Elsewhere in the mod an axe
/// is both, as in vanilla; for this switch it is one thing, so that enabling
/// weapons cannot let an axe in by the back door.
///
/// Deliberately no `@Comment` annotations: those generate the documented file.
public class NetheriteSection
{
  public boolean armor   = false;
  public boolean tools   = false;
  public boolean weapons = false;
}
