package com.storeql.service;

import com.storeql.service.Fx.Converted;
import com.storeql.service.Fx.Rate;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A business's exchange rates as tenant-svc, which owns them, answers {@code GET
 * /admin/tenant/fx-rates} (03.x): the home currency and, per other currency, the home units one
 * unit buys. Cached a minute, like the plan's allowances. A rate that cannot be read is no rate:
 * the caller decides what that means — a price is shown in the home currency, a spend ceiling fails
 * closed.
 */
@ApplicationScoped
public class FxRates {

  static final Duration TTL = Duration.ofMinutes(1);
  private static final String PATH = "/admin/tenant/fx-rates";

  /** The home currency and the rates the business keeps. */
  public record Table(String home, Map<String, Rate> rates) {
    public Table {
      rates = Map.copyOf(rates);
    }
  }

  private record Cached(Table table, Instant expiresAt) {}

  @Inject ServiceSettings settings;

  @Inject
  @ConfigProperty(name = "storeql.clients.tenant-svc.url")
  Optional<String> tenantSvcUrl;

  private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
  private Clock clock = Clock.systemUTC();
  private Function<UUID, Optional<String>> fetch;

  @PostConstruct
  void init() {
    ServiceReader client = ServiceReader.tenantSvc(settings, tenantSvcUrl);
    fetch = tenantId -> client.body(tenantId, PATH, Map.of());
  }

  /** For tests: an answer standing in for tenant-svc, and a clock to age the cache with. */
  public static FxRates forTest(Function<UUID, Optional<String>> fetch, Clock clock) {
    FxRates r = new FxRates();
    r.fetch = fetch;
    r.clock = clock;
    return r;
  }

  /** The business's rate table, or empty when tenant-svc could not be asked. */
  public Optional<Table> table(UUID tenantId) {
    if (tenantId == null) return Optional.empty();
    Instant now = clock.instant();
    Cached hit = cache.get(tenantId);
    if (hit != null && hit.expiresAt().isAfter(now)) return Optional.of(hit.table());
    Table read = fetch.apply(tenantId).flatMap(FxRates::parse).orElse(null);
    if (read == null) {
      // Not cached: a failed read must not hold a business rateless for a whole TTL.
      return Optional.empty();
    }
    cache.put(tenantId, new Cached(read, now.plus(TTL)));
    return Optional.of(read);
  }

  /** The home currency, when known. */
  public Optional<String> home(UUID tenantId) {
    return table(tenantId).map(Table::home);
  }

  /** The currencies a business can show or take figures in: home first, then those with a rate. */
  public List<String> currencies(UUID tenantId) {
    return table(tenantId)
        .map(
            t -> {
              List<String> out = new java.util.ArrayList<>();
              out.add(t.home());
              t.rates().keySet().stream().sorted().forEach(out::add);
              return List.copyOf(out);
            })
        .orElse(List.of());
  }

  /** The rate for a currency: home units per one unit of it; empty for the home currency too. */
  public Optional<Rate> rate(UUID tenantId, String currency) {
    String code = Fx.upper(currency);
    return table(tenantId).map(t -> t.rates().get(code));
  }

  /** A foreign amount in the home currency, with the rate; empty without a rate. */
  public Optional<Converted> toHome(UUID tenantId, BigDecimal amount, String currency) {
    Optional<Table> t = table(tenantId);
    if (t.isEmpty() || amount == null) return Optional.empty();
    String code = Fx.upper(currency);
    if (code.equals(t.get().home())) {
      return Optional.of(new Converted(amount, code, BigDecimal.ONE));
    }
    Rate r = t.get().rates().get(code);
    if (r == null) return Optional.empty();
    return Optional.of(
        new Converted(Fx.toHome(amount, r.rate(), t.get().home()), t.get().home(), r.rate()));
  }

  /** A home amount shown in another currency, with the rate; empty without a rate. */
  public Optional<Converted> fromHome(UUID tenantId, BigDecimal homeAmount, String currency) {
    Optional<Table> t = table(tenantId);
    if (t.isEmpty() || homeAmount == null) return Optional.empty();
    String code = Fx.upper(currency);
    if (code.equals(t.get().home())) {
      return Optional.of(new Converted(homeAmount, code, BigDecimal.ONE));
    }
    Rate r = t.get().rates().get(code);
    if (r == null) return Optional.empty();
    return Optional.of(new Converted(Fx.fromHome(homeAmount, r.rate(), code), code, r.rate()));
  }

  static Optional<Table> parse(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      if (!root.containsKey("data") || root.isNull("data")) return Optional.empty();
      JsonObject data = root.getJsonObject("data");
      String home = data.getString("home", null);
      if (home == null || home.isBlank()) return Optional.empty();
      Map<String, Rate> rates = new HashMap<>();
      if (data.containsKey("rates") && !data.isNull("rates")) {
        for (JsonValue value : data.getJsonArray("rates")) {
          JsonObject r = value.asJsonObject();
          String currency = r.getString("currency", null);
          if (currency == null || !r.containsKey("rate") || r.isNull("rate")) continue;
          String code = currency.trim().toUpperCase(Locale.ROOT);
          LocalDate from =
              r.containsKey("effectiveFrom") && !r.isNull("effectiveFrom")
                  ? LocalDate.parse(r.getString("effectiveFrom"))
                  : null;
          rates.put(code, new Rate(code, r.getJsonNumber("rate").bigDecimalValue(), from));
        }
      }
      return Optional.of(new Table(home.trim().toUpperCase(Locale.ROOT), rates));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
