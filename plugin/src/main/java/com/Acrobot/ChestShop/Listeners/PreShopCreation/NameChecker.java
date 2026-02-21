package com.Acrobot.ChestShop.Listeners.PreShopCreation;

import com.Acrobot.ChestShop.ChestShop;
import com.Acrobot.ChestShop.Configuration.Messages;
import com.Acrobot.ChestShop.Database.Account;
import com.Acrobot.ChestShop.Events.AccountAccessEvent;
import com.Acrobot.ChestShop.Events.AccountQueryEvent;
import com.Acrobot.ChestShop.Events.PreShopCreationEvent;
import com.Acrobot.ChestShop.Permission;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import com.Acrobot.ChestShop.UUIDs.NameManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.logging.Level;

import static com.Acrobot.ChestShop.Permission.OTHER_NAME_CREATE;
import static com.Acrobot.ChestShop.Signs.ChestShopSign.NAME_LINE;
import static com.Acrobot.ChestShop.Events.PreShopCreationEvent.CreationOutcome.UNKNOWN_PLAYER;

/**
 * @author Acrobot
 */
public class NameChecker implements Listener {

    @EventHandler(priority = EventPriority.LOW)
    public static void onPreShopCreation(PreShopCreationEvent event) {
        handleEvent(event);
    }

    /**
     * ignoreCancelled = true prevents this second pass from overriding a rejection
     * that was already issued during the LOW-priority pass (e.g. no CHESTSHOP permission,
     * business account not found, Treasury not loaded).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public static void onPreShopCreationHighest(PreShopCreationEvent event) {
        handleEvent(event);
    }

    private static void handleEvent(PreShopCreationEvent event) {
        String name = ChestShopSign.getOwner(event.getSignLines());
        Player player = event.getPlayer();

        // Business accounts have a dedicated validation path that produces specific
        // error messages and enforces the CHESTSHOP firm permission.
        if (ChestShopSign.isBusinessAccount(name)) {
            handleBusinessAccount(event, player, name);
            return;
        }

        // --- Regular (player) account flow ---
        Account account = event.getOwnerAccount();
        if (account == null || !account.getShortName().equalsIgnoreCase(name)) {
            account = null;
            try {
                if (name.isEmpty() || !NameManager.canUseName(player, OTHER_NAME_CREATE, name)) {
                    account = NameManager.getOrCreateAccount(player);
                } else {
                    AccountQueryEvent accountQueryEvent = new AccountQueryEvent(name);
                    ChestShop.callEvent(accountQueryEvent);
                    account = accountQueryEvent.getAccount();
                    if (account == null) {
                        Player otherPlayer = ChestShop.getBukkitServer().getPlayer(name);
                        try {
                            if (otherPlayer != null) {
                                account = NameManager.getOrCreateAccount(otherPlayer);
                            } else {
                                account = NameManager.getOrCreateAccount(ChestShop.getBukkitServer().getOfflinePlayer(name));
                            }
                        } catch (IllegalArgumentException e) {
                            event.getPlayer().sendMessage(e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                ChestShop.getBukkitLogger().log(Level.SEVERE, "Error while trying to check account for name " + name + " with player " + player.getName(), e);
            }
        }
        event.setOwnerAccount(account);
        if (account != null) {
            event.setSignLine(NAME_LINE, account.getShortName());
        } else {
            event.setSignLine(NAME_LINE, "");
            event.setOutcome(UNKNOWN_PLAYER);
        }
    }

    /**
     * Validates and resolves a business account (B:&lt;base36&gt;) sign name.
     *
     * <p>Checks in order:
     * <ol>
     *   <li>Treasury plugin is available (required for business accounts).</li>
     *   <li>The Treasury account with the encoded ID actually exists.</li>
     *   <li>The creating player holds the {@code CHESTSHOP} firm permission for that
     *       account (via {@link AccountAccessEvent} → TreasuryListener → Business API),
     *       unless they have the {@code ChestShop.admin} node.</li>
     * </ol>
     *
     * <p>A specific error message is sent for each failure case so the player knows
     * exactly why the shop could not be created.
     */
    private static void handleBusinessAccount(PreShopCreationEvent event, Player player, String name) {
        // Already correctly resolved in a previous handler pass — nothing to do.
        Account existing = event.getOwnerAccount();
        if (existing != null && existing.getShortName().equalsIgnoreCase(name)) {
            return;
        }

        if (Bukkit.getPluginManager().getPlugin("Treasury") == null) {
            Messages.TREASURY_REQUIRED.sendWithPrefix(player);
            event.setSignLine(NAME_LINE, "");
            event.setOutcome(UNKNOWN_PLAYER);
            return;
        }

        // Resolve the Treasury account for the encoded firm account ID.
        AccountQueryEvent queryEvent = new AccountQueryEvent(name);
        ChestShop.callEvent(queryEvent);
        Account account = queryEvent.getAccount();

        if (account == null) {
            Messages.BUSINESS_ACCOUNT_NOT_FOUND.sendWithPrefix(player);
            event.setSignLine(NAME_LINE, "");
            event.setOutcome(UNKNOWN_PLAYER);
            return;
        }

        // ChestShop admins bypass firm-level permission checks.
        if (!Permission.has(player, Permission.ADMIN)) {
            // Fire AccountAccessEvent: TreasuryListener checks the CHESTSHOP firm
            // permission via the Business API (or falls back to Treasury membership).
            AccountAccessEvent accessEvent = new AccountAccessEvent(player, account);
            ChestShop.callEvent(accessEvent);
            if (!accessEvent.canAccess()) {
                Messages.BUSINESS_NO_CHESTSHOP_PERMISSION.sendWithPrefix(player);
                event.setSignLine(NAME_LINE, "");
                event.setOutcome(UNKNOWN_PLAYER);
                return;
            }
        }

        event.setOwnerAccount(account);
        int accountId = ChestShopSign.getBusinessAccountId(account.getShortName());
        event.setSignLine(NAME_LINE, ChestShopSign.businessAccountSignName(accountId));
    }
}
