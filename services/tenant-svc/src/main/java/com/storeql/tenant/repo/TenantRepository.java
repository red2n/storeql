package com.storeql.tenant.repo;

import com.storeql.events.EventPayload;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.tenant.domain.Domain.DeliveryArea;
import com.storeql.tenant.domain.Domain.StaffAssignment;
import com.storeql.tenant.domain.Domain.Store;
import com.storeql.tenant.domain.Domain.StoreWithZone;
import com.storeql.tenant.domain.Domain.Tenant;
import com.storeql.tenant.domain.Domain.TenantCurrency;
import com.storeql.tenant.domain.Domain.TenantInventoryConfig;
import com.storeql.tenant.domain.Domain.Zone;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence for tenants/stores/zones/staff + outbox. Every store/zone/staff query filters
 * tenant_id FIRST (golden rule #3). Multi-row writes atomic with their outbox events (golden rule
 * #6).
 */
@ApplicationScoped
public class TenantRepository extends BaseOutboxRepository {

  private static final String TENANT_SELECT =
      "SELECT id, name, legal_name, status, plan_id, owner_user_id, country, currency,"
          + " created_at, updated_at, vat_number, einvoice_scheme, einvoice_id, deactivated_reason, mode, sandbox_of FROM tenants";

  // ─────────────────────────────────────────────── create (atomic with outbox)

  /**
   * Inserts a tenant and its {@code TenantCreated} event in one transaction.
   *
   * @param t the tenant to persist; its {@code id} must already be a UUIDv7
   * @param event the outbox row to commit alongside the insert
   * @return the tenant as stored
   */
  public Tenant createTenantWithOutbox(Tenant t, OutboxRow event) {
    return inTx(
        c -> {
          insertTenant(c, t);
          insertOutbox(c, event);
          return t;
        },
        "create tenant");
  }

  /**
   * Inserts a store, its DEFAULT zone and all their events in one transaction.
   *
   * <p>The zone is not optional: stock must always have somewhere to sit, so a store without one
   * would be unusable the moment inventory arrived.
   *
   * @param store the store to persist
   * @param defaultZone the DEFAULT zone created alongside it
   * @param events the outbox rows to commit with them — store created, zone created, and the
   *     store's initial status
   * @return the store with its zone
   */
  public StoreWithZone createStoreWithDefaultZone(
      Store store, Zone defaultZone, List<OutboxRow> events) {
    return inTx(
        c -> {
          assertTenantActive(c, store.tenantId());
          insertStore(c, store);
          insertZone(c, defaultZone);
          for (OutboxRow event : events) {
            insertOutbox(c, event);
          }
          return new StoreWithZone(store, defaultZone);
        },
        "create store");
  }

  /**
   * Inserts a zone and its {@code ZoneCreated} event in one transaction.
   *
   * @param zone the zone to persist; its {@code id} must already be a UUIDv7
   * @param event the outbox row to commit alongside the insert
   * @return the zone as stored
   */
  public Zone createZoneWithOutbox(Zone zone, OutboxRow event) {
    return inTx(
        c -> {
          insertZone(c, zone);
          insertOutbox(c, event);
          return zone;
        },
        "create zone");
  }

  /**
   * Inserts a staff assignment and its {@code StaffAssigned} event in one transaction.
   *
   * <p>The event is what makes iam-svc bind the store-scoped role, so the two must not separate.
   *
   * @param s the assignment to persist
   * @param event the outbox row to commit alongside the insert
   */
  public void createStaffWithOutbox(StaffAssignment s, OutboxRow event) {
    inTx(
        c -> {
          insertStaff(c, s);
          insertOutbox(c, event);
          return null;
        },
        "assign staff");
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState()))
      return new ApiException(
          409, "CODE_ALREADY_EXISTS", "A record with that code already exists", List.of(), e);
    return dbError(what, e);
  }

  // ─────────────────────────────────────────────────────── tenant reads/writes

  /**
   * Looks a tenant up by id.
   *
   * @param tenantId the tenant to fetch
   * @return the tenant, or empty when no such tenant exists
   */
  /**
   * The business a login owns, if any (21.13: one login, one business). The live one: a sandbox is
   * owned by the same login and is not a second business (22.8).
   */
  public Optional<Tenant> findByOwner(UUID ownerUserId) {
    return one(
        TENANT_SELECT + " WHERE owner_user_id = ? AND mode = 'LIVE' ORDER BY created_at LIMIT 1",
        ownerUserId,
        TenantRepository::mapTenant);
  }

  /** The business's active sandbox, if it has one (22.8). */
  public Optional<Tenant> findActiveSandbox(UUID liveTenantId) {
    return one(
        TENANT_SELECT
            + " WHERE sandbox_of = ? AND mode = 'SANDBOX' AND status = 'ACTIVE'"
            + " ORDER BY created_at DESC LIMIT 1",
        liveTenantId,
        TenantRepository::mapTenant);
  }

  public Optional<Tenant> findTenant(UUID tenantId) {
    return one(
        "SELECT id, name, legal_name, status, plan_id, owner_user_id, country, currency,"
            + " created_at, updated_at, vat_number, einvoice_scheme, einvoice_id, deactivated_reason, mode, sandbox_of FROM tenants WHERE id = ?",
        tenantId,
        TenantRepository::mapTenant);
  }

  /**
   * Every business holding an e-invoicing address, oldest first (07.13, the transport seam).
   *
   * @param scheme the EAS scheme of the address
   * @param id the identifier within it, matched without regard to case
   * @return the tenants, active or not; empty when none holds it
   */
  public List<Tenant> findTenantsByEinvoiceAddress(String scheme, String id) {
    return query(
        TENANT_SELECT
            + " WHERE einvoice_scheme = ? AND lower(einvoice_id) = lower(?) ORDER BY created_at",
        ps -> {
          ps.setString(1, scheme);
          ps.setString(2, id);
        },
        TenantRepository::mapTenant,
        "tenants by e-invoicing address");
  }

  /**
   * Every business holding a VAT identifier, oldest first (07.13, the transport seam).
   *
   * @param vatNumber the identifier with its country prefix, upper-cased with no spaces
   * @return the tenants, active or not; empty when none holds it
   */
  public List<Tenant> findTenantsByVatNumber(String vatNumber) {
    return query(
        TENANT_SELECT + " WHERE upper(replace(vat_number, ' ', '')) = ? ORDER BY created_at",
        ps -> ps.setString(1, vatNumber),
        TenantRepository::mapTenant,
        "tenants by VAT number");
  }

  /**
   * Sets a tenant's status without announcing it.
   *
   * <p>Publishes nothing — a status change other services must see should go through {@link
   * #updateTenantStatusWithOutbox} instead.
   *
   * @param tenantId the tenant to update
   * @param status the status to set
   * @return the tenant with its new status
   */
  public Tenant updateTenantStatus(UUID tenantId, String status) {
    Instant now = Instant.now();
    exec(
        "UPDATE tenants SET status = ?, updated_at = ? WHERE id = ?",
        ps -> {
          ps.setString(1, status);
          ps.setObject(2, now.atOffset(ZoneOffset.UTC));
          ps.setObject(3, tenantId);
        },
        "update tenant status");
    return findTenant(tenantId)
        .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
  }

  /**
   * Flip the tenant status AND publish the change event in one transaction (golden rule #6), so a
   * suspension can never be applied locally without other services (iam-svc) hearing about it.
   */
  /**
   * Moves a business's status, recording <em>why</em> when it is switched off (21.12).
   *
   * <p>The reason is not decoration. Only a business suspended for {@code NON_PAYMENT} comes back
   * when it pays; one an {@code ADMINISTRATOR} switched off never does. Recording it in the same
   * statement as the status means the pair cannot come apart — and switching a business back on
   * clears it, so a stale reason cannot make a later payment lift a suspension nobody asked it to.
   *
   * @param reason {@code ADMINISTRATOR} or {@code NON_PAYMENT} when switching off, ignored when
   *     switching on
   * @param actorId who did it, or null when the dunning run did
   */
  public Tenant updateTenantStatusWithOutbox(
      UUID tenantId, String status, String reason, UUID actorId, OutboxRow event) {
    return updateTenantStatusWithOutbox(tenantId, status, reason, actorId, List.of(event));
  }

  /**
   * The same, announcing more than one thing in the transaction — a sandbox removed is switched off
   * and its erasure started at once (22.8).
   *
   * @param events the outbox rows to commit with the change, in order
   */
  public Tenant updateTenantStatusWithOutbox(
      UUID tenantId, String status, String reason, UUID actorId, List<OutboxRow> events) {
    Instant now = Instant.now();
    boolean off = !"ACTIVE".equals(status);
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE tenants SET status = ?, updated_at = ?, deactivated_reason = ?,"
                      + " deactivated_by = ?, deactivated_at = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setObject(2, now.atOffset(ZoneOffset.UTC));
            ps.setString(3, off ? reason : null);
            ps.setObject(4, off ? actorId : null);
            ps.setObject(5, off ? now.atOffset(ZoneOffset.UTC) : null);
            ps.setObject(6, tenantId);
            if (ps.executeUpdate() == 0) {
              throw ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found");
            }
          }
          // Cascade: when a tenant is suspended, mark all its ACTIVE stores SUSPENDED too so
          // the gateway's TenantStatusGate cache refresh reflects closure immediately, AND
          // publish a StoreStatusChanged per affected store — otherwise every other service's
          // *local* store_status projection (cart-svc, order-svc, iam-svc) never learns of the
          // cascade and keeps reporting those stores as ACTIVE.
          // Stores are NOT auto-reactivated when the tenant is re-enabled — that is an
          // explicit operator action (PATCH /admin/stores/{id}/status).
          if (!"ACTIVE".equals(status)) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE stores SET status = 'SUSPENDED', updated_at = ?"
                        + " WHERE tenant_id = ? AND status = 'ACTIVE' RETURNING id")) {
              ps.setObject(1, now.atOffset(ZoneOffset.UTC));
              ps.setObject(2, tenantId);
              try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                  UUID storeId = rs.getObject("id", UUID.class);
                  String payload =
                      EventPayload.base("StoreStatusChanged", tenantId, storeId)
                          + ",\"storeId\":\""
                          + storeId
                          + "\",\"status\":\"SUSPENDED\"}";
                  insertOutbox(
                      c,
                      new OutboxRow(
                          "StoreStatusChanged",
                          "storeql.tenant.store-status-changed",
                          tenantId,
                          storeId,
                          payload));
                }
              }
            }
          }
          for (OutboxRow event : events) {
            insertOutbox(c, event);
          }
          return null;
        },
        "update tenant status");
    return findTenant(tenantId)
        .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
  }

  /**
   * Renames a tenant.
   *
   * @param tenantId the tenant to update
   * @param businessName the new trading name
   * @param legalName the new registered name, or {@code null} to clear it
   * @return the tenant as stored
   */
  public Tenant updateTenant(
      UUID tenantId,
      String businessName,
      String legalName,
      String vatNumber,
      String einvoiceScheme,
      String einvoiceId) {
    Instant now = Instant.now();
    exec(
        "UPDATE tenants SET name = ?, legal_name = ?, vat_number = ?, einvoice_scheme = ?,"
            + " einvoice_id = ?, updated_at = ? WHERE id = ?",
        ps -> {
          ps.setString(1, businessName);
          ps.setString(2, legalName);
          ps.setString(3, vatNumber);
          ps.setString(4, einvoiceScheme);
          ps.setString(5, einvoiceId);
          ps.setObject(6, now.atOffset(ZoneOffset.UTC));
          ps.setObject(7, tenantId);
        },
        "update tenant");
    return findTenant(tenantId)
        .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
  }

  // ──────────────────────────────────────────────────────── store reads/writes

  /**
   * Every store in the tenant, unpaginated.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @return the tenant's stores
   */
  public List<Store> listStores(UUID tenantId) {
    return many(
        "SELECT id, tenant_id, name, code, type, line1, line2, city, state, country, pincode,"
            + " geo_lat, geo_lng, timezone, business_hours, status, is_default, show_prices, enabled_payment_methods, till_phone, created_at,"
            + " updated_at"
            + " FROM stores WHERE tenant_id = ? ORDER BY created_at",
        tenantId,
        TenantRepository::mapStore);
  }

  /**
   * Keyset page of stores: rows strictly after the cursor in (created_at, id) order.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param afterCreatedAt cursor timestamp, or {@code null} for the first page
   * @param afterId cursor id, breaking ties on identical timestamps
   * @param limit maximum rows; callers pass one more than the page size to detect a next page
   * @return the page of stores
   */
  public List<Store> listStores(UUID tenantId, Instant afterCreatedAt, UUID afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, name, code, type, line1, line2, city, state, country, pincode,"
                + " geo_lat, geo_lng, timezone, business_hours, status, is_default, show_prices,"
                + " enabled_payment_methods, till_phone, created_at, updated_at"
                + " FROM stores WHERE tenant_id = ?");
    if (afterCreatedAt != null && afterId != null) sql.append(" AND (created_at, id) > (?, ?)");
    sql.append(" ORDER BY created_at, id LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (afterCreatedAt != null && afterId != null) {
            ps.setObject(i++, afterCreatedAt.atOffset(ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
        },
        TenantRepository::mapStore,
        "list stores page");
  }

  /**
   * Looks a store up by id.
   *
   * <p>Also the existence-and-ownership guard the zone, staff and delivery-area writes call first.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store to fetch
   * @return the store, or empty when it does not exist in this tenant
   */
  public Optional<Store> findStore(UUID tenantId, UUID storeId) {
    return query(
            "SELECT id, tenant_id, name, code, type, line1, line2, city, state, country, pincode,"
                + " geo_lat, geo_lng, timezone, business_hours, status, is_default, show_prices, enabled_payment_methods, till_phone, created_at,"
                + " updated_at"
                + " FROM stores WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
            },
            TenantRepository::mapStore,
            "find store")
        .stream()
        .findFirst();
  }

  /**
   * Writes a store's address, hours and trading settings back.
   *
   * <p>Takes every value explicitly rather than a partial patch — the caller has already resolved
   * which fields keep their current values.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store to update
   * @param name trading name
   * @param line1 address line 1
   * @param line2 address line 2, may be {@code null}
   * @param city town or city
   * @param state county or region, may be {@code null}
   * @param country ISO country code
   * @param pincode postal code
   * @param geoLat latitude, may be {@code null}
   * @param geoLng longitude, may be {@code null}
   * @param timezone IANA zone the store trades in
   * @param businessHours opening hours, may be {@code null}
   * @param showPrices whether the storefront shows prices or runs as a catalogue
   * @param enabledPaymentMethods the canonicalised comma-separated tender list
   * @param tillPhone what the till asks for the customer's phone: REQUIRED, OPTIONAL or OFF
   * @return the store as stored
   */
  public Store updateStore(
      UUID tenantId,
      UUID storeId,
      String name,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode,
      BigDecimal geoLat,
      BigDecimal geoLng,
      String timezone,
      String businessHours,
      boolean showPrices,
      String enabledPaymentMethods,
      String tillPhone) {
    Instant now = Instant.now();
    exec(
        "UPDATE stores SET name=?, line1=?, line2=?, city=?, state=?, country=?, pincode=?,"
            + " geo_lat=?, geo_lng=?, timezone=?, business_hours=?, show_prices=?,"
            + " enabled_payment_methods=?, till_phone=?, updated_at=?"
            + " WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setString(1, name);
          ps.setString(2, line1);
          ps.setString(3, line2);
          ps.setString(4, city);
          ps.setString(5, state);
          ps.setString(6, country);
          ps.setString(7, pincode);
          ps.setBigDecimal(8, geoLat);
          ps.setBigDecimal(9, geoLng);
          ps.setString(10, timezone);
          ps.setString(11, businessHours);
          ps.setBoolean(12, showPrices);
          ps.setString(13, enabledPaymentMethods);
          ps.setString(14, tillPhone);
          ps.setObject(15, now.atOffset(ZoneOffset.UTC));
          ps.setObject(16, tenantId);
          ps.setObject(17, storeId);
        },
        "update store");
    return findStore(tenantId, storeId)
        .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "Store not found"));
  }

  /**
   * Sets a store's status without announcing it.
   *
   * <p>Publishes nothing — a status change other services must see should go through {@link
   * #updateStoreStatusWithOutbox} instead.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store to update
   * @param status the status to set
   * @return the store with its new status
   */
  public Store updateStoreStatus(UUID tenantId, UUID storeId, String status) {
    Instant now = Instant.now();
    exec(
        "UPDATE stores SET status = ?, updated_at = ? WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setString(1, status);
          ps.setObject(2, now.atOffset(ZoneOffset.UTC));
          ps.setObject(3, tenantId);
          ps.setObject(4, storeId);
        },
        "update store status");
    return findStore(tenantId, storeId)
        .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "Store not found"));
  }

  /**
   * Flip a store's status AND publish the change event in one transaction (golden rule #6), so a
   * store closure can never be applied locally without other services (iam-svc) hearing about it.
   */
  public Store updateStoreStatusWithOutbox(
      UUID tenantId, UUID storeId, String status, OutboxRow event) {
    Instant now = Instant.now();
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE stores SET status = ?, updated_at = ? WHERE tenant_id = ? AND id = ?")) {
            ps.setString(1, status);
            ps.setObject(2, now.atOffset(ZoneOffset.UTC));
            ps.setObject(3, tenantId);
            ps.setObject(4, storeId);
            if (ps.executeUpdate() == 0) {
              throw ApiException.notFound("STORE_NOT_FOUND", "Store not found");
            }
          }
          insertOutbox(c, event);
          return null;
        },
        "update store status");
    return findStore(tenantId, storeId)
        .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "Store not found"));
  }

  /**
   * Whether the tenant already has a default store.
   *
   * <p>Decides whether the next store created becomes the default, and backs the onboarding
   * checklist's "first store" step.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @return {@code true} once a default store exists
   */
  public boolean hasDefaultStore(UUID tenantId) {
    return !query(
            "SELECT 1 FROM stores WHERE tenant_id = ? AND is_default = true LIMIT 1",
            ps -> ps.setObject(1, tenantId),
            rs -> rs.getInt(1),
            "check default store")
        .isEmpty();
  }

  // ──────────────────────────────────────────────────────── zone reads/writes

  /**
   * Keyset page of a store's zones: rows strictly after the cursor in (created_at, id) order.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store whose zones to page through
   * @param afterCreatedAt cursor timestamp, or {@code null} for the first page
   * @param afterId cursor id, breaking ties on identical timestamps
   * @param limit maximum rows; callers pass one more than the page size to detect a next page
   * @return the page of zones
   */
  public List<Zone> listZones(
      UUID tenantId, UUID storeId, Instant afterCreatedAt, UUID afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, name, code, type, status, created_at, updated_at"
                + " FROM zones WHERE tenant_id = ? AND store_id = ?");
    if (afterCreatedAt != null && afterId != null) sql.append(" AND (created_at, id) > (?, ?)");
    sql.append(" ORDER BY created_at, id LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          ps.setObject(i++, storeId);
          if (afterCreatedAt != null && afterId != null) {
            ps.setObject(i++, afterCreatedAt.atOffset(ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
        },
        TenantRepository::mapZone,
        "list zones page");
  }

  /**
   * Looks a zone up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param zoneId the zone to fetch
   * @return the zone, or empty when it does not exist in this tenant
   */
  public Optional<Zone> findZone(UUID tenantId, UUID zoneId) {
    return query(
            "SELECT id, tenant_id, store_id, name, code, type, status, created_at, updated_at"
                + " FROM zones WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, zoneId);
            },
            TenantRepository::mapZone,
            "find zone")
        .stream()
        .findFirst();
  }

  /**
   * Renames or retypes a zone.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param zoneId the zone to update
   * @param name the new display name
   * @param code the new short code
   * @param type the new zone type
   * @return the zone as stored
   */
  public Zone updateZone(UUID tenantId, UUID zoneId, String name, String code, String type) {
    Instant now = Instant.now();
    exec(
        "UPDATE zones SET name = ?, code = ?, type = ?, updated_at = ?"
            + " WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setString(1, name);
          ps.setString(2, code);
          ps.setString(3, type);
          ps.setObject(4, now.atOffset(ZoneOffset.UTC));
          ps.setObject(5, tenantId);
          ps.setObject(6, zoneId);
        },
        "update zone");
    return findZone(tenantId, zoneId)
        .orElseThrow(() -> ApiException.notFound("ZONE_NOT_FOUND", "Zone not found"));
  }

  /**
   * Sets a zone's status.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param zoneId the zone to update
   * @param status the status to set
   * @return the zone with its new status
   */
  public Zone updateZoneStatus(UUID tenantId, UUID zoneId, String status) {
    Instant now = Instant.now();
    exec(
        "UPDATE zones SET status = ?, updated_at = ? WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setString(1, status);
          ps.setObject(2, now.atOffset(ZoneOffset.UTC));
          ps.setObject(3, tenantId);
          ps.setObject(4, zoneId);
        },
        "update zone status");
    return findZone(tenantId, zoneId)
        .orElseThrow(() -> ApiException.notFound("ZONE_NOT_FOUND", "Zone not found"));
  }

  // ──────────────────────────────────────────────────────── staff reads/writes

  /**
   * Keyset page of staff assignments: rows strictly after the cursor in (created_at, id) order.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeIds the caller's stores; empty means unrestricted (an owner, a business-wide
   *     manager, the platform admin) and every assignment is a candidate. Non-empty restricts to
   *     assignments at one of those stores or a business-wide one ({@code store_id IS NULL}) — the
   *     same rule iam-svc applies to {@code GET /auth/admin/staff-users}.
   * @param afterCreatedAt cursor timestamp, or {@code null} for the first page
   * @param afterId cursor id, breaking ties on identical timestamps
   * @param limit maximum rows; callers pass one more than the page size to detect a next page
   * @return the page of assignments
   */
  public List<StaffAssignment> listStaff(
      UUID tenantId, Set<UUID> storeIds, Instant afterCreatedAt, UUID afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, user_id, store_id, role, base_tier, created_at"
                + " FROM staff_assignments WHERE tenant_id = ?");
    if (!storeIds.isEmpty()) sql.append(" AND (store_id = ANY(?) OR store_id IS NULL)");
    if (afterCreatedAt != null && afterId != null) sql.append(" AND (created_at, id) > (?, ?)");
    sql.append(" ORDER BY created_at, id LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (!storeIds.isEmpty()) {
            ps.setArray(i++, ps.getConnection().createArrayOf("uuid", storeIds.toArray()));
          }
          if (afterCreatedAt != null && afterId != null) {
            ps.setObject(i++, afterCreatedAt.atOffset(ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
        },
        TenantRepository::mapStaff,
        "list staff page");
  }

  /**
   * Deletes one staff assignment.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param userId the staff member to unassign
   * @param storeId the store to unassign them from
   */
  public void removeStaff(UUID tenantId, UUID userId, UUID storeId) {
    removeStaffWithOutbox(tenantId, userId, storeId, tier -> null);
  }

  /**
   * Deletes a user's assignments at a store and, for each tier taken away, writes the event iam-svc
   * unbinds on, in one transaction (SJ-D51).
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param userId the staff member to unassign
   * @param storeId the store to unassign them from
   * @param event builds the {@code StaffRemoved} row for a tier, or returns null for none
   * @return how many assignments were removed
   */
  public int removeStaffWithOutbox(
      UUID tenantId,
      UUID userId,
      UUID storeId,
      java.util.function.Function<String, OutboxRow> event) {
    return inTx(
        c -> {
          java.util.List<String> tiers = new java.util.ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM staff_assignments WHERE tenant_id = ? AND user_id = ?"
                      + " AND store_id = ? RETURNING base_tier")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, userId);
            ps.setObject(3, storeId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) tiers.add(rs.getString(1));
            }
          }
          for (String tier : new java.util.LinkedHashSet<>(tiers)) {
            OutboxRow row = event.apply(tier);
            if (row != null) insertOutbox(c, row);
          }
          return tiers.size();
        },
        "remove staff");
  }

  // ──────────────────────────────────────────────────────── custom roles (20.10)

  private static final String ROLE_COLUMNS =
      "id, tenant_id, code, name, base_tier, permissions, description, created_at, updated_at";

  /**
   * Inserts a custom role and the event iam-svc applies it on, in one transaction.
   *
   * @param r the role
   * @param event {@code RoleDefined}
   * @throws ApiException 409 {@code ROLE_ALREADY_EXISTS} when the tenant already has the code
   */
  public void createRole(com.storeql.tenant.domain.Domain.TenantRole r, OutboxRow event) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO tenant_roles (" + ROLE_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, r.id());
            ps.setObject(2, r.tenantId());
            ps.setString(3, r.code());
            ps.setString(4, r.name());
            ps.setString(5, r.baseTier());
            ps.setString(6, String.join(",", r.permissions()));
            ps.setString(7, r.description());
            ps.setObject(8, r.createdAt().atOffset(ZoneOffset.UTC));
            ps.setObject(9, r.updatedAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
              throw new ApiException(
                  409,
                  "ROLE_ALREADY_EXISTS",
                  "This tenant already has a role " + r.code(),
                  List.of(),
                  e);
            }
            throw e;
          }
          insertOutbox(c, event);
          return null;
        },
        "define role");
  }

  /**
   * Rewrites a custom role's name, permissions and description, with the event that carries the
   * change to its holders.
   *
   * @return {@code true} when the role existed in this tenant
   */
  public boolean updateRole(com.storeql.tenant.domain.Domain.TenantRole r, OutboxRow event) {
    return inTx(
        c -> {
          int n;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE tenant_roles SET name = ?, permissions = ?, description = ?,"
                      + " updated_at = now() WHERE tenant_id = ? AND code = ?")) {
            ps.setString(1, r.name());
            ps.setString(2, String.join(",", r.permissions()));
            ps.setString(3, r.description());
            ps.setObject(4, r.tenantId());
            ps.setString(5, r.code());
            n = ps.executeUpdate();
          }
          if (n == 0) return false;
          insertOutbox(c, event);
          return true;
        },
        "redefine role");
  }

  /**
   * Deletes a custom role no assignment names.
   *
   * @return {@code true} when deleted; {@code false} when the tenant has no such role
   * @throws ApiException 409 {@code ROLE_IN_USE} while an assignment still names it, decided under
   *     the role's row lock so an assignment made at the same moment cannot slip past
   */
  public boolean deleteRole(UUID tenantId, String code) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id FROM tenant_roles WHERE tenant_id = ? AND code = ? FOR UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) return false;
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT count(*) FROM staff_assignments WHERE tenant_id = ? AND role = ?")) {
            ps.setObject(1, tenantId);
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              if (rs.getInt(1) > 0) {
                throw ApiException.conflict(
                    "ROLE_IN_USE",
                    rs.getInt(1)
                        + " staff assignment(s) still use "
                        + code
                        + "; remove them first");
              }
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM tenant_roles WHERE tenant_id = ? AND code = ?")) {
            ps.setObject(1, tenantId);
            ps.setString(2, code);
            ps.executeUpdate();
          }
          return true;
        },
        "delete role");
  }

  /**
   * @param tenantId owning tenant; the first condition of the query
   * @param code the role's code
   * @return the role, or empty when the tenant has none by that code
   */
  public Optional<com.storeql.tenant.domain.Domain.TenantRole> findRole(
      UUID tenantId, String code) {
    var rows =
        query(
            "SELECT " + ROLE_COLUMNS + " FROM tenant_roles WHERE tenant_id = ? AND code = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, code);
            },
            TenantRepository::mapRole,
            "find role");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * @param tenantId owning tenant; the first condition of the query
   * @return the tenant's custom roles, by code
   */
  public List<com.storeql.tenant.domain.Domain.TenantRole> listRoles(UUID tenantId) {
    return query(
        "SELECT " + ROLE_COLUMNS + " FROM tenant_roles WHERE tenant_id = ? ORDER BY code",
        ps -> ps.setObject(1, tenantId),
        TenantRepository::mapRole,
        "list roles");
  }

  /**
   * @param tenantId owning tenant; the first condition of the query
   * @param code a custom role's code
   * @return how many assignments name it
   */
  public int countStaffWithRole(UUID tenantId, String code) {
    var rows =
        query(
            "SELECT count(*) AS n FROM staff_assignments WHERE tenant_id = ? AND role = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, code);
            },
            rs -> rs.getInt("n"),
            "count staff with role");
    return rows.isEmpty() ? 0 : rows.get(0);
  }

  private static com.storeql.tenant.domain.Domain.TenantRole mapRole(ResultSet rs)
      throws SQLException {
    String perms = rs.getString("permissions");
    java.util.Set<String> set = new java.util.LinkedHashSet<>();
    if (perms != null) {
      for (String p : perms.split(",")) {
        if (!p.isBlank()) set.add(p.trim());
      }
    }
    return new com.storeql.tenant.domain.Domain.TenantRole(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("base_tier"),
        java.util.Set.copyOf(set),
        rs.getString("description"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  // ─────────────────────────────────────────────────────────── inserts

  private void insertTenant(Connection c, Tenant t) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO tenants"
                + " (id, name, legal_name, status, plan_id, owner_user_id, country, currency,"
                + " created_at, updated_at, mode, sandbox_of) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, t.id());
      ps.setString(2, t.name());
      ps.setString(3, t.legalName());
      ps.setString(4, t.status());
      ps.setObject(5, t.planId());
      ps.setObject(6, t.ownerUserId());
      ps.setString(7, t.country());
      ps.setString(8, t.currency());
      ps.setObject(9, t.createdAt().atOffset(ZoneOffset.UTC));
      ps.setObject(10, t.createdAt().atOffset(ZoneOffset.UTC));
      ps.setString(11, t.mode() == null ? Tenant.MODE_LIVE : t.mode());
      ps.setObject(12, t.sandboxOf());
      ps.executeUpdate();
    }
  }

  private void insertStore(Connection c, Store s) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO stores"
                + " (id, tenant_id, name, code, type, line1, line2, city, state, country, pincode,"
                + " geo_lat, geo_lng, timezone, business_hours, status, is_default, show_prices,"
                + " enabled_payment_methods, till_phone, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, s.id());
      ps.setObject(2, s.tenantId());
      ps.setString(3, s.name());
      ps.setString(4, s.code());
      ps.setString(5, s.type());
      ps.setString(6, s.line1());
      ps.setString(7, s.line2());
      ps.setString(8, s.city());
      ps.setString(9, s.state());
      ps.setString(10, s.country());
      ps.setString(11, s.pincode());
      ps.setBigDecimal(12, s.geoLat());
      ps.setBigDecimal(13, s.geoLng());
      ps.setString(14, s.timezone());
      ps.setString(15, s.businessHours());
      ps.setString(16, s.status());
      ps.setBoolean(17, s.isDefault());
      ps.setBoolean(18, s.showPrices());
      ps.setString(19, s.enabledPaymentMethods());
      ps.setString(20, s.tillPhone());
      ps.setObject(21, s.createdAt().atOffset(ZoneOffset.UTC));
      ps.setObject(22, s.createdAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private void insertZone(Connection c, Zone z) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO zones"
                + " (id, tenant_id, store_id, name, code, type, status, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, z.id());
      ps.setObject(2, z.tenantId());
      ps.setObject(3, z.storeId());
      ps.setString(4, z.name());
      ps.setString(5, z.code());
      ps.setString(6, z.type());
      ps.setString(7, z.status());
      ps.setObject(8, z.createdAt().atOffset(ZoneOffset.UTC));
      ps.setObject(9, z.createdAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private void insertStaff(Connection c, StaffAssignment s) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO staff_assignments"
                + " (id, tenant_id, user_id, store_id, role, base_tier, created_at)"
                + " VALUES (?,?,?,?,?,?,?)")) {
      ps.setObject(1, s.id());
      ps.setObject(2, s.tenantId());
      ps.setObject(3, s.userId());
      ps.setObject(4, s.storeId());
      ps.setString(5, s.role());
      ps.setString(6, s.baseTier());
      ps.setObject(7, s.createdAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private void assertTenantActive(Connection c, UUID tenantId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT status FROM tenants WHERE id = ?")) {
      ps.setObject(1, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) throw ApiException.notFound("TENANT_NOT_FOUND", "Tenant does not exist");
        if (!Tenant.STATUS_ACTIVE.equals(rs.getString(1)))
          throw ApiException.unprocessable("TENANT_NOT_ACTIVE", "Tenant is not active");
      }
    }
  }

  // ─────────────────────────────────────────────────── single-arg query helpers

  private <T> Optional<T> one(String sql, UUID arg, RowMapper<T> mapper) {
    return query(sql, ps -> ps.setObject(1, arg), mapper, "query").stream().findFirst();
  }

  private <T> List<T> many(String sql, UUID arg, RowMapper<T> mapper) {
    return query(sql, ps -> ps.setObject(1, arg), mapper, "query list");
  }

  // ──────────────────────────────────────────────────────────────── row mappers

  private static Tenant mapTenant(ResultSet rs) throws SQLException {
    return new Tenant(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("legal_name"),
        rs.getString("status"),
        rs.getObject("plan_id", UUID.class),
        rs.getObject("owner_user_id", UUID.class),
        rs.getString("country"),
        rs.getString("currency"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
        rs.getString("vat_number"),
        rs.getString("einvoice_scheme"),
        rs.getString("einvoice_id"),
        rs.getString("deactivated_reason"),
        rs.getString("mode"),
        rs.getObject("sandbox_of", UUID.class));
  }

  private static Store mapStore(ResultSet rs) throws SQLException {
    return new Store(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("code"),
        rs.getString("type"),
        rs.getString("line1"),
        rs.getString("line2"),
        rs.getString("city"),
        rs.getString("state"),
        rs.getString("country"),
        rs.getString("pincode"),
        rs.getBigDecimal("geo_lat"),
        rs.getBigDecimal("geo_lng"),
        rs.getString("timezone"),
        rs.getString("business_hours"),
        rs.getString("status"),
        rs.getBoolean("is_default"),
        rs.getBoolean("show_prices"),
        rs.getString("enabled_payment_methods"),
        rs.getString("till_phone"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static Zone mapZone(ResultSet rs) throws SQLException {
    return new Zone(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("name"),
        rs.getString("code"),
        rs.getString("type"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static StaffAssignment mapStaff(ResultSet rs) throws SQLException {
    return new StaffAssignment(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("role"),
        rs.getString("base_tier"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  // ── Gap #53: Inventory org config ────────────────────────────────────────

  /**
   * Writes an inventory configuration wholesale.
   *
   * <p>Replaces every field, so a partial update must go through {@link
   * #upsertInventoryConfigMerged} to avoid clearing what it does not mention.
   *
   * @param cfg the configuration to store
   * @return the configuration as stored
   */
  public TenantInventoryConfig upsertInventoryConfig(TenantInventoryConfig cfg) {
    return inTx(c -> upsertInventoryConfigTx(c, cfg), "upsert inventory config");
  }

  /**
   * Locks the tenant's config row (if any) for the duration of the transaction, lets {@code merge}
   * compute the new value from it, then atomically upserts the result — closing the race where two
   * concurrent partial updates each read the same stale snapshot and the second silently clobbers
   * fields the first one just set. {@code SELECT ... FOR UPDATE} serializes concurrent callers on
   * the same tenant_id; a first-ever insert for a tenant has no row to lock, but {@code ON
   * CONFLICT} already makes concurrent first-inserts safe on its own.
   */
  public TenantInventoryConfig upsertInventoryConfigMerged(
      UUID tenantId, java.util.function.UnaryOperator<TenantInventoryConfig> merge) {
    return inTx(
        c -> {
          TenantInventoryConfig existing = lockInventoryConfigForUpdate(c, tenantId);
          TenantInventoryConfig merged = merge.apply(existing);
          return upsertInventoryConfigTx(c, merged);
        },
        "upsert inventory config (merged)");
  }

  private TenantInventoryConfig lockInventoryConfigForUpdate(Connection c, UUID tenantId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, lot_control_enabled, serial_control_enabled,"
                + " grade_control_enabled, expiry_tracking_enabled, costing_method,"
                + " default_uom, reorder_alert_enabled, auto_reserve_on_order,"
                + " created_at, updated_at"
                + " FROM tenant_inventory_config WHERE tenant_id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? mapInventoryConfig(rs) : null;
      }
    }
  }

  private TenantInventoryConfig upsertInventoryConfigTx(Connection c, TenantInventoryConfig cfg)
      throws SQLException {
    String sql =
        """
        INSERT INTO tenant_inventory_config
          (id, tenant_id, lot_control_enabled, serial_control_enabled,
           grade_control_enabled, expiry_tracking_enabled, costing_method,
           default_uom, reorder_alert_enabled, auto_reserve_on_order,
           created_at, updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT (tenant_id) DO UPDATE SET
          lot_control_enabled     = EXCLUDED.lot_control_enabled,
          serial_control_enabled  = EXCLUDED.serial_control_enabled,
          grade_control_enabled   = EXCLUDED.grade_control_enabled,
          expiry_tracking_enabled = EXCLUDED.expiry_tracking_enabled,
          costing_method          = EXCLUDED.costing_method,
          default_uom             = EXCLUDED.default_uom,
          reorder_alert_enabled   = EXCLUDED.reorder_alert_enabled,
          auto_reserve_on_order   = EXCLUDED.auto_reserve_on_order,
          updated_at              = now()
        RETURNING *
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, cfg.id());
      ps.setObject(2, cfg.tenantId());
      ps.setBoolean(3, cfg.lotControlEnabled());
      ps.setBoolean(4, cfg.serialControlEnabled());
      ps.setBoolean(5, cfg.gradeControlEnabled());
      ps.setBoolean(6, cfg.expiryTrackingEnabled());
      ps.setString(7, cfg.costingMethod());
      ps.setString(8, cfg.defaultUom());
      ps.setBoolean(9, cfg.reorderAlertEnabled());
      ps.setBoolean(10, cfg.autoReserveOnOrder());
      ps.setObject(11, OffsetDateTime.ofInstant(cfg.createdAt(), ZoneOffset.UTC));
      ps.setObject(12, OffsetDateTime.ofInstant(cfg.updatedAt(), ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException("upsert inventory config returned no row");
        }
        return mapInventoryConfig(rs);
      }
    }
  }

  /**
   * Keyset page of every tenant on the platform (platform-admin), ordered by {@code (created_at,
   * id)} ascending. Was a flat unbounded scan of {@code tenants}; now paginated like {@link
   * #listStores(UUID, Instant, UUID, int)} so a growing platform doesn't turn this into an
   * ever-larger single response.
   */
  public List<Tenant> listAllTenants(Instant afterCreatedAt, UUID afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, name, legal_name, status, plan_id, owner_user_id, country, currency,"
                + " created_at, updated_at, vat_number, einvoice_scheme, einvoice_id, deactivated_reason, mode, sandbox_of FROM tenants");
    if (afterCreatedAt != null && afterId != null) sql.append(" WHERE (created_at, id) > (?, ?)");
    sql.append(" ORDER BY created_at, id LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          if (afterCreatedAt != null && afterId != null) {
            ps.setObject(i++, afterCreatedAt.atOffset(ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
        },
        TenantRepository::mapTenant,
        "list all tenants page");
  }

  /**
   * Reads the tenant's inventory configuration.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @return the configuration, or empty when the tenant has never set one
   */
  public Optional<TenantInventoryConfig> findInventoryConfig(UUID tenantId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id, tenant_id, lot_control_enabled, serial_control_enabled,"
                      + " grade_control_enabled, expiry_tracking_enabled, costing_method,"
                      + " default_uom, reorder_alert_enabled, auto_reserve_on_order,"
                      + " created_at, updated_at"
                      + " FROM tenant_inventory_config WHERE tenant_id = ?")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? Optional.of(mapInventoryConfig(rs)) : Optional.empty();
            }
          }
        },
        "find inventory config");
  }

  private static TenantInventoryConfig mapInventoryConfig(ResultSet rs) throws SQLException {
    return new TenantInventoryConfig(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getBoolean("lot_control_enabled"),
        rs.getBoolean("serial_control_enabled"),
        rs.getBoolean("grade_control_enabled"),
        rs.getBoolean("expiry_tracking_enabled"),
        rs.getString("costing_method"),
        rs.getString("default_uom"),
        rs.getBoolean("reorder_alert_enabled"),
        rs.getBoolean("auto_reserve_on_order"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  /**
   * Every tenant's declared currency, or just one when {@code scope} is given.
   *
   * <p>Tenants with no currency recorded are skipped in SQL rather than filtered in Java: they have
   * nothing to announce, and emitting an event with a null currency would only give the consumer
   * something to reject.
   *
   * @param scope a single tenant to read, or {@code null} for every tenant on the platform
   * @return one row per tenant that has a currency, ordered so a replay is reproducible
   */
  public List<TenantCurrency> findTenantCurrencies(UUID scope) {
    String sql =
        "SELECT id, currency FROM tenants WHERE currency IS NOT NULL"
            + (scope == null ? "" : " AND id = ?")
            + " ORDER BY created_at, id";
    return query(
        sql,
        ps -> {
          if (scope != null) ps.setObject(1, scope);
        },
        rs -> new TenantCurrency(rs.getObject("id", UUID.class), rs.getString("currency")),
        "list tenant currencies");
  }

  /**
   * Write a batch of events to the outbox in one transaction, so a replay either announces every
   * tenant or none of them. A partial replay is the worst outcome: it leaves some projections fixed
   * and some not, with nothing to say which.
   *
   * @param events the outbox rows to write; an empty list is a no-op
   * @return the number of rows written
   */
  public int publishEvents(List<OutboxRow> events) {
    if (events.isEmpty()) return 0;
    return inTx(
        c -> {
          for (OutboxRow event : events) insertOutbox(c, event);
          return events.size();
        },
        "publish event batch");
  }

  /**
   * Publish a single event to the outbox. Flow guard: used to emit role grants, status changes,
   * etc.
   */
  public void publishEvent(OutboxRow event) {
    inTx(
        c -> {
          insertOutbox(c, event);
          return null;
        },
        "publish event");
  }

  // ── delivery areas ─────────────────────────────────────────────────────────

  /**
   * Maps a pincode to a store.
   *
   * @param a the delivery area to persist; its {@code id} must already be a UUIDv7
   * @return the delivery area as stored
   * @throws RuntimeException when the unique {@code (tenant, store, pincode)} constraint rejects a
   *     duplicate; the service turns this into a 409
   */
  public DeliveryArea insertDeliveryArea(DeliveryArea a) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO delivery_areas (id, tenant_id, store_id, pincode, priority,"
                      + " created_at) VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, a.id());
            ps.setObject(2, a.tenantId());
            ps.setObject(3, a.storeId());
            ps.setString(4, a.pincode());
            ps.setInt(5, a.priority());
            ps.setObject(6, a.createdAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          return null;
        },
        "insert delivery area");
    return a;
  }

  /**
   * Lists the pincodes one store delivers to.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store whose areas to list
   * @return the delivery areas, empty when none are mapped
   */
  public List<DeliveryArea> listDeliveryAreas(UUID tenantId, UUID storeId) {
    return query(
        "SELECT id, tenant_id, store_id, pincode, priority, created_at FROM delivery_areas"
            + " WHERE tenant_id=? AND store_id=? ORDER BY priority ASC, pincode ASC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        TenantRepository::mapDeliveryArea,
        "list delivery areas");
  }

  /**
   * Unmaps a pincode from a store.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store the area belongs to
   * @param areaId the delivery area to remove
   * @return {@code true} when a row was deleted, {@code false} when nothing matched
   */
  public boolean deleteDeliveryArea(UUID tenantId, UUID storeId, UUID areaId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM delivery_areas WHERE tenant_id=? AND store_id=? AND id=?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
            ps.setObject(3, areaId);
            return ps.executeUpdate() > 0;
          }
        },
        "delete delivery area");
  }

  /**
   * Lowest-priority (highest precedence) store covering the pincode, or empty if none mapped. When
   * no delivery_areas exist for the tenant at all, callers should fall back to the default store.
   */
  public Optional<DeliveryArea> resolveDeliveryArea(UUID tenantId, String pincode) {
    List<DeliveryArea> rows =
        query(
            "SELECT id, tenant_id, store_id, pincode, priority, created_at FROM delivery_areas"
                + " WHERE tenant_id=? AND lower(pincode)=lower(?) ORDER BY priority ASC LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, pincode.trim());
            },
            TenantRepository::mapDeliveryArea,
            "resolve delivery area");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Whether the tenant has mapped any delivery areas at all.
   *
   * <p>Distinguishes "this pincode is not covered" from "delivery mapping was never set up", which
   * is what lets a single-store tenant deliver everywhere without configuring anything.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @return {@code true} once at least one area exists
   */
  public boolean hasAnyDeliveryAreas(UUID tenantId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("SELECT 1 FROM delivery_areas WHERE tenant_id=? LIMIT 1")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next();
            }
          }
        },
        "has any delivery areas");
  }

  private static DeliveryArea mapDeliveryArea(ResultSet rs) throws SQLException {
    return new DeliveryArea(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("pincode"),
        rs.getInt("priority"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
