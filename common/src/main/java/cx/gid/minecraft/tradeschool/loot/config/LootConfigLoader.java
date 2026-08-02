package cx.gid.minecraft.tradeschool.loot.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import cx.gid.minecraft.tradeschool.Constants;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Loads the loot distribution configuration from JSON resources.
 */
public class LootConfigLoader {
  private static final String CONFIG_PATH =
      "/data/tradeschool/loot_distribution.json";

  /**
   * User-editable overrides, at {@code config/tradeschool.json}.
   *
   * The bundled JSON lives inside the jar and is therefore read-only — right for the loot
   * tables, useless for anything an operator wants to tune. This second file overlays it:
   * whatever it contains replaces the bundled value, and anything it omits keeps the
   * default, so operators only write down what they are actually changing.
   *
   * The relative {@code config/} path matches the convention already used by NoFlyZone and
   * Fire Sprinkler ({@code Paths.get("config", MOD_ID + ".properties")}) and needs no
   * loader-specific API on either Fabric or NeoForge.
   */
  private static final java.nio.file.Path USER_CONFIG =
      java.nio.file.Path.of("config", Constants.MOD_ID + ".json");

  /**
   * Schema version for {@code config/tradeschool.json}.
   *
   * Bump only when an existing setting is renamed or changes meaning — new settings need
   * no bump, because an absent key simply keeps its default. When it is bumped, extend
   * {@link #MIGRATION} to rewrite the older shape.
   */
  private static final int CONFIG_VERSION = 1;

  /**
   * Rewrites older config files to the current schema.
   *
   * Nothing to do yet: version 1 is the first. A file written before versioning existed
   * reports version 0, which is treated as "same shape, no marker" rather than an error.
   */
  private static final cx.gid.minecraft.common.config.JsonConfig.Migration MIGRATION =
      (root, fromVersion) -> {
        // Example for the future:
        //   if (fromVersion < 2 && root.has("old_name")) {
        //       root.add("new_name", root.remove("old_name"));
        //   }
      };

  /** Bridges JsonConfig's diagnostics to this mod's logger. */
  private static final cx.gid.minecraft.common.config.JsonConfig.Log LOG =
      new cx.gid.minecraft.common.config.JsonConfig.Log() {
        @Override public void info(String message) { Constants.LOGGER.info(message); }
        @Override public void warn(String message) { Constants.LOGGER.warn(message); }
      };

  /**
   * Overlays {@code config/tradeschool.json} onto the bundled defaults, and regenerates
   * the reference file listing every setting.
   *
   * The overlay applies to the whole config, so an operator can override loot structures
   * as well as the tunables — though a datapack is the better tool for the former.
   */
  private static LootConfig applyUserOverrides(LootConfig bundled) {
    LootConfig result = cx.gid.minecraft.common.config.JsonConfig.load(
        USER_CONFIG, bundled, CONFIG_VERSION, MIGRATION, LOG);

    // The reference documents the *defaults*, not this server's choices, so it is
    // generated from a pristine config rather than the one just loaded.
    //
    // Only the "global" block is documented. The structures and categories blocks run to
    // hundreds of lines of loot tables that are better edited as a datapack, and dumping
    // them here would bury the settings an operator actually wants.
    cx.gid.minecraft.common.config.JsonConfig.writeReference(
        USER_CONFIG.resolveSibling(Constants.MOD_ID + ".defaults.json"),
        loadBundled().global, CONFIG_VERSION, Constants.MOD_NAME, "global", LOG);

    if (java.nio.file.Files.isRegularFile(USER_CONFIG)) {
      Constants.LOGGER.info("Applied overrides from {}", USER_CONFIG);
    }
    return result;
  }

  /** A pristine copy of the bundled config, for documenting defaults. */
  private static LootConfig loadBundled() {
    try (InputStream stream = LootConfigLoader.class.getResourceAsStream(CONFIG_PATH)) {
      if (stream == null) return LootConfig.createDefault();
      Gson gson = new GsonBuilder()
          .setFieldNamingPolicy(com.google.gson.FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
          .create();
      LootConfig config = gson.fromJson(
          new InputStreamReader(stream, StandardCharsets.UTF_8), LootConfig.class);
      if (config == null) return LootConfig.createDefault();
      if (config.global == null) config.global = new GlobalConfig();
      config.global.applyDefaults();
      return config;
    } catch (Exception e) {
      return LootConfig.createDefault();
    }
  }

  /**
   * Loads the loot distribution configuration from the bundled JSON file.
   *
   * @return The loaded configuration, or a default config if loading fails
   */
  public static LootConfig load() {
    Constants.LOGGER.info("Loading loot distribution configuration from {}",
                          CONFIG_PATH);

    try (InputStream stream =
             LootConfigLoader.class.getResourceAsStream(CONFIG_PATH)) {
      if (stream == null) {
        Constants.LOGGER.error("Could not find loot distribution config at {}",
                               CONFIG_PATH);
        Constants.LOGGER.warn("Using default configuration");
        return LootConfig.createDefault();
      }

      Gson gson = new GsonBuilder()
          .setPrettyPrinting()
          .setFieldNamingPolicy(com.google.gson.FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
          .create();

      InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);

      LootConfig config = gson.fromJson(reader, LootConfig.class);

      // Gson omits no-arg field initialisers only for keys the JSON does supply, but a
      // present-and-null sub-object would slip through — normalise before use.
      if (config.global == null) {
        config.global = new GlobalConfig();
      }
      config.global.applyDefaults();

      // Let the operator's file override the bundled defaults, and refresh the reference
      // file so it documents this build's settings.
      config = applyUserOverrides(config);
      config.global.applyDefaults();

      validateConfig(config);

      Constants.LOGGER.info(
          "Loaded loot distribution config version {} with {} structures",
          config.version, config.structures.size());

      return config;

    } catch (Exception e) {
      Constants.LOGGER.error("Failed to load loot distribution config", e);
      Constants.LOGGER.warn("Using default configuration");
      return LootConfig.createDefault();
    }
  }

  /**
   * Validates the loaded configuration and logs warnings for invalid entries.
   *
   * @param config The configuration to validate
   */
  private static void validateConfig(LootConfig config) {
    int warnings = 0;
    int errors = 0;

    // Validate global config
    if (config.global == null) {
      Constants.LOGGER.error("Global config is null - this will cause issues");
      errors++;
    }

    // Validate categories
    if (config.categories == null || config.categories.isEmpty()) {
      Constants.LOGGER.warn("No categories defined in config");
      warnings++;
    } else {
      // Validate each category
      for (Map.Entry<String, CategoryConfig> entry :
           config.categories.entrySet()) {
        CategoryConfig category = entry.getValue();
        if (category.enchantments == null || category.enchantments.isEmpty()) {
          Constants.LOGGER.warn("Category '{}' has no enchantments defined",
                                entry.getKey());
          warnings++;
        }
        if (category.defaultWeight <= 0) {
          Constants.LOGGER.warn("Category '{}' has invalid default weight: {}",
                                entry.getKey(), category.defaultWeight);
          warnings++;
        }
      }
    }

    // Validate structures
    if (config.structures == null || config.structures.isEmpty()) {
      Constants.LOGGER.error(
          "No structures defined in config - loot distribution will not work!");
      errors++;
    } else {
      int enabledStructures = 0;

      for (Map.Entry<String, StructureConfig> entry :
           config.structures.entrySet()) {
        String structureId = entry.getKey();
        StructureConfig structureConfig = entry.getValue();

        if (!structureConfig.enabled) {
          continue; // Skip disabled structures
        }

        enabledStructures++;

        // Validate tier
        if (structureConfig.getTier() == null) {
          Constants.LOGGER.error(
              "Structure '{}' has invalid tier '{}' - will default to MEDIUM",
              structureId, structureConfig.tier);
          errors++;
        }

        // Validate rolls
        if (structureConfig.rollsPerChest <= 0) {
          Constants.LOGGER.warn(
              "Structure '{}' has invalid rolls_per_chest: {} - using 1",
              structureId, structureConfig.rollsPerChest);
          warnings++;
        }

        // Validate categories exist
        if (structureConfig.primaryCategories != null) {
          for (String category : structureConfig.primaryCategories) {
            if (config.categories != null &&
                !config.categories.containsKey(category)) {
              // Check if it's a valid enum category
              try {
                cx.gid.minecraft.tradeschool.loot.category.EnchantmentCategory
                    .valueOf(category.toUpperCase());
              } catch (IllegalArgumentException e) {
                Constants.LOGGER.warn(
                    "Structure '{}' references unknown category '{}'",
                    structureId, category);
                warnings++;
              }
            }
          }
        }

        // Validate crossover categories
        if (structureConfig.crossoverCategories != null) {
          for (Map.Entry<String, Double> crossover :
               structureConfig.crossoverCategories.entrySet()) {
            double probability = crossover.getValue();
            if (probability < 0.0 || probability > 1.0) {
              Constants.LOGGER.warn("Structure '{}' has invalid crossover " +
                                    "probability for '{}': {}",
                                    structureId, crossover.getKey(),
                                    probability);
              warnings++;
            }
          }
        }

        // Validate custom enchantments
        if (structureConfig.customEnchantments != null) {
          for (Map.Entry<String, EnchantmentWeight> ench :
               structureConfig.customEnchantments.entrySet()) {
            EnchantmentWeight weight = ench.getValue();
            if (weight.getWeight() <= 0) {
              Constants.LOGGER.warn(
                  "Structure '{}' has invalid weight for enchantment '{}': {}",
                  structureId, ench.getKey(), weight.getWeight());
              warnings++;
            }
            if (weight.getMaxLevel() != null && weight.getMaxLevel() <= 0) {
              Constants.LOGGER.warn("Structure '{}' has invalid max level " +
                                    "for enchantment '{}': {}",
                                    structureId, ench.getKey(),
                                    weight.getMaxLevel());
              warnings++;
            }
          }
        }
      }

      Constants.LOGGER.info("Config validation complete: {} enabled " +
                            "structures, {} warnings, {} errors",
                            enabledStructures, warnings, errors);
    }

    if (errors > 0) {
      Constants.LOGGER.error(
          "Configuration has {} errors - some features may not work correctly",
          errors);
    }
  }
}
