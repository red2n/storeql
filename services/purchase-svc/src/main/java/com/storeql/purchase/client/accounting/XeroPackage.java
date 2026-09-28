package com.storeql.purchase.client.accounting;

import com.storeql.purchase.domain.Accounting;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Xero's Accounting API (17.9): a journal is a manual journal ({@code PUT
 * /api.xro/2.0/ManualJournals}) for the organisation named by {@code xero-tenant-id}, each ledger
 * line a journal line whose amount is positive for a debit and negative for a credit, posted, and
 * made idempotent by the journal's id in {@code Idempotency-Key}. The chart is {@code GET
 * /api.xro/2.0/Accounts}; Xero names an account by its code.
 */
@ApplicationScoped
public class XeroPackage implements AccountingPackage {

  static final String BASE_URL = "https://api.xero.com";
  static final String TOKEN_URL = "https://identity.xero.com/connect/token";

  private final String baseUrl;
  private final PackageHttp http;
  private final OAuthRefresh oauth;

  public XeroPackage() {
    this(BASE_URL, TOKEN_URL, Duration.ofSeconds(20));
  }

  XeroPackage(String baseUrl, String tokenUrl, Duration timeout) {
    this.baseUrl = baseUrl;
    this.http = new PackageHttp(timeout);
    this.oauth = new OAuthRefresh(tokenUrl, http);
  }

  static XeroPackage forTest(String baseUrl) {
    return new XeroPackage(baseUrl, baseUrl + "/connect/token", Duration.ofSeconds(5));
  }

  @Override
  public String provider() {
    return Accounting.XERO;
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
    JsonArrayBuilder lines = Json.createArrayBuilder();
    for (NominalLedgerEntry line : j.lines()) {
      BigDecimal amount = line.debit().subtract(line.credit());
      lines.add(
          Json.createObjectBuilder()
              .add("LineAmount", amount)
              .add("AccountCode", account.apply(line.nominalCode()))
              .add("Description", line.nominalName())
              .add("TaxType", "NONE"));
    }
    JsonObject body =
        Json.createObjectBuilder()
            .add(
                "ManualJournals",
                Json.createArrayBuilder()
                    .add(
                        Json.createObjectBuilder()
                            .add("Narration", j.description())
                            .add("Date", j.entryDate().toString())
                            .add("Status", "POSTED")
                            .add("JournalLines", lines)))
            .build();
    PackageHttp.Reply reply =
        http.send(
            "PUT",
            baseUrl + "/api.xro/2.0/ManualJournals",
            Map.of(
                "Authorization",
                "Bearer " + creds.accessToken(),
                "xero-tenant-id",
                c.setting("tenantId"),
                "Idempotency-Key",
                j.journalId().toString(),
                "Accept",
                "application/json"),
            "application/json",
            body.toString());
    JsonObject json = reply.json();
    if (!reply.ok()) {
      throw new Refused(
          reply.status(), refusal(json, reply), reply.status() != 401 && reply.status() != 403);
    }
    JsonArray journals =
        json.containsKey("ManualJournals") ? json.getJsonArray("ManualJournals") : null;
    String id =
        journals == null || journals.isEmpty()
            ? null
            : PackageHttp.text(journals.getJsonObject(0), "ManualJournalID");
    if (id == null) {
      throw new Unreachable("Xero answered no ManualJournalID: " + reply.snippet(), true, null);
    }
    return new Pushed(id);
  }

  @Override
  public List<ExternalAccount> accounts(Accounting.Connection c, Accounting.Credentials creds) {
    PackageHttp.Reply reply =
        http.send(
            "GET",
            baseUrl + "/api.xro/2.0/Accounts",
            Map.of(
                "Authorization",
                "Bearer " + creds.accessToken(),
                "xero-tenant-id",
                c.setting("tenantId"),
                "Accept",
                "application/json"),
            null,
            null);
    JsonObject json = reply.json();
    if (!reply.ok()) {
      throw new Refused(
          reply.status(), refusal(json, reply), reply.status() != 401 && reply.status() != 403);
    }
    List<ExternalAccount> out = new ArrayList<>();
    if (json.containsKey("Accounts")) {
      for (JsonValue v : json.getJsonArray("Accounts")) {
        JsonObject a = v.asJsonObject();
        String status = PackageHttp.text(a, "Status");
        if (status != null && !"ACTIVE".equals(status)) continue;
        String code = PackageHttp.text(a, "Code");
        if (code == null || code.isBlank()) continue;
        out.add(
            new ExternalAccount(
                code, code, nz(PackageHttp.text(a, "Name")), nz(PackageHttp.text(a, "Type"))));
      }
    }
    return out;
  }

  @Override
  public Accounting.Credentials refresh(Accounting.Credentials credentials, Instant now) {
    return oauth.refresh(credentials, now);
  }

  /** Xero's own words: every validation message, else its Message, Detail or Title. */
  private static String refusal(JsonObject json, PackageHttp.Reply reply) {
    List<String> messages = new ArrayList<>();
    if (json.containsKey("Elements")) {
      for (JsonValue e : json.getJsonArray("Elements")) {
        JsonObject element = e.asJsonObject();
        if (!element.containsKey("ValidationErrors")) continue;
        for (JsonValue ve : element.getJsonArray("ValidationErrors")) {
          String m = PackageHttp.text(ve.asJsonObject(), "Message");
          if (m != null) messages.add(m);
        }
      }
    }
    if (messages.isEmpty()) {
      for (String key : new String[] {"Message", "Detail", "Title"}) {
        String m = PackageHttp.text(json, key);
        if (m != null) messages.add(m);
      }
    }
    return "Xero refused (HTTP "
        + reply.status()
        + "): "
        + (messages.isEmpty() ? reply.snippet() : String.join("; ", messages));
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
