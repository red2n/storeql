package com.storeql.events.contract;

import com.storeql.events.EventPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The value a sale put on a gift card was taken back off it because that sale was voided or
 * cancelled (intent/till-sessions-and-registers.md, slice 8). One event per card line, announced on
 * the void or cancel's own transaction. Producer: order-svc; consumer: purchase-svc, which posts
 * the opposite of the sale-loaded posting once per {@code eventId} (Dr 2310 / Cr 1105, by order).
 * {@code aggregateId} is the gift card's id; {@code storeId} (optional) is the card's store, so the
 * journal lands where the sale-loaded one did.
 */
public final class GiftCardLoadReversed {

  public static final String TYPE = "GiftCardLoadReversed";
  public static final String TOPIC = "storeql.order.gift-card-load-reversed";

  public static final String GIFT_CARD_ID = "giftCardId";
  public static final String ORDER_ID = "orderId";
  public static final String AMOUNT = "amount";
  public static final String CURRENCY = "currency";
  public static final String SOURCE = "source";
  public static final String REVERSED_AT = "reversedAt";
  public static final String STORE_ID = "storeId";

  /** The only source today: value that a sale loaded. */
  public static final String SOURCE_SALE = "SALE";

  public static final EventContract CONTRACT =
      new EventContract(TYPE, TOPIC, TenantScope.REQUIRED, GiftCardLoadReversed::read);

  private GiftCardLoadReversed() {}

  public record Read(
      Envelope envelope,
      UUID giftCardId,
      UUID orderId,
      BigDecimal amount,
      String currency,
      String source,
      Instant reversedAt,
      Optional<UUID> storeId) {}

  public static String payload(
      UUID tenantId,
      UUID giftCardId,
      UUID orderId,
      BigDecimal amount,
      String currency,
      String source,
      Instant reversedAt,
      UUID storeId) {
    return new JsonFields(EventPayload.base(TYPE, tenantId, giftCardId))
        .uuid(GIFT_CARD_ID, giftCardId)
        .uuid(ORDER_ID, orderId)
        .num(AMOUNT, amount)
        .str(CURRENCY, currency)
        .str(SOURCE, source)
        .instant(REVERSED_AT, reversedAt)
        .optUuid(STORE_ID, storeId)
        .close();
  }

  public static Read read(String json) {
    EventReader r = EventReader.open(json, TYPE);
    return new Read(
        r.envelope(TenantScope.REQUIRED),
        r.uuid(GIFT_CARD_ID),
        r.uuid(ORDER_ID),
        r.decimal(AMOUNT),
        r.string(CURRENCY),
        r.string(SOURCE),
        r.instant(REVERSED_AT),
        r.optUuid(STORE_ID));
  }
}
