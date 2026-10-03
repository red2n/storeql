package com.storeql.order.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.order.config.Json;
import com.storeql.order.config.ServiceConfig;
import com.storeql.service.ServiceReader;
import com.storeql.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientRequest;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Delivers a document into this platform's own inbox, as a network would (07.13, the transport
 * seam).
 *
 * <p>The simulated provider stands in for a network; when the receiver is a business on this
 * platform, standing in means the document lands in that business's inbox, through the same route
 * an access point would call: purchase-svc's delivery route, with the deployment's delivery key and
 * no identity at all — the receiver is whoever the document names.
 */
@ApplicationScoped
public class EInvoiceDeliveryClient {

  private static final Logger LOG = System.getLogger(EInvoiceDeliveryClient.class.getName());

  static final String PURCHASE_SERVICE = "purchase-svc";

  static final String ROUTE = "/e-invoices/inbound/";

  /** What the inbox answered: {@code 0} when it could not be reached. */
  public record Delivery(int status, String body) {

    public boolean delivered() {
      return status == 200 || status == 201;
    }

    public boolean unreachable() {
      return status == 0 || status >= 500;
    }

    /** The inbox's reason, when it gave one as an error envelope. */
    public String reason() {
      JsonObject json = json().orElse(null);
      if (json != null && json.containsKey("error") && !json.isNull("error")) {
        JsonObject error = json.getJsonObject("error");
        return error.getString("message", error.getString("code", "refused"));
      }
      return "HTTP " + status;
    }

    /** The body as JSON, when it is JSON. */
    public Optional<JsonObject> json() {
      if (body == null || !body.strip().startsWith("{")) return Optional.empty();
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        return Optional.of(reader.readObject());
      } catch (RuntimeException e) {
        return Optional.empty();
      }
    }
  }

  @Inject ServiceConfig config;

  /** The key purchase-svc holds deliveries against; none means no in-platform delivery. */
  @Inject
  @ConfigProperty(name = "storeql.einvoice.inbound.key")
  Optional<String> key;

  private ServiceRegistry registry;
  private WebClient web;

  @PostConstruct
  void init() {
    registry = new ConsulClient(config.consulHost(), config.consulPort());
    web =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(10))
            .build();
  }

  /** Whether this deployment can deliver into its own inbox at all. */
  public boolean isConfigured() {
    return key != null && key.filter(k -> !k.isBlank()).isPresent();
  }

  /**
   * Delivers a document.
   *
   * @param network the network the document travels, as the route names it
   * @param reference this side's reference for the delivery
   * @param ubl the document
   * @return what the inbox answered
   */
  public Delivery deliver(String network, String reference, String ubl) {
    Optional<String> base = locate();
    if (base.isEmpty()) {
      LOG.log(Level.WARNING, "{0} could not be located for a delivery", PURCHASE_SERVICE);
      return new Delivery(0, null);
    }
    HttpClientRequest request =
        web.post(base.get() + ROUTE + network.toLowerCase(Locale.ROOT))
            .header(HeaderNames.create(HttpHeaders.EINVOICE_KEY), key.orElse(""))
            .header(HeaderNames.CONTENT_TYPE, "application/xml");
    if (reference != null) {
      request = request.header(HeaderNames.create(HttpHeaders.EINVOICE_REFERENCE), reference);
    }
    try (HttpClientResponse res = request.submit(ubl)) {
      String body = res.entity().hasEntity() ? res.as(String.class) : null;
      return new Delivery(res.status().code(), body);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "delivery over {0} failed: {1}", network, e.getMessage());
      return new Delivery(0, null);
    }
  }

  private Optional<String> locate() {
    return ServiceReader.configuredUrl(PURCHASE_SERVICE)
        .or(() -> registry.resolve(PURCHASE_SERVICE).map(ServiceInstance::baseUri));
  }
}
