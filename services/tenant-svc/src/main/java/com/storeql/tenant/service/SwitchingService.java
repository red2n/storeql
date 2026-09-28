package com.storeql.tenant.service;

import com.storeql.ids.Ids;
import com.storeql.service.OutboxRow;
import com.storeql.service.TenantDataErasureHandler;
import com.storeql.tenant.domain.Domain.Tenant;
import com.storeql.tenant.domain.Switching;
import com.storeql.tenant.domain.Switching.Dates;
import com.storeql.tenant.domain.Switching.Evidence;
import com.storeql.tenant.domain.Switching.Stage;
import com.storeql.tenant.domain.Switching.Switch;
import com.storeql.tenant.repo.SwitchingRepository;
import com.storeql.tenant.repo.TenantRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A business leaving (21.14, EU Data Act art.25): the notice its owner gives, the one extension, a
 * withdrawal while notice runs, the sweep that starts each erasure when it falls due, and the
 * evidence every service sends back of what it erased.
 */
@ApplicationScoped
public class SwitchingService {

  static final String ERASURE_TOPIC = "storeql.tenant.tenant-data-erasure-due";
  private static final Logger LOG = System.getLogger(SwitchingService.class.getName());

  @Inject SwitchingRepository repo;
  @Inject TenantRepository tenants;

  @Inject
  @ConfigProperty(
      name = "storeql.switching.services",
      defaultValue =
          "iam-svc,tenant-svc,product-svc,inventory-svc,order-svc,cart-svc,pricing-svc,"
              + "payment-svc,purchase-svc,customer-svc,notification-svc,reporting-svc")
  List<String> services;

  Clock clock = Clock.systemUTC();

  /** A notice and where it stands, with what each service has erased so far. */
  public record Status(
      Switch notice,
      Stage stage,
      List<Evidence> evidence,
      List<String> services,
      List<String> awaiting) {}

  /** The business's latest notice, if it has given one. */
  public Optional<Status> status(UUID tenantId) {
    return repo.latest(tenantId).map(this::statusOf);
  }

  /**
   * The business's latest notice.
   *
   * @throws ApiException 404 {@code SWITCHING_NO_NOTICE} when it has given none
   */
  public Status requireStatus(UUID tenantId) {
    return status(tenantId)
        .orElseThrow(
            () ->
                ApiException.notFound("SWITCHING_NO_NOTICE", "this business has given no notice"));
  }

  /**
   * Gives notice.
   *
   * @throws ApiException 404 {@code TENANT_NOT_FOUND}; 400 for the rules' refusals; 409 {@code
   *     SWITCHING_NOTICE_ALREADY_GIVEN}
   */
  public Status give(UUID tenantId, UUID actor, String intent, String noticeEndsOn) {
    tenants
        .findTenant(tenantId)
        .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
    LocalDate ends = date(noticeEndsOn, "SWITCHING_NOTICE_INVALID");
    Dates d = rules(() -> Switching.forNotice(intent, today(), ends), 400);
    repo.insert(Switching.notice(Ids.newId(), tenantId, intent, actor, d));
    return status(tenantId).orElseThrow();
  }

  /**
   * Extends the transitional period, once.
   *
   * @throws ApiException 404 {@code SWITCHING_NO_NOTICE}; 400 or 409 for the rules' refusals
   */
  public Status extend(UUID tenantId, UUID actor, String transitionEndsOn) {
    Switch s = latest(tenantId);
    LocalDate ends = date(transitionEndsOn, "SWITCHING_EXTENSION_INVALID");
    Dates d = rules(() -> Switching.extended(s, today(), ends), 409);
    if (!repo.extend(tenantId, s.id(), d, actor)) {
      throw ApiException.conflict(
          "SWITCHING_ALREADY_EXTENDED", "the transitional period has already been extended");
    }
    return status(tenantId).orElseThrow();
  }

  /**
   * Withdraws the notice while it runs.
   *
   * @throws ApiException 404 {@code SWITCHING_NO_NOTICE}; 409 for the rules' refusals
   */
  public Status cancel(UUID tenantId, UUID actor, String reason) {
    Switch s = latest(tenantId);
    rules(
        () -> {
          Switching.cancellable(s, today());
          return s;
        },
        409);
    if (!repo.cancel(tenantId, s.id(), actor, reason.trim())) {
      throw ApiException.conflict("SWITCHING_CLOSED", "this notice is no longer running");
    }
    return status(tenantId).orElseThrow();
  }

  /**
   * Starts every erasure due today: the business made inactive, and {@code TenantDataErasureDue}
   * published for every service to erase its data.
   *
   * @return how many erasures this sweep started
   */
  public int sweep() {
    int started = 0;
    for (Switch s : repo.due(today())) {
      UUID eventId = Ids.newId();
      OutboxRow status =
          new OutboxRow(
              "TenantStatusChanged",
              "storeql.tenant.tenant-status-changed",
              s.tenantId(),
              s.tenantId(),
              Events.tenantStatusChanged(s.tenantId(), Tenant.STATUS_INACTIVE));
      OutboxRow due =
          new OutboxRow(
              TenantDataErasureHandler.DUE,
              ERASURE_TOPIC,
              s.tenantId(),
              s.tenantId(),
              Events.tenantDataErasureDue(eventId, s.tenantId(), s.id(), s.intent()));
      if (repo.startErasure(s, eventId, Tenant.STATUS_INACTIVE, status, due)) {
        started++;
        LOG.log(Level.INFO, "Erasure {0} started for tenant {1}", eventId, s.tenantId());
      }
    }
    return started;
  }

  /**
   * Records a service's {@code TenantDataErased}, once per announcement. A payload that is
   * malformed or answers no erasure this service started is skipped with a warning.
   *
   * @return whether it was recorded now
   */
  public boolean recordErased(String json) {
    UUID tenantId;
    UUID erasureEventId;
    Evidence evidence;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject o = reader.readObject();
      tenantId = Ids.parse(o.getString("tenantId"));
      erasureEventId = Ids.parse(o.getString("erasureEventId"));
      evidence =
          new Evidence(
              Ids.parse(o.getString("eventId")),
              erasureEventId,
              o.getString("service"),
              o.getInt("rows"),
              o.getJsonObject("tables").toString(),
              Instant.parse(o.getString("occurredAt")),
              null);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed TenantDataErased payload skipped: " + e.getMessage());
      return false;
    }
    if (evidence.rowsErased() < 0
        || evidence.service().isBlank()
        || evidence.service().length() > 60) {
      LOG.log(Level.WARNING, "TenantDataErased with nonsense skipped: {0}", evidence.service());
      return false;
    }
    Optional<Switch> s = repo.byErasureEvent(tenantId, erasureEventId);
    if (s.isEmpty()) {
      // A sandbox removed (22.8) is erased the same way but gave no notice: nothing to record.
      if (tenants.findTenant(tenantId).map(Tenant::isSandbox).orElse(false)) {
        LOG.log(Level.INFO, "Sandbox {0} erased by {1}", tenantId, evidence.service());
        return false;
      }
      LOG.log(Level.WARNING, "TenantDataErased for no erasure started here: {0}", erasureEventId);
      return false;
    }
    return repo.recordEvidence(tenantId, s.get().id(), evidence);
  }

  private Status statusOf(Switch s) {
    List<Evidence> evidence =
        s.erasureEventId() == null ? List.of() : repo.evidence(s.tenantId(), s.id());
    Set<String> reported = evidence.stream().map(Evidence::service).collect(Collectors.toSet());
    Set<String> expected = new TreeSet<>(services);
    List<String> awaiting =
        s.erasureEventId() == null
            ? List.of()
            : expected.stream().filter(svc -> !reported.contains(svc)).toList();
    return new Status(
        s,
        Switching.stage(s, today(), reported, expected),
        evidence,
        List.copyOf(expected),
        awaiting);
  }

  private Switch latest(UUID tenantId) {
    return repo.latest(tenantId)
        .orElseThrow(
            () ->
                ApiException.notFound("SWITCHING_NO_NOTICE", "this business has given no notice"));
  }

  private LocalDate today() {
    return LocalDate.now(clock);
  }

  private static LocalDate date(String value, String code) {
    try {
      return value == null ? null : LocalDate.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new ApiException(400, code, "a date is YYYY-MM-DD", List.of(), e);
    }
  }

  /** Refusals a caller mends by changing what it sent; every other one is about the moment. */
  private static final Set<String> BAD_REQUESTS =
      Set.of(
          "SWITCHING_INTENT_UNKNOWN",
          "SWITCHING_NOTICE_INVALID",
          "SWITCHING_NOTICE_TOO_LONG",
          "SWITCHING_EXTENSION_INVALID",
          "SWITCHING_EXTENSION_TOO_LONG");

  private static <T> T rules(Supplier<T> step, int status) {
    try {
      return step.get();
    } catch (Switching.Refused r) {
      throw new ApiException(
          BAD_REQUESTS.contains(r.code()) ? 400 : status, r.code(), r.getMessage(), List.of(), r);
    }
  }
}
