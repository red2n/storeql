package com.storeql.purchase.service;

import static com.storeql.purchase.service.StoreHeldCallersTest.caller;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.purchase.dto.Dtos.DeferredRevenueSettingsRequest;
import com.storeql.purchase.dto.Dtos.JournalLineRequest;
import com.storeql.purchase.dto.Dtos.PostJournalRequest;
import com.storeql.purchase.repo.PurchaseRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * What in purchasing belongs to the business as a whole is changed — and, where it cannot be split
 * by store, read — only by a caller held to no store: a journal posted at no store, a supplier's
 * master data (its bank details and terms are every store's), the deferred-revenue estimates and
 * where they stand. A caller held to stores is refused {@code 403 BUSINESS_WIDE_ONLY} before
 * anything is read or written; a caller held to none is past the gate. No database: every
 * repository is a stand-in that counts what it is asked.
 */
class BusinessWideServicesTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE_A = Ids.newId();
  private static final UUID STORE_B = Ids.newId();

  private static TenantContext heldToA(String role) {
    return caller(TENANT, role, Set.of(STORE_A));
  }

  private static TenantContext wholeBusiness(String role) {
    return caller(TENANT, role, Set.of());
  }

  private static void businessWideOnly(Executable call) {
    ApiException e = assertThrows(ApiException.class, call);
    assertThat(e.getMessage(), e.status(), is(403));
    assertThat(e.getMessage(), e.code(), is("BUSINESS_WIDE_ONLY"));
  }

  /** Past the gate: whatever happens next, it is not the business-wide refusal. */
  private static void pastTheGate(Executable call) {
    try {
      call.execute();
    } catch (ApiException e) {
      assertThat(e.getMessage(), e.code().equals("BUSINESS_WIDE_ONLY"), is(false));
    } catch (Throwable other) {
      // a stand-in that was not given what the rest of the call needs: the gate let it through
    }
  }

  /** Journals posted and suppliers read, counted. */
  private static final class Ledger extends PurchaseRepository {
    int posted;
    int suppliersRead;
    Set<UUID> lastStores = Set.of();

    @Override
    public void postJournal(List<NominalLedgerEntry> entries) {
      posted++;
      Set<UUID> stores = new java.util.HashSet<>();
      for (NominalLedgerEntry e : entries) stores.add(e.storeId());
      lastStores = stores;
    }

    @Override
    public Optional<Domain.Supplier> findSupplier(UUID tenantId, UUID id) {
      suppliersRead++;
      return Optional.empty();
    }
  }

  private static PostJournalRequest journal(UUID storeId) {
    return new PostJournalRequest(
        "2026-09-15",
        "Accrual",
        storeId == null ? null : storeId.toString(),
        List.of(
            new JournalLineRequest("5000", "Purchases", new BigDecimal("10.00"), null),
            new JournalLineRequest("2100", "Creditors", null, new BigDecimal("10.00"))));
  }

  @Test
  @DisplayName(
      "A journal posted at no store is the business's own: a manager held to several stores who"
          + " names none is refused BUSINESS_WIDE_ONLY and nothing is posted; held to none, it is")
  void aJournalAtNoStoreIsTheBusinesss() {
    Ledger ledger = new Ledger();
    PurchaseService svc = new PurchaseService();
    svc.repo = ledger;
    svc.inventory = new NoPeriods();

    businessWideOnly(() -> svc.postJournal(heldToAAndB("MANAGER"), journal(null)));
    businessWideOnly(() -> svc.postJournal(heldToAAndB("OWNER"), journal(null)));
    assertThat("nothing was posted", ledger.posted, is(0));

    Domain.Journal business = svc.postJournal(wholeBusiness("MANAGER"), journal(null));
    assertThat(ledger.posted, is(1));
    assertThat("held to none, it is the business's", business.storeId(), is((UUID) null));
    pastTheGate(() -> svc.postJournal(heldToA("MANAGER"), journal(STORE_A)));
  }

  @Test
  @DisplayName(
      "A manager held to one store who names none posts at that store, as a read names it (SJ-D74):"
          + " the journal is theirs to read back, and the app need not ask")
  void aJournalFromACallerOfOneStoreIsPostedAtThatStore() {
    Ledger ledger = new Ledger();
    PurchaseService svc = new PurchaseService();
    svc.repo = ledger;
    svc.inventory = new NoPeriods();

    Domain.Journal posted = svc.postJournal(heldToA("MANAGER"), journal(null));

    assertThat(ledger.posted, is(1));
    assertThat(posted.storeId(), is(STORE_A));
    assertThat("every line carries the store", ledger.lastStores, is(Set.of(STORE_A)));
  }

  /**
   * A caller held to two stores: which one a store-less journal belongs to is not theirs to guess.
   */
  private static TenantContext heldToAAndB(String role) {
    return caller(TENANT, role, Set.of(STORE_A, STORE_B));
  }

  /** Inventory with no accounting periods kept: every month is open. */
  private static final class NoPeriods extends com.storeql.purchase.client.InventoryClient {
    @Override
    public Optional<List<com.storeql.purchase.domain.PeriodControl.Period>> accountingPeriods(
        UUID tenantId, UUID storeId) {
      return Optional.empty();
    }
  }

  @Test
  @DisplayName(
      "A supplier's master data is every store's: a manager held to a store is refused"
          + " BUSINESS_WIDE_ONLY correcting it, before the supplier is read; held to none, past it")
  void aSuppliersMasterDataIsTheBusinesss() {
    Ledger ledger = new Ledger();
    PurchaseService svc = new PurchaseService();
    svc.repo = ledger;
    UUID supplier = Ids.newId();

    businessWideOnly(() -> svc.updateSupplier(heldToA("MANAGER"), supplier, null));
    assertThat("the supplier was not read", ledger.suppliersRead, is(0));
    pastTheGate(() -> svc.updateSupplier(wholeBusiness("MANAGER"), supplier, null));
    assertThat(ledger.suppliersRead, is(1));
  }

  @Test
  @DisplayName(
      "The deferred-revenue estimates and where they stand are the business's: a manager held to"
          + " a store is refused BUSINESS_WIDE_ONLY reading or setting them; held to none, past it")
  void deferredRevenueIsTheBusinesss() {
    DeferredRevenueService svc = new DeferredRevenueService();
    DeferredRevenueSettingsRequest req =
        new DeferredRevenueSettingsRequest(
            new BigDecimal("0.01"), new BigDecimal("20"), new BigDecimal("10"), "year end");
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      businessWideOnly(() -> svc.view(heldToA(role)));
      businessWideOnly(() -> svc.setEstimates(heldToA(role), req));
    }
    pastTheGate(() -> svc.view(wholeBusiness("MANAGER")));
    pastTheGate(() -> svc.setEstimates(wholeBusiness("MANAGER"), req));
  }
}
