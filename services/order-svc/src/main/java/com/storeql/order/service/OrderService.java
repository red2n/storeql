package com.storeql.order.service;

import com.storeql.ids.Ids;
import com.storeql.order.domain.Domain;
import com.storeql.order.domain.Domain.AgeVerification;
import com.storeql.order.domain.Domain.AgeVerificationSummary;
import com.storeql.order.domain.Domain.ExceptionGrouping;
import com.storeql.order.domain.Domain.ExceptionRow;
import com.storeql.order.domain.Domain.GiftCard;
import com.storeql.order.domain.Domain.GiftCardTransaction;
import com.storeql.order.domain.Domain.Layaway;
import com.storeql.order.domain.Domain.LayawayDeposit;
import com.storeql.order.domain.Domain.LayawayItem;
import com.storeql.order.domain.Domain.Order;
import com.storeql.order.domain.Domain.OrderDeposit;
import com.storeql.order.domain.Domain.OrderDiscount;
import com.storeql.order.domain.Domain.OrderItem;
import com.storeql.order.domain.Domain.OrderReceipt;
import com.storeql.order.domain.Domain.OrderStatusHistory;
import com.storeql.order.domain.Domain.PosLogEntry;
import com.storeql.order.domain.Domain.PosVoidLog;
import com.storeql.order.domain.Domain.Return;
import com.storeql.order.domain.Domain.ReturnItem;
import com.storeql.order.domain.Domain.SalesByHourRow;
import com.storeql.order.domain.Domain.SalesByStaffRow;
import com.storeql.order.domain.Domain.SpecialOrder;
import com.storeql.order.domain.Domain.SpecialOrderItem;
import com.storeql.order.domain.Handover;
import com.storeql.order.domain.OrderSplit;
import com.storeql.order.domain.Routing;
import com.storeql.order.domain.SubstitutePrice;
import com.storeql.order.dto.Dtos.AddDepositRequest;
import com.storeql.order.dto.Dtos.CreateLayawayRequest;
import com.storeql.order.dto.Dtos.CreateReturnRequest;
import com.storeql.order.dto.Dtos.CreateSpecialOrderRequest;
import com.storeql.order.dto.Dtos.GenerateReceiptRequest;
import com.storeql.order.dto.Dtos.IssueGiftCardRequest;
import com.storeql.order.dto.Dtos.PlaceOrderRequest;
import com.storeql.order.dto.Dtos.RecordAgeCheckRequest;
import com.storeql.order.dto.Dtos.RedeemGiftCardRequest;
import com.storeql.order.dto.Dtos.ReloadGiftCardRequest;
import com.storeql.order.dto.Dtos.VoidRequest;
import com.storeql.order.repo.OrderRepository;
import com.storeql.order.repo.RecallNoticeRepository;
import com.storeql.service.StoreStatusRepository;
import com.storeql.service.TenantStatusRepository;
import com.storeql.web.ApiException;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Business logic for order-svc. Thin resource → this service → repository. */
@ApplicationScoped
public class OrderService {

  private static final System.Logger LOG = System.getLogger(OrderService.class.getName());

  @Inject OrderRepository repo;
  @Inject com.storeql.order.repo.FiscalReceiptRepository receiptRepo;
  @Inject com.storeql.order.repo.SalesAnalyticsRepository salesAnalyticsRepo;
  @Inject TenantStatusRepository tenantStatusRepo;
  @Inject com.storeql.service.TenantProfiles profiles;
  @Inject StoreStatusRepository storeStatusRepo;
  @Inject com.storeql.order.config.ServiceConfig config;
  @Inject com.storeql.order.client.PricingClient pricing;
  @Inject com.storeql.order.client.CustomerLinkClient customerLink;
  @Inject com.storeql.order.client.InventoryClient inventory;
  @Inject com.storeql.order.client.NotificationClient notifications;
  @Inject com.storeql.order.client.TenantClient tenants;
  @Inject FiscalService fiscal;
  @Inject SalesInvoiceService salesInvoices;
  @Inject com.storeql.service.Jurisdictions jurisdictions;
  @Inject com.storeql.order.client.ProductClient products;
  @Inject com.storeql.order.client.StockClient stock;
  @Inject com.storeql.order.repo.DepositRepository depositRepo;
  @Inject OrderRouter router;
  @Inject FulfilmentWindowService windows;

  // ── Orders ────────────────────────────────────────────────────────────────

  /** OrderFulfilled for the whole order, each line carrying its net revenue (19.7). */
  private static com.storeql.service.OutboxRow fulfilledWithRevenue(
      UUID tenantId, Order order, List<OrderItem> lines) {
    return Events.orderFulfilled(
        tenantId,
        order.id(),
        order.storeId(),
        lines,
        com.storeql.order.domain.LineRevenue.unitNet(order, lines),
        java.util.Currency.getInstance(order.currency()).getDefaultFractionDigits());
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /**
   * Resolves the currency to stamp on a money-bearing row (SJ-D2).
   *
   * <p>Previously three call sites each picked their own literal — {@code "USD"} for orders and
   * gift cards, {@code "GBP"} for special orders — while pricing-svc resolved every line in the
   * price list's own currency. A GBP tenant could therefore end up with GBP-priced lines on a
   * USD-stamped order, and a USD-stamped POSLog entry underneath it. The tenant's currency has been
   * captured at onboarding since tenant-svc V1 and published on {@code TenantCreated}; nothing read
   * it.
   *
   * <p>Precedence: the tenant's projected currency wins. A request that names a different one is
   * rejected rather than silently overridden — a client asking to be billed in a currency the
   * tenant does not trade in is a bug on the caller's side, and silently correcting it would hide a
   * mispriced basket. When the projection has no row yet (a tenant onboarded before this projection
   * existed, or event-delivery lag) the tenant's currency is read from tenant-svc instead, and the
   * same rule applies. There is no platform default any more (SJ-D53): a configured "GBP" stamped
   * pounds onto a yen tenant's order whenever the projection lagged; when neither source can
   * answer, the order is refused with 503 rather than guessed.
   *
   * @param tenantId the tenant the row belongs to
   * @param requested the client-supplied currency, or {@code null} when the request omitted it
   * @return the ISO-4217 code to persist, upper-cased
   * @throws ApiException 400 {@code ORDER_CURRENCY_MISMATCH} if {@code requested} contradicts the
   *     tenant's own currency; 503 {@code TENANT_PROFILE_UNAVAILABLE} when it cannot be read
   */
  private String resolveCurrency(UUID tenantId, String requested) {
    String asked = isBlank(requested) ? null : requested.trim().toUpperCase(Locale.ROOT);
    String tenantCurrency =
        tenantStatusRepo.findCurrency(tenantId).orElseGet(() -> profiles.requireCurrency(tenantId));
    if (asked != null && !asked.equals(tenantCurrency)) {
      throw ApiException.badRequest(
          "ORDER_CURRENCY_MISMATCH",
          "currency " + asked + " does not match the tenant's currency " + tenantCurrency);
    }
    return tenantCurrency;
  }

  /**
   * Authorises a manual discount and builds its audit row (SJ-D6).
   *
   * <p>Three checks, in the order that gives the caller the most useful failure. A non-staff caller
   * is refused outright -- an online or guest checkout self-applying a discount would let the buyer
   * name their own price. A staff caller must give a reason, because a discount with no stated
   * reason is unauditable and the discount is the most common internal-theft vector at a till.
   * Finally the amount must sit within the caller's own authority: every staff role could
   * previously have taken 100% off, with only the subtotal as a ceiling.
   *
   * @return the audit row to commit alongside the order; never null (callers skip a zero discount)
   * @throws ApiException 403 {@code ORDER_DISCOUNT_NOT_ALLOWED} for a non-staff caller or a staff
   *     role with no configured ceiling; 400 {@code ORDER_DISCOUNT_REASON_REQUIRED} when no reason
   *     is given; 403 {@code ORDER_DISCOUNT_EXCEEDS_AUTHORITY} when it is above the ceiling
   */
  private OrderDiscount authorizeDiscount(
      TenantContext ctx,
      UUID orderId,
      UUID storeId,
      BigDecimal subtotal,
      BigDecimal disc,
      PlaceOrderRequest req) {
    var ceilings = config.discountCeilings();
    String bestRole = null;
    BigDecimal bestCeiling = null;
    for (String role : ctx.roles()) {
      BigDecimal ceiling = ceilings.get(role.toUpperCase(Locale.ROOT));
      if (ceiling != null && (bestCeiling == null || ceiling.compareTo(bestCeiling) > 0)) {
        bestCeiling = ceiling;
        bestRole = role.toUpperCase(Locale.ROOT);
      }
    }
    if (bestRole == null)
      throw ApiException.forbidden(
          "ORDER_DISCOUNT_NOT_ALLOWED", "discounts can only be applied by authorised staff");

    if (isBlank(req.discountReason()))
      throw ApiException.badRequest(
          "ORDER_DISCOUNT_REASON_REQUIRED", "discountReason is required when applying a discount");

    // Percentage of subtotal, not of total: tax follows the discounted price, so measuring against
    // the post-tax figure would let the same cash discount pass or fail depending on the VAT rate.
    BigDecimal pct = disc.multiply(HUNDRED).divide(subtotal, 3, java.math.RoundingMode.HALF_UP);
    if (pct.compareTo(bestCeiling) > 0)
      throw ApiException.forbidden(
          "ORDER_DISCOUNT_EXCEEDS_AUTHORITY",
          "discount of "
              + pct
              + "% exceeds the "
              + bestCeiling
              + "% limit for role "
              + bestRole
              + " — a more senior member of staff must authorise it");

    return new OrderDiscount(
        Ids.newId(),
        ctx.requireTenantId(),
        orderId,
        storeId,
        subtotal,
        disc,
        pct,
        req.discountReason().trim(),
        ctx.userId(),
        bestRole,
        Instant.now());
  }

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /**
   * Places an order — the entry point for both online checkout and POS.
   *
   * <p>The same method serves both channels, so inventory, payments and reporting behave
   * identically across them; only {@code channel} and {@code fulfilmentType} differ. A till sale
   * that is paid at the counter is confirmed immediately, while an online order stays PENDING until
   * payment is captured.
   *
   * @param req the store, channel, fulfilment type, lines and customer details
   * @param ctx caller context; supplies the tenant and the acting identity
   * @param idempotencyKey the caller's {@code Idempotency-Key}, so a retried checkout returns the
   *     original order rather than placing a second one
   * @return the placed order
   * @throws ApiException {@code ORDER_NO_ITEMS} (400) when the order has no lines; a conflict when
   *     the tenant or store is not trading
   */
  /**
   * Who a sale is credited to: what the till named, else the person operating the till.
   *
   * <p>Named explicitly because the two are not the same question. The POS journal already records
   * who rang a sale up; this records who <em>sold</em> it, which on a counter is somebody else, and
   * which is who a shop paying commission pays. Online is nobody: a website does the selling, and
   * crediting whoever confirmed the order would pay somebody for it.
   *
   * @throws ApiException 400 when a seller is named on an online order, or is not an id
   */
  private static UUID sellerOf(PlaceOrderRequest req, TenantContext ctx) {
    boolean till = "POS".equalsIgnoreCase(req.channel());
    String named = req.sellerUserId();
    if (named != null && !named.isBlank()) {
      if (!till) {
        throw ApiException.badRequest(
            "ORDER_SELLER_POS_ONLY",
            "an online sale is credited to nobody; a seller belongs to a sale somebody made");
      }
      try {
        return Ids.parse(named.strip());
      } catch (IllegalArgumentException e) {
        throw new ApiException(
            400, "ORDER_SELLER_INVALID", "sellerUserId is not an id: " + named, List.of(), e);
      }
    }
    return till ? ctx.userId() : null;
  }

  public Order placeOrder(PlaceOrderRequest req, TenantContext ctx, String idempotencyKey) {
    if (req.items() == null || req.items().isEmpty())
      throw ApiException.badRequest("ORDER_NO_ITEMS", "order must have at least one item");

    UUID tenantId = ctx.requireTenantId();
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");

    if (!tenantStatusRepo.isActive(tenantId))
      throw ApiException.conflict(
          "TENANT_NOT_OPERATIONAL",
          "Tenant is suspended or blocked — orders cannot be placed at this time");
    // A signed-in storefront customer is bound to their own order from the authenticated identity —
    // never from the (untrusted) request body. Staff placing a POS order may still attach a
    // customer explicitly via the body.
    //
    // SJ-D44: the two ids are not the same id. A login is global; the shop's customer record is
    // per-tenant. Stamping the login into customer_id made every customer-keyed path — loyalty,
    // the confirmation email, erasure — miss every online order. The login is recorded as a login,
    // and the shop's record of that person is resolved from customer-svc, which creates one the
    // first time. If that call fails the order still stands with its login id: a sale is never
    // lost over a link, and the next order makes it.
    //
    // SJ-D59: only an ONLINE order is the caller's own. Every staff login carries CUSTOMER too, so
    // linking on the role alone filed a cashier's anonymous till sales under the cashier's own
    // record, loyalty and all. A till sale names its customer only when the cashier says who.
    UUID loginId = null;
    UUID customerId;
    if (Order.CHANNEL_ONLINE.equals(req.channel())
        && ctx.hasRole("CUSTOMER")
        && ctx.userId() != null) {
      loginId = ctx.userId();
      customerId = customerLink.customerIdFor(tenantId, loginId, ctx.email()).orElse(null);
    } else {
      customerId = req.customerId() != null ? Parsing.uuid(req.customerId(), "customerId") : null;
    }
    String currency = resolveCurrency(tenantId, req.currency());
    String fulfilment =
        req.fulfilmentType() != null ? req.fulfilmentType() : Order.FULFILMENT_INSTORE;
    boolean delivery = Order.FULFILMENT_DELIVERY.equals(fulfilment);
    if (delivery) {
      if (isBlank(req.deliveryLine1())
          || isBlank(req.deliveryCity())
          || isBlank(req.deliveryPostalCode())
          || isBlank(req.deliveryRecipientName())
          || isBlank(req.deliveryRecipientPhone()))
        throw ApiException.badRequest(
            "ORDER_DELIVERY_ADDRESS_REQUIRED",
            "deliveryLine1, deliveryCity, deliveryPostalCode, deliveryRecipientName and"
                + " deliveryRecipientPhone are required when fulfilmentType is DELIVERY");
      // Server-side fulfilling-store resolve (pincode → store). When delivery areas are mapped,
      // this overrides the client storeId so stock is reserved at the correct warehouse.
      // When none are configured, tenant-svc falls back to default/first store; if tenant-svc
      // is down we keep the client storeId (fail-open for routing only).
      var resolved = tenants.resolveFulfilment(tenantId, req.deliveryPostalCode().trim());
      if (resolved.isPresent()) {
        storeId = resolved.get().storeId();
      }
    }
    // Delivery and collection slots: the window belongs to the store that fills the order — for a
    // delivery, the store the postcode just resolved to — captured before routing can reassign
    // storeId to a different shop below, so a split (or reassigned single-store) delivery's window
    // still comes from the area store's own offering, as the intent decided.
    UUID areaStore = storeId;
    ctx.requireStoreAccess(storeId);
    if (!storeStatusRepo.isActive(tenantId, storeId))
      throw ApiException.conflict(
          "STORE_NOT_OPERATIONAL",
          "Store is closed or suspended — orders cannot be placed at this location");
    // A dark store has no shop floor (ship-from-store and dark-store picking): it fills online
    // orders for delivery, nobody is there to hand a collection over, and no till rings there.
    if (isDarkStore(tenantId, storeId)) {
      if ("POS".equalsIgnoreCase(req.channel())) {
        throw ApiException.conflict(
            "ORDER_NO_TILL_AT_DARK_STORE", "a dark store has no till; it fills online orders only");
      }
      if (!delivery) {
        throw ApiException.conflict(
            "ORDER_PICKUP_NOT_OFFERED",
            "a dark store offers no collection; choose delivery, or a shop to collect from");
      }
    }
    // Delivery and collection slots: ONLINE DELIVERY/PICKUP only; a slot sent on anything else
    // (POS, or an online in-store sale) names a thing that does not apply to it.
    boolean pickup = Order.FULFILMENT_PICKUP.equals(fulfilment);
    boolean slotEligible = Order.CHANNEL_ONLINE.equals(req.channel()) && (delivery || pickup);
    boolean slotNamed = !isBlank(req.slotWindowId()) || !isBlank(req.slotStartsAt());
    if (!slotEligible && slotNamed) {
      throw ApiException.badRequest(
          "ORDER_SLOT_NOT_APPLICABLE",
          "a fulfilment slot only applies to an online delivery or pickup order");
    }
    FulfilmentWindowService.ResolvedSlot slot =
        slotEligible
            ? windows.resolveForCheckout(
                tenantId, areaStore, fulfilment, req.slotWindowId(), req.slotStartsAt())
            : null;
    String paymentMethod = null;
    if (req.paymentMethod() != null && !req.paymentMethod().isBlank()) {
      paymentMethod = req.paymentMethod().trim().toUpperCase(java.util.Locale.ROOT);
      if (!java.util.Set.of("CASH", "CARD", "UPI", "WALLET").contains(paymentMethod))
        throw ApiException.badRequest(
            "ORDER_PAYMENT_METHOD_INVALID",
            "paymentMethod must be one of CASH, CARD, UPI, WALLET — got: " + req.paymentMethod());
    }
    // A phone at the till (intent/phone-at-the-till.md): the store says whether its till asks for
    // the customer's number. A Required store refuses a till sale with neither a number nor a
    // customer. A number given is read in the store's own country, then the business's; at the
    // till one that is no phone anywhere the business trades is refused, while the customer is
    // still there to correct it. Online it is read the same way and never refused over.
    boolean tillSale = "POS".equalsIgnoreCase(req.channel());
    if (tillSale
        && TillPhone.missing(tillPhoneAsk(tenantId, storeId), customerId, req.contactPhone())) {
      throw ApiException.conflict(
          "ORDER_CONTACT_PHONE_REQUIRED",
          "this store asks for a phone number on every till sale; give the customer's, or name the"
              + " customer");
    }
    String contactPhoneE164 = contactPhoneE164(tenantId, storeId, req.contactPhone(), tillSale);

    boolean enforcePricing = config.pricingEnforce();

    BigDecimal subtotal = BigDecimal.ZERO;
    BigDecimal serverTax = BigDecimal.ZERO;
    List<OrderItem> items = new ArrayList<>();
    UUID orderId = Ids.newId();

    List<UUID> variantIds =
        req.items().stream().map(ir -> Parsing.uuid(ir.variantId(), "variantId")).toList();
    // Gap #63: when enforcement is on, the price comes from pricing-svc — the client-supplied
    // unitPrice is ignored. When off (local dev / unseeded rigs), the client price is trusted.
    // One batched call resolves every line instead of one cross-service HTTP call per line.
    List<com.storeql.order.client.PricingClient.QuotedLine> resolvedLines = null;
    com.storeql.order.client.PricingClient.QuotedBasket quoted = null;
    if (enforcePricing) {
      var lineRequests =
          new ArrayList<com.storeql.order.client.PricingClient.LineRequest>(variantIds.size());
      for (int i = 0; i < variantIds.size(); i++) {
        lineRequests.add(
            new com.storeql.order.client.PricingClient.LineRequest(
                variantIds.get(i),
                req.items().get(i).qty(),
                Parsing.optionalUuid(req.items().get(i).markdownId(), "markdownId")));
      }
      // The whole basket in one call, so the promotion engine can see rules that need the order
      // total — a spend threshold, a basket percentage, a buy-one-get-one. resolveLines priced
      // each line independently and gave those nothing to be about.
      try {
        quoted =
            pricing.quoteBasket(
                tenantId, lineRequests, storeId, req.channel(), customerId, req.couponCodes());
      } catch (org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException e) {
        // Thrown by the breaker's interceptor outside the client method, so the client's own
        // catch never sees it; without this the checkout answered 500 for an open breaker.
        throw new ApiException(
            503,
            "ORDER_PRICING_UNAVAILABLE",
            "pricing-svc circuit open — too many recent failures",
            List.of(),
            e);
      }
      resolvedLines = quoted.lines();
    }

    for (int i = 0; i < req.items().size(); i++) {
      var ir = req.items().get(i);
      UUID variantId = variantIds.get(i);
      BigDecimal unitPrice;
      BigDecimal quotedLineNet = null;
      BigDecimal quotedLineVat = null;
      String quotedVatCode = null;
      BigDecimal quotedVatRate = null;
      if (enforcePricing) {
        var resolved = resolvedLines.get(i);
        unitPrice = resolved.unitPrice();
        // Kept on the line (18.5): a fiscal file lists the sale by VAT rate, and the order's one
        // tax total cannot be split back into a 19% line and a 7% line.
        quotedLineVat = resolved.lineVat();
        // A quote returns the whole line's VAT, already multiplied out. The per-unit form this
        // used to multiply belongs to /prices/resolve-batch; multiplying a line total by the
        // quantity again put £144 of VAT on an £80 basket (SJ-D20).
        serverTax = serverTax.add(resolved.lineVat());
        // And the line's value comes from the quote too, rather than from unitPrice × qty. The
        // unit price is a rounded division of that same figure, so multiplying it back does not
        // reproduce it: three units of a £100 line quote at 33.33 each and rebuild as 99.99. Taking
        // the quoted figure keeps the order's subtotal equal to the quote the customer was shown.
        quotedLineNet = resolved.lineNet();
        // And the code and rate it was taxed at (18.9): an invoice states the rate, and a rounded
        // amount on a small line cannot say what it was.
        quotedVatCode = resolved.vatCode();
        quotedVatRate = resolved.vatRate();
      } else {
        if (ir.unitPrice() == null)
          throw ApiException.badRequest(
              "ORDER_PRICE_REQUIRED", "unitPrice is required for variant " + ir.variantId());
        unitPrice = ir.unitPrice();
      }
      BigDecimal line = quotedLineNet != null ? quotedLineNet : unitPrice.multiply(ir.qty());
      subtotal = subtotal.add(line);
      UUID instrumentId =
          ir.weighingInstrumentId() == null || ir.weighingInstrumentId().isBlank()
              ? null
              : Parsing.uuid(ir.weighingInstrumentId(), "weighingInstrumentId");
      items.add(
          new OrderItem(
              Ids.newId(),
              tenantId,
              orderId,
              variantId,
              ir.qty(),
              unitPrice,
              line,
              ir.notes(),
              instrumentId,
              BigDecimal.ZERO,
              quotedLineVat,
              Parsing.optionalUuid(ir.markdownId(), "markdownId"),
              quotedVatCode,
              quotedVatRate));
    }

    // Order orchestration (intent/order-orchestration-and-split-fulfilment.md): an online delivery
    // order the delivery-area store cannot fill alone goes to the shops that can — one other shop
    // as an ordinary order there, several as a group of orders. Not for a basket with a staff
    // discount or a whole-basket offer, whose money is not shared across parts here; those are
    // placed at the area store as they always were.
    if (Order.CHANNEL_ONLINE.equals(req.channel())
        && delivery
        && config.reserveEnforce()
        && (req.discountAmount() == null || req.discountAmount().signum() == 0)
        && (quoted == null || quoted.basketDiscount().signum() == 0)) {
      var routed = router.route(ctx, tenantId, storeId, items).orElse(null);
      if (routed != null && routed.legs().size() == 1) {
        storeId = routed.legs().get(0).storeId();
      } else if (routed != null) {
        BigDecimal splitTax =
            enforcePricing
                ? serverTax.setScale(2, java.math.RoundingMode.HALF_UP)
                : req.taxAmount() != null ? req.taxAmount() : BigDecimal.ZERO;
        return placeSplit(
            new SplitCheckout(
                req,
                tenantId,
                customerId,
                loginId,
                currency,
                fulfilment,
                paymentMethod,
                items,
                splitTax,
                quoted == null ? List.of() : quoted.applied(),
                slot,
                contactPhoneE164),
            routed,
            idempotencyKey);
      }
    }

    // Hold stock for ONLINE orders before persisting, so a short line rejects the checkout with
    // 409 instead of accepting an order the store can't fulfil (industry-standard reserve →
    // consume-at-fulfilment → release-on-cancel). POS is exempt: it places and fulfils within
    // seconds, and its fulfilment deducts stock directly. Holds are idempotent per line on the
    // client Idempotency-Key, so a retried placement replays the original holds; if createOrder
    // fails below, the holds are released (best effort — the TTL sweeper is the backstop).
    List<UUID> heldReservations = List.of();
    if (Order.CHANNEL_ONLINE.equals(req.channel()) && config.reserveEnforce()) {
      var reserveLines =
          new ArrayList<com.storeql.order.client.InventoryClient.ReserveLine>(items.size());
      for (OrderItem it : items) {
        reserveLines.add(
            new com.storeql.order.client.InventoryClient.ReserveLine(it.variantId(), it.qty()));
      }
      UUID idemBase = idempotencyKey != null ? Ids.parse(idempotencyKey) : orderId;
      heldReservations =
          inventory.reserveForOrder(
              tenantId, orderId, storeId, reserveLines, config.reservationTtlSeconds(), idemBase);
    }

    BigDecimal tax;
    // A manual discount is honoured under pricing enforcement, not discarded (SJ-D6). Enforcement
    // still owns unit prices -- the resolved price above is authoritative and the client cannot
    // name its own -- but the till's discount is a separate, deliberate staff act on top of it.
    // Zeroing it here meant the till tendered subtotal - discount against an order stored at full
    // price, so paid_amount never covered the total, the order never confirmed, and the sweeper
    // cancelled a sale the customer had already paid for.
    BigDecimal disc = req.discountAmount() != null ? req.discountAmount() : BigDecimal.ZERO;
    if (enforcePricing) {
      tax = serverTax.setScale(2, java.math.RoundingMode.HALF_UP);
    } else {
      tax = req.taxAmount() != null ? req.taxAmount() : BigDecimal.ZERO;
    }
    if (disc.signum() < 0)
      throw ApiException.badRequest(
          "ORDER_DISCOUNT_NEGATIVE", "discountAmount cannot be negative — got " + disc);
    if (disc.compareTo(subtotal) > 0)
      throw ApiException.badRequest(
          "ORDER_DISCOUNT_EXCEEDS_SUBTOTAL",
          "discountAmount " + disc + " exceeds order subtotal " + subtotal);

    OrderDiscount discountAudit =
        disc.signum() == 0 ? null : authorizeDiscount(ctx, orderId, storeId, subtotal, disc, req);

    // The promotion engine's whole-basket reduction. Line-level promotions are already inside the
    // resolved unit prices and therefore inside subtotal; this is the part that belongs to no
    // line. It is deliberately NOT added to disc: that column is the staff discount, and the role
    // ceiling authorizeDiscount enforces must not be spent by an automatic offer.
    BigDecimal promoDiscount =
        quoted == null
            ? BigDecimal.ZERO
            : quoted.basketDiscount().min(subtotal.subtract(disc).max(BigDecimal.ZERO));
    // 09.16: the deposit a return scheme puts on each drink's container, its own line beside the
    // item. It is added to what the customer pays and is no part of the subtotal, the tax or any
    // discount: outside the scope of VAT where the scheme says so, taxed as the drink elsewhere.
    List<OrderDeposit> containerDeposits =
        containerDeposits(tenantId, storeId, currency, orderId, items);
    BigDecimal depositAmount =
        containerDeposits.stream()
            .map(OrderDeposit::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal total = subtotal.add(tax).subtract(disc).subtract(promoDiscount).add(depositAmount);

    boolean taxExempt = req.taxExempt() != null && req.taxExempt();
    // SJ-D41: a catalog-mode till order is placed without prices and waits for a manager; it is
    // not PENDING, so the stranded-order sweeper leaves it alone.
    // Who is credited with the sale. The till may name an assistant — on a counter, one person
    // sells
    // and another takes the money — and when it does not, a till sale is credited to whoever is
    // operating it. An online order is credited to nobody: crediting whoever happened to confirm it
    // would pay commission for a website doing the selling.
    UUID seller = sellerOf(req, ctx);
    boolean awaitingPrice = Boolean.TRUE.equals(req.awaitingPrice());
    if (awaitingPrice && !"POS".equalsIgnoreCase(req.channel())) {
      throw ApiException.badRequest(
          "ORDER_AWAITING_PRICE_POS_ONLY", "only a till order can be placed awaiting a price");
    }
    Order order =
        new Order(
            orderId,
            tenantId,
            storeId,
            customerId,
            loginId,
            req.channel(),
            fulfilment,
            awaitingPrice ? Order.STATUS_AWAITING_PRICE : Order.STATUS_PENDING,
            subtotal,
            tax,
            disc,
            total,
            currency,
            req.notes(),
            idempotencyKey,
            Instant.now(),
            Instant.now(),
            taxExempt,
            req.exemptReason(),
            delivery ? req.deliveryLine1() : null,
            delivery ? req.deliveryLine2() : null,
            delivery ? req.deliveryCity() : null,
            delivery ? req.deliveryPostalCode() : null,
            delivery ? req.deliveryRecipientName() : null,
            delivery ? req.deliveryRecipientPhone() : null,
            req.contactPhone(),
            paymentMethod,
            promoDiscount,
            seller,
            // The shopper's choice at checkout: substitutions welcome unless they said no
            // (substitutions for out-of-stock online lines).
            req.allowSubstitutions() == null || req.allowSubstitutions(),
            slot == null ? null : slot.windowId(),
            slot == null ? null : slot.startsAt(),
            slot == null ? null : slot.endsAt(),
            slot == null ? null : slot.timeZone(),
            contactPhoneE164);

    try {
      Order placed =
          repo.createOrder(
              order,
              items,
              Events.orderPlaced(
                  tenantId,
                  orderId,
                  req.channel(),
                  customerId,
                  loginId,
                  storeId,
                  null,
                  order.slotStartsAt(),
                  order.slotEndsAt(),
                  order.slotTimeZone()),
              discountAudit,
              quoted == null ? List.of() : quoted.applied(),
              containerDeposits);
      // Spending the coupon is deliberately the last thing, and deliberately outside the order's
      // transaction. A basket is quoted on every change and must not burn a redemption by being
      // looked at; only a placed order spends one. If this call fails the order still stands — a
      // customer who has paid must not lose their order because a usage counter could not be
      // written — and the redemption is idempotent on the order, so a retry costs nothing.
      if (quoted != null && !quoted.applied().isEmpty()) {
        pricing.recordRedemptionsQuietly(tenantId, orderId, customerId, quoted.applied(), currency);
      }
      // A reduced-price sticker counts down the same way (05.4): after the order, quietly, once.
      if (quoted != null && items.stream().anyMatch(i -> i.markdownId() != null)) {
        pricing.recordMarkdownRedemptionsQuietly(tenantId, orderId, items);
      }
      return placed;
    } catch (ApiException e) {
      // Idempotent replay: a retried checkout with the same key gets the original order back
      // instead of an error (golden rule #11). The stock holds are NOT released here — the
      // reservation replay above already returned the original order's holds, not new ones.
      if ("ORDER_DUPLICATE_KEY".equals(e.code()) && idempotencyKey != null) {
        return repo.findOrderByIdempotencyKey(tenantId, idempotencyKey).orElseThrow(() -> e);
      }
      inventory.releaseQuietly(tenantId, heldReservations);
      throw e;
    } catch (RuntimeException e) {
      inventory.releaseQuietly(tenantId, heldReservations);
      throw e;
    }
  }

  /**
   * Whether the store is one of the tenant's dark stores. An unreadable answer is not a refusal:
   * the store is taken for a shop and the order placed as it always was, as routing does when the
   * stores cannot be read.
   */
  private boolean isDarkStore(UUID tenantId, UUID storeId) {
    try {
      var stores = profiles.stores(tenantId, storeId);
      return stores != null && stores.isDark(storeId);
    } catch (ApiException e) {
      LOG.log(
          System.Logger.Level.WARNING,
          "store types unreadable ({0}); {1} taken for a shop",
          e.code(),
          storeId);
      return false;
    }
  }

  /**
   * What the store's till asks for the customer's phone (a phone at the till). A store whose choice
   * cannot be read asks it optionally: a sale is never refused over a store tenant-svc could not
   * answer about.
   */
  private String tillPhoneAsk(UUID tenantId, UUID storeId) {
    try {
      var stores = profiles.stores(tenantId, storeId);
      return TillPhone.ask(stores == null ? null : stores.tillPhoneOf(storeId));
    } catch (ApiException e) {
      LOG.log(
          System.Logger.Level.WARNING,
          "stores unreadable ({0}); the till at {1} taken to ask for a phone optionally",
          e.code(),
          storeId);
      return TillPhone.OPTIONAL;
    }
  }

  /**
   * A contact number in international form (a phone at the till): read in the store's own country,
   * then the business's home and its other stores'. With no country readable a national number is
   * kept as typed and never refused.
   *
   * @param tillSale whether the number was given at a till, where one that reads nowhere the
   *     business trades is refused
   * @return the international form, or {@code null} when none was given or it did not read
   * @throws ApiException 400 {@code ORDER_CONTACT_PHONE_INVALID} at a till, for a number that is no
   *     phone anywhere the business trades
   */
  private String contactPhoneE164(UUID tenantId, UUID storeId, String typed, boolean tillSale) {
    if (isBlank(typed)) return null;
    Map<UUID, String> countries = storeCountries(tenantId, storeId);
    TillPhone.Reading reading =
        TillPhone.read(typed, countries.get(storeId), homeCountry(tenantId), countries.values());
    if (tillSale && reading.unreadable()) {
      throw ApiException.badRequest(
          "ORDER_CONTACT_PHONE_INVALID",
          "the contact phone is not a phone number in any country this business trades in; check"
              + " it, or leave it out");
    }
    return reading.e164();
  }

  /** The country of each of the tenant's stores, or none when they cannot be read. */
  private Map<UUID, String> storeCountries(UUID tenantId, UUID storeId) {
    try {
      var stores = profiles.stores(tenantId, storeId);
      return stores == null ? Map.of() : stores.countries();
    } catch (ApiException e) {
      return Map.of();
    }
  }

  /** The business's own country, or {@code null} when it cannot be read. */
  private String homeCountry(UUID tenantId) {
    try {
      return profiles.requireCountry(tenantId);
    } catch (ApiException e) {
      return null;
    }
  }

  // ── Short closes and substitutions (substitutions for out-of-stock online lines) ──

  /** A stand-in the business declared for a line's product, with what the store has of it. */
  public record SubstituteSuggestion(
      UUID variantId, String productName, String sku, BigDecimal available) {}

  /** An order the store still owes something on, and the lines it owes. */
  public record OwingOrder(Order order, List<OrderItem> lines) {
    public OwingOrder {
      lines = List.copyOf(lines);
    }
  }

  /**
   * Closes a line short: the quantity (all still outstanding, when none is given) will never be
   * handed over, the order owes less, and what the shopper paid for it goes back. Any member of
   * staff at the order's store; once per Idempotency-Key.
   *
   * @throws ApiException 404 {@code ORDER_NOT_FOUND}; 403 {@code STORE_ACCESS_DENIED}; 409 {@code
   *     ORDER_LINE_NOT_ADJUSTABLE}, {@code ORDER_LINE_QTY_EXCEEDS_OUTSTANDING}; 400 {@code
   *     ORDER_LINE_UNKNOWN}
   */
  public Order shortClose(
      UUID tenantId,
      UUID orderId,
      UUID variantId,
      com.storeql.order.dto.Dtos.ShortCloseRequest req,
      TenantContext ctx,
      String idempotencyKey) {
    Order order = getOrder(tenantId, orderId);
    ctx.requireStoreAccess(order.storeId());
    if (idempotencyKey != null && repo.findAdjustmentByKey(tenantId, idempotencyKey).isPresent()) {
      return getOrder(tenantId, orderId);
    }
    BigDecimal qty =
        req == null || req.qty() == null ? outstandingOf(tenantId, orderId, variantId) : req.qty();
    String reason = req == null || isBlank(req.reason()) ? null : req.reason().trim();
    String name = variantName(tenantId, variantId);
    try {
      return repo.adjustLine(
              tenantId,
              orderId,
              variantId,
              qty,
              null,
              reason,
              ctx.userId(),
              idempotencyKey,
              a ->
                  List.of(
                      Events.orderLineShortClosed(
                          a.order(), variantId, name, qty, a.adjustment().refundAmount())))
          .order();
    } catch (ApiException e) {
      if ("ORDER_DUPLICATE_KEY".equals(e.code())) return getOrder(tenantId, orderId);
      throw e;
    }
  }

  /**
   * Puts a substitute in the bag for a line the store cannot fill, where the shopper allowed it: a
   * new line at the store's price for the substitute, capped at the original line's gross unit
   * price, its VAT within it; the original closed short for the quantity; the substitute picked at
   * once. Any member of staff at the order's store; once per Idempotency-Key.
   *
   * @throws ApiException as {@link #shortClose}, plus 409 {@code ORDER_SUBSTITUTION_NOT_ALLOWED},
   *     400 {@code ORDER_SUBSTITUTE_SAME_VARIANT}, 409 {@code ORDER_SUBSTITUTE_NOT_SELLABLE} (no
   *     price, or none on the shelf), 503 {@code ORDER_PRICING_UNAVAILABLE}
   */
  public Order substitute(
      UUID tenantId,
      UUID orderId,
      UUID variantId,
      com.storeql.order.dto.Dtos.SubstituteRequest req,
      TenantContext ctx,
      String idempotencyKey) {
    Order order = getOrder(tenantId, orderId);
    ctx.requireStoreAccess(order.storeId());
    if (idempotencyKey != null && repo.findAdjustmentByKey(tenantId, idempotencyKey).isPresent()) {
      return getOrder(tenantId, orderId);
    }
    if (!order.allowSubstitutions()) {
      throw ApiException.conflict(
          "ORDER_SUBSTITUTION_NOT_ALLOWED",
          "the shopper asked for no substitutions on this order; close the line short instead");
    }
    UUID sub = Parsing.uuid(req.substituteVariantId(), "substituteVariantId");
    if (sub.equals(variantId)) {
      throw ApiException.badRequest(
          "ORDER_SUBSTITUTE_SAME_VARIANT", "a substitute is another product than the one short");
    }
    List<OrderItem> items = repo.findOrderItems(tenantId, orderId);
    OrderItem original =
        items.stream()
            .filter(i -> i.variantId().equals(variantId) && i.remainingQty().signum() > 0)
            .findFirst()
            .or(() -> items.stream().filter(i -> i.variantId().equals(variantId)).findFirst())
            .orElseThrow(
                () ->
                    ApiException.badRequest(
                        "ORDER_LINE_UNKNOWN", "variant " + variantId + " is not on this order"));
    BigDecimal qty = req.qty() != null ? req.qty() : outstandingOf(tenantId, orderId, variantId);
    int scale = java.util.Currency.getInstance(order.currency()).getDefaultFractionDigits();
    // What a unit of the original is worth, gross, as it stands — the most a substitute costs.
    BigDecimal standing = original.standingQty();
    BigDecimal originalGrossUnit =
        standing.signum() > 0
            ? original
                .lineTotal()
                .add(original.vatAmount() == null ? BigDecimal.ZERO : original.vatAmount())
                .divide(standing, 6, RoundingMode.HALF_UP)
            : original.unitPrice();
    // On the shelf at the order's store, when the shelf can be read; a supplier-shipped product is
    // held nowhere and passes.
    stock
        .stockByStore(tenantId, List.of(sub))
        .ifPresent(
            st -> {
              BigDecimal available =
                  st.available()
                      .getOrDefault(order.storeId(), Map.of())
                      .getOrDefault(sub, BigDecimal.ZERO);
              if (available.compareTo(qty) < 0 && !st.dropship().contains(sub)) {
                throw ApiException.conflict(
                    "ORDER_SUBSTITUTE_NOT_SELLABLE",
                    "the store has "
                        + available.stripTrailingZeros().toPlainString()
                        + " of "
                        + sub
                        + ", not "
                        + qty.stripTrailingZeros().toPlainString());
              }
            });
    // Its price at the store, from pricing-svc as at checkout; the given unit price when
    // server-side
    // pricing is off.
    BigDecimal net;
    BigDecimal vat;
    BigDecimal rate = null;
    String code = null;
    if (config.pricingEnforce()) {
      try {
        var quoted =
            pricing.quoteBasket(
                tenantId,
                List.of(new com.storeql.order.client.PricingClient.LineRequest(sub, qty)),
                order.storeId(),
                order.channel(),
                order.customerId(),
                null);
        var line = quoted.lines().get(0);
        net = line.lineNet();
        vat = line.lineVat();
        rate = line.vatRate();
        code = line.vatCode();
      } catch (org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException e) {
        throw new ApiException(
            503,
            "ORDER_PRICING_UNAVAILABLE",
            "pricing-svc circuit open — too many recent failures",
            List.of(),
            e);
      } catch (ApiException e) {
        if (e.status() >= 500) throw e;
        throw new ApiException(
            409,
            "ORDER_SUBSTITUTE_NOT_SELLABLE",
            "no price for " + sub + " at this store: " + e.getMessage(),
            List.of(),
            e);
      }
    } else {
      if (req.unitPrice() == null) {
        throw ApiException.badRequest(
            "ORDER_PRICE_REQUIRED", "unitPrice is required for the substitute " + sub);
      }
      net = req.unitPrice().multiply(qty).setScale(scale, RoundingMode.HALF_UP);
      vat = original.vatAmount() == null ? null : BigDecimal.ZERO.setScale(scale);
    }
    var charge = SubstitutePrice.charge(originalGrossUnit, net, vat, rate, qty, scale);
    var priced =
        new OrderRepository.Substitute(
            sub,
            qty,
            charge.unitPrice(),
            charge.lineNet(),
            vat == null ? null : charge.lineVat(),
            code,
            rate);
    String fromName = variantName(tenantId, variantId);
    String toName = variantName(tenantId, sub);
    String reason = isBlank(req.reason()) ? null : req.reason().trim();
    try {
      return repo.adjustLine(
              tenantId,
              orderId,
              variantId,
              qty,
              priced,
              reason,
              ctx.userId(),
              idempotencyKey,
              a ->
                  List.of(
                      // The substitute is in the picker's hand: deducted and its revenue recorded
                      // as any picked line is, the original's waiting line set to what it still
                      // owes.
                      Events.orderFulfilled(
                          tenantId,
                          orderId,
                          a.order().storeId(),
                          List.of(a.substituteItem()),
                          com.storeql.order.domain.LineRevenue.unitNet(a.order(), a.items()),
                          scale,
                          a.outstanding(),
                          a.order().status(),
                          a.order().channel(),
                          a.order().fulfilmentType(),
                          a.order().customerId(),
                          a.order().loginId()),
                      Events.orderLineSubstituted(
                          a.order(),
                          variantId,
                          fromName,
                          sub,
                          toName,
                          qty,
                          a.adjustment().chargedAmount(),
                          a.adjustment().refundAmount())))
          .order();
    } catch (ApiException e) {
      if ("ORDER_DUPLICATE_KEY".equals(e.code())) return getOrder(tenantId, orderId);
      throw e;
    }
  }

  /**
   * The stand-ins the business declared for a line's product, each with what the order's store has
   * of it, most available first; nothing when none is declared.
   */
  public List<SubstituteSuggestion> substituteSuggestions(
      UUID tenantId, UUID orderId, UUID variantId, TenantContext ctx) {
    Order order = getOrder(tenantId, orderId);
    ctx.requireStoreAccess(order.storeId());
    List<UUID> ids = products.substitutes(tenantId, variantId, ctx);
    if (ids.isEmpty()) return List.of();
    Map<UUID, com.storeql.order.client.ProductClient.VariantName> names =
        products.names(tenantId, ids, ctx).orElse(Map.of());
    Map<UUID, BigDecimal> available =
        stock
            .stockByStore(tenantId, ids)
            .map(s -> s.available().getOrDefault(order.storeId(), Map.of()))
            .orElse(Map.of());
    return ids.stream()
        .map(
            v -> {
              var n = names.get(v);
              return new SubstituteSuggestion(
                  v,
                  n == null ? null : n.productName(),
                  n == null ? null : n.sku(),
                  available.getOrDefault(v, BigDecimal.ZERO));
            })
        .sorted(java.util.Comparator.comparing(SubstituteSuggestion::available).reversed())
        .toList();
  }

  /** The store's online orders still owing something, oldest first, each with the lines it owes. */
  public List<OwingOrder> owingLines(UUID tenantId, UUID storeId, TenantContext ctx) {
    ctx.requireStoreAccess(storeId);
    List<Order> orders = repo.findOwingOrders(tenantId, storeId);
    Map<UUID, List<OrderItem>> items =
        repo.findOrderItems(tenantId, orders.stream().map(Order::id).toList());
    List<OwingOrder> out = new ArrayList<>();
    for (Order o : orders) {
      List<OrderItem> owing =
          items.getOrDefault(o.id(), List.of()).stream()
              .filter(i -> i.remainingQty().signum() > 0)
              .toList();
      if (!owing.isEmpty()) out.add(new OwingOrder(o, owing));
    }
    return out;
  }

  private BigDecimal outstandingOf(UUID tenantId, UUID orderId, UUID variantId) {
    List<OrderItem> items = repo.findOrderItems(tenantId, orderId);
    if (items.stream().noneMatch(i -> i.variantId().equals(variantId))) {
      throw ApiException.badRequest(
          "ORDER_LINE_UNKNOWN", "variant " + variantId + " is not on this order");
    }
    // Possibly nothing: the repository then says why — a picked order has no line to close, a
    // confirmed one owes nothing on this line.
    return items.stream()
        .filter(i -> i.variantId().equals(variantId))
        .map(OrderItem::remainingQty)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** The product's name for a message, or null when product-svc cannot say. */
  private String variantName(UUID tenantId, UUID variantId) {
    var n = products.namesAsSystem(tenantId, List.of(variantId)).orElse(Map.of()).get(variantId);
    return n == null ? null : n.productName();
  }

  // ── Handover (ship-from-store and dark-store picking) ─────────────────────

  /**
   * Hands a picked delivery order to a carrier: recorded once, on the order's own store, by any
   * member of staff assigned there; the shopper is told it is on its way through {@code
   * OrderDispatched}.
   *
   * @throws ApiException 404 {@code ORDER_NOT_FOUND}; 403 {@code STORE_ACCESS_DENIED}; 409 {@code
   *     ORDER_HANDOVER_KIND_MISMATCH} (not an online delivery), {@code ORDER_NOT_PICKED} (not yet
   *     FULFILLED — still being picked, part-picked, or cancelled), {@code
   *     ORDER_ALREADY_HANDED_OVER}
   */
  public Handover dispatch(
      UUID tenantId,
      UUID orderId,
      com.storeql.order.dto.Dtos.DispatchRequest req,
      TenantContext ctx) {
    Order order = getOrder(tenantId, orderId);
    String carrier = req.carrier().trim();
    String reference = isBlank(req.reference()) ? null : req.reference().trim();
    Handover h =
        new Handover(
            Ids.newId(),
            tenantId,
            orderId,
            order.storeId(),
            Handover.KIND_DISPATCHED,
            carrier,
            reference,
            req.parcels(),
            null,
            ctx.userId(),
            Instant.now());
    return handOver(
        order,
        h,
        Order.FULFILMENT_DELIVERY,
        ctx,
        "dispatched: " + carrier + (reference == null ? "" : ", ref " + reference),
        Events.orderDispatched(
            tenantId,
            orderId,
            order.storeId(),
            order.customerId(),
            order.loginId(),
            carrier,
            reference,
            req.parcels()));
  }

  /**
   * Hands a picked pickup order to its shopper at the counter: recorded once, by any member of
   * staff at the store, naming who took it when staff noted it.
   *
   * @throws ApiException as {@link #dispatch}, with {@code ORDER_HANDOVER_KIND_MISMATCH} for
   *     anything but an online pickup
   */
  public Handover collect(
      UUID tenantId,
      UUID orderId,
      com.storeql.order.dto.Dtos.CollectRequest req,
      TenantContext ctx) {
    Order order = getOrder(tenantId, orderId);
    String who = req == null || isBlank(req.collectedBy()) ? null : req.collectedBy().trim();
    Handover h =
        new Handover(
            Ids.newId(),
            tenantId,
            orderId,
            order.storeId(),
            Handover.KIND_COLLECTED,
            null,
            null,
            null,
            who,
            ctx.userId(),
            Instant.now());
    return handOver(
        order,
        h,
        Order.FULFILMENT_PICKUP,
        ctx,
        who == null ? "collected" : "collected by " + who,
        Events.orderCollected(
            tenantId, orderId, order.storeId(), order.customerId(), order.loginId(), who));
  }

  private Handover handOver(
      Order order,
      Handover h,
      String forFulfilment,
      TenantContext ctx,
      String reason,
      com.storeql.service.OutboxRow event) {
    ctx.requireStoreAccess(order.storeId());
    // A till sale is handed over when it is paid; a pickup is collected and a delivery dispatched,
    // never the other way about.
    if (!Order.CHANNEL_ONLINE.equals(order.channel())
        || !forFulfilment.equals(order.fulfilmentType())) {
      throw ApiException.conflict(
          "ORDER_HANDOVER_KIND_MISMATCH",
          "order "
              + order.id()
              + " is a "
              + order.channel()
              + " "
              + order.fulfilmentType()
              + " order; "
              + (Handover.KIND_DISPATCHED.equals(h.kind())
                  ? "only an online delivery is dispatched"
                  : "only an online pickup is collected"));
    }
    // Picked and packed in full: a part-picked order is dispatched when it is complete, and a
    // cancelled one never.
    if (!Order.STATUS_FULFILLED.equals(order.status())) {
      throw ApiException.conflict(
          "ORDER_NOT_PICKED",
          "order "
              + order.id()
              + " is "
              + order.status()
              + "; only an order picked in full (FULFILLED) is handed over");
    }
    if (repo.findHandover(order.tenantId(), order.id()).isPresent()) {
      throw ApiException.conflict(
          "ORDER_ALREADY_HANDED_OVER", "order " + order.id() + " was handed over already");
    }
    return repo.recordHandover(h, reason, event);
  }

  /** The handover an order had, if any. */
  public java.util.Optional<Handover> handoverOf(UUID tenantId, UUID orderId) {
    return repo.findHandover(tenantId, orderId);
  }

  /** The handovers of these orders, by order; an order not yet handed over is not in the map. */
  public Map<UUID, Handover> handoversOf(UUID tenantId, List<Order> orders) {
    if (orders.isEmpty()) return Map.of();
    return repo.findHandovers(tenantId, orders.stream().map(Order::id).toList());
  }

  /** A priced online delivery order about to be placed as a group (order orchestration). */
  private record SplitCheckout(
      PlaceOrderRequest req,
      UUID tenantId,
      UUID customerId,
      UUID loginId,
      String currency,
      String fulfilment,
      String paymentMethod,
      List<OrderItem> items,
      BigDecimal tax,
      List<com.storeql.order.client.PricingClient.AppliedPromotion> applied,
      /**
       * The delivery or collection window the checkout holds, resolved once against the area store
       * before it was known whether the order would split (delivery and collection slots): every
       * part carries it, and it takes one place. Null when the area store offers no windows of this
       * type.
       */
      FulfilmentWindowService.ResolvedSlot slot,
      /** The contact number in international form (a phone at the till); every part carries it. */
      String contactPhoneE164) {
    SplitCheckout {
      items = List.copyOf(items);
      applied = List.copyOf(applied);
    }
  }

  /**
   * Places a delivery order as a group of orders, one per shop (order orchestration): each part its
   * lines, its share of the tax, its own holds at its own store and its own OrderPlaced naming the
   * group; the group and every part on one transaction. The holds are all or nothing: one shop
   * short releases what the others held. The checkout's key places the group once and a retry gets
   * the first part back, as a retried single order gets its order.
   *
   * @return the first part, the delivery-area store's when it takes part
   */
  private Order placeSplit(SplitCheckout co, Routing.Plan plan, String idempotencyKey) {
    UUID tenantId = co.tenantId();
    if (idempotencyKey != null) {
      var earlier = repo.findOrderByIdempotencyKey(tenantId, idempotencyKey);
      if (earlier.isPresent()) return earlier.get();
    }
    List<OrderItem> items = co.items();
    List<OrderSplit.Part> parts =
        OrderSplit.split(
            items.stream()
                .map(i -> new OrderSplit.Line(i.variantId(), i.qty(), i.lineTotal(), i.vatAmount()))
                .toList(),
            plan.byStore(),
            co.tax(),
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    List<List<com.storeql.order.client.PricingClient.AppliedPromotion>> promotions =
        promotionsByPart(co.applied(), parts);
    UUID groupId = Ids.newId();
    UUID idemBase = idempotencyKey != null ? Ids.parse(idempotencyKey) : groupId;
    PlaceOrderRequest req = co.req();
    Instant now = Instant.now();
    List<OrderRepository.NewOrder> placed = new ArrayList<>();
    List<UUID> held = new ArrayList<>();
    BigDecimal groupTotal = BigDecimal.ZERO;
    try {
      for (int k = 0; k < parts.size(); k++) {
        OrderSplit.Part part = parts.get(k);
        UUID childId = Ids.newId();
        List<OrderItem> childItems = new ArrayList<>();
        for (OrderSplit.LinePart lp : part.lines()) {
          OrderItem it = items.get(lp.lineIndex());
          childItems.add(
              new OrderItem(
                  Ids.newId(),
                  tenantId,
                  childId,
                  it.variantId(),
                  lp.qty(),
                  it.unitPrice(),
                  lp.lineTotal(),
                  it.notes(),
                  it.weighingInstrumentId(),
                  BigDecimal.ZERO,
                  lp.vat(),
                  it.markdownId(),
                  it.vatCode(),
                  it.vatRate()));
        }
        held.addAll(
            inventory.reserveForOrder(
                tenantId,
                childId,
                part.storeId(),
                childItems.stream()
                    .map(
                        i ->
                            new com.storeql.order.client.InventoryClient.ReserveLine(
                                i.variantId(), i.qty()))
                    .toList(),
                config.reservationTtlSeconds(),
                Ids.derived(idemBase, "store:" + part.storeId())));
        List<OrderDeposit> deposits =
            containerDeposits(tenantId, part.storeId(), co.currency(), childId, childItems);
        BigDecimal total =
            part.subtotal()
                .add(part.tax())
                .add(
                    deposits.stream()
                        .map(OrderDeposit::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        groupTotal = groupTotal.add(total);
        Order child =
            new Order(
                childId,
                tenantId,
                part.storeId(),
                co.customerId(),
                co.loginId(),
                req.channel(),
                co.fulfilment(),
                Order.STATUS_PENDING,
                part.subtotal(),
                part.tax(),
                BigDecimal.ZERO,
                total,
                co.currency(),
                req.notes(),
                // The first part carries the checkout's key, so a retry finds it as a retried
                // single order finds its order.
                k == 0 ? idempotencyKey : null,
                now,
                now,
                req.taxExempt() != null && req.taxExempt(),
                req.exemptReason(),
                req.deliveryLine1(),
                req.deliveryLine2(),
                req.deliveryCity(),
                req.deliveryPostalCode(),
                req.deliveryRecipientName(),
                req.deliveryRecipientPhone(),
                req.contactPhone(),
                co.paymentMethod(),
                BigDecimal.ZERO,
                null,
                req.allowSubstitutions() == null || req.allowSubstitutions(),
                co.slot() == null ? null : co.slot().windowId(),
                co.slot() == null ? null : co.slot().startsAt(),
                co.slot() == null ? null : co.slot().endsAt(),
                co.slot() == null ? null : co.slot().timeZone(),
                co.contactPhoneE164());
        placed.add(
            new OrderRepository.NewOrder(
                child,
                childItems,
                Events.orderPlaced(
                    tenantId,
                    childId,
                    req.channel(),
                    co.customerId(),
                    co.loginId(),
                    part.storeId(),
                    groupId,
                    child.slotStartsAt(),
                    child.slotEndsAt(),
                    child.slotTimeZone()),
                null,
                promotions.get(k),
                deposits));
      }
      repo.createOrderGroup(
          new com.storeql.order.domain.OrderGroup(
              groupId,
              tenantId,
              co.customerId(),
              co.loginId(),
              groupTotal,
              co.currency(),
              now,
              List.of()),
          idempotencyKey,
          placed);
    } catch (ApiException e) {
      // A retry that raced the first placement: its holds replayed the first's, so none is freed.
      if ("ORDER_DUPLICATE_KEY".equals(e.code()) && idempotencyKey != null) {
        return repo.findOrderByIdempotencyKey(tenantId, idempotencyKey).orElseThrow(() -> e);
      }
      inventory.releaseQuietly(tenantId, held);
      throw e;
    } catch (RuntimeException e) {
      inventory.releaseQuietly(tenantId, held);
      throw e;
    }
    Order first = placed.get(0).order();
    // A coupon is spent once for the checkout, against its first part; each part counts down its
    // own reduced-price stickers.
    if (!co.applied().isEmpty()) {
      pricing.recordRedemptionsQuietly(
          tenantId, first.id(), co.customerId(), co.applied(), co.currency());
    }
    for (OrderRepository.NewOrder n : placed) {
      if (n.items().stream().anyMatch(i -> i.markdownId() != null)) {
        pricing.recordMarkdownRedemptionsQuietly(tenantId, n.order().id(), n.items());
      }
    }
    return first;
  }

  /**
   * Each part's share of the line promotions: a promotion on a product goes with the parts that
   * hold it, shared by their quantities when the line was split; one on no product goes with the
   * first part.
   */
  private static List<List<com.storeql.order.client.PricingClient.AppliedPromotion>>
      promotionsByPart(
          List<com.storeql.order.client.PricingClient.AppliedPromotion> applied,
          List<OrderSplit.Part> parts) {
    List<List<com.storeql.order.client.PricingClient.AppliedPromotion>> out = new ArrayList<>();
    parts.forEach(p -> out.add(new ArrayList<>()));
    for (var a : applied) {
      List<BigDecimal> qtys =
          parts.stream()
              .map(
                  p ->
                      p.lines().stream()
                          .filter(l -> l.variantId().equals(a.variantId()))
                          .map(OrderSplit.LinePart::qty)
                          .reduce(BigDecimal.ZERO, BigDecimal::add))
              .toList();
      if (a.variantId() == null || qtys.stream().allMatch(q -> q.signum() == 0)) {
        out.get(0).add(a);
        continue;
      }
      List<BigDecimal> shares = OrderSplit.share(a.amount(), qtys);
      for (int k = 0; k < parts.size(); k++) {
        if (qtys.get(k).signum() > 0) {
          out.get(k)
              .add(
                  new com.storeql.order.client.PricingClient.AppliedPromotion(
                      a.promotionId(), a.name(), a.variantId(), shares.get(k)));
        }
      }
    }
    return out;
  }

  /**
   * The checkout an order is a part of, when a delivery was split across shops.
   *
   * @return the group, its parts in the order the shopper reads them; empty for an order never
   *     split
   */
  public java.util.Optional<com.storeql.order.domain.OrderGroup> groupOf(
      UUID tenantId, UUID orderId) {
    return repo.groupIdOf(tenantId, orderId).flatMap(g -> repo.findGroup(tenantId, g));
  }

  /** The checkout each of these orders is a part of, for those that were split. */
  public Map<UUID, UUID> groupIdsOf(UUID tenantId, List<Order> orders) {
    if (orders.isEmpty()) return Map.of();
    return repo.groupIdsOf(tenantId, orders.stream().map(Order::id).toList());
  }

  /**
   * A split checkout, for its shopper or the business's staff.
   *
   * @throws ApiException 404 {@code ORDER_GROUP_NOT_FOUND} when there is none in the tenant, or the
   *     caller may not read it — a denial is a 404 so ids cannot be probed
   */
  public com.storeql.order.domain.OrderGroup getGroup(UUID groupId, TenantContext ctx) {
    UUID tenantId = ctx.requireTenantId();
    var group =
        repo.findGroup(tenantId, groupId)
            .orElseThrow(
                () -> ApiException.notFound("ORDER_GROUP_NOT_FOUND", "order group not found"));
    boolean mine = group.loginId() != null && group.loginId().equals(ctx.userId());
    boolean staff =
        isStaff(ctx) && group.parts().stream().anyMatch(p -> ctx.hasStoreAccess(p.storeId()));
    if (!mine && !staff) {
      throw ApiException.notFound("ORDER_GROUP_NOT_FOUND", "order group not found");
    }
    return group;
  }

  /**
   * One page of orders plus the opaque cursor for the next page (null when exhausted).
   *
   * @param orders the page's rows
   * @param nextCursor cursor for the following page, or {@code null} on the last page
   */
  public record OrderPage(List<Order> orders, String nextCursor) {}

  /**
   * Cursor-paginated order search across the tenant.
   *
   * <p>Keyset paging on {@code (created_at, id)}, fetching one extra row to learn whether a further
   * page exists without a second query. Every filter is optional; passing none lists the tenant's
   * whole order history.
   *
   * @param tenantId owning tenant
   * @param storeId restrict to one store, or {@code null}
   * @param customerId restrict to one customer, or {@code null}
   * @param loginId restrict to the orders one login placed, or {@code null} — a shopper's own
   *     history filters on this, not on the customer id (SJ-D44)
   * @param channel restrict to {@code ONLINE} or {@code POS}, or {@code null}
   * @param status restrict to one order status, or {@code null}
   * @param from inclusive lower bound on creation time, or {@code null}
   * @param to exclusive upper bound on creation time, or {@code null}
   * @param afterCursor cursor from the previous page, or {@code null} to start
   * @param limit page size
   * @return the page and its next cursor
   * @throws ApiException {@code INVALID_CURSOR} (400) when the cursor is malformed
   */
  public OrderPage listOrders(
      UUID tenantId,
      UUID storeId,
      UUID customerId,
      UUID loginId,
      String channel,
      String status,
      Instant from,
      Instant to,
      String afterCursor,
      int limit) {
    return listOrders(
        tenantId,
        storeId,
        customerId,
        loginId,
        channel,
        status,
        null,
        null,
        null,
        null,
        from,
        to,
        afterCursor,
        limit);
  }

  /**
   * As above, also by how the order is fulfilled and whether it was handed over (ship-from-store
   * and dark-store picking): a store's packed parcels awaiting the courier are {@code
   * fulfilmentType=DELIVERY}, {@code status=FULFILLED}, {@code handedOver=false}.
   *
   * <p>{@code handedFrom}/{@code handedTo} bound when the order was <em>handed over</em>, not when
   * it was placed ({@code from}/{@code to} stay on creation): yesterday's delivery dispatched this
   * morning is among today's handovers. A window implies {@code handedOver = true}; asked of the
   * orders not yet handed over it is refused, since they have no handover time.
   *
   * @param fulfilmentType restrict to PICKUP, DELIVERY or INSTORE, or {@code null}
   * @param handedOver {@code false} for orders not yet handed over, {@code true} for those that
   *     were, or {@code null} for either (or for {@code true} when a window is given)
   * @param handedFrom inclusive lower bound on the handover time, or {@code null}
   * @param handedTo exclusive upper bound on the handover time, or {@code null}
   * @throws ApiException 400 {@code ORDER_HANDOVER_FILTER_INVALID} for a window with {@code
   *     handedOver = false}
   */
  public OrderPage listOrders(
      UUID tenantId,
      UUID storeId,
      UUID customerId,
      UUID loginId,
      String channel,
      String status,
      String fulfilmentType,
      Boolean handedOver,
      Instant handedFrom,
      Instant handedTo,
      Instant from,
      Instant to,
      String afterCursor,
      int limit) {
    return listOrders(
        tenantId,
        storeId,
        customerId,
        loginId,
        channel,
        status,
        fulfilmentType,
        handedOver,
        handedFrom,
        handedTo,
        from,
        to,
        null,
        afterCursor,
        limit);
  }

  /**
   * As above, also by {@code sort}: {@code null} (or anything but {@code "slot"}) keeps the usual
   * newest-first order; {@code "slot"} orders by the delivery or collection window's start instead
   * (delivery and collection slots) — soonest first, an order with no window last, then id — so the
   * Fulfilment queue can be worked in the order the vans and the counter need it.
   *
   * @param sort {@code "slot"} for slot order; anything else (including {@code null}) for the usual
   *     newest-first order
   */
  public OrderPage listOrders(
      UUID tenantId,
      UUID storeId,
      UUID customerId,
      UUID loginId,
      String channel,
      String status,
      String fulfilmentType,
      Boolean handedOver,
      Instant handedFrom,
      Instant handedTo,
      Instant from,
      Instant to,
      String sort,
      String afterCursor,
      int limit) {
    boolean handedWindow = handedFrom != null || handedTo != null;
    if (handedWindow && Boolean.FALSE.equals(handedOver)) {
      throw ApiException.badRequest(
          "ORDER_HANDOVER_FILTER_INVALID",
          "handedFrom and handedTo are for handover=DONE: an order not handed over has no"
              + " handover time");
    }
    Boolean handed = handedWindow ? Boolean.TRUE : handedOver;
    boolean bySlot = "slot".equalsIgnoreCase(sort);
    Instant afterKey = null;
    UUID afterId = null;
    String rawKey = com.storeql.web.Cursor.decode(afterCursor);
    if (rawKey != null) {
      // Raw cursor key is "<ISO instant>|<order id>" — the keyset of the last row served, on
      // created_at for the usual order or on the slot's effective instant (a store with no window
      // sorting as the far-future sentinel OrderRepository.NO_SLOT_SORT_KEY) for slot order.
      int sep = rawKey.indexOf('|');
      try {
        if (sep < 0) throw new IllegalArgumentException("missing separator");
        afterKey = Instant.parse(rawKey.substring(0, sep));
        afterId = Ids.parse(rawKey.substring(sep + 1));
      } catch (RuntimeException e) {
        throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
      }
    }
    // Fetch one extra row to learn whether a further page exists without a second query.
    List<Order> rows =
        repo.listOrders(
            tenantId,
            storeId,
            customerId,
            loginId,
            channel,
            status,
            fulfilmentType,
            handed,
            handedFrom,
            handedTo,
            from,
            to,
            afterKey,
            afterId,
            limit + 1,
            bySlot);
    if (rows.size() <= limit) {
      return new OrderPage(rows, null);
    }
    List<Order> page = rows.subList(0, limit);
    Order last = page.get(page.size() - 1);
    Instant lastKey =
        bySlot
            ? (last.slotStartsAt() != null ? last.slotStartsAt() : OrderRepository.NO_SLOT_SORT_KEY)
            : last.createdAt();
    return new OrderPage(page, com.storeql.web.Cursor.encode(lastKey + "|" + last.id()));
  }

  /** Hard cap on one export, so a data request cannot read an unbounded table into memory. */
  private static final int EXPORT_MAX_ORDERS = 2000;

  /**
   * Every order one person placed at this shop, with their lines — the sales half of a data export
   * (UK GDPR art.20), assembled by customer-svc.
   *
   * @param tenantId owning tenant
   * @param customerId the shop's record of the person, or {@code null}
   * @param loginId the login they sign in with, or {@code null}
   * @return the orders newest first, each with its lines; empty when both ids are null
   */
  public List<OrderWithItems> exportOrdersFor(UUID tenantId, UUID customerId, UUID loginId) {
    if (customerId == null && loginId == null) {
      return List.of();
    }
    return repo.listOrdersForSubject(tenantId, customerId, loginId, EXPORT_MAX_ORDERS).stream()
        .map(o -> new OrderWithItems(o, repo.findOrderItems(tenantId, o.id())))
        .toList();
  }

  /** An order and its lines, as the export needs them together. */
  public record OrderWithItems(
      Order order, List<com.storeql.order.domain.Domain.OrderItem> items) {}

  /**
   * Reads an order with tenant scoping but <strong>no</strong> object-level authorization.
   *
   * <p>For internal callers only — anything serving a request should use {@link #getOrder(UUID,
   * UUID, TenantContext)} so one customer cannot read another's order.
   *
   * @param tenantId owning tenant
   * @param orderId the order to read
   * @return the order
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists in this tenant
   */
  public Order getOrder(UUID tenantId, UUID orderId) {
    return repo.findOrder(tenantId, orderId)
        .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "order not found"));
  }

  /**
   * Order-by-id read for the API: tenant scope plus object-level authorization.
   *
   * @param tenantId owning tenant
   * @param orderId the order to read
   * @param ctx caller context; staff may read any order in the tenant, a customer only their own
   * @return the order
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists or the caller may
   *     not read it — denials are 404 so ids cannot be probed for existence
   */
  public Order getOrder(UUID tenantId, UUID orderId, TenantContext ctx) {
    Order order = getOrder(tenantId, orderId);
    requireReadAccess(order, ctx);
    return order;
  }

  /**
   * Object-level authorization for order-by-id reads (mirrors CartService.requireOwnership): an
   * order id alone is not proof of ownership. Staff may read any order in their tenant; an
   * authenticated customer may only read an order placed against their own customerId. Denials are
   * 404 (not 403) so order ids can't be probed for existence.
   *
   * <p>There is deliberately no exemption for a caller with no principal. That branch existed for
   * service-to-service lookups and assumed the gateway never forwards a tenant here without a
   * verified user — but guest checkout does exactly that, so any order id could be read by anyone
   * holding one. payment-svc's OrderClient stamps a staff role instead.
   */
  private static void requireReadAccess(Order order, TenantContext ctx) {
    if (isStaff(ctx)) return;
    // No service-to-service exemption. This used to return early for a caller with no principal
    // at all, on the reasoning that only the mesh could produce that shape — but a guest storefront
    // request carries a tenant and no principal too, so the shape was reachable from outside and
    // any id could be read by anyone who had one. The internal callers now stamp a staff role
    // (payment-svc OrderClient, notification-svc CustomerClient), so nothing needs the exemption.
    // Matched on the login the order was placed with, not the customer id: they are different ids
    // (SJ-D44), and the login is the one the token carries. An order with no login was not placed
    // by a shopper, so no shopper may read it.
    if (order.loginId() == null || !order.loginId().equals(ctx.userId()))
      throw ApiException.notFound("ORDER_NOT_FOUND", "order not found");
  }

  private static boolean isStaff(TenantContext ctx) {
    return ctx.hasRole("PLATFORM_ADMIN")
        || ctx.hasRole("OWNER")
        || ctx.hasRole("MANAGER")
        || ctx.hasRole("STOREKEEPER")
        || ctx.hasRole("CASHIER");
  }

  /**
   * SIM↔POS projection rows for POS screens (gap #50).
   *
   * <p>Read from order-svc's own projection of inventory events, not from inventory-svc, so the
   * figures are eventually consistent with the owning service.
   *
   * @param tenantId owning tenant
   * @param storeId restrict to one store, or {@code null}
   * @param variantId restrict to one variant, or {@code null}
   * @param limit maximum rows
   * @return the stock positions
   */
  public List<com.storeql.order.domain.Domain.PosStockPosition> listStockPositions(
      UUID tenantId, UUID storeId, UUID variantId, int limit) {
    return repo.findStockPositions(tenantId, storeId, variantId, limit);
  }

  /**
   * The lines on an order, with tenant scoping but <strong>no</strong> object-level authorization.
   *
   * <p>Callers serving a request must check access themselves — the resource does so by reading the
   * order through {@link #getOrder(UUID, UUID, TenantContext)} first.
   *
   * @param tenantId owning tenant
   * @param orderId the order whose lines to read
   * @return the order's lines
   */
  public List<OrderItem> getOrderItems(UUID tenantId, UUID orderId) {
    return repo.findOrderItems(tenantId, orderId);
  }

  /**
   * The append-only status history of an order.
   *
   * @param tenantId owning tenant
   * @param orderId the order whose history to read
   * @param ctx caller context, checked against the order before the history is read
   * @return the status transitions, oldest first
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists or the caller may
   *     not read it
   */
  public List<OrderStatusHistory> getOrderHistory(UUID tenantId, UUID orderId, TenantContext ctx) {
    requireReadAccess(getOrder(tenantId, orderId), ctx);
    return repo.findOrderHistory(tenantId, orderId);
  }

  /**
   * A till sale: rung up on the POS channel and handed over at the counter.
   *
   * <p>SJ-D40. inventory-svc deducts stock only on OrderFulfilled, and nothing ever fulfilled a
   * till sale: the till places the order, payment capture confirms it, and there it stopped. Stock
   * moved only if a manager later opened each sale and clicked "Mark fulfilled".
   *
   * <p>PICKUP counts as well as INSTORE because the till sent PICKUP for every tendered sale until
   * this fix, and sales already sitting in offline queues on devices will replay with it. Nothing
   * on the POS channel means "collect later" — special orders and layaways have their own resources
   * for that. DELIVERY is excluded: a till can take payment for goods that go out on a van, and
   * those are handed over when they arrive.
   */
  /** The delivery address as one line, or null when the order delivers nowhere. */
  static String deliveryAddressOf(Order order) {
    StringBuilder sb = new StringBuilder();
    for (String part :
        new String[] {
          order.deliveryLine1(),
          order.deliveryLine2(),
          order.deliveryCity(),
          order.deliveryPostalCode()
        }) {
      if (part == null || part.isBlank()) continue;
      if (sb.length() > 0) sb.append(", ");
      sb.append(part.trim());
    }
    return sb.length() == 0 ? null : sb.toString();
  }

  static boolean isTillSale(String channel, String fulfilmentType) {
    return Order.CHANNEL_POS.equals(channel)
        && (Order.FULFILMENT_INSTORE.equals(fulfilmentType)
            || Order.FULFILMENT_PICKUP.equals(fulfilmentType));
  }

  /**
   * Moves an order to CONFIRMED and publishes {@code OrderConfirmed}.
   *
   * <p>The event carries the buyer and the settled amount because customer-svc accrues loyalty from
   * it; inventory-svc treats confirmation as the point stock is committed.
   *
   * @param tenantId owning tenant
   * @param orderId the order to confirm
   * @param userId the staff member or system actor confirming it
   * @return the confirmed order
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists; a conflict when
   *     the order is not awaiting confirmation
   */
  public Order confirmOrder(UUID tenantId, UUID orderId, UUID userId) {
    // Load the order so OrderConfirmed can carry the buyer + settled amount (loyalty accrual)
    // and its lines (sales by category).
    Order order = getOrder(tenantId, orderId);
    var confirmEvent =
        Events.orderConfirmed(
            tenantId,
            orderId,
            order.storeId(),
            order.channel(),
            order.customerId(),
            order.total(),
            order.taxAmount(),
            order.currency(),
            repo.findOrderItems(tenantId, orderId),
            order.fulfilmentType(),
            deliveryAddressOf(order),
            order.deliveryRecipientName(),
            order.deliveryRecipientPhone(),
            order.slotStartsAt(),
            order.slotEndsAt(),
            order.slotTimeZone());
    Order confirmed =
        isTillSale(order.channel(), order.fulfilmentType())
            ? repo.confirmAndFulfil(
                tenantId,
                orderId,
                userId,
                confirmEvent,
                fulfilledWithRevenue(tenantId, order, repo.findOrderItems(tenantId, orderId)))
            : repo.transitionOrderStatus(
                tenantId,
                orderId,
                Order.STATUS_PENDING,
                Order.STATUS_CONFIRMED,
                "confirmed",
                userId,
                confirmEvent);

    // The number is taken when a sale completes, not when someone asks for a document — a
    // sequence that only numbers the sales somebody remembered to print is not a sequence. It is
    // outside the status transaction on purpose: a fiscal number is worth having and not worth
    // failing a paid-for sale to get, and the sequence stays gapless either way because the
    // counter only moves when a receipt row is written. POST /admin/orders/{id}/fiscal-receipt
    // issues it later if this fails.
    issueReceiptQuietly(confirmed, userId);
    // A sale to a VAT-registered business is invoiced too (18.9), in the background: the customer's
    // status is pricing-svc's to say, and a till does not wait on it.
    salesInvoices.issueInvoiceLater(confirmed, userId);
    return confirmed;
  }

  private void issueReceiptQuietly(Order order, UUID userId) {
    try {
      issueReceipt(order, Domain.FiscalReceipt.DEFAULT_SERIES, userId);
    } catch (RuntimeException e) {
      LOG.log(
          System.Logger.Level.ERROR,
          () ->
              "Order "
                  + order.id()
                  + " was confirmed but no fiscal receipt could be issued; issue it with POST"
                  + " /admin/orders/{id}/fiscal-receipt",
          e);
    }
  }

  /**
   * Issues (or returns) the numbered receipt for a sale.
   *
   * <p>Only a sale that has actually happened gets a number. A PENDING order has not been paid for
   * and may never be — numbering it would put a hole in the sequence the moment the basket is
   * abandoned, which is the exact thing the sequence must not have.
   */
  public Domain.FiscalReceipt issueReceipt(Order order, String seriesCode, UUID userId) {
    if (Order.STATUS_PENDING.equals(order.status())
        || Order.STATUS_CANCELLED.equals(order.status())) {
      throw ApiException.badRequest(
          "ORDER_NOT_SELLABLE",
          "A receipt is only issued for a completed sale; this order is " + order.status());
    }
    String series =
        seriesCode == null || seriesCode.isBlank()
            ? Domain.FiscalReceipt.DEFAULT_SERIES
            : seriesCode.trim().toUpperCase(java.util.Locale.ROOT);
    // The store's fiscal regime stamps the document as it is numbered (18.5): the fiscal year the
    // numbering restarts on is taken there, in UTC like the rest of the platform.
    return fiscal.issue(order, series, userId);
  }

  /**
   * Issues a fiscal receipt for an order looked up by id.
   *
   * @param tenantId owning tenant
   * @param orderId the completed sale to receipt
   * @param seriesCode the numbering series, or {@code null}/blank for the default
   * @param userId the staff member issuing it
   * @return the issued receipt with its allocated number
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists; {@code
   *     ORDER_NOT_SELLABLE} (409) when the sale is not completed
   */
  public Domain.FiscalReceipt issueReceipt(
      UUID tenantId, UUID orderId, String seriesCode, UUID userId) {
    return issueReceipt(getOrder(tenantId, orderId), seriesCode, userId);
  }

  /**
   * The fiscal receipt for a sale, with tenant scoping but <strong>no</strong> object-level
   * authorization.
   *
   * <p>For internal callers only — request-serving code should use the {@link TenantContext}
   * overload.
   *
   * @param tenantId owning tenant
   * @param orderId the sale whose receipt to read
   * @return the receipt
   * @throws ApiException {@code ORDER_RECEIPT_NOT_ISSUED} (404) when none has been issued
   */
  public Domain.FiscalReceipt receiptOf(UUID tenantId, UUID orderId) {
    return receiptRepo
        .findByOrder(tenantId, orderId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "ORDER_RECEIPT_NOT_ISSUED", "No fiscal receipt has been issued for this sale"));
  }

  /**
   * The receipt for a sale, to whoever may read the sale: any staff member, or the customer who
   * placed it. The till prints the number from here — the admin route is management-only.
   *
   * @param tenantId owning tenant
   * @param orderId the sale whose receipt to read
   * @param ctx caller context, checked against the order first
   * @return the receipt
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when the caller may not read the sale;
   *     {@code ORDER_RECEIPT_NOT_ISSUED} (404) when no receipt has been issued
   */
  /**
   * The receipt for a sale, waiting a bounded time for it to be issued. The number is taken when
   * the payment that completes a till sale lands, a Kafka hop after the tender, so the till used to
   * poll sixteen times over eight seconds and print without a number when order-svc was slow. One
   * request that waits here instead costs one round trip, and the wait is capped so a stuck
   * consumer never holds a till.
   *
   * @param tenantId owning tenant
   * @param orderId the sale
   * @param ctx caller identity, for the object-level check
   * @param waitSeconds how long to wait, 0..20
   * @return the receipt
   * @throws ApiException {@code ORDER_RECEIPT_NOT_ISSUED} (404) when it is still not issued
   */
  public Domain.FiscalReceipt awaitReceipt(
      UUID tenantId, UUID orderId, TenantContext ctx, int waitSeconds) {
    requireReadAccess(getOrder(tenantId, orderId), ctx);
    long deadline = System.nanoTime() + Math.min(Math.max(waitSeconds, 0), 20) * 1_000_000_000L;
    do {
      var found = receiptRepo.findByOrder(tenantId, orderId);
      if (found.isPresent()) {
        return found.get();
      }
    } while (System.nanoTime() < deadline && pauseBriefly());
    throw ApiException.notFound(
        "ORDER_RECEIPT_NOT_ISSUED", "No fiscal receipt has been issued for this sale");
  }

  /** One poll interval; false when the thread was interrupted, which ends the wait. */
  private static boolean pauseBriefly() {
    try {
      Thread.sleep(250);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * The series a store runs: code, period, where the counter has got to, and the prefix.
   *
   * @param tenantId owning tenant
   * @param storeId the store
   * @return the counters, newest period first
   */
  public List<com.storeql.order.repo.FiscalReceiptRepository.ReceiptSeries> receiptSeriesConfig(
      UUID tenantId, UUID storeId) {
    return receiptRepo.listSeriesConfig(tenantId, storeId);
  }

  /**
   * Sets the prefix a series prints in front of its numbers, opening the series if it is new.
   *
   * @param tenantId owning tenant
   * @param storeId the store
   * @param series the series code; MAIN when blank
   * @param period the fiscal period, e.g. 2026
   * @param prefix letters, digits and hyphens, at most 16; blank for none
   * @return the counter as it now stands
   * @throws ApiException {@code RECEIPT_PREFIX_INVALID} (400)
   */
  public com.storeql.order.repo.FiscalReceiptRepository.ReceiptSeries setReceiptSeriesPrefix(
      UUID tenantId, UUID storeId, String series, String period, String prefix) {
    String p = prefix == null || prefix.isBlank() ? null : prefix.trim().toUpperCase(Locale.ROOT);
    if (p != null && !p.matches("^[A-Z0-9][A-Z0-9-]{0,15}$")) {
      throw ApiException.badRequest(
          "RECEIPT_PREFIX_INVALID", "a prefix is letters, digits and hyphens, at most 16");
    }
    if (period == null || !period.trim().matches("^[0-9]{4}(-[0-9]{2})?$")) {
      throw ApiException.badRequest("RECEIPT_PERIOD_INVALID", "period is a year, e.g. 2026");
    }
    return receiptRepo.setSeriesPrefix(
        tenantId, storeId, seriesOrDefault(series), period.trim(), p);
  }

  public Domain.FiscalReceipt receiptOf(UUID tenantId, UUID orderId, TenantContext ctx) {
    requireReadAccess(getOrder(tenantId, orderId), ctx);
    return receiptOf(tenantId, orderId);
  }

  /**
   * The receipts in one numbering series, for a store and fiscal period.
   *
   * @param tenantId owning tenant
   * @param storeId the store whose series to read
   * @param series the numbering series, or {@code null}/blank for the default
   * @param period the fiscal period, normally the year
   * @param limit maximum rows; clamped to 1..500
   * @return the receipts in the series
   */
  public List<Domain.FiscalReceipt> receiptSeries(
      UUID tenantId, UUID storeId, String series, String period, int limit) {
    return receiptRepo.listSeries(
        tenantId, storeId, seriesOrDefault(series), period, Math.min(Math.max(limit, 1), 500));
  }

  /**
   * The gap audit: bounds, count, and every hole. An empty gap list is the proof.
   *
   * <p>What a tax inspector asks for: a fiscal series must be unbroken, so the absence of gaps is
   * the evidence. {@code expected} is the span rather than the count, so a series holding 400
   * receipts numbered 1..500 reads as 100 missing without anyone subtracting.
   *
   * @param tenantId owning tenant
   * @param storeId the store whose series to audit
   * @param series the numbering series, or {@code null}/blank for the default
   * @param period the fiscal period, normally the year
   * @return first and last number, issued and expected counts, an {@code intact} flag, and every
   *     gap as a from/to pair
   */
  public java.util.Map<String, Object> receiptAudit(
      UUID tenantId, UUID storeId, String series, String period) {
    String s = seriesOrDefault(series);
    long[] bounds = receiptRepo.seriesBounds(tenantId, storeId, s, period);
    var gaps = receiptRepo.findGaps(tenantId, storeId, s, period);
    var out = new java.util.LinkedHashMap<String, Object>();
    out.put("storeId", storeId.toString());
    out.put("seriesCode", s);
    out.put("period", period);
    out.put("firstNumber", bounds[0]);
    out.put("lastNumber", bounds[1]);
    out.put("issued", bounds[2]);
    // Expected is the span, so a series with 400 receipts numbered 1..500 reads as 100 missing
    // without anyone having to subtract.
    out.put("expected", bounds[1] == 0 ? 0 : bounds[1] - bounds[0] + 1);
    out.put("intact", gaps.isEmpty());
    out.put(
        "gaps", gaps.stream().map(g -> java.util.Map.of("from", g.from(), "to", g.to())).toList());
    // 18.4: a second, independent verdict — not whether a number is missing, but whether any
    // document's stored figures still match the hash written when it was issued.
    var chain = receiptRepo.verifyChain(tenantId, storeId, s, period);
    out.put("chainIntact", chain.intact());
    out.put("chainFrom", chain.from());
    out.put("chainBrokenAt", chain.brokenAt());
    return out;
  }

  /**
   * The register as a file (18.4): every document in a series with its hashes, as CSV rows or as
   * JSON with the order lines behind each document. Management-only at the resource.
   *
   * @param format {@code csv} or {@code json}
   * @return the CSV text, or the JSON-shaped map
   */
  public Object exportRegister(
      UUID tenantId, UUID storeId, String series, String period, String format) {
    String s = seriesOrDefault(series);
    var docs = receiptRepo.listSeries(tenantId, storeId, s, period, 1_000_000);
    if ("json".equalsIgnoreCase(format)) {
      var lines = new java.util.HashMap<Long, List<Map<String, Object>>>();
      for (var l : receiptRepo.linesInSeries(tenantId, storeId, s, period)) {
        var line = new LinkedHashMap<String, Object>();
        line.put("variantId", l.variantId().toString());
        line.put("qty", l.qty());
        line.put("unitPrice", l.unitPrice());
        line.put("lineTotal", l.lineTotal());
        lines.computeIfAbsent(l.number(), k -> new java.util.ArrayList<>()).add(line);
      }
      var out = new LinkedHashMap<String, Object>();
      out.put("storeId", storeId.toString());
      out.put("seriesCode", s);
      out.put("period", period);
      out.put("generatedAt", java.time.Instant.now().toString());
      out.put(
          "documents",
          docs.stream()
              .map(
                  d -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("number", d.number());
                    m.put("fullNumber", d.fullNumber());
                    m.put("issuedAt", d.issuedAt().toString());
                    m.put("orderId", d.orderId().toString());
                    m.put("currency", d.currency());
                    m.put("grossTotal", d.grossTotal());
                    m.put("taxTotal", d.taxTotal());
                    m.put("voidedAt", d.voidedAt() == null ? null : d.voidedAt().toString());
                    m.put("voidReason", d.voidReason());
                    m.put("prevHash", d.prevHash());
                    m.put("hash", d.hash());
                    m.put("lines", lines.getOrDefault(d.number(), List.of()));
                    return m;
                  })
              .toList());
      return out;
    }
    StringBuilder csv =
        new StringBuilder(
            "number,fullNumber,issuedAt,orderId,currency,grossTotal,taxTotal,voidedAt,voidReason,"
                + "prevHash,hash\n");
    for (var d : docs) {
      csv.append(d.number())
          .append(',')
          .append(csvCell(d.fullNumber()))
          .append(',')
          .append(d.issuedAt())
          .append(',')
          .append(d.orderId())
          .append(',')
          .append(d.currency())
          .append(',')
          .append(d.grossTotal().toPlainString())
          .append(',')
          .append(d.taxTotal().toPlainString())
          .append(',')
          .append(d.voidedAt() == null ? "" : d.voidedAt().toString())
          .append(',')
          .append(csvCell(d.voidReason()))
          .append(',')
          .append(csvCell(d.prevHash()))
          .append(',')
          .append(csvCell(d.hash()))
          .append('\n');
    }
    return csv.toString();
  }

  private static String csvCell(String v) {
    if (v == null) {
      return "";
    }
    return v.contains(",") || v.contains("\"") || v.contains("\n")
        ? "\"" + v.replace("\"", "\"\"") + "\""
        : v;
  }

  private static String seriesOrDefault(String series) {
    return series == null || series.isBlank()
        ? Domain.FiscalReceipt.DEFAULT_SERIES
        : series.trim().toUpperCase(java.util.Locale.ROOT);
  }

  /**
   * Cancels a PENDING or CONFIRMED order, publishing {@code OrderCancelled}.
   *
   * <p>Both states must be cancellable so their stock holds are released — inventory-svc reacts to
   * the event. A fulfilled order is returned rather than cancelled.
   *
   * @param tenantId owning tenant
   * @param orderId the order to cancel
   * @param reason free-text reason recorded on the status transition
   * @param userId the staff member cancelling it
   * @return the cancelled order
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists; {@code
   *     ORDER_CANNOT_CANCEL} (409) when it is not PENDING or CONFIRMED
   */
  public Order cancelOrder(UUID tenantId, UUID orderId, String reason, UUID userId) {
    // PENDING covers pay-later online orders awaiting confirmation; both states must be
    // cancellable so their stock holds get released (inventory-svc reacts to OrderCancelled).
    Order order = getOrder(tenantId, orderId);
    if (Order.STATUS_PARTIALLY_FULFILLED.equals(order.status()))
      throw ApiException.conflict(
          "ORDER_PARTLY_FULFILLED",
          "some of the goods were handed over; take them back as a return or hand over the rest");
    if (!Order.STATUS_PENDING.equals(order.status())
        && !Order.STATUS_AWAITING_PRICE.equals(order.status())
        && !Order.STATUS_CONFIRMED.equals(order.status()))
      throw ApiException.conflict(
          "ORDER_CANNOT_CANCEL",
          "only PENDING, AWAITING_PRICE or CONFIRMED orders can be cancelled");
    return repo.transitionOrderStatus(
        tenantId,
        orderId,
        order.status(),
        Order.STATUS_CANCELLED,
        reason,
        userId,
        Events.orderCancelled(tenantId, orderId, reason, order.channel(), order.fulfilmentType()));
  }

  /**
   * Marks a CONFIRMED order fulfilled, publishing {@code OrderFulfilled} with its lines.
   *
   * <p>The event carries the lines because inventory-svc deducts against them; the transition
   * itself is guarded on CONFIRMED, so fulfilling twice fails rather than deducting twice.
   *
   * @param tenantId owning tenant
   * @param orderId the order to fulfil
   * @param userId the staff member fulfilling it
   * @return the fulfilled order
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists; a conflict when
   *     the order is not CONFIRMED
   */
  public Order fulfillOrder(UUID tenantId, UUID orderId, UUID userId) {
    return fulfilOrder(tenantId, orderId, null, userId, null);
  }

  /**
   * Prices a catalog-mode till order (SJ-D41): a manager gives every line its unit price, the
   * totals are recomputed, and the order becomes PENDING — payable at the till or in Admin → Orders
   * → Collect payment, and swept as stranded only if it then sits unpaid.
   *
   * @throws ApiException {@code ORDER_NOT_FOUND} (404); {@code ORDER_NOT_AWAITING_PRICE} (409);
   *     {@code ORDER_PRICE_LINE_MISSING} / {@code ORDER_PRICE_LINE_UNKNOWN} (400)
   */
  public Order priceOrder(
      UUID tenantId,
      UUID orderId,
      com.storeql.order.dto.Dtos.PriceOrderRequest req,
      UUID userId,
      TenantContext ctx) {
    Order order = getOrder(tenantId, orderId);
    ctx.requireStoreAccess(order.storeId());
    Map<UUID, BigDecimal> prices = new LinkedHashMap<>();
    for (var line : req.lines()) {
      if (line.unitPrice() == null || line.unitPrice().signum() < 0) {
        throw ApiException.badRequest("ORDER_PRICE_INVALID", "a unit price cannot be negative");
      }
      prices.put(Parsing.uuid(line.variantId(), "variantId"), line.unitPrice());
    }
    if (prices.isEmpty()) {
      throw ApiException.badRequest("ORDER_PRICE_LINE_MISSING", "no prices given");
    }
    BigDecimal tax = req.taxAmount() == null ? BigDecimal.ZERO : req.taxAmount();
    return repo.priceOrder(tenantId, orderId, prices, tax, userId);
  }

  /**
   * Hands over some or all of an order (SJ-D35). With lines, only those quantities leave the store
   * now and the order is PARTIALLY_FULFILLED until every line is complete; without, everything
   * still outstanding is handed over, which for an untouched order is the old all-or-nothing
   * fulfilment. Each call emits one OrderFulfilled carrying only this call's quantities.
   *
   * @param tenantId owning tenant
   * @param orderId the order
   * @param req the lines and quantities handed over now; null or empty for everything outstanding
   * @param userId the staff member
   * @param ctx caller context, checked for access to the order's store; null for internal callers
   * @return the order as it now stands
   * @throws ApiException {@code ORDER_NOT_FOUND} (404); {@code ORDER_NOT_FULFILLABLE} (409) unless
   *     CONFIRMED or PARTIALLY_FULFILLED; {@code ORDER_FULFIL_LINE_UNKNOWN} (400); {@code
   *     ORDER_FULFIL_QTY_EXCEEDS_OUTSTANDING} (409); {@code ORDER_NOTHING_OUTSTANDING} (409)
   */
  public Order fulfilOrder(
      UUID tenantId,
      UUID orderId,
      com.storeql.order.dto.Dtos.FulfilRequest req,
      UUID userId,
      TenantContext ctx) {
    return fulfil(tenantId, orderId, req, userId, ctx, null, null).orElseThrow();
  }

  /**
   * {@link #fulfilOrder} once per {@code dedupeId}, for an event that hands an order over (a wave
   * picked at the store): the dedupe mark and the handover are one transaction, so a redelivered
   * event hands over nothing twice and a failure after the mark loses nothing.
   *
   * @return true when this call handed the lines over; false when the dedupe id was already applied
   */
  public boolean fulfilOrderOnce(
      UUID dedupeId,
      String consumer,
      UUID tenantId,
      UUID orderId,
      com.storeql.order.dto.Dtos.FulfilRequest req) {
    return fulfil(tenantId, orderId, req, null, null, dedupeId, consumer).isPresent();
  }

  private java.util.Optional<Order> fulfil(
      UUID tenantId,
      UUID orderId,
      com.storeql.order.dto.Dtos.FulfilRequest req,
      UUID userId,
      TenantContext ctx,
      UUID dedupeId,
      String dedupeConsumer) {
    Order order = getOrder(tenantId, orderId);
    if (ctx != null) {
      ctx.requireStoreAccess(order.storeId());
    }
    Map<UUID, BigDecimal> wanted = new LinkedHashMap<>();
    if (req != null && req.lines() != null) {
      for (var line : req.lines()) {
        if (line.qty() == null || line.qty().signum() <= 0) {
          throw ApiException.badRequest(
              "ORDER_FULFIL_QTY_INVALID", "a handed-over quantity must be greater than zero");
        }
        wanted.merge(Parsing.uuid(line.variantId(), "variantId"), line.qty(), BigDecimal::add);
      }
    }
    // The lines' prices are read before the handover, so each part handed over carries its own
    // share of the order's net revenue (19.7).
    var unitNet =
        com.storeql.order.domain.LineRevenue.unitNet(order, repo.findOrderItems(tenantId, orderId));
    int scale = java.util.Currency.getInstance(order.currency()).getDefaultFractionDigits();
    return repo.fulfilLines(
        tenantId,
        orderId,
        wanted,
        userId,
        dedupeId,
        dedupeConsumer,
        f -> {
          Map<UUID, BigDecimal> outstanding = new LinkedHashMap<>();
          for (var l : f.lines()) outstanding.put(l.variantId(), l.outstandingQty());
          return Events.orderFulfilled(
              tenantId,
              orderId,
              order.storeId(),
              f.lines().stream()
                  .map(
                      l ->
                          new OrderItem(
                              null,
                              tenantId,
                              orderId,
                              l.variantId(),
                              l.qty(),
                              BigDecimal.ZERO,
                              BigDecimal.ZERO,
                              null,
                              null))
                  .toList(),
              unitNet,
              scale,
              outstanding,
              f.complete() ? Order.STATUS_FULFILLED : Order.STATUS_PARTIALLY_FULFILLED,
              order.channel(),
              order.fulfilmentType(),
              order.customerId(),
              order.loginId());
        });
  }

  // ── Returns ───────────────────────────────────────────────────────────────

  /**
   * Records a return against a fulfilled order.
   *
   * <p>Only a fulfilled or partly refunded order can be returned: goods can come back only once
   * they were handed over. Returning a PENDING or CONFIRMED order would record a refund for goods,
   * and often money, that were never exchanged — cancel it instead.
   *
   * @param tenantId owning tenant
   * @param orderId the order being returned against
   * @param req the lines and quantities coming back, and the reason
   * @param ctx caller context, checked for access to the order's store
   * @return the recorded return
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists; {@code
   *     ORDER_CANNOT_RETURN} (409) when it is not FULFILLED or PARTIALLY_REFUNDED
   */
  public Return createReturn(
      UUID tenantId, UUID orderId, CreateReturnRequest req, TenantContext ctx) {
    Order order =
        repo.findOrder(tenantId, orderId)
            .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "order not found"));
    ctx.requireStoreAccess(order.storeId());

    // Goods can only come back once they were handed over. An order still PENDING or CONFIRMED
    // never left the store — cancel it instead; returning it recorded a refund for goods, and
    // often money, that were never exchanged. A fully REFUNDED order has nothing left to refund.
    if (!Order.STATUS_FULFILLED.equals(order.status())
        && !Order.STATUS_PARTIALLY_FULFILLED.equals(order.status())
        && !Order.STATUS_PARTIALLY_REFUNDED.equals(order.status()))
      throw ApiException.conflict(
          "ORDER_CANNOT_RETURN",
          "only a fulfilled order can be returned; this one is " + order.status());

    List<OrderItem> orderItems = repo.findOrderItems(tenantId, orderId);
    UUID returnId = Ids.newId();
    BigDecimal totalRefund = BigDecimal.ZERO;
    List<ReturnItem> returnItems = new ArrayList<>();
    String method = req.refundMethod() != null ? req.refundMethod() : Return.METHOD_ORIGINAL;

    for (var ri : req.items()) {
      UUID variantId = Parsing.uuid(ri.variantId(), "variantId");
      OrderItem matched =
          orderItems.stream()
              .filter(oi -> oi.variantId().equals(variantId))
              .findFirst()
              .orElseThrow(
                  () ->
                      ApiException.notFound(
                          "ITEM_NOT_IN_ORDER", "variant " + ri.variantId() + " not in order"));
      BigDecimal refundAmt = matched.unitPrice().multiply(ri.qty());
      totalRefund = totalRefund.add(refundAmt);
      returnItems.add(
          new ReturnItem(
              Ids.newId(), tenantId, returnId, variantId, ri.qty(), refundAmt, ri.condition()));
    }

    Return ret =
        new Return(
            returnId,
            tenantId,
            orderId,
            order.storeId(),
            req.reason(),
            totalRefund,
            method,
            Return.STATUS_COMPLETED,
            Instant.now(),
            Instant.now(),
            // The audit trail (20.11) names who took the goods back; a return never used to.
            ctx.userId());

    // A refund that settles a recall notice (05.10) settles it in this transaction: the goods, the
    // money and the notice agree, or none of them is recorded.
    UUID noticeId =
        req.recallNoticeId() == null || req.recallNoticeId().isBlank()
            ? null
            : Parsing.uuid(req.recallNoticeId(), "recallNoticeId");
    Return created =
        repo.createReturn(
            ret,
            returnItems,
            Events.orderReturned(
                tenantId,
                orderId,
                returnId,
                order.storeId(),
                returnItems,
                totalRefund,
                method,
                order.currency()),
            noticeId == null
                ? null
                : c ->
                    RecallNoticeRepository.resolveByReturnTx(
                        c, tenantId, noticeId, orderId, returnId, ctx.userId()));
    // A return against an invoiced sale is credited (18.9), in the background.
    salesInvoices.issueCreditNoteLater(tenantId, orderId, created.id(), ctx.userId());
    return created;
  }

  /**
   * The returns recorded against one order.
   *
   * @param tenantId owning tenant
   * @param orderId the order whose returns to read
   * @param ctx caller context, checked against the order first
   * @return the returns, empty when nothing has come back
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists or the caller may
   *     not read it
   */
  public List<Return> getReturns(UUID tenantId, UUID orderId, TenantContext ctx) {
    requireReadAccess(getOrder(tenantId, orderId), ctx);
    return repo.findReturns(tenantId, orderId);
  }

  /**
   * The lines on one return, with tenant scoping but <strong>no</strong> object-level
   * authorization.
   *
   * <p>Callers serving a request must check access to the owning order themselves.
   *
   * @param tenantId owning tenant
   * @param returnId the return whose lines to read
   * @return the returned lines, with their per-line refund amounts
   */
  public List<ReturnItem> getReturnItems(UUID tenantId, UUID returnId) {
    return repo.findReturnItems(tenantId, returnId);
  }

  // ── Post-void ─────────────────────────────────────────────────────────────

  /**
   * Voids a POS sale, restocking its lines and marking its receipt.
   *
   * <p>The receipt keeps its number and gains a reason rather than being removed: closing the hole
   * in the sequence is the trick a till fraud relies on — ring the sale, take the cash, void the
   * receipt, and a balancing till hides the theft. Here the document stays, numbered.
   *
   * <p>POS only: an online order is cancelled or returned instead.
   *
   * @param tenantId owning tenant
   * @param orderId the sale to void
   * @param req the reason, recorded on both the void log and the receipt
   * @param ctx caller context, checked for access to the sale's store
   * @return the recorded void log entry
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists; {@code
   *     ORDER_VOID_ONLY_POS} (409) when the order is not a POS sale
   */
  public PosVoidLog voidOrder(UUID tenantId, UUID orderId, VoidRequest req, TenantContext ctx) {
    Order order =
        repo.findOrder(tenantId, orderId)
            .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "order not found"));
    ctx.requireStoreAccess(order.storeId());
    if (!Order.CHANNEL_POS.equals(order.channel()))
      throw ApiException.conflict("ORDER_VOID_ONLY_POS", "void is only allowed on POS orders");
    PosVoidLog log =
        repo.voidOrder(
            tenantId,
            orderId,
            order.storeId(),
            req.reason(),
            ctx.userId(),
            restock -> Events.orderVoided(tenantId, orderId, order.storeId(), restock));

    // The receipt keeps its number and gains a reason. Removing it would close the hole in the
    // sequence, and closing the hole is the whole trick: ring the sale, take the cash, void the
    // receipt, and a till that balances hides a theft. Here the document stays, numbered.
    receiptRepo.markVoided(tenantId, orderId, req.reason());
    return log;
  }

  // ── Layaway ───────────────────────────────────────────────────────────────

  /**
   * Opens a layaway: goods set aside against a deposit, collected once paid off.
   *
   * @param req the store, customer, items and initial deposit
   * @param ctx caller context; supplies the tenant and is checked for store access
   * @return the opened layaway
   * @throws ApiException {@code LAYAWAY_NO_ITEMS} (400) when no items are supplied
   */
  public Layaway createLayaway(CreateLayawayRequest req, TenantContext ctx) {
    if (req.items() == null || req.items().isEmpty())
      throw ApiException.badRequest("LAYAWAY_NO_ITEMS", "layaway must have at least one item");

    // requireTenantId (not the nullable tenantId()) so a request that somehow reached this
    // financial write path without a tenant fails 401 instead of persisting a null-tenant row.
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    UUID customerId =
        req.customerId() != null ? Parsing.uuid(req.customerId(), "customerId") : null;
    UUID layawayId = Ids.newId();

    BigDecimal total = BigDecimal.ZERO;
    List<LayawayItem> items = new ArrayList<>();
    for (var li : req.items()) {
      BigDecimal line = li.unitPrice().multiply(li.qty());
      total = total.add(line);
      items.add(
          new LayawayItem(
              Ids.newId(),
              tenantId,
              layawayId,
              Parsing.uuid(li.variantId(), "variantId"),
              li.qty(),
              li.unitPrice(),
              line));
    }

    BigDecimal balance = total.subtract(req.initialDeposit());
    if (balance.compareTo(BigDecimal.ZERO) < 0)
      throw ApiException.conflict(
          "DEPOSIT_EXCEEDS_TOTAL", "initial deposit cannot exceed total amount");

    Instant dueDate = req.dueDate() != null ? Parsing.instant(req.dueDate(), "dueDate") : null;
    Layaway layaway =
        new Layaway(
            layawayId,
            tenantId,
            storeId,
            customerId,
            total,
            req.initialDeposit(),
            balance,
            Layaway.STATUS_ACTIVE,
            req.notes(),
            Instant.now(),
            dueDate,
            null,
            null);

    LayawayDeposit deposit =
        new LayawayDeposit(
            Ids.newId(),
            tenantId,
            layawayId,
            req.initialDeposit(),
            req.paymentMethod(),
            null,
            Instant.now());

    return repo.createLayaway(layaway, items, deposit, Events.layawayCreated(tenantId, layawayId));
  }

  /**
   * Reads one layaway.
   *
   * @param tenantId owning tenant
   * @param layawayId the layaway to read
   * @return the layaway with its total and outstanding balance
   * @throws ApiException {@code LAYAWAY_NOT_FOUND} (404) when no such layaway exists in this tenant
   */
  public Layaway getLayaway(UUID tenantId, UUID layawayId) {
    return repo.findLayaway(tenantId, layawayId)
        .orElseThrow(() -> ApiException.notFound("LAYAWAY_NOT_FOUND", "layaway not found"));
  }

  /**
   * The goods set aside on one layaway.
   *
   * @param tenantId owning tenant
   * @param layawayId the layaway whose items to read
   * @return the reserved lines with their prices
   */
  public List<LayawayItem> getLayawayItems(UUID tenantId, UUID layawayId) {
    return repo.findLayawayItems(tenantId, layawayId);
  }

  /**
   * The payments made against one layaway.
   *
   * @param tenantId owning tenant
   * @param layawayId the layaway whose deposits to read
   * @return the deposits, which together with the total give the balance still owed
   */
  public List<LayawayDeposit> getLayawayDeposits(UUID tenantId, UUID layawayId) {
    return repo.findLayawayDeposits(tenantId, layawayId);
  }

  /**
   * Takes a further payment against a layaway, reducing its balance.
   *
   * @param tenantId owning tenant
   * @param layawayId the layaway being paid down
   * @param req the amount, payment method and reference
   * @param ctx caller context
   * @return the layaway with its new balance
   * @throws ApiException {@code LAYAWAY_NOT_FOUND} (404) when no such layaway exists
   */
  public Layaway addDeposit(
      UUID tenantId, UUID layawayId, AddDepositRequest req, TenantContext ctx) {
    LayawayDeposit deposit =
        new LayawayDeposit(
            Ids.newId(),
            tenantId,
            layawayId,
            req.amount(),
            req.paymentMethod(),
            req.reference(),
            Instant.now());
    return repo.addDeposit(tenantId, layawayId, deposit);
  }

  /**
   * Closes a fully paid layaway and hands the goods over, publishing {@code LayawayCompleted}.
   *
   * @param tenantId owning tenant
   * @param layawayId the layaway to complete
   * @param ctx caller context
   * @return the completed layaway
   * @throws ApiException {@code LAYAWAY_NOT_FOUND} (404) when no such layaway exists; a conflict
   *     when a balance is still owed
   */
  public Layaway completeLayaway(UUID tenantId, UUID layawayId, TenantContext ctx) {
    return repo.completeLayaway(tenantId, layawayId, Events.layawayCompleted(tenantId, layawayId));
  }

  /**
   * Cancels a layaway, releasing the goods held against it and publishing {@code LayawayCancelled}.
   *
   * <p>Refunding deposits already taken is a separate decision, handled through payment-svc.
   *
   * @param tenantId owning tenant
   * @param layawayId the layaway to cancel
   * @param reason free-text reason recorded against it
   * @param ctx caller context
   * @return the cancelled layaway
   * @throws ApiException {@code LAYAWAY_NOT_FOUND} (404) when no such layaway exists
   */
  public Layaway cancelLayaway(UUID tenantId, UUID layawayId, String reason, TenantContext ctx) {
    return repo.cancelLayaway(
        tenantId, layawayId, reason, Events.layawayCancelled(tenantId, layawayId));
  }

  // ── Gift cards ────────────────────────────────────────────────────────────

  /**
   * Issues a gift card with a server-generated code, recording the opening transaction.
   *
   * <p>The code is minted here, never supplied by the caller: it is bearer stored value, so a
   * guessable or client-chosen code would be spendable by whoever guessed it.
   *
   * @param req the store, amount, optional currency and optional expiry
   * @param ctx caller context; supplies the tenant and is checked for store access
   * @return the issued card, including its code
   */
  public GiftCard issueGiftCard(IssueGiftCardRequest req, TenantContext ctx) {
    // requireTenantId (not the nullable tenantId()) so issuing a gift card without a tenant in
    // context fails 401 rather than minting stored value against a null-tenant row.
    UUID tenantId = ctx.requireTenantId();
    String paidBy = giftCardPaidBy(req.paidBy());
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    UUID gcId = Ids.newId();
    String code = generateGiftCardCode();
    String currency = resolveCurrency(tenantId, req.currency());
    Instant expiresAt =
        req.expiresAt() != null ? Parsing.instant(req.expiresAt(), "expiresAt") : null;

    GiftCard gc =
        new GiftCard(
            gcId,
            tenantId,
            storeId,
            code,
            req.amount(),
            req.amount(),
            GiftCard.STATUS_ACTIVE,
            currency,
            Instant.now(),
            expiresAt);

    GiftCardTransaction tx =
        new GiftCardTransaction(
            Ids.newId(),
            tenantId,
            gcId,
            GiftCardTransaction.TX_ISSUE,
            req.amount(),
            BigDecimal.ZERO,
            req.amount(),
            null,
            null,
            Instant.now());

    return repo.issueGiftCard(gc, tx, Events.giftCardLoaded(gc, tx, paidBy));
  }

  /**
   * Looks a gift card up by its code, to check the balance at the till.
   *
   * @param tenantId owning tenant
   * @param code the card's code
   * @return the card with its current balance
   * @throws ApiException {@code GIFT_CARD_NOT_FOUND} (404) when no such card exists in this tenant
   */
  public GiftCard getGiftCard(UUID tenantId, String code) {
    return repo.findGiftCardByCode(tenantId, code)
        .orElseThrow(() -> ApiException.notFound("GIFT_CARD_NOT_FOUND", "gift card not found"));
  }

  /**
   * Adds value to an existing gift card.
   *
   * @param tenantId owning tenant
   * @param code the card's code
   * @param req the amount to add and a reference for the transaction log
   * @return the card with its new balance
   * @throws ApiException {@code GIFT_CARD_NOT_FOUND} (404) when no such card exists; a conflict
   *     when the card is not active
   */
  public GiftCard reloadGiftCard(UUID tenantId, String code, ReloadGiftCardRequest req) {
    String paidBy = giftCardPaidBy(req.paidBy());
    return repo.reloadGiftCard(
        tenantId,
        code,
        req.amount(),
        req.reference(),
        (card, tx) -> Events.giftCardLoaded(card, tx, paidBy));
  }

  /** What gift card value may be paid for with, and PROMOTIONAL for value given away (17.11). */
  static final java.util.Set<String> GIFT_CARD_PAID_BY =
      java.util.Set.of("CASH", "CARD", "UPI", "WALLET", "PROMOTIONAL");

  /**
   * How gift card value was paid for, normalised. Stored value bought with another gift card, a
   * voucher or store credit would only move a liability from one account to another, so those are
   * refused with everything else that is not a tender.
   *
   * @throws ApiException {@code GIFT_CARD_PAID_BY_INVALID} (400)
   */
  static String giftCardPaidBy(String paidBy) {
    String p = paidBy == null ? "" : paidBy.trim().toUpperCase(java.util.Locale.ROOT);
    if (!GIFT_CARD_PAID_BY.contains(p)) {
      throw ApiException.badRequest(
          "GIFT_CARD_PAID_BY_INVALID", "paidBy must be CASH, CARD, UPI, WALLET or PROMOTIONAL");
    }
    return p;
  }

  /**
   * Spends against a gift card, optionally attributing it to an order.
   *
   * <p>The balance check happens in the repository, inside the transaction that writes the
   * transaction row, so two tills cannot together overspend one card.
   *
   * @param tenantId owning tenant
   * @param code the card's code
   * @param req the amount, the order being paid towards, and a reference
   * @return the card with its new balance
   * @throws ApiException {@code GIFT_CARD_NOT_FOUND} (404) when no such card exists; a conflict
   *     when the balance is insufficient or the card is not active
   */
  public GiftCard redeemGiftCard(UUID tenantId, String code, RedeemGiftCardRequest req) {
    UUID orderId = req.orderId() != null ? Parsing.uuid(req.orderId(), "orderId") : null;
    return repo.redeemGiftCard(tenantId, code, req.amount(), orderId, req.reference());
  }

  /**
   * The append-only transaction history of one gift card.
   *
   * @param tenantId owning tenant
   * @param code the card's code
   * @return every issue, reload and redemption against the card
   * @throws ApiException {@code GIFT_CARD_NOT_FOUND} (404) when no such card exists in this tenant
   */
  public List<GiftCardTransaction> getGiftCardTransactions(UUID tenantId, String code) {
    GiftCard gc =
        repo.findGiftCardByCode(tenantId, code)
            .orElseThrow(() -> ApiException.notFound("GIFT_CARD_NOT_FOUND", "gift card not found"));
    return repo.findGiftCardTransactions(tenantId, gc.id());
  }

  // ── Payment event handlers (called by PaymentEventHandler) ───────────────

  /**
   * Accumulates a captured tender toward the order's total and confirms the order once tenders
   * cover it. Split tenders (e.g. POS cash+card, each individually below the order total) each call
   * this once and accumulate, instead of the order only confirming on a single full-amount tender.
   */
  /** A capture whose event carried no tender method (older producers). */
  public void handlePaymentCaptured(
      java.util.UUID tenantId,
      java.util.UUID orderId,
      java.util.UUID paymentId,
      java.math.BigDecimal amount) {
    handlePaymentCaptured(tenantId, orderId, paymentId, amount, null);
  }

  public void handlePaymentCaptured(
      java.util.UUID tenantId,
      java.util.UUID orderId,
      java.util.UUID paymentId,
      java.math.BigDecimal amount,
      String method) {
    if (amount == null || paymentId == null) {
      LOG.log(
          java.lang.System.Logger.Level.WARNING,
          "PaymentCaptured for order {0} ignored: missing paymentId or amount",
          orderId);
      return;
    }
    // Load the order so OrderConfirmed can carry the buyer + settled amount (loyalty accrual). The
    // event is only written when this capture fully covers the total (applyPaymentCaptured), so a
    // partial split-tender builds the row but never emits it.
    Order order = repo.findOrder(tenantId, orderId).orElse(null);
    if (order == null) {
      LOG.log(
          java.lang.System.Logger.Level.WARNING,
          "PaymentCaptured for order {0} ignored: order not found",
          orderId);
      return;
    }
    // The lines once, for the fulfilment event and for OrderConfirmed (sales by category).
    List<OrderItem> items = repo.findOrderItems(tenantId, orderId);
    // A till sale is handed over the moment it is paid for, so the capture that completes it also
    // fulfils it, in the same transaction (SJ-D40). The event is built for every tender but only
    // written by the one that completes the sale; a partial tender or a redelivery writes nothing.
    var fulfilEvent =
        isTillSale(order.channel(), order.fulfilmentType())
            ? fulfilledWithRevenue(tenantId, order, items)
            : null;
    boolean completed =
        repo.applyPaymentCaptured(
            tenantId,
            orderId,
            paymentId,
            amount,
            method,
            Events.orderConfirmed(
                tenantId,
                orderId,
                order.storeId(),
                order.channel(),
                order.customerId(),
                order.total(),
                order.taxAmount(),
                order.currency(),
                items,
                order.fulfilmentType(),
                deliveryAddressOf(order),
                order.deliveryRecipientName(),
                order.deliveryRecipientPhone(),
                order.slotStartsAt(),
                order.slotEndsAt(),
                order.slotTimeZone()),
            fulfilEvent);

    // Till sales are confirmed here, not in confirmOrder, so this is where most receipts are
    // numbered. The first version of the sequence hooked only confirmOrder — which the till never
    // calls — and so numbered the sales a manager confirmed by hand and almost none of the ones
    // rung up at a till, which are the ones fiscal law is written about.
    if (completed) {
      repo.findOrder(tenantId, orderId)
          .ifPresent(
              o -> {
                issueReceiptQuietly(o, null);
                // And the invoice a business buyer is owed (18.9), for the same reason.
                salesInvoices.issueInvoiceLater(o, null);
              });
    }
  }

  /**
   * A customer this shop erased (SJ-D43). Settled orders lose what identifies the customer now;
   * open ones keep their delivery details until they finish, then {@link #sweepErasures} takes
   * them.
   *
   * @param tenantId the shop that erased the customer
   * @param customerId the erased customer record
   * @param loginId the login that record was linked to, or {@code null} for a walk-in — the orders
   *     that shopper placed online are filed under it (SJ-D44)
   * @param eventId the event's id, which makes this idempotent
   * @return false when the event had already been applied
   */
  public boolean handleCustomerErased(UUID tenantId, UUID customerId, UUID loginId, UUID eventId) {
    return repo.applyCustomerErasure(
        tenantId, customerId, loginId, eventId, "order-svc/customer-erased");
  }

  // ── age verification: the due-diligence record ──────────────────────────────

  /**
   * Records one age check as the till made it (Licensing Act 2003 s.139: the defence is that all
   * reasonable precautions were taken — and a precaution nobody can show was taken is none).
   *
   * <p>The rule fields come from the request because they are product-svc's answer at the moment of
   * the check, and the record must say what the rule was then. The cashier comes from the token,
   * never the body. Store access is enforced: a cashier records checks at their own store.
   *
   * @param req the check
   * @param ctx caller identity
   * @return the record as stored
   * @throws ApiException {@code AGE_CHECK_REASON_REQUIRED} (400) for a refusal with no reason,
   *     {@code AGE_CHECK_REASON_ON_PASS} (400) for a pass carrying one, {@code
   *     AGE_CHECK_OUTCOME_UNKNOWN} / {@code AGE_CHECK_REASON_UNKNOWN} / {@code
   *     AGE_CHECK_ID_TYPE_UNKNOWN} (400) for values outside the vocabulary; {@code
   *     STORE_ACCESS_DENIED} (403) for another store
   */
  public AgeVerification recordAgeCheck(RecordAgeCheckRequest req, TenantContext ctx) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    // The same projection placeOrder consults: a store another tenant owns is refused outright,
    // whatever role the caller holds in their own — a record against someone else's store would
    // be a record in the wrong shop's register.
    if (!storeStatusRepo.isActive(tenantId, storeId))
      throw ApiException.conflict(
          "STORE_NOT_OPERATIONAL", "Store is closed, suspended or not this business's");
    String outcome = req.outcome().trim().toUpperCase(Locale.ROOT);
    if (!AgeVerification.OUTCOME_PASSED.equals(outcome)
        && !AgeVerification.OUTCOME_REFUSED.equals(outcome)) {
      throw ApiException.badRequest(
          "AGE_CHECK_OUTCOME_UNKNOWN", "outcome is PASSED or REFUSED, not " + req.outcome());
    }
    String reason = blankToNull(req.reason());
    String idType = blankToNull(req.idType());
    if (reason != null) {
      reason = reason.toUpperCase(Locale.ROOT);
      if (!AgeVerification.REASONS.contains(reason)) {
        throw ApiException.badRequest("AGE_CHECK_REASON_UNKNOWN", "unknown reason: " + reason);
      }
    }
    if (idType != null) {
      idType = idType.toUpperCase(Locale.ROOT);
      if (!AgeVerification.ID_TYPES.contains(idType)) {
        throw ApiException.badRequest("AGE_CHECK_ID_TYPE_UNKNOWN", "unknown id type: " + idType);
      }
    }
    boolean refused = AgeVerification.OUTCOME_REFUSED.equals(outcome);
    if (refused && reason == null) {
      throw ApiException.badRequest(
          "AGE_CHECK_REASON_REQUIRED", "a refusal records why: the reason is the record");
    }
    if (!refused && reason != null) {
      throw ApiException.badRequest(
          "AGE_CHECK_REASON_ON_PASS", "a sale that went ahead has no refusal reason");
    }
    if (refused && idType != null) {
      throw ApiException.badRequest(
          "AGE_CHECK_ID_TYPE_ON_REFUSAL", "an id type is recorded on a sale that went ahead");
    }
    java.time.LocalDate bornBefore = ageCheckCutoff(req.bornBefore());
    boolean cutoffPolicy = Boolean.TRUE.equals(req.bornBeforeStorePolicy());
    if (bornBefore == null
        && (cutoffPolicy || AgeVerification.REASON_BORN_AFTER_CUTOFF.equals(reason))) {
      throw ApiException.badRequest(
          "AGE_CHECK_CUTOFF_REQUIRED",
          "a refusal for the date of birth, or a cut-off policy, names the cut-off in bornBefore");
    }
    var record =
        new AgeVerification(
            Ids.newId(),
            tenantId,
            storeId,
            ctx.userId(),
            req.posSessionId() == null ? null : Parsing.uuid(req.posSessionId(), "posSessionId"),
            Parsing.uuid(req.variantId(), "variantId"),
            req.category().trim().toUpperCase(Locale.ROOT),
            req.minimumAge(),
            req.country().trim().toUpperCase(Locale.ROOT),
            Boolean.TRUE.equals(req.storePolicy()),
            bornBefore,
            cutoffPolicy,
            outcome,
            reason,
            idType,
            req.orderId() == null ? null : Parsing.uuid(req.orderId(), "orderId"),
            Instant.now());
    return repo.recordAgeVerification(record);
  }

  /**
   * The cut-off an age check was judged against, as product-svc gave it: a date between 1900 and
   * today, since a later one refuses nobody born yet.
   */
  private static java.time.LocalDate ageCheckCutoff(String raw) {
    if (raw == null || raw.isBlank()) return null;
    java.time.LocalDate date;
    try {
      date = java.time.LocalDate.parse(raw.trim());
    } catch (java.time.format.DateTimeParseException e) {
      throw new ApiException(
          400,
          "AGE_CHECK_BORN_BEFORE_INVALID",
          "bornBefore is a date written yyyy-mm-dd",
          java.util.List.of(),
          e);
    }
    if (date.getYear() < 1900 || date.isAfter(java.time.LocalDate.now(java.time.ZoneOffset.UTC))) {
      throw ApiException.badRequest(
          "AGE_CHECK_BORN_BEFORE_INVALID", "bornBefore is a date between 1900 and today");
    }
    return date;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  /** A page of age checks and the cursor for the next one. */
  public record AgeVerificationPage(List<AgeVerification> items, String nextCursor) {}

  /**
   * The age-check register, newest first, for a licensing officer or a manager.
   *
   * @param tenantId owning tenant
   * @param storeIdStr one store, or {@code null}
   * @param outcome PASSED, REFUSED, or {@code null}
   * @param from inclusive lower bound, or {@code null}
   * @param to exclusive upper bound, or {@code null}
   * @param afterCursor cursor from the previous page, or {@code null}
   * @param limit page size
   * @return the page
   * @throws ApiException {@code INVALID_CURSOR} (400) for a malformed cursor; {@code
   *     AGE_CHECK_OUTCOME_UNKNOWN} (400) for an outcome outside the vocabulary
   */
  public AgeVerificationPage listAgeChecks(
      UUID tenantId,
      String storeIdStr,
      String outcome,
      Instant from,
      Instant to,
      String afterCursor,
      int limit) {
    UUID storeId = storeIdStr != null ? Parsing.uuid(storeIdStr, "store") : null;
    String outcomeFilter = null;
    if (outcome != null && !outcome.isBlank()) {
      outcomeFilter = outcome.trim().toUpperCase(Locale.ROOT);
      if (!AgeVerification.OUTCOME_PASSED.equals(outcomeFilter)
          && !AgeVerification.OUTCOME_REFUSED.equals(outcomeFilter)) {
        throw ApiException.badRequest(
            "AGE_CHECK_OUTCOME_UNKNOWN", "outcome is PASSED or REFUSED, not " + outcome);
      }
    }
    Instant afterCheckedAt = null;
    UUID afterId = null;
    String rawKey = com.storeql.web.Cursor.decode(afterCursor);
    if (rawKey != null) {
      int sep = rawKey.indexOf('|');
      try {
        if (sep < 0) throw new IllegalArgumentException("missing separator");
        afterCheckedAt = Instant.parse(rawKey.substring(0, sep));
        afterId = Ids.parse(rawKey.substring(sep + 1));
      } catch (RuntimeException e) {
        throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
      }
    }
    List<AgeVerification> rows =
        repo.listAgeVerifications(
            tenantId, storeId, outcomeFilter, from, to, afterCheckedAt, afterId, limit + 1);
    if (rows.size() <= limit) {
      return new AgeVerificationPage(rows, null);
    }
    List<AgeVerification> page = rows.subList(0, limit);
    AgeVerification last = page.get(page.size() - 1);
    return new AgeVerificationPage(
        page, com.storeql.web.Cursor.encode(last.checkedAt().toString() + "|" + last.id()));
  }

  /**
   * The counts a licensing officer asks for first: how many checks, how many refusals, and why.
   *
   * @param tenantId owning tenant
   * @param storeIdStr one store, or {@code null} for the tenant
   * @param from inclusive lower bound, or {@code null}
   * @param to exclusive upper bound, or {@code null}
   * @return the summary
   */
  public AgeVerificationSummary summariseAgeChecks(
      UUID tenantId, String storeIdStr, Instant from, Instant to) {
    UUID storeId = storeIdStr != null ? Parsing.uuid(storeIdStr, "store") : null;
    return repo.summariseAgeVerifications(tenantId, storeId, from, to);
  }

  /** Redacts orders that have finished since their customer was erased. */
  public int sweepErasures() {
    return repo.sweepErasures();
  }

  /**
   * Cancels a still-pending order whose payment failed, releasing its stock hold.
   *
   * <p>Only acts on a PENDING order: a failure arriving after the order was confirmed by another
   * tender must not cancel a sale that has since been paid.
   *
   * @param tenantId owning tenant
   * @param orderId the order whose payment failed
   */
  public void handlePaymentFailed(java.util.UUID tenantId, java.util.UUID orderId) {
    repo.findOrder(tenantId, orderId)
        .ifPresent(
            o -> {
              if (Order.STATUS_PENDING.equals(o.status())) {
                repo.transitionOrderStatus(
                    tenantId,
                    orderId,
                    Order.STATUS_PENDING,
                    Order.STATUS_CANCELLED,
                    "payment failed",
                    null,
                    Events.orderCancelled(tenantId, orderId, "payment failed"));
              }
            });
  }

  /**
   * Apply a refund reported by payment-svc (PaymentRefunded) to the order: accumulate the refunded
   * total and flip a sold order (CONFIRMED/FULFILLED) to PARTIALLY_REFUNDED / REFUNDED. Idempotent
   * on the payment event's {@code eventId}.
   */
  public void applyRefund(
      java.util.UUID eventId,
      java.util.UUID tenantId,
      java.util.UUID orderId,
      java.math.BigDecimal amount) {
    applyRefund(eventId, tenantId, orderId, amount, false);
  }

  /**
   * As above; an {@code adjustment} refund (a line closed short or substituted) records the money
   * and moves no status — the goods are still to be handed over.
   */
  public void applyRefund(
      java.util.UUID eventId,
      java.util.UUID tenantId,
      java.util.UUID orderId,
      java.math.BigDecimal amount,
      boolean adjustment) {
    if (eventId == null || amount == null || amount.signum() <= 0) {
      return;
    }
    repo.applyRefundOnce(eventId, tenantId, orderId, amount, adjustment);
  }

  /**
   * Cancel PENDING orders older than {@code ttlHours} — stranded pay-later orders that were never
   * paid (a paid one would have confirmed). Each cancellation emits OrderCancelled, which releases
   * the inventory hold (inventory-svc) and is a payment no-op (nothing captured). An order
   * confirmed concurrently between the scan and the update is left alone. Returns the count
   * cancelled. Driven by {@code PendingOrderSweeper}.
   */
  public int sweepExpiredPendingOrders(int ttlHours, int batchLimit) {
    int cancelled = 0;
    for (var ref : repo.findExpiredPendingOrders(ttlHours, batchLimit)) {
      try {
        repo.transitionOrderStatus(
            ref.tenantId(),
            ref.orderId(),
            Order.STATUS_PENDING,
            Order.STATUS_CANCELLED,
            "expired: payment not received",
            null,
            Events.orderCancelled(ref.tenantId(), ref.orderId(), "expired: payment not received"));
        cancelled++;
      } catch (ApiException e) {
        // Confirmed/cancelled concurrently between the scan and the conditional update — leave it.
        LOG.log(
            java.lang.System.Logger.Level.DEBUG,
            "Skipped expiring order {0}: {1}",
            ref.orderId(),
            e.getMessage());
      }
    }
    return cancelled;
  }

  // ── Gap #42: Special orders ───────────────────────────────────────────────

  /**
   * Opens a special order: goods a store does not stock, ordered in for a named customer.
   *
   * @param tenantId owning tenant
   * @param req the store, customer, items and optional currency
   * @param idempotencyKey the caller's key, already checked to be a UUIDv7, or {@code null}
   * @param ctx caller context, checked for access to the store
   * @return the opened special order
   * @throws ApiException {@code SPECIAL_ORDER_NO_ITEMS} (400) when no items are supplied
   */
  public SpecialOrder createSpecialOrder(
      UUID tenantId, CreateSpecialOrderRequest req, String idempotencyKey, TenantContext ctx) {
    if (req.items() == null || req.items().isEmpty())
      throw ApiException.badRequest(
          "SPECIAL_ORDER_NO_ITEMS", "special order must have at least one item");

    UUID soId = Ids.newId();
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    UUID customerId =
        req.customerId() != null ? Parsing.uuid(req.customerId(), "customerId") : null;
    String currency = resolveCurrency(tenantId, req.currency());

    java.math.BigDecimal subtotal = java.math.BigDecimal.ZERO;
    List<SpecialOrderItem> items = new ArrayList<>();
    for (var ir : req.items()) {
      var line = ir.unitPrice().multiply(ir.qty());
      subtotal = subtotal.add(line);
      items.add(
          new SpecialOrderItem(
              Ids.newId(),
              tenantId,
              soId,
              Parsing.uuid(ir.variantId(), "variantId"),
              ir.qty(),
              ir.unitPrice(),
              line,
              ir.notes()));
    }

    java.time.LocalDate delivDate = null;
    if (req.requestedDeliveryDate() != null && !req.requestedDeliveryDate().isBlank())
      delivDate = Parsing.date(req.requestedDeliveryDate(), "requestedDeliveryDate");

    var so =
        new SpecialOrder(
            soId,
            tenantId,
            storeId,
            customerId,
            req.customerName(),
            req.customerPhone(),
            req.customerEmail(),
            req.deliveryAddress(),
            delivDate,
            req.notes(),
            SpecialOrder.STATUS_PENDING,
            subtotal,
            subtotal,
            currency,
            idempotencyKey,
            Instant.now(),
            Instant.now());

    return repo.createSpecialOrder(so, items);
  }

  /** One page of special orders plus the opaque cursor for the next page (null when exhausted). */
  public record SpecialOrderPage(List<SpecialOrder> orders, String nextCursor) {}

  /**
   * Cursor-paginated list of the tenant's special orders.
   *
   * @param tenantId owning tenant
   * @param storeId restrict to one store, or {@code null}
   * @param status restrict to one status, or {@code null}
   * @param afterCursor cursor from the previous page, or {@code null} to start
   * @param limit page size
   * @return the page and its next cursor
   * @throws ApiException {@code INVALID_CURSOR} (400) when the cursor is malformed
   */
  public SpecialOrderPage listSpecialOrders(
      UUID tenantId, String storeIdStr, String customerIdStr, String afterCursor, int limit) {
    UUID storeId = storeIdStr != null ? Parsing.uuid(storeIdStr, "storeId") : null;
    UUID customerId = customerIdStr != null ? Parsing.uuid(customerIdStr, "customerId") : null;
    Instant afterCreatedAt = null;
    UUID afterId = null;
    String rawKey = com.storeql.web.Cursor.decode(afterCursor);
    if (rawKey != null) {
      int sep = rawKey.indexOf('|');
      try {
        if (sep < 0) throw new IllegalArgumentException("missing separator");
        afterCreatedAt = Instant.parse(rawKey.substring(0, sep));
        afterId = Ids.parse(rawKey.substring(sep + 1));
      } catch (RuntimeException e) {
        throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
      }
    }
    // Fetch one extra row to learn whether a further page exists without a second query.
    List<SpecialOrder> rows =
        repo.listSpecialOrders(tenantId, storeId, customerId, afterCreatedAt, afterId, limit + 1);
    if (rows.size() <= limit) {
      return new SpecialOrderPage(rows, null);
    }
    List<SpecialOrder> page = rows.subList(0, limit);
    SpecialOrder last = page.get(page.size() - 1);
    return new SpecialOrderPage(
        page, com.storeql.web.Cursor.encode(last.createdAt().toString() + "|" + last.id()));
  }

  /**
   * Reads one special order.
   *
   * <p>Also the tenant-scoping guard the other special-order methods call first.
   *
   * @param tenantId owning tenant
   * @param id the special order to read
   * @return the special order
   * @throws ApiException {@code SPECIAL_ORDER_NOT_FOUND} (404) when it does not exist in this
   *     tenant
   */
  public SpecialOrder getSpecialOrder(UUID tenantId, UUID id) {
    return repo.findSpecialOrder(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("SPECIAL_ORDER_NOT_FOUND", "special order not found"));
  }

  /**
   * The lines on one special order.
   *
   * @param tenantId owning tenant
   * @param soId the special order whose lines to read
   * @return the ordered lines with their prices
   */
  public List<SpecialOrderItem> getSpecialOrderItems(UUID tenantId, UUID soId) {
    getSpecialOrder(tenantId, soId);
    return repo.findSpecialOrderItems(tenantId, soId);
  }

  /**
   * Confirms a special order once the goods are on their way.
   *
   * @param tenantId owning tenant
   * @param soId the special order to confirm
   * @param userId the staff member confirming it
   * @return the confirmed special order
   * @throws ApiException {@code SPECIAL_ORDER_NOT_FOUND} (404) when it does not exist; a conflict
   *     when its current status does not allow confirmation
   */
  public SpecialOrder confirmSpecialOrder(UUID tenantId, UUID soId, UUID userId) {
    return repo.transitionSpecialOrderStatus(
        tenantId,
        soId,
        SpecialOrder.STATUS_PENDING,
        SpecialOrder.STATUS_CONFIRMED,
        "confirmed",
        userId);
  }

  /**
   * Marks a special order handed over to the customer.
   *
   * @param tenantId owning tenant
   * @param soId the special order to fulfil
   * @param userId the staff member handing it over
   * @return the fulfilled special order
   * @throws ApiException {@code SPECIAL_ORDER_NOT_FOUND} (404) when it does not exist; a conflict
   *     when its current status does not allow fulfilment
   */
  public SpecialOrder fulfilSpecialOrder(UUID tenantId, UUID soId, UUID userId) {
    return repo.transitionSpecialOrderStatus(
        tenantId,
        soId,
        SpecialOrder.STATUS_CONFIRMED,
        SpecialOrder.STATUS_FULFILLED,
        "fulfilled",
        userId);
  }

  /**
   * Cancels a special order that has not yet been handed over.
   *
   * @param tenantId owning tenant
   * @param soId the special order to cancel
   * @param userId the staff member cancelling it
   * @return the cancelled special order
   * @throws ApiException {@code SPECIAL_ORDER_NOT_FOUND} (404) when it does not exist; {@code
   *     SPECIAL_ORDER_FULFILLED} (409) when it has already been fulfilled
   */
  public SpecialOrder cancelSpecialOrder(UUID tenantId, UUID soId, UUID userId) {
    var so = getSpecialOrder(tenantId, soId);
    if (SpecialOrder.STATUS_FULFILLED.equals(so.status()))
      throw ApiException.conflict(
          "SPECIAL_ORDER_FULFILLED", "cannot cancel a fulfilled special order");
    return repo.transitionSpecialOrderStatus(
        tenantId, soId, so.status(), SpecialOrder.STATUS_CANCELLED, "cancelled", userId);
  }

  // ── Gap #43: POSLog ───────────────────────────────────────────────────────

  /**
   * Writes a POSLog entry for a till sale — the audit record a POS audit expects.
   *
   * <p>POS channel only: an online order has no till transaction to log.
   *
   * @param tenantId owning tenant
   * @param orderId the sale to log
   * @param userId the cashier who rang it
   * @return the recorded entry
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists; {@code
   *     POSLOG_NOT_POS} (400) when the order is not a POS sale
   */
  public PosLogEntry recordPosLog(UUID tenantId, UUID orderId, UUID userId) {
    var order =
        repo.findOrder(tenantId, orderId)
            .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "order not found"));
    if (!Order.CHANNEL_POS.equals(order.channel()))
      throw ApiException.badRequest("POSLOG_NOT_POS", "POSLog is only for POS channel orders");
    var entry =
        new PosLogEntry(
            Ids.newId(),
            tenantId,
            orderId,
            order.storeId(),
            userId,
            order.subtotal(),
            order.taxAmount(),
            order.discountAmount(),
            order.total(),
            order.currency(),
            order.taxExempt(),
            order.exemptReason(),
            Instant.now(),
            Instant.now());
    // Idempotent on the order: the till calls this straight after taking money, so it is on
    // the retry path — and an offline sale replays it along with everything else.
    return repo.recordPosLogOnce(entry);
  }

  // ── Staff exception report ────────────────────────────────────────────────

  /**
   * What loss prevention actually asks: which cashier is an outlier. Sums the three append-only
   * logs that record staff-initiated exceptions — discounts granted, sales voided, drawer opened
   * with no sale — against the transaction journal that says how much each person sold, so a result
   * can be read as a rate rather than a ranking of who worked the most shifts.
   *
   * <p>Rows are merged on the key rather than joined in SQL: these are four independent logs, and a
   * join would multiply a cashier's 3 discounts by their 2 voids into 6 of each. Merging also means
   * someone who appears in only one log still gets a row, which is the case that matters — the
   * cashier with no sales and four no-sales is the whole point of the report.
   *
   * <p>A null actor buckets as {@code UNATTRIBUTED} rather than being dropped. An exception nobody
   * is accountable for is the last thing this report should hide.
   */
  public List<ExceptionRow> exceptionReport(
      UUID tenantId, UUID storeId, Instant from, Instant to, ExceptionGrouping grouping) {
    boolean byActor = grouping == ExceptionGrouping.ACTOR;
    Map<String, BigDecimal[]> money = new LinkedHashMap<>();
    Map<String, long[]> counts = new LinkedHashMap<>();

    for (Object[] r : repo.aggregateDiscounts(tenantId, storeId, from, to, byActor)) {
      String k = key(r[0]);
      counts.computeIfAbsent(k, x -> new long[4])[0] = (Long) r[1];
      money.computeIfAbsent(k, x -> newMoney())[0] = (BigDecimal) r[2];
    }
    for (Object[] r : repo.aggregateVoids(tenantId, storeId, from, to, byActor)) {
      counts.computeIfAbsent(key(r[0]), x -> new long[4])[1] = (Long) r[1];
    }
    for (Object[] r : repo.aggregateNoSales(tenantId, storeId, from, to, byActor)) {
      counts.computeIfAbsent(key(r[0]), x -> new long[4])[2] = (Long) r[1];
    }
    for (Object[] r : repo.aggregateJournalledSales(tenantId, storeId, from, to, byActor)) {
      String k = key(r[0]);
      counts.computeIfAbsent(k, x -> new long[4])[3] = (Long) r[1];
      money.computeIfAbsent(k, x -> newMoney())[1] = (BigDecimal) r[2];
    }

    List<ExceptionRow> rows = new ArrayList<>();
    for (Map.Entry<String, long[]> e : counts.entrySet()) {
      long[] c = e.getValue();
      BigDecimal[] m = money.getOrDefault(e.getKey(), newMoney());
      rows.add(new ExceptionRow(e.getKey(), c[0], m[0], c[1], c[2], c[3], m[1]));
    }
    // Most exceptions first, money breaking the tie: two cashiers with three exceptions each are
    // not equally interesting if one of them discounted a hundred times more.
    rows.sort(
        java.util.Comparator.comparingLong(
                (ExceptionRow r) -> r.discounts() + r.voids() + r.noSales())
            .thenComparing(ExceptionRow::discountAmount)
            .reversed());
    return rows;
  }

  private static BigDecimal[] newMoney() {
    return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};
  }

  /** Null actor or store ids are bucketed, never dropped. */
  private static String key(Object raw) {
    return raw == null ? "UNATTRIBUTED" : raw.toString();
  }

  // ---- sales by hour / by staff ----

  /**
   * Takings bucketed by hour of the trading day, on the clock of a named timezone.
   *
   * <p>The timezone is validated here rather than in the resource because getting it wrong is a
   * domain error, not a parsing one: {@code Europe/Londn} parses fine as a string and would reach
   * Postgres, which rejects it with an error that surfaces as a 500. {@link ZoneId#of} knows the
   * same tz database Postgres does, so validating with it turns that into the 400 it always was.
   *
   * @param tz an IANA zone name such as {@code Europe/London}; defaults to UTC when absent
   */
  public List<SalesByHourRow> salesByHour(
      UUID tenantId, UUID storeId, String channel, Instant from, Instant to, String tz) {
    requireOrderedPeriod(from, to);
    String normalisedChannel = normaliseChannel(channel);
    return salesAnalyticsRepo
        .salesByHour(tenantId, storeId, normalisedChannel, from, to, zone(tz))
        .stream()
        .map(
            r ->
                new SalesByHourRow(
                    r.hourOfDay(),
                    r.orders(),
                    r.grossAmount(),
                    r.discountAmount(),
                    // An hour with no orders produces no row, so the divisor is never zero.
                    r.grossAmount()
                        .divide(BigDecimal.valueOf(r.orders()), 2, RoundingMode.HALF_UP)))
        .toList();
  }

  /**
   * Takings by the cashier who rang them up, from the POS transaction journal.
   *
   * <p>Online orders have no cashier and are therefore not here at all. That is a property of the
   * data rather than a filter — the journal only ever covers the till.
   */
  public List<SalesByStaffRow> salesByStaff(
      UUID tenantId, UUID storeId, Instant from, Instant to, int limit) {
    requireOrderedPeriod(from, to);
    return salesAnalyticsRepo.salesByStaff(tenantId, storeId, from, to, limit).stream()
        .map(OrderService::withStaffRatios)
        .toList();
  }

  /**
   * Average basket and discount rate for one cashier.
   *
   * <p>The discount rate divides by what the sales would have been worth undiscounted, not by what
   * they fetched: discounting £50 off £100 is half the ticket given away, and dividing by the £50
   * that was actually taken would call it 100%.
   */
  private static SalesByStaffRow withStaffRatios(SalesByStaffRow r) {
    BigDecimal basket =
        r.sales() == 0
            ? null
            : r.grossAmount().divide(BigDecimal.valueOf(r.sales()), 2, RoundingMode.HALF_UP);
    BigDecimal undiscounted = r.grossAmount().add(r.discountAmount());
    BigDecimal rate =
        undiscounted.signum() <= 0
            ? null
            : r.discountAmount()
                .multiply(BigDecimal.valueOf(100))
                .divide(undiscounted, 1, RoundingMode.HALF_UP);
    return new SalesByStaffRow(
        r.groupKey(), r.sales(), r.grossAmount(), r.discountAmount(), basket, rate);
  }

  private static ZoneId zone(String tz) {
    if (tz == null || tz.isBlank()) return ZoneOffset.UTC;
    try {
      return ZoneId.of(tz.trim());
    } catch (DateTimeException e) {
      // Cause preserved, as the grouping parsers do: what ZoneId disliked about the string is
      // the only thing that distinguishes a typo from an offset in a form it will not take.
      throw new ApiException(
          400,
          "ORDER_INVALID_TIMEZONE",
          "tz must be an IANA zone name such as Europe/London, or an ISO offset such as"
              + " +05:30 — got: "
              + tz,
          List.of(),
          e);
    }
  }

  /** Only the two channels exist; anything else is a caller error, not an empty result. */
  private static String normaliseChannel(String channel) {
    if (channel == null || channel.isBlank()) return null;
    String c = channel.trim().toUpperCase(Locale.ROOT);
    if (!Order.CHANNEL_ONLINE.equals(c) && !Order.CHANNEL_POS.equals(c))
      throw ApiException.badRequest(
          "ORDER_INVALID_CHANNEL", "channel must be ONLINE or POS — got: " + channel);
    return c;
  }

  private static void requireOrderedPeriod(Instant from, Instant to) {
    if (from != null && to != null && !from.isBefore(to))
      throw ApiException.badRequest(
          "ORDER_INVALID_PERIOD", "from must be before to — got " + from + " and " + to);
  }

  /** One page of POSLog entries plus the opaque cursor for the next page (null when exhausted). */
  public record PosLogPage(List<PosLogEntry> entries, String nextCursor) {}

  /**
   * Cursor-paginated POSLog entries for the tenant.
   *
   * @param tenantId owning tenant
   * @param storeIdStr restrict to one store, or {@code null} for all
   * @param afterCursor cursor from the previous page, or {@code null} to start
   * @param limit page size
   * @return the page and its next cursor
   * @throws ApiException {@code INVALID_CURSOR} (400) when the cursor is malformed
   */
  public PosLogPage listPosLog(UUID tenantId, String storeIdStr, String afterCursor, int limit) {
    UUID storeId = storeIdStr != null ? Parsing.uuid(storeIdStr, "storeId") : null;
    Instant afterTransactionTs = null;
    UUID afterId = null;
    String rawKey = com.storeql.web.Cursor.decode(afterCursor);
    if (rawKey != null) {
      int sep = rawKey.indexOf('|');
      try {
        if (sep < 0) throw new IllegalArgumentException("missing separator");
        afterTransactionTs = Instant.parse(rawKey.substring(0, sep));
        afterId = Ids.parse(rawKey.substring(sep + 1));
      } catch (RuntimeException e) {
        throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
      }
    }
    // Fetch one extra row to learn whether a further page exists without a second query.
    List<PosLogEntry> rows =
        repo.listPosLog(tenantId, storeId, afterTransactionTs, afterId, limit + 1);
    if (rows.size() <= limit) {
      return new PosLogPage(rows, null);
    }
    List<PosLogEntry> page = rows.subList(0, limit);
    PosLogEntry last = page.get(page.size() - 1);
    return new PosLogPage(
        page, com.storeql.web.Cursor.encode(last.transactionTs().toString() + "|" + last.id()));
  }

  /**
   * The POSLog entries recorded against one sale.
   *
   * @param tenantId owning tenant
   * @param orderId the sale whose log entries to read
   * @return the entries, empty when the sale was not rung on a till
   */
  public List<PosLogEntry> getPosLogByOrder(UUID tenantId, UUID orderId) {
    return repo.findPosLogByOrder(tenantId, orderId);
  }

  // ── Gap #44: Receipts ─────────────────────────────────────────────────────

  /**
   * Records a print/email receipt event. For {@link OrderReceipt#TYPE_EMAIL}, builds a plain-text
   * receipt and delivers it via notification-svc (SMTP when configured, APP log otherwise) before
   * persisting the audit row — a send failure surfaces as 503 so the cashier is not told it emailed
   * when it did not.
   */
  public OrderReceipt generateReceipt(UUID tenantId, UUID orderId, GenerateReceiptRequest req) {
    return generateReceipt(tenantId, orderId, req, null);
  }

  /**
   * Records a print/email receipt event, optionally with the caller's context for access checks.
   *
   * <p>An email receipt is delivered <em>before</em> the audit row is written, so a send failure
   * surfaces as 503 rather than telling the cashier it emailed when it did not.
   *
   * @param tenantId owning tenant
   * @param orderId the sale being receipted
   * @param req the receipt type and, for email, the destination address
   * @param ctx caller context, or {@code null} for an internal caller with no access check
   * @return the recorded receipt event
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists; a 503 when an
   *     email receipt could not be delivered
   */
  public OrderReceipt generateReceipt(
      UUID tenantId, UUID orderId, GenerateReceiptRequest req, TenantContext ctx) {
    Order order =
        repo.findOrder(tenantId, orderId)
            .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "order not found"));
    if (OrderReceipt.TYPE_EMAIL.equals(req.receiptType())
        && (req.emailedTo() == null || req.emailedTo().isBlank()))
      throw ApiException.badRequest(
          "RECEIPT_EMAIL_REQUIRED", "emailedTo required for EMAIL receipts");

    if (OrderReceipt.TYPE_EMAIL.equals(req.receiptType())) {
      List<OrderItem> items = repo.findOrderItems(tenantId, orderId);
      String body = formatReceiptEmail(order, items);
      String subject = "Your receipt — order " + shortId(order.id());
      UUID eventId = Ids.newId();
      UUID userId = ctx != null ? ctx.userId() : null;
      java.util.Set<String> roles =
          ctx != null && ctx.roles() != null ? ctx.roles() : java.util.Set.of("CASHIER");
      notifications.send(
          tenantId,
          userId,
          roles,
          req.emailedTo().trim(),
          subject,
          body,
          "POS_RECEIPT",
          eventId,
          order.customerId());
    }

    int printCount = req.printCount() != null ? req.printCount() : 1;
    var receipt =
        new OrderReceipt(
            Ids.newId(),
            tenantId,
            orderId,
            req.receiptType(),
            req.emailedTo(),
            printCount,
            Instant.now());
    return repo.insertOrderReceipt(receipt);
  }

  private static String shortId(UUID id) {
    String s = id.toString().replace("-", "");
    return s.substring(0, Math.min(8, s.length())).toUpperCase(Locale.ROOT);
  }

  private static String formatReceiptEmail(Order order, List<OrderItem> items) {
    StringBuilder sb = new StringBuilder();
    sb.append("Thank you for your purchase.\n\n");
    sb.append("Order: ").append(order.id()).append('\n');
    sb.append("Channel: ").append(order.channel()).append('\n');
    sb.append("Status: ").append(order.status()).append('\n');
    if (order.createdAt() != null) {
      sb.append("Date (UTC): ").append(order.createdAt()).append('\n');
    }
    sb.append('\n').append("Items:\n");
    if (items == null || items.isEmpty()) {
      sb.append("  (no line items)\n");
    } else {
      for (OrderItem i : items) {
        sb.append("  • ")
            .append(i.qty())
            .append(" × ")
            .append(i.variantId())
            .append(" @ ")
            .append(i.unitPrice())
            .append(" = ")
            .append(i.lineTotal())
            .append(' ')
            .append(order.currency())
            .append('\n');
      }
    }
    sb.append('\n');
    sb.append("Subtotal: ")
        .append(order.subtotal())
        .append(' ')
        .append(order.currency())
        .append('\n');
    if (order.taxAmount() != null) {
      sb.append("Tax: ")
          .append(order.taxAmount())
          .append(' ')
          .append(order.currency())
          .append('\n');
    }
    if (order.discountAmount() != null
        && order.discountAmount().compareTo(java.math.BigDecimal.ZERO) != 0) {
      sb.append("Discount: ")
          .append(order.discountAmount())
          .append(' ')
          .append(order.currency())
          .append('\n');
    }
    sb.append("Total: ").append(order.total()).append(' ').append(order.currency()).append('\n');
    sb.append("\n— StoreQL\n");
    return sb.toString();
  }

  /**
   * Every print/email receipt event recorded against one sale.
   *
   * <p>Distinct from the fiscal receipt: this is the log of times a copy was produced, not the
   * numbered tax document.
   *
   * @param tenantId owning tenant
   * @param orderId the sale whose receipt events to read
   * @return the receipt events, empty when none were produced
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists in this tenant
   */
  public List<OrderReceipt> listReceipts(UUID tenantId, UUID orderId) {
    repo.findOrder(tenantId, orderId)
        .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "order not found"));
    return repo.findOrderReceipts(tenantId, orderId);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final SecureRandom RNG = new SecureRandom();

  private String generateGiftCardCode() {
    StringBuilder sb = new StringBuilder(16);
    for (int i = 0; i < 16; i++) {
      if (i > 0 && i % 4 == 0) sb.append('-');
      sb.append(CODE_CHARS.charAt(RNG.nextInt(CODE_CHARS.length())));
    }
    return sb.toString();
  }

  // ── Deposit return (09.16) ────────────────────────────

  private static final int MAX_REFUND_KINDS = 20;
  private static final int MAX_REFUND_OF_ONE_KIND = 500;
  private static final int MAX_REFUND_CONTAINERS = 2000;

  /** The deposit lines of an order; empty for a sale with no container a scheme takes back. */
  public List<OrderDeposit> depositsOf(UUID tenantId, UUID orderId) {
    return depositRepo.depositsOf(tenantId, orderId);
  }

  /**
   * The deposit a return scheme puts on each drink's container in the sale (09.16): one line per
   * item sold in a container the scheme covers, by the material and volume the catalogue records.
   * Nothing where no scheme is in force where the store trades today.
   */
  private List<OrderDeposit> containerDeposits(
      UUID tenantId, UUID storeId, String currency, UUID orderId, List<OrderItem> items) {
    if (items.isEmpty()) return List.of();
    java.util.Optional<com.storeql.service.Jurisdictions.DepositScheme> scheme;
    try {
      scheme =
          jurisdictions.depositScheme(
              tenantId, storeId, currency, java.time.LocalDate.now(java.time.ZoneOffset.UTC));
    } catch (ApiException e) {
      // The register or the business's profile could not be read. A till keeps selling when
      // tenant-svc is away (the currency is projected for that reason); the deposit not charged is
      // the business's loss to the scheme, not the customer's, and it is said here.
      LOG.log(
          System.Logger.Level.WARNING,
          "deposit register unavailable for tenant {0}; sale placed without deposit lines: {1}",
          tenantId,
          e.getMessage());
      return List.of();
    }
    if (scheme.isEmpty()) return List.of();
    var s = scheme.get();
    var facts =
        products
            .namesAsSystem(tenantId, items.stream().map(OrderItem::variantId).distinct().toList())
            .orElse(java.util.Map.of());
    List<OrderDeposit> out = new java.util.ArrayList<>();
    for (OrderItem item : items) {
      var v = facts.get(item.variantId());
      if (v == null || v.depositMaterial() == null || v.depositVolumeMl() == null) continue;
      if (!s.covers(v.depositMaterial(), v.depositVolumeMl())) continue;
      BigDecimal amount =
          s.depositEach().multiply(item.qty()).setScale(2, java.math.RoundingMode.HALF_UP);
      // Where the scheme taxes the deposit it is taxed as the drink: the deposit is quoted gross,
      // so the VAT is the part inside it at the line's rate.
      BigDecimal vatRate = s.taxed() ? item.vatRate() : null;
      BigDecimal vat =
          vatRate == null
              ? BigDecimal.ZERO.setScale(2)
              : amount.subtract(
                  amount.divide(BigDecimal.ONE.add(vatRate), 2, java.math.RoundingMode.HALF_UP));
      out.add(
          new OrderDeposit(
              Ids.newId(),
              tenantId,
              orderId,
              item.id(),
              item.variantId(),
              v.depositMaterial(),
              v.depositVolumeMl(),
              item.qty(),
              s.depositEach(),
              amount,
              currency,
              s.vatTreatment(),
              vatRate,
              vat,
              s.scope(),
              s.citation(),
              Instant.now()));
    }
    return out;
  }

  /**
   * Deposits paid back at the till for containers brought back (09.16), at the scheme's amount for
   * each container it takes back.
   *
   * @throws ApiException {@code 400 ORDER_CONTAINER_LINES_INVALID} for no lines or more than 20
   *     kinds; {@code 400 ORDER_CONTAINER_COUNT_TOO_MANY} for more than 500 of one kind or 2,000 in
   *     all; {@code 400 ORDER_CONTAINER_NOT_IN_SCHEME} for a container the scheme does not take
   *     back; {@code 409 ORDER_DEPOSIT_SCHEME_NOT_IN_FORCE} where no scheme is in force where the
   *     store trades today; {@code 409 STORE_NOT_OPERATIONAL} for a store that is not trading
   */
  public com.storeql.order.domain.Domain.ContainerRefund refundContainers(
      com.storeql.order.dto.Dtos.ContainerRefundRequest req,
      TenantContext ctx,
      String idempotencyKey) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    UUID tillSessionId = Parsing.uuid(req.tillSessionId(), "tillSessionId");
    ctx.requireStoreAccess(storeId);
    if (!storeStatusRepo.isActive(tenantId, storeId)) {
      throw ApiException.conflict(
          "STORE_NOT_OPERATIONAL", "this store is not trading; nothing is paid out at its till");
    }
    if (req.lines() == null || req.lines().isEmpty() || req.lines().size() > MAX_REFUND_KINDS) {
      throw ApiException.badRequest(
          "ORDER_CONTAINER_LINES_INVALID",
          "give one to " + MAX_REFUND_KINDS + " kinds of container brought back");
    }
    String currency = resolveCurrency(tenantId, null);
    var scheme =
        jurisdictions
            .depositScheme(
                tenantId, storeId, currency, java.time.LocalDate.now(java.time.ZoneOffset.UTC))
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "ORDER_DEPOSIT_SCHEME_NOT_IN_FORCE",
                        "no deposit return scheme is in force where this store trades"));
    List<com.storeql.order.domain.Domain.ContainerRefundLine> lines = new java.util.ArrayList<>();
    int containers = 0;
    BigDecimal amount = BigDecimal.ZERO.setScale(2);
    for (var line : req.lines()) {
      String material = line.material().trim().toUpperCase(java.util.Locale.ROOT);
      if (line.count() > MAX_REFUND_OF_ONE_KIND) {
        throw ApiException.badRequest(
            "ORDER_CONTAINER_COUNT_TOO_MANY",
            "at most " + MAX_REFUND_OF_ONE_KIND + " containers of one kind in one refund");
      }
      if (!scheme.covers(material, line.volumeMl())) {
        throw ApiException.badRequest(
            "ORDER_CONTAINER_NOT_IN_SCHEME",
            material
                + " "
                + line.volumeMl()
                + " ml is not a container the "
                + scheme.scope()
                + " scheme takes back");
      }
      containers += line.count();
      BigDecimal lineAmount =
          scheme
              .depositEach()
              .multiply(BigDecimal.valueOf(line.count()))
              .setScale(2, java.math.RoundingMode.HALF_UP);
      amount = amount.add(lineAmount);
      lines.add(
          new com.storeql.order.domain.Domain.ContainerRefundLine(
              material, line.volumeMl(), line.count(), scheme.depositEach(), lineAmount));
    }
    if (containers > MAX_REFUND_CONTAINERS) {
      throw ApiException.badRequest(
          "ORDER_CONTAINER_COUNT_TOO_MANY",
          "at most " + MAX_REFUND_CONTAINERS + " containers in one refund");
    }
    var refund =
        new com.storeql.order.domain.Domain.ContainerRefund(
            Ids.newId(),
            tenantId,
            storeId,
            tillSessionId,
            currency,
            containers,
            amount,
            scheme.scope(),
            idempotencyKey,
            ctx.userId(),
            Instant.now(),
            List.copyOf(lines));
    return depositRepo.insertRefund(refund, Events.containerDepositRefunded(refund));
  }

  /**
   * A refund by id.
   *
   * @throws ApiException {@code 404 ORDER_CONTAINER_REFUND_NOT_FOUND}
   */
  public com.storeql.order.domain.Domain.ContainerRefund containerRefund(UUID tenantId, UUID id) {
    return depositRepo
        .refund(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("ORDER_CONTAINER_REFUND_NOT_FOUND", "refund not found"));
  }

  /**
   * Deposits charged on sales that stand and paid back at the till over [from, to) (09.16), by
   * material; the difference is what the scheme holds unredeemed.
   *
   * @throws ApiException {@code 400 ORDER_REPORT_PERIOD_INVALID} when from is not before to
   */
  public com.storeql.order.dto.Dtos.DepositReportResponse depositReport(
      TenantContext ctx, String storeIdRaw, String fromRaw, String toRaw) {
    UUID tenantId = ctx.requireTenantId();
    Instant from = Parsing.instant(fromRaw, "from");
    Instant to = Parsing.instant(toRaw, "to");
    if (!from.isBefore(to)) {
      throw ApiException.badRequest("ORDER_REPORT_PERIOD_INVALID", "from must be before to");
    }
    UUID storeId =
        storeIdRaw == null || storeIdRaw.isBlank() ? null : Parsing.uuid(storeIdRaw, "storeId");
    if (storeId != null) ctx.requireStoreAccess(storeId);
    var rows = depositRepo.report(tenantId, storeId, from, to);
    long chargedContainers = 0;
    long refundedContainers = 0;
    BigDecimal chargedAmount = BigDecimal.ZERO.setScale(2);
    BigDecimal chargedVat = BigDecimal.ZERO.setScale(2);
    BigDecimal refundedAmount = BigDecimal.ZERO.setScale(2);
    List<com.storeql.order.dto.Dtos.DepositReportRowResponse> byMaterial =
        new java.util.ArrayList<>();
    for (var r : rows) {
      chargedContainers += r.chargedContainers();
      refundedContainers += r.refundedContainers();
      chargedAmount = chargedAmount.add(r.chargedAmount());
      chargedVat = chargedVat.add(r.chargedVat());
      refundedAmount = refundedAmount.add(r.refundedAmount());
      byMaterial.add(
          new com.storeql.order.dto.Dtos.DepositReportRowResponse(
              r.material(),
              r.chargedContainers(),
              r.chargedAmount(),
              r.chargedVat(),
              r.refundedContainers(),
              r.refundedAmount()));
    }
    return new com.storeql.order.dto.Dtos.DepositReportResponse(
        from.toString(),
        to.toString(),
        storeId == null ? null : storeId.toString(),
        resolveCurrency(tenantId, null),
        chargedContainers,
        chargedAmount,
        chargedVat,
        refundedContainers,
        refundedAmount,
        chargedAmount.subtract(refundedAmount),
        byMaterial);
  }
}
