Treasury Integration Plan for ChestShop

Context

ChestShop currently integrates with Vault and Reserve for economy via UUID-based events. The goal is to add Treasury API as the highest-priority economy provider, enabling business account shops
(identified by Treasury account IDs) alongside personal accounts. Treasury is a softdepend — Vault/Reserve remain as fallbacks.

 ---
Design Decisions

1. Synthetic UUIDs for business accounts

The economy events (CurrencyCheckEvent, CurrencyTransferEvent, etc.) carry UUIDs, not account IDs. Rather than modifying all event classes, business Treasury accounts use synthetic UUIDs:

static final long BUSINESS_UUID_MSB = 0xC5B0000000000000L;
// Create: new UUID(BUSINESS_UUID_MSB, (long) treasuryAccountId)
// Detect: uuid.getMostSignificantBits() == BUSINESS_UUID_MSB
// Extract: (int) uuid.getLeastSignificantBits()

The MSB 0xC... has an invalid UUID version nibble, guaranteeing no collision with real player UUIDs. This means zero changes to economy event classes, AmountAndPriceChecker, EconomicModule,
TaxModule, or ServerAccountCorrector.

2. SYSTEM intermediary account for add/subtract events

Treasury requires double-entry transfers (from → to). The existing CurrencyAddEvent and CurrencySubtractEvent are single-sided. We bridge this with a ChestShop SYSTEM account (allowOverdraft=true):

- CurrencySubtractEvent(amount, target) → treasury.transfer(target → SYSTEM, amount)
- CurrencyAddEvent(amount, target) → treasury.transfer(SYSTEM → target, amount)
- CurrencyTransferEvent → uses processTransfer() (subtract then add), which flows through the above

The SYSTEM account temporarily goes negative during tax processing (TaxModule's CurrencyAddEvent fires before processTransfer's subtract), but allowOverdraft=true handles this. After each complete
transaction, SYSTEM nets to $0.

3. Sign format for business accounts

Name line (line 0): B:<base36_account_id> (case-insensitive)
- 2-char prefix + up to 13 chars of base-36 = supports int account IDs
- Minecraft usernames cannot contain :, so no ambiguity
- Example: Account #46 → B:1A

 ---
Files to Create

plugin/src/main/java/com/Acrobot/ChestShop/Listeners/Economy/Plugins/TreasuryListener.java

Extends EconomyAdapter. Single self-contained class handling all Treasury integration.

Fields:
- TreasuryApi treasury — the API instance
- int systemAccountId — ChestShop SYSTEM account for intermediary transfers
- static final long BUSINESS_UUID_MSB — synthetic UUID constant
- static final UUID CHESTSHOP_SYSTEM_UUID — deterministic UUID for system account ownership

Static setup — prepareListener():
1. Get TreasuryApi from Bukkit.getServicesManager().getRegistration(TreasuryApi.class)
2. Find or create SYSTEM account: treasury.getAccountsByTypeAndOwner("SYSTEM", CHESTSHOP_SYSTEM_UUID) → if empty, treasury.createAccount("SYSTEM", CHESTSHOP_SYSTEM_UUID, "ChestShop System") with
   allowOverdraft=true
3. Return new TreasuryListener or null if Treasury not found

Helper methods:
- isBusinessUuid(UUID) — checks MSB == BUSINESS_UUID_MSB
- toBusinessUuid(int accountId) — creates synthetic UUID
- resolveAccountId(UUID uuid) — if business UUID, extract accountId; else treasury.resolveOrCreatePersonal(uuid).getAccountId()

Economy event handlers (8 required by EconomyAdapter):
┌───────────────────────┬──────────────────────────────────────────────────────────────────────────────────────────────────────┐
│        Handler        │                                                Logic                                                 │
├───────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ onAmountCheck         │ Detect synthetic → getBalanceByAccountId(id), else getBalanceByOwnerUuid(uuid)                       │
├───────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ onCurrencyCheck       │ Detect synthetic → hasFunds(id, amount), else hasFunds(resolveOrCreate(uuid).getAccountId(), amount) │
├───────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ onAccountCheck        │ Detect synthetic → hasAccountByAccountId(id), else hasAccountByOwnerUuid(uuid)                       │
├───────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ onCurrencyFormat      │ treasury.formatAmount(amount)                                                                        │
├───────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ onCurrencyAdd         │ treasury.transfer(systemAccountId → resolveAccountId(target), amount)                                │
├───────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ onCurrencySubtraction │ treasury.transfer(resolveAccountId(target) → systemAccountId, amount)                                │
├───────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ onCurrencyTransfer    │ Call processTransfer(event) — inherited from EconomyAdapter, triggers subtract+add above             │
├───────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ onCurrencyHoldCheck   │ Set canHold(true) and handled (Treasury manages overdraft internally)                                │
└───────────────────────┴──────────────────────────────────────────────────────────────────────────────────────────────────────┘
For onCurrencyAdd/onCurrencySubtraction: Build a TransferRequest with pluginSystem="ChestShop", initiator=CHESTSHOP_SYSTEM_UUID, and a SHA-256 dedup key.

For admin shops: processTransfer() already skips subtract/add for admin shop UUIDs (checks NameManager.isAdminShop()), so no special handling needed.

AccountQueryEvent handler (priority LOW):
- Runs before NameManager's default handler
- If name matches (?i)^B:[0-9A-Z]+$:
    - Parse accountId from base-36
    - Call treasury.getAccountById(accountId)
    - If found: create transient com.Acrobot.ChestShop.Database.Account(displayName, "B:" + base36upper, syntheticUuid)
    - Set on event

AccountAccessEvent handler:
- If event.getAccount().getShortName() starts with B: (case-insensitive):
    - Extract Treasury accountId from the synthetic UUID
    - Call treasury.canAccessAccount(player.getUniqueId(), accountId)
    - If true: event.setAccess(true)
- This enables business account members/authorizers to:
    - Create shops for the account (via NameChecker → canUseName → AccountAccessEvent)
    - Open shop chests (via ChestShopSign.canAccess → hasPermission → canUseName → AccountAccessEvent)

 ---
Files to Modify

plugin/pom.xml

Add after the Reserve dependency:
<dependency>
<groupId>net.democracycraft</groupId>
<artifactId>treasury</artifactId>
<version>1.0.0</version>
<scope>provided</scope>
</dependency>

plugin/src/main/resources/plugin.yml

Add Treasury to softdepend list (before Vault):
softdepend: [Treasury, Vault, Reserve, ...]

plugin/src/main/java/com/Acrobot/ChestShop/Dependencies.java

In loadEconomy() (line 98-124): Add Treasury check first (before Reserve):
if (Bukkit.getPluginManager().getPlugin("Treasury") != null) {
plugin = "Treasury";
economy = TreasuryListener.prepareListener();
}
if (economy == null && Bukkit.getPluginManager().getPlugin("Reserve") != null) {
// existing Reserve code
}
if (economy == null && Bukkit.getPluginManager().getPlugin("Vault") != null) {
// existing Vault code
}
Note: The existing code has Vault overwriting Reserve. We fix this so each is only tried if economy is still null.

plugin/src/main/java/com/Acrobot/ChestShop/Signs/ChestShopSign.java

Add static helper methods:
private static final Pattern BUSINESS_PATTERN = Pattern.compile("(?i)^B:[0-9A-Z]+$");

public static boolean isBusinessAccount(String owner) {
return BUSINESS_PATTERN.matcher(owner).matches();
}

public static boolean isBusinessAccount(String[] lines) {
return isBusinessAccount(getOwner(lines));
}

public static int getBusinessAccountId(String owner) {
return Integer.parseInt(owner.substring(2), 36);
}

public static String businessAccountSignName(int accountId) {
return "B:" + Integer.toString(accountId, 36).toUpperCase(Locale.ROOT);
}

plugin/src/main/java/com/Acrobot/ChestShop/Listeners/PreShopCreation/NameChecker.java

In handleEvent(), add early check for business account names. Before the existing if (account == null || ...) block:
- If name matches B: pattern and Treasury plugin is not loaded → set event.setSignLine(NAME_LINE, "") and event.setOutcome(UNKNOWN_PLAYER) with a warning message, then return
- If Treasury IS loaded, the existing flow works naturally: canUseName() fires AccountQueryEvent (handled by TreasuryListener) → AccountAccessEvent (handled by TreasuryListener)
- After account resolution for business accounts, normalize sign line to uppercase: event.setSignLine(NAME_LINE, ChestShopSign.businessAccountSignName(accountId))

plugin/src/main/java/com/Acrobot/ChestShop/Configuration/Messages.java

Add message constants (only if they don't exist):
- BUSINESS_ACCOUNT_NOT_FOUND — for when B: account ID doesn't resolve
- TREASURY_REQUIRED — for when B: sign is used without Treasury plugin

 ---
Flow Traces

Personal shop buy ($100, 5% tax):

1. EconomicModule → CurrencyTransferEvent(amountSent=$100, amountReceived=$100, buyer→seller)
2. ServerAccountCorrector (LOWEST) — no-op for regular shops
3. TaxModule (LOW):
- Sets amountReceived=$95
- Fires CurrencyAddEvent($5, serverUUID) → TreasuryListener: transfer(SYSTEM → server, $5) → SYSTEM: -$5
4. TreasuryListener.processTransfer() (NORMAL):
- Fires CurrencySubtractEvent($100, buyer) → transfer(buyer → SYSTEM, $100) → SYSTEM: +$95
- Fires CurrencyAddEvent($95, seller) → transfer(SYSTEM → seller, $95) → SYSTEM: $0
5. Result: buyer -$100, seller +$95, server +$5, SYSTEM ±$0 ✓

Business shop buy (B:1A = account #46):

1. PlayerInteract → AccountQueryEvent("B:1A") → TreasuryListener resolves to Account(syntheticUUID(46))
2. AmountAndPriceChecker → CurrencyCheckEvent(syntheticUUID) → TreasuryListener: hasFunds(46, amount) ✓
3. EconomicModule → CurrencyTransferEvent(buyer, syntheticUUID, PARTNER)
4. TreasuryListener.processTransfer():
- CurrencySubtractEvent(buyer) → transfer(buyerPersonal → SYSTEM)
- CurrencyAddEvent(syntheticUUID) → transfer(SYSTEM → businessAccount#46)
5. Result: buyer pays from personal, business account #46 receives ✓

Business member opens chest:

1. Click sign → ChestShopSign.canAccess() → hasPermission() → canUseName()
2. canUseName() → AccountQueryEvent("B:1A") → TreasuryListener resolves
3. canUseName() → AccountAccessEvent(player, businessAccount) → TreasuryListener: canAccessAccount(playerUuid, 46) → true
4. notAllowedToTrade=true → showChestGUI() ✓

 ---
Verification

1. Build: mvn clean install — verifies compilation
2. Existing tests: mvn test — should pass unchanged (Treasury not loaded in tests, Vault/Reserve paths unaffected)
3. Manual testing on server with Treasury:
- Create personal shop → economy events handled by Treasury
- B:<id> sign creation → validated against Treasury, sign formatted
- Buy/sell at personal shop → transfer via SYSTEM intermediary
- Buy/sell at business shop → correct accounts debited/credited
- Business member/authorizer opens shop chest → access granted
- Non-member denied chest access
- Tax applies correctly with SYSTEM account balancing to zero
- Remove Treasury → fallback to Vault/Reserve