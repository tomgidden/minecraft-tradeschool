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
import java.util.function.BiConsumer;

/**
 * Loads and generates JSON config files, shared across the mods in this family.
 *
 * <h2>Two files, different jobs</h2>
 * <ul>
 *   <li>{@code config/<modid>.json} — what the operator edits. Strict JSON, and
 *       <em>sparse</em>: it only needs to contain settings being changed from their
 *       defaults.</li>
 *   <li>{@code config/<modid>.defaults.json} — generated reference, never read back. It
 *       lists every setting with its default and an explanation. Because nothing parses
 *       it, it can use JSON5-style {@code //} comments freely.</li>
 * </ul>
 *
 * <h2>Why overlay rather than generate-and-read</h2>
 * The obvious design — write a fully-populated file on first run, then read it — quietly
 * breaks on upgrade: a file written by v1 has no entry for a setting v2 introduced, so
 * either migration must know every key ever added, or the new setting silently misbehaves.
 * Overlaying instead means an absent key simply keeps its field initialiser, so new
 * settings work everywhere without migration. Migration is then reserved for things that
 * genuinely need it — renames, changed meanings, format changes — signalled by
 * {@code _version}.
 *
 * <h2>Broken files</h2>
 * A config that will not parse is moved aside with a timestamp and replaced with a fresh
 * default, loudly. Overwriting in place would destroy an operator's work over a stray
 * comma; refusing to start would take a server down for the same. Neither is worth it when
 * the file can simply be kept.
 */
public final class JsonConfig {

    /** Key carrying the schema version, used to decide whether migration is needed. */
    public static final String VERSION_KEY = "_version";

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create();

    private JsonConfig() {}

    /** Reports a problem to whatever logger the consuming mod uses. */
    public interface Log {
        void info(String message);
        void warn(String message);
    }

    /**
     * Rewrites an older config in place so it matches the current schema.
     *
     * Called only when the file's {@code _version} is behind {@code currentVersion}, with
     * the raw tree so keys can be renamed or values reinterpreted before binding.
     */
    public interface Migration {
        /**
         * @param root      the parsed file, mutated in place
         * @param fromVersion the version found in the file; 0 if it had none
         */
        void migrate(JsonObject root, int fromVersion);
    }

    /**
     * Loads {@code path} over the defaults in {@code target}.
     *
     * @param path           the operator's file; absent is normal and means "all defaults"
     * @param target         a freshly constructed instance carrying the default values
     * @param currentVersion schema version this build expects
     * @param migration      applied when the file predates {@code currentVersion}; may be null
     * @param log            where to report trouble
     * @return {@code target}, with any settings from the file applied
     */
    public static <T> T load(Path path, T target, int currentVersion,
                             Migration migration, Log log) {
        if (!Files.isRegularFile(path)) {
            return target;
        }

        JsonObject root;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new JsonParseException("root is not an object");
            }
            root = parsed.getAsJsonObject();
        } catch (IOException | JsonParseException e) {
            quarantine(path, e.toString(), log);
            return target;
        }

        int fileVersion = root.has(VERSION_KEY) && root.get(VERSION_KEY).isJsonPrimitive()
            ? root.get(VERSION_KEY).getAsInt()
            : 0;

        if (fileVersion < currentVersion && migration != null) {
            log.info("Migrating " + path.getFileName() + " from version "
                     + fileVersion + " to " + currentVersion);
            try {
                migration.migrate(root, fileVersion);
            } catch (Exception e) {
                quarantine(path, "migration failed: " + e, log);
                return target;
            }
        } else if (fileVersion > currentVersion) {
            log.warn(path.getFileName() + " was written by a newer version ("
                     + fileVersion + " > " + currentVersion + "); reading it anyway, but "
                     + "settings this build does not recognise will be ignored.");
        }

        // Merge the operator's keys onto the defaults as *trees*, then bind once.
        //
        // Binding the file directly would replace whole objects rather than merging them:
        // a file mentioning one field of "global" would construct a fresh GlobalConfig and
        // lose every other setting the bundled defaults supplied. Merging first means an
        // absent key genuinely keeps its default, at any depth.
        try {
            JsonElement defaults = GSON.toJsonTree(target);
            if (defaults.isJsonObject()) {
                JsonObject merged = defaults.getAsJsonObject();
                merge(merged, root);
                root = merged;
            }
            @SuppressWarnings("unchecked")
            T loaded = (T) GSON.fromJson(root, target.getClass());
            return loaded != null ? loaded : target;
        } catch (JsonParseException e) {
            quarantine(path, e.toString(), log);
            return target;
        }
    }

    /**
     * Tops up the operator's config with intended values for anything it does not mention.
     *
     * The compiled defaults are deliberately benign, so that merging a partial config
     * cannot smuggle in meaningful values the operator never chose. The consequence is
     * that the defaults alone do nothing useful, so the intended values have to reach the
     * operator's file somehow — and writing them there, rather than applying them
     * invisibly, means the file states the balance it is running.
     *
     * <p>Existing keys are never touched, at any depth: an operator who set something to
     * zero meant zero, and having it quietly restored would be worse than the setting
     * never existing. Only genuinely absent keys are added, which is also how a setting
     * introduced by a later version arrives with its intended value instead of a benign
     * one.
     *
     * @param initial the intended values, shaped like the config file
     * @return true if anything was written
     */
    public static boolean ensureInitialValues(Path path, JsonObject initial,
                                              int version, Log log) {
        JsonObject existing = new JsonObject();
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed.isJsonObject()) existing = parsed.getAsJsonObject();
            } catch (IOException | JsonParseException e) {
                // load() has already quarantined an unreadable file; nothing to top up.
                return false;
            }
        }

        int added = fillMissing(existing, initial);
        if (added == 0 && existing.has(VERSION_KEY)) return false;

        existing.addProperty(VERSION_KEY, version);
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                writer.write(GSON.toJson(existing));
                writer.write('\n');
            }
            if (added > 0) {
                log.info("Added " + added + " new setting(s) to " + path.getFileName());
            }
            return true;
        } catch (IOException e) {
            log.warn("Could not write " + path.getFileName() + ": " + e);
            return false;
        }
    }

    /** Copies keys of {@code source} absent from {@code target}. Returns how many. */
    private static int fillMissing(JsonObject target, JsonObject source) {
        int added = 0;
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            JsonElement incoming = entry.getValue();
            JsonElement present = target.get(entry.getKey());

            if (present == null) {
                target.add(entry.getKey(), incoming);
                added++;
            } else if (present.isJsonObject() && incoming.isJsonObject()) {
                added += fillMissing(present.getAsJsonObject(), incoming.getAsJsonObject());
            }
            // A present scalar or array is the operator's choice; leave it be.
        }
        return added;
    }

    /**
     * Merges {@code override} onto {@code base}, in place.
     *
     * Objects merge key by key, recursively; anything else replaces outright. Gson alone
     * cannot do this: binding a partial file over a populated instance replaces whole
     * objects, so a config saying no more than
     * <pre>{"global": {"debug_logging": true}}</pre>
     * would silently discard every other setting in {@code global}. The operator asked for
     * one change and lost the rest.
     *
     * <p><b>Arrays replace, they do not append.</b> Every list in this mod's config is a
     * complete statement — the enchantments a villager will not learn, the categories a
     * structure draws from, the filler trades for a profession. An operator writing one is
     * saying "this is the list now"; merging would make it impossible to remove a default
     * entry, which is usually the reason for editing a list at all.
     */
    private static void merge(JsonObject base, JsonObject override) {
        for (Map.Entry<String, JsonElement> entry : override.entrySet()) {
            JsonElement incoming = entry.getValue();
            JsonElement existing = base.get(entry.getKey());

            if (existing != null && existing.isJsonObject() && incoming.isJsonObject()) {
                merge(existing.getAsJsonObject(), incoming.getAsJsonObject());
            } else {
                base.add(entry.getKey(), incoming);
            }
        }
    }

    /**
     * Renames a file that cannot be used, so a fresh one can take its place without
     * destroying whatever the operator had written.
     */
    private static void quarantine(Path path, String reason, Log log) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path aside = path.resolveSibling(path.getFileName() + ".broken-" + stamp);
        try {
            Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING);
            log.warn("Could not read " + path.getFileName() + " (" + reason + "). "
                     + "Moved it to " + aside.getFileName() + " and continued with defaults.");
        } catch (IOException e) {
            log.warn("Could not read " + path.getFileName() + " (" + reason + ") and could "
                     + "not move it aside either (" + e + "). Continuing with defaults.");
        }
    }

    /**
     * Writes the reference file listing every setting, its default and its explanation.
     *
     * Generated by walking the object rather than serialising it, so each field can carry
     * the {@link Comment} above it — which is the whole reason this file exists, and
     * something Gson cannot produce.
     */
    public static void writeReference(Path path, Object defaults, int version,
                                      String modName, Log log) {
        writeReference(path, defaults, version, modName, null, log);
    }

    /**
     * As above, but nesting the documented settings inside {@code wrapperKey}.
     *
     * Used when the settings worth documenting are one block of a larger file: the
     * reference then shows the same shape the operator has to write, rather than a set of
     * keys that would silently do nothing at the top level.
     */
    public static void writeReference(Path path, Object defaults, int version,
                                      String modName, String wrapperKey, Log log) {
        StringBuilder out = new StringBuilder();
        out.append("// ").append(modName).append(" — every setting, with its default.\n")
           .append("//\n")
           .append("// FOR REFERENCE ONLY. This file is regenerated and may be overwritten or\n")
           .append("// deleted at any time; editing it has no effect. To change a setting, copy the\n")
           .append("// line into the config file beside this one and edit it there. Settings you do\n")
           .append("// not copy keep the defaults shown here, including ones added by later versions.\n")
           .append("{\n")
           .append("  \"").append(VERSION_KEY).append("\": ").append(version).append(",\n");

        if (wrapperKey != null) {
            out.append("  \"").append(wrapperKey).append("\": {\n");
            writeFields(out, defaults, 2);
            int lastComma = out.lastIndexOf(",");
            if (lastComma > 0 && out.substring(lastComma + 1).isBlank()) {
                out.deleteCharAt(lastComma);
            }
            out.append("  }\n");
        } else {
            writeFields(out, defaults, 1);
        }

        // Trim the trailing comma from the final entry so the result is valid JSON5.
        int lastComma = out.lastIndexOf(",");
        if (lastComma > 0 && out.substring(lastComma + 1).isBlank()) {
            out.deleteCharAt(lastComma);
        }
        out.append("}\n");

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                writer.write(out.toString());
            }
        } catch (IOException e) {
            log.warn("Could not write " + path.getFileName() + ": " + e);
        }
    }

    /** Recursively emits each field as a commented JSON5 entry. */
    private static void writeFields(StringBuilder out, Object owner, int depth) {
        String indent = "  ".repeat(depth);
        List<Field> fields = List.of(owner.getClass().getFields());

        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers())) continue;

            Comment comment = field.getAnnotation(Comment.class);
            if (comment != null) {
                for (String line : comment.value()) {
                    out.append(indent).append("// ").append(line).append('\n');
                }
            }

            Object value;
            try {
                value = field.get(owner);
            } catch (IllegalAccessException e) {
                continue;
            }

            String name = FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES
                .translateName(field);

            if (value != null && isNested(value)) {
                out.append(indent).append('"').append(name).append("\": {\n");
                writeFields(out, value, depth + 1);
                int lastComma = out.lastIndexOf(",");
                if (lastComma > 0 && out.substring(lastComma + 1).isBlank()) {
                    out.deleteCharAt(lastComma);
                }
                out.append(indent).append("},\n");
            } else {
                // Gson pretty-prints from column zero, so a multi-line list or map lands
                // flush against the left margin in the middle of an indented block.
                // Re-indent every line after the first to sit under its key.
                String rendered = GSON.toJson(value).replace("\n", "\n" + indent);
                out.append(indent).append('"').append(name).append("\": ")
                   .append(rendered).append(",\n");
            }
        }
    }

    /**
     * Whether a value should be expanded as a nested block rather than dumped as JSON.
     *
     * Only this family's own config objects are worth walking into; collections and
     * anything from the JDK or Minecraft serialise perfectly well in one piece.
     */
    private static boolean isNested(Object value) {
        return value.getClass().getName().startsWith("cx.gid.minecraft.")
            && !(value instanceof Iterable)
            && !(value instanceof java.util.Map);
    }

    /**
     * Convenience for the usual startup sequence: load the operator's file over the
     * defaults, then refresh the reference file so it documents this build.
     *
     * @param defaultsSupplier called twice, and must return a fresh instance each time —
     *     once to be loaded over, and once to document. Sharing a single instance would
     *     let the operator's settings leak into the reference file, which is supposed to
     *     show what the defaults <em>are</em>, not what this server happens to use.
     */
    public static <T> T loadAndDocument(Path configPath, java.util.function.Supplier<T> defaultsSupplier,
                                        int version, Migration migration, String modName, Log log) {
        T loaded = load(configPath, defaultsSupplier.get(), version, migration, log);
        Path reference = configPath.resolveSibling(
            stripExtension(configPath.getFileName().toString()) + ".defaults.json");
        writeReference(reference, defaultsSupplier.get(), version, modName, log);
        return loaded;
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
