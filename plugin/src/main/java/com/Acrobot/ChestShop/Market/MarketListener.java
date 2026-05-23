package com.Acrobot.ChestShop.Market;

import com.Acrobot.Breeze.Utils.MaterialUtil;
import com.Acrobot.ChestShop.Events.ShopCreatedEvent;
import com.Acrobot.ChestShop.Events.ShopDestroyedEvent;
import com.Acrobot.ChestShop.Events.TransactionEvent;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import com.Acrobot.ChestShop.Utils.uBlock;
import net.democracycraft.treasury.api.MarketApi;
import org.bukkit.Location;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Keeps the ChestShop sales tracker + live shop registry up to date:
 *  - {@link TransactionEvent}: record the trade + upsert the shop (lazy-registers
 *    pre-existing shops on first activity and refreshes post-trade stock).
 *  - {@link ShopCreatedEvent}: register/refresh the shop.
 *  - {@link ShopDestroyedEvent}: mark it inactive.
 *  - {@link InventoryCloseEvent}: a manual restock — recount stock for the
 *    connected shop signs (mirrors ChestShop's own sign-counter refresh).
 *
 * All handlers are MONITOR + fully guarded: analytics must never disrupt a trade.
 */
public class MarketListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransaction(TransactionEvent event) {
        if (!MarketHook.enabled()) return;
        try {
            ItemStack[] stock = event.getStock();
            if (stock == null || stock.length == 0 || stock[0] == null) return;
            ItemStack item = stock[0];
            int quantity = MarketRecords.totalAmount(stock);
            Sign sign = event.getSign();
            boolean admin = ChestShopSign.isAdminShop(sign);
            UUID ownerUuid = event.getOwnerAccount() != null ? event.getOwnerAccount().getUuid() : null;
            MarketRecords.Owner owner = MarketRecords.ownerFromUuid(ownerUuid, admin);
            String direction = event.getTransactionType() == TransactionEvent.TransactionType.BUY ? "BUY" : "SELL";
            Integer shopStock = admin ? null : MarketRecords.stockOf(item, event.getOwnerInventory());

            MarketApi market = MarketHook.market();
            market.recordSale(MarketRecords.sale(sign, item, quantity, event.getClient().getUniqueId(),
                    owner, event.getExactPrice(), BigDecimal.ZERO, direction, shopStock));
            market.upsertShop(MarketRecords.shop(sign, item, owner, shopStock));
        } catch (Throwable ignored) {
            // analytics only
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShopCreated(ShopCreatedEvent event) {
        if (!MarketHook.enabled()) return;
        try {
            Sign sign = event.getSign();
            ItemStack item = MaterialUtil.getItem(event.getSignLines()[ChestShopSign.ITEM_LINE]);
            if (item == null) return;
            boolean admin = ChestShopSign.isAdminShop(event.getSignLines());
            UUID ownerUuid = event.getOwnerAccount() != null ? event.getOwnerAccount().getUuid() : null;
            MarketRecords.Owner owner = MarketRecords.ownerFromUuid(ownerUuid, admin);
            Integer stock = (!admin && event.getContainer() != null)
                    ? MarketRecords.stockOf(item, event.getContainer().getInventory())
                    : null;
            MarketHook.market().upsertShop(MarketRecords.shop(sign, item, owner, stock));
        } catch (Throwable ignored) {
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShopDestroyed(ShopDestroyedEvent event) {
        if (!MarketHook.enabled()) return;
        try {
            Location l = event.getSign().getLocation();
            MarketHook.market().deactivateShop(
                    l.getWorld() != null ? l.getWorld().getName() : null,
                    l.getBlockX(), l.getBlockY(), l.getBlockZ());
        } catch (Throwable ignored) {
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!MarketHook.enabled()) return;
        try {
            InventoryHolder holder = event.getInventory().getHolder();
            if (holder == null) return;
            List<Sign> signs = uBlock.findConnectedShopSigns(holder);
            if (signs.isEmpty()) return;
            Inventory inv = event.getInventory();
            MarketApi market = MarketHook.market();
            for (Sign sign : signs) {
                if (ChestShopSign.isAdminShop(sign)) continue;
                String itemName = ChestShopSign.getItem(sign);
                ItemStack item = itemName != null ? MaterialUtil.getItem(itemName) : null;
                if (item == null) continue;
                Location l = sign.getLocation();
                market.updateShopStock(
                        l.getWorld() != null ? l.getWorld().getName() : null,
                        l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                        MarketRecords.stockOf(item, inv));
            }
        } catch (Throwable ignored) {
        }
    }
}
