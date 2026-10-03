package com.storeql.tenant.service;

import com.storeql.tenant.domain.Audit;
import com.storeql.tenant.repo.AuditRepository;
import com.storeql.web.Cursor;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Reads the business's admin change log (who changed a store's status or till setting, who was
 * given or lost a role). The entries themselves are written by the repository that makes each
 * change, on its transaction; nothing here writes.
 */
@ApplicationScoped
public class AuditService {

  @Inject AuditRepository repo;

  /**
   * A page of the caller's business's entries, newest first.
   *
   * <p>A caller held to stores reads the entries at those stores and the business-wide ones,
   * whatever they ask for; naming a store that is not theirs is refused.
   *
   * @throws com.storeql.web.ApiException 400 {@code AUDIT_TYPE_INVALID}, {@code
   *     AUDIT_RANGE_INVALID}, {@code INVALID_UUID}, {@code INVALID_DATE}; 403 {@code
   *     STORE_ACCESS_DENIED}
   */
  public Cursor.Page<Audit.Entry> list(
      TenantContext ctx,
      String type,
      String actor,
      String store,
      String from,
      String to,
      String after,
      int limit) {
    var tenantId = ctx.requireTenantId();
    var storeId = Parsing.optionalUuid(store, "store");
    if (storeId != null) ctx.requireStoreAccess(storeId);
    var window =
        new Audit.Filter(
            Audit.type(type),
            Parsing.optionalUuid(actor, "actor"),
            storeId,
            Parsing.optionalInstant(from, "from"),
            Parsing.optionalInstant(to, "to"));
    Audit.requireOrderedWindow(window.from(), window.to());
    Cursor.CreatedAtId key = Cursor.decodeCreatedAtId(after);
    var rows =
        repo.list(
            tenantId,
            ctx.storeIds(),
            window,
            key == null ? null : key.createdAt(),
            key == null ? null : key.id(),
            limit + 1);
    return Cursor.page(rows, limit, e -> e.occurredAt() + "|" + e.id());
  }
}
