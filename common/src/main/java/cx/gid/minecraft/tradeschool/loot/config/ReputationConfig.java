package cx.gid.minecraft.tradeschool.loot.config;

/**
 * Gossip awarded to a player for teaching a villager.
 *
 * Villager reputation is the sum of each gossip type's stored value times its weight, and
 * that total shifts trade prices. The two positive types behave very differently, which is
 * why both are exposed:
 *
 * <ul>
 *   <li>{@code minorPositive} — weight 1, caps at 25, decays 1/day. Improves prices
 *       noticeably without ever approaching cure-tier discounts.</li>
 *   <li>{@code majorPositive} — weight 5, caps at 20, <em>never decays</em>. This is
 *       curing's currency: curing a zombie villager grants the full 20 in one event, worth
 *       100 reputation permanently. Defaults to 0 here so teaching does not compete with
 *       or stack toward that.</li>
 * </ul>
 *
 * For scale, an ordinary trade grants TRADING +2, so the default +5 makes one teaching
 * worth roughly two and a half trades of standing.
 */
public class ReputationConfig {
    /** MINOR_POSITIVE gossip per teaching. Caps at 25, decays slowly. */
    public int minorPositivePerTeach = 5;

    /** MAJOR_POSITIVE gossip per teaching. Permanent and strong; 0 disables. */
    public int majorPositivePerTeach = 0;

    public ReputationConfig() {
        // Required for Gson deserialization
    }
}
