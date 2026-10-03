package com.storeql.order.einvoice;

import com.storeql.order.config.Json;
import com.storeql.order.domain.EInvoiceTransports;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * France's approved platform (plateforme agréée, the reform's PDP), through the HTTP facade the
 * platforms offer: a document is deposited with the business's SIREN and the buyer's directory
 * address, in the format it was written in, and its life is read back as the reform's fourteen
 * statuses — 200 déposée, 201 émise, 202 mise à disposition … 209 reçue par la plateforme, 210
 * refusée by the buyer, 213 rejetée by the platform. Configured with the platform's base URL and
 * key ({@code storeql.einvoice.fr-pdp.base-url}, {@code .api-key}); deployed always, choosable only
 * configured. A contract with an approved platform is not code.
 */
@ApplicationScoped
public class FrPdpTransport extends BearerFacadeTransport {

  public static final String NAME = "PDP";

  /** The lifecycle statuses of the reform (DGFiP external specifications, 200–213). */
  static final int DEPOSITED = 200;

  static final int ISSUED = 201;
  static final int REFUSED = 210;
  static final int REJECTED = 213;

  @Inject
  @ConfigProperty(name = "storeql.einvoice.fr-pdp.base-url")
  Optional<String> baseUrlConfig;

  @Inject
  @ConfigProperty(name = "storeql.einvoice.fr-pdp.api-key")
  Optional<String> apiKeyConfig;

  @PostConstruct
  void init() {
    configure(baseUrlConfig.orElse(""), apiKeyConfig.orElse(""));
  }

  /** For tests: a transport pointed at a stub, with no CDI. */
  static FrPdpTransport forTest(String baseUrl, String apiKey) {
    FrPdpTransport t = new FrPdpTransport();
    t.configure(baseUrl, apiKey);
    return t;
  }

  @Override
  String facade() {
    return "the platform";
  }

  @Override
  public Set<String> networks() {
    return Set.of(EInvoiceTransports.NETWORK_FR_PDP);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String configuration() {
    return "storeql.einvoice.fr-pdp.base-url and storeql.einvoice.fr-pdp.api-key, and the"
        + " business's SIREN as its account";
  }

  @Override
  public Dispatch send(Outbound d) {
    JsonObject body =
        Json.createObjectBuilder()
            .add("format", "UBL")
            .add("documentKind", d.documentKind() == null ? "Invoice" : d.documentKind())
            .add("documentNumber", d.number() == null ? "" : d.number())
            .add("supplierSiren", d.providerAccount() == null ? "" : d.providerAccount())
            .add("supplierVatId", d.sellerVatId() == null ? "" : d.sellerVatId())
            .add("recipient", d.receiver() == null ? "" : d.receiver())
            .add(
                "document",
                Base64.getEncoder().encodeToString(d.ubl().getBytes(StandardCharsets.UTF_8)))
            .build();
    return dispatched(post("/invoices", body), FrPdpTransport::outcome);
  }

  /**
   * The platform carries e-reporting as well as invoices — that is what a PDP is for: the reform
   * gives it both limbs, and a business that has chosen one has chosen the other.
   */
  /**
   * The platform's own directory read: it answers to a taxpayer it knows, and refuses one it does
   * not.
   */
  @Override
  public Readiness check(Outbound credentials) {
    return probe(
        "/participants/"
            + (credentials.providerAccount() == null ? "" : credentials.providerAccount()));
  }

  @Override
  public boolean carriesReports() {
    return true;
  }

  /**
   * Deposits e-reporting data with the platform.
   *
   * <p>Its own route, not {@code /invoices}: the reform's two limbs are separate flows with
   * separate statuses, and a report deposited as an invoice would be refused — or worse, accepted
   * and counted as one. The taxpayer is named by SIREN and VAT number, as a deposit under the
   * reform is.
   */
  @Override
  public Dispatch sendReport(Outbound report) {
    JsonObject body =
        Json.createObjectBuilder()
            .add("format", "EREPORTING")
            .add("stream", report.number() == null ? "" : report.number())
            .add("taxpayerSiren", report.providerAccount() == null ? "" : report.providerAccount())
            .add("taxpayerVatId", report.sellerVatId() == null ? "" : report.sellerVatId())
            .add(
                "data",
                Base64.getEncoder().encodeToString(report.ubl().getBytes(StandardCharsets.UTF_8)))
            .build();
    return dispatched(post("/ereporting", body), FrPdpTransport::outcome);
  }

  @Override
  public Outcome status(Outbound d, String providerRef) {
    Reply reply = get("/invoices/" + providerRef + "/lifecycle");
    if (reply.ok()) return outcome(reply.object(), reply.body());
    return Outcome.rejected("the platform no longer knows invoice " + providerRef, reply.body());
  }

  /**
   * The reform's status as the platform states it: a {@code status} object with a {@code code}, or
   * the code alone. Deposited and issued are on their way; refused by the buyer and rejected by the
   * platform are final; every other status means the buyer's platform has it.
   */
  static Outcome outcome(JsonObject o, String answer) {
    JsonObject status =
        o.containsKey("status") && o.get("status").getValueType() == JsonValue.ValueType.OBJECT
            ? o.getJsonObject("status")
            : o;
    int code = code(status);
    String reason = reason(status, "");
    if (code == DEPOSITED || code == ISSUED) {
      return Outcome.pending(
          (code == DEPOSITED ? "deposited" : "issued by the platform")
              + "; not yet with the buyer's platform",
          answer);
    }
    if (code == REFUSED) {
      return Outcome.rejected(
          "refused by the buyer" + (reason.isBlank() ? "" : ": " + reason), answer);
    }
    if (code == REJECTED) {
      return Outcome.rejected(
          "rejected by the platform" + (reason.isBlank() ? "" : ": " + reason), answer);
    }
    if (code >= 202 && code <= 212) {
      return Outcome.accepted("with the buyer's platform (status " + code + ")", answer);
    }
    return Outcome.pending(reason.isBlank() ? "taken; status " + code : reason, answer);
  }

  private static int code(JsonObject status) {
    for (String key : new String[] {"code", "statusCode", "status"}) {
      if (!status.containsKey(key) || status.isNull(key)) continue;
      JsonValue v = status.get(key);
      if (v.getValueType() == JsonValue.ValueType.NUMBER) return ((JsonNumber) v).intValue();
      if (v.getValueType() == JsonValue.ValueType.STRING) {
        try {
          return Integer.parseInt(((JsonString) v).getString().trim());
        } catch (NumberFormatException ignored) {
          // a word, not a code: read on
        }
      }
    }
    return 0;
  }
}
