package cx.gid.minecraft.tradeschool.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cx.gid.minecraft.common.config.ConfigSpec;
import cx.gid.minecraft.common.config.JsonConfig;
import cx.gid.minecraft.tradeschool.Constants;
import java.nio.file.Path;

/// Loads `config/tradeschool.json`, and keeps it populated with the
/// intended balance.
///
/// ### Why the config file is written, not just read
///
/// The defaults compiled into the mod are deliberately harmless — zeroes and
/// falses — so that a config naming one field of a block does not silently
/// inherit meaningful values for the rest. That makes merging safe, but it means
/// the defaults alone would leave the mod doing nothing.
///
/// The intended values therefore live here, and are written into the
/// operator's file: on first run, and again whenever the schema version
/// advances, for settings the file has no opinion about. An operator sees the
/// real balance in the file they edit, and a setting introduced by a later
/// version arrives with its intended value rather than a benign one.
public final class ConfigLoader {

    /// Current schema version.
  ///
  /// Advance this when settings are added, renamed or change meaning, and add a
  /// matching step to [#MIGRATION]. Adding a setting counts: that is how
  /// its intended value reaches configs that already exist.
  public static final int VERSION = 2;

  private static final Path CONFIG =
      Path.of("config", Constants.MOD_ID + ".json");

    /// The bundled specification: every setting, with its comment, benign default
  /// and intended value. Loaded once — it is a jar resource and cannot change at
  /// runtime.
  private static final ConfigSpec SPEC = ConfigSpec.load(
      ConfigLoader.class, "/data/" + Constants.MOD_ID + "/config-spec.json");

  private static final JsonConfig.Log LOG = new JsonConfig.Log() {
    @Override
    public void info(String message) {
      Constants.LOGGER.info(message);
    }
    @Override
    public void warn(String message) {
      Constants.LOGGER.warn(message);
    }
  };

  private ConfigLoader() {}

    /// Where each version 1 setting moved to.
  ///
  /// Version 1 kept everything in one `global` block inside the loot table
  /// file. Almost all of it survived the split unchanged apart from its address,
  /// so the moves are stated as data: source path on the left, destination on
  /// the right, both relative to the file root. Only the three that changed
  /// shape need code.
  ///
  /// Absent from this table, deliberately: `default_tier_max_levels`
  /// and
  /// `default_crossover_chance`, which nothing ever read, and
  /// `curse_of_copyright.enabled` / `enforced`, which no longer
  /// exist — enforcement is not optional. An operator who disabled the curse
  /// should set
  /// `loot_probability` to `[]` and `availability` to `0`.
  private static final String[][] V1_MOVES = {
      {"global.enabled", "enabled"},
      {"global.debug_logging", "debug"},
      {"global.found_loot_prefixes", "loot.found_loot_prefixes"},
      {"global.unlearnable_enchantments", "teaching.unlearnable_enchantments"},

      {"global.trade.single_level_enchantment_min_villager_level",
       "teaching.single_level_enchantment_minimum_level"},
      {"global.trade.full_learn_payment_divisor",
       "teaching.full_learn_payment_divisor"},
      {"global.trade.teach_xp_multiplier", "teaching.player_xp_multiplier"},

      {"global.reputation.minor_positive_per_teach",
       "teaching.reputation.minor_positive"},
      {"global.reputation.major_positive_per_teach",
       "teaching.reputation.major_positive"},

      {"global.trade.curse_book_required_level",
       "curse_of_copyright.book_trade.required_level"},
      {"global.trade.curse_book_availability",
       "curse_of_copyright.book_trade.availability"},
      {"global.trade.curse_book_emerald_cost",
       "curse_of_copyright.book_trade.book_price"},

      {"global.trade.teach_preview_radius", "interaction.preview_radius"},
      {"global.trade.hint_radius", "interaction.hint_radius"},

      {"global.feedback.chat", "feedback.chat"},
      {"global.feedback.action_bar", "feedback.action_bar"},
      {"global.feedback.title", "feedback.title"},

      {"global.trade.learned_trade_max_uses", "trading.max_uses"},
      {"global.trade.max_teach_offers", "trading.max_teach_offers"},
  };

    /// Rewrites older configs.
  ///
  /// Version 1 stated its settings in a `global` block, under different
  /// names and in a different file. Everything an operator had chosen is carried
  /// across rather than discarded: a config is a record of decisions, often made
  /// once and forgotten, and silently reverting them would change how a running
  /// server behaves without anybody noticing. The three settings that genuinely
  /// changed shape are converted in code below; the rest are moves, listed in
  /// [#V1_MOVES].
  private static final JsonConfig.Migration MIGRATION = (root, fromVersion) -> {
    if (fromVersion >= 2 || !root.has("global"))
      return;

    for (String[] move : V1_MOVES) {
      JsonConfig.move(root, move[0], move[1]);
    }

    migrateCurseProbability(root);
    migrateTitleTiming(root);
    migrateBookPriceBands(root);
    migrateFillers(root);

    // Everything worth keeping has been moved out; what remains is the dead
    // keys and the loot tables, which now live in the mod's own data file.
    root.remove("global");
    root.remove("structures");
    root.remove("categories");
    root.remove("version");

    Constants.LOGGER.info("Migrated config/{}.json from the version 1 " +
                          "layout. Loot table definitions in "
                              + "it were dropped — those are bundled data " +
                                "now, overridable with a datapack.",
                          Constants.MOD_ID);
  };

    /// A single rate becomes one per structure tier: the same value, stated four
  /// times.
  private static void migrateCurseProbability(JsonObject root) {
    JsonElement rate = JsonConfig.remove(root, "global.curse_probability");
    if (rate == null || !rate.isJsonPrimitive())
      return;
    JsonArray perTier = new JsonArray();
    for (int i = 0; i < 4; i++)
      perTier.add(rate.getAsDouble());
    JsonConfig.set(root, "curse_of_copyright.loot_probability", perTier);
  }

    ///  Three separate tick counts become one [fade in, hold, fade out] triple.
  private static void migrateTitleTiming(JsonObject root) {
    JsonElement in =
        JsonConfig.remove(root, "global.feedback.title_fade_in_ticks");
    JsonElement stay =
        JsonConfig.remove(root, "global.feedback.title_stay_ticks");
    JsonElement out =
        JsonConfig.remove(root, "global.feedback.title_fade_out_ticks");
    if (in == null && stay == null && out == null)
      return;

    JsonArray timing = new JsonArray();
    timing.add(in != null ? in.getAsInt() : 5);
    timing.add(stay != null ? stay.getAsInt() : 80);
    timing.add(out != null ? out.getAsInt() : 20);
    JsonConfig.set(root, "feedback.title_timing", timing);
  }

    ///  Four named price fields plus a surcharge become one ordered scale.
  private static void migrateBookPriceBands(JsonObject root) {
    String[] names = {"common", "uncommon", "rare", "very_rare", "per_level"};
    int[] fallbacks = {5, 10, 15, 20, 5};

    JsonArray bands = new JsonArray();
    boolean any = false;
    for (int i = 0; i < names.length; i++) {
      JsonElement value =
          JsonConfig.remove(root, "global.trade.book_price_" + names[i]);
      any |= value != null;
      bands.add(value != null ? value.getAsInt() : fallbacks[i]);
    }
    if (any)
      JsonConfig.set(root, "trading.book_price_bands", bands);
  }

    /// A list of fillers becomes a keyed map, and `material` becomes `item`.
  ///
  /// Keys are synthesised from what the filler is, matching the naming the
  /// shipped set uses (`armorer_l1_coal`), so an operator's own entries
  /// sit alongside the defaults and can be amended the same way. A list could
  /// not express that: amending one entry meant restating all of them.
  private static void migrateFillers(JsonObject root) {
    JsonElement existing = JsonConfig.remove(root, "global.trade.fillers");
    if (existing == null || !existing.isJsonArray())
      return;

    JsonObject keyed = new JsonObject();
    int unnamed = 0;
    for (JsonElement element : existing.getAsJsonArray()) {
      if (!element.isJsonObject())
        continue;
      JsonObject filler = element.getAsJsonObject();

      // "material" was renamed "item"; the value is unchanged.
      if (filler.has("material")) {
        filler.add("item", filler.remove("material"));
      }

      keyed.add(fillerKey(filler, ++unnamed), filler);
    }
    if (!keyed.isEmpty())
      JsonConfig.set(root, "trading.fillers", keyed);
  }

    /// Names a filler after its profession, level and item — e.g. `armorer_l1_coal`.
  private static String fillerKey(JsonObject filler, int ordinal) {
    String profession = filler.has("profession")
                            ? filler.get("profession").getAsString()
                            : "filler";
    String item = filler.has("item")
                      ? filler.get("item").getAsString().replaceAll("^.*:", "")
                      : String.valueOf(ordinal);
    int level = filler.has("level") ? filler.get("level").getAsInt() : 1;
    return profession + "_l" + level + "_" + item;
  }

    /// Reads the operator's config over the benign defaults, tops it up with any
  /// intended values it does not mention, and refreshes the generated reference.
  public static TradeSchoolConfig load() {
    // Migrate if needed, and refresh the reference file documenting this build.
    // The config it returns is discarded: the top-up below may add to the file,
    // and the read after it is what this session actually runs on.
    JsonConfig.loadAndDocument(CONFIG, TradeSchoolConfig::new, VERSION,
                               MIGRATION, Constants.MOD_NAME, initialValues(),
                               LOG);

    JsonConfig.ensureInitialValues(CONFIG, initialValues(), VERSION, LOG);

    // Re-read so the values just written take effect in this session rather
    // than the next: an operator restarting to see a setting appear would then
    // have to restart again for it to apply.
    TradeSchoolConfig effective = JsonConfig.load(
        CONFIG, new TradeSchoolConfig(), VERSION, MIGRATION, LOG);
    effective.applyDefaults();
    return effective;
  }

    /// The balance the mod is designed around, as opposed to the benign values it
  /// falls back to. Written into the operator's file for any key the file does
  /// not mention.
  ///
  /// Read from the bundled spec rather than built here: the spec already has to
  /// state these values in order to document them, and stating them twice is how
  /// the file an operator reads and the values they actually run drift apart.
  private static JsonObject initialValues() { return SPEC.initialValues(); }
}
