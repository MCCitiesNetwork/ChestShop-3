package com.Acrobot.ChestShop.Listeners.Economy.Plugins;

import com.Acrobot.ChestShop.ChestShop;
import com.Acrobot.ChestShop.Configuration.Properties;
import com.Acrobot.ChestShop.Database.Account;
import com.Acrobot.ChestShop.Events.AccountAccessEvent;
import com.Acrobot.ChestShop.Events.AccountQueryEvent;
import com.Acrobot.ChestShop.Events.Economy.AccountCheckEvent;
import com.Acrobot.ChestShop.Events.Economy.CurrencyAddEvent;
import com.Acrobot.ChestShop.Events.Economy.CurrencyAmountEvent;
import com.Acrobot.ChestShop.Events.Economy.CurrencyCheckEvent;
import com.Acrobot.ChestShop.Events.Economy.CurrencyFormatEvent;
import com.Acrobot.ChestShop.Events.Economy.CurrencyHoldEvent;
import com.Acrobot.ChestShop.Events.Economy.CurrencySubtractEvent;
import com.Acrobot.ChestShop.Events.Economy.CurrencyTransferEvent;
import com.Acrobot.ChestShop.Listeners.Economy.EconomyAdapter;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import net.democracycraft.treasury.model.economy.TransferRequest;
import net.democracycraft.treasury.api.TreasuryApi;
import net.democracycraft.treasury.utils.Idempotency;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.RegisteredServiceProvider;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Treasury economy adapter for ChestShop.
 * Supports both personal accounts (via player UUID) and business accounts (via synthetic UUIDs).
 */
public class TreasuryListener extends EconomyAdapter {

    static final long BUSINESS_UUID_MSB = 0xC5B0000000000000L;
    static final UUID CHESTSHOP_SYSTEM_UUID = new UUID(0xC5B0FFFFFFFFFFFEL, 0xFFFFFFFFFFFFFFFEL);

    private static final Pattern BUSINESS_NAME_PATTERN = Pattern.compile("(?i)^B:[0-9A-Z]+$");

    private final TreasuryApi treasury;
    private final int systemAccountId;

    private TreasuryListener(TreasuryApi treasury, int systemAccountId) {
        this.treasury = treasury;
        this.systemAccountId = systemAccountId;
    }

    /**
     * Attempt to initialize the Treasury listener.
     *
     * @return A new TreasuryListener, or null if Treasury is not available
     */
    @Nullable
    public static TreasuryListener prepareListener() {
        if (Bukkit.getPluginManager().getPlugin("Treasury") == null) {
            return null;
        }

        RegisteredServiceProvider<TreasuryApi> rsp =
                Bukkit.getServicesManager().getRegistration(TreasuryApi.class);
        if (rsp == null) {
            ChestShop.getBukkitLogger().warning("Treasury plugin found but TreasuryApi service not registered!");
            return null;
        }

        TreasuryApi treasury = rsp.getProvider();

        // Find or create the ChestShop SYSTEM account for intermediary transfers
        int systemAccountId;
        try {
            List<net.democracycraft.treasury.model.economy.Account> systemAccounts =
                    treasury.getAccountsByTypeAndOwner("SYSTEM", CHESTSHOP_SYSTEM_UUID);
            if (systemAccounts != null && !systemAccounts.isEmpty()) {
                systemAccountId = systemAccounts.get(0).getAccountId();
            } else {
                net.democracycraft.treasury.model.economy.Account systemAccount =
                        treasury.createAccount("SYSTEM", CHESTSHOP_SYSTEM_UUID, "ChestShop System");
                systemAccount.setAllowOverdraft(true);
                treasury.updateAccount(systemAccount);
                systemAccountId = systemAccount.getAccountId();
            }
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.SEVERE, "Failed to initialize Treasury SYSTEM account!", e);
            return null;
        }

        ChestShop.getBukkitLogger().info("Treasury SYSTEM account initialized (ID: " + systemAccountId + ")");
        return new TreasuryListener(treasury, systemAccountId);
    }

    @Override
    @Nullable
    public ProviderInfo getProviderInfo() {
        return new ProviderInfo("Treasury", Bukkit.getPluginManager().getPlugin("Treasury").getDescription().getVersion());
    }

    // --- Synthetic UUID helpers ---

    static boolean isBusinessUuid(UUID uuid) {
        return uuid.getMostSignificantBits() == BUSINESS_UUID_MSB;
    }

    static UUID toBusinessUuid(int accountId) {
        return new UUID(BUSINESS_UUID_MSB, (long) accountId);
    }

    /**
     * Resolve a UUID to a Treasury account ID.
     * If the UUID is a synthetic business UUID, extract the account ID directly.
     * Otherwise, resolve or create a personal account for the player UUID.
     */
    private int resolveAccountId(UUID uuid) {
        if (isBusinessUuid(uuid)) {
            return (int) uuid.getLeastSignificantBits();
        }
        net.democracycraft.treasury.model.economy.Account account = treasury.resolveOrCreatePersonal(uuid);
        return account.getAccountId();
    }

    // --- Economy event handlers ---

    @EventHandler
    public void onAmountCheck(CurrencyAmountEvent event) {
        if (event.wasHandled() || !event.getAmount().equals(BigDecimal.ZERO)) {
            return;
        }

        try {
            BigDecimal balance;
            if (isBusinessUuid(event.getAccount())) {
                int accountId = (int) event.getAccount().getLeastSignificantBits();
                balance = treasury.getBalanceByAccountId(accountId);
            } else {
                balance = treasury.getBalanceByOwnerUuid(event.getAccount());
            }
            event.setAmount(balance);
            event.setHandled(true);
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING, "Treasury: Could not get balance for " + event.getAccount(), e);
        }
    }

    @EventHandler
    public void onCurrencyCheck(CurrencyCheckEvent event) {
        if (event.wasHandled() || event.hasEnough()) {
            return;
        }

        try {
            int accountId = resolveAccountId(event.getAccount());
            event.hasEnough(treasury.hasFunds(accountId, event.getAmount()));
            event.setHandled(true);
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING, "Treasury: Could not check funds for " + event.getAccount(), e);
        }
    }

    @EventHandler
    public void onAccountCheck(AccountCheckEvent event) {
        if (event.wasHandled() || event.hasAccount()) {
            return;
        }

        try {
            if (isBusinessUuid(event.getAccount())) {
                int accountId = (int) event.getAccount().getLeastSignificantBits();
                event.hasAccount(treasury.hasAccountByAccountId(accountId));
            } else {
                event.hasAccount(treasury.hasAccountByOwnerUuid(event.getAccount()));
            }
            event.setHandled(true);
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING, "Treasury: Could not check account for " + event.getAccount(), e);
        }
    }

    @EventHandler
    public void onCurrencyFormat(CurrencyFormatEvent event) {
        if (event.wasHandled() || !event.getFormattedAmount().isEmpty()) {
            return;
        }

        try {
            String formatted = treasury.formatAmount(event.getAmount());
            event.setFormattedAmount(Properties.STRIP_PRICE_COLORS ? ChatColor.stripColor(formatted) : formatted);
            event.setHandled(true);
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING, "Treasury: Could not format amount " + event.getAmount(), e);
        }
    }

    @EventHandler
    public void onCurrencyAdd(CurrencyAddEvent event) {
        if (event.wasHandled()) {
            return;
        }

        try {
            int targetAccountId = resolveAccountId(event.getTarget());
            byte[] dedupKey = Idempotency.sha256(
                    "chestshop:add:" + event.getTarget() + ":" + event.getAmount() + ":" + System.nanoTime()
            );

            TransferRequest request = new TransferRequest(
                    systemAccountId,
                    targetAccountId,
                    event.getAmount(),
                    "ChestShop deposit",
                    CHESTSHOP_SYSTEM_UUID,
                    null,
                    "ChestShop",
                    dedupKey
            );
            treasury.transfer(request);
            event.setHandled(true);
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING, "Treasury: Could not add " + event.getAmount() + " to " + event.getTarget(), e);
        }
    }

    @EventHandler
    public void onCurrencySubtraction(CurrencySubtractEvent event) {
        if (event.wasHandled()) {
            return;
        }

        try {
            int targetAccountId = resolveAccountId(event.getTarget());
            byte[] dedupKey = Idempotency.sha256(
                    "chestshop:sub:" + event.getTarget() + ":" + event.getAmount() + ":" + System.nanoTime()
            );

            // Use the target's UUID as the initiator for personal accounts,
            // since the account owner is the one authorizing the withdrawal.
            UUID initiator = isBusinessUuid(event.getTarget()) ? CHESTSHOP_SYSTEM_UUID : event.getTarget();

            TransferRequest request = new TransferRequest(
                    targetAccountId,
                    systemAccountId,
                    event.getAmount(),
                    "ChestShop withdrawal",
                    initiator,
                    null,
                    "ChestShop",
                    dedupKey
            );
            treasury.transfer(request);
            event.setHandled(true);
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING, "Treasury: Could not subtract " + event.getAmount() + " from " + event.getTarget(), e);
        }
    }

    @EventHandler
    public void onCurrencyTransfer(CurrencyTransferEvent event) {
        processTransfer(event);
    }

    @EventHandler
    public void onCurrencyHoldCheck(CurrencyHoldEvent event) {
        if (event.wasHandled() || event.getAccount() == null) {
            return;
        }

        // Treasury manages overdraft internally; accounts can always hold currency
        event.canHold(true);
        event.setHandled(true);
    }

    // --- Account query/access handlers for business accounts ---

    @EventHandler(priority = EventPriority.LOW)
    public void onAccountQuery(AccountQueryEvent event) {
        if (event.getAccount() != null) {
            return;
        }

        String name = event.getName();
        if (!BUSINESS_NAME_PATTERN.matcher(name).matches()) {
            return;
        }

        try {
            int accountId = Integer.parseInt(name.substring(2), 36);
            net.democracycraft.treasury.model.economy.Account treasuryAccount = treasury.getAccountById(accountId);
            if (treasuryAccount != null) {
                String displayName = treasuryAccount.getDisplayName();
                String shortName = ChestShopSign.businessAccountSignName(accountId);
                UUID syntheticUuid = toBusinessUuid(accountId);
                Account csAccount = new Account(displayName, shortName, syntheticUuid);
                event.setAccount(csAccount);
            }
        } catch (NumberFormatException e) {
            // Invalid base-36 number, ignore
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING, "Treasury: Could not resolve business account for " + name, e);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onAccountAccess(AccountAccessEvent event) {
        if (event.canAccess()) {
            return;
        }

        Account account = event.getAccount();
        if (account == null || account.getShortName() == null) {
            return;
        }

        String shortName = account.getShortName();
        if (!shortName.toUpperCase(Locale.ROOT).startsWith("B:")) {
            return;
        }

        UUID uuid = account.getUuid();
        if (!isBusinessUuid(uuid)) {
            return;
        }

        try {
            int accountId = (int) uuid.getLeastSignificantBits();
            boolean canAccess = treasury.canAccessAccount(event.getPlayer().getUniqueId(), accountId);
            if (canAccess) {
                event.setAccess(true);
            }
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING, "Treasury: Could not check access for " + event.getPlayer().getName() + " on account " + shortName, e);
        }
    }
}
