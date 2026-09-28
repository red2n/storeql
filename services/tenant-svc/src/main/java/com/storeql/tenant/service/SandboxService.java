package com.storeql.tenant.service;

import com.storeql.ids.Ids;
import com.storeql.service.OutboxRow;
import com.storeql.service.TenantDataErasureHandler;
import com.storeql.tenant.domain.Domain.Store;
import com.storeql.tenant.domain.Domain.Tenant;
import com.storeql.tenant.dto.Dtos.CreateStoreRequest;
import com.storeql.tenant.repo.TenantRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A business's sandbox (22.8): a second tenant of its own, marked as such, where an integrator can
 * create products, book stock, place orders and receive webhooks against nothing real. Every
 * service that reads the tenant's profile sees {@code mode: SANDBOX} and behaves: no message leaves
 * it, no money moves, nothing is billed. It sits on the plan the platform keeps for sandboxes, and
 * starts with the live business's default store copied in so there is somewhere for stock to sit.
 *
 * <p>One at a time, made and removed by the owner alone, and never of a sandbox. Removed, it is
 * switched off with the reason and every service is told to erase what it held of it — the same
 * erasure a business leaving the platform gets, without the notice period.
 */
@ApplicationScoped
public class SandboxService {

  private static final Logger LOG = System.getLogger(SandboxService.class.getName());

  /** What a removed sandbox's erasure says it is for. */
  static final String INTENT_SANDBOX_DELETED = Tenant.REASON_SANDBOX_DELETED;

  @Inject TenantRepository repo;
  @Inject TenantService tenants;
  @Inject PlanService plans;

  /**
   * The caller's sandbox: the active sandbox of a live business, or the sandbox itself when the
   * caller is already inside it.
   *
   * @throws ApiException {@code 404 SANDBOX_NOT_FOUND} when the business has none
   */
  public Tenant get(UUID callerTenantId) {
    Tenant caller = tenants.getTenant(callerTenantId);
    if (caller.isSandbox()) {
      return caller;
    }
    return repo.findActiveSandbox(caller.id())
        .orElseThrow(
            () -> ApiException.notFound("SANDBOX_NOT_FOUND", "This business has no sandbox"));
  }

  /**
   * Makes the business's sandbox.
   *
   * @param callerTenantId the live business, from the caller's token
   * @throws ApiException {@code 409 SANDBOX_NESTED} from inside a sandbox, {@code 409
   *     SANDBOX_EXISTS} when it already has one, {@code 503 SANDBOX_PLAN_MISSING} when the
   *     deployment has lost the plan sandboxes sit on
   */
  public Tenant create(UUID callerTenantId) {
    Tenant live = requireLive(callerTenantId, "made");
    repo.findActiveSandbox(live.id())
        .ifPresent(
            existing -> {
              throw ApiException.conflict(
                  "SANDBOX_EXISTS",
                  "This business already has a sandbox; remove it to make another");
            });
    // Looked up before anything is written: a sandbox on no plan would be unrestricted.
    UUID planId = plans.requireSandboxPlanId();

    UUID id = Ids.newId();
    Instant now = Instant.now();
    Tenant sandbox =
        new Tenant(
            id,
            live.name() + " (sandbox)",
            live.legalName(),
            Tenant.STATUS_ACTIVE,
            planId,
            live.ownerUserId(),
            live.country(),
            live.currency(),
            now,
            now,
            null,
            null,
            null,
            null,
            Tenant.MODE_SANDBOX,
            live.id());
    OutboxRow announced =
        new OutboxRow(
            "TenantCreated",
            "storeql.tenant.tenant-created",
            id,
            id,
            Events.tenantCreated(
                id,
                live.ownerUserId(),
                sandbox.name(),
                sandbox.country(),
                sandbox.currency(),
                Tenant.MODE_SANDBOX,
                live.id()));
    repo.createTenantWithOutbox(sandbox, announced);

    // The live business's default store, copied, so stock has somewhere to sit from the start.
    // Best effort: a sandbox with no store is usable — the owner adds one — where a sandbox that
    // exists and is not returned is not.
    defaultStoreOf(live.id())
        .ifPresent(
            source -> {
              try {
                tenants.createDefaultStore(id, live.ownerUserId(), copyOf(source));
              } catch (RuntimeException e) {
                LOG.log(
                    Level.WARNING,
                    "Sandbox {0} made without a copy of store {1}: {2}",
                    id,
                    source.code(),
                    e.getMessage());
              }
            });
    LOG.log(Level.INFO, "Sandbox {0} made for tenant {1}", id, live.id());
    return tenants.getTenant(id);
  }

  /**
   * Removes the business's sandbox: switched off with the reason, and every service told to erase
   * what it holds of it. Another can be made at once.
   *
   * @param callerTenantId the live business, from the caller's token
   * @param actorId who removed it
   * @throws ApiException {@code 409 SANDBOX_NESTED} from inside a sandbox, {@code 404
   *     SANDBOX_NOT_FOUND} when there is none
   */
  public Tenant delete(UUID callerTenantId, UUID actorId) {
    Tenant live = requireLive(callerTenantId, "removed");
    Tenant sandbox =
        repo.findActiveSandbox(live.id())
            .orElseThrow(
                () -> ApiException.notFound("SANDBOX_NOT_FOUND", "This business has no sandbox"));
    UUID erasureEventId = Ids.newId();
    OutboxRow switchedOff = TenantService.tenantStatusEvent(sandbox.id(), Tenant.STATUS_INACTIVE);
    OutboxRow erasureDue =
        new OutboxRow(
            TenantDataErasureHandler.DUE,
            SwitchingService.ERASURE_TOPIC,
            sandbox.id(),
            sandbox.id(),
            Events.tenantDataErasureDue(
                erasureEventId,
                sandbox.id(),
                Ids.derived(erasureEventId, "sandbox"),
                INTENT_SANDBOX_DELETED));
    Tenant gone =
        repo.updateTenantStatusWithOutbox(
            sandbox.id(),
            Tenant.STATUS_INACTIVE,
            Tenant.REASON_SANDBOX_DELETED,
            actorId,
            List.of(switchedOff, erasureDue));
    LOG.log(
        Level.INFO,
        "Sandbox {0} of tenant {1} removed; erasure {2}",
        sandbox.id(),
        live.id(),
        erasureEventId);
    return gone;
  }

  private Tenant requireLive(UUID callerTenantId, String verb) {
    Tenant caller = tenants.getTenant(callerTenantId);
    if (caller.isSandbox()) {
      throw ApiException.conflict(
          "SANDBOX_NESTED",
          "You are inside the sandbox; a sandbox is " + verb + " from the live business");
    }
    return caller;
  }

  private Optional<Store> defaultStoreOf(UUID tenantId) {
    List<Store> stores = repo.listStores(tenantId);
    return stores.stream()
        .filter(Store::isDefault)
        .findFirst()
        .or(() -> stores.stream().findFirst());
  }

  private static CreateStoreRequest copyOf(Store s) {
    List<String> tenders =
        s.enabledPaymentMethods() == null || s.enabledPaymentMethods().isBlank()
            ? null
            : Arrays.stream(s.enabledPaymentMethods().split(",")).map(String::trim).toList();
    return new CreateStoreRequest(
        s.name(),
        s.code(),
        s.type(),
        s.line1(),
        s.line2(),
        s.city(),
        s.state(),
        s.country(),
        s.pincode(),
        s.geoLat(),
        s.geoLng(),
        s.timezone(),
        s.businessHours(),
        s.showPrices(),
        tenders,
        s.tillPhone());
  }
}
