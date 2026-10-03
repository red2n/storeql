package com.storeql.events.contract;

import com.storeql.events.EventPayload;
import java.util.Optional;
import java.util.UUID;

/**
 * A variant's date kind, storage class or lot tracking changed (or a backfill re-announced it): the
 * single definition shared by expired-and-short-dated-stock, receiving-controls and
 * goods-receipt-detail. Producer: product-svc, one event per variant of the product; consumer:
 * inventory-svc's {@code variant_handling} projection. {@code aggregateId} is the variant's id.
 */
public final class VariantHandlingSet {

  public static final String TYPE = "VariantHandlingSet";
  public static final String TOPIC = "storeql.product.variant-handling-set";

  public static final String PRODUCT_ID = "productId";
  public static final String VARIANT_ID = "variantId";
  public static final String DATE_KIND = "dateKind";
  public static final String STORAGE_CLASS = "storageClass";
  public static final String TRACKING = "tracking";

  public static final String USE_BY = "USE_BY";
  public static final String BEST_BEFORE = "BEST_BEFORE";
  public static final String TRACKING_NONE = "NONE";
  public static final String TRACKING_LOT = "LOT";
  public static final String TRACKING_LOT_EXPIRY = "LOT_EXPIRY";

  public static final EventContract CONTRACT =
      new EventContract(TYPE, TOPIC, TenantScope.REQUIRED, VariantHandlingSet::read);

  private VariantHandlingSet() {}

  /**
   * @param dateKind empty = USE_BY (apply {@link #USE_BY} when reading)
   * @param storageClass empty = no rule
   */
  public record Read(
      Envelope envelope,
      UUID productId,
      UUID variantId,
      Optional<String> dateKind,
      Optional<String> storageClass,
      String tracking) {

    /** The date kind with the page's default applied. */
    public String effectiveDateKind() {
      return dateKind.orElse(USE_BY);
    }
  }

  /** {@code dateKind} and {@code storageClass} null = unset; {@code tracking} defaults to NONE. */
  public static String payload(
      UUID tenantId,
      UUID productId,
      UUID variantId,
      String dateKind,
      String storageClass,
      String tracking) {
    return new JsonFields(EventPayload.base(TYPE, tenantId, variantId))
        .uuid(PRODUCT_ID, productId)
        .uuid(VARIANT_ID, variantId)
        .optStr(DATE_KIND, dateKind)
        .optStr(STORAGE_CLASS, storageClass)
        .str(TRACKING, tracking == null ? TRACKING_NONE : tracking)
        .close();
  }

  public static Read read(String json) {
    EventReader r = EventReader.open(json, TYPE);
    return new Read(
        r.envelope(TenantScope.REQUIRED),
        r.uuid(PRODUCT_ID),
        r.uuid(VARIANT_ID),
        r.optString(DATE_KIND),
        r.optString(STORAGE_CLASS),
        r.optString(TRACKING).orElse(TRACKING_NONE));
  }
}
