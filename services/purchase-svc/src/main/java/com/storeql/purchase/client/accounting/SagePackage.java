package com.storeql.purchase.client.accounting;

import com.storeql.purchase.domain.Accounting;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Sage Business Cloud Accounting's API v3.1 (17.9): a journal is {@code POST /v3.1/journals} for
 * the business named by {@code X-Business}, each ledger line a line with the ledger account's id
 * and a debit or a credit. Sage offers no idempotency key, so a push that got no answer waits for a
 * person. The chart is {@code GET /v3.1/ledger_accounts}, a page at a time; Sage names an account
 * by its id and shows a nominal code beside it.
 */
@ApplicationScoped
public class SagePackage implements AccountingPackage {

  static final String BASE_URL = "https://api.accounting.sage.com";
  static final String TOKEN_URL = "https://oauth.accounting.sage.com/token";
  private static final int MAX_PAGES = 20;

  private final String baseUrl;
  private final PackageHttp http;
  private final OAuthRefresh oauth;

  public SagePackage() {
    this(BASE_URL, TOKEN_URL, Duration.ofSeconds(20));
  }

  SagePackage(String baseUrl, String tokenUrl, Duration timeout) {
    this.baseUrl = baseUrl;
    this.http = new PackageHttp(timeout);
    this.oauth = new OAuthRefresh(tokenUrl, http);
  }

  static SagePackage forTest(String baseUrl) {
    return new SagePackage(baseUrl, baseUrl + "/token", Duration.ofSeconds(5));
  }

  @Override
  public String provider() {
    return Accounting.SAGE;
  }

  @Override
  public boolean idempotentWrites() {
    return false;
  }

  @Override
  public Pushed push(
      Accounting.Connection c,
      Accounting.Credentials creds,
      Domain.Journal j,
      Function<String, String> account) {
    JsonArrayBuilder lines = Json.createArrayBuilder();
    for (NominalLedgerEntry line : j.lines()) {
      lines.add(
          Json.createObjectBuilder()
              .add("ledger_account_id", account.apply(line.nominalCode()))
              .add("details", line.nominalName())
              .add("debit", line.debit())
              .add("credit", line.credit()));
    }
    JsonObject body =
        Json.createObjectBuilder()
            .add(
                "journal",
                Json.createObjectBuilder()
                    .add("date", j.entryDate().toString())
                    .add("reference", j.journalId().toString())
                    .add("description", j.description())
                    .add("journal_lines", lines))
            .build();
    PackageHttp.Reply reply =
        http.send(
            "POST",
            baseUrl + "/v3.1/journals",
            headers(c, creds),
            "application/json",
            body.toString());
    JsonObject json = reply.json();
    if (!reply.ok()) {
      throw new Refused(
          reply.status(), refusal(json, reply), reply.status() != 401 && reply.status() != 403);
    }
    String id = PackageHttp.text(json, "id");
    if (id == null) {
      throw new Unreachable("Sage answered no journal id: " + reply.snippet(), true, null);
    }
    return new Pushed(id);
  }

  @Override
  public List<ExternalAccount> accounts(Accounting.Connection c, Accounting.Credentials creds) {
    List<ExternalAccount> out = new ArrayList<>();
    for (int page = 1; page <= MAX_PAGES; page++) {
      PackageHttp.Reply reply =
          http.send(
              "GET",
              baseUrl + "/v3.1/ledger_accounts?items_per_page=200&page=" + page,
              headers(c, creds),
              null,
              null);
      JsonObject json = reply.json();
      if (!reply.ok()) {
        throw new Refused(
            reply.status(), refusal(json, reply), reply.status() != 401 && reply.status() != 403);
      }
      if (json.containsKey("$items")) {
        for (JsonValue v : json.getJsonArray("$items")) {
          JsonObject a = v.asJsonObject();
          String id = PackageHttp.text(a, "id");
          if (id == null) continue;
          String name = PackageHttp.text(a, "name");
          if (name == null) name = PackageHttp.text(a, "displayed_as");
          String code = PackageHttp.text(a, "nominal_code");
          JsonObject type =
              a.containsKey("ledger_account_type") && !a.isNull("ledger_account_type")
                  ? a.getJsonObject("ledger_account_type")
                  : null;
          out.add(
              new ExternalAccount(
                  id,
                  code == null ? "" : code,
                  name == null ? "" : name,
                  nz(PackageHttp.text(type, "id"))));
        }
      }
      String next = PackageHttp.text(json, "$next");
      if (next == null || next.isBlank()) break;
    }
    return out;
  }

  @Override
  public Accounting.Credentials refresh(Accounting.Credentials credentials, Instant now) {
    return oauth.refresh(credentials, now);
  }

  private static Map<String, String> headers(
      Accounting.Connection c, Accounting.Credentials creds) {
    return Map.of(
        "Authorization",
        "Bearer " + creds.accessToken(),
        "X-Business",
        c.setting("businessId"),
        "Accept",
        "application/json");
  }

  /** Sage's message, with what it was about. */
  private static String refusal(JsonObject json, PackageHttp.Reply reply) {
    String message = PackageHttp.text(json, "$message");
    String source = PackageHttp.text(json, "$source");
    if (message == null) {
      List<String> messages = new ArrayList<>();
      if (json.containsKey("$items")) {
        for (JsonValue v : json.getJsonArray("$items")) {
          String m = PackageHttp.text(v.asJsonObject(), "$message");
          if (m != null) messages.add(m);
        }
      }
      message = messages.isEmpty() ? reply.snippet() : String.join("; ", messages);
    }
    return "Sage refused (HTTP "
        + reply.status()
        + "): "
        + message
        + (source == null ? "" : " (" + source + ")");
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
