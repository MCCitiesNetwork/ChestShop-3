package com.Acrobot.ChestShop.Market;

import com.Acrobot.Breeze.Utils.InventoryUtil;
import com.Acrobot.Breeze.Utils.MaterialUtil;
import com.Acrobot.Breeze.Utils.PriceUtil;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import net.democracycraft.business.api.BusinessApi;
import net.democracycraft.business.model.Firm;
import net.democracycraft.treasury.api.TreasuryApi;
import net.democracycraft.treasury.api.market.ChestShopSaleRecord;
import net.democracycraft.treasury.api.market.ChestShopShopRecord;
import org.bukkit.Location;
import org.bukkit.block.Sign;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds the MarketApi DTOs from ChestShop data: classifies the owning account
 * (personal / business firm / government / admin) via the Treasury + Business
 * APIs, and resolves the real item (incl. custom items) via ChestShop's own
 * item encoding. Pure helper — no state.
 */
final class MarketRecords {

    /** Mirrors ChestShop's synthetic-UUID scheme for business accounts: UUID(MSB, accountId). */
    static final long BUSINESS_UUID_MSB = 0xC5B0000000000000L;

    private MarketRecords() {}

    record Owner(Integer accountId, String type, Integer firmId, UUID ownerUuid, boolean admin) {}

    /** Resolve the shop's owning account from the ChestShop owner-account UUID. */
    static Owner ownerFromUuid(UUID ownerUuid, boolean adminShop) {
        if (adminShop || ownerUuid == null) {
            return new Owner(null, null, null, null, true);
        }
        TreasuryApi treasury = MarketHook.treasury();
        int accountId;
        if (ownerUuid.getMostSignificantBits() == BUSINESS_UUID_MSB) {
            accountId = (int) ownerUuid.getLeastSignificantBits();
        } else {
            net.democracycraft.treasury.model.economy.Account personal = treasury.getAccountByUUID(ownerUuid);
            if (personal == null) {
                return new Owner(null, null, null, null, false);
            }
            accountId = personal.getAccountId();
        }
        return classify(accountId);
    }

    static Owner classify(int accountId) {
        net.democracycraft.treasury.model.economy.Account acc = MarketHook.treasury().getAccountById(accountId);
        if (acc == null) {
            return new Owner(accountId, null, null, null, false);
        }
        String type = acc.getAccountType() != null ? acc.getAccountType().name() : null;
        UUID ownerUuid = "PERSONAL".equals(type) ? acc.getOwnerUuid() : null;
        Integer firmId = null;
        BusinessApi business = MarketHook.business();
        if ("BUSINESS".equals(type) && business != null) {
            Firm firm = business.firms().getFirmByAccountId(accountId);
            if (firm != null) {
                firmId = firm.getFirmId();
            }
        }
        return new Owner(accountId, type, firmId, ownerUuid, false);
    }

    // ── item identity (ChestShop already encodes custom/enchanted items) ──
    static String itemKey(ItemStack item) { return MaterialUtil.getSignName(item); }

    static boolean isCustom(ItemStack item) {
        String key = MaterialUtil.getSignName(item);
        return (key != null && key.contains("#")) || item.hasItemMeta();
    }

    static String itemData(ItemStack item) {
        if (!isCustom(item)) return null;
        try {
            YamlConfiguration yc = new YamlConfiguration();
            yc.set("item", item);
            return yc.saveToString();
        } catch (Throwable t) {
            return null;
        }
    }

    static int stockOf(ItemStack item, Inventory inventory) {
        return inventory == null ? 0 : InventoryUtil.getAmount(item, inventory);
    }

    private static BigDecimal nonNegativeOrNull(BigDecimal v) {
        return (v == null || v.signum() < 0) ? null : v;
    }

    // ── DTO builders ──
    static ChestShopSaleRecord sale(Sign sign, ItemStack item, int quantity, UUID customer,
                                    Owner owner, BigDecimal total, BigDecimal tax,
                                    String direction, Integer shopStock) {
        Location l = sign.getLocation();
        BigDecimal unit = quantity > 0
                ? total.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP)
                : total;
        return new ChestShopSaleRecord(
                null, direction, customer,
                owner.accountId(), owner.type(), owner.firmId(), owner.ownerUuid(), owner.admin(),
                item.getType().name(), itemKey(item), MaterialUtil.getName(item), isCustom(item), itemData(item),
                quantity, unit, total, tax != null ? tax : BigDecimal.ZERO,
                worldName(l), l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                owner.admin() ? null : shopStock);
    }

    static ChestShopShopRecord shop(Sign sign, ItemStack item, Owner owner, Integer currentStock) {
        Location l = sign.getLocation();
        String priceLine = sign.getLine(ChestShopSign.PRICE_LINE);
        int batch;
        try {
            batch = ChestShopSign.getQuantity(sign);
        } catch (RuntimeException e) {
            batch = Math.max(1, item.getAmount());
        }
        return new ChestShopShopRecord(
                worldName(l), l.getBlockX(), l.getBlockY(), l.getBlockZ(), owner.admin(),
                owner.accountId(), owner.type(), owner.firmId(), owner.ownerUuid(),
                item.getType().name(), itemKey(item), MaterialUtil.getName(item), isCustom(item), itemData(item),
                nonNegativeOrNull(PriceUtil.getExactBuyPrice(priceLine)),
                nonNegativeOrNull(PriceUtil.getExactSellPrice(priceLine)),
                batch, owner.admin() ? null : currentStock);
    }

    static int totalAmount(ItemStack[] stock) {
        return java.util.Arrays.stream(stock).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum();
    }

    private static String worldName(Location l) {
        return l.getWorld() != null ? l.getWorld().getName() : null;
    }
}
