package com.storeql.purchase.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.purchase.config.Jsons;
import com.storeql.purchase.config.ServiceConfig;
import com.storeql.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * Reads the tenant's VAT rate table from pricing-svc, which owns it (golden rule #1).
 *
 * <p>Needed because a purchase order line carries a {@code vat_code} — {@code T1}, {@code T0} — and
 * nothing but the rate behind that code turns an order's net value into the gross the supplier will
 * actually invoice. purchase-svc cannot read {@code vat_rates} itself; it is another service's
 * table.
 *
 * <p>The whole table is fetched in one call rather than a lookup per code. A tenant has a handful
 * of VAT codes, an order has a handful of lines, and one round trip that returns everything is
 * cheaper and less racy than several that each return one row.
 */
@ApplicationScoped
public class PricingClient {

  private static final Logger LOG = System.getLogger(PricingClient.class.getName());
  private static final String PRICING_SERVICE = "pricing-svc";

  /**
   * {@code GET /vat-rates} requires a staff role — it is one of the 24 reads SJ-D11 closed. Stamped
   * explicitly rather than relying on an identity-less bypass, which SJ-D13 removed.
   */
  private static final String INTERNAL_ROLE = "STOREKEEPER";

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient webClient;

  @PostConstruct
  void init() {
    registry = new ConsulClient(config.consulHost(), config.consulPort());
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(5))
            .build();
  }

  /**
   * The tenant's VAT codes and their rates, as a fraction: {@code {"T1": 0.20, "T0": 0.00}}.
   *
   * <p><b>An empty map is a legitimate answer, not a failure.</b> VAT rates are tenant
   * configuration that nothing seeds at onboarding, so a new tenant genuinely has none — and so
   * does a business that is not VAT-registered at all, or one trading somewhere VAT does not apply.
   * Every one of those means the same thing for a purchase order: no VAT, gross equals net.
   * Treating "no rates configured" as an error would make it impossible to raise a purchase order
   * on a fresh tenant.
   *
   * <p>The same map is returned when pricing-svc cannot be reached, and the caller cannot tell the
   * two apart — see {@code PurchaseService.addPurchaseOrderLine} for why that is handled at the
   * call site rather than here.
   *
   * @param tenantId the tenant whose VAT table is wanted
   * @return code to rate, upper-cased keys; empty when there are none or pricing-svc is unreachable
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public Map<String, BigDecimal> findVatRates(UUID tenantId) {
    ServiceInstance instance = registry.resolve(PRICING_SERVICE).orElse(null);
    if (instance == null) {
      LOG.log(Level.WARNING, "pricing-svc not in discovery — VAT cannot be resolved");
      return Map.of();
    }
    try (HttpClientResponse res =
        webClient
            .get(instance.baseUri() + "/vat-rates")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
            .request()) {
      int status = res.status().code();
      String body = res.as(String.class);
      if (status != 200) {
        LOG.log(Level.WARNING, "VAT rate lookup HTTP {0}: {1}", status, body);
        return Map.of();
      }
      return parseRates(body);
    } catch (CircuitBreakerOpenException e) {
      LOG.log(Level.WARNING, "pricing-svc circuit open — VAT cannot be resolved");
      return Map.of();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "VAT rate lookup failed: {0}", e.getMessage());
      return Map.of();
    }
  }

  /**
   * Extracted so it is assertable without a network, for the reason SJ-D14 and SJ-D20 both
   * established: a cross-service payload parsed inline behind discovery and a breaker is a payload
   * no test can reach.
   *
   * <p>An {@code exempt} code is rated zero regardless of what {@code rate} says. The two fields
   * can disagree — the schema constrains neither against the other — and exemption is the stronger
   * statement.
   *
   * @param body the raw {@code GET /vat-rates} response
   * @return code to rate; empty rather than throwing if the payload is not the expected shape
   */
  static Map<String, BigDecimal> parseRates(String body) {
    try (JsonReader reader = Jsons.PROVIDER.createReader(new StringReader(body))) {
      JsonArray data = reader.readObject().getJsonArray("data");
      if (data == null) return Map.of();
      Map<String, BigDecimal> rates = new HashMap<>();
      for (JsonValue v : data) {
        if (v.getValueType() != JsonValue.ValueType.OBJECT) continue;
        JsonObject o = v.asJsonObject();
        String code = o.getString("code", null);
        if (code == null || code.isBlank()) continue;
        boolean exempt = o.getBoolean("exempt", false);
        BigDecimal rate = BigDecimal.ZERO;
        if (!exempt && o.containsKey("rate") && !o.isNull("rate")) {
          rate = o.getJsonNumber("rate").bigDecimalValue();
        }
        rates.put(code.trim().toUpperCase(Locale.ROOT), rate);
      }
      return Collections.unmodifiableMap(rates);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "malformed VAT rate payload: {0}", e.getMessage());
      return Map.of();
    }
  }
}
