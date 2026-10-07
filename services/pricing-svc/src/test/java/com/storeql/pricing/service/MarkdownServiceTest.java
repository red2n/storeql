package com.storeql.pricing.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.pricing.client.InventoryClient;
import com.storeql.pricing.domain.Domain.Markdown;
import com.storeql.pricing.domain.Domain.MarkdownLadder;
import com.storeql.pricing.domain.Domain.MarkdownStep;
import com.storeql.pricing.domain.Domain.PriceList;
import com.storeql.pricing.domain.Domain.PriceListItem;
import com.storeql.pricing.dto.Dtos.CreateMarkdownRequest;
import com.storeql.pricing.dto.Dtos.MarkdownStepRequest;
import com.storeql.pricing.repo.PricingRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The morning's plan (03.9) and the sticker's arithmetic (05.4), with inventory-svc and the
 * database stood in for. What the ladder says at each number of days, what the suggested price
 * comes to, what happens when inventory cannot be read, and what a bad request is refused for.
 */
class MarkdownServiceTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID VARIANT = Ids.newId();
  private static final UUID PRICE_LIST = Ids.newId();

  private final Map<UUID, BigDecimal> prices = new java.util.HashMap<>();
  private Optional<MarkdownLadder> ladder = Optional.empty();
  private Optional<List<InventoryClient.ExpiringBatch>> batches = Optional.of(List.of());
  private List<Markdown> active = List.of();
  private Markdown created;

  private MarkdownService svc;

  @BeforeEach
  void setUp() {
    svc = new MarkdownService();
    svc.repo =
        new PricingRepository() {
          @Override
          public Optional<MarkdownLadder> findLadder(UUID tenantId, UUID storeId) {
            return ladder;
          }

          @Override
          public Optional<PriceListItem> resolveBasePrice(
              UUID tenantId, UUID variantId, String channel, BigDecimal qty) {
            BigDecimal p = prices.get(variantId);
            return p == null
                ? Optional.empty()
                : Optional.of(
                    new PriceListItem(
                        Ids.newId(),
                        tenantId,
                        PRICE_LIST,
                        variantId,
                        p,
                        BigDecimal.ONE,
                        Instant.now(),
                        Instant.now()));
          }

          @Override
          public Optional<PriceList> findPriceList(UUID tenantId, UUID id) {
            return Optional.of(
                new PriceList(
                    id,
                    tenantId,
                    "POS",
                    PriceList.CHANNEL_POS,
                    "GBP",
                    Instant.EPOCH,
                    null,
                    true,
                    Instant.EPOCH));
          }

          @Override
          public List<Markdown> findActiveMarkdownsForBatches(UUID tenantId, List<UUID> batchIds) {
            return active;
          }

          @Override
          public Markdown createMarkdown(Markdown draft) {
            created = draft;
            return draft;
          }
        };
    svc.inventory =
        new InventoryClient() {
          @Override
          public Optional<List<InventoryClient.ExpiringBatch>> expiringBatches(
              UUID tenantId, UUID storeId, int withinDays, TenantContext ctx) {
            return batches;
          }
        };
  }

  private static TenantContext ctx(String role) {
    return new TenantContext() {
      @Override
      public UUID requireTenantId() {
        return TENANT;
      }

      @Override
      public UUID tenantId() {
        return TENANT;
      }

      @Override
      public Set<String> roles() {
        return Set.of(role);
      }

      @Override
      public Set<UUID> storeIds() {
        return Set.of();
      }

      @Override
      public void requireAnyRole(String... required) {
        for (String r : required) if (r.equals(role)) return;
        throw ApiException.forbidden("FORBIDDEN", "no");
      }

      @Override
      public void requireStoreAccess(UUID storeId) {}

      @Override
      public UUID userId() {
        return null;
      }
    };
  }

  private static InventoryClient.ExpiringBatch batch(long days, String qty) {
    return new InventoryClient.ExpiringBatch(
        Ids.newId(),
        VARIANT,
        "B-" + days,
        new BigDecimal(qty),
        LocalDate.now().plusDays(days),
        days);
  }

  // ── the ladder ─────────────────────────────────────────────────────────────

  @Test
  void theDefaultLadderStepsAreTheTightestAtOrAboveTheDays() {
    MarkdownLadder l = svc.ladder(TENANT, STORE);
    assertThat(l.source(), is(MarkdownLadder.SOURCE_DEFAULT));
    assertThat(l.stepFor(3).percentOff(), comparesEqualTo(new BigDecimal("25")));
    assertThat(l.stepFor(2).percentOff(), comparesEqualTo(new BigDecimal("25")));
    assertThat(l.stepFor(1).percentOff(), comparesEqualTo(new BigDecimal("50")));
    assertThat(l.stepFor(0).percentOff(), comparesEqualTo(new BigDecimal("75")));
    assertThat("four days out is not on the ladder", l.stepFor(4), nullValue());
  }

  @Test
  void twoStepsOnTheSameDayAreRefused() {
    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.setLadder(
                    ctx("MANAGER"),
                    null,
                    List.of(
                        new MarkdownStepRequest(2, new BigDecimal("20")),
                        new MarkdownStepRequest(2, new BigDecimal("30")))));
    assertThat(e.code(), is("PRICING_LADDER_DUPLICATE_STEP"));
  }

  @Test
  void aStorekeeperCannotSetTheLadder() {
    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.setLadder(
                    ctx("STOREKEEPER"),
                    null,
                    List.of(new MarkdownStepRequest(2, new BigDecimal("20")))));
    assertThat(e.code(), is("FORBIDDEN"));
  }

  // ── the plan ───────────────────────────────────────────────────────────────

  @Test
  void thePlanPricesEveryExpiringBatchOffTheLadder() {
    prices.put(VARIANT, new BigDecimal("4.00"));
    batches = Optional.of(List.of(batch(2, "12"), batch(0, "3"), batch(6, "40")));

    MarkdownService.Plan plan = svc.plan(ctx("STOREKEEPER"), STORE, 7);

    assertThat(plan.inventoryReachable(), is(true));
    assertThat(plan.suggestions().size(), is(3));
    var twoDays = plan.suggestions().get(0);
    assertThat(twoDays.step().daysToExpiry(), is(3));
    assertThat(twoDays.suggestedPrice(), comparesEqualTo(new BigDecimal("3.00")));
    var today = plan.suggestions().get(1);
    assertThat(today.step().daysToExpiry(), is(0));
    assertThat(today.suggestedPrice(), comparesEqualTo(new BigDecimal("1.00")));
    var sixDays = plan.suggestions().get(2);
    assertThat("six days out has no step yet", sixDays.step(), nullValue());
    assertThat(sixDays.suggestedPrice(), nullValue());
    assertThat(sixDays.currentPrice(), comparesEqualTo(new BigDecimal("4.00")));
  }

  @Test
  void thePlanShowsTheMarkdownAlreadyOnABatch() {
    prices.put(VARIANT, new BigDecimal("4.00"));
    var b = batch(1, "5");
    batches = Optional.of(List.of(b));
    Markdown existing =
        new Markdown(
            Ids.newId(),
            TENANT,
            STORE,
            VARIANT,
            b.batchId(),
            b.batchNo(),
            b.expiryDate(),
            new BigDecimal("5"),
            "GBP",
            new BigDecimal("4.00"),
            new BigDecimal("2.00"),
            new BigDecimal("50.00"),
            "SHORT_DATED",
            "2100001002007",
            Markdown.STATUS_ACTIVE,
            null,
            Instant.now(),
            null,
            null,
            null,
            BigDecimal.ZERO);
    active = List.of(existing);

    MarkdownService.Plan plan = svc.plan(ctx("MANAGER"), STORE, 7);

    assertThat(plan.suggestions().get(0).existing().id(), is(existing.id()));
  }

  @Test
  void thePlanSaysWhenInventoryCouldNotBeRead() {
    batches = Optional.empty();
    MarkdownService.Plan plan = svc.plan(ctx("MANAGER"), STORE, 7);
    assertThat(plan.inventoryReachable(), is(false));
    assertThat(plan.suggestions().isEmpty(), is(true));
  }

  @Test
  void aVariantWithNoPriceIsListedButNotPriced() {
    batches = Optional.of(List.of(batch(1, "5")));
    var s = svc.plan(ctx("MANAGER"), STORE, 7).suggestions().get(0);
    assertThat(s.currentPrice(), nullValue());
    assertThat(s.suggestedPrice(), nullValue());
    assertThat(
        "the step is still named so the counter knows what it would be",
        s.step().daysToExpiry(),
        is(1));
  }

  @Test
  void theHorizonIsBounded() {
    assertThat(
        assertThrows(ApiException.class, () -> svc.plan(ctx("MANAGER"), STORE, 0)).code(),
        is("PRICING_INVALID_HORIZON"));
    assertThat(
        assertThrows(ApiException.class, () -> svc.plan(ctx("MANAGER"), STORE, 61)).code(),
        is("PRICING_INVALID_HORIZON"));
  }

  @Test
  void aCashierCannotSeeThePlan() {
    assertThat(
        assertThrows(ApiException.class, () -> svc.plan(ctx("CASHIER"), STORE, 7)).code(),
        is("FORBIDDEN"));
  }

  // ── stickering ─────────────────────────────────────────────────────────────

  private CreateMarkdownRequest req(String percent, String price, String reason, LocalDate expiry) {
    return new CreateMarkdownRequest(
        STORE.toString(),
        VARIANT.toString(),
        null,
        "B-1",
        expiry.toString(),
        new BigDecimal("6"),
        percent == null ? null : new BigDecimal(percent),
        price == null ? null : new BigDecimal(price),
        reason);
  }

  @Test
  void aPercentageIsTakenOffTheCurrentPriceAndRoundedHalfUp() {
    prices.put(VARIANT, new BigDecimal("2.99"));
    Markdown m =
        svc.create(ctx("STOREKEEPER"), req("25", null, "short_dated", LocalDate.now().plusDays(2)));
    assertThat(m.originalPrice(), comparesEqualTo(new BigDecimal("2.99")));
    assertThat(m.markdownPrice(), comparesEqualTo(new BigDecimal("2.24")));
    assertThat(m.percentOff(), comparesEqualTo(new BigDecimal("25.00")));
    assertThat(m.reason(), is("SHORT_DATED"));
    assertThat(m.status(), is(Markdown.STATUS_ACTIVE));
    assertThat(created, is(m));
  }

  @Test
  void aPriceDerivesItsPercentage() {
    prices.put(VARIANT, new BigDecimal("4.00"));
    Markdown m = svc.create(ctx("MANAGER"), req(null, "1.00", "CLEARANCE", LocalDate.now()));
    assertThat(m.percentOff(), comparesEqualTo(new BigDecimal("75.00")));
    assertThat(
        "the day itself is still sellable",
        m.effectiveStatus(LocalDate.now()),
        is(Markdown.STATUS_ACTIVE));
  }

  @Test
  void whatIsRefused() {
    prices.put(VARIANT, new BigDecimal("4.00"));
    LocalDate soon = LocalDate.now().plusDays(1);
    assertThat(
        code(() -> svc.create(ctx("MANAGER"), req("25", null, "BORED", soon))),
        is("PRICING_MARKDOWN_REASON_UNKNOWN"));
    assertThat(
        code(() -> svc.create(ctx("MANAGER"), req(null, null, "CLEARANCE", soon))),
        is("PRICING_MARKDOWN_AMOUNT_REQUIRED"));
    assertThat(
        code(() -> svc.create(ctx("MANAGER"), req("25", "1.00", "CLEARANCE", soon))),
        is("PRICING_MARKDOWN_AMOUNT_AMBIGUOUS"));
    assertThat(
        code(() -> svc.create(ctx("MANAGER"), req(null, "4.00", "CLEARANCE", soon))),
        is("PRICING_MARKDOWN_NOT_A_REDUCTION"));
    assertThat(
        code(() -> svc.create(ctx("MANAGER"), req(null, "5.00", "CLEARANCE", soon))),
        is("PRICING_MARKDOWN_NOT_A_REDUCTION"));
    assertThat(
        code(
            () ->
                svc.create(
                    ctx("MANAGER"), req("25", null, "CLEARANCE", LocalDate.now().minusDays(1)))),
        is("PRICING_MARKDOWN_EXPIRED_DATE"));
    assertThat(
        code(() -> svc.create(ctx("CASHIER"), req("25", null, "CLEARANCE", soon))),
        is("FORBIDDEN"));
    prices.clear();
    assertThat(
        code(() -> svc.create(ctx("MANAGER"), req("25", null, "CLEARANCE", soon))),
        is("PRICING_MARKDOWN_NO_PRICE"));
    prices.put(VARIANT, new BigDecimal("1500.00"));
    assertThat(
        code(() -> svc.create(ctx("MANAGER"), req("10", null, "CLEARANCE", soon))),
        is("PRICING_MARKDOWN_LABEL_RANGE"));
  }

  private static String code(Runnable r) {
    return assertThrows(ApiException.class, r::run).code();
  }

  @Test
  void reducedPriceRoundsHalfUp() {
    assertThat(
        MarkdownService.reducedPrice(new BigDecimal("1.99"), new BigDecimal("50"), 2),
        comparesEqualTo(new BigDecimal("1.00")));
    assertThat(
        MarkdownService.reducedPrice(new BigDecimal("0.10"), new BigDecimal("75"), 2),
        comparesEqualTo(new BigDecimal("0.03")));
  }

  @Test
  void aStepOnTheLadderCanBeAnyWholeOrFractionalPercentage() {
    MarkdownStep s = new MarkdownStep(2, new BigDecimal("33.33"));
    assertThat(
        MarkdownService.reducedPrice(new BigDecimal("3.00"), s.percentOff(), 2),
        comparesEqualTo(new BigDecimal("2.00")));
  }

  /** A reduced price is rounded to the currency's own minor units: whole yen, fils for dinars. */
  @Test
  void aReducedPriceIsInTheCurrencysOwnUnits() {
    assertThat(
        MarkdownService.reducedPrice(new BigDecimal("199"), new BigDecimal("50"), 0),
        org.hamcrest.Matchers.is(new BigDecimal("100")));
    assertThat(
        MarkdownService.reducedPrice(new BigDecimal("1.235"), new BigDecimal("30"), 3),
        org.hamcrest.Matchers.is(new BigDecimal("0.865")));
  }
}
