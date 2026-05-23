package com.Acrobot.ChestShop.Market;

import com.Acrobot.ChestShop.ChestShop;
import net.democracycraft.business.api.BusinessApi;
import net.democracycraft.treasury.api.MarketApi;
import net.democracycraft.treasury.api.TreasuryApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.logging.Level;

/**
 * Holds the optional Treasury {@link MarketApi} (which persists the ChestShop
 * sales tracker + live shop registry in the shared economy DB) plus the
 * {@link TreasuryApi} / {@link BusinessApi} used to classify a shop's owning
 * account. Looked up from the Bukkit ServicesManager on enable — the same way
 * the Treasury economy adapter is wired. If the MarketApi isn't present the
 * tracker is simply inert.
 */
public final class MarketHook {

    private static MarketApi market;
    private static TreasuryApi treasury;
    private static BusinessApi business;

    private MarketHook() {}

    public static void init() {
        market = load(MarketApi.class);
        treasury = load(TreasuryApi.class);
        business = load(BusinessApi.class);
        ChestShop.getBukkitLogger().log(Level.INFO, "ChestShop market tracker {0}",
                enabled() ? "enabled" : "disabled (Treasury MarketApi not available)");
    }

    private static <T> T load(Class<T> type) {
        try {
            RegisteredServiceProvider<T> rsp = Bukkit.getServicesManager().getRegistration(type);
            return rsp != null ? rsp.getProvider() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when both the MarketApi (writes) and TreasuryApi (account classification) are available. */
    public static boolean enabled() {
        return market != null && treasury != null;
    }

    public static MarketApi market() { return market; }
    public static TreasuryApi treasury() { return treasury; }
    public static BusinessApi business() { return business; }
}
