package com.storeql.purchase.config;

import com.storeql.purchase.domain.Money;
import com.storeql.purchase.domain.ThreeWayMatch;
import com.storeql.service.BaseServiceConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Typed config for purchase-svc — extends {@link BaseServiceConfig} for the 9 common properties.
 *
 * <p>Adds the purchasing-specific settings: the fallback currency, the per-currency/per-role spend
 * authority that gates purchase-order submission, and the three-way match tolerances.
 */
@ApplicationScoped
public class ServiceConfig extends BaseServiceConfig {

  @Inject
  @ConfigProperty(name = "server.port", defaultValue = "8009")
  int servicePort;

  /**
   * {@inheritDoc}
   *
   * @return always {@code purchase-svc}
   */
  @Override
  public String serviceName() {
    return "purchase-svc";
  }

  /**
   * {@inheritDoc}
   *
   * @return the HTTP listen port; the {@code 8009} default is a local-dev convenience only, as
   *     every service listens on 8080 in production
   */
  @Override
  public int servicePort() {
    return servicePort;
  }

  /**
   * Per-currency, per-role spend authority for submitting a purchase order, as {@code
   * CURRENCY:ROLE:amount} entries. {@code UNLIMITED} is accepted in place of an amount.
   *
   * <p><b>Currency is part of the key, and that is the whole design.</b> A ceiling of 5000 means
   * nothing on its own: against JPY it is roughly £25, against KWD roughly £13,000. A
   * single-currency ceiling silently applied to another currency is SJ-D18's inverted sign with
   * money attached: it either blocks every Japanese order or waves through 160 times the intended
   * authority, and which one you get depends on nothing but where the tenant happens to buy.
   *
   * <p>Empty by default, which leaves approval <b>off</b> and submission behaving exactly as it did
   * before this feature — a deliberate choice, because turning mandatory approval on for every
   * existing tenant is not a change to make silently in a config default.
   *
   * <p>Once anything is configured, a currency with no entries requires approval rather than
   * granting unlimited authority. Otherwise enabling limits for sterling would leave a
   * currency-shaped hole: the first order from an overseas supplier would sail through precisely
   * because nobody had thought about it.
   */
  // Optional rather than defaultValue="": MicroProfile Config treats an empty default as "no
  // value at all" and fails the injection point at boot, so a service with approval switched off
  // would not start. Found by running both integration suites in one JVM, which is the only
  // configuration in which the unconfigured case is exercised.
  @Inject
  @ConfigProperty(name = "storeql.purchase.approval.limits")
  Optional<String> approvalLimitsCfg;

  private Map<String, Map<String, BigDecimal>> approvalLimits = Map.of();

  /**
   * Spend authority as currency to role to ceiling. A {@code null} ceiling for a role that is
   * present means unlimited.
   *
   * @return an immutable view; empty when approval is switched off
   */
  public Map<String, Map<String, BigDecimal>> approvalLimits() {
    return approvalLimits;
  }

  /** Whether any spend authority is configured at all. When false, submission is never routed. */
  /**
   * Whether any spend authority is configured.
   *
   * @return {@code false} when the limits table is empty, in which case submission is never routed
   *     for approval
   */
  public boolean approvalEnabled() {
    return !approvalLimits.isEmpty();
  }

  /** Marks an entry configured as {@code UNLIMITED}; kept out of the map's value space. */
  public static final BigDecimal UNLIMITED = null;

  /**
   * Parses the limit table once at startup, failing the boot on anything malformed.
   *
   * <p>Fails loudly rather than dropping the entry, on the SJ-D6 precedent: a silently-dropped role
   * quietly loses its authority, and the symptom is a confusing 403 in a buyer's face rather than a
   * configuration error where someone can act on it. A silently-dropped <em>currency</em> is worse
   * still, because under the fail-closed rule above it sends every order in that currency for
   * approval that no one has the authority to give.
   */
  @PostConstruct
  void parseApprovalLimits() {
    Map<String, Map<String, BigDecimal>> parsed = new LinkedHashMap<>();
    for (String entry : approvalLimitsCfg.orElse("").split(",")) {
      String e = entry.trim();
      if (e.isEmpty()) continue;
      String[] parts = e.split(":");
      if (parts.length != 3)
        throw new IllegalStateException(
            "storeql.purchase.approval.limits entry is not CURRENCY:ROLE:amount — got: " + e);
      String currency = parts[0].trim().toUpperCase(Locale.ROOT);
      if (!Money.isIso4217(currency))
        throw new IllegalStateException(
            "storeql.purchase.approval.limits names a currency that is not ISO 4217: " + currency);
      String role = parts[1].trim().toUpperCase(Locale.ROOT);
      String amount = parts[2].trim();
      BigDecimal ceiling;
      if ("UNLIMITED".equalsIgnoreCase(amount)) {
        ceiling = UNLIMITED;
      } else {
        try {
          ceiling = new BigDecimal(amount);
        } catch (NumberFormatException nfe) {
          throw new IllegalStateException(
              "storeql.purchase.approval.limits has a non-numeric ceiling for "
                  + currency
                  + ":"
                  + role,
              nfe);
        }
        if (ceiling.signum() < 0)
          throw new IllegalStateException(
              "storeql.purchase.approval.limits ceiling for "
                  + currency
                  + ":"
                  + role
                  + " is negative — got: "
                  + ceiling);
      }
      Map<String, BigDecimal> byRole = parsed.computeIfAbsent(currency, k -> new LinkedHashMap<>());
      // A HashMap cannot distinguish "absent" from "present and null", and UNLIMITED is null, so
      // membership is what the lookup tests. Duplicate keys would make that ambiguous.
      if (byRole.containsKey(role))
        throw new IllegalStateException(
            "storeql.purchase.approval.limits names " + currency + ":" + role + " twice");
      byRole.put(role, ceiling);
    }
    Map<String, Map<String, BigDecimal>> immutable = new LinkedHashMap<>();
    parsed.forEach(
        (currency, byRole) -> immutable.put(currency, Collections.unmodifiableMap(byRole)));
    approvalLimits = Collections.unmodifiableMap(immutable);
  }

  /**
   * Accepted per-unit price difference on a three-way match, as a percentage of the ordered price.
   *
   * <p>Zero by default, which surfaces any difference at all. Safe as a default precisely because
   * flagging does not block: a buyer sees everything until someone decides what the business
   * actually tolerates, rather than variance being quietly accepted by a number nobody chose.
   * Setting a band is a procurement policy — the same line partial receipt drew about over-receipt.
   */
  @Inject
  @ConfigProperty(name = "storeql.purchase.match.tolerance.price-percent", defaultValue = "0")
  BigDecimal matchPricePercent;

  /** How far below the order a price may be; defaults to the upper band, i.e. symmetric. */
  @Inject
  @ConfigProperty(name = "storeql.purchase.match.tolerance.price-lower-percent")
  Optional<BigDecimal> matchPriceLowerPercent;

  /** The most a unit price may differ from the order's in money; unset for no absolute limit. */
  @Inject
  @ConfigProperty(name = "storeql.purchase.match.tolerance.price-absolute")
  Optional<BigDecimal> matchPriceAbsolute;

  @Inject
  @ConfigProperty(name = "storeql.purchase.match.tolerance.qty-percent", defaultValue = "0")
  BigDecimal matchQtyPercent;

  /** The most units a line may bill above what was received; unset for no absolute limit. */
  @Inject
  @ConfigProperty(name = "storeql.purchase.match.tolerance.qty-absolute")
  Optional<BigDecimal> matchQtyAbsolute;

  /** How far the supplier's stated total may differ from their own lines plus VAT. */
  @Inject
  @ConfigProperty(name = "storeql.purchase.match.tolerance.total-absolute", defaultValue = "0")
  BigDecimal matchTotalAbsolute;

  /**
   * The match tolerance this deployment runs with. Every band defaults to exact, which surfaces
   * every difference; widening one is a procurement policy, made in configuration.
   */
  public ThreeWayMatch.Tolerance matchTolerance() {
    return new ThreeWayMatch.Tolerance(
        matchPricePercent,
        matchPriceLowerPercent.orElse(matchPricePercent),
        matchPriceAbsolute.orElse(null),
        matchQtyPercent,
        matchQtyAbsolute.orElse(null),
        matchTotalAbsolute);
  }

  /**
   * {@inheritDoc}
   *
   * @return always {@code purchase}, the Postgres schema this service owns
   */
  @Override
  public String dbSchema() {
    return "purchase";
  }
}
