package com.storeql.payment;

import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.service.TerminalService;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The till's card rule (intent card-payments, slice 1). A card taken on a machine StoreQL drives is
 * recorded from its approval; a store with such a machine refuses a typed card unless the owner has
 * allowed a standalone one; and wherever a typed card is taken the machine's own receipt reference
 * is required, so every card sale can be found in the acquirer's file. The permission is the
 * owner's, per store, kept with who and when, and never reaches another business's store.
 */
@HelidonTest
class CardTenderRuleIT {

  private static final PostgresSupport PG;

  private static final UUID BIZ = Ids.newId();
  private static final UUID RIVAL = Ids.newId();
  private static final UUID WITH_MACHINE = Ids.newId();
  private static final UUID NO_MACHINE = Ids.newId();
  private static final UUID RETIRED_ONLY = Ids.newId();
  private static final UUID RIVAL_STORE = Ids.newId();
  // businesses of their own for the two tests that change a setting, so no test depends on another
  private static final UUID ALLOWER = Ids.newId();
  private static final UUID ALLOWER_STORE = Ids.newId();
  private static final UUID LOGGER = Ids.newId();
  private static final UUID LOGGER_STORE = Ids.newId();

  static {
    PG = PostgresSupport.start().wire("payment");
    TenantSvcStub.start()
        .with(BIZ.toString(), "GBP", "GB")
        .withStore(BIZ.toString(), WITH_MACHINE.toString(), "GB")
        .withStore(BIZ.toString(), NO_MACHINE.toString(), "GB")
        .withStore(BIZ.toString(), RETIRED_ONLY.toString(), "GB")
        .with(RIVAL.toString(), "GBP", "GB")
        .withStore(RIVAL.toString(), RIVAL_STORE.toString(), "GB")
        .with(ALLOWER.toString(), "GBP", "GB")
        .withStore(ALLOWER.toString(), ALLOWER_STORE.toString(), "GB")
        .with(LOGGER.toString(), "GBP", "GB")
        .withStore(LOGGER.toString(), LOGGER_STORE.toString(), "GB");
  }

  @Inject WebTarget target;
  @Inject TerminalService terminals;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  private final UUID actor = Ids.newId();

  private Caller cashier(UUID store) {
    return new Caller(BIZ, Ids.newId(), "CASHIER", store);
  }

  private Answer card(Caller who, UUID store, UUID order, String reference) {
    return ItCalls.call(
        target,
        "POST",
        "/payments",
        who,
        "{\"orderId\":\""
            + order
            + "\",\"amount\":10.00,\"method\":\"CARD\",\"storeId\":\""
            + store
            + "\""
            + (reference == null ? "" : ",\"reference\":\"" + reference + "\"")
            + "}",
        Ids.newId().toString());
  }

  private static String row(UUID order) {
    return scalar(
        PG,
        "SELECT coalesce(string_agg(coalesce(reference, '-') || '|' || coalesce(entry_mode, '-'), ','),"
            + " 'none') FROM payment.payment_tenders WHERE order_id = '"
            + order
            + "'");
  }

  private Answer allow(Caller who, UUID store, boolean allowed) {
    return ItCalls.call(
        target,
        "PUT",
        "/admin/payments/stores/" + store + "/standalone-card",
        who,
        "{\"allowed\":" + allowed + "}",
        null);
  }

  private void registerMachine(UUID store) {
    terminals.register(BIZ, store, "Till " + Ids.newId(), "SIMULATED", null, actor);
  }

  // ── a store with a machine ───────────────────────────────────────────────────

  @Test
  @DisplayName(
      "a store with a registered machine refuses a typed card, whatever reference it carries")
  void cardNeedsTerminalWhereOneIsRegistered() {
    registerMachine(WITH_MACHINE);
    UUID order = Ids.newId();

    Answer a = card(cashier(WITH_MACHINE), WITH_MACHINE, order, "AUTH 4821");

    assertThat(a.body().toString(), a.status(), is(409));
    assertThat(a.code(), is("PAYMENT_CARD_NEEDS_TERMINAL"));
    assertThat(row(order), is("none"));
  }

  @Test
  @DisplayName(
      "an owner can allow a standalone machine, then the reference is required and recorded")
  void standaloneNeedsReference() {
    UUID store = ALLOWER_STORE;
    UUID biz = ALLOWER;
    terminals.register(biz, store, "Till " + Ids.newId(), "SIMULATED", null, actor);
    Caller owner = Caller.owner(biz);
    Caller till = new Caller(biz, Ids.newId(), "CASHIER", store);
    UUID order = Ids.newId();

    assertThat(card(till, store, order, "AUTH 1").code(), is("PAYMENT_CARD_NEEDS_TERMINAL"));
    assertThat(allow(owner, store, true).status(), is(200));

    Answer noReference = card(till, store, order, null);
    assertThat(noReference.status(), is(400));
    assertThat(noReference.code(), is("PAYMENT_CARD_REFERENCE_REQUIRED"));
    assertThat(row(order), is("none"));

    Answer ok = card(till, store, order, "  AUTH 4821  ");
    assertThat(ok.body().toString(), ok.status(), is(201));
    assertThat(row(order), is("AUTH 4821|STANDALONE"));

    assertThat(allow(owner, store, false).status(), is(200));
    assertThat(card(till, store, Ids.newId(), "AUTH 2").code(), is("PAYMENT_CARD_NEEDS_TERMINAL"));
  }

  @Test
  @DisplayName(
      "a retired machine alone is no machine: the store takes a typed card with a reference")
  void retiredTerminalIsNoTerminal() {
    var t = terminals.register(BIZ, RETIRED_ONLY, "Till " + Ids.newId(), "SIMULATED", null, actor);
    Answer retired =
        ItCalls.post(
            target,
            "/admin/payments/terminals/" + t.id() + "/retire",
            Caller.owner(BIZ),
            "{\"reason\":\"cracked\"}");
    assertThat(retired.body().toString(), retired.status(), is(200));
    UUID order = Ids.newId();

    Answer a = card(cashier(RETIRED_ONLY), RETIRED_ONLY, order, "RRN 77");

    assertThat(a.body().toString(), a.status(), is(201));
    assertThat(row(order), is("RRN 77|STANDALONE"));
  }

  // ── a store with no machine StoreQL sees ─────────────────────────────────────

  @Test
  @DisplayName("a store with no machine at all needs the machine's reference on every typed card")
  void noMachineNeedsReference() {
    UUID order = Ids.newId();

    for (String bad : new String[] {null, "", "   "}) {
      Answer a = card(cashier(NO_MACHINE), NO_MACHINE, order, bad);
      assertThat(String.valueOf(bad), a.status(), is(400));
      assertThat(a.code(), is("PAYMENT_CARD_REFERENCE_REQUIRED"));
    }
    Answer tooLong = card(cashier(NO_MACHINE), NO_MACHINE, order, "x".repeat(65));
    assertThat(tooLong.status(), is(400));
    assertThat(tooLong.code(), is("PAYMENT_CARD_REFERENCE_INVALID"));
    assertThat(row(order), is("none"));

    Answer ok = card(cashier(NO_MACHINE), NO_MACHINE, order, "AUTH 99120");
    assertThat(ok.body().toString(), ok.status(), is(201));
    assertThat(row(order), is("AUTH 99120|STANDALONE"));
  }

  @Test
  @DisplayName("other tenders are untouched: cash needs no reference and records no entry mode")
  void cashIsUntouched() {
    UUID order = Ids.newId();

    Answer a =
        ItCalls.call(
            target,
            "POST",
            "/payments",
            cashier(WITH_MACHINE),
            "{\"orderId\":\""
                + order
                + "\",\"amount\":5.00,\"method\":\"CASH\",\"storeId\":\""
                + WITH_MACHINE
                + "\"}",
            Ids.newId().toString());

    assertThat(a.body().toString(), a.status(), is(201));
    assertThat(row(order), is("-|-"));
  }

  // ── who may allow it ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("only an owner allows a standalone machine; every change is kept with who and when")
  void onlyOwnerAllowsStandalone() {
    UUID store = LOGGER_STORE;
    UUID biz = LOGGER;
    Caller owner = Caller.owner(biz);

    for (String role : new String[] {"MANAGER", "STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      Answer a = allow(owner.as(role), store, true);
      assertThat(role + " " + a.body(), a.status(), is(403));
    }
    assertThat(
        scalar(
            PG, "SELECT count(*) FROM payment.store_card_settings WHERE tenant_id = '" + biz + "'"),
        is("0"));

    assertThat(allow(owner, store, true).status(), is(200));
    assertThat(allow(owner, store, false).status(), is(200));

    assertThat(
        scalar(
            PG,
            "SELECT count(*) FROM payment.store_card_setting_changes WHERE tenant_id = '"
                + biz
                + "' AND store_id = '"
                + store
                + "' AND changed_by = '"
                + owner.userId()
                + "' AND changed_at IS NOT NULL"),
        is("2"));
    // a manager may read it, with the history and what the store has taken
    Answer read =
        ItCalls.get(
            target, "/admin/payments/stores/" + store + "/standalone-card", owner.as("MANAGER"));
    assertThat(read.body().toString(), read.status(), is(200));
    assertThat(read.data().getBoolean("allowed"), is(false));
    assertThat(read.data().getJsonArray("changes").size(), is(2));
    assertThat(
        read.data().getJsonArray("changes").getJsonObject(0).getString("changedBy"),
        is(owner.userId().toString()));
    // a cashier may not
    assertThat(
        ItCalls.get(
                target, "/admin/payments/stores/" + store + "/standalone-card", owner.as("CASHIER"))
            .status(),
        is(403));
  }

  // ── tenant isolation ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("another business's owner can neither set nor read our store's setting")
  void otherTenantCannotTouchStoreSetting() {
    registerMachine(WITH_MACHINE);
    Caller theirs = Caller.owner(RIVAL);

    Answer set = allow(theirs, WITH_MACHINE, true);
    Answer read =
        ItCalls.get(target, "/admin/payments/stores/" + WITH_MACHINE + "/standalone-card", theirs);

    assertThat(set.body().toString(), set.status(), is(404));
    assertThat(set.code(), is("STORE_NOT_FOUND"));
    assertThat(read.status(), is(404));
    assertThat(
        scalar(
            PG,
            "SELECT count(*) FROM payment.store_card_settings WHERE store_id = '"
                + WITH_MACHINE
                + "'"),
        is("0"));
    // their own store works for them, and does not move ours
    assertThat(allow(theirs, RIVAL_STORE, true).status(), is(200));
    assertThat(
        card(cashier(WITH_MACHINE), WITH_MACHINE, Ids.newId(), "A1").code(),
        is("PAYMENT_CARD_NEEDS_TERMINAL"));
    // and a cashier of ours cannot record a card at their store by naming it
    Answer foreign =
        ItCalls.call(
            target,
            "POST",
            "/payments",
            cashier(WITH_MACHINE),
            "{\"orderId\":\""
                + Ids.newId()
                + "\",\"amount\":1.00,\"method\":\"CARD\",\"reference\":\"A\",\"storeId\":\""
                + RIVAL_STORE
                + "\"}",
            Ids.newId().toString());
    assertThat(foreign.status(), is(403));
  }
}
