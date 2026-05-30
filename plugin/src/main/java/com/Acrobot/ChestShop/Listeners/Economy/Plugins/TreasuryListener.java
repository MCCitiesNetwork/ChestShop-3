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
import com.Acrobot.ChestShop.Events.TransactionEvent;
import com.Acrobot.ChestShop.Listeners.Economy.EconomyAdapter;
import com.Acrobot.ChestShop.Listeners.Economy.TaxModule;
import com.Acrobot.ChestShop.Permission;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import com.Acrobot.ChestShop.UUIDs.NameManager;
import net.democracycraft.business.api.BusinessApi;
import net.democracycraft.business.model.RolePermission;
import net.democracycraft.treasury.api.TaxApi;
import net.democracycraft.treasury.model.economy.AccountType;
import net.democracycraft.treasury.model.economy.TransferRequest;
import net.democracycraft.treasury.model.tax.TaxResult;
import net.democracycraft.treasury.api.TreasuryApi;
import net.democracycraft.treasury.utils.Idempotency;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Treasury economy adapter for ChestShop.
 * Supports both personal accounts (via player UUID) and business accounts (via synthetic UUIDs).
 */
public class TreasuryListener extends EconomyAdapter {

    static final long BUSINESS_UUID_MSB = 0xC5B0000000000000L;
    static final UUID CHESTSHOP_SYSTEM_UUID = new UUID(0xC5B0FFFFFFFFFFFEL, 0xFFFFFFFFFFFFFFFEL);

    private final TreasuryApi treasury;
    private final TaxApi taxApi;
    private final int systemAccountId;
    @Nullable private final BusinessApi businessApi;

    private TreasuryListener(TreasuryApi treasury, TaxApi taxApi, int systemAccountId, @Nullable BusinessApi businessApi) {
        this.treasury = treasury;
        this.taxApi = taxApi;
        this.systemAccountId = systemAccountId;
        this.businessApi = businessApi;
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
                    treasury.getAccountsByTypeAndOwner(AccountType.SYSTEM, CHESTSHOP_SYSTEM_UUID);
            if (systemAccounts != null && !systemAccounts.isEmpty()) {
                systemAccountId = systemAccounts.get(0).getAccountId();
            } else {
                net.democracycraft.treasury.model.economy.Account systemAccount =
                        treasury.createAccount(AccountType.SYSTEM, CHESTSHOP_SYSTEM_UUID, "ChestShop System");
                systemAccount.setAllowOverdraft(true);
                treasury.updateAccount(systemAccount);
                systemAccountId = systemAccount.getAccountId();
            }
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.SEVERE, "Failed to initialize Treasury SYSTEM account!", e);
            return null;
        }

        ChestShop.getBukkitLogger().info("Treasury SYSTEM account initialized (ID: " + systemAccountId + ")");

        // Resolve TaxApi for sales-tax routing into Treasury's default tax
        // account (typically DCGovernment). Treasury exposes it as a separate
        // service so we don't need a second Bukkit lookup.
        TaxApi taxApi = treasury.getTaxApi();
        if (taxApi == null) {
            ChestShop.getBukkitLogger().warning(
                    "Treasury loaded but TaxApi unavailable — ChestShop sales tax will not be collected.");
        }

        // The legacy TaxModule mutates CurrencyTransferEvent amounts and
        // fires its own CurrencyAddEvent into the configured server economy
        // account. With Treasury wired in we route tax through TaxApi
        // instead — debiting the seller and crediting the configured tax
        // account. Disable the legacy path so it doesn't double-tax.
        TaxModule.setHandledByTreasury(true);
        ChestShop.getBukkitLogger().info("Sales tax now routed via Treasury TaxApi → "
                + (taxApi != null ? taxApi.getDefaultTaxAccountName() : "(disabled)"));

        // Optionally integrate with the Business plugin for CHESTSHOP permission checks
        BusinessApi businessApi = null;
        if (Bukkit.getPluginManager().getPlugin("Business") != null) {
            RegisteredServiceProvider<BusinessApi> businessRsp =
                    Bukkit.getServicesManager().getRegistration(BusinessApi.class);
            if (businessRsp != null) {
                businessApi = businessRsp.getProvider();
                ChestShop.getBukkitLogger().info("Business API integrated: firm CHESTSHOP permissions will gate shop access.");
            } else {
                ChestShop.getBukkitLogger().warning("Business plugin found but BusinessApi service not registered — falling back to Treasury membership checks.");
            }
        }

        return new TreasuryListener(treasury, taxApi, systemAccountId, businessApi);
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
     * Otherwise, prefer a GOVERNMENT account owned by that UUID before falling
     * back to resolving (or creating) a personal account for the player UUID.
     */
    private int resolveAccountId(UUID uuid) {
        if (isBusinessUuid(uuid)) {
            return (int) uuid.getLeastSignificantBits();
        }
        Integer governmentAccountId = resolveGovernmentAccountId(uuid);
        if (governmentAccountId != null) {
            return governmentAccountId;
        }
        net.democracycraft.treasury.model.economy.Account account = treasury.resolveOrCreatePersonal(uuid);
        return account.getAccountId();
    }

    /**
     * Legacy DemocracyCraft government ledgers (DCGovernment, SCGovernment, ...) used to
     * be real players, so shops still address them by that player's name/UUID. Those
     * ledgers are now GOVERNMENT accounts whose {@code owner_uuid_bin} has been set to the
     * legacy player UUID, so they must take precedence over personal resolution. Otherwise
     * {@link TreasuryApi#resolveOrCreatePersonal(UUID)} — which filters to PERSONAL type —
     * would mint an empty personal account and shop funds would never reach the ledger.
     *
     * @return the GOVERNMENT account id owned by {@code uuid}, or {@code null} if none exists.
     */
    private Integer resolveGovernmentAccountId(UUID uuid) {
        try {
            List<net.democracycraft.treasury.model.economy.Account> governmentAccounts =
                    treasury.getAccountsByTypeAndOwner(AccountType.GOVERNMENT, uuid);
            if (governmentAccounts != null && !governmentAccounts.isEmpty()) {
                return governmentAccounts.get(0).getAccountId();
            }
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING,
                    "Treasury: government account lookup failed for " + uuid, e);
        }
        return null;
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
                Integer governmentAccountId = resolveGovernmentAccountId(event.getAccount());
                balance = governmentAccountId != null
                        ? treasury.getBalanceByAccountId(governmentAccountId)
                        : treasury.getBalanceByOwnerUuid(event.getAccount());
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
                Integer governmentAccountId = resolveGovernmentAccountId(event.getAccount());
                event.hasAccount(governmentAccountId != null
                        ? treasury.hasAccountByAccountId(governmentAccountId)
                        : treasury.hasAccountByOwnerUuid(event.getAccount()));
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
        if (event.wasHandled() || event.getTransactionEvent() == null || event.getTransactionEvent().isCancelled()) {
            return;
        }

        String message = buildTransferMessage(event.getTransactionEvent());
        BigDecimal amountSent = event.getAmountSent();
        BigDecimal amountReceived = event.getAmountReceived();
        boolean senderIsAdmin = NameManager.isAdminShop(event.getSender());
        boolean receiverIsAdmin = NameManager.isAdminShop(event.getReceiver());

        // Subtract from sender (unless admin shop)
        if (!senderIsAdmin) {
            try {
                int senderAccountId = resolveAccountId(event.getSender());
                UUID initiator = isBusinessUuid(event.getSender()) ? CHESTSHOP_SYSTEM_UUID : event.getSender();
                byte[] dedupKey = Idempotency.sha256(
                        "chestshop:transfer:sub:" + event.getSender() + ":" + amountSent + ":" + System.nanoTime()
                );
                TransferRequest request = new TransferRequest(
                        senderAccountId, systemAccountId, amountSent,
                        message, initiator, null, "ChestShop", dedupKey
                );
                treasury.transfer(request);
            } catch (Exception e) {
                ChestShop.getBukkitLogger().log(Level.WARNING,
                        "Treasury: Could not subtract " + amountSent + " from " + event.getSender(), e);
                return;
            }
        }

        // Add to receiver (unless admin shop)
        int receiverAccountId = -1;
        if (!receiverIsAdmin) {
            try {
                receiverAccountId = resolveAccountId(event.getReceiver());
                byte[] dedupKey = Idempotency.sha256(
                        "chestshop:transfer:add:" + event.getReceiver() + ":" + amountReceived + ":" + System.nanoTime()
                );
                TransferRequest request = new TransferRequest(
                        systemAccountId, receiverAccountId, amountReceived,
                        message, CHESTSHOP_SYSTEM_UUID, null, "ChestShop", dedupKey
                );
                treasury.transfer(request);
            } catch (Exception e) {
                ChestShop.getBukkitLogger().log(Level.WARNING,
                        "Treasury: Could not add " + amountReceived + " to " + event.getReceiver(), e);
                // Rollback the sender's subtraction
                if (!senderIsAdmin) {
                    try {
                        int senderAccountId = resolveAccountId(event.getSender());
                        byte[] dedupKey = Idempotency.sha256(
                                "chestshop:rollback:" + event.getSender() + ":" + amountSent + ":" + System.nanoTime()
                        );
                        TransferRequest rollback = new TransferRequest(
                                systemAccountId, senderAccountId, amountSent,
                                "ChestShop rollback", CHESTSHOP_SYSTEM_UUID, null, "ChestShop", dedupKey
                        );
                        treasury.transfer(rollback);
                    } catch (Exception rollbackEx) {
                        ChestShop.getBukkitLogger().log(Level.SEVERE,
                                "Treasury: CRITICAL - Failed to rollback " + amountSent + " to " + event.getSender(), rollbackEx);
                    }
                }
                return;
            }
        }

        // Sales tax. Computed against the receiver-side amount, debited from
        // the seller's account into Treasury's default tax account (typically
        // DCGovernment) as a separate ledger entry. Skipped when:
        //   - the receiver is an admin shop (no real account to debit)
        //   - the rate is 0 (config-disabled)
        //   - TaxApi was unavailable at startup
        //   - the buyer holds the ChestShop.notax.sell permission
        if (taxApi != null && !receiverIsAdmin && receiverAccountId > 0) {
            BigDecimal rate = resolveTaxRate(event.getPartner());
            Player initiatorPlayer = event.getInitiator();
            if (rate.compareTo(BigDecimal.ZERO) > 0
                    && (initiatorPlayer == null || !Permission.has(initiatorPlayer, Permission.NO_BUY_TAX))) {
                try {
                    UUID initiatorUuid = isBusinessUuid(event.getReceiver())
                            ? CHESTSHOP_SYSTEM_UUID : event.getReceiver();
                    byte[] dedupKey = Idempotency.sha256(
                            "chestshop:tax:" + event.getReceiver() + ":" + amountReceived
                                    + ":" + System.nanoTime()
                    );
                    TaxResult result = taxApi.collectRateTax(
                            receiverAccountId,
                            amountReceived,
                            rate,
                            "chestshop-sales-tax",
                            "ChestShop sales tax (" + rate.movePointRight(2).stripTrailingZeros().toPlainString()
                                    + "% of " + amountReceived + ") — " + message,
                            initiatorUuid,
                            "ChestShop",
                            dedupKey);
                    if (result instanceof TaxResult.Failed f) {
                        ChestShop.getBukkitLogger().warning(
                                "Treasury: sales-tax collection failed for accountId=" + receiverAccountId
                                        + ": " + f.errorMessage());
                    }
                } catch (Exception e) {
                    // Tax collection is best-effort — log and continue. The
                    // primary transfer has already committed.
                    ChestShop.getBukkitLogger().log(Level.WARNING,
                            "Treasury: sales-tax collection threw for receiver " + event.getReceiver(), e);
                }
            }
        }

        event.setHandled(true);
    }

    /**
     * Tax rate as a decimal fraction (e.g. {@code 0.05} for 5%). Mirrors the
     * legacy TaxModule split: {@code SERVER_TAX_AMOUNT} for admin / server
     * counterparties, {@code TAX_AMOUNT} for everyone else.
     */
    private static BigDecimal resolveTaxRate(@Nullable UUID partner) {
        double pct = (partner != null
                && (NameManager.isAdminShop(partner) || NameManager.isServerEconomyAccount(partner)))
                ? Properties.SERVER_TAX_AMOUNT
                : Properties.TAX_AMOUNT;
        if (pct == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(pct)
                .divide(BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP);
    }

    private static final int MAX_MESSAGE_LENGTH = 250;

    private static String buildTransferMessage(TransactionEvent txn) {
        int totalItems = Arrays.stream(txn.getStock()).mapToInt(ItemStack::getAmount).sum();
        String itemName = ChestShopSign.getItem(txn.getSign());
        String ownerName = txn.getOwnerAccount().getName();
        String clientName = txn.getClient().getName();
        boolean isBuy = txn.getTransactionType() == TransactionEvent.TransactionType.BUY;

        // "{client} bought x{qty} {item} from {owner}" or "{client} sold x{qty} {item} to {owner}"
        String prefix = clientName + (isBuy ? " bought x" : " sold x") + totalItems + " ";
        String suffix = (isBuy ? " from " : " to ") + ownerName;
        int available = MAX_MESSAGE_LENGTH - prefix.length() - suffix.length();

        if (available < 1) {
            return (prefix + suffix).substring(0, MAX_MESSAGE_LENGTH);
        }
        if (itemName.length() > available) {
            itemName = itemName.substring(0, available);
        }
        return prefix + itemName + suffix;
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
        // A business token is anything starting with "B:" — the native, uppercase form
        // written by ChestShopSign.businessAccountSignName, or the legacy lowercase "b:"
        // form written by the old PlayerBusinesses/PlayerTreasury chestshops. We accept
        // both prefixes (and any suffix, incl. firm names with spaces) here; the suffix is
        // disambiguated below. Player names can never contain ':' so this never collides.
        if (name == null || name.length() < 3 || !name.regionMatches(true, 0, "B:", 0, 2)) {
            return;
        }

        try {
            String token = name.substring(2);
            int accountId = -1;
            net.democracycraft.treasury.model.economy.Account treasuryAccount = null;

            // Native form: the suffix is a base-36 Treasury account id (e.g. B:1A).
            try {
                accountId = Integer.parseInt(token, 36);
                treasuryAccount = treasury.getAccountById(accountId);
            } catch (NumberFormatException notBase36) {
                // e.g. a legacy firm name containing spaces — fall through to the name lookup.
            }

            // Legacy migration form: the suffix is an old PlayerBusinesses firm *name*
            // (e.g. b:My Shop). Resolve it to the firm's default BUSINESS account so the
            // shop keeps working; the physical sign is rewritten to the native form on
            // first use (see onTransactionMigrateSign). This is also the fallback when a
            // firm name happens to be valid base-36 but doesn't decode to a real account.
            if (treasuryAccount == null && businessApi != null) {
                net.democracycraft.business.model.Firm firm = businessApi.firms().getFirm(token);
                if (firm != null && firm.getDefaultAccountId() != null) {
                    accountId = firm.getDefaultAccountId();
                    treasuryAccount = treasury.getAccountById(accountId);
                }
            }

            if (treasuryAccount != null) {
                String displayName = treasuryAccount.getDisplayName();
                String shortName = ChestShopSign.businessAccountSignName(accountId);
                UUID syntheticUuid = toBusinessUuid(accountId);
                Account csAccount = new Account(displayName, shortName, syntheticUuid);
                event.setAccount(csAccount);
            }
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING, "Treasury: Could not resolve business account for " + name, e);
        }
    }

    /**
     * Lazily migrates legacy business shop signs to the native account-id format.
     *
     * <p>The old PlayerBusinesses chestshops addressed a firm by name
     * ({@code b:<FirmName>}); the native format is {@code B:<base36 account id>}
     * ({@link ChestShopSign#businessAccountSignName(int)}). By the time a shop
     * trades, {@link #onAccountQuery} has already resolved the owner account, and
     * its short name is the canonical native token. So if the physical sign still
     * shows the legacy text, we rewrite the owner line in place. This runs only on
     * a completed (non-cancelled) transaction and is a no-op for shops already in
     * the native form. Firms whose names were altered during the data migration
     * (stripped special characters) won't resolve and are intentionally left for
     * their owners to recreate.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransactionMigrateSign(TransactionEvent event) {
        Sign sign = event.getSign();
        Account owner = event.getOwnerAccount();
        if (sign == null || owner == null || owner.getUuid() == null || !isBusinessUuid(owner.getUuid())) {
            return;
        }

        String canonical = owner.getShortName();
        if (canonical == null || canonical.equals(ChestShopSign.getOwner(sign))) {
            return;
        }

        sign.setLine(ChestShopSign.NAME_LINE, canonical);
        sign.update(true);
        ChestShop.getBukkitLogger().info("Migrated legacy business shop sign to " + canonical
                + " at " + sign.getLocation());
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
            UUID playerUuid = event.getPlayer().getUniqueId();

            if (businessApi != null) {
                // Business plugin is present: use the CHESTSHOP role-permission as the
                // authoritative gate for both shop creation and shop ownership checks.
                if (businessApi.staff().hasPermissionForAccount(accountId, playerUuid, RolePermission.CHESTSHOP)) {
                    event.setAccess(true);
                }
            } else {
                // Business plugin absent: fall back to Treasury account membership.
                boolean canAccess = treasury.isAccountMember(playerUuid, accountId)
                        || treasury.isOwnerForAccountId(playerUuid, accountId);
                if (canAccess) {
                    event.setAccess(true);
                }
            }
        } catch (Exception e) {
            ChestShop.getBukkitLogger().log(Level.WARNING, "Treasury: Could not check access for " + event.getPlayer().getName() + " on account " + shortName, e);
        }
    }
}
