package com.storeql.order.service;

import com.storeql.order.domain.Domain.AuditEvent;
import com.storeql.order.repo.AuditTrailRepository;
import com.storeql.web.ApiException;
import com.storeql.web.Cursor;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The business audit trail (20.11): who discounted, voided, opened the drawer, cancelled, took
 * goods back, or sold offline what a recall or an unfit scale would have stopped, read as one
 * stream. Validates what the caller asked for and turns the repository's rows into a cursor page;
 * the rows themselves are the append-only logs, untouched.
 */
@ApplicationScoped
public class AuditTrailService {

  @Inject AuditTrailRepository repo;

  /**
   * One page of the trail, newest first.
   *
   * @param ctx the caller: supplies the tenant, and the stores they are limited to, if any
   * @param store one store id, or {@code null}
   * @param actor one staff user id, or {@code null}
   * @param type one of {@link AuditEvent#TYPES}, in any case, or {@code null} for all
   * @param from inclusive lower bound, or {@code null}
   * @param to exclusive upper bound, or {@code null}
   * @param after the previous page's cursor, or {@code null}
   * @param limit the page size, already clamped
   * @return the page and the cursor for the next one
   * @throws ApiException {@code 400} for a store or actor that is not a UUID, an unknown type
   *     ({@code AUDIT_TYPE_UNKNOWN}), a period that ends before it starts ({@code
   *     AUDIT_RANGE_EMPTY}) or a malformed cursor ({@code INVALID_CURSOR}); {@code 403} for a store
   *     outside the caller's scope
   */
  public Cursor.Page<AuditEvent> list(
      TenantContext ctx,
      String store,
      String actor,
      String type,
      Instant from,
      Instant to,
      String after,
      int limit) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = Parsing.optionalUuid(store, "store");
    if (storeId != null) ctx.requireStoreAccess(storeId);
    UUID actorId = Parsing.optionalUuid(actor, "actor");
    String typeFilter = null;
    if (type != null && !type.isBlank()) {
      typeFilter = type.trim().toUpperCase(Locale.ROOT);
      if (!AuditEvent.TYPES.contains(typeFilter)) {
        throw ApiException.badRequest(
            "AUDIT_TYPE_UNKNOWN",
            "type is one of " + String.join(", ", AuditEvent.TYPES) + ", not " + type);
      }
    }
    if (from != null && to != null && !from.isBefore(to)) {
      throw ApiException.badRequest("AUDIT_RANGE_EMPTY", "from must be before to");
    }
    Cursor.CreatedAtId key = Cursor.decodeCreatedAtId(after);
    List<AuditEvent> rows =
        repo.list(
            tenantId,
            storeId,
            ctx.storeIds(),
            actorId,
            typeFilter,
            from,
            to,
            key == null ? null : key.createdAt(),
            key == null ? null : key.id(),
            limit + 1);
    return Cursor.page(rows, limit, r -> r.occurredAt().toString() + "|" + r.id());
  }
}
