package cx.gid.minecraft.tradeschool.config;

/// The loaded settings, reachable from anywhere.
///
/// ### Why a static holder
///
/// Most readers are mixins -- injected into vanilla classes that know nothing
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
public final class Config
{
  private static volatile TradeSchoolConfig current = benign();

  /// Serialises [#load()], which reads *and rewrites* the file.
  ///
  /// Loot tables load on a worker pool, so this is reached from around a dozen threads
  /// within the same millisecond. Without this they each ran the whole
  /// read-migrate-write-reread cycle over one file, and a thread reading while another
  /// wrote saw a truncated document -- whereupon the operator's valid config was renamed
  /// `.broken-<timestamp>` and the mod continued on defaults. Observed on Fabric during
  /// the 26.3 work; NeoForge happened to win the same race.
  ///
  /// [JsonConfig#writeAtomically] closes the window from the writer's side; this closes
  /// it from the caller's, and stops eleven threads doing eight threads' worth of
  /// redundant file IO at startup.
  private static final Object LOAD_LOCK = new Object();

  private Config() {}

  ///  The settings in force. Never null.
  public static TradeSchoolConfig get()
  {
    return current;
  }

  /// Reads `config/tradeschool.json`, replacing whatever was in force.
  ///
  /// Safe to call more than once -- both loaders initialise loot early and again
  /// when the server starts, and a reload should pick up an edited file rather
  /// than being ignored. Deliberately *not* a one-shot guard for that reason:
  /// concurrent callers are serialised, not skipped.
  public static void load()
  {
    synchronized(LOAD_LOCK) {
      current = ConfigLoader.load();
    }
  }

  private static TradeSchoolConfig benign()
  {
    TradeSchoolConfig config = new TradeSchoolConfig();
    config.applyDefaults();
    return config;
  }
}
