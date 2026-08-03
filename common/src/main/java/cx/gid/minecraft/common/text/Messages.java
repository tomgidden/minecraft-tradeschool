package cx.gid.minecraft.common.text;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.NonNull;

/// Player-facing messages, translated server-side where the client
/// cannot translate them itself.
///
/// `Component.translatable` is resolved by the *client*, against
/// language files the client holds. A player without the mod installed has no
/// namespace for it at all, so a bare `translatable` renders as the literal key
/// -- `somemod.message.denied` -- which is exactly what an unmodded player
/// would see at the moment the mod was trying to explain itself.
///
/// `translatableWithFallback` carries a literal string alongside the key.
/// A client that has the mod translates the key as normal and ignores the
/// fallback; a client that does not renders the fallback verbatim.
///
/// Crucially the fallback is chosen *per send, on the server*, and
/// nothing requires it to be English. So this class loads the mod's own
/// language files -- the same `assets/<modid>/lang/*.json` shipped in the jar
/// -- and picks the fallback matching that player's client language, obtained
/// from ServerPlayer#clientInformation(). An unmodded French client therefore
/// gets French text, because the *server* translated it.
///
/// The result covers both audiences from one call:
/// * modded client -- translates the key locally, honouring resource packs;
/// * unmodded client -- renders a fallback already in its own language.
///
/// Chain:
/// * Player's locale,
/// * then its language without the region (`fr_ca` tries `fr_fr`),
/// * then `en_us`,
/// * then the key itself.
///
/// A locale the mod does not ship simply reads as English, which is the
/// ordinary outcome for any mod.
public final class Messages {

  /// The language every lookup ultimately falls back to.
  private static final String DEFAULT_LANGUAGE = "en_us";

  /// Loaded language tables, keyed by locale (`"fr_fr"`). Populated
  /// lazily: a server whose players are all English never reads another file.
  private final Map<String, Map<String, String>> TABLES = new ConcurrentHashMap<>();

  /// Where diagnostics go; defaults to discarding them.
  private java.util.function.Consumer<String> logger = m -> {};

  /// Routes load diagnostics to the consuming mod's logger.
  public Messages withLogger(java.util.function.Consumer<String> logger) {
    this.logger = logger;
    return this;
  }

  /// The namespace whose `assets/<modId>/lang/*.json` are read.
  private final String modId;

  /// @param modId names the namespace whose `assets/<modId>/lang/*.json` are
  /// read. Passed in rather than read from a Constants class so this stays
  /// mod-agnostic.
  public Messages(String modId) { this.modId = modId; }

  /// A component that translates for clients with the mod, and reads in the
  /// player's own language for those without.
  public MutableComponent of(ServerPlayer player, String key) {
    return Component.translatableWithFallback(key, lookup(languageOf(player), key));
  }

  /// As #of(ServerPlayer, String), with arguments substituted into the
  /// fallback.
  ///
  /// The fallback must be pre-formatted because the client only substitutes
  /// into a string it resolved itself; a fallback is rendered as-is.
  public MutableComponent of(ServerPlayer player, @NonNull String key,
                             Object... args) {
    String pattern = lookup(languageOf(player), key);
    return Component.translatableWithFallback(key, format(pattern, args), args);
  }

  /// As #of(ServerPlayer, String) but for a recipient whose language is
  /// unknown -- the console, a command block, or an offline player. Always
  /// English.
  public MutableComponent ofDefault(@NonNull String key, Object... args) {
    String pattern = lookup(DEFAULT_LANGUAGE, key);
    return Component.translatableWithFallback(key, format(pattern, args), args);
  }

  /// Substitutes placeholders the way vanilla's own translation does, in both
  /// the sequential/implicit (`%s`) and indexed/explicit (`%1$s`) forms.
  ///
  /// The indexed form matters for more than completeness: a language whose
  /// word order differs from English needs it to keep arguments straight, and
  /// `en_ud` needs it because reversing a string reverses its
  /// placeholders too. Vanilla's own `en_ud` uses `%1$s` for
  /// exactly this reason, so any translation modelled on it will as well.
  ///
  /// Deliberately not `String.format`: a stray `%` in a translated string
  /// would wreck the whole string, and we don't want an exception thrown
  /// for simple messaging. Anything unparseable is passed through unchanged.
  /// And, here we can use `getString()` rather than a debug representation
  /// without having to pre-map the arguments.
  ///
  /// Doing this with a regexp-based `Matcher.appendReplacement/appendTail`
  /// approach would be neater but more overhead on a hot-ish path.
  private static String format(String pattern, Object... args) {

    // If there are no args, then the pattern is the result.
    if (args == null || args.length == 0)
      return pattern;

    // Get the pattern length
    int len = pattern.length();

    // Otherwise, build a new string with the pattern's characters
    StringBuilder out = new StringBuilder(len + 16);
    int nextArg = 0;

    // For each character in the pattern...
    for (int i = 0; i < len; i++) {
      char c = pattern.charAt(i); // the current character

      // If it's definitely not a placeholder...
      if (c != '%' || i + 1 >= len) {

        // then append it to the output.
        out.append(c);
        continue;
      }

      // Else, it might be a placeholder.

      // Assume c is '%', then if followed by 's'...
      if (pattern.charAt(i + 1) == 's') {

        // then we've got "%s", a sequential placeholder.

        // If we haven't consumed all the arguments sequentially...
        if (nextArg < args.length) {

          // then append the next argument in place of the placeholder
          out.append(stringify(args[nextArg++]));
          i++;
          continue;
        }

        // Otherwise, append the `%s` and skip ahead one so we don't have
        // to iterate just for the 's' that we know is next.
        out.append("%s");
        i++;
        continue;
      }

      // "%<n>$s" -- take the n'th argument, 1-based.

      // Starting at the next character, continue while we get digits, and use
      // them to build the index.  This is effectively a parseInt.
      int j = i + 1;
      int index = 0;
      while (j < len && Character.isDigit(pattern.charAt(j))) {
        index = index * 10 + (pattern.charAt(j) - '0');
        j++;
      }

      // index should now be the int version of `<n>`, and `j` should
      // be pointing at the `$` in `%<n>$s` if the format is correct;
      // Alternatively, we might've hit the end of the string, in which
      // case this isn't a valid placeholder; or if the next letter isn't
      // `$`, then this isn't a valid placeholder either.

      // If:
      //   * the `j` cursor has (at least) enough string left for "$s"; and
      //   * there was at least one digit consumed (j>i+1); and
      //   * the next character is a `$`...
      //   * ...followed by `s`; and
      //   * the index is in the range of arguments...
      if ( j + 1 < len && j > i + 1 && pattern.charAt(j) == '$' &&
          pattern.charAt(j + 1) == 's' && index >= 1 && index <= args.length) {

        // then append the argument to the output rather than the placeholder.
        out.append(stringify(args[index - 1]));

        // and move to after the end of the placeholder.
        i = j + 1;
        continue;
      }

      // Otherwise, append the character to the output.
      out.append(c);
    }

    return out.toString();
  }

  /// Renders an argument, resolving nested components to their plain text.
  private static String stringify(Object arg) {

    // If it's a component, then render it.
    if (arg instanceof Component component)
      return component.getString();

    // Otherwise, just stringify it.
    return String.valueOf(arg);
  }

  /// The player's client language, normalised, or the default if unavailable.
  private static String languageOf(ServerPlayer player) {

    // If we haven't got the player, eg. this is a server-side message, then
    // the default language is the best we can do.
    if (player == null)
      return DEFAULT_LANGUAGE;

    try {
      // Get the player's language, normalised to lower case.
      String language = player.clientInformation().language();

      // If it's blank, then the default language is the best we can do.
      if (language == null || language.isBlank())
        return DEFAULT_LANGUAGE;

      // Trim and lowercase the language using the root locale's lowercasing
      // rules (which should be trivial, as the language code should be plain ASCII).
      return language.trim().toLowerCase(Locale.ROOT);
    }

    // If that failed, then the default language is the best we can do.
    catch (Exception e) {

      // clientInformation is populated at login; be defensive rather than
      // let a message lookup break the trade.
      return DEFAULT_LANGUAGE;
    }
  }

  /// Resolves a key for the given language, walking the fallback chain.
  /// Never returns null: an unknown key resolves to itself.
  ///
  // TODO: the regional scan below is not deterministic, and this method
  // cannot be memoised until it is. Both problems have the same root, and
  // it is worth understanding before the next rework.
  //
  // The scan asks "which other table might have this key?" and answers it
  // by iterating TABLES -- the tables *already loaded*, which is whichever
  // locales players have happened to log in with, in ConcurrentHashMap
  // order. So a `zh_hk` player can get `zh_cn` on one run and `zh_tw` on
  // the next, from the same jar. Memoising would freeze whichever answer
  // came first, making the inconsistency stable within a run but no more
  // correct -- and harder to notice. Hence: fix the order first, cache
  // second. (`cx.gid.minecraft.common.func.Memo` is ready when it is;
  // `Memo.of(this::lookup)` is the whole change, and `tableFor` can move
  // onto it at the same time once nothing needs `TABLES.keySet()`.)
  //
  // Determinism needs the set of shipped languages known up front, and
  // *that* is the hard part for a reusable, loader-agnostic library:
  //
  //   * Enumerating the jar at runtime is a trap. getResourceAsStream on a
  //     directory lists entries when the classpath is a folder (dev runs)
  //     and returns null inside a jar (production) -- so it would appear to
  //     work right up until release. The APIs that do work
  //     (FabricLoader#findPath and the NeoForge equivalent) are
  //     loader-specific and cannot be called from `common`; reaching them
  //     needs an Architectury @ExpectPlatform indirection.
  //   * A build-time index -- a Gradle task scanning
  //     src/main/resources/assets/*/lang/*.json into a lang/index.json --
  //     avoids all of that, since it is read as an ordinary resource from a
  //     known path. Loader-agnostic and self-maintaining.
  //   * Failing that, the mod declares its languages explicitly, e.g. a
  //     withLanguages("en_us", "fr_fr", ...) beside #withLogger.
  //
  // Note, however, the languages could well be layered on top of the mod
  // so we can't rely on the mod knowing what languages it supports at
  // build time.  Not that this is at all likely, but it is possible.
  //
  // Knowing the set is necessary but not sufficient: something must still
  // choose *between* candidates. Ordering the declared list by preference
  // covers it (`zh_hk` -> first `zh_*` declared), and doubles as the
  // CLDR likely-subtags data we cannot otherwise reach -- the JDK ships
  // CLDR but does not expose addLikelySubtags, and ICU4J is ~13MB to shade.
  // Note Locale#filter and Locale#lookup (RFC 4647) are public and would do
  // the matching deterministically, but they take the available set as an
  // argument, so they do not avoid any of the above.
  //
  // Two things defeat any general solution, and are why this stays manual:
  //
  //   * An `extends` key inside each language file (`fr_ca` -> `fr_fr` ->
  //     `en_us`) only describes files that *exist*. A player on `fr_qz`
  //     has no `fr_qz.json` to read an `extends` from, which is precisely
  //     the case needing help. It would also force every file to be loaded
  //     at startup to build the graph, discarding the laziness above.
  //   * Minecraft's joke locales are not locales. `en_pt` is Pirate,
  //     `en_ud` is upside-down, `lol_us` is LOLCAT, `tlh_aa` is Klingon.
  //     The region subtag is not a region, so prefix logic is inferring
  //     from something that carries no such meaning. `en_ud` falling back
  //     to `en_us` is merely unfunny; the general case has no right answer
  //     without a hand-written table.
  //
  // Until then the chain stays: exact -> (scan) -> `xx_xx` -> en_us -> key,
  // and the scan's arbitrariness is bounded by it being a fallback that
  // only fires when the exact table lacks the key.
  private String lookup(String language, String key) {

    // Get the language table for the given language, and get the value
    // for the key.
    String value = tableFor(language).get(key);

    // If it's set, then we're done: return it.
    if (value != null)
      return value;

    // Otherwise, if the language has an underscore, then we're looking for a
    // regional variant of the key. eg. finding the key in "fr_ca" failed,
    // but "fr_fr" might succeed.

    // Find the variant
    int underscore = language.indexOf('_');
    if (underscore > 0) {

      // Strip the variant, eg. `fr_ca` -> `fr`.
      String prefix = language.substring(0, underscore);

      // Look for tables with the prefix, eg. `fr_ca` -> `fr`.
      for (String candidate : TABLES.keySet()) {

        // If the candidate starts with the prefix, then it's a candidate.
        if (candidate.startsWith(prefix + "_")) {

          // Look for the key in the candidate, and return it if found.
          String regional = TABLES.get(candidate).get(key);
          if (regional != null)
            return regional;
        }
      }

      // If we didn't find a regional variant, then try finding a value
      // the variant is the same as the language, eg. `fr_fr`.
      String guess = tableFor(prefix + "_" + prefix).get(key);
      if (guess != null)
        return guess;
    }

    // All else fails, get the default language table and look for the value.
    // If not, just return the key itself.
    value = tableFor(DEFAULT_LANGUAGE).get(key);
    return value != null ? value : key;
  }

  /// The language table for a locale, loading it from the jar on first use.
  ///
  /// A missing file caches an empty table, so a server full of players with
  /// unshipped locales does not retry the classpath on every message.
  private Map<String, String> tableFor(String language) {

    // TODO: Consider using common/func/Memo or an extension of it, rather
    // than a roll-your-own memoization.  We may need TABLES.keySet() to
    // scan for languages, depending on the solution to the determinism
    // problem above, and so func/Memo might need to be extended to support that.
    return TABLES.computeIfAbsent(language, this::load);
  }

  /// Attempt to load the given language's table.
  private Map<String, String> load(String language) {

    // Build the file path
    String path = "/assets/" + modId + "/lang/" + language + ".json";

    // Try to load it.
    // XXX: Can we actually search for files somehow with a similar
    // mechanism to identify all available languages?
    try (InputStream in = Messages.class.getResourceAsStream(path)) {

      // If it's missing, then return an empty table.
      if (in == null)
        return Collections.emptyMap();

      // Parse the file as JSON.
      JsonObject json = JsonParser
              .parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonObject();

      // Build a table from the JSON.
      Map<String, String> table = new HashMap<>();

      // For each key/value pair in the JSON...
      for (Map.Entry<String, JsonElement> entry : json.entrySet()) {

        // If the value is a primitive, then add it to the table.
        if (entry.getValue().isJsonPrimitive())
          table.put(entry.getKey(), entry.getValue().getAsString());
      }

      // Log the result.
      logger.accept("loaded " + table.size() + " strings for " + language);
      return Map.copyOf(table); // XXX: do we need to copy?
    }

    catch (Exception e) {
      logger.accept("could not read language " + language + ": " + e);
      return Collections.emptyMap();
    }

    // TODO: positive and negative caching?  Repeated reload attempts
    // of files that don't exist?  Consider how to deal with this.
  }
}
