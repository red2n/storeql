package com.storeql.order.service;

import com.storeql.ids.Ids;
import com.storeql.order.client.TenantClient;
import com.storeql.order.client.TenantClient.RatedBand;
import com.storeql.order.client.TenantClient.RatedSegment;
import com.storeql.order.client.TenantClient.RatedSeller;
import com.storeql.order.client.TenantClient.SchemeTerms;
import com.storeql.order.client.TenantClient.SellerDay;
import com.storeql.order.domain.SalesAttribution;
import com.storeql.order.domain.SalesAttribution.SellerChange;
import com.storeql.order.domain.SalesAttribution.Statement;
import com.storeql.order.domain.SalesAttribution.StatementLine;
import com.storeql.order.repo.CommissionRepository;
import com.storeql.service.FxRates;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What a period of attributed sales earned (store operations & workforce).
 *
 * <p>The sales are this service's and the arrangement is tenant-svc's, so the figures go there and
 * the money comes back: no order or return leaves this service, and the commission rule exists
 * once, beside the arrangement the business agreed to.
 *
 * <p>Three rules hold the thing together. A statement covers a period that has <b>finished</b>,
 * because one produced mid-month would be approved, paid, and then contradicted by the rest of the
 * month. It is <b>frozen when approved</b>, so a later refund, a corrected scheme or a
 * re-attributed sale cannot move a figure somebody was paid on; a period that has to change is
 * restated by a new statement that says what it replaced. And when the arrangements cannot be read,
 * it <b>refuses to produce anything</b> rather than a statement of zeros — a zero somebody signs
 * off is worse than an error somebody retries.
 */
@ApplicationScoped
public class CommissionStatementService {

  private static final int MAX_LIMIT = 100;

  @Inject CommissionRepository repo;
  @Inject TenantClient tenants;
  @Inject TenantProfiles profiles;
  @Inject FxRates fx;

  /**
   * Produces a draft for a period.
   *
   * @param storeId one store, or null for the whole business
   * @param currency the currency counted; the business's own when omitted
   * @param supersedes the approved statement this one will replace when approved, or null
   * @throws ApiException 400 on a period that is not one or has not finished; 409 when a statement
   *     already stands for that period and scope and none was named to replace, and {@code
   *     COMMISSION_FX_RATE_MISSING} when a per-unit arrangement pays in a currency the business
   *     keeps no rate for; 503 when the arrangements could not be read, and {@code
   *     COMMISSION_FX_UNAVAILABLE} when a translation was needed and the rates could not be read
   */
  public Statement draft(
      UUID tenantId,
      UUID storeId,
      LocalDate from,
      LocalDate to,
      String currency,
      String note,
      UUID supersedes,
      TenantContext ctx,
      UUID actorId) {
    String problem = SalesAttribution.periodProblem(from, to, LocalDate.now(ZoneOffset.UTC));
    if (problem != null) throw ApiException.badRequest("COMMISSION_PERIOD_INVALID", problem);
    String money = currency(tenantId, currency);

    Optional<Statement> standing = repo.standing(tenantId, storeId, from, to, money);
    if (standing.isPresent() && !standing.get().id().equals(supersedes)) {
      throw ApiException.conflict(
          "COMMISSION_STATEMENT_STANDS",
          "a statement for that period and scope is already approved; restate it by naming it as the"
              + " one this replaces");
    }
    if (supersedes != null) {
      Statement replaced = require(tenantId, supersedes);
      if (!replaced.standing()) {
        throw ApiException.conflict(
            "COMMISSION_STATEMENT_NOT_STANDING",
            "only the approved statement of a period can be restated");
      }
    }

    List<SalesAttribution.SellerDay> days = repo.sellerDays(tenantId, storeId, money, from, to);
    Map<UUID, List<SellerDay>> figures = new LinkedHashMap<>();
    for (SalesAttribution.SellerDay d : days) {
      figures
          .computeIfAbsent(d.sellerUserId(), k -> new ArrayList<>())
          .add(new SellerDay(d.day(), d.net(), d.units()));
    }

    List<RatedSeller> rated =
        figures.isEmpty()
            ? List.of()
            : tenants
                .rateCommission(tenantId, ctx, from, to, figures)
                .orElseThrow(CommissionStatementService::ratesUnavailable);

    UUID id = Ids.newId();
    List<StatementLine> lines = new ArrayList<>();
    int scale = com.storeql.service.Fx.minorUnits(money);
    BigDecimal commission = BigDecimal.ZERO.setScale(scale);
    // A seller's currency is set only when a per-unit arrangement rated some of their days, and
    // names only the last one, so which stretch pays in what is read from the arrangements
    // themselves — and only then: a business on percentages alone makes one call, as before.
    Map<UUID, SchemeTerms> schemes =
        rated.stream().anyMatch(s -> s.currency() != null)
            ? tenants
                .commissionSchemes(tenantId, ctx)
                .orElseThrow(CommissionStatementService::ratesUnavailable)
            : Map.of();
    Translator translator = new Translator(tenantId, money);
    for (RatedSeller seller : rated) {
      for (RatedSegment segment : seller.segments()) {
        String rateCurrency = rateCurrency(segment, schemes);
        if (segment.bands().isEmpty()) {
          // Sales that earned nothing: carried, so the statement's sales add up to the period's.
          lines.add(line(tenantId, id, seller, segment, null, zero(scale, rateCurrency)));
          continue;
        }
        for (RatedBand band : segment.bands()) {
          CommissionMoney.Stated stated = translator.state(band.commission(), rateCurrency, seller);
          // Each line at the statement currency's units, and the total their sum, so the lines of
          // a statement add up to it exactly.
          commission = commission.add(stated.commission());
          lines.add(line(tenantId, id, seller, segment, band, stated));
        }
      }
    }

    Statement statement =
        new Statement(
            id,
            tenantId,
            storeId,
            from,
            to,
            money,
            SalesAttribution.DRAFT,
            SalesAttribution.netSales(days, scale),
            commission,
            blankToNull(note),
            supersedes,
            null,
            Instant.now(),
            actorId,
            null,
            null,
            lines);
    repo.record(statement);
    return require(tenantId, id);
  }

  private static StatementLine line(
      UUID tenantId,
      UUID statementId,
      RatedSeller seller,
      RatedSegment segment,
      RatedBand band,
      CommissionMoney.Stated stated) {
    return new StatementLine(
        Ids.newId(),
        tenantId,
        statementId,
        seller.userId(),
        segment.schemeId(),
        segment.schemeName(),
        segment.from(),
        segment.to(),
        band == null ? null : band.thresholdFrom(),
        band == null ? null : band.rate(),
        band == null ? segment.amount() : band.amountInBand(),
        stated.commission(),
        stated.rateCurrency(),
        stated.ratedCommission());
  }

  private static CommissionMoney.Stated zero(int scale, String rateCurrency) {
    return new CommissionMoney.Stated(BigDecimal.ZERO.setScale(scale), rateCurrency, null);
  }

  /** The per-unit currency a stretch was rated in, or null; unknown arrangements fail closed. */
  private static String rateCurrency(RatedSegment segment, Map<UUID, SchemeTerms> schemes) {
    if (schemes.isEmpty()) return null;
    // Rated under an arrangement tenant-svc's own list does not hold: what it pays in is unknown,
    // and a statement is not drawn up on a guess.
    if (segment.schemeId() != null && !schemes.containsKey(segment.schemeId())) {
      throw ratesUnavailable();
    }
    return CommissionMoney.rateCurrency(segment.schemeId(), schemes);
  }

  private static ApiException ratesUnavailable() {
    return new ApiException(
        503,
        "COMMISSION_RATES_UNAVAILABLE",
        "the commission arrangements could not be read, so nothing was rated; a statement of"
            + " zeros would be signed off and paid",
        List.of());
  }

  /**
   * States each band's commission in the statement's currency: as rated when it is in it already,
   * else translated at the business's own rate, read once per statement.
   *
   * <p>The rule decided (intent/workforce-rules.md, 2026-10-02) is the rate in force on the
   * period's last day, for a draft and for every restatement of the period alike. {@link FxRates}
   * reads only the sheet as it stands today, so this translates at today's rate: the same figure
   * whenever the rate in force today took effect on or before the period's last day, and not the
   * period's when a newer one has taken effect since — until common-service can read a business's
   * rates as they stood on a day.
   */
  private final class Translator {
    private final UUID tenantId;
    private final String statementCurrency;
    private FxRates.Table rates;

    Translator(UUID tenantId, String statementCurrency) {
      this.tenantId = tenantId;
      this.statementCurrency = statementCurrency;
    }

    CommissionMoney.Stated state(BigDecimal rated, String rateCurrency, RatedSeller seller) {
      int scale = com.storeql.service.Fx.minorUnits(statementCurrency);
      if (rateCurrency == null || rateCurrency.equals(statementCurrency)) {
        return new CommissionMoney.Stated(
            rated.setScale(scale, java.math.RoundingMode.HALF_UP), rateCurrency, null);
      }
      if (rates == null) {
        rates =
            fx.table(tenantId)
                .orElseThrow(
                    () ->
                        new ApiException(
                            503,
                            "COMMISSION_FX_UNAVAILABLE",
                            "a per-unit arrangement pays in "
                                + rateCurrency
                                + " and this business's exchange rates could not be read, so it"
                                + " cannot be stated in "
                                + statementCurrency
                                + "; nothing was drafted",
                            List.of()));
      }
      BigDecimal translated =
          CommissionMoney.translate(rated, rateCurrency, statementCurrency, rates)
              .orElseThrow(
                  () ->
                      new ApiException(
                          409,
                          "COMMISSION_FX_RATE_MISSING",
                          "a per-unit arrangement pays in "
                              + rateCurrency
                              + " and this business keeps no rate to state it in "
                              + statementCurrency
                              + "; add the rate (exchange rates, in the business settings) and"
                              + " draft the statement again",
                          List.of("sellerUserId: " + seller.userId())));
      return new CommissionMoney.Stated(translated, rateCurrency, rated);
    }
  }

  /**
   * Approves a draft, which freezes it, and closes the statement it replaces.
   *
   * @throws ApiException 404 when there is no such statement; 409 when it is not a draft, or
   *     another statement already stands for its period
   */
  public Statement approve(UUID tenantId, UUID id, UUID actorId) {
    Statement draft = require(tenantId, id);
    if (!SalesAttribution.DRAFT.equals(draft.status())) {
      throw ApiException.conflict(
          "COMMISSION_STATEMENT_NOT_DRAFT",
          "that statement is " + draft.status().toLowerCase(Locale.ROOT) + " already");
    }
    if (!repo.approve(tenantId, id, actorId, draft.supersedes())) {
      throw ApiException.conflict(
          "COMMISSION_STATEMENT_NOT_DRAFT", "that statement was not a draft any more");
    }
    return require(tenantId, id);
  }

  /**
   * Throws away a draft nobody approved.
   *
   * @throws ApiException 409 on anything that is not a draft — an approved statement is a record,
   *     and records are superseded rather than deleted
   */
  public void discard(UUID tenantId, UUID id) {
    Statement statement = require(tenantId, id);
    if (!SalesAttribution.DRAFT.equals(statement.status())) {
      throw ApiException.conflict(
          "COMMISSION_STATEMENT_NOT_DRAFT",
          "only a draft is thrown away; an approved statement is restated");
    }
    repo.discard(tenantId, id);
  }

  public Statement statement(UUID tenantId, UUID id) {
    return require(tenantId, id);
  }

  public List<Statement> statements(UUID tenantId, UUID storeId, String status, Integer limit) {
    int clamped = limit == null ? 20 : Math.max(1, Math.min(MAX_LIMIT, limit));
    String wanted =
        status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
    if (wanted != null
        && !List.of(SalesAttribution.DRAFT, SalesAttribution.APPROVED, SalesAttribution.SUPERSEDED)
            .contains(wanted)) {
      throw ApiException.badRequest(
          "COMMISSION_STATEMENT_STATUS_UNKNOWN", "a statement is DRAFT, APPROVED or SUPERSEDED");
    }
    return repo.statements(tenantId, storeId, wanted, clamped);
  }

  // ── who a sale is credited to ───────────────────────────────────────────────

  /**
   * Credits a sale to somebody, or to nobody.
   *
   * <p>Recorded rather than overwritten, because money follows attribution: who changed it, when
   * and why has to survive the change. A statement already approved does not move — it is frozen —
   * so a period that was paid on the old attribution has to be restated for the change to reach
   * anybody's pay, which is a decision somebody takes deliberately.
   *
   * @param sellerUserId null credits the sale to nobody
   * @throws ApiException 400 without a reason; 404 when the order is not this business's
   */
  public SellerChange credit(
      UUID tenantId, UUID orderId, UUID sellerUserId, String reason, UUID actorId) {
    String why = blankToNull(reason);
    if (why == null) {
      throw ApiException.badRequest(
          "COMMISSION_REASON_REQUIRED",
          "say why the sale is being credited to somebody else; commission follows it");
    }
    var credited =
        repo.sellerOf(tenantId, orderId)
            .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "no such order"));
    SellerChange change =
        new SellerChange(
            Ids.newId(),
            tenantId,
            orderId,
            credited.sellerUserId(),
            sellerUserId,
            why,
            Instant.now(),
            actorId);
    repo.credit(tenantId, orderId, sellerUserId, change);
    return change;
  }

  public List<SellerChange> changes(UUID tenantId, UUID orderId) {
    if (repo.sellerOf(tenantId, orderId).isEmpty()) {
      throw ApiException.notFound("ORDER_NOT_FOUND", "no such order");
    }
    return repo.changes(tenantId, orderId);
  }

  private Statement require(UUID tenantId, UUID id) {
    return repo.statement(tenantId, id)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "COMMISSION_STATEMENT_NOT_FOUND", "no such commission statement"));
  }

  private String currency(UUID tenantId, String asked) {
    String given = blankToNull(asked);
    if (given != null) return given.toUpperCase(Locale.ROOT);
    return profiles.currencyOr(tenantId, null) == null
        ? failCurrency()
        : profiles.currencyOr(tenantId, null).toUpperCase(Locale.ROOT);
  }

  private static String failCurrency() {
    throw ApiException.conflict(
        "COMMISSION_CURRENCY_UNKNOWN",
        "this business's own currency could not be read, so say which currency to count");
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
