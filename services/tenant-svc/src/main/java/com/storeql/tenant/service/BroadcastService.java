package com.storeql.tenant.service;

import com.storeql.ids.Ids;
import com.storeql.service.OutboxRow;
import com.storeql.tenant.domain.Broadcasts;
import com.storeql.tenant.domain.Broadcasts.Ack;
import com.storeql.tenant.domain.Broadcasts.Broadcast;
import com.storeql.tenant.domain.Broadcasts.Reach;
import com.storeql.tenant.repo.BroadcastRepository;
import com.storeql.tenant.repo.BroadcastRepository.Staff;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Notices from management to the shop floor (store operations & workforce).
 *
 * <p>Publishing is management's; reading and acknowledging is the staff's own, outside {@code
 * /admin/} as the clock and the task list are. A notice is never edited — what was acknowledged is
 * what was seen — and it is announced to the store's devices in the same transaction it is
 * published in, once per store it reaches, with only an urgent one waking them.
 */
@ApplicationScoped
public class BroadcastService {

  private static final int MAX_LIMIT = 100;

  @Inject BroadcastRepository repo;

  /** A notice as one person sees it: with whether they have acknowledged it. */
  public record Seen(Broadcast broadcast, Instant acknowledgedAt) {}

  /**
   * Publishes a notice and announces it to every store it reaches.
   *
   * @param storeId one store, or null for every open store
   * @throws ApiException 400 {@code BROADCAST_INVALID}; 404 when the store is not this business's
   */
  public Broadcast publish(
      UUID tenantId,
      String title,
      String body,
      String priority,
      UUID storeId,
      String role,
      boolean requiresAck,
      Instant expiresAt,
      UUID actorId) {
    // Microseconds, because that is what the record keeps: an answer that said nanoseconds would
    // disagree with the next read of the same notice by the digits the database never held.
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    String upper = priority == null ? null : priority.strip().toUpperCase(Locale.ROOT);
    String problem = Broadcasts.problem(title, body, upper, now, expiresAt);
    if (problem != null) throw ApiException.badRequest("BROADCAST_INVALID", problem);
    List<UUID> stores = storeId == null ? repo.openStores(tenantId) : List.of(storeId);
    if (storeId != null && !repo.openStores(tenantId).contains(storeId)) {
      throw ApiException.notFound("STORE_NOT_FOUND", "no such store");
    }
    Broadcast b =
        new Broadcast(
            Ids.newId(),
            tenantId,
            title.strip(),
            body.strip(),
            upper,
            storeId,
            role == null || role.isBlank() ? null : role.strip().toUpperCase(Locale.ROOT),
            requiresAck,
            now,
            expiresAt,
            Broadcasts.PUBLISHED,
            actorId,
            null,
            null,
            null);
    List<OutboxRow> announcements = new ArrayList<>();
    for (UUID reached : stores) announcements.add(announce(b, reached));
    repo.publish(b, announcements);
    return b;
  }

  private static OutboxRow announce(Broadcast b, UUID storeId) {
    return new OutboxRow(
        "StoreBroadcastPublished",
        "storeql.tenant.store-broadcast-published",
        b.tenantId(),
        b.id(),
        Events.storeBroadcastPublished(
            b.tenantId(),
            storeId,
            b.id(),
            b.title(),
            b.priority(),
            b.requiresAck(),
            b.wakesDevices()));
  }

  /**
   * Withdraws a notice, with the reason.
   *
   * @throws ApiException 400 without a reason; 404; 409 when it was already withdrawn
   */
  public Broadcast withdraw(UUID tenantId, UUID id, String reason, UUID actorId) {
    String why = require(reason, "BROADCAST_REASON_REQUIRED", "say why the notice is withdrawn");
    Broadcast b = requireBroadcast(tenantId, id);
    if (!repo.withdraw(tenantId, id, actorId, why)) {
      throw ApiException.conflict("BROADCAST_WITHDRAWN", "that notice was already withdrawn");
    }
    return repo.broadcast(tenantId, id).orElse(b);
  }

  public List<Broadcast> broadcasts(UUID tenantId, boolean publishedOnly, Integer limit) {
    int clamped = limit == null ? 20 : Math.max(1, Math.min(MAX_LIMIT, limit));
    return repo.broadcasts(tenantId, publishedOnly, clamped);
  }

  public Broadcast broadcast(UUID tenantId, UUID id) {
    return requireBroadcast(tenantId, id);
  }

  /**
   * How far a notice has reached: per store, how many it is addressed to, how many acknowledged,
   * and who has not — named, because "three of nine" is a number and a manager needs a name.
   */
  public List<Reach> reach(UUID tenantId, UUID id) {
    Broadcast b = requireBroadcast(tenantId, id);
    Map<UUID, Instant> acked = repo.acks(tenantId, id);
    Map<UUID, List<Staff>> byStore = new LinkedHashMap<>();
    for (Staff s : repo.staff(tenantId, b.storeId())) {
      if (b.addressedTo(s.storeId(), s.roles())) {
        byStore.computeIfAbsent(s.storeId(), k -> new ArrayList<>()).add(s);
      }
    }
    List<Reach> out = new ArrayList<>();
    for (Map.Entry<UUID, List<Staff>> store : byStore.entrySet()) {
      List<UUID> outstanding = new ArrayList<>();
      int acknowledged = 0;
      for (Staff s : store.getValue()) {
        if (acked.containsKey(s.userId())) acknowledged++;
        else outstanding.add(s.userId());
      }
      out.add(new Reach(store.getKey(), store.getValue().size(), acknowledged, outstanding));
    }
    return List.copyOf(out);
  }

  /**
   * The notices current for one person at a store, newest first, each with whether they have
   * acknowledged it.
   *
   * @throws ApiException 409 {@code WORKFORCE_NOT_ASSIGNED} for somebody not on that store's staff
   */
  public List<Seen> current(UUID tenantId, UUID storeId, UUID userId) {
    List<String> roles = requireRoles(tenantId, userId, storeId);
    Instant now = Instant.now();
    Map<UUID, Instant> mine = repo.ackTimesBy(tenantId, userId);
    List<Seen> out = new ArrayList<>();
    for (Broadcast b : repo.publishedFor(tenantId, storeId)) {
      if (!b.currentAt(now) || !b.addressedTo(storeId, roles)) continue;
      out.add(new Seen(b, mine.get(b.id())));
    }
    return List.copyOf(out);
  }

  /**
   * Acknowledges a notice.
   *
   * @throws ApiException 404; 409 {@code BROADCAST_NOT_CURRENT} for one withdrawn or expired,
   *     {@code BROADCAST_NOT_ADDRESSED} for one not meant for this person here, {@code
   *     BROADCAST_ALREADY_ACKNOWLEDGED} for a second tap
   */
  public Ack acknowledge(UUID tenantId, UUID id, UUID storeId, UUID userId) {
    Broadcast b = requireBroadcast(tenantId, id);
    List<String> roles = requireRoles(tenantId, userId, storeId);
    if (!b.currentAt(Instant.now())) {
      throw ApiException.conflict(
          "BROADCAST_NOT_CURRENT", "that notice is withdrawn or has expired");
    }
    if (!b.addressedTo(storeId, roles)) {
      throw ApiException.conflict(
          "BROADCAST_NOT_ADDRESSED", "that notice is not addressed to you at that store");
    }
    return repo.acknowledge(
        new Ack(
            Ids.newId(),
            tenantId,
            id,
            userId,
            storeId,
            Instant.now().truncatedTo(ChronoUnit.MICROS)));
  }

  private List<String> requireRoles(UUID tenantId, UUID userId, UUID storeId) {
    List<String> roles = repo.rolesAt(tenantId, userId, storeId);
    if (roles.isEmpty()) {
      throw ApiException.conflict(
          "WORKFORCE_NOT_ASSIGNED", "that person is not assigned to that store");
    }
    return roles;
  }

  private Broadcast requireBroadcast(UUID tenantId, UUID id) {
    return repo.broadcast(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("BROADCAST_NOT_FOUND", "no such notice"));
  }

  private static String require(String value, String code, String message) {
    if (value == null || value.isBlank()) throw ApiException.badRequest(code, message);
    return value.strip();
  }
}
