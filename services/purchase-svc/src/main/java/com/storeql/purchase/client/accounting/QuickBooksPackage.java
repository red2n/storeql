package com.storeql.purchase.client.accounting;

import com.storeql.purchase.domain.Accounting;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * QuickBooks Online's Accounting API (17.9): a journal is a JournalEntry ({@code POST
 * /v3/company/{realmId}/journalentry}), each ledger line a line with an unsigned amount, a
 * PostingType of Debit or Credit and the account's id, made idempotent by the journal's id in
 * {@code requestid}. The chart is a query over Account; QuickBooks names an account by its id, and
 * a number is optional.
 */
@ApplicationScoped
public class QuickBooksPackage implements AccountingPackage {

  static final String PRODUCTION_URL = "https://quickbooks.api.intuit.com";
  static final String SANDBOX_URL = "https://sandbox-quickbooks.api.intuit.com";
  static final String TOKEN_URL = "https://oauth.platform.intuit.com/oauth2/v1/tokens/bearer";
  static final String MINOR_VERSION = "75";

  private final String baseUrlOverride;
  private final PackageHttp http;
  private final OAuthRefresh oauth;

  public QuickBooksPackage() {
    this(null, TOKEN_URL, Duration.ofSeconds(20));
  }

  QuickBooksPackage(String baseUrlOverride, String tokenUrl, Duration timeout) {
    this.baseUrlOverride = baseUrlOverride;
    this.http = new PackageHttp(timeout);
    this.oauth = new OAuthRefresh(tokenUrl, http);
  }

  static QuickBooksPackage forTest(String baseUrl, String tokenUrl) {
    return new QuickBooksPackage(baseUrl, tokenUrl, Duration.ofSeconds(5));
  }

  /** The sandbox company lives at Intuit's sandbox host; anything else is production. */
  static String baseUrlFor(String environment) {
    return "SANDBOX".equalsIgnoreCase(environment == null ? "" : environment.trim())
        ? SANDBOX_URL
        : PRODUCTION_URL;
  }

  private String baseUrl(Accounting.Connection c) {
    return baseUrlOverride != null ? baseUrlOverride : baseUrlFor(c.setting("environment"));
  }

  @Override
  public String provider() {
    return Accounting.QUICKBOOKS;
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
      boolean debit = line.debit().compareTo(BigDecimal.ZERO) > 0;
      lines.add(
          Json.createObjectBuilder()
              .add("Amount", debit ? line.debit() : line.credit())
              .add("DetailType", "JournalEntryLineDetail")
              .add("Description", line.nominalName())
              .add(
                  "JournalEntryLineDetail",
                  Json.createObjectBuilder()
                      .add("PostingType", debit ? "Debit" : "Credit")
                      .add(
                          "AccountRef",
                          Json.createObjectBuilder()
                              .add("value", account.apply(line.nominalCode())))));
    }
    JsonObject body =
        Json.createObjectBuilder()
            .add("TxnDate", j.entryDate().toString())
            .add("PrivateNote", j.description())
            .add("Line", lines)
            .build();
    PackageHttp.Reply reply =
        http.send(
            "POST",
            baseUrl(c)
                + "/v3/company/"
                + c.setting("realmId")
                + "/journalentry?minorversion="
                + MINOR_VERSION
                + "&requestid="
                + j.journalId(),
            Map.of("Authorization", "Bearer " + creds.accessToken(), "Accept", "application/json"),
            "application/json",
            body.toString());
    JsonObject json = reply.json();
    if (!reply.ok()) {
      throw new Refused(
          reply.status(), refusal(json, reply), reply.status() != 401 && reply.status() != 403);
    }
    JsonObject entry = json.containsKey("JournalEntry") ? json.getJsonObject("JournalEntry") : null;
    String id = entry == null ? null : PackageHttp.text(entry, "Id");
    if (id == null) {
      throw new Unreachable(
          "QuickBooks answered no JournalEntry id: " + reply.snippet(), true, null);
    }
    return new Pushed(id);
  }

  @Override
  public List<ExternalAccount> accounts(Accounting.Connection c, Accounting.Credentials creds) {
    String query =
        URLEncoder.encode("select * from Account maxresults 1000", StandardCharsets.UTF_8);
    PackageHttp.Reply reply =
        http.send(
            "GET",
            baseUrl(c)
                + "/v3/company/"
                + c.setting("realmId")
                + "/query?query="
                + query
                + "&minorversion="
                + MINOR_VERSION,
            Map.of("Authorization", "Bearer " + creds.accessToken(), "Accept", "application/json"),
            null,
            null);
    JsonObject json = reply.json();
    if (!reply.ok()) {
      throw new Refused(
          reply.status(), refusal(json, reply), reply.status() != 401 && reply.status() != 403);
    }
    List<ExternalAccount> out = new ArrayList<>();
    JsonObject response =
        json.containsKey("QueryResponse") ? json.getJsonObject("QueryResponse") : null;
    if (response != null && response.containsKey("Account")) {
      for (JsonValue v : response.getJsonArray("Account")) {
        JsonObject a = v.asJsonObject();
        if (a.containsKey("Active") && !a.getBoolean("Active", true)) continue;
        String id = PackageHttp.text(a, "Id");
        if (id == null) continue;
        String number = PackageHttp.text(a, "AcctNum");
        out.add(
            new ExternalAccount(
                id,
                number == null ? "" : number,
                nz(PackageHttp.text(a, "Name")),
                nz(PackageHttp.text(a, "AccountType"))));
      }
    }
    return out;
  }

  @Override
  public Accounting.Credentials refresh(Accounting.Credentials credentials, Instant now) {
    return oauth.refresh(credentials, now);
  }

  /** Intuit's Fault: each error's Detail, else its Message. */
  private static String refusal(JsonObject json, PackageHttp.Reply reply) {
    List<String> messages = new ArrayList<>();
    JsonObject fault = json.containsKey("Fault") ? json.getJsonObject("Fault") : null;
    if (fault != null && fault.containsKey("Error")) {
      for (JsonValue e : fault.getJsonArray("Error")) {
        JsonObject error = e.asJsonObject();
        String detail = PackageHttp.text(error, "Detail");
        String message = PackageHttp.text(error, "Message");
        if (detail != null) messages.add(detail);
        else if (message != null) messages.add(message);
      }
    }
    return "QuickBooks refused (HTTP "
        + reply.status()
        + "): "
        + (messages.isEmpty() ? reply.snippet() : String.join("; ", messages));
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
