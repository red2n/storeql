package com.storeql.purchase.client.accounting;

import com.storeql.purchase.domain.Accounting;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * The platform standing in for a package (17.9): delivers every journal in-process, so a stack with
 * no package connected can be driven end to end, and refuses one account when its {@code refuse}
 * setting names it, so a refusal and its retry can be rehearsed. Its chart is the ledger's own
 * codes, prefixed.
 */
@ApplicationScoped
public class SimulatedPackage implements AccountingPackage {

  static final String ID_PREFIX = "SIM-";

  private static final List<ExternalAccount> CHART =
      List.of(
          account("1001", "Stock", "ASSET"),
          account("1100", "Trade Debtors", "ASSET"),
          account("1105", "Sales Receipts Clearing", "ASSET"),
          account("1200", "Bank", "BANK"),
          account("1210", "Cash in Tills", "BANK"),
          account("1250", "Card and Wallet Clearing", "ASSET"),
          account("2100", "Trade Creditors", "LIABILITY"),
          account("2109", "Goods Received Not Invoiced", "LIABILITY"),
          account("2200", "VAT Output", "LIABILITY"),
          account("2201", "VAT Input", "ASSET"),
          account("4010", "Sales", "REVENUE"),
          account("5000", "Purchases", "EXPENSE"));

  private static ExternalAccount account(String code, String name, String type) {
    return new ExternalAccount(ID_PREFIX + code, code, name, type);
  }

  @Override
  public String provider() {
    return Accounting.SIMULATED;
  }

  @Override
  public boolean idempotentWrites() {
    return true;
  }

  @Override
  public Pushed push(
      Accounting.Connection c,
      Accounting.Credentials creds,
      Domain.Journal j,
      Function<String, String> account) {
    String refuse = c.setting(Accounting.SETTING_REFUSE);
    for (NominalLedgerEntry line : j.lines()) {
      String external = account.apply(line.nominalCode());
      if (refuse != null && !refuse.isBlank() && refuse.trim().equals(external)) {
        throw new Refused(
            400, "Simulated package refused: unknown account '" + external + "'", true);
      }
    }
    return new Pushed("sim-" + j.journalId());
  }

  @Override
  public List<ExternalAccount> accounts(Accounting.Connection c, Accounting.Credentials creds) {
    return CHART;
  }

  @Override
  public Accounting.Credentials refresh(Accounting.Credentials credentials, Instant now) {
    return credentials;
  }
}
