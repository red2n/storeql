package com.storeql.pricing.repo;

import com.storeql.ids.Ids;
import com.storeql.pricing.domain.Domain;
import com.storeql.pricing.domain.Domain.CustomerVatStatus;
import com.storeql.pricing.domain.Domain.PriceList;
import com.storeql.pricing.domain.Domain.PriceListItem;
import com.storeql.pricing.domain.Domain.PriceOverride;
import com.storeql.pricing.domain.Domain.ProductVatCategory;
import com.storeql.pricing.domain.Domain.Promotion;
import com.storeql.pricing.domain.Domain.PromotionItem;
import com.storeql.pricing.domain.Domain.TaxTransaction;
import com.storeql.pricing.domain.Domain.VatRate;
import com.storeql.pricing.domain.MarkdownLabel;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** JDBC persistence for pricing-svc. Every tenant query filters by tenant_id first. */
@ApplicationScoped
public class PricingRepository extends BaseOutboxRepository {

  // ── VAT Rates ─────────────────────────────────────────────────────────────

  /**
   * Inserts a VAT rate.
   *
   * @param r the rate to persist; its {@code id} must already be a UUIDv7
   * @return the rate as stored
   */
  public VatRate createVatRate(VatRate r) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO vat_rates"
                      + " (id,tenant_id,code,name,rate,exempt,description,effective_from,effective_to)"
                      + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, r.id());
            ps.setObject(2, r.tenantId());
            ps.setString(3, r.code());
            ps.setString(4, r.name());
            ps.setBigDecimal(5, r.rate());
            ps.setBoolean(6, r.exempt());
            ps.setString(7, r.description());
            ps.setObject(8, toOdt(r.effectiveFrom()));
            ps.setObject(9, toOdt(r.effectiveTo()));
            ps.executeUpdate();
          } catch (java.sql.SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState()))
              throw new ApiException(
                  409,
                  "PRICING_VAT_CODE_EXISTS",
                  "VAT code " + r.code() + " already configured for this tenant",
                  List.of(),
                  sqle);
            throw sqle;
          }
          com.storeql.pricing.repo.AppliedPriceRepository.enqueue(
              c, r.tenantId(), null, null, "VAT_RATE_SET");
          return r;
        },
        "create vat rate");
  }

  /**
   * Lists a tenant's VAT rates.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @return the configured rates
   */
  public List<VatRate> findVatRates(UUID tenantId) {
    return query(
        "SELECT id,tenant_id,code,name,rate,exempt,description,effective_from,effective_to,created_at"
            + " FROM vat_rates WHERE tenant_id=? ORDER BY code",
        ps -> ps.setObject(1, tenantId),
        this::mapVatRate,
        "list vat rates");
  }

  /**
   * Looks a VAT rate up by code.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param code the VAT code; callers upper-case it first, as stored
   * @return the rate, or empty when the code is not configured
   */
  public Optional<VatRate> findVatRate(UUID tenantId, String code) {
    return findVatRate(tenantId, code, null);
  }

  /**
   * As {@link #findVatRate(UUID, String)}; with {@code asOf}, a rate made after it did not yet
   * exist (03.12).
   */
  public Optional<VatRate> findVatRate(UUID tenantId, String code, Instant asOf) {
    var list =
        query(
            "SELECT id,tenant_id,code,name,rate,exempt,description,effective_from,effective_to,created_at"
                + " FROM vat_rates WHERE tenant_id=? AND code=?"
                + (asOf == null ? "" : " AND created_at <= ?"),
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, code);
              if (asOf != null) ps.setObject(3, toOdt(asOf));
            },
            this::mapVatRate,
            "find vat rate");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /**
   * Writes a VAT rate back in place.
   *
   * @param r the rate carrying the new values; its id and tenant select the row
   * @return the rate as stored
   */
  public VatRate updateVatRate(VatRate r) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "UPDATE vat_rates SET name=?,rate=?,exempt=?,description=?,effective_from=?,"
                      + "effective_to=? WHERE tenant_id=? AND code=?")) {
            ps.setString(1, r.name());
            ps.setBigDecimal(2, r.rate());
            ps.setBoolean(3, r.exempt());
            ps.setString(4, r.description());
            ps.setObject(5, toOdt(r.effectiveFrom()));
            ps.setObject(6, toOdt(r.effectiveTo()));
            ps.setObject(7, r.tenantId());
            ps.setString(8, r.code());
            ps.executeUpdate();
          }
          // A rate overwritten in place is exactly what the ledger exists to remember (03.12). It
          // applies from now, whatever its effective date says, so it is evaluated as of now; and
          // it cannot be read as of before, so an earlier evaluation still queued is uncertain.
          com.storeql.pricing.repo.AppliedPriceRepository.enqueue(
              c, r.tenantId(), null, null, "VAT_RATE_CHANGED", true);
          return r;
        },
        "update vat rate");
  }

  private VatRate mapVatRate(java.sql.ResultSet rs) throws java.sql.SQLException {
    OffsetDateTime effTo = rs.getObject("effective_to", OffsetDateTime.class);
    return new VatRate(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getBigDecimal("rate"),
        rs.getBoolean("exempt"),
        rs.getString("description"),
        rs.getObject("effective_from", OffsetDateTime.class).toInstant(),
        effTo != null ? effTo.toInstant() : null,
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  // ── Product VAT Categories ────────────────────────────────────────────────

  /**
   * Assigns a variant to a VAT code, replacing any existing assignment.
   *
   * @param pvc the assignment to store
   * @return the assignment as stored
   */
  public ProductVatCategory upsertProductVatCategory(ProductVatCategory pvc) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO product_vat_categories"
                      + " (id,tenant_id,variant_id,vat_code,effective_from)"
                      + " VALUES (?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id,variant_id)"
                      + " DO UPDATE SET vat_code=EXCLUDED.vat_code,"
                      + "  effective_from=EXCLUDED.effective_from,"
                      + "  effective_to=NULL"
                      + " RETURNING (xmax = 0) AS inserted")) {
            ps.setObject(1, pvc.id());
            ps.setObject(2, pvc.tenantId());
            ps.setObject(3, pvc.variantId());
            ps.setString(4, pvc.vatCode());
            ps.setObject(5, toOdt(pvc.effectiveFrom()));
            boolean inserted;
            try (var rs = ps.executeQuery()) {
              inserted = rs.next() && rs.getBoolean("inserted");
            }
            // A category replaced in place cannot be read as of before (03.12); a new one can.
            com.storeql.pricing.repo.AppliedPriceRepository.enqueue(
                c, pvc.tenantId(), pvc.variantId(), null, "VAT_CATEGORY_SET", !inserted);
          }
          return pvc;
        },
        "upsert product vat category");
  }

  /**
   * Reads a variant's VAT assignment.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param variantId the variant to look up
   * @return the assignment, or empty when the variant was never categorised — price resolution
   *     treats that as the standard rate
   */
  public Optional<ProductVatCategory> findProductVatCategory(UUID tenantId, UUID variantId) {
    return findProductVatCategory(tenantId, variantId, null);
  }

  /**
   * As {@link #findProductVatCategory(UUID, UUID)}; with {@code asOf}, an assignment made after it
   * did not yet exist (03.12).
   */
  public Optional<ProductVatCategory> findProductVatCategory(
      UUID tenantId, UUID variantId, Instant asOf) {
    var list =
        query(
            "SELECT id,tenant_id,variant_id,vat_code,effective_from,effective_to,created_at"
                + " FROM product_vat_categories WHERE tenant_id=? AND variant_id=?"
                + (asOf == null ? "" : " AND created_at <= ?"),
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
              if (asOf != null) ps.setObject(3, toOdt(asOf));
            },
            rs -> {
              OffsetDateTime effTo = rs.getObject("effective_to", OffsetDateTime.class);
              return new ProductVatCategory(
                  rs.getObject("id", UUID.class),
                  rs.getObject("tenant_id", UUID.class),
                  rs.getObject("variant_id", UUID.class),
                  rs.getString("vat_code"),
                  rs.getObject("effective_from", OffsetDateTime.class).toInstant(),
                  effTo != null ? effTo.toInstant() : null,
                  rs.getObject("created_at", OffsetDateTime.class).toInstant());
            },
            "find product vat category");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  // ── Customer VAT Status ───────────────────────────────────────────────────

  /**
   * Records a customer's VAT status, replacing any existing one.
   *
   * @param cvs the status to store
   * @return the status as stored
   */
  public CustomerVatStatus upsertCustomerVatStatus(CustomerVatStatus cvs) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO customer_vat_status"
                      + " (id,tenant_id,customer_id,vat_number,vat_registered,"
                      + "  reverse_charge_eligible,country_code,legal_name,einvoice_scheme,einvoice_id)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id,customer_id)"
                      + " DO UPDATE SET vat_number=EXCLUDED.vat_number,"
                      + "  vat_registered=EXCLUDED.vat_registered,"
                      + "  reverse_charge_eligible=EXCLUDED.reverse_charge_eligible,"
                      + "  country_code=EXCLUDED.country_code,"
                      + "  legal_name=EXCLUDED.legal_name,"
                      + "  einvoice_scheme=EXCLUDED.einvoice_scheme,"
                      + "  einvoice_id=EXCLUDED.einvoice_id,"
                      + "  updated_at=now()")) {
            ps.setObject(1, cvs.id());
            ps.setObject(2, cvs.tenantId());
            ps.setObject(3, cvs.customerId());
            ps.setString(4, cvs.vatNumber());
            ps.setBoolean(5, cvs.vatRegistered());
            ps.setBoolean(6, cvs.reverseChargeEligible());
            ps.setString(7, cvs.countryCode());
            ps.setString(8, cvs.legalName());
            ps.setString(9, cvs.einvoiceScheme());
            ps.setString(10, cvs.einvoiceId());
            ps.executeUpdate();
          }
          return cvs;
        },
        "upsert customer vat status");
  }

  /**
   * Reads a customer's VAT status.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param customerId the customer to look up
   * @return the status, or empty when none is recorded
   */
  public Optional<CustomerVatStatus> findCustomerVatStatus(UUID tenantId, UUID customerId) {
    var list =
        query(
            "SELECT id,tenant_id,customer_id,vat_number,vat_registered,"
                + "  reverse_charge_eligible,country_code,legal_name,einvoice_scheme,einvoice_id,"
                + "  created_at,updated_at"
                + " FROM customer_vat_status WHERE tenant_id=? AND customer_id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, customerId);
            },
            rs ->
                new CustomerVatStatus(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("customer_id", UUID.class),
                    rs.getString("vat_number"),
                    rs.getBoolean("vat_registered"),
                    rs.getBoolean("reverse_charge_eligible"),
                    rs.getString("country_code"),
                    rs.getString("legal_name"),
                    rs.getString("einvoice_scheme"),
                    rs.getString("einvoice_id"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("updated_at", OffsetDateTime.class).toInstant()),
            "find customer vat status");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  // ── Price Lists ───────────────────────────────────────────────────────────

  /**
   * Inserts a price list.
   *
   * @param pl the price list to persist; its {@code id} must already be a UUIDv7
   * @return the price list as stored
   */
  public PriceList createPriceList(PriceList pl) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO price_lists"
                      + " (id,tenant_id,name,channel,currency,effective_from,effective_to,active,"
                      + " zone_id)"
                      + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, pl.id());
            ps.setObject(2, pl.tenantId());
            ps.setString(3, pl.name());
            ps.setString(4, pl.channel());
            ps.setString(5, pl.currency());
            ps.setObject(6, toOdt(pl.effectiveFrom()));
            ps.setObject(7, toOdt(pl.effectiveTo()));
            ps.setBoolean(8, pl.active());
            ps.setObject(9, pl.zoneId());
            ps.executeUpdate();
          } catch (SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState()))
              throw new ApiException(
                  409,
                  "PRICING_LIST_NAME_EXISTS",
                  "Price list '" + pl.name() + "' already exists",
                  List.of(),
                  sqle);
            throw sqle;
          }
          com.storeql.pricing.repo.AppliedPriceRepository.enqueue(
              c, pl.tenantId(), null, later(pl.effectiveFrom()), "PRICE_LIST_STARTS");
          if (pl.effectiveTo() != null) {
            com.storeql.pricing.repo.AppliedPriceRepository.enqueue(
                c, pl.tenantId(), null, later(pl.effectiveTo()), "PRICE_LIST_ENDS");
          }
          return pl;
        },
        "create price list");
  }

  /**
   * Keyset page of price lists: rows strictly after the cursor in (created_at, id) order.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param afterCreatedAt cursor timestamp, or {@code null} for the first page
   * @param afterId cursor id, breaking ties on identical timestamps
   * @param limit maximum rows; callers pass one more than the page size to detect a next page
   * @return the page of price lists
   */
  public List<PriceList> findPriceLists(
      UUID tenantId, Instant afterCreatedAt, UUID afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id,tenant_id,name,channel,currency,effective_from,effective_to,active,"
                + "created_at,zone_id FROM price_lists WHERE tenant_id=?");
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
        this::mapPriceList,
        "list price lists");
  }

  /**
   * Looks a price list up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the price list to fetch
   * @return the price list, or empty when it does not exist in this tenant
   */
  public Optional<PriceList> findPriceList(UUID tenantId, UUID id) {
    var list =
        query(
            "SELECT id,tenant_id,name,channel,currency,effective_from,effective_to,active,created_at,"
                + " zone_id FROM price_lists WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            this::mapPriceList,
            "find price list");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  private PriceList mapPriceList(java.sql.ResultSet rs) throws java.sql.SQLException {
    OffsetDateTime effTo = rs.getObject("effective_to", OffsetDateTime.class);
    return new PriceList(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("channel"),
        rs.getString("currency"),
        rs.getObject("effective_from", OffsetDateTime.class).toInstant(),
        effTo != null ? effTo.toInstant() : null,
        rs.getBoolean("active"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("zone_id", UUID.class));
  }

  // ── Price List Items ──────────────────────────────────────────────────────

  /**
   * Sets a variant's price on a price list and writes the {@code PriceChanged} event atomically.
   *
   * @param item the price to store, keyed by price list, variant and minimum quantity
   * @param event the outbox row to commit alongside the write
   * @return the item as stored
   */
  public PriceListItem upsertPriceListItem(PriceListItem item, OutboxRow event) {
    return inTx(
        c -> {
          writePriceListItemTx(c, item);
          insertOutbox(c, event);
          return item;
        },
        "upsert price list item");
  }

  /**
   * The item write on a caller's transaction: the price, its history row and the evaluation the
   * applied-price ledger owes. Shared with a repricing proposal's apply (03.x), which decides the
   * proposal and moves the price atomically; the caller adds the outbox event.
   */
  static void writePriceListItemTx(java.sql.Connection c, PriceListItem item) throws SQLException {
    // 03.12: an item priced before its history was kept starts it with the price it stood at.
    try (var ps =
        c.prepareStatement(
            "SELECT pli.id, pli.price, pli.updated_at FROM price_list_items pli"
                + " WHERE pli.tenant_id = ? AND pli.price_list_id = ? AND pli.variant_id = ?"
                + " AND pli.min_qty = ? AND NOT EXISTS (SELECT 1 FROM price_list_item_prices v"
                + " WHERE v.tenant_id = pli.tenant_id AND v.price_list_item_id = pli.id)"
                + " FOR UPDATE OF pli")) {
      ps.setObject(1, item.tenantId());
      ps.setObject(2, item.priceListId());
      ps.setObject(3, item.variantId());
      ps.setBigDecimal(4, item.minQty());
      try (var rs = ps.executeQuery()) {
        if (rs.next()) {
          insertItemPriceTx(
              c,
              item.tenantId(),
              rs.getObject("id", UUID.class),
              rs.getBigDecimal("price"),
              rs.getObject("updated_at", OffsetDateTime.class));
        }
      }
    }
    UUID itemId;
    try (var ps =
        c.prepareStatement(
            "INSERT INTO price_list_items"
                + " (id,tenant_id,price_list_id,variant_id,price,min_qty)"
                + " VALUES (?,?,?,?,?,?)"
                + " ON CONFLICT (tenant_id,price_list_id,variant_id,min_qty)"
                + " DO UPDATE SET price=EXCLUDED.price, updated_at=now()"
                + " RETURNING id")) {
      ps.setObject(1, item.id());
      ps.setObject(2, item.tenantId());
      ps.setObject(3, item.priceListId());
      ps.setObject(4, item.variantId());
      ps.setBigDecimal(5, item.price());
      ps.setBigDecimal(6, item.minQty());
      try (var rs = ps.executeQuery()) {
        if (!rs.next()) throw new SQLException("price list item upsert returned no row");
        itemId = rs.getObject("id", UUID.class);
      }
    }
    // Stamped once the row is locked, so two writers' prices are ordered as they committed.
    insertItemPriceTx(c, item.tenantId(), itemId, item.price(), null);
    com.storeql.pricing.repo.AppliedPriceRepository.enqueue(
        c, item.tenantId(), item.variantId(), null, "PRICE_SET");
  }

  /** Appends a price to a list item's history, from {@code validFrom} or, when null, from now. */
  private static void insertItemPriceTx(
      java.sql.Connection c, UUID tenantId, UUID itemId, BigDecimal price, OffsetDateTime validFrom)
      throws java.sql.SQLException {
    try (var ps =
        c.prepareStatement(
            "INSERT INTO price_list_item_prices (id, tenant_id, price_list_item_id, price, valid_from)"
                + " VALUES (?, ?, ?, ?, COALESCE(?::timestamptz, clock_timestamp()))")) {
      ps.setObject(1, com.storeql.ids.Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, itemId);
      ps.setBigDecimal(4, price);
      ps.setObject(5, validFrom);
      ps.executeUpdate();
    }
  }

  /**
   * Lists the priced variants on one price list, ordered by variant then quantity break.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param priceListId the price list whose items to list
   * @return the items
   */
  public List<PriceListItem> findPriceListItems(UUID tenantId, UUID priceListId) {
    return query(
        "SELECT id,tenant_id,price_list_id,variant_id,price,min_qty,created_at,updated_at"
            + " FROM price_list_items WHERE tenant_id=? AND price_list_id=? ORDER BY variant_id,min_qty",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, priceListId);
        },
        PricingRepository::mapPriceListItem,
        "list price list items");
  }

  /**
   * Resolve best price: active price list matching channel + qty-break tier, lowest price wins.
   *
   * <p>Lowest wins rather than most-specific, so overlapping price lists cannot overcharge: a
   * shopper gets the best price any applicable list offers.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param variantId the variant being priced
   * @param channel the sales channel, matched against the list's channel or {@code ALL}
   * @param qty the quantity, which selects the qty-break tier
   * @return the winning item, or empty when no active price covers the variant
   */
  public Optional<PriceListItem> resolveBasePrice(
      UUID tenantId, UUID variantId, String channel, BigDecimal qty) {
    return resolveBasePrice(tenantId, variantId, channel, qty, Instant.now());
  }

  /**
   * As {@link #resolveBasePrice(UUID, UUID, String, BigDecimal)}, with the price lists in force at
   * {@code at} (03.12: the applied-price ledger evaluates a scheduled start or end as of its
   * moment).
   */
  public Optional<PriceListItem> resolveBasePrice(
      UUID tenantId, UUID variantId, String channel, BigDecimal qty, Instant at) {
    return basePrice(tenantId, variantId, channel, qty, at, false, null);
  }

  /**
   * The base price at a store (03.x): a price list bound to the store's price zone beats the
   * tenant-wide list; a store in no zone, or no store, gets the tenant-wide list alone.
   */
  public Optional<PriceListItem> resolveBasePrice(
      UUID tenantId, UUID variantId, String channel, BigDecimal qty, Instant at, UUID storeId) {
    return basePrice(tenantId, variantId, channel, qty, at, false, storeId);
  }

  /**
   * As {@link #resolveBasePrice(UUID, UUID, String, BigDecimal, Instant)}, as the lists stood at
   * {@code at}: made by then, switched on then, and at the price each item carried then (03.12).
   */
  public Optional<PriceListItem> resolveBasePriceAsOf(
      UUID tenantId, UUID variantId, String channel, BigDecimal qty, Instant at) {
    return basePrice(tenantId, variantId, channel, qty, at, true, null);
  }

  /** As {@link #resolveBasePrice(UUID, UUID, String, BigDecimal, Instant, UUID)}, as recorded. */
  public Optional<PriceListItem> resolveBasePriceAsOf(
      UUID tenantId, UUID variantId, String channel, BigDecimal qty, Instant at, UUID storeId) {
    return basePrice(tenantId, variantId, channel, qty, at, true, storeId);
  }

  private Optional<PriceListItem> basePrice(
      UUID tenantId,
      UUID variantId,
      String channel,
      BigDecimal qty,
      Instant at,
      boolean asOf,
      UUID storeId) {
    String price = asOf ? "h.price" : "pli.price";
    var list =
        query(
            "SELECT pli.id, pli.tenant_id, pli.price_list_id, pli.variant_id, "
                + price
                + " AS price, pli.min_qty, pli.created_at, pli.updated_at"
                + " FROM price_list_items pli"
                + " JOIN price_lists pl ON pl.id = pli.price_list_id"
                + (asOf ? ITEM_PRICE_AS_OF : "")
                + " WHERE pli.tenant_id = ?"
                + "   AND pli.variant_id = ?"
                + "   AND (pl.channel = ? OR pl.channel = 'ALL')"
                // 03.x: the store's zone's list, or the tenant-wide one; never another zone's.
                + (storeId == null
                    ? "   AND pl.zone_id IS NULL"
                    : "   AND (pl.zone_id IS NULL OR pl.zone_id IN (SELECT z.zone_id"
                        + " FROM price_zone_stores z WHERE z.tenant_id = pli.tenant_id"
                        + " AND z.store_id = ?))")
                + (asOf
                    ? "   AND "
                        + activeAsOf("pl", "PRICE_LIST")
                        + "   AND pl.created_at <= ? AND pli.created_at <= ? AND h.price IS NOT NULL"
                    : "   AND pl.active = TRUE")
                + "   AND pl.effective_from <= ?"
                + "   AND (pl.effective_to IS NULL OR pl.effective_to > ?)"
                + "   AND pli.min_qty <= ?"
                + " ORDER BY (pl.zone_id IS NULL), pli.min_qty DESC, "
                + price
                + " ASC"
                + " LIMIT 1",
            ps -> {
              int i = 1;
              if (asOf) ps.setObject(i++, toOdt(at));
              ps.setObject(i++, tenantId);
              ps.setObject(i++, variantId);
              ps.setString(i++, channel);
              if (storeId != null) ps.setObject(i++, storeId);
              if (asOf) {
                for (int k = 0; k < 4; k++) ps.setObject(i++, toOdt(at));
              }
              ps.setObject(i++, toOdt(at));
              ps.setObject(i++, toOdt(at));
              ps.setBigDecimal(i, qty);
            },
            PricingRepository::mapPriceListItem,
            "resolve base price");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  private static PriceListItem mapPriceListItem(java.sql.ResultSet rs)
      throws java.sql.SQLException {
    return new PriceListItem(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("price_list_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("price"),
        rs.getBigDecimal("min_qty"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  /** The price each list item carried at a moment: from its history, or as it is with none. */
  private static final String ITEM_PRICE_AS_OF =
      " CROSS JOIN LATERAL (SELECT COALESCE("
          + "(SELECT v.price FROM price_list_item_prices v WHERE v.tenant_id = pli.tenant_id"
          + " AND v.price_list_item_id = pli.id AND v.valid_from <= ?"
          + " ORDER BY v.valid_from DESC, v.id DESC LIMIT 1),"
          + " CASE WHEN NOT EXISTS (SELECT 1 FROM price_list_item_prices v"
          + " WHERE v.tenant_id = pli.tenant_id AND v.price_list_item_id = pli.id)"
          + " THEN pli.price END) AS price) h";

  /**
   * Whether a price list or promotion was switched on at a moment (03.12): as the last switch
   * thrown by then left it; before its first switch, the opposite of what that switched it to;
   * never switched, as it is. Binds the moment twice.
   */
  private static String activeAsOf(String alias, String subjectType) {
    String subject =
        " FROM promotion_status_changes s WHERE s.tenant_id = "
            + alias
            + ".tenant_id AND s.subject_type = '"
            + subjectType
            + "' AND s.subject_id = "
            + alias
            + ".id";
    return "COALESCE((SELECT s.active"
        + subject
        + " AND s.changed_at <= ? ORDER BY s.changed_at DESC, s.id DESC LIMIT 1),"
        + " (SELECT NOT s.active"
        + subject
        + " AND s.changed_at > ? ORDER BY s.changed_at, s.id LIMIT 1), "
        + alias
        + ".active)";
  }

  /**
   * Maps a unique-key clash to the 409 it is, rather than letting it surface as a 500.
   *
   * <p>The clash that matters is the coupon code: two promotions sharing one would put the engine
   * back where this rebuild found it, with which offer a customer got decided by an accident of
   * ordering. Without this the caller is told the server broke, which is both wrong and — per SJ-D9
   * — indistinguishable from a real fault to whoever is reading the alerts.
   */
  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState()))
      return new ApiException(
          409,
          "PRICING_COUPON_CODE_TAKEN",
          "another promotion in this tenant already uses that coupon code (codes are matched"
              + " case-insensitively)",
          List.of(),
          e);
    return dbError(what, e);
  }

  // ── Promotions ────────────────────────────────────────────────────────────

  /**
   * Inserts a promotion and writes its activation event atomically.
   *
   * @param p the promotion to persist; its {@code id} must already be a UUIDv7
   * @param event the outbox row to commit alongside the insert
   * @return the promotion as stored
   */
  public Promotion createPromotion(Promotion p, OutboxRow event) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO promotions"
                      + " (id,tenant_id,store_id,name,type,value,min_order_amount,"
                      + "  channel,active,starts_at,ends_at,priority,exclusive,coupon_code,"
                      + "  max_redemptions,max_per_customer,buy_qty,get_qty,get_discount_pct)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, p.id());
            ps.setObject(2, p.tenantId());
            ps.setObject(3, p.storeId());
            ps.setString(4, p.name());
            ps.setString(5, p.type());
            ps.setBigDecimal(6, p.value());
            ps.setBigDecimal(7, p.minOrderAmount());
            ps.setString(8, p.channel());
            ps.setBoolean(9, p.active());
            ps.setObject(10, toOdt(p.startsAt()));
            ps.setObject(11, toOdt(p.endsAt()));
            ps.setInt(12, p.priority());
            ps.setBoolean(13, p.exclusive());
            ps.setString(14, p.couponCode());
            setIntOrNull(ps, 15, p.maxRedemptions());
            setIntOrNull(ps, 16, p.maxPerCustomer());
            ps.setBigDecimal(17, p.buyQty());
            ps.setBigDecimal(18, p.getQty());
            ps.setBigDecimal(19, p.getDiscountPct());
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          com.storeql.pricing.repo.AppliedPriceRepository.enqueue(
              c, p.tenantId(), null, later(p.startsAt()), "PROMOTION_STARTS");
          if (p.endsAt() != null) {
            com.storeql.pricing.repo.AppliedPriceRepository.enqueue(
                c, p.tenantId(), null, later(p.endsAt()), "PROMOTION_ENDS");
          }
          return p;
        },
        "create promotion");
  }

  /**
   * Switches a promotion or a price list on or off, and records who did it and why, atomically
   * (SJ-D33).
   *
   * <p>The two halves must not be separable. A promotion that stops running with no record of who
   * stopped it is a discount that vanished from the shop floor with nobody accountable, and a trail
   * row written for a switch that did not throw is worse than no trail at all.
   *
   * <p>The guard sits in the {@code WHERE} clause rather than in a preceding read, on the same
   * reasoning as every other state transition in this codebase: two people stopping the same
   * promotion at once must not both write a trail row claiming they were the one who did it.
   *
   * @param table the physical table — {@code promotions} or {@code price_lists}, chosen by the
   *     caller from a closed set, never from user input
   * @param change the append-only trail row, carrying the state being moved TO
   * @return {@code true} if this call changed the state; {@code false} if it was already there
   */
  public boolean setActive(String table, Domain.StatusChange change) {
    if (!"promotions".equals(table) && !"price_lists".equals(table)) {
      throw new IllegalArgumentException("not a switchable table: " + table);
    }
    return inTx(
        c -> {
          int rows;
          try (var ps =
              c.prepareStatement(
                  "UPDATE " + table + " SET active=? WHERE tenant_id=? AND id=? AND active<>?")) {
            ps.setBoolean(1, change.active());
            ps.setObject(2, change.tenantId());
            ps.setObject(3, change.subjectId());
            ps.setBoolean(4, change.active());
            rows = ps.executeUpdate();
          }
          if (rows == 0) return false;
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO promotion_status_changes"
                      + " (id,tenant_id,subject_type,subject_id,active,reason,changed_by)"
                      + " VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, change.id());
            ps.setObject(2, change.tenantId());
            ps.setString(3, change.subjectType());
            ps.setObject(4, change.subjectId());
            ps.setBoolean(5, change.active());
            ps.setString(6, change.reason());
            ps.setObject(7, change.changedBy());
            ps.executeUpdate();
          }
          com.storeql.pricing.repo.AppliedPriceRepository.enqueue(
              c,
              change.tenantId(),
              null,
              Instant.now(),
              change.subjectType() + (change.active() ? "_ACTIVATED" : "_DEACTIVATED"));
          return true;
        },
        "set active");
  }

  /** Whether a promotion or price list exists for this tenant, and whether it is currently live. */
  public Boolean findActive(String table, UUID tenantId, UUID id) {
    if (!"promotions".equals(table) && !"price_lists".equals(table)) {
      throw new IllegalArgumentException("not a switchable table: " + table);
    }
    var rows =
        query(
            "SELECT active FROM " + table + " WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            rs -> rs.getBoolean("active"),
            "read active");
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** The on/off history for one promotion or price list, newest first. Append-only. */
  public List<Domain.StatusChange> findStatusChanges(String subjectType, UUID tenantId, UUID id) {
    return query(
        "SELECT id,tenant_id,subject_type,subject_id,active,reason,changed_by,changed_at"
            + " FROM promotion_status_changes"
            + " WHERE tenant_id=? AND subject_type=? AND subject_id=?"
            + " ORDER BY changed_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, subjectType);
          ps.setObject(3, id);
        },
        rs ->
            new Domain.StatusChange(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class),
                rs.getBoolean("active"),
                rs.getString("reason"),
                rs.getObject("changed_by", UUID.class),
                rs.getObject("changed_at", java.time.OffsetDateTime.class).toInstant()),
        "find status changes");
  }

  /**
   * Every promotion live for this tenant, store, channel and instant — the whole candidate list,
   * for {@link com.storeql.pricing.service.PromotionEngine} to choose between.
   *
   * <p>Replaces a query that ended {@code ORDER BY p.value DESC LIMIT 1}, which decided the winner
   * in SQL by comparing a PERCENT's value (15, meaning 15%) against a FLAT's (20, meaning £20) as
   * though they shared a unit. Which offer a customer got therefore depended on a comparison
   * between a percentage and a sum of money. Ordering is now the engine's job and is done on an
   * explicit {@code priority}.
   *
   * <p><b>The store filter is new and was a live defect.</b> {@code promotions.store_id} has been
   * stored since V1 and filtered nowhere, so a promotion created for one shop ran in every shop of
   * the tenant. A NULL store_id still means "all stores", which is what the column was for.
   *
   * @param storeId the store being priced, or null to consider only tenant-wide promotions
   */
  public List<Promotion> findCandidatePromotions(
      UUID tenantId, UUID storeId, String channel, Instant now) {
    return candidatePromotions(tenantId, storeId, channel, now, false);
  }

  /**
   * As {@link #findCandidatePromotions}, as they stood at {@code at}: made and switched on by then
   * (03.12).
   */
  public List<Promotion> findCandidatePromotionsAsOf(
      UUID tenantId, UUID storeId, String channel, Instant at) {
    return candidatePromotions(tenantId, storeId, channel, at, true);
  }

  private List<Promotion> candidatePromotions(
      UUID tenantId, UUID storeId, String channel, Instant now, boolean asOf) {
    return query(
        "SELECT p.id, p.tenant_id, p.store_id, p.name, p.type, p.value,"
            + "  p.min_order_amount, p.channel, p.active, p.starts_at, p.ends_at, p.created_at,"
            + "  p.priority, p.exclusive, p.coupon_code, p.max_redemptions, p.max_per_customer,"
            + "  p.buy_qty, p.get_qty, p.get_discount_pct"
            + " FROM promotions p"
            + " WHERE p.tenant_id = ?"
            + (asOf
                ? "   AND " + activeAsOf("p", "PROMOTION") + " AND p.created_at <= ?"
                : "   AND p.active = TRUE")
            + "   AND p.starts_at <= ?"
            + "   AND (p.ends_at IS NULL OR p.ends_at > ?)"
            + "   AND (p.channel = ? OR p.channel = 'ALL')"
            + "   AND (p.store_id IS NULL"
            + (storeId != null ? " OR p.store_id = ?)" : ")")
            + " ORDER BY p.priority ASC, p.id ASC",
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (asOf) {
            for (int k = 0; k < 3; k++) ps.setObject(i++, toOdt(now));
          }
          ps.setObject(i++, toOdt(now));
          ps.setObject(i++, toOdt(now));
          ps.setString(i++, channel);
          if (storeId != null) ps.setObject(i, storeId);
        },
        this::mapPromotion,
        "find candidate promotions");
  }

  /**
   * The variants each of these promotions is scoped to.
   *
   * <p>A promotion with an ALL row, or with no scope rows at all, is absent from the result — the
   * engine reads a missing entry as "everything", so an unscoped promotion cannot accidentally
   * become a scoped-to-nothing one.
   *
   * <p><b>CATEGORY rows are deliberately not resolved here</b> and the service rejects creating
   * them: the variant→category mapping belongs to product-svc, which publishes no catalogue event
   * for pricing-svc to project (golden rule #1 forbids reading its tables). Until it does, a
   * CATEGORY promotion cannot be honoured — and the previous engine's answer to that was to accept
   * one, store it, and never fire it.
   */
  public Map<UUID, Set<UUID>> findPromotionVariantScopes(UUID tenantId, List<UUID> promotionIds) {
    return findPromotionVariantScopes(tenantId, promotionIds, null);
  }

  /**
   * As {@link #findPromotionVariantScopes(UUID, List)}; with {@code asOf}, only the scope rows
   * added by then (03.12).
   */
  public Map<UUID, Set<UUID>> findPromotionVariantScopes(
      UUID tenantId, List<UUID> promotionIds, Instant asOf) {
    if (promotionIds.isEmpty()) return Map.of();
    String placeholders = String.join(",", java.util.Collections.nCopies(promotionIds.size(), "?"));
    Map<UUID, Set<UUID>> out = new java.util.LinkedHashMap<>();
    Set<UUID> unscoped = new java.util.HashSet<>();
    query(
        "SELECT promotion_id, scope_type, scope_id FROM promotion_items"
            + " WHERE tenant_id = ? AND promotion_id IN ("
            + placeholders
            + ")"
            + (asOf == null ? "" : " AND created_at <= ?"),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          for (UUID id : promotionIds) ps.setObject(i++, id);
          if (asOf != null) ps.setObject(i, toOdt(asOf));
        },
        rs -> {
          UUID promo = rs.getObject("promotion_id", UUID.class);
          String scopeType = rs.getString("scope_type");
          UUID scopeId = rs.getObject("scope_id", UUID.class);
          if (PromotionItem.SCOPE_VARIANT.equals(scopeType) && scopeId != null) {
            out.computeIfAbsent(promo, k -> new java.util.LinkedHashSet<>()).add(scopeId);
          } else if (PromotionItem.SCOPE_CATEGORY.equals(scopeType) && scopeId != null) {
            // Resolved through the catalogue product-svc announces (03.8): every variant of every
            // product whose category path carries this category, so a parent's scope reaches its
            // children's products. A category nothing was announced for adds no variant — and the
            // set stays present but empty, which the engine reads as "nothing", not "everything".
            out.computeIfAbsent(promo, k -> new java.util.LinkedHashSet<>())
                .addAll(findVariantsInCategory(tenantId, scopeId));
          } else {
            // ALL (or a malformed row): this promotion is not variant-scoped at all.
            unscoped.add(promo);
          }
          return promo;
        },
        "find promotion scopes");
    // An ALL row beats any VARIANT rows alongside it — "everything" is not narrowed by also
    // naming a few things.
    for (UUID id : unscoped) out.remove(id);
    return out;
  }

  /**
   * Usage already spent per promotion, so the engine can reject an exhausted coupon with a reason
   * rather than skipping it silently.
   *
   * @param customerId the shopper, or null for a guest — a per-customer cap cannot bind on a caller
   *     with no identity, and pretending otherwise would cap every guest collectively
   * @return promotion id to a reason code, for the promotions that may no longer be used
   */
  public Map<UUID, String> findExhaustedPromotions(
      UUID tenantId, List<Promotion> candidates, UUID customerId) {
    Map<UUID, String> out = new java.util.LinkedHashMap<>();
    for (Promotion p : candidates) {
      if (p.maxRedemptions() != null) {
        long used = countRedemptions(tenantId, p.id(), null);
        if (used >= p.maxRedemptions()) {
          out.put(p.id(), "COUPON_EXHAUSTED");
          continue;
        }
      }
      if (p.maxPerCustomer() != null && customerId != null) {
        long mine = countRedemptions(tenantId, p.id(), customerId);
        if (mine >= p.maxPerCustomer()) out.put(p.id(), "COUPON_LIMIT_REACHED");
      }
    }
    return out;
  }

  private long countRedemptions(UUID tenantId, UUID promotionId, UUID customerId) {
    List<Long> n =
        query(
            "SELECT COUNT(*) AS n FROM promotion_redemptions"
                + " WHERE tenant_id = ? AND promotion_id = ?"
                + (customerId != null ? " AND customer_id = ?" : ""),
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, promotionId);
              if (customerId != null) ps.setObject(3, customerId);
            },
            rs -> rs.getLong("n"),
            "count promotion redemptions");
    return n.isEmpty() ? 0L : n.get(0);
  }

  /**
   * Records that a promotion was used on an order.
   *
   * <p>Idempotent on {@code (tenant, promotion, order)} via a unique index: a retried checkout, or
   * an offline POS sale replaying its writes, must not burn a second use of a coupon. That is
   * SJ-D15's lesson applied before the defect rather than after it — the question is not whether
   * this code is correct but what a replay of it does.
   *
   * @return true if this call recorded the redemption, false if it had already been recorded
   */
  public boolean recordRedemption(
      UUID tenantId,
      UUID promotionId,
      UUID orderId,
      UUID customerId,
      java.math.BigDecimal amount,
      String currency) {
    try {
      exec(
          "INSERT INTO promotion_redemptions"
              + " (id, tenant_id, promotion_id, order_id, customer_id, amount, currency)"
              + " VALUES (?,?,?,?,?,?,?)"
              + " ON CONFLICT (tenant_id, promotion_id, order_id) DO NOTHING",
          ps -> {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setObject(3, promotionId);
            ps.setObject(4, orderId);
            ps.setObject(5, customerId);
            ps.setBigDecimal(6, amount);
            ps.setString(7, currency);
          },
          "record promotion redemption");
      return true;
    } catch (RuntimeException e) {
      // ON CONFLICT already makes this a no-op; the catch is for the race that beats it.
      return false;
    }
  }

  /**
   * Every promotion flagged active in the tenant, highest priority first.
   *
   * <p>Applies no date filter — a promotion whose window has not opened, or has closed, still
   * appears. Use {@link #findCandidatePromotions} for the set the engine may actually apply.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @return the active promotions
   */
  public List<Promotion> findAllActivePromotions(UUID tenantId) {
    return query(
        "SELECT id,tenant_id,store_id,name,type,value,min_order_amount,"
            + "  channel,active,starts_at,ends_at,created_at,priority,exclusive,coupon_code,"
            + "  max_redemptions,max_per_customer,buy_qty,get_qty,get_discount_pct"
            + " FROM promotions WHERE tenant_id=? AND active=TRUE"
            + " ORDER BY priority ASC, starts_at DESC",
        ps -> ps.setObject(1, tenantId),
        this::mapPromotion,
        "list active promotions");
  }

  /**
   * The promotions that touch a store on or after {@code from}: the store's own and the
   * business-wide ones, on or off, whose end date had not passed by then. A promotion switched off
   * before {@code from} still appears here; the service reads the switch and drops it.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store
   * @param from the first moment of interest
   * @return the promotions, earliest start first
   */
  public List<Promotion> findPromotionsTouching(UUID tenantId, UUID storeId, Instant from) {
    return query(
        "SELECT id,tenant_id,store_id,name,type,value,min_order_amount,"
            + "  channel,active,starts_at,ends_at,created_at,priority,exclusive,coupon_code,"
            + "  max_redemptions,max_per_customer,buy_qty,get_qty,get_discount_pct"
            + " FROM promotions WHERE tenant_id=? AND (store_id IS NULL OR store_id=?)"
            + " AND (ends_at IS NULL OR ends_at >= ?)"
            + " ORDER BY starts_at ASC, id ASC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setObject(3, toOdt(from));
        },
        this::mapPromotion,
        "find promotions touching a store");
  }

  /**
   * When each of these promotions was last switched off, for those that ever were.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param promotionIds the promotions to ask about
   * @return promotion id to the moment of its latest switch-off
   */
  public Map<UUID, Instant> findLastSwitchOff(UUID tenantId, List<UUID> promotionIds) {
    if (promotionIds.isEmpty()) return Map.of();
    String placeholders = String.join(",", java.util.Collections.nCopies(promotionIds.size(), "?"));
    Map<UUID, Instant> out = new java.util.HashMap<>();
    query(
        "SELECT subject_id, MAX(changed_at) AS changed_at FROM promotion_status_changes"
            + " WHERE tenant_id = ? AND subject_type = 'PROMOTION' AND active = FALSE"
            + " AND subject_id IN ("
            + placeholders
            + ") GROUP BY subject_id",
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          for (UUID id : promotionIds) ps.setObject(i++, id);
        },
        rs -> {
          out.put(
              rs.getObject("subject_id", UUID.class),
              rs.getObject("changed_at", OffsetDateTime.class).toInstant());
          return null;
        },
        "find last switch-off");
    return out;
  }

  /**
   * Records what a promotion applies to.
   *
   * @param pi the scope row to persist; its {@code id} must already be a UUIDv7
   * @return the scope row as stored
   */
  public PromotionItem addPromotionItem(PromotionItem pi) {
    return inTx(
        c -> {
          if (PromotionItem.SCOPE_CATEGORY.equals(pi.scopeType()))
            lockCategoryScopes(c, pi.tenantId());
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO promotion_items (id,tenant_id,promotion_id,scope_type,scope_id)"
                      + " VALUES (?,?,?,?,?)")) {
            ps.setObject(1, pi.id());
            ps.setObject(2, pi.tenantId());
            ps.setObject(3, pi.promotionId());
            ps.setString(4, pi.scopeType());
            ps.setObject(5, pi.scopeId());
            ps.executeUpdate();
          }
          com.storeql.pricing.repo.AppliedPriceRepository.enqueue(
              c, pi.tenantId(), null, Instant.now(), "PROMOTION_SCOPED");
          return pi;
        },
        "add promotion item");
  }

  /**
   * Queues the evaluation a catalogue change needs (03.12): only where a category-scoped promotion
   * exists, because the catalogue decides nothing else about a price; and then as an overwrite,
   * because the catalogue keeps no history to read an earlier moment from. Scopes are never
   * removed, so a scope that could reach an earlier moment exists now; the lock orders this check
   * against a scope being added.
   */
  private static void enqueueCatalogueChange(
      java.sql.Connection c, UUID tenantId, UUID variantId, String cause)
      throws java.sql.SQLException {
    lockCategoryScopes(c, tenantId);
    try (var ps =
        c.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM promotion_items WHERE tenant_id = ? AND scope_type = ?)")) {
      ps.setObject(1, tenantId);
      ps.setString(2, PromotionItem.SCOPE_CATEGORY);
      try (var rs = ps.executeQuery()) {
        if (!rs.next() || !rs.getBoolean(1)) return;
      }
    }
    com.storeql.pricing.repo.AppliedPriceRepository.enqueue(
        c, tenantId, variantId, null, cause, true);
  }

  /**
   * Orders a tenant's category scopes and catalogue changes (03.12): a catalogue change either sees
   * a category scope committed, or commits before one is added and is then read by its evaluation.
   */
  private static void lockCategoryScopes(java.sql.Connection c, UUID tenantId)
      throws java.sql.SQLException {
    try (var ps = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      ps.setString(1, "category-scopes|" + tenantId);
      ps.execute();
    }
  }

  private Promotion mapPromotion(java.sql.ResultSet rs) throws java.sql.SQLException {
    OffsetDateTime endsAt = rs.getObject("ends_at", OffsetDateTime.class);
    return new Promotion(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("name"),
        rs.getString("type"),
        rs.getBigDecimal("value"),
        rs.getBigDecimal("min_order_amount"),
        rs.getString("channel"),
        rs.getBoolean("active"),
        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
        endsAt != null ? endsAt.toInstant() : null,
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getInt("priority"),
        rs.getBoolean("exclusive"),
        rs.getString("coupon_code"),
        intOrNull(rs, "max_redemptions"),
        intOrNull(rs, "max_per_customer"),
        rs.getBigDecimal("buy_qty"),
        rs.getBigDecimal("get_qty"),
        rs.getBigDecimal("get_discount_pct"));
  }

  /** getInt returns 0 for SQL NULL, and 0 is a meaningful cap. */
  private static Integer intOrNull(java.sql.ResultSet rs, String column)
      throws java.sql.SQLException {
    int v = rs.getInt(column);
    return rs.wasNull() ? null : v;
  }

  private static void setIntOrNull(java.sql.PreparedStatement ps, int index, Integer v)
      throws java.sql.SQLException {
    if (v == null) ps.setNull(index, java.sql.Types.INTEGER);
    else ps.setInt(index, v);
  }

  // ── Tax Transactions ──────────────────────────────────────────────────────

  /**
   * Appends one tax line to the append-only tax-transaction record.
   *
   * @param tt the transaction to persist; its {@code id} must already be a UUIDv7
   * @return the transaction as stored
   */
  public TaxTransaction recordTaxTransaction(TaxTransaction tt) {
    exec(
        "INSERT INTO tax_transactions"
            + " (id,tenant_id,order_id,order_line_id,variant_id,store_id,"
            + "  vat_code,vat_rate,net_amount,vat_amount,gross_amount,"
            + "  exempt,tax_point_date,invoice_ref)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, tt.id());
          ps.setObject(2, tt.tenantId());
          ps.setObject(3, tt.orderId());
          ps.setObject(4, tt.orderLineId());
          ps.setObject(5, tt.variantId());
          ps.setObject(6, tt.storeId());
          ps.setString(7, tt.vatCode());
          ps.setBigDecimal(8, tt.vatRate());
          ps.setBigDecimal(9, tt.netAmount());
          ps.setBigDecimal(10, tt.vatAmount());
          ps.setBigDecimal(11, tt.grossAmount());
          ps.setBoolean(12, tt.exempt());
          ps.setObject(13, toOdt(tt.taxPointDate()));
          ps.setString(14, tt.invoiceRef());
        },
        "record tax transaction");
    return tt;
  }

  /**
   * The tax lines recorded against one order, oldest first.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param orderId the order whose tax lines to read
   * @return the transactions, empty when none were recorded
   */
  public List<TaxTransaction> findTaxTransactionsByOrder(UUID tenantId, UUID orderId) {
    return query(
        "SELECT id,tenant_id,order_id,order_line_id,variant_id,store_id,"
            + "  vat_code,vat_rate,net_amount,vat_amount,gross_amount,"
            + "  exempt,tax_point_date,invoice_ref,created_at"
            + " FROM tax_transactions WHERE tenant_id=? AND order_id=? ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        this::mapTaxTransaction,
        "find tax transactions by order");
  }

  // ── VAT Return (MTD boxes 1–9) ────────────────────────────────────────────

  /**
   * Records one supplier invoice's VAT (SJ-D39), once: the event id is unique, so a redelivered
   * event inserts nothing and the caller learns it was already there.
   *
   * @param t the projection of the event
   * @return true when inserted; false when that event was already recorded
   */
  public boolean recordInputTaxOnce(com.storeql.pricing.domain.Domain.InputTaxTransaction t) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO input_tax_transactions (id, tenant_id, event_id, invoice_id, po_id,"
                      + " supplier_id, invoice_number, currency, net_amount, vat_amount,"
                      + " gross_amount, tax_point_date)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (event_id) DO NOTHING")) {
            ps.setObject(1, t.id());
            ps.setObject(2, t.tenantId());
            ps.setObject(3, t.eventId());
            ps.setObject(4, t.invoiceId());
            ps.setObject(5, t.poId());
            ps.setObject(6, t.supplierId());
            ps.setString(7, t.invoiceNumber());
            ps.setString(8, t.currency());
            ps.setBigDecimal(9, t.netAmount());
            ps.setBigDecimal(10, t.vatAmount());
            ps.setBigDecimal(11, t.grossAmount());
            ps.setObject(12, toOdt(t.taxPointDate()));
            return ps.executeUpdate() > 0;
          }
        },
        "record input tax");
  }

  /** Box 4: VAT reclaimed on purchases in the period, by invoice date (SJ-D39). */
  public BigDecimal sumInputVat(UUID tenantId, Instant from, Instant to) {
    var rows =
        query(
            "SELECT COALESCE(SUM(vat_amount), 0) AS total FROM input_tax_transactions"
                + " WHERE tenant_id=? AND tax_point_date >= ? AND tax_point_date < ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, toOdt(from));
              ps.setObject(3, toOdt(to));
            },
            rs -> rs.getBigDecimal("total"),
            "sum input vat");
    return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
  }

  /** Box 7: net value of purchases in the period, by invoice date (SJ-D39). */
  public BigDecimal sumNetPurchases(UUID tenantId, Instant from, Instant to) {
    var rows =
        query(
            "SELECT COALESCE(SUM(net_amount), 0) AS total FROM input_tax_transactions"
                + " WHERE tenant_id=? AND tax_point_date >= ? AND tax_point_date < ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, toOdt(from));
              ps.setObject(3, toOdt(to));
            },
            rs -> rs.getBigDecimal("total"),
            "sum net purchases");
    return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
  }

  /**
   * Aggregate output VAT and net sales for MTD boxes. Box 1 = output VAT on taxable supplies. Box 6
   * = total net sales (all supplies). Both exclude nothing — even exempt supplies count for Box 6.
   * Per HMRC VAT Notice 700 s.17.
   */
  public BigDecimal sumOutputVat(UUID tenantId, Instant from, Instant to) {
    var rows =
        query(
            "SELECT COALESCE(SUM(vat_amount), 0) AS total"
                + " FROM tax_transactions"
                + " WHERE tenant_id=? AND NOT exempt"
                + "   AND tax_point_date >= ? AND tax_point_date < ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, toOdt(from));
              ps.setObject(3, toOdt(to));
            },
            rs -> rs.getBigDecimal("total"),
            "sum output vat");
    return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
  }

  /**
   * Total net sales over a period — VAT return Box 6.
   *
   * <p>Unlike output VAT, this includes exempt supplies: Box 6 is total sales excluding VAT, not
   * total VATable sales.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param from inclusive lower bound on the tax point
   * @param to exclusive upper bound
   * @return the summed net amount, zero when nothing falls in the period
   */
  public BigDecimal sumNetSales(UUID tenantId, Instant from, Instant to) {
    var rows =
        query(
            "SELECT COALESCE(SUM(net_amount), 0) AS total"
                + " FROM tax_transactions"
                + " WHERE tenant_id=? AND tax_point_date >= ? AND tax_point_date < ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, toOdt(from));
              ps.setObject(3, toOdt(to));
            },
            rs -> rs.getBigDecimal("total"),
            "sum net sales");
    return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
  }

  // ── Gap #41: Price overrides ──────────────────────────────────────────────

  /**
   * Records a manual price override in the audit log.
   *
   * @param p the override to persist; its {@code id} must already be a UUIDv7
   * @return the override as stored
   */
  public PriceOverride insertPriceOverride(PriceOverride p) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO price_overrides"
                      + " (id,tenant_id,order_id,variant_id,store_id,original_price,override_price,override_reason,overridden_by)"
                      + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, p.id());
            ps.setObject(2, p.tenantId());
            ps.setObject(3, p.orderId());
            ps.setObject(4, p.variantId());
            ps.setObject(5, p.storeId());
            ps.setBigDecimal(6, p.originalPrice());
            ps.setBigDecimal(7, p.overridePrice());
            ps.setString(8, p.overrideReason());
            ps.setObject(9, p.overriddenBy());
            ps.executeUpdate();
          }
          return p;
        },
        "insert price override");
  }

  /**
   * Keyset page of price overrides (append-only audit log), newest first. {@code storeId} and
   * {@code variantId} filters can be combined. Replaces three previously separate branches, two of
   * which had no bound at all and one a hardcoded, non-paginated {@code LIMIT 200} that silently
   * dropped rows past it with no signal a further page existed.
   */
  public List<PriceOverride> listPriceOverrides(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      Instant afterCreatedAt,
      UUID afterId,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, order_id, variant_id, store_id, original_price,"
                + " override_price, override_reason, overridden_by, created_at"
                + " FROM price_overrides WHERE tenant_id=?");
    if (storeId != null) sql.append(" AND store_id=?");
    if (variantId != null) sql.append(" AND variant_id=?");
    if (afterCreatedAt != null && afterId != null) sql.append(" AND (created_at, id) < (?, ?)");
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (variantId != null) ps.setObject(i++, variantId);
          if (afterCreatedAt != null && afterId != null) {
            ps.setObject(i++, afterCreatedAt.atOffset(ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
        },
        this::mapPriceOverride,
        "list price overrides");
  }

  private PriceOverride mapPriceOverride(java.sql.ResultSet rs) throws java.sql.SQLException {
    var orderId = rs.getObject("order_id", UUID.class);
    var overriddenBy = rs.getObject("overridden_by", UUID.class);
    return new PriceOverride(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        orderId,
        rs.getObject("variant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getBigDecimal("original_price"),
        rs.getBigDecimal("override_price"),
        rs.getString("override_reason"),
        overriddenBy,
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  /** A scheduled moment, or now when it has already passed. */
  private static Instant later(Instant moment) {
    Instant now = Instant.now();
    return moment == null || moment.isBefore(now) ? now : moment;
  }

  private static OffsetDateTime toOdt(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  private TaxTransaction mapTaxTransaction(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new TaxTransaction(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getObject("order_line_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("vat_code"),
        rs.getBigDecimal("vat_rate"),
        rs.getBigDecimal("net_amount"),
        rs.getBigDecimal("vat_amount"),
        rs.getBigDecimal("gross_amount"),
        rs.getBoolean("exempt"),
        rs.getObject("tax_point_date", OffsetDateTime.class).toInstant(),
        rs.getString("invoice_ref"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  // ── Date-code markdown (05.4, 03.9) ──────────────────────────────────────

  private static final String MD_COLUMNS =
      "m.id, m.tenant_id, m.store_id, m.variant_id, m.batch_id, m.batch_no, m.expiry_date, m.qty,"
          + " m.currency, m.original_price, m.markdown_price, m.percent_off, m.reason,"
          + " m.label_code, m.status, m.applied_by, m.created_at, m.cancelled_at,"
          + " m.cancelled_by, m.cancel_reason,"
          + " COALESCE((SELECT SUM(r.qty) FROM markdown_redemptions r"
          + "   WHERE r.tenant_id = m.tenant_id AND r.markdown_id = m.id), 0) AS redeemed_qty";

  /**
   * The ladder steps for a store, or the tenant's when the store has none; empty when neither.
   *
   * @return the steps and where they came from, or empty
   */
  public Optional<Domain.MarkdownLadder> findLadder(UUID tenantId, UUID storeId) {
    if (storeId != null) {
      List<Domain.MarkdownStep> own = ladderSteps(tenantId, storeId);
      if (!own.isEmpty()) {
        return Optional.of(
            new Domain.MarkdownLadder(storeId, own, Domain.MarkdownLadder.SOURCE_STORE));
      }
    }
    List<Domain.MarkdownStep> tenant = ladderSteps(tenantId, null);
    if (tenant.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Domain.MarkdownLadder(null, tenant, Domain.MarkdownLadder.SOURCE_TENANT));
  }

  private List<Domain.MarkdownStep> ladderSteps(UUID tenantId, UUID storeId) {
    return query(
        "SELECT days_to_expiry, percent_off FROM markdown_ladders"
            + " WHERE tenant_id = ? AND store_id IS NOT DISTINCT FROM ?"
            + " ORDER BY days_to_expiry DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        rs -> new Domain.MarkdownStep(rs.getInt(1), rs.getBigDecimal(2)),
        "ladder steps");
  }

  /** Replaces a ladder wholesale: the steps are one decision, not a row each. */
  public void replaceLadder(
      UUID tenantId, UUID storeId, List<Domain.MarkdownStep> steps, UUID userId) {
    inTx(
        c -> {
          try (var del =
              c.prepareStatement(
                  "DELETE FROM markdown_ladders WHERE tenant_id = ? AND store_id IS NOT DISTINCT FROM ?")) {
            del.setObject(1, tenantId);
            del.setObject(2, storeId);
            del.executeUpdate();
          }
          for (Domain.MarkdownStep s : steps) {
            try (var ins =
                c.prepareStatement(
                    "INSERT INTO markdown_ladders (id, tenant_id, store_id, days_to_expiry,"
                        + " percent_off, created_by) VALUES (?,?,?,?,?,?)")) {
              ins.setObject(1, Ids.newId());
              ins.setObject(2, tenantId);
              ins.setObject(3, storeId);
              ins.setInt(4, s.daysToExpiry());
              ins.setBigDecimal(5, s.percentOff());
              ins.setObject(6, userId);
              ins.executeUpdate();
            }
          }
          return null;
        },
        "replace markdown ladder");
  }

  /**
   * Records a markdown, taking the sticker's item number from the tenant's series under its lock
   * and encoding the label; refused when the label would collide with a live sticker (the series
   * wraps at 100,000 and a tenant with that many live stickers has another problem).
   *
   * @param draft the markdown without its label
   * @return the markdown as stored, with its label
   */
  public Domain.Markdown createMarkdown(Domain.Markdown draft) {
    return inTx(
        c -> {
          try (var open =
              c.prepareStatement(
                  "INSERT INTO markdown_label_series (tenant_id, next_number) VALUES (?, 1)"
                      + " ON CONFLICT DO NOTHING")) {
            open.setObject(1, draft.tenantId());
            open.executeUpdate();
          }
          long number;
          try (var take =
              c.prepareStatement(
                  "UPDATE markdown_label_series SET next_number = next_number + 1"
                      + " WHERE tenant_id = ? RETURNING next_number - 1")) {
            take.setObject(1, draft.tenantId());
            try (ResultSet rs = take.executeQuery()) {
              if (!rs.next()) {
                throw new SQLException("markdown label series vanished");
              }
              number = rs.getLong(1);
            }
          }
          String label = MarkdownLabel.encode(number, draft.markdownPrice());
          try (var ins =
              c.prepareStatement(
                  "INSERT INTO markdowns (id, tenant_id, store_id, variant_id, batch_id, batch_no,"
                      + " expiry_date, qty, currency, original_price, markdown_price, percent_off,"
                      + " reason, label_code, status, applied_by)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ins.setObject(1, draft.id());
            ins.setObject(2, draft.tenantId());
            ins.setObject(3, draft.storeId());
            ins.setObject(4, draft.variantId());
            ins.setObject(5, draft.batchId());
            ins.setString(6, draft.batchNo());
            ins.setObject(7, draft.expiryDate());
            ins.setBigDecimal(8, draft.qty());
            ins.setString(9, draft.currency());
            ins.setBigDecimal(10, draft.originalPrice());
            ins.setBigDecimal(11, draft.markdownPrice());
            ins.setBigDecimal(12, draft.percentOff());
            ins.setString(13, draft.reason());
            ins.setString(14, label);
            ins.setString(15, Domain.Markdown.STATUS_ACTIVE);
            ins.setObject(16, draft.appliedBy());
            ins.executeUpdate();
          } catch (SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState())) {
              ApiException collision =
                  ApiException.conflict(
                      "PRICING_MARKDOWN_LABEL_COLLISION",
                      "the sticker code " + label + " is still live on another markdown");
              collision.initCause(sqle);
              throw collision;
            }
            throw sqle;
          }
          Domain.Markdown stored = findMarkdownTx(c, draft.tenantId(), draft.id());
          if (stored == null) {
            throw new SQLException("markdown vanished after insert");
          }
          return stored;
        },
        "create markdown");
  }

  private static Domain.Markdown findMarkdownTx(Connection c, UUID tenantId, UUID id)
      throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT " + MD_COLUMNS + " FROM markdowns m WHERE m.tenant_id = ? AND m.id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? mapMarkdown(rs) : null;
      }
    }
  }

  /**
   * @return the markdown, or empty when it is not this tenant's
   */
  public Optional<Domain.Markdown> findMarkdown(UUID tenantId, UUID id) {
    List<Domain.Markdown> rows =
        query(
            "SELECT " + MD_COLUMNS + " FROM markdowns m WHERE m.tenant_id = ? AND m.id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            PricingRepository::mapMarkdown,
            "find markdown");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** The live markdown behind a sticker's code, or empty. */
  public Optional<Domain.Markdown> findMarkdownByLabel(UUID tenantId, String labelCode) {
    List<Domain.Markdown> rows =
        query(
            "SELECT "
                + MD_COLUMNS
                + " FROM markdowns m"
                + " WHERE m.tenant_id = ? AND m.label_code = ? AND m.status = 'ACTIVE'",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, labelCode);
            },
            PricingRepository::mapMarkdown,
            "find markdown by label");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * A store's markdowns, newest first.
   *
   * @param status ACTIVE, CANCELLED, or null for all stored statuses
   */
  public List<Domain.Markdown> listMarkdowns(
      UUID tenantId, UUID storeId, String status, int limit) {
    return query(
        "SELECT "
            + MD_COLUMNS
            + " FROM markdowns m"
            + " WHERE m.tenant_id = ? AND m.store_id = ? AND (? IS NULL OR m.status = ?)"
            + " ORDER BY m.created_at DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setString(3, status);
          ps.setString(4, status);
          ps.setInt(5, limit);
        },
        PricingRepository::mapMarkdown,
        "list markdowns");
  }

  /** The active markdowns on a set of batches, for the plan to show beside the suggestion. */
  public List<Domain.Markdown> findActiveMarkdownsForBatches(UUID tenantId, List<UUID> batchIds) {
    if (batchIds.isEmpty()) {
      return List.of();
    }
    return query(
        "SELECT "
            + MD_COLUMNS
            + " FROM markdowns m"
            + " WHERE m.tenant_id = ? AND m.status = 'ACTIVE' AND m.batch_id = ANY (?)",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setArray(2, ps.getConnection().createArrayOf("uuid", batchIds.toArray()));
        },
        PricingRepository::mapMarkdown,
        "active markdowns for batches");
  }

  /**
   * Cancels a live markdown; the state guard is in the WHERE clause.
   *
   * @return whether it was ACTIVE and is now CANCELLED
   */
  public boolean cancelMarkdown(UUID tenantId, UUID id, String reason, UUID userId) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "UPDATE markdowns SET status = 'CANCELLED', cancelled_at = now(),"
                      + " cancelled_by = ?, cancel_reason = ?"
                      + " WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'")) {
            ps.setObject(1, userId);
            ps.setString(2, reason);
            ps.setObject(3, tenantId);
            ps.setObject(4, id);
            return ps.executeUpdate() > 0;
          }
        },
        "cancel markdown");
  }

  /**
   * Records what an order sold at a markdown, once per (markdown, order).
   *
   * @return whether the row was written now
   */
  public boolean recordMarkdownRedemption(
      UUID tenantId, UUID markdownId, UUID orderId, BigDecimal qty) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO markdown_redemptions (id, tenant_id, markdown_id, order_id, qty)"
                      + " VALUES (?,?,?,?,?) ON CONFLICT (tenant_id, markdown_id, order_id) DO NOTHING")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setObject(3, markdownId);
            ps.setObject(4, orderId);
            ps.setBigDecimal(5, qty);
            return ps.executeUpdate() > 0;
          }
        },
        "record markdown redemption");
  }

  private static Domain.Markdown mapMarkdown(ResultSet rs) throws SQLException {
    OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class);
    OffsetDateTime cancelled = rs.getObject("cancelled_at", OffsetDateTime.class);
    return new Domain.Markdown(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("batch_id", UUID.class),
        rs.getString("batch_no"),
        rs.getObject("expiry_date", LocalDate.class),
        rs.getBigDecimal("qty"),
        rs.getString("currency"),
        rs.getBigDecimal("original_price"),
        rs.getBigDecimal("markdown_price"),
        rs.getBigDecimal("percent_off"),
        rs.getString("reason"),
        rs.getString("label_code"),
        rs.getString("status"),
        rs.getObject("applied_by", UUID.class),
        created == null ? null : created.toInstant(),
        cancelled == null ? null : cancelled.toInstant(),
        rs.getObject("cancelled_by", UUID.class),
        rs.getString("cancel_reason"),
        rs.getBigDecimal("redeemed_qty"));
  }

  // ── the catalogue projection (03.8) ──────────────────────────────────────

  /**
   * The variants of every product whose category path carries a category.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param categoryId the category, or an ancestor of the products' categories
   * @return the variant ids, empty when nothing announced sits under it
   */
  public Set<UUID> findVariantsInCategory(UUID tenantId, UUID categoryId) {
    return new java.util.LinkedHashSet<>(
        query(
            "SELECT cv.variant_id FROM catalogue_variants cv"
                + " JOIN catalogue_products cp"
                + "   ON cp.tenant_id = cv.tenant_id AND cp.product_id = cv.product_id"
                + " WHERE cv.tenant_id = ? AND ? = ANY (cp.category_path)",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, categoryId);
            },
            rs -> rs.getObject("variant_id", UUID.class),
            "find variants in category"));
  }

  /**
   * Records a product's category path and its variants, once per event: what {@code
   * ProductCategorised} says. The path replaces what was known; the variants are added to it, never
   * removed by it — a delisted variant simply never appears in a basket.
   *
   * @return {@code true} when this event was processed now
   */
  public boolean projectProductCategorisedOnce(
      UUID eventId,
      String consumer,
      UUID tenantId,
      UUID productId,
      List<UUID> categoryPath,
      List<UUID> variantIds) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) return false;
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO catalogue_products (tenant_id, product_id, category_path, updated_at)"
                      + " VALUES (?, ?, ?, now()) ON CONFLICT (tenant_id, product_id)"
                      + " DO UPDATE SET category_path = EXCLUDED.category_path, updated_at = now()")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, productId);
            ps.setArray(3, c.createArrayOf("uuid", categoryPath.toArray()));
            ps.executeUpdate();
          }
          for (UUID variantId : variantIds) {
            upsertCatalogueVariantTx(c, tenantId, variantId, productId);
            // A category-scoped promotion may now reach, or no longer reach, the variant (03.12).
            enqueueCatalogueChange(c, tenantId, variantId, "CATEGORISED");
          }
          return true;
        },
        "project product categorised");
  }

  /**
   * Records which product a new variant belongs to, once per event: what {@code VariantCreated}
   * says, so a variant added after its product was categorised still falls under the category.
   *
   * @return {@code true} when this event was processed now
   */
  public boolean projectVariantCreatedOnce(
      UUID eventId, String consumer, UUID tenantId, UUID variantId, UUID productId) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) return false;
          upsertCatalogueVariantTx(c, tenantId, variantId, productId);
          // A category-scoped promotion may now reach the variant (03.12).
          enqueueCatalogueChange(c, tenantId, variantId, "CATALOGUED");
          return true;
        },
        "project variant created");
  }

  private static void upsertCatalogueVariantTx(
      java.sql.Connection c, UUID tenantId, UUID variantId, UUID productId)
      throws java.sql.SQLException {
    try (var ps =
        c.prepareStatement(
            "INSERT INTO catalogue_variants (tenant_id, variant_id, product_id, updated_at)"
                + " VALUES (?, ?, ?, now()) ON CONFLICT (tenant_id, variant_id)"
                + " DO UPDATE SET product_id = EXCLUDED.product_id, updated_at = now()")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, variantId);
      ps.setObject(3, productId);
      ps.executeUpdate();
    }
  }

  // ── unit pricing (03.13) ───────────────────────────────────────────────────

  /**
   * Records a variant's measure once per event, placing the variant if it was not yet known.
   *
   * @param unit KG, L, M, SQM or EA; null with quantity when none is declared
   * @param version product-svc's version of the measure; an older one never replaces a newer
   * @return true when the measure now stands, false for an event already seen or superseded
   */
  public boolean projectVariantMeasuredOnce(
      UUID eventId,
      String consumer,
      UUID tenantId,
      UUID variantId,
      UUID productId,
      String soldBy,
      String unit,
      BigDecimal quantity,
      long version) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) return false;
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO catalogue_variants (tenant_id, variant_id, product_id, sold_by,"
                      + " measure_unit, measure_quantity, measure_version, measured_at, updated_at)"
                      + " VALUES (?, ?, ?, ?, ?, ?, ?, now(), now()) ON CONFLICT (tenant_id, variant_id)"
                      + " DO UPDATE SET product_id = EXCLUDED.product_id, sold_by = EXCLUDED.sold_by,"
                      + " measure_unit = EXCLUDED.measure_unit,"
                      + " measure_quantity = EXCLUDED.measure_quantity,"
                      + " measure_version = EXCLUDED.measure_version,"
                      + " measured_at = EXCLUDED.measured_at, updated_at = now()"
                      + " WHERE catalogue_variants.measure_version IS NULL"
                      + " OR EXCLUDED.measure_version > catalogue_variants.measure_version")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, variantId);
            ps.setObject(3, productId);
            ps.setString(4, soldBy);
            ps.setString(5, unit);
            ps.setBigDecimal(6, quantity);
            ps.setLong(7, version);
            if (ps.executeUpdate() == 0) return false;
          }
          // The variant may have been placed under a product, and so under its categories (03.12).
          enqueueCatalogueChange(c, tenantId, variantId, "CATALOGUED");
          return true;
        },
        "project variant measured");
  }

  /**
   * A variant's declared measure.
   *
   * @param tenantId owning tenant; the first condition of the query
   */
  public Optional<com.storeql.pricing.domain.Domain.Measure> findMeasure(
      UUID tenantId, UUID variantId) {
    return query(
            "SELECT measure_unit, measure_quantity FROM catalogue_variants"
                + " WHERE tenant_id = ? AND variant_id = ? AND measure_unit IS NOT NULL",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
            },
            rs ->
                new com.storeql.pricing.domain.Domain.Measure(rs.getString(1), rs.getBigDecimal(2)),
            "find variant measure")
        .stream()
        .findFirst();
  }

  /**
   * Variants with a price in a list in force now and no declared measure, by variant id.
   *
   * @param tenantId owning tenant; the first condition of the query
   */
  public List<com.storeql.pricing.domain.Domain.UnitPriceGap> unitPriceGaps(
      UUID tenantId, int limit) {
    return query(
        "SELECT DISTINCT pli.variant_id, cv.product_id, cv.variant_id IS NOT NULL AS catalogued"
            + " FROM price_list_items pli"
            + " JOIN price_lists pl ON pl.id = pli.price_list_id AND pl.tenant_id = pli.tenant_id"
            + " LEFT JOIN catalogue_variants cv"
            + " ON cv.tenant_id = pli.tenant_id AND cv.variant_id = pli.variant_id"
            + " WHERE pli.tenant_id = ? AND pl.active = TRUE AND pl.effective_from <= now()"
            + " AND (pl.effective_to IS NULL OR pl.effective_to > now())"
            + " AND cv.measure_unit IS NULL"
            + " ORDER BY pli.variant_id LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        rs ->
            new com.storeql.pricing.domain.Domain.UnitPriceGap(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBoolean(3)),
        "unit price gaps");
  }
}
