package com.storeql.tenant.service;

import com.storeql.ids.Ids;
import com.storeql.service.Fx;
import com.storeql.tenant.domain.Commission;
import com.storeql.tenant.domain.Commission.Assignment;
import com.storeql.tenant.domain.Commission.Band;
import com.storeql.tenant.domain.Commission.Day;
import com.storeql.tenant.domain.Commission.Scheme;
import com.storeql.tenant.domain.Commission.Segment;
import com.storeql.tenant.repo.CommissionRepository;
import com.storeql.tenant.repo.TenantRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Commission arrangements, and the rating of sales under them (store operations & workforce).
 *
 * <p>The arithmetic lives here rather than in the service that holds the sales, because it belongs
 * to the arrangement: order-svc sends what each person sold, day by day, and gets back what that
 * earns. Two implementations of a commission rule would eventually disagree, and the one a person
 * is paid on must be the one the business agreed.
 *
 * <p>Nothing here is ever edited. A scheme is corrected by writing a new version and moving whoever
 * is on the old one across from a chosen day, so a month already paid cannot be re-rated.
 */
@ApplicationScoped
public class CommissionService {

  /** How many day-rows one rating call will take: a month for a large shop, not a decade. */
  static final int MAX_DAY_ROWS = 10_000;

  @Inject CommissionRepository repo;
  @Inject TenantRepository tenants;

  /** What one person sold, day by day, as the service holding the sales reports it. */
  public record SellerDays(UUID userId, List<Day> days) {

    public SellerDays {
      days = days == null ? List.of() : List.copyOf(days);
    }
  }

  /** What one person's days earned, arrangement by arrangement. */
  public record Rated(UUID userId, List<Segment> segments, BigDecimal commission, String currency) {

    public Rated {
      segments = segments == null ? List.of() : List.copyOf(segments);
    }
  }

  // ── the arrangements ────────────────────────────────────────────────────────

  /**
   * Records a scheme with its rate bands.
   *
   * @param bands threshold and rate in pairs, the first threshold zero
   * @throws ApiException 400 when the scheme could not be paid on: an unknown basis, a per-unit
   *     scheme with no currency, no bands, or bands that leave a rate undecidable
   */
  public Scheme create(
      UUID tenantId,
      String name,
      String basis,
      String currency,
      List<Band> bands,
      String note,
      UUID actorId) {
    return write(tenantId, name, basis, currency, bands, note, actorId, null, null);
  }

  /**
   * Corrects a scheme: a new version, and whoever is on the old one moves across from a day.
   *
   * <p>The old version stays exactly as it was, because commission earned under it was earned under
   * its rates. Moving the people is what makes the correction take effect at all — and it is dated,
   * so the days before it keep the arrangement they were sold under.
   *
   * @param effectiveFrom the day the new rates apply from; null means today
   * @throws ApiException 404 when there is no such scheme, 409 when it was already superseded
   */
  public Scheme correct(
      UUID tenantId,
      UUID schemeId,
      String name,
      String basis,
      String currency,
      List<Band> bands,
      String note,
      LocalDate effectiveFrom,
      UUID actorId) {
    Scheme old = require(tenantId, schemeId);
    if (old.supersededBy() != null) {
      throw ApiException.conflict(
          "COMMISSION_SCHEME_SUPERSEDED",
          "that version was already replaced; correct the one that replaced it");
    }
    LocalDate from =
        effectiveFrom == null ? LocalDate.now(java.time.ZoneOffset.UTC) : effectiveFrom;
    return write(tenantId, name, basis, currency, bands, note, actorId, schemeId, from);
  }

  private Scheme write(
      UUID tenantId,
      String name,
      String basis,
      String currency,
      List<Band> bands,
      String note,
      UUID actorId,
      UUID supersedes,
      LocalDate moveFrom) {
    String schemeName = require(name, "COMMISSION_NAME_REQUIRED", "a scheme needs a name");
    String upperBasis = basis == null ? null : basis.strip().toUpperCase(Locale.ROOT);
    String upperCurrency =
        currency == null || currency.isBlank() ? null : currency.strip().toUpperCase(Locale.ROOT);
    if (upperCurrency != null && !Fx.isCurrency(upperCurrency)) {
      throw ApiException.badRequest(
          "COMMISSION_SCHEME_INVALID",
          "a per-unit amount is in an ISO 4217 currency such as EUR or JPY: "
              + upperCurrency
              + " is not one");
    }
    List<BigDecimal> thresholds =
        bands == null ? List.of() : bands.stream().map(Band::thresholdFrom).toList();
    String problem =
        Commission.problem(
            upperBasis, upperCurrency, thresholds, Fx.minorUnits(homeCurrency(tenantId)));
    if (problem != null) throw ApiException.badRequest("COMMISSION_SCHEME_INVALID", problem);

    UUID id = Ids.newId();
    List<Band> withIds =
        bands.stream().map(b -> new Band(Ids.newId(), id, b.thresholdFrom(), b.rate())).toList();
    Scheme scheme =
        new Scheme(
            id,
            tenantId,
            schemeName,
            upperBasis,
            upperCurrency,
            Commission.ACTIVE,
            blankToNull(note),
            supersedes,
            null,
            Instant.now(),
            actorId,
            withIds);
    repo.create(scheme, supersedes);
    if (supersedes != null) moveAssignees(tenantId, supersedes, id, moveFrom, actorId);
    return atScale(tenantId, repo.scheme(tenantId, id).orElse(scheme));
  }

  /** Moves everybody currently on the old version onto the new one, from a day. */
  private void moveAssignees(UUID tenantId, UUID from, UUID to, LocalDate on, UUID actorId) {
    Map<UUID, UUID> current = repo.schemesOn(tenantId, on);
    for (Map.Entry<UUID, UUID> e : current.entrySet()) {
      if (!from.equals(e.getValue())) continue;
      try {
        repo.assign(
            new Assignment(
                Ids.newId(),
                tenantId,
                e.getKey(),
                to,
                on,
                "moved to the corrected scheme",
                Instant.now(),
                actorId));
      } catch (ApiException existing) {
        // Somebody already has an arrangement written for that very day: theirs is the deliberate
        // one, and a correction must not overwrite a decision somebody took by hand.
        if (!"COMMISSION_ARRANGEMENT_EXISTS".equals(existing.code())) throw existing;
      }
    }
  }

  /**
   * Withdraws a scheme, so nobody new goes on it.
   *
   * @throws ApiException 409 while anybody is still on it — a shop that thought commission had
   *     stopped and found it had not is the reason this refuses rather than quietly leaving them
   */
  public Scheme withdraw(UUID tenantId, UUID schemeId) {
    Scheme scheme = require(tenantId, schemeId);
    long on =
        repo.schemesOn(tenantId, LocalDate.now(java.time.ZoneOffset.UTC)).values().stream()
            .filter(schemeId::equals)
            .count();
    if (on > 0) {
      throw ApiException.conflict(
          "COMMISSION_SCHEME_IN_USE",
          on
              + " member(s) of staff are still on that scheme; take them off it first, so nobody is"
              + " left earning under a scheme the shop thinks it has stopped");
    }
    if (!scheme.withdrawn()) repo.withdraw(tenantId, schemeId);
    return atScale(tenantId, repo.scheme(tenantId, schemeId).orElse(scheme));
  }

  public List<Scheme> schemes(UUID tenantId, boolean activeOnly) {
    int units = Fx.minorUnits(homeCurrency(tenantId));
    return repo.schemes(tenantId, activeOnly).stream()
        .map(s -> Commission.atScale(s, units))
        .toList();
  }

  public Scheme scheme(UUID tenantId, UUID schemeId) {
    return atScale(tenantId, require(tenantId, schemeId));
  }

  /**
   * A scheme as it is read back: each band's threshold at its own scale ({@link
   * Commission#atScale}).
   */
  private Scheme atScale(UUID tenantId, Scheme scheme) {
    return Commission.atScale(scheme, Fx.minorUnits(homeCurrency(tenantId)));
  }

  /**
   * Puts somebody on a scheme from a day, or takes them off it.
   *
   * @param schemeId null ends the arrangement from that day
   * @throws ApiException 404 on an unknown scheme, 409 when the person is not staff of this
   *     business or an arrangement is already written for that day
   */
  public Assignment assign(
      UUID tenantId,
      UUID userId,
      UUID schemeId,
      LocalDate effectiveFrom,
      String note,
      UUID actorId) {
    if (!repo.isStaff(tenantId, userId)) {
      throw ApiException.conflict(
          "COMMISSION_NOT_STAFF",
          "that person is not on this business's staff; assign them to a store first");
    }
    if (schemeId != null) {
      Scheme scheme = require(tenantId, schemeId);
      if (!scheme.current()) {
        throw ApiException.conflict(
            "COMMISSION_SCHEME_NOT_CURRENT",
            "that scheme was withdrawn or replaced; put them on the one in force");
      }
    }
    LocalDate from =
        effectiveFrom == null ? LocalDate.now(java.time.ZoneOffset.UTC) : effectiveFrom;
    return repo.assign(
        new Assignment(
            Ids.newId(),
            tenantId,
            userId,
            schemeId,
            from,
            blankToNull(note),
            Instant.now(),
            actorId));
  }

  public List<Assignment> assignments(UUID tenantId, UUID userId) {
    return repo.assignments(tenantId, userId);
  }

  // ── rating what was sold ────────────────────────────────────────────────────

  /**
   * What each person's days earn under the arrangements in force on them.
   *
   * <p>Called by the service that holds the sales. It sends figures, never rows: a period's every
   * sale and return stays in the service that owns it, and what comes back is the money.
   *
   * @throws ApiException 400 on a period that is not one, or more day-rows than one call will take
   */
  public List<Rated> rate(UUID tenantId, LocalDate from, LocalDate to, List<SellerDays> sellers) {
    requirePeriod(from, to, sellers);
    List<SellerDays> asked = sellers == null ? List.of() : sellers;
    String home = homeCurrency(tenantId);
    Map<UUID, List<Assignment>> timelines = repo.assignmentsUpTo(tenantId, to);
    Map<UUID, Scheme> schemes = new LinkedHashMap<>();
    for (Scheme s : repo.schemes(tenantId, false)) schemes.put(s.id(), s);

    List<Rated> rated = new ArrayList<>(asked.size());
    for (SellerDays seller : asked) {
      List<Day> within =
          seller.days().stream()
              .filter(d -> d.day() != null && !d.day().isBefore(from) && !d.day().isAfter(to))
              .toList();
      List<Segment> segments =
          Commission.rate(
              within,
              timelines.getOrDefault(seller.userId(), List.of()),
              schemes,
              home,
              Fx::minorUnits);
      BigDecimal total = BigDecimal.ZERO;
      String currency = null;
      for (Segment s : segments) {
        total = total.add(s.commission());
        Scheme scheme = s.schemeId() == null ? null : schemes.get(s.schemeId());
        if (scheme != null && scheme.currency() != null) currency = scheme.currency();
      }
      // The total at the minor units of the currency it is named in: a per-unit scheme's, else the
      // business's own (what a percentage of its sales is paid in) — never an assumed two places.
      total =
          total.setScale(Fx.minorUnits(currency == null ? home : currency), RoundingMode.HALF_UP);
      rated.add(new Rated(seller.userId(), segments, total, currency));
    }
    return List.copyOf(rated);
  }

  /**
   * What a rating call must be before anything else is asked of it — even whose sales they are: a
   * period that runs forwards, and no more day-rows than one call takes. The resource judges this
   * first, so a malformed request is told so (400) rather than which sellers it may not name.
   *
   * @throws ApiException 400 {@code COMMISSION_PERIOD_INVALID} for a period missing an end or
   *     running backwards; 400 {@code COMMISSION_PERIOD_TOO_LARGE} beyond {@value #MAX_DAY_ROWS}
   *     day-rows
   */
  public void requirePeriod(LocalDate from, LocalDate to, List<SellerDays> sellers) {
    if (from == null || to == null || to.isBefore(from)) {
      throw ApiException.badRequest(
          "COMMISSION_PERIOD_INVALID", "a period ends on or after it starts");
    }
    int rows = sellers == null ? 0 : sellers.stream().mapToInt(s -> s.days().size()).sum();
    if (rows > MAX_DAY_ROWS) {
      throw ApiException.badRequest(
          "COMMISSION_PERIOD_TOO_LARGE",
          "one rating call takes at most " + MAX_DAY_ROWS + " day-rows; ask for a shorter period");
    }
  }

  /**
   * The business's own currency, read from tenant-svc's own record of it: what its sales — and so a
   * percentage of them — are counted in. Null when it has none recorded, which {@link
   * Fx#minorUnits} reads as two places, its stated fallback.
   */
  private String homeCurrency(UUID tenantId) {
    return tenants.findTenant(tenantId).map(t -> t.currency()).orElse(null);
  }

  private Scheme require(UUID tenantId, UUID schemeId) {
    return repo.scheme(tenantId, schemeId)
        .orElseThrow(
            () ->
                ApiException.notFound("COMMISSION_SCHEME_NOT_FOUND", "no such commission scheme"));
  }

  private static String require(String value, String code, String message) {
    String v = blankToNull(value);
    if (v == null) throw ApiException.badRequest(code, message);
    return v;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
