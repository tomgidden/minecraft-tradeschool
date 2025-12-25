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
