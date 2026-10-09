package com.storeql.events.contract;

/**
 * Member names wave 2 adds to events that already exist. Every one is optional and additive: an
 * existing consumer that ignores it is unchanged, a producer that omits it means the old meaning.
 * The existing payloads stay hand-built in their service's {@code Events}; use these constants for
 * the new members so producer and consumer spell them once.
 */
public final class AdditiveFields {

  private AdditiveFields() {}

  /**
   * {@code StockAdjusted.origin} (stock-counts; shared with expired-stock, transfer-discrepancies).
   */
  public static final String STOCK_ADJUSTED_ORIGIN = "origin";

  /**
   * Values of {@link #STOCK_ADJUSTED_ORIGIN}; absent reads as {@link #ORIGIN_MANUAL}. purchase-svc
   * posts a write-off only for MANUAL.
   */
  public static final String ORIGIN_MANUAL = "MANUAL";

  public static final String ORIGIN_COUNT = "COUNT";
  public static final String ORIGIN_DISPOSAL = "DISPOSAL";
  public static final String ORIGIN_YIELD = "YIELD";
  public static final String ORIGIN_TRANSFER = "TRANSFER";
  public static final String ORIGIN_RTV_OVERRIDE = "RTV_OVERRIDE";

  /**
   * {@code StockAdjusted.sourceReturnId} and {@code MaterialStatusChanged.sourceReturnId}
   * (shopper-returns).
   */
  public static final String SOURCE_RETURN_ID = "sourceReturnId";

  /**
   * {@code OrderPlaced.slotFee} / {@code slotFeeVat}: NUMERIC money in the home currency; absent =
   * no fee (workforce-rules slice 10).
   */
  public static final String ORDER_SLOT_FEE = "slotFee";

  public static final String ORDER_SLOT_FEE_VAT = "slotFeeVat";

  /**
   * {@code OrderPlaced}/{@code OrderConfirmed} line member: the line's discount as money
   * (line-discounts-and-price-overrides). Absent = 0.
   */
  public static final String LINE_DISCOUNT_AMOUNT = "discountAmount";

  /**
   * {@code GoodsReceived} line: quantity received damaged (goods-receipt-detail); good = received
   * minus damaged. Absent = 0.
   */
  public static final String GOODS_RECEIVED_QTY_DAMAGED = "qtyDamaged";

  /**
   * {@code GoodsReceived} line: per-lot detail (goods-receipt-detail slice 2). Per-lot lines are
   * sent as separate lines of the same variant so per-line dedupe holds; members of a lot: {@link
   * #LOT_NO}, {@link #LOT_EXPIRY_DATE}, {@link #LOT_QTY}, {@link #LOT_CONDITION} (GOOD|DAMAGED).
   */
  public static final String GOODS_RECEIVED_LOTS = "lots";

  public static final String LOT_NO = "lotNo";
  public static final String LOT_EXPIRY_DATE = "expiryDate";
  public static final String LOT_QTY = "qty";
  public static final String LOT_CONDITION = "condition";

  /**
   * {@code ReturnedToVendor} line: SEALED|OPENED|DAMAGED|FAULTY (goods-receipt-detail slice 4).
   * Absent = sellable stock.
   */
  public static final String RTV_CONDITION = "condition";

  /**
   * {@code ReturnedToVendor} line: true when a manager's documented override allowed a return above
   * the shelf. Absent = false.
   */
  public static final String RTV_ON_HAND_OVERRIDE = "onHandOverride";

  /**
   * {@code OrderReturned.requestId}: the shopper's return request it honours (shopper-returns).
   * Absent = staff-initiated.
   */
  public static final String ORDER_RETURNED_REQUEST_ID = "requestId";

  /**
   * {@code StaffAssigned}/{@code StaffRemoved.source}: MANUAL or DIRECTORY; absent = MANUAL
   * (directory-sync).
   */
  public static final String STAFF_SOURCE = "source";

  public static final String SOURCE_MANUAL = "MANUAL";
  public static final String SOURCE_DIRECTORY = "DIRECTORY";

  /**
   * {@code StaffAssigned}/{@code StaffRemoved.businessWide} (wave 1): true = all stores, {@code
   * storeId} omitted.
   */
  public static final String STAFF_BUSINESS_WIDE = "businessWide";

  /**
   * {@code OrderReturned} (a return or an exchange), {@code OrderVoided}, {@code OrderCancelled}
   * and {@code GiftCardRedeemed}: the till session (the drawer) the till said the money moved in, a
   * UUIDv7. Optional: absent = the sale was rung at no till that named one. payment-svc keeps it
   * only when it is the business's own OPEN session at the very store the money was taken at, and
   * otherwise counts the money at no drawer; an event is never refused over it.
   */
  public static final String TILL_SESSION_ID = "tillSessionId";
}
