package cx.gid.minecraft.tradeschool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Constants {
    public static final String MOD_ID = "tradeschool";
    public static final String MOD_NAME = "Trade School";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    /**
     * Whether verbose per-event logging is enabled, from the {@code debugLogging} config flag.
     *
     * The mod's tracing runs at INFO because most of it needs to be visible without
     * reconfiguring log4j, but that means it is on by default and floods a busy server —
     * teach scans and loot modifications both fire constantly. Guard those call sites with
     * this rather than demoting them to LOGGER.debug().
     *
     * Safe before config load: the manager falls back to a default config, so this reads
     * false rather than throwing.
     */
    public static boolean debugLogging() {
        try {
            return cx.gid.minecraft.tradeschool.loot.LootDistributionManager.getInstance()
                .getConfig().global.debugLogging;
        } catch (Exception e) {
            return false;
        }
    }

    /** Logs at INFO only when debug logging is enabled. */
    public static void debug(String format, Object... args) {
        if (debugLogging()) {
            LOGGER.info(format, args);
        }
    }
}
