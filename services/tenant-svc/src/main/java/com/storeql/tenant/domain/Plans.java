package com.storeql.tenant.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plans and packaging (21.8): the platform's price list — what a business can be sold, for how much
 * in which currency, and what it includes.
 */
public final class Plans {

  private Plans() {}

  /** Being written: it can be changed freely and nobody is on it. */
  public static final String DRAFT = "DRAFT";

  /** Sold. */
  public static final String ACTIVE = "ACTIVE";

  /** Kept by the businesses that have it, offered to nobody. */
  public static final String RETIRED = "RETIRED";

  public static final Set<String> STATUSES = Set.of(DRAFT, ACTIVE, RETIRED);

  public static final String MONTH = "MONTH";
  public static final String YEAR = "YEAR";
  public static final Set<String> INTERVALS = Set.of(MONTH, YEAR);

  // ── what a plan can include ─────────────────────────────────────────────────

  /**
   * An entitlement key, and what it means to the person reading the price list. A key is either a
   * limit (how many) or a feature (whether at all), never both, and every key here is enforced
   * somewhere: a promise nobody keeps is worse than no promise.
   *
   * @param key what the API and the database call it
   * @param label what a person reads
   * @param limit true when the key carries a number, false when it is a yes or no
   * @param enforcedBy the service that refuses when it is exceeded, for the person maintaining this
   */
  public record Entitlement(String key, String label, boolean limit, String enforcedBy) {}

  /**
   * The code of the plan a business's sandbox sits on (22.8): seeded by migration, sold to nobody,
   * never the default, never given to a live business.
   */
  public static final String SANDBOX_CODE = "SANDBOX";

  public static final String STORES_MAX = "stores.max";
  public static final String STAFF_MAX = "staff.max";
  public static final String PRODUCTS_MAX = "products.max";
  public static final String FEATURE_STOREFRONT = "feature.storefront";

  /** API requests a minute, across every login and the online shop (21.11). */
  public static final String REQUESTS_PER_MINUTE = "requests.per-minute";

  /** Megabytes of product images the business may keep (21.11). */
  public static final String IMAGES_MB_MAX = "images.mb.max";

  /** Megabytes of supplier e-invoice documents the business may keep (21.11). */
  public static final String DOCUMENTS_MB_MAX = "documents.mb.max";

  /** Every key a plan may carry. Adding one here means adding the refusal that enforces it. */
  public static final List<Entitlement> CATALOGUE =
      List.of(
          new Entitlement(STORES_MAX, "Stores and warehouses", true, "tenant-svc"),
          new Entitlement(STAFF_MAX, "Staff logins", true, "iam-svc"),
          new Entitlement(PRODUCTS_MAX, "Products", true, "product-svc"),
          new Entitlement(
              FEATURE_STOREFRONT, "The online shop", false, "tenant-svc (storefront gate)"),
          new Entitlement(
              REQUESTS_PER_MINUTE,
              "API requests a minute",
              true,
              "gateway (TenantRateLimitFilter)"),
          new Entitlement(IMAGES_MB_MAX, "Product images (MB)", true, "product-svc"),
          new Entitlement(
              DOCUMENTS_MB_MAX, "Supplier e-invoice documents (MB)", true, "purchase-svc"));

  private static final Map<String, Entitlement> BY_KEY =
      CATALOGUE.stream()
          .collect(java.util.stream.Collectors.toUnmodifiableMap(Entitlement::key, e -> e));

  /** The entitlement a key names, or empty when the platform enforces no such thing. */
  public static java.util.Optional<Entitlement> entitlement(String key) {
    return java.util.Optional.ofNullable(BY_KEY.get(key == null ? "" : key.strip()));
  }

  // ── the plan itself ─────────────────────────────────────────────────────────

  /**
   * A plan.
   *
   * @param isDefault the plan a business that signs up is put on; at most one plan is
   * @param isPublic whether it appears on the price list a visitor can read
   */
  public record Plan(
      UUID id,
      String code,
      String name,
      String description,
      String status,
      String billingInterval,
      int trialDays,
      boolean isDefault,
      boolean isPublic,
      int sortOrder,
      UUID createdBy,
      Instant createdAt,
      Instant updatedAt) {

    public boolean sold() {
      return ACTIVE.equals(status);
    }
  }

  /**
   * A plan's price in one currency from a date. A price is never edited: a new row takes effect and
   * the old one stays, because an invoice raised last month must still be explicable next year.
   */
  public record Price(
      UUID id,
      UUID planId,
      String currency,
      BigDecimal amount,
      LocalDate effectiveFrom,
      UUID createdBy,
      Instant createdAt) {}

  /**
   * What a plan grants for one key: a limit, or a feature.
   *
   * @param limitValue how many; null means unlimited, and null on a feature row means it is a
   *     feature rather than a limit
   * @param enabled whether the feature is included; null on a limit row
   */
  public record Grant(String key, Long limitValue, Boolean enabled) {

    public boolean feature() {
      return enabled != null;
    }

    public boolean unlimited() {
      return !feature() && limitValue == null;
    }
  }

  /**
   * A plan with its prices and what it includes, as the price list and the console show it.
   *
   * @param meters what it includes of each meter (21.10)
   * @param meterPrices what each unit beyond that costs, per currency and from a date
   */
  public record PlanFile(
      Plan plan,
      List<Price> prices,
      List<Grant> grants,
      List<Meters.PlanMeter> meters,
      List<Meters.MeterPrice> meterPrices) {
    public PlanFile {
      prices = List.copyOf(prices);
      grants = List.copyOf(grants);
      meters = List.copyOf(meters);
      meterPrices = List.copyOf(meterPrices);
    }

    /** What this plan grants for a key: the row it carries, or nothing said. */
    public java.util.Optional<Grant> grant(String key) {
      return grants.stream().filter(g -> g.key().equals(key)).findFirst();
    }
  }

  /**
   * One limit of a business's plan against what it is using.
   *
   * @param used how many it has, or null when the service that knows could not be reached — said as
   *     unknown rather than guessed at
   */
  public record Usage(String key, String label, Long limitValue, Long used) {

    public boolean over() {
      return limitValue != null && used != null && used > limitValue;
    }
  }

  /**
   * The plan a business is on, with each limit against what it is using.
   *
   * @param plan null when it is on none
   * @param note why, when it is on none
   */
  public record TenantPlan(PlanFile plan, List<Usage> usage, String note) {
    public TenantPlan {
      usage = List.copyOf(usage);
    }
  }

  /** How a business came to be on the plan it is on. */
  public record PlanChange(
      UUID id, UUID fromPlanId, UUID toPlanId, UUID changedBy, String reason, Instant changedAt) {}
}
