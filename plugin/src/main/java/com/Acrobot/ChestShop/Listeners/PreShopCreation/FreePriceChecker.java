package com.Acrobot.ChestShop.Listeners.PreShopCreation;

import com.Acrobot.Breeze.Utils.PriceUtil;
import com.Acrobot.ChestShop.Events.PreShopCreationEvent;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.math.BigDecimal;

import static com.Acrobot.ChestShop.Events.PreShopCreationEvent.CreationOutcome.INVALID_PRICE;

/**
 * DemocracyCraft: free shops are not allowed — a buy or sell price of exactly 0
 * (the {@code b:0} / {@code s:0} case). Runs after {@link PriceChecker} (LOWEST)
 * has normalised the price line, reads the resulting buy/sell prices, and
 * rejects creation if either offered side is priced at 0. An <em>unoffered</em>
 * side ({@link PriceUtil#NO_PRICE} / -1) is fine — a buy-only or sell-only shop
 * with a non-zero price is unaffected.
 */
public class FreePriceChecker implements Listener {

    @EventHandler(priority = EventPriority.NORMAL)
    public static void onPreShopCreation(PreShopCreationEvent event) {
        String price = ChestShopSign.getPrice(event.getSignLines());
        if (isFree(PriceUtil.getExactBuyPrice(price)) || isFree(PriceUtil.getExactSellPrice(price))) {
            event.setOutcome(INVALID_PRICE);
        }
    }

    private static boolean isFree(BigDecimal price) {
        return price.compareTo(PriceUtil.FREE) == 0;
    }
}
