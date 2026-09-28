package com.storeql.notification.messaging;

import com.storeql.ids.Ids;
import com.storeql.notification.channel.SmsChannel;
import com.storeql.notification.client.CustomerClient;
import com.storeql.notification.service.Messages;
import com.storeql.notification.service.Notifier;
import com.storeql.notification.template.Catalogue;
import com.storeql.notification.template.Values;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Writes to the buyer a recall notice was issued to (GPSR art.35): by email to the shop's customer
 * record, else by text to the number the order or the record holds, and a push to the login's
 * devices besides. The message says what art.36 asks: the headline, the product and its lot, the
 * hazard in plain words, what to do, the remedies to choose from, where to turn, and to pass it on.
 * Each send is keyed on the event, so a redelivery tells nobody twice; a malformed payload is
 * skipped; a buyer with no address at all is logged, not lost — order-svc still holds the notice
 * for when they come back.
 */
@ApplicationScoped
class RecallNoticeIssuedHandler {

  private static final Logger LOG = System.getLogger(RecallNoticeIssuedHandler.class.getName());
  static final String TYPE_EMAIL = "RECALL_NOTICE";
  static final String TYPE_SMS = "RECALL_NOTICE_SMS";
  static final String TYPE_PUSH = "RECALL_NOTICE_PUSH";
  @Inject Notifier notifier;
  @Inject CustomerClient customers;

  /** A product line the buyer bought: what a recall notice names. */
  private record Line(String name, String sku, String lot, LocalDate bestBefore, BigDecimal qty) {

    /**
     * "Crunchy peanut butter (PB-340), lot L1, best before 2026-10-01, 2 bought": English
     * shorthand.
     */
    String inEnglish() {
      StringBuilder b = new StringBuilder(name != null ? name : "the product");
      if (sku != null) b.append(" (").append(sku).append(')');
      if (lot != null) b.append(", lot ").append(lot);
      if (bestBefore != null) b.append(", best before ").append(bestBefore);
      return b.append(", ")
          .append(qty.stripTrailingZeros().toPlainString())
          .append(" bought")
          .toString();
    }
  }

  private record Parsed(
      UUID eventId,
      UUID tenantId,
      UUID customerId,
      UUID loginId,
      String buyerPhone,
      String reference,
      String hazardCode,
      String reason,
      String customerNotice,
      List<String> remedies,
      String singleRemedyReason,
      String contactPhone,
      String contactUrl,
      UUID orderId,
      Instant soldAt,
      List<Line> lines) {}

  void handle(String json) {
    Parsed p;
    try (var reader = Json.createReader(new StringReader(json))) {
      p = parse(reader.readObject());
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed RecallNoticeIssued payload skipped: " + e.getMessage());
      return;
    }
    // In the buyer's own language when they have said which (13.x). What the notice must say is
    // held by the template's required parts: a business's words cannot leave any of them out.
    String language =
        p.customerId() == null
            ? null
            : customers.languageOf(p.tenantId(), p.customerId()).orElse(null);
    String email =
        p.customerId() == null
            ? null
            : customers.emailOf(p.tenantId(), p.customerId()).orElse(null);
    if (email != null) {
      notifier.notifyOnce(
          p.eventId(),
          TYPE_EMAIL,
          p.tenantId(),
          p.customerId(),
          email,
          message(Catalogue.Form.EMAIL, language, p));
    } else {
      String phone = p.buyerPhone();
      // The order's number when it can be texted, else the buyer's own record's (a phone at the
      // till): an order placed before its number was kept in international form carries it as
      // typed, and a local form is never guessed into a number that may not be theirs.
      if ((phone == null || !SmsChannel.E164.matcher(phone).matches()) && p.customerId() != null) {
        phone = customers.phoneOf(p.tenantId(), p.customerId()).orElse(phone);
      }
      if (phone != null && SmsChannel.E164.matcher(phone).matches()) {
        notifier.notifyOnce(
            p.eventId(),
            TYPE_SMS,
            p.tenantId(),
            p.customerId(),
            phone,
            message(Catalogue.Form.SMS, language, p),
            "SMS");
      } else if (p.loginId() == null) {
        LOG.log(
            Level.WARNING,
            "Recall notice for order {0} has no email and no usable number; nobody written to",
            p.orderId());
      }
    }
    if (p.loginId() != null) {
      try {
        notifier.notifyOnce(
            p.eventId(),
            TYPE_PUSH,
            p.tenantId(),
            p.customerId(),
            p.loginId().toString(),
            message(Catalogue.Form.PUSH, language, p),
            "PUSH");
      } catch (RuntimeException e) {
        LOG.log(Level.DEBUG, "No push for recall notice {0}: {1}", p.orderId(), e.getMessage());
      }
    }
  }

  private static Parsed parse(JsonObject obj) {
    List<String> remedies =
        obj.getJsonArray("remedies").getValuesAs(JsonString.class).stream()
            .map(JsonString::getString)
            .toList();
    List<Line> lines =
        obj.getJsonArray("lines").getValuesAs(JsonObject.class).stream()
            .map(RecallNoticeIssuedHandler::line)
            .toList();
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("a recall notice names what was bought");
    }
    return new Parsed(
        Ids.parse(obj.getString("eventId")),
        Ids.parse(obj.getString("tenantId")),
        uuid(obj, "customerId"),
        uuid(obj, "loginId"),
        text(obj, "buyerPhone"),
        obj.getString("reference"),
        obj.getString("hazard"),
        obj.getString("reason"),
        obj.getString("customerNotice"),
        remedies,
        text(obj, "singleRemedyReason"),
        text(obj, "contactPhone"),
        text(obj, "contactUrl"),
        Ids.parse(obj.getString("orderId")),
        Instant.parse(obj.getString("soldAt")),
        lines);
  }

  private static Line line(JsonObject l) {
    String expiry = text(l, "expiryDate");
    return new Line(
        text(l, "productName"),
        text(l, "sku"),
        text(l, "batchNo"),
        expiry == null ? null : LocalDate.parse(expiry),
        new BigDecimal(l.get("qty").toString()));
  }

  /**
   * The notice's parts, in the order GPSR art.36(2) lists them: what, when, the hazard, what to do,
   * the remedy, whom to contact — each as a value, so a business's template can put them in its own
   * words and language, and codes and flags beside the English, for those words to choose from.
   */
  private static Messages.Message message(Catalogue.Form form, String language, Parsed p) {
    Values v =
        Values.of()
            .text("reference", p.reference())
            .items(
                "products",
                p.lines().stream()
                    .map(
                        l ->
                            Values.of()
                                .text("name", l.name() != null ? l.name() : "the product")
                                .text("sku", l.sku())
                                .text("lot", l.lot())
                                .day("best_before", l.bestBefore())
                                .number("quantity", l.qty()))
                    .toList())
            .text("product", p.lines().get(0).inEnglish())
            .day("bought_on", p.soldAt().atOffset(ZoneOffset.UTC).toLocalDate())
            .text("order", p.orderId().toString())
            .text("hazard", RecallText.hazard(p.hazardCode()))
            .text("hazard_code", p.hazardCode())
            .text("reason", p.reason())
            .text("what_to_do", p.customerNotice())
            .text("remedies", RecallText.remedies(p.remedies()))
            .flag("remedy_refund", p.remedies().contains("REFUND"))
            .flag("remedy_replacement", p.remedies().contains("REPLACEMENT"))
            .flag("remedy_repair", p.remedies().contains("REPAIR"))
            .text("single_remedy_reason", p.singleRemedyReason())
            .text("contact", contact(p))
            .text("contact_phone", p.contactPhone())
            .text("contact_url", p.contactUrl());
    for (String hazard :
        List.of(
            "MICROBIOLOGICAL", "ALLERGEN", "FOREIGN_BODY", "CHEMICAL", "LABELLING", "QUALITY")) {
      v.flag("hazard_" + hazard.toLowerCase(java.util.Locale.ROOT), hazard.equals(p.hazardCode()));
    }
    return new Messages.Message("RECALL_NOTICE", form, language, v);
  }

  private static String contact(Parsed p) {
    if (p.contactPhone() != null && p.contactUrl() != null) {
      return p.contactPhone() + " or " + p.contactUrl();
    }
    return p.contactPhone() != null ? p.contactPhone() : p.contactUrl();
  }

  private static String text(JsonObject obj, String field) {
    return obj.containsKey(field) && !obj.isNull(field) ? obj.getString(field) : null;
  }

  private static UUID uuid(JsonObject obj, String field) {
    String value = text(obj, field);
    return value == null ? null : Ids.parse(value);
  }
}
