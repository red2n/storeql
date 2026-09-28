package com.storeql.order.service;

import com.storeql.ids.Ids;
import com.storeql.order.domain.Windows;
import com.storeql.order.repo.FulfilmentWindowRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Delivery and collection slots (intent/delivery-and-collection-slots.md): a store's windows
 * (management), the storefront's read of the next seven days, and the resolution a checkout asks
 * for before it may take a place — the capacity claim itself is {@link
 * com.storeql.order.repo.OrderRepository}'s, on the order's own transaction, so it is never asked
 * of a window a checkout will not actually try to take.
 */
@ApplicationScoped
public class FulfilmentWindowService {

  @Inject FulfilmentWindowRepository repo;
  @Inject TenantProfiles profiles;

  // ── Management ───────────────────────────────────────────────────────────────

  /** A store's windows, every type and weekday, as the admin screen lists them. */
  public List<Windows.WindowRecord> list(UUID tenantId, TenantContext ctx, UUID storeId) {
    ctx.requireStoreAccess(storeId);
    requireTheBusinesssStore(tenantId, storeId);
    return repo.listByStore(tenantId, storeId);
  }

  /**
   * Sets a new window at a store the caller may act at, of the business's own, whose zone is known
   * — a window with no zone to keep its times in is refused rather than guessed.
   *
   * @throws ApiException 403 {@code STORE_ACCESS_DENIED}; 404 {@code ORDER_SLOT_STORE_NOT_FOUND};
   *     400 {@code ORDER_SLOT_WINDOW_INVALID}
   */
  public Windows.WindowRecord create(
      UUID tenantId,
      TenantContext ctx,
      UUID storeId,
      String fulfilmentType,
      Integer weekday,
      String startTime,
      String endTime,
      Integer capacity,
      Integer cutoffMinutes,
      Boolean active,
      UUID actorId) {
    ctx.requireStoreAccess(storeId);
    requireTheBusinesssStore(tenantId, storeId);
    ZoneId zone = zoneOfStoreOrNull(tenantId, storeId);
    if (zone == null) {
      throw ApiException.badRequest(
          "ORDER_SLOT_WINDOW_INVALID", "the store's time zone is not known; set it before windows");
    }
    Windows.Window candidate =
        new Windows.Window(
            Ids.newId(),
            storeId,
            upper(fulfilmentType),
            weekday == null ? 0 : weekday,
            parseTime(startTime, "startTime"),
            parseTime(endTime, "endTime"),
            capacity == null ? 0 : capacity,
            cutoffMinutes == null ? 0 : cutoffMinutes,
            active == null || active);
    validateAgainstOthers(candidate, repo.listByStore(tenantId, storeId));
    return repo.create(tenantId, candidate, zone.getId(), actorId);
  }

  /**
   * Changes a window's shape — its store and fulfilment type are fixed. Only a manager held to that
   * store, or one held to none, may.
   *
   * @throws ApiException 404 {@code ORDER_SLOT_WINDOW_NOT_FOUND}; 403 {@code STORE_ACCESS_DENIED};
   *     400 {@code ORDER_SLOT_WINDOW_INVALID}
   */
  public Windows.WindowRecord update(
      UUID tenantId,
      TenantContext ctx,
      UUID windowId,
      Integer weekday,
      String startTime,
      String endTime,
      Integer capacity,
      Integer cutoffMinutes,
      Boolean active,
      UUID actorId) {
    Windows.WindowRecord existing =
        repo.find(tenantId, windowId)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "ORDER_SLOT_WINDOW_NOT_FOUND", "no such fulfilment window in this tenant"));
    ctx.requireStoreAccess(existing.window().storeId());
    Windows.Window candidate =
        new Windows.Window(
            windowId,
            existing.window().storeId(),
            existing.window().fulfilmentType(),
            weekday == null ? 0 : weekday,
            parseTime(startTime, "startTime"),
            parseTime(endTime, "endTime"),
            capacity == null ? 0 : capacity,
            cutoffMinutes == null ? 0 : cutoffMinutes,
            active == null || active);
    validateAgainstOthers(candidate, repo.listByStore(tenantId, existing.window().storeId()));
    return repo.update(tenantId, windowId, candidate, actorId);
  }

  private void validateAgainstOthers(
      Windows.Window candidate, List<Windows.WindowRecord> storeWindows) {
    List<Windows.Window> others =
        storeWindows.stream()
            .map(Windows.WindowRecord::window)
            .filter(
                w ->
                    w.fulfilmentType().equals(candidate.fulfilmentType())
                        && w.weekday() == candidate.weekday())
            .toList();
    List<String> problems = Windows.problems(candidate, others);
    if (!problems.isEmpty()) {
      throw new ApiException(
          400, "ORDER_SLOT_WINDOW_INVALID", String.join("; ", problems), problems);
    }
  }

  private static LocalTime parseTime(String value, String field) {
    if (value == null || value.isBlank()) {
      throw ApiException.badRequest("ORDER_SLOT_WINDOW_INVALID", field + " is required (HH:mm)");
    }
    try {
      return LocalTime.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw new ApiException(
          400,
          "ORDER_SLOT_WINDOW_INVALID",
          field + " must be a time as HH:mm: " + value,
          List.of(),
          e);
    }
  }

  /** The tenant's own store, or {@code 404 ORDER_SLOT_STORE_NOT_FOUND}. */
  private void requireTheBusinesssStore(UUID tenantId, UUID storeId) {
    if (storeId == null || !profiles.stores(tenantId, storeId).has(storeId)) {
      throw ApiException.notFound("ORDER_SLOT_STORE_NOT_FOUND", "no such store in this tenant");
    }
  }

  /** The store's own zone, or {@code null} when tenant-svc cannot say (never a platform guess). */
  private ZoneId zoneOfStoreOrNull(UUID tenantId, UUID storeId) {
    try {
      return profiles.stores(tenantId, storeId).zoneOf(storeId);
    } catch (ApiException e) {
      return null;
    }
  }

  // ── Storefront ───────────────────────────────────────────────────────────────

  /** The next seven days of a store's windows of one type, with what each occurrence has left. */
  public record SlotsView(
      UUID storeId,
      String fulfilmentType,
      String timeZone,
      boolean offered,
      List<Windows.Day> days,
      Map<UUID, Integer> capacityByWindow,
      Map<UUID, Map<Instant, Long>> taken) {}

  /**
   * @throws ApiException 400 {@code ORDER_SLOT_STORE_REQUIRED}, {@code ORDER_SLOT_TYPE_INVALID};
   *     404 {@code ORDER_SLOT_STORE_NOT_FOUND}
   */
  public SlotsView slotsFor(UUID tenantId, String storeParam, String typeParam) {
    if (storeParam == null || storeParam.isBlank()) {
      throw ApiException.badRequest("ORDER_SLOT_STORE_REQUIRED", "store is required");
    }
    UUID storeId = com.storeql.web.Parsing.uuid(storeParam, "store");
    String type = typeParam == null ? "" : typeParam.strip().toUpperCase(Locale.ROOT);
    if (!Windows.TYPES.contains(type)) {
      throw ApiException.badRequest("ORDER_SLOT_TYPE_INVALID", "type must be DELIVERY or PICKUP");
    }
    requireTheBusinesssStore(tenantId, storeId);
    List<Windows.WindowRecord> rows = repo.listByStoreAndType(tenantId, storeId, type);
    ZoneId zone = zoneOfStoreOrNull(tenantId, storeId);
    if (zone == null) {
      zone =
          rows.stream()
              .map(r -> zoneOrNull(r.timeZone()))
              .filter(z -> z != null)
              .findFirst()
              .orElse(ZoneId.of("UTC"));
    }
    List<Windows.Window> windows = rows.stream().map(Windows.WindowRecord::window).toList();
    boolean offered = windows.stream().anyMatch(Windows.Window::active);
    Instant now = Instant.now();
    List<Windows.Day> days = Windows.occurrences(windows, zone, now);
    Map<UUID, Integer> capacityByWindow = new HashMap<>();
    for (Windows.Window w : windows) capacityByWindow.put(w.id(), w.capacity());
    Map<UUID, Map<Instant, Long>> taken = new HashMap<>();
    for (var t : repo.takenCounts(tenantId, storeId, type, now)) {
      taken.computeIfAbsent(t.windowId(), k -> new HashMap<>()).put(t.startsAt(), t.taken());
    }
    return new SlotsView(storeId, type, zone.getId(), offered, days, capacityByWindow, taken);
  }

  private static ZoneId zoneOrNull(String id) {
    try {
      return id == null ? null : ZoneId.of(id);
    } catch (DateTimeException e) {
      return null;
    }
  }

  // ── Checkout ─────────────────────────────────────────────────────────────────

  /** The window and occurrence an order is to hold, resolved and validated but not yet claimed. */
  public record ResolvedSlot(UUID windowId, Instant startsAt, Instant endsAt, String timeZone) {}

  /**
   * Resolves and validates the slot a checkout names against the store's windows of this type —
   * everything a checkout must settle before it may try to take a place. Capacity is not checked
   * here: that is {@code FulfilmentWindowRepository.claimTx}'s, locked on the order's own
   * transaction, so a race is decided only once, at the insert.
   *
   * @param slotWindowId the request's {@code slotWindowId}, or null/blank for none
   * @param slotStartsAt the request's {@code slotStartsAt}, or null/blank for none
   * @return the resolved slot, or {@code null} when the store offers no active window of this type
   *     and none was named — a checkout that proceeds exactly as before this feature existed
   * @throws ApiException 400 {@code ORDER_SLOT_REQUIRED}, {@code ORDER_SLOT_UNKNOWN}; 409 {@code
   *     ORDER_SLOT_CLOSED}
   */
  public ResolvedSlot resolveForCheckout(
      UUID tenantId,
      UUID storeId,
      String fulfilmentType,
      String slotWindowId,
      String slotStartsAt) {
    boolean hasWindowId = slotWindowId != null && !slotWindowId.isBlank();
    boolean hasStartsAt = slotStartsAt != null && !slotStartsAt.isBlank();
    if (hasWindowId != hasStartsAt) {
      throw ApiException.badRequest(
          "ORDER_SLOT_UNKNOWN", "slotWindowId and slotStartsAt must both be given, or neither");
    }
    if (!hasWindowId) {
      if (repo.hasActiveWindow(tenantId, storeId, fulfilmentType)) {
        throw ApiException.badRequest(
            "ORDER_SLOT_REQUIRED",
            "this store offers "
                + fulfilmentType.toLowerCase(Locale.ROOT)
                + " windows; choose one (slotWindowId, slotStartsAt)");
      }
      return null;
    }
    UUID windowId = com.storeql.web.Parsing.uuid(slotWindowId, "slotWindowId");
    Instant startsAt = com.storeql.web.Parsing.instant(slotStartsAt, "slotStartsAt");
    Windows.WindowRecord row =
        repo.find(tenantId, windowId)
            .filter(
                r ->
                    r.window().storeId().equals(storeId)
                        && r.window().fulfilmentType().equals(fulfilmentType))
            .orElseThrow(
                () ->
                    ApiException.badRequest(
                        "ORDER_SLOT_UNKNOWN", "no such window at this store and fulfilment type"));
    ZoneId zone = zoneOfStoreOrNull(tenantId, storeId);
    if (zone == null) zone = zoneOrNull(row.timeZone());
    if (zone == null) zone = ZoneId.of("UTC");
    Instant now = Instant.now();
    Windows.Occurrence occurrence =
        Windows.occurrenceForInstant(row.window(), zone, now, startsAt)
            .orElseThrow(
                () ->
                    ApiException.badRequest(
                        "ORDER_SLOT_UNKNOWN",
                        "not an occurrence of that window in the next seven days"));
    if (Windows.pastCutoff(row.window(), occurrence.startsAt(), now)) {
      throw ApiException.conflict(
          "ORDER_SLOT_CLOSED", "this window has closed for new orders — its cut-off has passed");
    }
    return new ResolvedSlot(windowId, occurrence.startsAt(), occurrence.endsAt(), zone.getId());
  }

  private static String upper(String s) {
    if (s == null || s.isBlank()) {
      throw ApiException.badRequest("ORDER_SLOT_WINDOW_INVALID", "fulfilmentType is required");
    }
    return s.strip().toUpperCase(Locale.ROOT);
  }
}
