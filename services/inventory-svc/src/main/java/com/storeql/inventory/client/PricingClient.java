package com.storeql.inventory.client;

import com.storeql.ids.Ids;
import com.storeql.inventory.config.ServiceConfig;
import com.storeql.inventory.domain.Promotions.PromotionWindow;
import com.storeql.service.ServiceReader;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Asks pricing-svc, which owns promotions (golden rule #1), which promotions touched a store and
 * when, so the forecast can take a promotion's days out of the history and put its lift back into
 * the days one will run on (06.x).
 *
 * <p>Advisory: an empty answer means pricing-svc could not be asked, and the forecast runs with no
 * promotions in it, saying so in the log. Through the shared reader, so a test or a deployment
 * without discovery can name the address ({@code storeql.clients.pricing-svc.url}).
 */
@ApplicationScoped
public class PricingClient {

  private static final Logger LOG = System.getLogger(PricingClient.class.getName());
  private static final String PRICING_SERVICE = "pricing-svc";

  /** {@code GET /admin/promotions/windows} is the staff-readable leaf common-web lists for this. */
  private static final String INTERNAL_ROLE = "STOREKEEPER";

  @Inject ServiceConfig config;

  private ServiceReader reader;

  @PostConstruct
  void init() {
    reader =
        new ServiceReader(
            config,
            PRICING_SERVICE,
            INTERNAL_ROLE,
            2,
            ServiceReader.configuredUrl(PRICING_SERVICE));
  }

  /**
   * The promotion windows touching a store since {@code from}.
   *
   * @param tenantId owning tenant
   * @param storeId the store
   * @param from the first day of interest
   * @return the windows, or empty when pricing-svc could not be asked
   */
  public Optional<List<PromotionWindow>> promotionWindows(
      UUID tenantId, UUID storeId, LocalDate from) {
    ServiceReader.Reply reply =
        reader.get(
            tenantId,
            "/admin/promotions/windows",
            Map.of("store", storeId.toString(), "from", from.toString()));
    if (!reply.ok()) {
      LOG.log(
          Level.WARNING,
          "pricing-svc did not answer for the promotion windows of store {0} ({1}); forecasting without them",
          storeId,
          reply.status());
      return Optional.empty();
    }
    List<PromotionWindow> out = new ArrayList<>();
    JsonObject envelope = Json.createReader(new StringReader(reply.body())).readObject();
    for (JsonObject w : envelope.getJsonArray("data").getValuesAs(JsonObject.class)) {
      Set<UUID> variants = new HashSet<>();
      if (w.containsKey("variantIds") && !w.isNull("variantIds")) {
        for (JsonValue v : w.getJsonArray("variantIds")) {
          variants.add(Ids.parse(((JsonString) v).getString()));
        }
      }
      out.add(
          new PromotionWindow(
              Ids.parse(w.getString("promotionId")),
              w.containsKey("storeId") && !w.isNull("storeId")
                  ? Ids.parse(w.getString("storeId"))
                  : null,
              Instant.parse(w.getString("startsAt")),
              w.containsKey("endsAt") && !w.isNull("endsAt")
                  ? Instant.parse(w.getString("endsAt"))
                  : null,
              variants,
              w.getBoolean("allVariants", false)));
    }
    return Optional.of(out);
  }
}
