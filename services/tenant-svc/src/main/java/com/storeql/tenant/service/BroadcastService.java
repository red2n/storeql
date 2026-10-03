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
import java.util.Set;
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
    String upper = requirePublishable(title, body, priority, expiresAt, now);
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

  /**
   * A notice that tells somebody something, judged before anything else is, so the request is
   * refused as a request whoever sends it and wherever it is addressed.
   *
   * @return the priority as kept, upper case
   * @throws ApiException 400 {@code BROADCAST_INVALID}
   */
  public static String requirePublishable(
      String title, String body, String priority, Instant expiresAt, Instant now) {
    String upper = priority == null ? null : priority.strip().toUpperCase(Locale.ROOT);
    String problem = Broadcasts.problem(title, body, upper, now, expiresAt);
    if (problem != null) throw ApiException.badRequest("BROADCAST_INVALID", problem);
    return upper;
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

  /**
   * The business's notices, newest first.
   *
   * @param stores the caller's stores ({@code TenantContext.reportStores(null)}): their own notices
   *     and those to every store; null for a caller held to none, who reads them all
   */
  public List<Broadcast> broadcasts(
      UUID tenantId, boolean publishedOnly, Integer limit, Set<UUID> stores) {
    int clamped = limit == null ? 20 : Math.max(1, Math.min(MAX_LIMIT, limit));
    return repo.broadcasts(tenantId, publishedOnly, clamped, stores);
  }

  public Broadcast broadcast(UUID tenantId, UUID id) {
    return requireBroadcast(tenantId, id);
  }

  /**
   * How far a notice has reached: per store, how many it is addressed to, how many acknowledged,
   * and who has not — named, because "three of nine" is a number and a manager needs a name.
   *
   * @param stores the caller's stores, whose rows alone are answered (a notice to every store, read
   *     by a manager of one branch, reaches that branch and names nobody elsewhere); null for a
   *     caller held to none. Whether the caller may read the notice at all is the resource's call,
   *     made before this.
   */
  public List<Reach> reach(UUID tenantId, UUID id, Set<UUID> stores) {
    Broadcast b = requireBroadcast(tenantId, id);
    Map<UUID, Instant> acked = repo.acks(tenantId, id);
    Map<UUID, List<Staff>> byStore = new LinkedHashMap<>();
    for (Staff s : repo.staff(tenantId, b.storeId())) {
      if (stores != null && !stores.contains(s.storeId())) continue;
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
   * The store a notice is published to, read at or acknowledged at, so the caller can be judged
   * against it before anything else: it must be the business's — another business's staff naming
   * our store, even among their own store ids, are told it does not exist — and the caller then
   * holds it or not (the resource asks).
   *
   * @throws ApiException 404 {@code STORE_NOT_FOUND}
   */
  public UUID requireStore(UUID tenantId, UUID storeId) {
    if (!repo.isStore(tenantId, storeId)) {
      throw ApiException.notFound("STORE_NOT_FOUND", "no such store");
    }
    return storeId;
  }

  /**
   * The notices current for one person at a store, newest first, each with whether they have
   * acknowledged it.
   *
   * @throws ApiException 409 {@code WORKFORCE_NOT_ASSIGNED} for somebody not on that store's staff
   */
  public List<Seen> current(UUID tenantId, UUID storeId, UUID userId) {
    return current(tenantId, storeId, userId, List.of());
  }

  /**
   * {@link #current(UUID, UUID, UUID)}, for a caller who may act at any of the business's stores.
   *
   * @param anywhereAs the management tiers (OWNER, MANAGER) a caller held to no store holds — an
   *     owner, a business-wide manager — who reads a store's notices without being assigned there,
   *     addressed by those tiers as well as by any role they hold at the store; empty holds the
   *     caller to the stores they are assigned at ({@code 409 WORKFORCE_NOT_ASSIGNED} elsewhere)
   */
  public List<Seen> current(UUID tenantId, UUID storeId, UUID userId, List<String> anywhereAs) {
    List<String> roles = requireRoles(tenantId, userId, storeId, anywhereAs);
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
    return acknowledge(tenantId, id, storeId, userId, List.of());
  }

  /**
   * {@link #acknowledge(UUID, UUID, UUID, UUID)}, for a caller who may act at any store.
   *
   * @param anywhereAs as {@link #current(UUID, UUID, UUID, List)}
   */
  public Ack acknowledge(
      UUID tenantId, UUID id, UUID storeId, UUID userId, List<String> anywhereAs) {
    Broadcast b = requireBroadcast(tenantId, id);
    List<String> roles = requireRoles(tenantId, userId, storeId, anywhereAs);
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

  /**
   * The roles a person is addressed by at a store: what they are assigned there, and — for a caller
   * who acts at any store — the management tiers they hold business-wide.
   *
   * @throws ApiException 409 {@code WORKFORCE_NOT_ASSIGNED} for somebody held to where they are
   *     assigned and not assigned there
   */
  private List<String> requireRoles(
      UUID tenantId, UUID userId, UUID storeId, List<String> anywhereAs) {
    List<String> roles = repo.rolesAt(tenantId, userId, storeId);
    if (anywhereAs.isEmpty()) {
      if (roles.isEmpty()) {
        throw ApiException.conflict(
            "WORKFORCE_NOT_ASSIGNED", "that person is not assigned to that store");
      }
      return roles;
    }
    List<String> held = new ArrayList<>(roles);
    for (String tier : anywhereAs) {
      if (!held.contains(tier)) held.add(tier);
    }
    return List.copyOf(held);
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
