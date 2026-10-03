package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.order.client.PricingClient;
import com.storeql.order.config.ServiceConfig;
import com.storeql.order.domain.Domain;
import com.storeql.order.domain.Domain.Order;
import com.storeql.order.domain.Domain.OrderItem;
import com.storeql.order.dto.Dtos.OrderItemRequest;
import com.storeql.order.dto.Dtos.PlaceOrderRequest;
import com.storeql.order.repo.OrderRepository;
import com.storeql.service.StoreStatusRepository;
import com.storeql.service.TenantStatusRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Gap #63 — placeOrder must not trust client-supplied money when pricing enforcement is on. */
@ExtendWith(MockitoExtension.class)
class OrderServicePricingTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID VARIANT = Ids.newId();

  @Mock OrderRepository repo;
  @Mock ServiceConfig config;
  @Mock PricingClient pricing;
  @Mock com.storeql.order.client.InventoryClient inventory;
  @Mock TenantContext ctx;
  @Mock TenantStatusRepository tenantStatusRepo;
  @Mock StoreStatusRepository storeStatusRepo;
  @Mock com.storeql.service.TenantProfiles profiles;

  private OrderService svc;

  @BeforeEach
  void setUp() {
    svc = new OrderService();
    svc.repo = repo;
    svc.config = config;
    svc.pricing = pricing;
    svc.inventory = inventory;
    svc.tenantStatusRepo = tenantStatusRepo;
    svc.storeStatusRepo = storeStatusRepo;
    svc.profiles = profiles;
    // 09.16: no deposit scheme reaches these sales; a mock answers empty.
    svc.jurisdictions = org.mockito.Mockito.mock(com.storeql.service.Jurisdictions.class);
    // Stop-sale and certified scales: nothing here is recalled or weighed; a mock refuses nothing.
    svc.saleChecks = org.mockito.Mockito.mock(SaleChecks.class);
    // The tenant's declared currency, as tenant-svc would answer (SJ-D53).
    org.mockito.Mockito.lenient().when(profiles.requireCurrency(TENANT)).thenReturn("USD");
    when(ctx.requireTenantId()).thenReturn(TENANT);
    when(tenantStatusRepo.isActive(any())).thenReturn(true);
    when(storeStatusRepo.isActive(any(), any())).thenReturn(true);
  }

  /**
   * A quoted basket with no promotions on it. Checkout now asks pricing-svc to price the whole
   * basket rather than each line, because a spend threshold or a buy-one-get-one has nothing to be
   * about until the order total exists.
   */
  private static PricingClient.QuotedBasket quoted(PricingClient.QuotedLine... lines) {
    return new PricingClient.QuotedBasket(
        List.of(lines), BigDecimal.ZERO, List.of(), java.util.Map.of());
  }

  private static PlaceOrderRequest request(BigDecimal clientUnitPrice, BigDecimal discount) {
    return request(clientUnitPrice, discount, null);
  }

  private static PlaceOrderRequest request(
      BigDecimal clientUnitPrice, BigDecimal discount, String discountReason) {
    return PlaceOrderRequest.builder()
        .storeId(STORE.toString())
        .channel("POS")
        .fulfilmentType("INSTORE")
        .items(
            List.of(
                new OrderItemRequest(
                    VARIANT.toString(),
                    BigDecimal.ONE,
                    clientUnitPrice,
                    null,
                    null,
                    null,
                    null,
                    null)))
        .discountAmount(discount)
        .discountReason(discountReason)
        .currency("USD")
        .build();
  }

  @Test
  void enforcementOnUsesServerPriceAndIgnoresClientPrice() {
    when(config.pricingEnforce()).thenReturn(true);
    when(pricing.quoteBasket(eq(TENANT), anyList(), eq(STORE), eq("POS"), any(), any()))
        .thenReturn(
            quoted(
                new PricingClient.QuotedLine(
                    new BigDecimal("7.77"), new BigDecimal("7.77"), BigDecimal.ZERO)));
    when(repo.createOrder(any(), anyList(), any(), any(), anyList(), anyList(), anyList(), any()))
        .thenAnswer(inv -> inv.getArgument(0));

    // client claims the item costs 0.01 — the server-resolved 7.77 must win
    Order order = svc.placeOrder(request(new BigDecimal("0.01"), null), ctx, null);

    assertEquals(new BigDecimal("7.77"), order.subtotal());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<OrderItem>> items = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(repo)
        .createOrder(any(), items.capture(), any(), any(), anyList(), anyList(), anyList(), any());
    assertEquals(new BigDecimal("7.77"), items.getValue().get(0).unitPrice());
  }

  @Test
  void enforcementOnFailsClosedWhenPriceCannotBeResolved() {
    when(config.pricingEnforce()).thenReturn(true);
    when(pricing.quoteBasket(any(), any(), any(), any(), any(), any()))
        .thenThrow(ApiException.unprocessable("ORDER_PRICE_UNRESOLVED", "no price"));

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.placeOrder(request(new BigDecimal("0.01"), null), ctx, null));
    assertEquals("ORDER_PRICE_UNRESOLVED", e.code());
    verifyNoInteractions(repo);
  }

  @Test
  void enforcementOffTrustsClientPrice() {
    when(config.pricingEnforce()).thenReturn(false);
    when(repo.createOrder(any(), anyList(), any(), any(), anyList(), anyList(), anyList(), any()))
        .thenAnswer(inv -> inv.getArgument(0));

    Order order = svc.placeOrder(request(new BigDecimal("5.00"), null), ctx, null);

    assertEquals(new BigDecimal("5.00"), order.subtotal());
    verifyNoInteractions(pricing);
  }

  @Test
  void enforcementOffStillRequiresAUnitPrice() {
    when(config.pricingEnforce()).thenReturn(false);

    ApiException e =
        assertThrows(ApiException.class, () -> svc.placeOrder(request(null, null), ctx, null));
    assertEquals("ORDER_PRICE_REQUIRED", e.code());
  }

  @Test
  void discountLargerThanSubtotalIsRejected() {
    when(config.pricingEnforce()).thenReturn(false);
    org.mockito.Mockito.lenient().when(ctx.hasRole("MANAGER")).thenReturn(true);

    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.placeOrder(
                    request(new BigDecimal("5.00"), new BigDecimal("10.00")), ctx, null));
    assertEquals("ORDER_DISCOUNT_EXCEEDS_SUBTOTAL", e.code());
    verifyNoInteractions(repo);
  }

  // ── a till's full-basket discount on weighed goods ─────────────────────────

  /**
   * Two weighed lines of 0.333 at {@code unitPrice}, a till sale with the till's own discount. The
   * till adds the lines up unrounded and caps the discount at that; this service rounds each line
   * half up at the currency's units first, so the till's cap can be the rounded subtotal plus a
   * part of a minor unit per line.
   */
  private static PlaceOrderRequest weighedTillSale(String unitPrice, String discount) {
    BigDecimal qty = new BigDecimal("0.333");
    BigDecimal price = new BigDecimal(unitPrice);
    return PlaceOrderRequest.builder()
        .storeId(STORE.toString())
        .channel("POS")
        .fulfilmentType("INSTORE")
        .items(
            List.of(
                new OrderItemRequest(
                    Ids.newId().toString(), qty, price, null, null, null, null, null),
                new OrderItemRequest(
                    Ids.newId().toString(), qty, price, null, null, null, null, null)))
        .discountAmount(new BigDecimal(discount))
        .discountReason("staff party")
        .build();
  }

  private Order placeWeighedAsOwner(String currency, String unitPrice, String discount) {
    when(config.pricingEnforce()).thenReturn(false);
    org.mockito.Mockito.lenient().when(profiles.requireCurrency(TENANT)).thenReturn(currency);
    when(config.discountCeilings()).thenReturn(Map.of("OWNER", new BigDecimal("100")));
    when(ctx.roles()).thenReturn(Set.of("OWNER"));
    when(repo.createOrder(any(), anyList(), any(), any(), anyList(), anyList(), anyList(), any()))
        .thenAnswer(inv -> inv.getArgument(0));
    return svc.placeOrder(weighedTillSale(unitPrice, discount), ctx, null);
  }

  /**
   * 2 × 0.333 kg at 1.99 is 1.32534 on the till, which shows 1.33; here each line is 0.66 and the
   * order 1.32. The cashier gives the whole basket away: the till's 1.32534, half up 1.33, is more
   * than the order, and was refused 400 ORDER_DISCOUNT_EXCEEDS_SUBTOTAL — the sale stopped and its
   * offline replay refused for ever. It is the whole basket: 1.32.
   */
  @Test
  void aTillsFullBasketDiscountOnWeighedGoodsIsCappedAtTheSubtotal() {
    Order order = placeWeighedAsOwner("USD", "1.99", "1.32534");

    assertEquals(new BigDecimal("1.32"), order.subtotal());
    assertEquals(new BigDecimal("1.32"), order.discountAmount());
    assertEquals(0, order.total().signum());
    ArgumentCaptor<Domain.OrderDiscount> audit =
        ArgumentCaptor.forClass(Domain.OrderDiscount.class);
    org.mockito.Mockito.verify(repo)
        .createOrder(
            any(), anyList(), any(), audit.capture(), anyList(), anyList(), anyList(), any());
    // The audit row says what was given, measured against what the order came to.
    assertEquals(new BigDecimal("1.32"), audit.getValue().discountAmount());
    assertEquals(new BigDecimal("100.000"), audit.getValue().discountPct());
  }

  /** The same at a dinar's three places: 2 × 0.664 is 1.328; the till's 1.32867 is 1.329. */
  @Test
  void aDinarTillsFullBasketDiscountIsCappedAtTheSubtotalsFils() {
    Order order = placeWeighedAsOwner("KWD", "1.995", "1.32867");

    assertEquals(new BigDecimal("1.328"), order.subtotal());
    assertEquals(new BigDecimal("1.328"), order.discountAmount());
    assertEquals(0, order.total().signum());
  }

  /** And in whole yen: 2 × ¥66 is ¥132; the till's 132.534 is ¥133. */
  @Test
  void aYenTillsFullBasketDiscountIsCappedAtTheSubtotalsYen() {
    Order order = placeWeighedAsOwner("JPY", "199", "132.534");

    assertEquals(new BigDecimal("132"), order.subtotal());
    assertEquals(new BigDecimal("132"), order.discountAmount());
    assertEquals(0, order.total().signum());
  }

  /**
   * With pricing enforced the lines are the quote's, each already rounded by pricing-svc; the
   * till's figure is still capped at them.
   */
  @Test
  void aTillsFullBasketDiscountIsCappedAtTheQuotedSubtotal() {
    when(config.pricingEnforce()).thenReturn(true);
    when(config.discountCeilings()).thenReturn(Map.of("OWNER", new BigDecimal("100")));
    when(ctx.roles()).thenReturn(Set.of("OWNER"));
    PricingClient.QuotedLine line =
        new PricingClient.QuotedLine(
            new BigDecimal("1.99"), new BigDecimal("0.66"), BigDecimal.ZERO);
    when(pricing.quoteBasket(eq(TENANT), anyList(), eq(STORE), eq("POS"), any(), any()))
        .thenReturn(quoted(line, line));
    when(repo.createOrder(any(), anyList(), any(), any(), anyList(), anyList(), anyList(), any()))
        .thenAnswer(inv -> inv.getArgument(0));

    Order order = svc.placeOrder(weighedTillSale("1.99", "1.3253400000000002"), ctx, null);

    assertEquals(new BigDecimal("1.32"), order.discountAmount());
    assertEquals(0, order.total().signum());
  }

  /**
   * The till's prices may be older than the quote's (a sale rung up offline before a price came
   * down): the cashier gave away the basket the till showed, 2 × 0.333 kg at 2.50 = 1.665, half up
   * 1.67. It is still the whole basket, at the quote's 1.32.
   */
  @Test
  void aTillsDiscountAtAnOlderShelfPriceIsStillTheWholeBasket() {
    when(config.pricingEnforce()).thenReturn(true);
    when(config.discountCeilings()).thenReturn(Map.of("OWNER", new BigDecimal("100")));
    when(ctx.roles()).thenReturn(Set.of("OWNER"));
    PricingClient.QuotedLine line =
        new PricingClient.QuotedLine(
            new BigDecimal("1.99"), new BigDecimal("0.66"), BigDecimal.ZERO);
    when(pricing.quoteBasket(eq(TENANT), anyList(), eq(STORE), eq("POS"), any(), any()))
        .thenReturn(quoted(line, line));
    when(repo.createOrder(any(), anyList(), any(), any(), anyList(), anyList(), anyList(), any()))
        .thenAnswer(inv -> inv.getArgument(0));

    Order order = svc.placeOrder(weighedTillSale("2.50", "1.665"), ctx, null);

    assertEquals(new BigDecimal("1.32"), order.discountAmount());
    assertEquals(0, order.total().signum());
  }

  /**
   * More than the goods the till itself rang up is no rounding of the till's but a wrong figure:
   * refused by name, as a discount typed anywhere else is, and nothing written — at a pound's two
   * places, a dinar's three and in whole yen.
   */
  @Test
  void aTillsDiscountAboveTheGoodsItRangUpIsRefused() {
    when(config.pricingEnforce()).thenReturn(false);
    String[][] cases = {
      {"USD", "1.99", "1.34"}, // the till's goods 1.32534 → 1.33
      {"KWD", "1.995", "1.330"}, // 1.32867 → 1.329
      {"JPY", "199", "134"}, // 132.534 → 133
    };
    for (String[] c : cases) {
      org.mockito.Mockito.lenient().when(profiles.requireCurrency(TENANT)).thenReturn(c[0]);
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> svc.placeOrder(weighedTillSale(c[1], c[2]), ctx, null),
              c[0]);
      assertEquals("ORDER_DISCOUNT_EXCEEDS_SUBTOTAL", e.code(), c[0]);
      assertEquals(400, e.status(), c[0]);
    }
    verifyNoInteractions(repo);
  }

  /**
   * The cap never widens anyone's authority: a manager's ceiling is measured on the capped figure,
   * and the whole basket is over a 50% ceiling — refused as before, nothing written.
   */
  @Test
  void theCappedDiscountIsStillHeldToTheRolesCeiling() {
    when(config.pricingEnforce()).thenReturn(false);
    when(config.discountCeilings()).thenReturn(Map.of("MANAGER", new BigDecimal("50")));
    when(ctx.roles()).thenReturn(Set.of("MANAGER"));

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.placeOrder(weighedTillSale("1.99", "1.32534"), ctx, null));
    assertEquals("ORDER_DISCOUNT_EXCEEDS_AUTHORITY", e.code());
    assertEquals(403, e.status());
    verifyNoInteractions(repo);
  }

  @Test
  void nonStaffCallerCannotSelfApplyADiscount() {
    when(config.pricingEnforce()).thenReturn(false);
    // ctx mock has no staff role stubbed -> hasRole(...) defaults to false for every role.

    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.placeOrder(request(new BigDecimal("5.00"), new BigDecimal("1.00")), ctx, null));
    assertEquals("ORDER_DISCOUNT_NOT_ALLOWED", e.code());
    verifyNoInteractions(repo);
  }

  /**
   * Regression test for the tenant-aware store check in StoreStatusRepository: a storeId that
   * belongs to a different tenant (or the projection has recorded under one) must reject the order,
   * not just check whether *some* store with that id happens to be ACTIVE. Without this, a caller
   * could place an order under tenant A referencing a real, active store that actually belongs to
   * tenant B.
   */
  @Test
  void placeOrderRejectsAStoreThatBelongsToAnotherTenant() {
    // The real repo method returns false here specifically because STORE is on record under a
    // different tenant than TENANT — simulated directly at the mock boundary since that
    // tenant-vs-projection comparison lives in StoreStatusRepository, not OrderService.
    when(storeStatusRepo.isActive(TENANT, STORE)).thenReturn(false);

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.placeOrder(request(new BigDecimal("5.00"), null), ctx, null));
    assertEquals("STORE_NOT_OPERATIONAL", e.code());
    verifyNoInteractions(repo);
  }

  /**
   * This test previously asserted that a client discount was ignored under pricing enforcement.
   * That behaviour was the bug (SJ-D6): the till tendered subtotal - discount against an order
   * stored at full price, so the payment never covered the total and the sale was swept away as
   * unconfirmed. Enforcement owns the unit price -- the client's 0.01 is still overridden by the
   * resolved 10.00 -- but a staff discount on top is honoured.
   */
  @Test
  void enforcementOnOwnsThePriceButHonoursAStaffDiscount() {
    when(config.pricingEnforce()).thenReturn(true);
    when(config.discountCeilings()).thenReturn(Map.of("MANAGER", new BigDecimal("50")));
    when(ctx.roles()).thenReturn(Set.of("MANAGER"));
    when(pricing.quoteBasket(eq(TENANT), anyList(), eq(STORE), eq("POS"), any(), any()))
        .thenReturn(
            quoted(
                new PricingClient.QuotedLine(
                    new BigDecimal("10.00"), new BigDecimal("10.00"), new BigDecimal("2.00"))));
    when(repo.createOrder(any(), anyList(), any(), any(), anyList(), anyList(), anyList(), any()))
        .thenAnswer(inv -> inv.getArgument(0));

    Order order =
        svc.placeOrder(
            request(new BigDecimal("0.01"), new BigDecimal("4.00"), "damaged packaging"),
            ctx,
            null);

    assertEquals(new BigDecimal("10.00"), order.subtotal());
    assertEquals(new BigDecimal("2.00"), order.taxAmount());
    assertEquals(new BigDecimal("4.00"), order.discountAmount());
    assertEquals(new BigDecimal("8.00"), order.total());
  }

  /** A discount above the caller role's ceiling needs someone more senior (SJ-D6). */
  @Test
  void discountAboveTheRoleCeilingIsRefused() {
    when(config.pricingEnforce()).thenReturn(true);
    when(config.discountCeilings()).thenReturn(Map.of("CASHIER", new BigDecimal("10")));
    when(ctx.roles()).thenReturn(Set.of("CASHIER"));
    when(pricing.quoteBasket(eq(TENANT), anyList(), eq(STORE), eq("POS"), any(), any()))
        .thenReturn(
            quoted(
                new PricingClient.QuotedLine(
                    new BigDecimal("10.00"), new BigDecimal("10.00"), BigDecimal.ZERO)));

    // 2.00 off 10.00 is 20%, over the cashier's 10% ceiling.
    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.placeOrder(
                    request(new BigDecimal("10.00"), new BigDecimal("2.00"), "goodwill"),
                    ctx,
                    null));
    assertEquals("ORDER_DISCOUNT_EXCEEDS_AUTHORITY", e.code());
    verifyNoInteractions(repo);
  }

  /** The caller's most permissive role decides, and it is the one recorded on the audit row. */
  @Test
  void theHighestCeilingAmongTheCallersRolesApplies() {
    when(config.pricingEnforce()).thenReturn(true);
    when(config.discountCeilings())
        .thenReturn(Map.of("CASHIER", new BigDecimal("10"), "MANAGER", new BigDecimal("50")));
    when(ctx.roles()).thenReturn(Set.of("CASHIER", "MANAGER"));
    when(pricing.quoteBasket(eq(TENANT), anyList(), eq(STORE), eq("POS"), any(), any()))
        .thenReturn(
            quoted(
                new PricingClient.QuotedLine(
                    new BigDecimal("10.00"), new BigDecimal("10.00"), BigDecimal.ZERO)));
    when(repo.createOrder(any(), anyList(), any(), any(), anyList(), anyList(), anyList(), any()))
        .thenAnswer(inv -> inv.getArgument(0));

    // 3.00 off 10.00 is 30%: over CASHIER's ceiling, within MANAGER's.
    svc.placeOrder(request(new BigDecimal("10.00"), new BigDecimal("3.00"), "damaged"), ctx, null);

    ArgumentCaptor<Domain.OrderDiscount> audit =
        ArgumentCaptor.forClass(Domain.OrderDiscount.class);
    org.mockito.Mockito.verify(repo)
        .createOrder(
            any(), anyList(), any(), audit.capture(), anyList(), anyList(), anyList(), any());
    assertEquals("MANAGER", audit.getValue().grantedRole());
    assertEquals(new BigDecimal("30.000"), audit.getValue().discountPct());
    assertEquals("damaged", audit.getValue().reason());
  }

  /** A discount with no stated reason is unauditable, so it is refused (SJ-D6). */
  @Test
  void discountWithoutAReasonIsRefused() {
    when(config.pricingEnforce()).thenReturn(true);
    when(config.discountCeilings()).thenReturn(Map.of("MANAGER", new BigDecimal("50")));
    when(ctx.roles()).thenReturn(Set.of("MANAGER"));
    when(pricing.quoteBasket(eq(TENANT), anyList(), eq(STORE), eq("POS"), any(), any()))
        .thenReturn(
            quoted(
                new PricingClient.QuotedLine(
                    new BigDecimal("10.00"), new BigDecimal("10.00"), BigDecimal.ZERO)));

    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.placeOrder(
                    request(new BigDecimal("10.00"), new BigDecimal("1.00"), "   "), ctx, null));
    assertEquals("ORDER_DISCOUNT_REASON_REQUIRED", e.code());
    verifyNoInteractions(repo);
  }

  // ── Date-code markdown (05.4) ──────────────────────────────────────────────

  private static final UUID MARKDOWN = Ids.newId();

  private static PlaceOrderRequest stickered() {
    return PlaceOrderRequest.builder()
        .storeId(STORE.toString())
        .channel("POS")
        .fulfilmentType("INSTORE")
        .items(
            List.of(
                new OrderItemRequest(
                    VARIANT.toString(),
                    new BigDecimal("2"),
                    null,
                    null,
                    null,
                    MARKDOWN.toString(),
                    null,
                    null)))
        .currency("USD")
        .build();
  }

  /**
   * A line a reduced-price sticker was scanned for names its markdown to pricing-svc, keeps it on
   * the stored line, and counts the sticker down once the order stands.
   */
  @Test
  void aStickeredLineNamesItsMarkdownAndCountsItDown() {
    when(config.pricingEnforce()).thenReturn(true);
    when(pricing.quoteBasket(eq(TENANT), anyList(), eq(STORE), eq("POS"), any(), any()))
        .thenReturn(
            quoted(
                new PricingClient.QuotedLine(
                    new BigDecimal("2.00"), new BigDecimal("4.00"), BigDecimal.ZERO)));
    when(repo.createOrder(any(), anyList(), any(), any(), anyList(), anyList(), anyList(), any()))
        .thenAnswer(inv -> inv.getArgument(0));

    Order order = svc.placeOrder(stickered(), ctx, null);

    assertEquals(new BigDecimal("4.00"), order.subtotal());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PricingClient.LineRequest>> asked = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(pricing)
        .quoteBasket(eq(TENANT), asked.capture(), eq(STORE), eq("POS"), any(), any());
    assertEquals(MARKDOWN, asked.getValue().get(0).markdownId());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<OrderItem>> items = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(repo)
        .createOrder(any(), items.capture(), any(), any(), anyList(), anyList(), anyList(), any());
    assertEquals(MARKDOWN, items.getValue().get(0).markdownId());
    org.mockito.Mockito.verify(pricing)
        .recordMarkdownRedemptionsQuietly(eq(TENANT), any(), eq(items.getValue()));
  }

  /** A sticker pricing-svc refuses — another product's, or sold out — refuses the sale with why. */
  @Test
  void aRefusedStickerRefusesTheSaleWithTheReason() {
    when(config.pricingEnforce()).thenReturn(true);
    when(pricing.quoteBasket(any(), any(), any(), any(), any(), any()))
        .thenThrow(ApiException.conflict("PRICING_MARKDOWN_EXHAUSTED", "none left"));

    ApiException e = assertThrows(ApiException.class, () -> svc.placeOrder(stickered(), ctx, null));
    assertEquals("PRICING_MARKDOWN_EXHAUSTED", e.code());
    verifyNoInteractions(repo);
  }

  /** An ordinary line never touches the sticker counter. */
  @Test
  void anOrdinaryOrderRecordsNoMarkdownRedemption() {
    when(config.pricingEnforce()).thenReturn(true);
    when(pricing.quoteBasket(eq(TENANT), anyList(), eq(STORE), eq("POS"), any(), any()))
        .thenReturn(
            quoted(
                new PricingClient.QuotedLine(
                    new BigDecimal("7.77"), new BigDecimal("7.77"), BigDecimal.ZERO)));
    when(repo.createOrder(any(), anyList(), any(), any(), anyList(), anyList(), anyList(), any()))
        .thenAnswer(inv -> inv.getArgument(0));

    svc.placeOrder(request(new BigDecimal("0.01"), null), ctx, null);

    org.mockito.Mockito.verify(pricing, org.mockito.Mockito.never())
        .recordMarkdownRedemptionsQuietly(any(), any(), any());
  }

  // ── a weighed line: counted to three places, valued per line ───────────────

  /**
   * Two weighings of the same product added on the till post as 0.30000000000000004 kg. The line is
   * 0.300 kg — held, kept and deducted as the column keeps it — and is worth 0.300 × 1.99 = 0.597,
   * £0.60: the quantity it stands for, valued by the one line rule, not refused and not charged on
   * a figure the stock never sees. At a dinar's three places and in whole yen too.
   */
  @Test
  void aTillsAddedWeighingsAreCountedAtThreePlacesAndValuedPerLine() {
    when(config.pricingEnforce()).thenReturn(false);
    when(repo.createOrder(any(), anyList(), any(), any(), anyList(), anyList(), anyList(), any()))
        .thenAnswer(inv -> inv.getArgument(0));
    String[][] cases = {
      {"USD", "1.99", "0.60"}, {"KWD", "1.995", "0.599"}, {"JPY", "199", "60"},
    };
    for (String[] c : cases) {
      org.mockito.Mockito.lenient().when(profiles.requireCurrency(TENANT)).thenReturn(c[0]);
      PlaceOrderRequest sale =
          PlaceOrderRequest.builder()
              .storeId(STORE.toString())
              .channel("POS")
              .fulfilmentType("INSTORE")
              .items(
                  List.of(
                      new OrderItemRequest(
                          VARIANT.toString(),
                          new BigDecimal("0.30000000000000004"),
                          new BigDecimal(c[1]),
                          null,
                          null,
                          null,
                          null,
                          null)))
              .build();

      Order order = svc.placeOrder(sale, ctx, null);

      assertEquals(new BigDecimal(c[2]), order.subtotal(), c[0]);
    }
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<OrderItem>> items = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(3))
        .createOrder(any(), items.capture(), any(), any(), anyList(), anyList(), anyList(), any());
    for (List<OrderItem> lines : items.getAllValues()) {
      assertEquals(new BigDecimal("0.300"), lines.get(0).qty());
    }
    assertEquals(new BigDecimal("0.60"), items.getAllValues().get(0).get(0).lineTotal());
    assertEquals(new BigDecimal("0.599"), items.getAllValues().get(1).get(0).lineTotal());
    assertEquals(new BigDecimal("60"), items.getAllValues().get(2).get(0).lineTotal());
  }

  /**
   * A quantity finer than a reading's six places, and no double's noise around one, is no weight
   * any scale or label gives: refused, never rounded, nothing written.
   */
  @Test
  void aTillQuantityFinerThanThreePlacesIsRefusedNotRounded() {
    PlaceOrderRequest sale =
        PlaceOrderRequest.builder()
            .storeId(STORE.toString())
            .channel("POS")
            .fulfilmentType("INSTORE")
            .items(
                List.of(
                    new OrderItemRequest(
                        VARIANT.toString(),
                        new BigDecimal("0.3755123"),
                        new BigDecimal("1.99"),
                        null,
                        null,
                        null,
                        null,
                        null)))
            .build();
    // Refused before anything is asked of the business: the stubs this class sets up are spent
    // here so the refusal is what is measured.
    ctx.requireTenantId();
    tenantStatusRepo.isActive(TENANT);
    storeStatusRepo.isActive(TENANT, STORE);

    ApiException e = assertThrows(ApiException.class, () -> svc.placeOrder(sale, ctx, null));
    assertEquals("VALIDATION_FAILED", e.code());
    assertEquals(400, e.status());
    verifyNoInteractions(repo, pricing, inventory);
  }

  // ── a label's weight: the gram below, priced, held and kept at one figure ──

  private static PlaceOrderRequest labelSale(String qty, String price, String key) {
    return PlaceOrderRequest.builder()
        .storeId(STORE.toString())
        .channel("POS")
        .fulfilmentType("INSTORE")
        .items(
            List.of(
                new OrderItemRequest(
                    VARIANT.toString(),
                    new BigDecimal(qty),
                    new BigDecimal(price),
                    null,
                    null,
                    null,
                    null,
                    null)))
        .build();
  }

  /**
   * A pack labelled 0.37512 kg (GS1 AI 3105), which the till sells at the label's reading: the line
   * is 0.375 kg, the gram below, and is charged, kept and priced at that one figure — 0.375 ×
   * 100.00 = 37.50, which the till's own 37.512 always covers — not refused, and not charged on a
   * weight the stock never sees. In a dinar's fils and whole yen by the same rule.
   */
  @Test
  void aTillsLabelWeightIsChargedAndKeptAtTheGramBelow() {
    when(config.pricingEnforce()).thenReturn(false);
    when(repo.createOrder(any(), anyList(), any(), any(), anyList(), anyList(), anyList(), any()))
        .thenAnswer(inv -> inv.getArgument(0));
    String[][] cases = {
      {"USD", "100.00", "37.50"}, {"KWD", "1.995", "0.748"}, {"JPY", "199", "75"},
    };
    for (String[] c : cases) {
      org.mockito.Mockito.lenient().when(profiles.requireCurrency(TENANT)).thenReturn(c[0]);

      Order order = svc.placeOrder(labelSale("0.37512", c[1], null), ctx, null);

      assertEquals(new BigDecimal(c[2]), order.subtotal(), c[0]);
    }
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<OrderItem>> items = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(3))
        .createOrder(any(), items.capture(), any(), any(), anyList(), anyList(), anyList(), any());
    for (List<OrderItem> lines : items.getAllValues()) {
      assertEquals(new BigDecimal("0.375"), lines.get(0).qty());
    }
  }

  /** Under enforcement the quote is asked for the gram figure the line is kept at. */
  @Test
  void aTillsLabelWeightIsQuotedAtTheGramBelow() {
    when(config.pricingEnforce()).thenReturn(true);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PricingClient.LineRequest>> asked = ArgumentCaptor.forClass(List.class);
    when(pricing.quoteBasket(eq(TENANT), asked.capture(), eq(STORE), eq("POS"), any(), any()))
        .thenReturn(
            quoted(
                new PricingClient.QuotedLine(
                    new BigDecimal("4.87"), new BigDecimal("4.87"), BigDecimal.ZERO)));
    when(repo.createOrder(any(), anyList(), any(), any(), anyList(), anyList(), anyList(), any()))
        .thenAnswer(inv -> inv.getArgument(0));

    svc.placeOrder(labelSale("0.37512", "12.99", null), ctx, null);

    assertEquals(new BigDecimal("0.375"), asked.getValue().get(0).qty());
  }

  private static Order standing(UUID store, String key) {
    return new Order(
        Ids.newId(),
        TENANT,
        store,
        null,
        null,
        "POS",
        "INSTORE",
        "CONFIRMED",
        new BigDecimal("37.51"),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        new BigDecimal("37.51"),
        "USD",
        null,
        key,
        java.time.Instant.now(),
        java.time.Instant.now(),
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        BigDecimal.ZERO,
        null,
        true);
  }

  /**
   * A till sale accepted before quantities were counted (the column rounded it then) is retried
   * from the offline queue under its key: the sale that stands is the answer, not a refusal of a
   * figure nothing will be written from.
   */
  @Test
  void aRetriedSaleThatStandsIsAnsweredWhereItsQuantityIsNowRefused() {
    String key = Ids.newId().toString();
    Order earlier = standing(STORE, key);
    when(repo.findOrderByIdempotencyKey(TENANT, key)).thenReturn(java.util.Optional.of(earlier));
    when(ctx.hasStoreAccess(STORE)).thenReturn(true);
    tenantStatusRepo.isActive(TENANT);
    storeStatusRepo.isActive(TENANT, STORE);

    Order answer = svc.placeOrder(labelSale("0.3755123", "100.00", key), ctx, key);

    assertEquals(earlier, answer);
    org.mockito.Mockito.verify(repo, org.mockito.Mockito.never())
        .createOrder(any(), anyList(), any(), any(), anyList(), anyList(), anyList(), any());
    verifyNoInteractions(pricing, inventory);
  }

  /**
   * The refusal stands for a caller held to other stores than the earlier sale's, and with no key
   * nothing is looked up at all.
   */
  @Test
  void aRefusedQuantityIsNoWayToReadAnotherStoresSale() {
    String key = Ids.newId().toString();
    UUID elsewhere = Ids.newId();
    when(repo.findOrderByIdempotencyKey(TENANT, key))
        .thenReturn(java.util.Optional.of(standing(elsewhere, key)));
    when(ctx.hasStoreAccess(elsewhere)).thenReturn(false);
    tenantStatusRepo.isActive(TENANT);
    storeStatusRepo.isActive(TENANT, STORE);

    ApiException held =
        assertThrows(
            ApiException.class,
            () -> svc.placeOrder(labelSale("0.3755123", "100.00", key), ctx, key));
    assertEquals(400, held.status());
    assertEquals("VALIDATION_FAILED", held.code());

    ApiException keyless =
        assertThrows(
            ApiException.class,
            () -> svc.placeOrder(labelSale("0.3755123", "100.00", null), ctx, null));
    assertEquals(400, keyless.status());
    org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(1))
        .findOrderByIdempotencyKey(any(), any());
    org.mockito.Mockito.verify(repo, org.mockito.Mockito.never())
        .createOrder(any(), anyList(), any(), any(), anyList(), anyList(), anyList(), any());
  }
}
