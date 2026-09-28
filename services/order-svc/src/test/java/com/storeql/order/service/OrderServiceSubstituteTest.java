package com.storeql.order.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.order.client.PricingClient;
import com.storeql.order.client.ProductClient;
import com.storeql.order.client.StockClient;
import com.storeql.order.config.ServiceConfig;
import com.storeql.order.domain.Domain.Order;
import com.storeql.order.domain.Domain.OrderItem;
import com.storeql.order.domain.LineAdjustment;
import com.storeql.order.dto.Dtos.SubstituteRequest;
import com.storeql.order.repo.OrderRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Substitutions for out-of-stock online lines, the part that talks to other services: the
 * substitute is priced at the store by pricing-svc as at checkout and charged at no more than the
 * original, its own VAT within the charge; the shelf is read from inventory-svc and an empty one
 * refuses, an unreadable one does not; the shopper's "no" is final; and a replayed key does nothing
 * twice.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceSubstituteTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID ORDER = Ids.newId();
  private static final UUID ITEM = Ids.newId();
  private static final UUID VARIANT = Ids.newId();
  private static final UUID SUB = Ids.newId();
  private static final UUID STAFF = Ids.newId();

  @Mock OrderRepository repo;
  @Mock ServiceConfig config;
  @Mock PricingClient pricing;
  @Mock StockClient stock;
  @Mock ProductClient products;
  @Mock TenantContext ctx;

  private OrderService svc;

  @BeforeEach
  void setUp() {
    svc = new OrderService();
    svc.repo = repo;
    svc.config = config;
    svc.pricing = pricing;
    svc.stock = stock;
    svc.products = products;
    lenient().when(ctx.userId()).thenReturn(STAFF);
    lenient().when(products.namesAsSystem(any(), any())).thenReturn(Optional.of(Map.of()));
  }

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  /** One unit of apples at 10.00 gross: 8.33 net + 1.67 VAT at 20%, a confirmed delivery. */
  private static Order order(boolean allowSubstitutions) {
    return new Order(
        ORDER,
        TENANT,
        STORE,
        null,
        Ids.newId(),
        "ONLINE",
        "DELIVERY",
        "CONFIRMED",
        d("8.33"),
        d("1.67"),
        BigDecimal.ZERO,
        d("10.00"),
        "GBP",
        null,
        null,
        Instant.now(),
        Instant.now(),
        false,
        null,
        "1 Park Row",
        null,
        "Leeds",
        "LS1 5AB",
        "Sam Shopper",
        null,
        null,
        null,
        BigDecimal.ZERO,
        null,
        allowSubstitutions);
  }

  private static OrderItem line() {
    return new OrderItem(
        ITEM,
        TENANT,
        ORDER,
        VARIANT,
        BigDecimal.ONE,
        d("10.00"),
        d("8.33"),
        null,
        null,
        BigDecimal.ZERO,
        d("1.67"),
        null,
        "S",
        d("0.2000"),
        BigDecimal.ZERO,
        null);
  }

  private static PricingClient.QuotedBasket quote(String net, String vat) {
    return new PricingClient.QuotedBasket(
        List.of(new PricingClient.QuotedLine(d(net), d(net), d(vat), "S", d("0.2000"))),
        BigDecimal.ZERO,
        List.of(),
        Map.of());
  }

  private static StockClient.Stock shelf(String available) {
    return new StockClient.Stock(
        available == null ? Map.of() : Map.of(STORE, Map.of(SUB, d(available))), Set.of());
  }

  private static SubstituteRequest ask(UUID substitute, String unitPrice) {
    return new SubstituteRequest(
        substitute.toString(), null, unitPrice == null ? null : d(unitPrice), "shelf empty");
  }

  /**
   * What the repository would do, given the priced substitute: the closed original and the new
   * line.
   */
  private static OrderRepository.Adjusted adjusted(OrderRepository.Substitute s) {
    OrderItem closed =
        new OrderItem(
            ITEM,
            TENANT,
            ORDER,
            VARIANT,
            BigDecimal.ONE,
            d("10.00"),
            d("0.00"),
            null,
            null,
            BigDecimal.ZERO,
            d("0.00"),
            null,
            "S",
            d("0.2000"),
            BigDecimal.ONE,
            null);
    OrderItem sub =
        new OrderItem(
            Ids.newId(),
            TENANT,
            ORDER,
            s.variantId(),
            s.qty(),
            s.unitPrice(),
            s.lineNet(),
            null,
            null,
            s.qty(),
            s.lineVat(),
            null,
            s.vatCode(),
            s.vatRate(),
            BigDecimal.ZERO,
            ITEM);
    BigDecimal charged = s.lineNet().add(s.lineVat() == null ? BigDecimal.ZERO : s.lineVat());
    Order after =
        new Order(
            ORDER,
            TENANT,
            STORE,
            null,
            null,
            "ONLINE",
            "DELIVERY",
            "FULFILLED",
            s.lineNet(),
            s.lineVat() == null ? BigDecimal.ZERO : s.lineVat(),
            BigDecimal.ZERO,
            charged,
            "GBP",
            null,
            null,
            Instant.now(),
            Instant.now(),
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
    return new OrderRepository.Adjusted(
        after,
        List.of(closed, sub),
        new LineAdjustment(
            Ids.newId(),
            TENANT,
            ORDER,
            LineAdjustment.SUBSTITUTED,
            ITEM,
            VARIANT,
            BigDecimal.ONE,
            sub.id(),
            SUB,
            charged,
            d("10.00").subtract(charged).max(BigDecimal.ZERO),
            "shelf empty",
            STAFF,
            null,
            Instant.now()),
        sub,
        Map.of(VARIANT, BigDecimal.ZERO, SUB, BigDecimal.ZERO),
        true,
        "CONFIRMED");
  }

  /**
   * Runs a substitution through the service and returns the priced line it asked the repository to
   * write.
   */
  private OrderRepository.Substitute substituted(SubstituteRequest req) {
    ArgumentCaptor<OrderRepository.Substitute> priced =
        ArgumentCaptor.forClass(OrderRepository.Substitute.class);
    when(repo.adjustLine(
            eq(TENANT),
            eq(ORDER),
            eq(VARIANT),
            any(),
            priced.capture(),
            eq("shelf empty"),
            eq(STAFF),
            any(),
            any()))
        .thenAnswer(
            inv -> {
              OrderRepository.Adjusted done = adjusted(inv.getArgument(4));
              Function<OrderRepository.Adjusted, List<OutboxRow>> events = inv.getArgument(8);
              List<OutboxRow> rows = events.apply(done);
              // The substitute is deducted and its revenue recorded like any picked line, and
              // the swap is announced beside it.
              assertEquals(
                  List.of("OrderFulfilled", "OrderLineSubstituted"),
                  rows.stream().map(OutboxRow::eventType).toList());
              assertThat(
                  rows.get(0).payload(), org.hamcrest.Matchers.containsString(SUB.toString()));
              return done;
            });
    svc.substitute(TENANT, ORDER, VARIANT, req, ctx, null);
    return priced.getValue();
  }

  private void confirmedOrderWithApples(boolean allow) {
    when(repo.findOrder(TENANT, ORDER)).thenReturn(Optional.of(order(allow)));
    lenient().when(repo.findOrderItems(TENANT, ORDER)).thenReturn(List.of(line()));
  }

  @Test
  void aDearerSubstituteIsChargedTheOriginalsPriceWithItsOwnVatInside() {
    confirmedOrderWithApples(true);
    when(config.pricingEnforce()).thenReturn(true);
    when(stock.stockByStore(eq(TENANT), any())).thenReturn(Optional.of(shelf("5")));
    // Pears are 12.00 + 2.40 VAT at the store; the shopper paid 10.00 gross for the apples.
    when(pricing.quoteBasket(eq(TENANT), anyList(), eq(STORE), eq("ONLINE"), any(), any()))
        .thenReturn(quote("12.00", "2.40"));
    OrderRepository.Substitute s = substituted(ask(SUB, null));
    assertThat(s.variantId(), is(SUB));
    assertThat(s.qty(), comparesEqualTo(BigDecimal.ONE));
    assertThat(s.lineNet(), comparesEqualTo(d("8.33")));
    assertThat(s.lineVat(), comparesEqualTo(d("1.67")));
    assertThat(s.unitPrice(), comparesEqualTo(d("8.33")));
    assertThat(s.vatCode(), is("S"));
    assertThat(s.vatRate(), comparesEqualTo(d("0.2000")));
  }

  @Test
  void aCheaperSubstituteIsChargedItsOwnPriceAndTheDifferenceGoesBack() {
    confirmedOrderWithApples(true);
    when(config.pricingEnforce()).thenReturn(true);
    when(stock.stockByStore(eq(TENANT), any())).thenReturn(Optional.of(shelf("5")));
    when(pricing.quoteBasket(eq(TENANT), anyList(), eq(STORE), eq("ONLINE"), any(), any()))
        .thenReturn(quote("6.00", "1.20"));
    OrderRepository.Substitute s = substituted(ask(SUB, null));
    assertThat(s.lineNet(), comparesEqualTo(d("6.00")));
    assertThat(s.lineVat(), comparesEqualTo(d("1.20")));
    assertThat(s.unitPrice(), comparesEqualTo(d("6.00")));
  }

  @Test
  void theShoppersNoIsFinal() {
    confirmedOrderWithApples(false);
    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.substitute(TENANT, ORDER, VARIANT, ask(SUB, null), ctx, null));
    assertThat(e.status(), is(409));
    assertThat(e.code(), is("ORDER_SUBSTITUTION_NOT_ALLOWED"));
    verify(repo, never()).adjustLine(any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(pricing, never()).quoteBasket(any(), anyList(), any(), any(), any(), any());
  }

  @Test
  void aSubstituteIsAnotherProduct() {
    confirmedOrderWithApples(true);
    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.substitute(TENANT, ORDER, VARIANT, ask(VARIANT, null), ctx, null));
    assertThat(e.status(), is(400));
    assertThat(e.code(), is("ORDER_SUBSTITUTE_SAME_VARIANT"));
  }

  @Test
  void anEmptyShelfRefusesAnUnreadableOneDoesNotAndASupplierShippedProductPasses() {
    confirmedOrderWithApples(true);
    when(config.pricingEnforce()).thenReturn(true);
    when(stock.stockByStore(eq(TENANT), any())).thenReturn(Optional.of(shelf(null)));
    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.substitute(TENANT, ORDER, VARIANT, ask(SUB, null), ctx, null));
    assertThat(e.status(), is(409));
    assertThat(e.code(), is("ORDER_SUBSTITUTE_NOT_SELLABLE"));
    verify(pricing, never()).quoteBasket(any(), anyList(), any(), any(), any(), any());

    // inventory-svc cannot answer: the picker has the thing in hand, so the sale goes through.
    when(stock.stockByStore(eq(TENANT), any())).thenReturn(Optional.empty());
    when(pricing.quoteBasket(eq(TENANT), anyList(), eq(STORE), eq("ONLINE"), any(), any()))
        .thenReturn(quote("6.00", "1.20"));
    assertThat(substituted(ask(SUB, null)).lineNet(), comparesEqualTo(d("6.00")));

    // A dropship product is held by no store and passes the shelf check.
    when(stock.stockByStore(eq(TENANT), any()))
        .thenReturn(Optional.of(new StockClient.Stock(Map.of(), Set.of(SUB))));
    assertThat(substituted(ask(SUB, null)).lineNet(), comparesEqualTo(d("6.00")));
  }

  @Test
  void noPriceAtTheStoreRefusesAndAnOpenCircuitIsUnavailable() {
    confirmedOrderWithApples(true);
    when(config.pricingEnforce()).thenReturn(true);
    when(stock.stockByStore(eq(TENANT), any())).thenReturn(Optional.of(shelf("5")));
    when(pricing.quoteBasket(eq(TENANT), anyList(), eq(STORE), eq("ONLINE"), any(), any()))
        .thenThrow(ApiException.notFound("PRICING_NO_PRICE", "no price for the variant"));
    ApiException none =
        assertThrows(
            ApiException.class,
            () -> svc.substitute(TENANT, ORDER, VARIANT, ask(SUB, null), ctx, null));
    assertThat(none.status(), is(409));
    assertThat(none.code(), is("ORDER_SUBSTITUTE_NOT_SELLABLE"));

    when(pricing.quoteBasket(eq(TENANT), anyList(), eq(STORE), eq("ONLINE"), any(), any()))
        .thenThrow(new CircuitBreakerOpenException("pricing-svc"));
    ApiException open =
        assertThrows(
            ApiException.class,
            () -> svc.substitute(TENANT, ORDER, VARIANT, ask(SUB, null), ctx, null));
    assertThat(open.status(), is(503));
    assertThat(open.code(), is("ORDER_PRICING_UNAVAILABLE"));
    verify(repo, never()).adjustLine(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void withServerSidePricingOffTheGivenPriceIsUsedAndStillCappedAndIsRequired() {
    confirmedOrderWithApples(true);
    when(config.pricingEnforce()).thenReturn(false);
    when(stock.stockByStore(eq(TENANT), any())).thenReturn(Optional.empty());
    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.substitute(TENANT, ORDER, VARIANT, ask(SUB, null), ctx, null));
    assertThat(e.status(), is(400));
    assertThat(e.code(), is("ORDER_PRICE_REQUIRED"));
    // 12.00 given for a unit the shopper paid 10.00 for: 10.00, all of it net with no VAT known.
    OrderRepository.Substitute s = substituted(ask(SUB, "12.00"));
    assertThat(s.lineNet(), comparesEqualTo(d("10.00")));
    assertThat(s.lineVat(), comparesEqualTo(d("0.00")));
    assertThat(s.unitPrice(), comparesEqualTo(d("10.00")));
    verify(pricing, never()).quoteBasket(any(), anyList(), any(), any(), any(), any());
  }

  @Test
  void aReplayedKeyChangesNothingAndAnswersTheOrder() {
    confirmedOrderWithApples(true);
    String key = Ids.newId().toString();
    when(repo.findAdjustmentByKey(TENANT, key))
        .thenReturn(
            Optional.of(
                adjusted(
                        new OrderRepository.Substitute(
                            SUB, BigDecimal.ONE, d("6.00"), d("6.00"), d("1.20"), "S", d("0.2000")))
                    .adjustment()));
    Order answered = svc.substitute(TENANT, ORDER, VARIANT, ask(SUB, null), ctx, key);
    assertThat(answered.id(), is(ORDER));
    verify(repo, never()).adjustLine(any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(pricing, never()).quoteBasket(any(), anyList(), any(), any(), any(), any());
  }
}
