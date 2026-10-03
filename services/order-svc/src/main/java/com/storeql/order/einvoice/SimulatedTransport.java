package com.storeql.order.einvoice;

import com.storeql.order.client.EInvoiceDeliveryClient;
import com.storeql.order.client.EInvoiceDeliveryClient.Delivery;
import com.storeql.order.config.Json;
import com.storeql.order.domain.EInvoiceTransports;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.util.Locale;
import java.util.Set;

/**
 * The platform standing in for every network: what a stack with no provider contract sends on, and
 * exactly why it is not a network — nothing leaves the platform. Every document is taken and
 * delivered at once, except two the tests and the demonstrations rely on: a receiver whose
 * identifier contains {@code REJECT} is refused as unknown to the network, and one containing
 * {@code LATER} is taken but answered only when asked after, the way an access point answers.
 *
 * <p>Delivered means delivered: when the deployment holds a delivery key, the document goes to
 * purchase-svc's delivery route as an access point's would, and a receiver that is a business on
 * this platform finds it in its inbox. A receiver nobody here holds is delivered to the outside
 * world the simulated way — that is, nowhere. An inbox that cannot be reached is tried again later,
 * like a network that is down; one that refuses the document is a refusal.
 */
@ApplicationScoped
public class SimulatedTransport implements EInvoiceTransport {

  static final String REFUSED = "REJECT";
  static final String DEFERRED = "LATER";

  @Inject EInvoiceDeliveryClient inbox;

  @Override
  public Set<String> networks() {
    return Set.copyOf(EInvoiceTransports.NETWORKS);
  }

  @Override
  public String name() {
    return EInvoiceTransports.PROVIDER_SIMULATED;
  }

  @Override
  public String configuration() {
    return "nothing: the platform stands in for the network, and nothing leaves it; a receiver on"
        + " this platform gets the document in its inbox";
  }

  /**
   * Nothing to ask: there is no network to ask.
   *
   * <p>Ready, because a document really will be taken and a receiver on this platform really will
   * find it — and said in the words that stop a shop believing more than that. A check that
   * answered a bare "READY" here would be the reason someone went live on a stack with no provider
   * contract.
   */
  @Override
  public Readiness check(Outbound credentials) {
    return Readiness.ready(
        "the platform stands in for the network: documents are taken and reach a receiver on this"
            + " platform, and nothing leaves it — a provider contract is what sends them further");
  }

  @Override
  public Dispatch send(Outbound d) {
    String ref =
        "SIM-"
            + d.invoiceId().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    String receiver = d.receiver() == null ? "" : d.receiver().toUpperCase(Locale.ROOT);
    if (receiver.contains(REFUSED)) {
      return new Dispatch(
          ref,
          Outcome.rejected(
              "the network knows no participant " + d.receiver(),
              json(ref, "REJECTED", "unknown participant", null)));
    }
    if (receiver.contains(DEFERRED)) {
      return new Dispatch(
          ref,
          Outcome.pending(
              "taken; the receiver's access point has not answered yet",
              json(ref, "PENDING", "awaiting the receiver", null)));
    }
    if (inbox == null || !inbox.isConfigured()) {
      return new Dispatch(
          ref, Outcome.accepted("delivered", json(ref, "DELIVERED", "delivered", null)));
    }
    // Standing in for the network means delivering: to the receiver's inbox when the receiver is
    // a business on this platform, through the route an access point would call.
    Delivery in = inbox.deliver(name(), ref, d.ubl());
    if (in.delivered()) {
      return new Dispatch(
          ref,
          Outcome.accepted(
              "delivered into the receiver's inbox on this platform",
              json(
                  ref,
                  "DELIVERED",
                  "delivered into the receiver's inbox",
                  in.json().orElse(null))));
    }
    if (in.status() == 404) {
      return new Dispatch(
          ref,
          Outcome.accepted(
              "delivered; no business on this platform holds " + d.receiver(),
              json(ref, "DELIVERED", "delivered outside the platform", null)));
    }
    if (in.status() == 401 || in.unreachable()) {
      throw new TransportException(
          in.status() == 0
              ? "the platform's inbox could not be reached"
              : "the platform's inbox answered HTTP "
                  + in.status()
                  + (in.status() == 401 ? ": the delivery key differs between services" : ""),
          null);
    }
    return new Dispatch(
        ref,
        Outcome.rejected(
            "the receiver's inbox refused the document: " + in.reason(),
            json(ref, "REJECTED", in.reason(), in.json().orElse(null))));
  }

  /** The platform stands in for the administration too: the report is taken, and nothing leaves. */
  @Override
  public boolean carriesReports() {
    return true;
  }

  @Override
  public Dispatch sendReport(Outbound report) {
    String ref =
        "SIM-ER-"
            + report
                .invoiceId()
                .toString()
                .replace("-", "")
                .substring(0, 10)
                .toUpperCase(Locale.ROOT);
    // One refusal the tests rely on, chosen to be a real failure mode rather than a magic string: a
    // report the platform could not sign as the business — no VAT number on the identity — is what
    // a
    // partner platform refuses, because it deposits under the taxpayer's own number.
    if (report.sellerVatId() == null || report.sellerVatId().isBlank()) {
      return new Dispatch(
          ref,
          Outcome.rejected(
              "the business has no VAT number, and a report is deposited under the taxpayer's own",
              json(ref, "REJECTED", "no taxpayer number", null)));
    }
    return new Dispatch(
        ref,
        Outcome.accepted(
            "taken for " + report.number(), json(ref, "DEPOSITED", "taken by the platform", null)));
  }

  @Override
  public Outcome status(Outbound d, String providerRef) {
    return Outcome.accepted("delivered", json(providerRef, "DELIVERED", "delivered", null));
  }

  /** The network's answer as kept, with the inbox's own when it gave one. */
  private static String json(String ref, String state, String message, JsonObject inbox) {
    JsonObjectBuilder b =
        Json.createObjectBuilder()
            .add("provider", EInvoiceTransports.PROVIDER_SIMULATED)
            .add("reference", ref)
            .add("state", state)
            .add("message", message);
    if (inbox != null) b.add("inbox", inbox);
    return b.build().toString();
  }
}
