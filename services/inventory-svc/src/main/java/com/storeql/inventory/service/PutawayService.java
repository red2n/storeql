package com.storeql.inventory.service;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.PutawayRule;
import com.storeql.inventory.domain.Domain.PutawayTask;
import com.storeql.inventory.dto.WaveDtos.PutawayRuleRequest;
import com.storeql.inventory.repo.PutawayRepository;
import com.storeql.web.ApiException;
import com.storeql.web.Parsing;
import com.storeql.web.Permissions;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Directed putaway: management says where a product goes when it arrives with no zone (a rule per
 * product per store, and a store default); the placement itself happens as the batch arrives. What
 * no rule places waits on the list for a storekeeper to place with one tap.
 */
@ApplicationScoped
public class PutawayService {

  private static final String[] MANAGEMENT = {"PLATFORM_ADMIN", "OWNER", "MANAGER"};

  @Inject PutawayRepository repo;

  public PutawayRule setRule(TenantContext ctx, PutawayRuleRequest req) {
    ctx.requireAnyRole(MANAGEMENT);
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    UUID variantId =
        req.variantId() == null || req.variantId().isBlank()
            ? null
            : Parsing.uuid(req.variantId(), "variantId");
    return repo.upsertRule(
        new PutawayRule(
            Ids.newId(),
            ctx.requireTenantId(),
            storeId,
            variantId,
            Parsing.uuid(req.zoneId(), "zoneId"),
            ctx.userId(),
            Instant.now()));
  }

  public List<PutawayRule> rules(TenantContext ctx, UUID storeId) {
    ctx.requireStoreAccess(storeId);
    return repo.rules(ctx.requireTenantId(), storeId);
  }

  /**
   * @throws ApiException 404 {@code INVENTORY_PUTAWAY_RULE_NOT_FOUND}
   */
  public void deleteRule(TenantContext ctx, UUID id) {
    ctx.requireAnyRole(MANAGEMENT);
    UUID tenantId = ctx.requireTenantId();
    PutawayRule rule =
        repo.findRule(tenantId, id)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "INVENTORY_PUTAWAY_RULE_NOT_FOUND", "no putaway rule " + id));
    // The rule's own store, not one the caller names: a manager of one store removes no other's.
    ctx.requireStoreAccess(rule.storeId());
    if (!repo.deleteRule(tenantId, id)) {
      throw ApiException.notFound("INVENTORY_PUTAWAY_RULE_NOT_FOUND", "no putaway rule " + id);
    }
  }

  public List<PutawayTask> openTasks(TenantContext ctx, UUID storeId) {
    return repo.openTasks(ctx.requireTenantId(), ctx.scopeStore(storeId));
  }

  /**
   * Places the batch a task names in the zone given, or the one suggested.
   *
   * @throws ApiException 404 {@code INVENTORY_PUTAWAY_TASK_NOT_FOUND}; 400 {@code
   *     INVENTORY_PUTAWAY_ZONE_REQUIRED}; 409 {@code INVENTORY_PUTAWAY_TASK_PLACED}
   */
  public PutawayTask place(TenantContext ctx, UUID taskId, String zoneId) {
    ctx.requirePermission(Permissions.STOCK_TRANSFER);
    UUID tenantId = ctx.requireTenantId();
    PutawayTask t =
        repo.findTask(tenantId, taskId)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "INVENTORY_PUTAWAY_TASK_NOT_FOUND", "no putaway task " + taskId));
    ctx.requireStoreAccess(t.storeId());
    UUID zone =
        zoneId != null && !zoneId.isBlank() ? Parsing.uuid(zoneId, "zoneId") : t.suggestedZoneId();
    if (zone == null) {
      throw ApiException.badRequest(
          "INVENTORY_PUTAWAY_ZONE_REQUIRED",
          "no rule places this batch; name the zone it was put in");
    }
    return repo.place(tenantId, taskId, zone, ctx.userId());
  }
}
