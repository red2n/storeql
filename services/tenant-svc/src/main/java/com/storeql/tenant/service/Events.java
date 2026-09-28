package com.storeql.tenant.service;

import static com.storeql.events.EventPayload.esc;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Domain;
import com.storeql.tenant.domain.Subscriptions.Invoice;
import com.storeql.tenant.domain.Subscriptions.Subscription;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Builds JSON event payloads for the outbox. Past-tense events, topic storeql.tenant.<event>.
 *
 * <p>Every interpolated string that originates from a request (tenant name, store/zone codes,
 * country, currency, role) goes through {@code esc()} — a quote in a tenant name must not be able
 * to corrupt the event JSON or inject fields into it.
 */
final class Events {

  private Events() {}

  static String tenantCreated(
      UUID tenantId, UUID ownerUserId, String name, String country, String currency) {
    return tenantCreated(
        tenantId, ownerUserId, name, country, currency, Domain.Tenant.MODE_LIVE, null);
  }

  /**
   * A business made, saying what kind (22.8): {@code mode} is {@code LIVE} or {@code SANDBOX}, and
   * a sandbox names the live business it stands in for as {@code sandboxOf}. iam-svc binds the
   * owner of a live business and, for a sandbox, keeps the mapping instead — the owner already owns
   * the live one.
   */
  static String tenantCreated(
      UUID tenantId,
      UUID ownerUserId,
      String name,
      String country,
      String currency,
      String mode,
      UUID sandboxOf) {
    return """
                {"eventId":"%s","eventType":"TenantCreated","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "ownerUserId":"%s","name":"%s","country":"%s","currency":"%s","mode":"%s","sandboxOf":%s}"""
        .formatted(
            Ids.newId(),
            tenantId,
            tenantId,
            Instant.now(),
            ownerUserId,
            esc(name),
            esc(country),
            esc(currency),
            esc(mode),
            sandboxOf == null ? "null" : "\"" + sandboxOf + "\"");
  }

  /**
   * Re-announces a tenant's declared trading currency.
   *
   * <p>Separate from {@link #tenantCreated} on purpose. The currency is also carried on
   * TenantCreated, but that event is consumed by iam-svc to provision the owner's user record, so
   * replaying it to fix a currency projection would re-run unrelated onboarding work — the
   * consumer-side dedupe is keyed on eventId, and a replay necessarily carries a fresh one. This
   * event states one fact and nothing else, so it is safe to emit as often as needed.
   */
  static String tenantCurrencyDeclared(UUID tenantId, String currency) {
    return """
                {"eventId":"%s","eventType":"TenantCurrencyDeclared","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "currency":"%s"}"""
        .formatted(Ids.newId(), tenantId, tenantId, Instant.now(), esc(currency));
  }

  static String storeCreated(
      UUID tenantId, UUID storeId, String code, String type, boolean isDefault) {
    return """
                {"eventId":"%s","eventType":"StoreCreated","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "code":"%s","type":"%s","isDefault":%s}"""
        .formatted(Ids.newId(), tenantId, storeId, Instant.now(), esc(code), esc(type), isDefault);
  }

  static String zoneCreated(UUID tenantId, UUID storeId, UUID zoneId, String code, String type) {
    return """
                {"eventId":"%s","eventType":"ZoneCreated","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "storeId":"%s","code":"%s","type":"%s"}"""
        .formatted(Ids.newId(), tenantId, zoneId, Instant.now(), storeId, esc(code), esc(type));
  }

  /**
   * A staff role bound at a store. {@code role} is the tier iam-svc binds; {@code roleCode} and
   * {@code permissions} ride beside it when the assignment was made through a custom role (20.10).
   */
  /**
   * What an hour of work cost a store, for a labour report.
   *
   * <p>Carries the <b>store, the day and the money</b> — and deliberately <b>not the person</b>. A
   * labour figure is a fact about a shop's Saturday; who earned what is this service's business and
   * nobody else's, and an event naming both would put pay data in every consumer's database for
   * ever.
   *
   * @param supersedes the entry this correction replaces, so a reader can take the old figure back
   *     out rather than count the day twice
   * @param cost null when no rate was in force on the day, which a reader reports as unknown rather
   *     than as zero: zero is a real rate somebody may be on
   */
  static String labourRecorded(
      UUID tenantId,
      UUID storeId,
      UUID entryId,
      UUID supersedes,
      java.time.LocalDate day,
      long minutes,
      java.math.BigDecimal cost,
      String currency) {
    return """
                {"eventId":"%s","eventType":"LabourRecorded","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "storeId":"%s","day":"%s","minutes":%s,"cost":%s,"currency":%s,"supersedes":%s}"""
        .formatted(
            Ids.newId(),
            tenantId,
            entryId,
            Instant.now(),
            storeId,
            day,
            minutes,
            cost == null ? "null" : cost.toPlainString(),
            currency == null ? "null" : "\"" + esc(currency) + "\"",
            supersedes == null ? "null" : "\"" + supersedes + "\"");
  }

  static String staffAssigned(
      UUID tenantId,
      UUID userId,
      UUID storeId,
      String baseTier,
      String roleCode,
      java.util.Set<String> permissions,
      Instant roleUpdatedAt) {
    String custom =
        roleCode == null
            ? ""
            : ",\"roleCode\":\""
                + esc(roleCode)
                + "\",\"permissions\":"
                + array(permissions)
                + ",\"roleUpdatedAt\":\""
                + roleUpdatedAt
                + "\"";
    return """
                {"eventId":"%s","eventType":"StaffAssigned","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "userId":"%s","storeId":"%s","role":"%s"%s}"""
        .formatted(
            Ids.newId(), tenantId, userId, Instant.now(), userId, storeId, esc(baseTier), custom);
  }

  /** A staff role taken away at a store (SJ-D51): iam-svc unbinds it. */
  static String staffRemoved(UUID tenantId, UUID userId, UUID storeId, String baseTier) {
    return """
                {"eventId":"%s","eventType":"StaffRemoved","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "userId":"%s","storeId":"%s","role":"%s"}"""
        .formatted(Ids.newId(), tenantId, userId, Instant.now(), userId, storeId, esc(baseTier));
  }

  /** A custom role defined or redefined (20.10): iam-svc applies it to the role's holders. */
  static String roleDefined(
      UUID tenantId,
      String code,
      String baseTier,
      java.util.Set<String> permissions,
      Instant updatedAt) {
    // updatedAt is the role's own version: two redefinitions in one second may reach iam-svc in
    // either order, and the older must not overwrite the newer.
    return """
                {"eventId":"%s","eventType":"RoleDefined","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "code":"%s","baseTier":"%s","permissions":%s,"updatedAt":"%s"}"""
        .formatted(
            Ids.newId(),
            tenantId,
            tenantId,
            Instant.now(),
            esc(code),
            esc(baseTier),
            array(permissions),
            updatedAt);
  }

  private static String array(java.util.Set<String> values) {
    return values.stream()
        .sorted()
        .map(v -> "\"" + esc(v) + "\"")
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }

  static String tenantStatusChanged(UUID tenantId, String status) {
    return """
                {"eventId":"%s","eventType":"TenantStatusChanged","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "status":"%s"}"""
        .formatted(Ids.newId(), tenantId, tenantId, Instant.now(), esc(status));
  }

  /**
   * A store's status, and its type beside it (STORE, WAREHOUSE or DARK_STORE): iam-svc keeps the
   * type so no till opens at a dark store (ship-from-store and dark-store picking).
   */
  static String storeStatusChanged(UUID tenantId, UUID storeId, String status, String type) {
    return """
                {"eventId":"%s","eventType":"StoreStatusChanged","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "storeId":"%s","status":"%s","type":"%s"}"""
        .formatted(Ids.newId(), tenantId, storeId, Instant.now(), storeId, esc(status), esc(type));
  }

  static String userRoleGranted(UUID tenantId, UUID userId, String role) {
    return """
                {"eventId":"%s","eventType":"UserRoleGranted","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "userId":"%s","role":"%s"}"""
        .formatted(Ids.newId(), tenantId, userId, Instant.now(), userId, esc(role));
  }

  /** A business's data is due for erasure: every service erases what it holds (21.14). */
  static String tenantDataErasureDue(UUID eventId, UUID tenantId, UUID switchId, String intent) {
    return String.format(
        "{\"eventId\":\"%s\",\"eventType\":\"TenantDataErasureDue\",\"tenantId\":\"%s\","
            + "\"aggregateId\":\"%s\",\"occurredAt\":\"%s\",\"switchId\":\"%s\","
            + "\"intent\":\"%s\"}",
        eventId, tenantId, tenantId, java.time.Instant.now(), switchId, intent);
  }

  /**
   * A task that fell due and was never done (store operations & workforce).
   *
   * <p>Carries the store, the day, what the task was and when it was due — and no person, because
   * nobody did it; that is the point. A missed closing check is what a manager has to hear about.
   */
  static String storeTaskMissed(
      UUID tenantId,
      UUID storeId,
      UUID instanceId,
      String title,
      String kind,
      java.time.LocalDate businessDate,
      Instant dueAt,
      boolean required) {
    return """
                {"eventId":"%s","eventType":"StoreTaskMissed","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "storeId":"%s","title":"%s","kind":"%s","businessDate":"%s","dueAt":"%s","required":%s}"""
        .formatted(
            Ids.newId(),
            tenantId,
            instanceId,
            Instant.now(),
            storeId,
            esc(title),
            esc(kind),
            businessDate,
            dueAt,
            required);
  }

  /**
   * A notice published to a store (store operations & workforce): one per store it reaches, so the
   * store's devices can be told without notification-svc knowing which stores a business has.
   *
   * @param wake whether the devices should be woken now — only an urgent notice is
   */
  static String storeBroadcastPublished(
      UUID tenantId,
      UUID storeId,
      UUID broadcastId,
      String title,
      String priority,
      boolean requiresAck,
      boolean wake) {
    return """
                {"eventId":"%s","eventType":"StoreBroadcastPublished","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "storeId":"%s","title":"%s","priority":"%s","requiresAck":%s,"wake":%s}"""
        .formatted(
            Ids.newId(),
            tenantId,
            broadcastId,
            Instant.now(),
            storeId,
            esc(title),
            esc(priority),
            requiresAck,
            wake);
  }

  /**
   * A word to a business about its trial (21.13): that it ends on a day and what the plan will then
   * cost, or that it has ended and here is the first invoice with the link that pays it.
   *
   * @param stage {@code ENDING} or {@code ENDED}
   * @param invoice the first invoice, on {@code ENDED}; null before
   * @param payUrl the link that pays it, on {@code ENDED}; null before
   */
  static String trialNoticeIssued(
      Subscription s,
      String stage,
      String planName,
      String recipient,
      String platformName,
      Invoice invoice,
      String payUrl) {
    return """
                {"eventId":"%s","eventType":"TrialNoticeIssued","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "subscriptionId":"%s","stage":"%s","plan":"%s","trialEnd":%s,"price":%s,"currency":"%s",\
                "interval":"%s","recipient":"%s","platform":"%s","invoiceId":%s,"invoiceNumber":%s,\
                "amountDue":%s,"dueDate":%s,"payUrl":%s}"""
        .formatted(
            Ids.newId(),
            s.tenantId(),
            s.id(),
            Instant.now(),
            s.id(),
            esc(stage),
            esc(planName),
            s.trialEnd() == null ? "null" : "\"" + s.trialEnd() + "\"",
            s.priceAmount().toPlainString(),
            esc(s.currency()),
            esc(s.billingInterval()),
            esc(recipient),
            esc(platformName),
            invoice == null ? "null" : "\"" + invoice.id() + "\"",
            invoice == null ? "null" : "\"" + esc(invoice.number()) + "\"",
            invoice == null
                ? "null"
                : invoice.totalAmount().subtract(invoice.amountPaid()).toPlainString(),
            invoice == null ? "null" : "\"" + invoice.dueDate() + "\"",
            payUrl == null ? "null" : "\"" + esc(payUrl) + "\"");
  }

  /**
   * A dunning notice to be written and sent (21.12): the invoice, what is left on it, the day it
   * was due, the step this notice is, the day the service is interrupted if it stays unpaid, and
   * the link that pays it without a sign-in.
   *
   * <p>The address and the link travel in this event and are held nowhere else in the clear: this
   * service keeps the link only as a hash, and notification-svc is the one reader of the address.
   *
   * @param suspendOn the day the service is interrupted unless paid, or null once it has been
   */
  static String dunningNoticeIssued(
      UUID tenantId,
      Invoice invoice,
      String step,
      int daysOverdue,
      String recipient,
      String payUrl,
      String platformName,
      LocalDate suspendOn) {
    return """
                {"eventId":"%s","eventType":"DunningNoticeIssued","tenantId":"%s","aggregateId":"%s","occurredAt":"%s",\
                "invoiceId":"%s","invoiceNumber":"%s","step":"%s","daysOverdue":%d,"dueDate":"%s",\
                "currency":"%s","amountDue":%s,"recipient":"%s","payUrl":"%s","platform":"%s","suspendOn":%s}"""
        .formatted(
            Ids.newId(),
            tenantId,
            invoice.id(),
            Instant.now(),
            invoice.id(),
            esc(invoice.number()),
            esc(step),
            daysOverdue,
            invoice.dueDate(),
            esc(invoice.currency()),
            invoice.totalAmount().subtract(invoice.amountPaid()).toPlainString(),
            esc(recipient),
            esc(payUrl),
            esc(platformName),
            suspendOn == null ? "null" : "\"" + suspendOn + "\"");
  }
}
