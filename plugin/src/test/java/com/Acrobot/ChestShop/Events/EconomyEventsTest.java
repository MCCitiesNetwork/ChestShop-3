package com.Acrobot.ChestShop.Events;

import com.Acrobot.ChestShop.Events.Economy.CurrencyAmountEvent;
import com.Acrobot.ChestShop.Events.Economy.CurrencyHoldEvent;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * Tests the family of currency-event POJOs. Each event is essentially a
 * data carrier with getters/setters; we verify constructor wiring and
 * round-trip behaviour through the deprecated double overloads.
 */
@ExtendWith(MockitoExtension.class)
class EconomyEventsTest {

    @Mock private World world;
    @Mock private Player player;

    // ── CurrencyAmountEvent ───────────────────────────────────────────────────

    @Test
    void currencyAmountEvent_storesAccountAndWorldFromUuidConstructor() {
        UUID account = UUID.randomUUID();
        CurrencyAmountEvent ev = new CurrencyAmountEvent(account, world);

        assertThat(ev.getAccount()).isEqualTo(account);
        assertThat(ev.getWorld()).isSameAs(world);
        assertThat(ev.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void currencyAmountEvent_extractsAccountFromPlayer() {
        UUID id = UUID.randomUUID();
        lenient().when(player.getUniqueId()).thenReturn(id);
        lenient().when(player.getWorld()).thenReturn(world);

        CurrencyAmountEvent ev = new CurrencyAmountEvent(player);

        assertThat(ev.getAccount()).isEqualTo(id);
        assertThat(ev.getWorld()).isSameAs(world);
    }

    @Test
    @SuppressWarnings("deprecation")
    void currencyAmountEvent_setAmount_doubleOverload_storesAsBigDecimal() {
        CurrencyAmountEvent ev = new CurrencyAmountEvent(UUID.randomUUID(), world);
        ev.setAmount(12.34);

        assertThat(ev.getAmount()).isEqualByComparingTo(new BigDecimal("12.34"));
        assertThat(ev.getDoubleAmount()).isEqualTo(12.34);
    }

    @Test
    void currencyAmountEvent_setAmount_bigDecimalOverload_isStoredVerbatim() {
        CurrencyAmountEvent ev = new CurrencyAmountEvent(UUID.randomUUID(), world);
        BigDecimal target = new BigDecimal("999.99");
        ev.setAmount(target);

        assertThat(ev.getAmount()).isSameAs(target);
    }

    @Test
    void currencyAmountEvent_setAccount_overwritesAccount() {
        CurrencyAmountEvent ev = new CurrencyAmountEvent(UUID.randomUUID(), world);
        UUID newAccount = UUID.randomUUID();
        ev.setAccount(newAccount);
        assertThat(ev.getAccount()).isEqualTo(newAccount);
    }

    @Test
    void currencyAmountEvent_handlerListIsStaticAndStable() {
        // Both the instance and static accessors should refer to the same list.
        CurrencyAmountEvent ev = new CurrencyAmountEvent(UUID.randomUUID(), world);
        assertThat(ev.getHandlers()).isSameAs(CurrencyAmountEvent.getHandlerList());
    }

    // ── CurrencyHoldEvent ─────────────────────────────────────────────────────

    @Test
    void currencyHoldEvent_canHoldDefaultsTrue() {
        CurrencyHoldEvent ev = new CurrencyHoldEvent(BigDecimal.TEN, UUID.randomUUID(), world);
        assertThat(ev.canHold()).isTrue();
    }

    @Test
    void currencyHoldEvent_canHoldSetterFlipsState() {
        CurrencyHoldEvent ev = new CurrencyHoldEvent(BigDecimal.TEN, UUID.randomUUID(), world);
        ev.canHold(false);
        assertThat(ev.canHold()).isFalse();
        ev.canHold(true);
        assertThat(ev.canHold()).isTrue();
    }

    @Test
    void currencyHoldEvent_storesAmountFromConstructor() {
        BigDecimal amt = new BigDecimal("123.45");
        CurrencyHoldEvent ev = new CurrencyHoldEvent(amt, UUID.randomUUID(), world);
        assertThat(ev.getAmount()).isEqualByComparingTo(amt);
    }

}
