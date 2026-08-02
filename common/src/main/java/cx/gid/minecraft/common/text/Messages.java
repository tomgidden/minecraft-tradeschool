package cx.gid.minecraft.common.text;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player-facing messages, translated <em>server-side</em> where the client
 * cannot translate them itself.
 *
 * <h2>The problem</h2>
 * {@code Component.translatable} is resolved by the <em>client</em>, against
 * language files the client holds. A player without the mod installed has no
 * namespace for it at all, so a bare {@code translatable} renders as the literal key --
 * {@code somemod.message.denied} -- which is exactly what an unmodded player would see at
 * the moment the mod was trying to explain itself.
 *
 * <h2>The fix</h2>
 * {@code translatableWithFallback} carries a literal string alongside the key.
 * A client that has the mod translates the key as normal and ignores the
 * fallback; a client that does not renders the fallback verbatim.
 *
 * <p>Crucially the fallback is chosen <em>per send, on the server</em>, and
 * nothing requires it to be English. So this class loads the mod's own language
 * files -- the same {@code assets/<modid>/lang/*.json} shipped in the jar --
 * and picks the fallback matching that player's client language, obtained from
 * {@link ServerPlayer#clientInformation()}. An unmodded French client therefore
 * gets French text, because the <em>server</em> translated it.
 *
 * <p>The result covers both audiences from one call:
 * <ul>
 *   <li>modded client -- translates the key locally, honouring resource packs;</li>
 *   <li>unmodded client -- renders a fallback already in its own language.</li>
 * </ul>
 *
 * <h2>Fallback chain</h2>
 * Player's locale, then its language without the region ({@code fr_ca} tries
 * {@code fr_fr}), then {@code en_us}, then the key itself. A locale the mod does
 * not ship simply reads as English, which is the ordinary outcome for any mod.
 */
public final class Messages {

    /** The language every lookup ultimately falls back to. */
    private static final String DEFAULT_LANGUAGE = "en_us";

    /**
     * Loaded language tables, keyed by locale ({@code "fr_fr"}). Populated
     * lazily: a server whose players are all English never reads another file.
     */
    private final Map<String, Map<String, String>> TABLES = new ConcurrentHashMap<>();

    /** Where diagnostics go; defaults to discarding them. */
    private java.util.function.Consumer<String> logger = m -> {};

    /** Routes load diagnostics to the consuming mod's logger. */
    public Messages withLogger(java.util.function.Consumer<String> logger) {
        this.logger = logger;
        return this;
    }

    private final String modId;

    /**
     * @param modId names the namespace whose {@code assets/<modId>/lang/*.json} are read.
     *     Passed in rather than read from a Constants class so this stays mod-agnostic.
     */
    public Messages(String modId) {
        this.modId = modId;
    }

    /**
     * A component that translates for clients with the mod, and reads in the
     * player's own language for those without.
     */
    public MutableComponent of(ServerPlayer player, String key) {
        return Component.translatableWithFallback(key, lookup(languageOf(player), key));
    }

    /**
     * As {@link #of(ServerPlayer, String)}, with arguments substituted into the
     * fallback.
     *
     * <p>The fallback must be pre-formatted because the client only substitutes
     * into a string it resolved itself; a fallback is rendered as-is.
     */
    public MutableComponent of(ServerPlayer player, String key, Object... args) {
        String pattern = lookup(languageOf(player), key);
        return Component.translatableWithFallback(key, format(pattern, args), args);
    }

    /**
     * As {@link #of(ServerPlayer, String)} but for a recipient whose language is
     * unknown -- the console, a command block, or an offline player. Always
     * English.
     */
    public MutableComponent ofDefault(String key, Object... args) {
        String pattern = lookup(DEFAULT_LANGUAGE, key);
        return Component.translatableWithFallback(key, format(pattern, args), args);
    }

    /**
     * Substitutes placeholders the way vanilla's own translation does, in both
     * the positional ({@code %s}) and indexed ({@code %1$s}) forms.
     *
     * <p>The indexed form matters for more than completeness: a language whose
     * word order differs from English needs it to keep arguments straight, and
     * {@code en_ud} needs it because reversing a string reverses its
     * placeholders too. Vanilla's own {@code en_ud} uses {@code %1$s} for
     * exactly this reason, so any translation modelled on it will as well.
     *
     * <p>Deliberately not {@code String.format}: a stray {@code %} in a
     * translated string would throw, and a message about a refused trade is
     * not worth an exception. Anything unparseable is passed through unchanged.
     */
    private static String format(String pattern, Object... args) {
        if (args == null || args.length == 0) {
            return pattern;
        }

        StringBuilder out = new StringBuilder(pattern.length() + 16);
        int nextArg = 0;

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c != '%' || i + 1 >= pattern.length()) {
                out.append(c);
                continue;
            }

            // "%s" -- take the next argument in order.
            if (pattern.charAt(i + 1) == 's') {
                if (nextArg < args.length) {
                    out.append(stringify(args[nextArg++]));
                    i++;
                    continue;
                }
                out.append(c);
                continue;
            }

            // "%<n>$s" -- take the n'th argument, 1-based.
            int j = i + 1;
            int index = 0;
            while (j < pattern.length() && Character.isDigit(pattern.charAt(j))) {
                index = index * 10 + (pattern.charAt(j) - '0');
                j++;
            }
            if (j + 1 < pattern.length() && j > i + 1
                && pattern.charAt(j) == '$' && pattern.charAt(j + 1) == 's'
                && index >= 1 && index <= args.length) {
                out.append(stringify(args[index - 1]));
                i = j + 1;
                continue;
            }

            out.append(c);
        }
        return out.toString();
    }

    /** Renders an argument, resolving nested components to their plain text. */
    private static String stringify(Object arg) {
        if (arg instanceof Component component) {
            return component.getString();
        }
        return String.valueOf(arg);
    }

    /** The player's client language, normalised, or the default if unavailable. */
    private static String languageOf(ServerPlayer player) {
        if (player == null) {
            return DEFAULT_LANGUAGE;
        }
        try {
            String language = player.clientInformation().language();
            if (language == null || language.isBlank()) {
                return DEFAULT_LANGUAGE;
            }
            return language.trim().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            // clientInformation is populated at login; be defensive rather than
            // let a message lookup break the trade.
            return DEFAULT_LANGUAGE;
        }
    }

    /**
     * Resolves a key in the given language, walking the fallback chain.
     */
    private String lookup(String language, String key) {
        String value = tableFor(language).get(key);
        if (value != null) {
            return value;
        }

        // "fr_ca" is not shipped, but "fr_fr" very likely is.
        int underscore = language.indexOf('_');
        if (underscore > 0) {
            String prefix = language.substring(0, underscore);
            for (String candidate : TABLES.keySet()) {
                if (candidate.startsWith(prefix + "_")) {
                    String regional = TABLES.get(candidate).get(key);
                    if (regional != null) {
                        return regional;
                    }
                }
            }
            String guess = tableFor(prefix + "_" + prefix).get(key);
            if (guess != null) {
                return guess;
            }
        }

        value = tableFor(DEFAULT_LANGUAGE).get(key);
        return value != null ? value : key;
    }

    /**
     * The language table for a locale, loading it from the jar on first use.
     *
     * <p>A missing file caches an empty table, so a server full of players with
     * unshipped locales does not retry the classpath on every message.
     */
    private Map<String, String> tableFor(String language) {
        return TABLES.computeIfAbsent(language, this::load);
    }

    private Map<String, String> load(String language) {
        String path = "/assets/" + modId + "/lang/" + language + ".json";

        try (InputStream in = Messages.class.getResourceAsStream(path)) {
            if (in == null) {
                return Collections.emptyMap();
            }
            JsonObject json = JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();

            Map<String, String> table = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    table.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            logger.accept("loaded " + table.size() + " strings for " + language);
            return Map.copyOf(table);
        } catch (Exception e) {
            logger.accept("could not read language " + language + ": " + e);
            return Collections.emptyMap();
        }
    }
}
