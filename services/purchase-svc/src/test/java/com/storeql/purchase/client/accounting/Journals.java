package com.storeql.purchase.client.accounting;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Accounting;
import com.storeql.purchase.domain.Domain;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A journal as the ledger posts it, for the drivers to translate. */
final class Journals {
  private Journals() {}

  static final UUID TENANT = Ids.parse("01a090ae-611e-702c-a97b-d1b8025478e1");
  static final UUID JOURNAL = Ids.parse("01a090ae-611e-7071-8516-000000000501");
  static final UUID STORE = Ids.parse("01a090ae-611e-7071-8516-000000000502");

  /** Dr 1001 Stock 120.00 / Cr 2109 GR/IR 100.00, Cr 2201 VAT 20.00 — three lines, balanced. */
  static Domain.Journal rent() {
    return new Domain.Journal(
        JOURNAL,
        LocalDate.parse("2026-09-23"),
        "Goods received: PO-42",
        Domain.SOURCE_GOODS_RECEIPT,
        Ids.parse("01a090ae-611e-7071-8516-000000000503"),
        STORE,
        List.of(
            line("1001", "Stock", "120.00", "0"),
            line("2109", "Goods Received Not Invoiced", "0", "100.00"),
            line("2201", "VAT Input Account", "0", "20.00")));
  }

  static Domain.NominalLedgerEntry line(String code, String name, String debit, String credit) {
    return new Domain.NominalLedgerEntry(
        Ids.newId(),
        TENANT,
        LocalDate.parse("2026-09-23"),
        code,
        name,
        new BigDecimal(debit),
        new BigDecimal(credit),
        "Goods received: PO-42",
        null,
        Instant.parse("2026-09-23T09:00:00Z"),
        JOURNAL,
        Domain.SOURCE_GOODS_RECEIPT,
        STORE);
  }

  static Accounting.Connection connection(String provider, Map<String, String> settings) {
    return new Accounting.Connection(
        Ids.newId(),
        TENANT,
        provider,
        Accounting.ACTIVE,
        settings,
        null,
        LocalDate.parse("2026-01-01"),
        Ids.newId(),
        Instant.parse("2026-09-23T08:00:00Z"),
        Instant.parse("2026-09-23T08:00:00Z"),
        null,
        null,
        null);
  }

  static Accounting.Credentials bearer(String token) {
    return new Accounting.Credentials(token, null, null, null, null);
  }

  /** Our codes onto the package's: 1001 → the package's "STOCK", the rest pass through. */
  static java.util.function.Function<String, String> mapping() {
    return code -> code.equals("1001") ? "STOCK" : code;
  }
}
