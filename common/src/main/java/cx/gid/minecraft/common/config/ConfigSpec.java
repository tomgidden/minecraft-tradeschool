package cx.gid.minecraft.common.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/// A settings specification bundled in the mod jar, from which the config trees
/// are derived.
///
/// A setting has three things to say about itself: what it does, what it falls
/// back to, and what it should actually be. Kept apart, they drift -- a default
/// changes in Java, the comment describing it stays in a resource, and the
/// value written into the operator's file is a third statement in a third
/// place, each free to disagree with the others.
///
/// ```json
/// {
/// "xp_multiplier#comment": "Multiplier on the sell price to get experience
/// orbs.", "xp_multiplier#default": 0.0, "xp_multiplier#initial": 1.0,
/// "xp_multiplier#type":    "double"
/// }
/// ```
///
/// Re: default vs. initial.  Due to the merging behaviour whereby a set of
/// admin-edited config is merged over a set of defaults, setting a simple
/// object like `{foo:1}` can result in the object being something like `{foo:1,
/// bar:1}`, with the `bar` value coming from the defaults.  As this may be
/// surprising, the defaults in that file are intentionally *benign* - they are
/// "identity" values, like `0` for additive numbers, `1.0` for multiplicative
/// numbers, `false` for options, and so on.
///
/// However, there's also the need for *sensible*, *useful* defaults. So, those
/// are defined as "initial" values; then, on new install, the editable config
/// file is created with those initial values that then override the benign
/// defaults.
///
/// @see JsonConfig#ensureInitialValues
///
/// So, if you want to know what the *typical* "defaults" are, you're probably
/// looking for the "initial" values, not the "default" values!
///
/// Anyway, the "clean" structure for such a spec would be:
/// ```json
/// {
///   "xp_multiplier": {
///     "comment": "Multiplier on the sell price to get experience orbs.",
///     "initial": 1.0,
///     "default": 0.0,
///     "type":    "double"
///   },…
/// }
/// ```
/// and that would work fine. However, it's a chore to process and the spec file
/// will be a different shape from the files created from it.
///
/// By using suffixes like `xp_multiplier#comment` and `xp_multiplier#initial`,
/// the we can trivially filter by those suffixes and remove them using shell
/// tools to generate the desired "defaults" or "initial" JSON files.
///
/// It's also possible to use the spec to generate Java classes, but that's not
/// currently implemented.  Still, it can be used as a canonical source of truth
/// for an agent or developer to check manually-written classes.
public final class ConfigSpec
{
  private static final String SUFFIX_SEPARATOR = "#";

  /// The suffixes that an entry can have.
  private static final String COMMENT = "#comment";
  private static final String DEFAULT = "#default";
  private static final String INITIAL = "#initial";
  private static final String TYPE    = "#type";

  /// The spec itself, as a JSON object.
  private final JsonObject spec;

  private ConfigSpec(JsonObject spec)
  {
    this.spec = spec;
  }

  private static String[] getPrefixAndSuffix(String key)
  {
    int hash = key.lastIndexOf(SUFFIX_SEPARATOR);

    // No suffix, so return null
    if(hash < 0)
      return null;

    // Return a tuple of the prefix and suffix
    return new String[] {key.substring(0, hash), key.substring(hash)};
  }

  /// Reads a spec from the classpath.
  ///
  /// @param resource absolute resource path, e.g.
  /// `/data/mymod/config-spec.json`
  /// @throws IllegalStateException if it is missing or malformed -- both are
  ///     packaging faults in the mod itself rather than anything an operator
  ///     can cause or fix, so they fail loudly at startup instead of degrading
  ///     to empty defaults.
  public static ConfigSpec load(Class<?> owner, String resource)
  {
    // Read the spec from the classpath
    try(InputStream in = owner.getResourceAsStream(resource)) {
      // If it's missing, fail loudly
      if(in == null)
        throw new IllegalStateException("Config spec not found on classpath: " + resource);

      // Try to load the file as UTF-8...
      try(Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
        // TODO: replace this with a proper JSON5 parser, requiring a complete
        // rebuild of the config mechanism to be independent of Gson so as to
        // preserve the non-JSON-strict features like comments.

        // Set up a JSON stream reader
        var json = new com.google.gson.stream.JsonReader(reader);

        // and configure it to be lenient (ie. allow for comments, etc.)
        json.setStrictness(com.google.gson.Strictness.LENIENT);

        // Parse the JSON
        JsonElement parsed = JsonParser.parseReader(json);
        if(!parsed.isJsonObject())
          throw new IllegalStateException(resource + (": root is not an "
                                                      + "object"));

        // and return a new spec object
        return new ConfigSpec(parsed.getAsJsonObject());
      }
    }

    // On file issues or bad JSON, fail loudly.
    catch(IOException | JsonParseException e) {
      throw new IllegalStateException("Could not read config spec " + resource, e);
    }
  }

  /// Wraps an already-parsed spec, for tests and for callers holding their own
  /// copy.
  public static ConfigSpec of(JsonObject spec)
  {
    return new ConfigSpec(spec);
  }

  /// The intended balance, shaped like the config file.
  ///
  /// This is what gets written into the operator's file for keys it does not
  /// mention. Settings with no `#initial` are omitted rather than
  /// defaulted: absent means "the fallback is already right", and writing it
  /// out would clutter the file with lines stating what would happen anyway.
  public JsonObject initialValues()
  {
    return collect(spec, INITIAL);
  }

  /// The benign fallbacks, shaped like the config file.
  ///
  /// Useful for checking the hand-written config classes against the spec: bind
  /// this and compare it with a freshly constructed instance, and any
  /// disagreement is a field whose initialiser has drifted from what the spec
  /// promises.
  public JsonObject defaultValues()
  {
    return collect(spec, DEFAULT);
  }

  /// Every setting's explanation, keyed by dotted path --
  /// `teaching.reputation.trading`.
  ///
  /// Flat because comments are looked up per setting, and a caller that has
  /// walked down to a leaf already knows the path it took.
  public Map<String, String> comments()
  {
    Map<String, String> out = new java.util.LinkedHashMap<>();
    collectComments(spec, "", out);
    return out;
  }

  private static void collectComments(JsonObject source, String prefix, Map<String, String> out)
  {
    // For each entry in the source object
    for(Map.Entry<String, JsonElement> entry: source.entrySet()) {
      // Get the key and value
      String key        = entry.getKey();
      JsonElement value = entry.getValue();

      // Split the prefix and the trailing suffix (including the hash)
      String prefixAndSuffix[] = getPrefixAndSuffix(key);
      if(prefixAndSuffix != null) {
        // If it's a #comment and the value is stringy...
        if(prefixAndSuffix[1].equals(COMMENT) && value.isJsonPrimitive()) {
          // Add it to the output object
          out.put(prefixAndSuffix[0], value.getAsString());

        } // Else, ignore.

        continue;
      }

      // If it's a note, ie. key.startsWith("//"), ignore it.
      if(isNote(key))
        continue;

      // So, it's a plain key; if the value's an object, then it's a group
      // to be recursed into.
      if(value.isJsonObject()) {
        // So recurse into it.
        collectComments(value.getAsJsonObject(), prefix + key + ".", out);
      }
    }
  };

  /// Builds one tree from a single enry.
  ///
  /// Walks the spec keeping its nesting: a plain object is a group and
  /// recurses, a `name#suffix` key contributes `name` when the suffix matches.
  /// Keys of neither kind -- `//` notes, `_version` -- are copied
  /// through, since a spec may legitimately state something the config file
  /// needs verbatim.
  private static JsonObject collect(JsonObject source, String suffix)
  {
    // Create a new object to hold the collected values
    JsonObject out = new JsonObject();

    // For each entry in the source object
    for(Map.Entry<String, JsonElement> entry: source.entrySet()) {
      // Get the key and value
      String key        = entry.getKey();
      JsonElement value = entry.getValue();

      // Split the prefix and the trailing suffix (including the hash)
      String prefixAndSuffix[] = getPrefixAndSuffix(key);
      if(prefixAndSuffix != null) {
        // If it's the suffix we're looking for...
        if(prefixAndSuffix[1].equals(suffix)) {
          // Add the value to the output object using the key without the suffix
          out.add(prefixAndSuffix[0], value);

        } // or ignore it if it's not the suffix we're looking for.

        // Either way, continue to the next entry.
        continue;
      }

      // If it's a note, ignore it
      if(isNote(key)) {
        continue;
      }

      // If not, then it's a plain key, to be included in the output object.

      // If it's a group, recurse
      if(value.isJsonObject()) {
        // Recurse into the object for the same suffix
        JsonObject group = collect(value.getAsJsonObject(), suffix);

        // A group whose settings all lack this suffix contributes nothing;
        // emitting it anyway would write empty objects into the operator's
        // file.
        if(!group.isEmpty())
          out.add(key, group);
      }

      // Else it's a simple value...
      else {
        // so just add it.
        out.add(key, value);
      }
    }

    // Return the collected object
    return out;
  }

  /// Whether a key is a note to the reader rather than a setting.
  ///
  /// `//`-prefixed keys are how a JSON file carries prose if comments
  /// aren't allowed. JSON5 and Lenient JSON can just use `//`, but it's
  /// not as easily captured.
  ///
  /// As the keys must be unique, make sure the comment is unique within
  /// its object.
  private static boolean isNote(String key)
  {
    return key.startsWith("//");
  }
}
