package cx.gid.minecraft.common.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/// Loads and generates JSON config files, with a separate defaults file.
///
/// Note: Comments in the operator's file are not preserved, due to Gson
/// limitations.
///
/// Why overlay rather than generate-and-read:
/// The obvious design — write a fully-populated file on first run, then read it
/// — quietly breaks on upgrade: a file written by v1 has no entry for a setting
/// v2 introduced, so either migration must know every key ever added, or the
/// new setting silently misbehaves. Overlaying instead means an absent key
/// simply keeps its field initialiser, so new settings work everywhere without
/// migration. Migration is then reserved for things that genuinely need it —
/// renames, changed meanings, format changes — signalled by `_version`.
///
/// A config that will not parse is moved aside with a timestamp and replaced
/// with a fresh default, loudly.
public final class JsonConfig {

  /// Key carrying the schema version, used to decide whether migration is
  /// needed.
  public static final String VERSION_KEY = "_version";

  /// A nice builder for human-readable JSON.
  private static final Gson GSON =
      new GsonBuilder()
          .setPrettyPrinting()
          .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
          .create();

  private JsonConfig() {}

  /// Wraps a reader so comments and other JSON5-ish laxity are accepted.
  ///
  /// Comments do not survive. They are consumed at parse time and the tree has
  /// nowhere to hold them, so a file rewritten from it comes back without them.
  private static com.google.gson.stream.JsonReader lenient(Reader reader) {
    var json = new com.google.gson.stream.JsonReader(reader);
    json.setStrictness(com.google.gson.Strictness.LENIENT);
    return json;
  }

  /// Reports a problem to whatever logger the consuming mod uses.
  public interface Log {
    void info(String message);
    void warn(String message);
  }

  /// Rewrites an older config in place so it matches the current schema.
  ///
  /// Called only when the file's `_version` is behind `currentVersion`, with
  /// the raw tree so keys can be renamed or values reinterpreted before
  /// binding.
  public interface Migration {
    /// @param root the parsed file, mutated in place
    /// @param fromVersion the version found in the file; 0 if it had none
    void migrate(JsonObject root, int fromVersion);
  }

  /// Loads `path` over the defaults in `target`.
  ///
  /// @param path the operator's file; absent is normal and means "all
  ///     defaults"
  /// @param target a freshly constructed instance carrying the default
  ///     values
  /// @param currentVersion schema version this build expects
  /// @param migration applied when the file predates `currentVersion`; may be
  /// null
  /// @param log where to report trouble
  /// @return `target`, with any settings from the file applied
  public static <T> T load(Path path, T target, int currentVersion,
                           Migration migration, Log log) {

    if (target == null)
      throw new NullPointerException("target must not be null");

    // Fail clean if the file is not present, or is not a regular file, or is
    // empty.
    //
    // XXX: too narrow? Could a symlink be acceptable, or do we get into trouble
    // when migrating/writing out?
    if (!Files.isRegularFile(path)) {
      return target;
    }

    // Load the file as UTF-8 and parse it.
    JsonObject root;
    try (Reader reader =
             Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      JsonElement parsed = JsonParser.parseReader(lenient(reader));
      if (!parsed.isJsonObject())
        throw new JsonParseException("root is not an object");

      root = parsed.getAsJsonObject();
    } catch (IOException | JsonParseException e) {
      // Bad file, so move it out the way and start fresh.
      quarantine(path, e.toString(), log);
      return target;
    }

    // We should have root now.

    // If the file has a version, check it against the current version.
    int fileVersion =
        root.has(VERSION_KEY) && root.get(VERSION_KEY).isJsonPrimitive()
            ? root.get(VERSION_KEY).getAsInt()
            : 0;

    // If the file is too old, migrate it.
    if (fileVersion < currentVersion && migration != null) {
      log.info("Migrating " + path.getFileName() + " from version " +
               fileVersion + " to " + currentVersion);
      try {
        // Perform the migration
        migration.migrate(root, fileVersion);
      } catch (Exception e) {
        quarantine(path, "migration failed: " + e, log);
        return target;
      }
    }

    // Too new! Warn and ignore.
    else if (fileVersion > currentVersion) {
      log.warn(path.getFileName() + " was written by a newer version (" +
               fileVersion + " > " + currentVersion +
               "); reading it anyway, but "
               + "settings this build does not recognise will be ignored.");
    }

    // Merge the operator's keys onto the defaults as *trees*, then bind once.
    //
    // Binding the file directly would replace whole objects rather than merging
    // them: a file mentioning one field of "global" would construct a fresh
    // GlobalConfig and lose every other setting the bundled defaults supplied.
    // Merging first means an absent key genuinely keeps its default, at any
    // depth.
    try {
      JsonElement defaults = GSON.toJsonTree(target);
      if (defaults.isJsonObject()) {
        JsonObject merged = defaults.getAsJsonObject();
        merge(merged, root);
        root = merged;
      }

      @SuppressWarnings("unchecked")
      T loaded = (T)GSON.fromJson(root, target.getClass());

      return loaded != null ? loaded : target;
    }

    catch (JsonParseException e) {
      quarantine(path, e.toString(), log);
      return target;
    }
  }

  /// Tops up the operator's config with intended values for anything it does
  /// not mention.
  ///
  /// The compiled defaults are deliberately benign, so that merging a partial
  /// config cannot smuggle in meaningful values the operator never chose. The
  /// consequence is that the defaults alone do nothing useful, so the intended
  /// values have to reach the operator's file somehow — and writing them there,
  /// rather than applying them invisibly, means the file states the balance it
  /// is running.
  ///
  /// Classic example: otherwise if default was `setting: { foo: 1 }` then the
  /// operator might write `setting: { bar: 2 }` and not realise their config
  /// really means `setting: { foo: 1, bar: 2 }` as they're unaware of the
  /// merge. At least if we have a benign default of 0, we'd have `setting: {
  /// foo: 0, bar: 2 }` which might be closer to expectation.
  ///
  /// So, "defaults" are the benign "identity" values, whereas the "effective"
  /// defaults are the "initial values" that are written into the operator's
  /// config file on first run.
  ///
  /// @param initial the intended values, shaped like the config file
  /// @return true if anything was written
  public static boolean ensureInitialValues(Path path, JsonObject initial,
                                            int version, Log log) {

    // Start from scratch
    JsonObject existing = new JsonObject();

    if (Files.isRegularFile(path)) { // XXX: symlinks?

      // Load the config file
      try (Reader reader =
               Files.newBufferedReader(path, StandardCharsets.UTF_8)) {

        // Parse it
        JsonElement parsed = JsonParser.parseReader(lenient(reader));

        // and store it.  This is the first step.
        if (parsed.isJsonObject())
          existing = parsed.getAsJsonObject();

      } catch (IOException | JsonParseException e) {
        // load() has already quarantined an unreadable file; nothing to top up.
        return false;
      }
    }

    // Go through the config file and add any missing values
    int added = fillMissing(existing, initial);

    // If nothing was added, and the file already has a version, then we're
    // done.
    if (added == 0 && existing.has(VERSION_KEY))
      return false;

    // Add OR update the version
    existing.addProperty(VERSION_KEY, version);

    // Now try to write the updated/migrated file.
    try {

      // Create the parent directory if it doesn't exist
      Files.createDirectories(path.getParent());

      // Write the file
      try (Writer writer =
               Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
        writer.write(GSON.toJson(existing));
        writer.write('\n');
      }

      // Log the result
      if (added > 0)
        log.info("Added " + added + " new setting(s) to " + path.getFileName());

      return true;
    } catch (IOException e) {
      log.warn("Could not write " + path.getFileName() + ": " + e);
      return false;
    }
  }

  /// Copies keys of `source` absent from `target`. Recursive.
  /// Returns how many.
  private static int fillMissing(JsonObject target, JsonObject source) {

    // Count the additions
    int added = 0;

    // For each entry in the source object
    for (Map.Entry<String, JsonElement> entry : source.entrySet()) {

      // Get the value of the entry
      JsonElement incoming = entry.getValue();

      // and the current value, if any
      JsonElement present = target.get(entry.getKey());

      // If the key is not set in the target...
      if (present == null) {

        // Add it
        target.add(entry.getKey(), incoming);

        // and count it.
        added++;
      }

      // Else, if the existing value is an object, and the incoming value is an
      // object...
      else if (present.isJsonObject() && incoming.isJsonObject()) {

        // Then we need to recurse into the objects to merge them too,
        // adding their counts to the total.
        added +=
            fillMissing(present.getAsJsonObject(), incoming.getAsJsonObject());
      }

      // Else, a present scalar or array is the operator's choice; leave it be.
    }
    return added;
  }

  /// Merges `override` onto `base`, in place.
  ///
  /// Objects merge key by key, recursively; anything else replaces outright.
  /// Gson alone cannot do this: binding a partial file over a populated
  /// instance replaces whole objects, so a config saying no more than
  /// `{"debug_logging": true}` would silently would silently discard every
  /// other setting.
  ///
  /// However, arrays *and their contents, including objects* are not
  /// merged, so arrays effectively stop the merge process from descending
  /// further. We did consider using this as a convention (`[{...}]`) to make
  /// objects overwrite rather than merge, but while it'd be useful, it'd be
  /// confusing and idiosyncratic. We might revisit that, or use some other
  /// convention, eg. adding `!` to the key to indicate that it should
  /// overwrite rather than merge.
  private static void merge(JsonObject base, JsonObject override) {

    //  For each entry in the source object
    for (Map.Entry<String, JsonElement> entry : override.entrySet()) {

      // Get the value of the entry
      JsonElement incoming = entry.getValue();

      // and the current value, if any
      JsonElement existing = base.get(entry.getKey());

      // If the key is set in the target to an object, and the incoming value is
      // an object...
      if (existing != null && existing.isJsonObject() &&
          incoming.isJsonObject()) {

        // Then we need to recurse into the objects to merge them too.
        merge(existing.getAsJsonObject(), incoming.getAsJsonObject());
      }

      // Else, the incoming value overwrites the existing value.
      else {
        base.add(entry.getKey(), incoming);
      }
    }
  }

  // A migration's real work is moving settings around as the shape changes, and
  // doing that against a raw tree otherwise means nested
  // has/get/getAsJsonObject at every step. Dotted paths —
  // "global.trade.hint_radius" — keep a migration readable as a list of the
  // moves it makes, which is what anyone reading it later wants to see.

  /// Reads the value at a dotted path, or null if any part of it is missing.
  ///
  /// @param path dot-separated keys, e.g. `"feedback.chat"`
  public static JsonElement get(JsonObject root, String path) {

    // Resolve the path relative to the root object, and get the deepest
    // object above that path, such that `path='a.b.c.d' => parent=root.a.b.c;`
    JsonObject parent = parentOf(root, path, false);

    // If it doesn't exist, then we're done.
    if (parent == null)
      return null;

    // Get the value of that final part in that object.
    return parent.get(lastKey(path));
  }

  /// Removes and returns the value at a dotted path, or null if it was not
  /// there.
  public static JsonElement remove(JsonObject root, String path) {

    // Resolve the path relative to the root object, and get the deepest
    // object above that path, such that `path='a.b.c.d' => parent=root.a.b.c;`
    JsonObject parent = parentOf(root, path, false);

    // If it doesn't exist, then we're done.
    if (parent == null)
      return null;

    // Get the value and remove it.
    JsonElement result = parent.remove(lastKey(path));

    // Return the found value, now orphaned, presumably.
    return result;
  }

  /// Writes a value at a dotted path, creating intermediate objects as needed.
  public static void set(JsonObject root, String path, JsonElement value) {

    // Resolve the path relative to the root object, and get the deepest
    // object above that path, such that `path='a.b.c.d' => parent=root.a.b.c;`
    // and then add the final part to that object.
    parentOf(root, path, true).add(lastKey(path), value);
  }

  /// Moves the value at `from` to `to`, if it is present.
  ///
  /// A missing source is not an error: an operator's config only names the
  /// settings they changed, so most sources are absent in most files. An
  /// existing destination is left alone — a file that already states the new
  /// key has said something more current than whatever the old one held.
  ///
  /// @return true if something moved
  public static boolean move(JsonObject root, String from, String to) {

    // If the target object exists...
    if (get(root, to) != null) {

      // remove the corresponding source object
      remove(root, from);

      // and exit early so we don't trash an apparently already migrated object
      return false;
    }

    // Else, the target doesn't exist, so we can move it.

    // Get the value of the source object and remove it
    JsonElement value = remove(root, from);

    // If the value is null, then we're done.
    if (value == null)
      return false;

    // Otherwise, set it in the target object, thus moving from `root[[from]]`
    // to `root[[to]]`
    set(root, to, value);

    // and signal success.
    return true;
  }

  /// Resolve path relative to root and return the object containing the last
  /// key of `path`.
  ///
  /// @param create whether to build missing intermediate objects; false to just
  ///     look.
  ///
  /// @return the parent object, or null when `create` is false and the
  ///     path breaks — including when a step exists but is a scalar, which is a
  ///     malformed config rather than a path worth extending
  private static JsonObject parentOf(JsonObject root, String path,
                                     boolean create) {

    // Parse the path into its parts
    String[] parts = split(path);

    // Start at the root object
    JsonObject current = root;

    // For each part of the path...
    for (int i = 0; i < parts.length - 1; i++) {

      // Descend into the object along the path
      JsonElement next = current.get(parts[i]);

      // If the next element is not set, or not an object...
      if (next == null || !next.isJsonObject()) {

        // Then if we're not supposed to create it, then we're done.  This is
        // probably a failure.
        if (!create)
          return null;

        // Otherwise, create the next object.
        JsonObject made = new JsonObject();

        // Add this new child in the current level
        current.add(parts[i], made);

        // and then descend into it.
        current = made;
      }

      // Otherwise it's a valid object...
      else {
        // so descend into it.
        current = next.getAsJsonObject();
      }
    }

    // Return the current object.
    return current;
  }

  /// Splits a path into its parts.
  private static String[] split(String path) { return path.split("\\.+"); }

  /// Return the last component (the "leaf") of a path (dot-separated string)
  private static String lastKey(String path) {
    int dot = path.lastIndexOf('.');
    return dot < 0 ? path : path.substring(dot + 1);
  }

  /// Return the last component (the "leaf") of a path (an array of
  /// already-separated strings)
  private static String lastKey(String[] path) {
    return path.length > 0 ? path[path.length - 1] : "";
  }

  /// Renames a file that cannot be used, so a fresh one can take its place
  /// without destroying whatever the operator had written.
  private static void quarantine(Path path, String reason, Log log) {

    // Generate a timestamp for the file name
    String stamp = LocalDateTime.now().format(
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

    // And a path next to where it was found, with a timestamped name
    Path aside = path.resolveSibling(path.getFileName() + ".broken-" + stamp);

    // Try moving it there, and if that fails, log a warning
    try {
      // Move the file, firing an exception if it fails
      Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING);

      // and log a warning that the bad file was renamed
      log.warn("Could not read " + path.getFileName() + " (" + reason + "). "
               + "Moved it to " + aside.getFileName() +
               " and continued with defaults.");
    }

    // or the bad file wasn't renamed, so ...
    catch (IOException e) {
      // ugh, yeah, we probably just trash the file.
      log.warn("Could not read " + path.getFileName() + " (" + reason +
               ") and could "
               + "not move it aside either (" + e +
               "). Continuing with defaults.");
    }
  }

  /// Writes the reference file listing every setting, its default and its
  /// explanation.
  ///
  /// Generated by walking the object rather than serialising it, so each field
  /// can carry the Comment above it — which is the whole reason this
  /// file exists, and something Gson cannot produce.
  public static void writeReference(Path path, Object defaults, int version,
                                    String modName, Log log) {
    writeReference(path, defaults, version, modName, null, log);
  }

  /// As above, also naming the value each setting ships with where it differs.
  ///
  /// The compiled defaults are benign, so on their own they document a mod that
  /// does nothing. Stating the shipped value beside them shows both halves:
  /// what a setting falls back to if the config never mentions it, and what the
  /// config was given on first run.
  ///
  /// @param initialValues the intended values, shaped like the config file; may
  ///     be null
  public static void writeReference(Path path, Object defaults, int version,
                                    String modName, JsonObject initialValues,
                                    Log log) {

    // Prep an output buffer
    StringBuilder out = new StringBuilder();

    // Add the header explaining the file's purpose
    out.append("// ")
        .append(modName)
        .append(" — every setting, with its default.\n")
        .append("//\n")
        .append("// FOR REFERENCE ONLY. This file is regenerated and may be "
                + "overwritten or\n")
        .append("// deleted at any time; editing it has no effect. To change "
                + "a setting, copy the\n")
        .append("// line into the config file beside this one and edit it "
                + "there. Settings you do\n")
        .append("// not copy keep the defaults shown here, including ones "
                + "added by later versions.\n")

        // start the JSON object
        .append("{\n")
        .append("  \"")

        // and the version key plus version number:
        .append(VERSION_KEY)
        .append("\": ")
        .append(version)
        .append(",\n");

    // Write all the default config to the buffer
    writeFields(out, defaults, 1, initialValues);

    // Trim the trailing comma from the final entry so the result is valid JSON.
    // We could get away without doing this as we're lenient and writing
    // purported JSON5, but might as well be tidy if we can.  (Although arguably
    // it'd be easier for the op to cut-and-paste lines if the comma was there)
    int lastComma = out.lastIndexOf(",");
    if (lastComma > 0 && out.substring(lastComma + 1).isBlank()) {
      out.deleteCharAt(lastComma);
    }

    // Add the closing brace
    out.append("}\n");

    try {
      // Create the parent directory if it doesn't exist
      Files.createDirectories(path.getParent());

      // Write the file
      try (Writer writer =
               Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
        writer.write(out.toString());
      }
    } catch (IOException e) {
      // XXX: This might need to be a bit more dramatic, eg. actually fail /
      // crash
      log.warn("Could not write " + path.getFileName() + ": " + e);
    }
  }

  /// Recursively emits each field as a commented JSON5 entry.
  private static void writeFields(StringBuilder out, Object owner, int depth,
                                  JsonObject initialValues) {
    String indent = "  ".repeat(depth);
    List<Field> fields = List.of(owner.getClass().getFields());

    boolean first = true;
    for (Field field : fields) {
      if (Modifier.isStatic(field.getModifiers()))
        continue;

      Object value;
      try {
        value = field.get(owner);
      } catch (IllegalAccessException e) {
        continue;
      }

      // Convert the Java symbol to a JSON key
      String name =
          FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES.translateName(field);

      @SuppressWarnings("null")
      Comment comment = field.getAnnotation(Comment.class);

      if (comment != null) {
        // A blank line before each explanation, so the eye can tell where one
        // setting's prose ends and the next begins. Not before the first, which
        // would leave the block opening on an empty line.
        if (!first)
          out.append('\n');

        for (String line : comment.value()) {
          // A blank line in a comment is a paragraph break; emitting "// " with
          // nothing after it leaves trailing whitespace that some editors strip
          // and others flag.
          out.append(indent)
              .append(line.isEmpty() ? "//" : "// " + line)
              .append('\n');
        }
      }
      first = false;

      // The compiled defaults are benign — zero, false, empty — so on their own
      // they say nothing about what the mod actually does. Naming the shipped
      // value beside them lets a reader see both: what they inherit by saying
      // nothing, and what their config file was given on first run.
      if (initialValues != null) {
        JsonElement shipped = initialValues.get(name);
        if (shipped != null && !shipped.isJsonObject() &&
            !shipped.toString().equals(GSON.toJson(value))) {
          out.append(indent)
              .append("// Ships as: ")
              .append(shipped.toString().replace("\n", " "))
              .append('\n');
        }
      }

      if (value != null && isNested(value)) {
        out.append(indent).append('"').append(name).append("\": {\n");
        JsonElement nestedInitial =
            initialValues != null ? initialValues.get(name) : null;
        writeFields(out, value, depth + 1,
                    nestedInitial != null && nestedInitial.isJsonObject()
                        ? nestedInitial.getAsJsonObject()
                        : null);
        int lastComma = out.lastIndexOf(",");
        if (lastComma > 0 && out.substring(lastComma + 1).isBlank()) {
          out.deleteCharAt(lastComma);
        }
        out.append(indent).append("},\n");
      } else {
        // Gson pretty-prints from column zero, so a multi-line list or map
        // lands flush against the left margin in the middle of an indented
        // block. Re-indent every line after the first to sit under its key.
        String rendered = GSON.toJson(value).replace("\n", "\n" + indent);
        out.append(indent)
            .append('"')
            .append(name)
            .append("\": ")
            .append(rendered)
            .append(",\n");
      }
    }
  }

  /// Whether a value should be expanded as a nested block rather than dumped as
  /// JSON.
  ///
  /// Only this family's own config objects are worth walking into; collections
  /// and anything from the JDK or Minecraft serialise perfectly well in one
  /// piece.
  private static boolean isNested(Object value) {
    return value.getClass().getName().startsWith("cx.gid.minecraft.") &&
        !(value instanceof Iterable) && !(value instanceof java.util.Map);
  }

  /// Convenience for the usual startup sequence: load the operator's file over
  /// the defaults, then refresh the reference file so it documents this build.
  ///
  /// @param defaultsSupplier called twice, and must return a fresh instance
  /// each time — once to be loaded over, and once to document. Sharing a single
  /// instance would let the operator's settings leak into the reference
  /// file, which is supposed to show what the defaults *are*, not
  /// what this server happens to use.
  public static <T> T loadAndDocument(
      Path configPath, java.util.function.Supplier<T> defaultsSupplier,
      int version, Migration migration, String modName, Log log) {

    // Load the config file, clearing it up, migrating, refreshing, etc.
    // as needed.
    return loadAndDocument(configPath, defaultsSupplier, version, migration,
                           modName, null, log);
  }

  /// As above, with the shipped values named alongside the defaults in the
  /// reference.
  public static <T>
      T loadAndDocument(Path configPath,
                        java.util.function.Supplier<T> defaultsSupplier,
                        int version, Migration migration, String modName,
                        JsonObject initialValues, Log log) {

    // Load and migrate the config file
    T loaded =
        load(configPath, defaultsSupplier.get(), version, migration, log);

    // Generate a filename for the reference file, based on the config file's
    // name
    Path reference = configPath.resolveSibling(
        stripExtension(configPath.getFileName().toString()) +
        ".defaults.json5");

    // Write the reference file
    writeReference(reference, defaultsSupplier.get(), version, modName,
                   initialValues, log);

    // Return the loaded config
    return loaded;
  }

  private static String stripExtension(String filename) {

    int dot = filename.lastIndexOf('.');
    return dot > 0 ? filename.substring(0, dot) : filename;
  }
}
