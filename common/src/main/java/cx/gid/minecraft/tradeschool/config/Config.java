package cx.gid.minecraft.tradeschool.config;

/// The loaded settings, reachable from anywhere.
///
/// ### Why a static holder
///
/// Most readers are mixins — injected into vanilla classes that know nothing
/// about this mod and cannot be given a constructor argument or a field.
/// Threading a config object to them would mean a parallel set of accessors on
/// the classes they target, which is a great deal of machinery to avoid one
/// static.
///
/// [#get()] never returns null: before [#load()] runs it yields
/// the benign defaults, so a caller reached unexpectedly early sees a mod that
/// does nothing rather than a `NullPointerException`. That matters because
/// loot tables load before the server exists, and the ordering differs between
/// Fabric and NeoForge.
public final class Config {

  private static volatile TradeSchoolConfig current = benign();

  private Config() {}

    ///  The settings in force. Never null.
  public static TradeSchoolConfig get() { return current; }

    /// Reads `config/tradeschool.json`, replacing whatever was in force.
  ///
  /// Safe to call more than once — both loaders initialise loot early and again
  /// when the server starts, and a reload should pick up an edited file rather
  /// than being ignored.
  public static void load() { current = ConfigLoader.load(); }

  private static TradeSchoolConfig benign() {
    TradeSchoolConfig config = new TradeSchoolConfig();
    config.applyDefaults();
    return config;
  }
}
