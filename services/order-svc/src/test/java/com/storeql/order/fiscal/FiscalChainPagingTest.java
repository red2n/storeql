package com.storeql.order.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.order.domain.Domain.FiscalReceipt;
import com.storeql.order.repo.FiscalReceiptRepository;
import com.storeql.order.repo.FiscalReceiptRepository.ChainVerdict;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The audit and the CSV export walk a series a page at a time, never all of it at once. */
class FiscalChainPagingTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();

  /** A repository whose pages come from a list, counting how many were read and how large. */
  static final class Paged extends FiscalReceiptRepository {
    final List<FiscalReceipt> all;
    int pages;
    int largestPage;

    Paged(List<FiscalReceipt> all) {
      this.all = all;
    }

    @Override
    public List<FiscalReceipt> listSeriesAfter(
        UUID tenantId, UUID storeId, String series, String period, long after, int limit) {
      pages++;
      List<FiscalReceipt> page = all.stream().filter(r -> r.number() > after).limit(limit).toList();
      largestPage = Math.max(largestPage, page.size());
      return page;
    }
  }

  private static List<FiscalReceipt> chain(int n, boolean tamperAt3) {
    List<FiscalReceipt> out = new ArrayList<>();
    String prev = "GENESIS";
    for (int i = 1; i <= n; i++) {
      UUID order = Ids.newId();
      FiscalReceipt unsigned =
          receipt(i, order, prev, null, i == 3 && tamperAt3 ? "10.00" : "5.00");
      String hash = FiscalReceiptRepository.hashOf(unsigned);
      // The stored hash is the one written at issue time, over the untampered figures.
      FiscalReceipt stored = receipt(i, order, prev, hash, i == 3 && tamperAt3 ? "9.99" : "5.00");
      out.add(stored);
      prev = hash;
    }
    return out;
  }

  private static FiscalReceipt receipt(long n, UUID order, String prev, String hash, String gross) {
    return new FiscalReceipt(
        Ids.newId(),
        TENANT,
        STORE,
        "MAIN",
        "2026",
        n,
        "R-" + n,
        order,
        Instant.parse("2026-01-01T00:00:00Z").plusSeconds(n),
        null,
        "EUR",
        new BigDecimal(gross),
        new BigDecimal("0.50"),
        null,
        null,
        prev,
        hash,
        "NONE",
        null,
        null);
  }

  @Test
  void aLongSeriesIsVerifiedOnePageAtATime() {
    Paged repo = new Paged(chain(25, false));
    var seen = new int[1];
    repo.forEachInSeries(
        TENANT,
        STORE,
        "MAIN",
        "2026",
        10,
        r -> {
          seen[0]++;
          return true;
        });
    assertEquals(25, seen[0]);
    assertEquals(3, repo.pages);
    assertTrue(repo.largestPage <= 10, "never more than a page held: " + repo.largestPage);
  }

  @Test
  void theWalkStopsAtTheFirstBreak() {
    Paged repo = new Paged(chain(25, true));
    ChainVerdict v = repo.verifyChain(TENANT, STORE, "MAIN", "2026");
    assertEquals(Boolean.FALSE, v.intact());
    assertEquals(3L, v.brokenAt());
    assertEquals(1L, v.from());
  }

  @Test
  void anIntactSeriesIsIntactAcrossPages() {
    Paged repo = new Paged(chain(2500, false));
    ChainVerdict v = repo.verifyChain(TENANT, STORE, "MAIN", "2026");
    assertEquals(Boolean.TRUE, v.intact());
    assertEquals(1L, v.from());
    assertTrue(repo.pages >= 3, "default page size is 1000, so 2500 receipts take several pages");
  }
}
