package com.Acrobot.ChestShop.Listeners.PreTransaction;

import com.Acrobot.Breeze.Utils.PriceUtil;
import com.Acrobot.ChestShop.Events.PreTransactionEvent;
import com.Acrobot.ChestShop.Listeners.Block.Break.SignBreak;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.math.BigDecimal;

import static com.Acrobot.ChestShop.Events.PreTransactionEvent.TransactionOutcome.INVALID_SHOP;

/**
 * DemocracyCraft: free shops (a buy or sell price of exactly 0 — the {@code b:0}
 * / {@code s:0} case) are blocked at creation by {@link com.Acrobot.ChestShop.Listeners.PreShopCreation.FreePriceChecker}.
 * Any that pre-date that rule are cleaned up the first time someone interacts
 * with them: the transaction is cancelled with {@code INVALID_SHOP} — which
 * sends the "invalid shop" message via the PreTransaction ErrorMessageSender —
 * and the sign is broken (firing a ShopDestroyedEvent so the shop is
 * de-registered, then removing the block).
 */
public class FreeShopBreaker implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public static void onPreTransaction(PreTransactionEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Sign sign = event.getSign();
        if (sign == null) {
            return;
        }

        String price = ChestShopSign.getPrice(sign);
        if (!isFree(PriceUtil.getExactBuyPrice(price)) && !isFree(PriceUtil.getExactSellPrice(price))) {
            return;
        }

        // Cancel the trade (INVALID_SHOP → "invalid shop" message to the client)
        // and destroy the offending shop.
        event.setCancelled(INVALID_SHOP);
        SignBreak.sendShopDestroyedEvent(sign, event.getClient());
        sign.getBlock().breakNaturally();
    }

    private static boolean isFree(BigDecimal price) {
        return price.compareTo(PriceUtil.FREE) == 0;
    }
}
