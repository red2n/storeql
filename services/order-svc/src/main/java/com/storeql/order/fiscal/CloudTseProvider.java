package com.storeql.order.fiscal;

import com.storeql.ids.Ids;
import com.storeql.order.config.Json;
import com.storeql.order.domain.Domain.TseDevice;
import com.storeql.order.domain.Domain.TseStamp;
import com.storeql.web.ApiException;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A certified security module at a cloud provider, over the REST shape the market's cloud TSEs
 * share (18.5): authenticate with an API key, open a transaction as {@code ACTIVE}, close it as
 * {@code FINISHED} with the receipt's amounts per rate and per payment type, and read back the
 * signature, the counters, the serial and the QR payload the provider's certificate covers.
 *
 * <p>Nothing of the key is here. The provider signs; this class hands over the figures and stores
 * what comes back, which is the whole of what the law asks the till to do.
 */
@ApplicationScoped
public class CloudTseProvider implements TseProvider {

  private static final System.Logger LOG = System.getLogger(CloudTseProvider.class.getName());

  @Inject
  @ConfigProperty(
      name = "storeql.fiscal.tse.cloud.base-url",
      defaultValue = "https://kassensichv-middleware.fiskaly.com/api/v2")
  String baseUrl;

  @Inject
  @ConfigProperty(name = "storeql.fiscal.tse.cloud.api-key")
  Optional<String> apiKeyConfig;

  @Inject
  @ConfigProperty(name = "storeql.fiscal.tse.cloud.api-secret")
  Optional<String> apiSecretConfig;

  /** Resolved at startup; package-private so a test can point at a stub. */
  String apiKey = "";

  String apiSecret = "";

  private WebClient webClient;

  @PostConstruct
  void init() {
    apiKey = apiKeyConfig.orElse("");
    apiSecret = apiSecretConfig.orElse("");
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();
  }

  /** For tests: a provider pointed at a stub server, with no CDI. */
  static CloudTseProvider forTest(String baseUrl, String apiKey, String apiSecret) {
    CloudTseProvider p = new CloudTseProvider();
    p.baseUrl = baseUrl;
    p.apiKey = apiKey;
    p.apiSecret = apiSecret;
    p.webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();
    return p;
  }

  @Override
  public String name() {
    return TseDevice.PROVIDER_CLOUD;
  }

  /**
   * @return whether credentials are configured, and so whether a store may register a cloud device
   */
  @Override
  public boolean isConfigured() {
    return !apiKey.isBlank() && !apiSecret.isBlank();
  }

  @Override
  public TseDevice register(RegistrationRequest req) {
    if (!isConfigured()) {
      throw ApiException.conflict(
          "FISCAL_TSE_CLOUD_NOT_CONFIGURED",
          "No cloud TSE credentials are configured (storeql.fiscal.tse.cloud.api-key/api-secret)");
    }
    if (req.externalTssId() == null || req.externalTssId().isBlank()) {
      throw ApiException.badRequest(
          "FISCAL_TSE_ID_REQUIRED", "tseTssId is required to register a cloud device");
    }
    String token = token();
    JsonObject tss = get("/tss/" + req.externalTssId(), token);
    return new TseDevice(
        Ids.newId(),
        req.tenantId(),
        req.storeId(),
        name(),
        req.clientId(),
        tss.getString("serial_number", ""),
        tss.getString("public_key", ""),
        tss.getString("signature_algorithm", "ecdsa-plain-SHA256"),
        tss.getString("signature_timestamp_format", "unixTime"),
        null,
        req.externalTssId(),
        0,
        0,
        null,
        req.registeredBy());
  }

  @Override
  public TseStamp sign(TseDevice device, SaleFigures sale) {
    if (!isConfigured()) {
      throw new TseException("cloud TSE credentials are not configured", null);
    }
    try {
      return signWith(token(), device, sale);
    } catch (Unauthorized e) {
      // The provider no longer honours the cached bearer token: sign in again, once.
      invalidate();
      return signWith(token(), device, sale);
    }
  }

  private TseStamp signWith(String token, TseDevice device, SaleFigures sale) {
    UUID txId = Ids.newId();
    String path = "/tss/" + device.externalTssId() + "/tx/" + txId;
    put(
        path + "?tx_revision=1",
        token,
        Json.createObjectBuilder()
            .add("state", "ACTIVE")
            .add("client_id", device.clientId())
            .build());
    JsonObject finished =
        put(
            path + "?tx_revision=2",
            token,
            Json.createObjectBuilder()
                .add("state", "FINISHED")
                .add("client_id", device.clientId())
                .add("schema", Json.createObjectBuilder().add("standard_v1", receiptSchema(sale)))
                .build());
    JsonObject sig = finished.getJsonObject("signature");
    Instant started = epoch(finished, "time_start", sale.startedAt());
    Instant ended = epoch(finished, "time_end", Instant.now());
    String processData = ProcessData.kassenbeleg(sale);
    String algorithm = sig.getString("algorithm", device.signatureAlgorithm());
    String publicKey = sig.getString("public_key", device.publicKey());
    String timeFormat =
        finished.containsKey("log") && !finished.isNull("log")
            ? finished.getJsonObject("log").getString("timestamp_format", device.timeFormat())
            : device.timeFormat();
    long number = finished.getJsonNumber("number").longValue();
    long counter = sig.getJsonNumber("counter").longValue();
    String qr =
        finished.containsKey("qr_code_data") && !finished.isNull("qr_code_data")
            ? finished.getString("qr_code_data")
            : ProcessData.qr(
                device.clientId(),
                TseStamp.PROCESS_TYPE_RECEIPT,
                processData,
                number,
                counter,
                started,
                ended,
                algorithm,
                timeFormat,
                sig.getString("value"),
                publicKey);
    return new TseStamp(
        finished.getString("tss_serial_number", device.serialNumber()),
        device.clientId(),
        number,
        counter,
        sig.getString("value"),
        algorithm,
        publicKey,
        timeFormat,
        started,
        ended,
        TseStamp.PROCESS_TYPE_RECEIPT,
        processData,
        qr,
        null);
  }

  /** The receipt as the provider's schema shapes it: amounts per rate name, per payment type. */
  static JsonObject receiptSchema(SaleFigures sale) {
    JsonArrayBuilder perRate = Json.createArrayBuilder();
    BigDecimal[] gross = ProcessData.grossByPosition(sale.grossByRate());
    for (int i = 0; i < gross.length; i++) {
      if (gross[i].signum() != 0) {
        perRate.add(
            Json.createObjectBuilder()
                .add("vat_rate", ProcessData.cloudVatRateName(i + 1))
                .add("amount", ProcessData.money(gross[i])));
      }
    }
    JsonArrayBuilder perPayment = Json.createArrayBuilder();
    if (sale.tenders().isEmpty()) {
      perPayment.add(
          Json.createObjectBuilder()
              .add("payment_type", "NON_CASH")
              .add("amount", ProcessData.money(sale.gross())));
    } else {
      for (var t : sale.tenders()) {
        perPayment.add(
            Json.createObjectBuilder()
                .add("payment_type", t.isCash() ? "CASH" : "NON_CASH")
                .add("amount", ProcessData.money(t.amount())));
      }
    }
    JsonObjectBuilder receipt =
        Json.createObjectBuilder()
            .add("receipt_type", "RECEIPT")
            .add("amounts_per_vat_rate", perRate)
            .add("amounts_per_payment_type", perPayment);
    return Json.createObjectBuilder().add("receipt", receipt).build();
  }

  private static Instant epoch(JsonObject o, String key, Instant fallback) {
    if (o.containsKey(key) && !o.isNull(key)) {
      return Instant.ofEpochSecond(o.getJsonNumber(key).longValue());
    }
    return fallback;
  }

  /** A bearer token and the moment it must no longer be used. */
  private record Token(String value, Instant until) {}

  /** The provider answered 401: the bearer token in use is no longer good. */
  private static final class Unauthorized extends TseException {
    private static final long serialVersionUID = 1L;

    Unauthorized(String message) {
      super(message, null);
    }
  }

  private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

  private volatile Token cached;
  private final ReentrantLock signIn = new ReentrantLock();

  private void invalidate() {
    cached = null;
  }

  /**
   * The bearer token for the provider: the cached one while it lasts, else one fresh sign-in shared
   * by every caller waiting for it (one authenticates, the rest use the result), so a till does not
   * make three round trips to the provider per receipt.
   */
  private String token() {
    Token t = cached;
    if (t != null && Instant.now().isBefore(t.until())) return t.value();
    signIn.lock();
    try {
      t = cached;
      if (t != null && Instant.now().isBefore(t.until())) return t.value();
      return authenticate();
    } finally {
      signIn.unlock();
    }
  }

  private String authenticate() {
    JsonObject body =
        Json.createObjectBuilder().add("api_key", apiKey).add("api_secret", apiSecret).build();
    try (HttpClientResponse res =
        webClient
            .post(baseUrl + "/auth")
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(body.toString())) {
      String text = res.as(String.class);
      if (res.status().code() != 200) {
        throw new TseException(
            "cloud TSE refused the credentials: HTTP " + res.status().code(), null);
      }
      JsonObject auth = parse(text);
      String token = auth.getString("access_token");
      // Cached only when the provider says how long the token lasts, less a margin.
      long lifeSeconds =
          auth.containsKey("access_token_expires_in") && !auth.isNull("access_token_expires_in")
              ? auth.getJsonNumber("access_token_expires_in").longValue()
              : 0L;
      Duration life = Duration.ofSeconds(lifeSeconds).minus(EXPIRY_MARGIN);
      cached =
          life.isNegative() || life.isZero() ? null : new Token(token, Instant.now().plus(life));
      return token;
    } catch (TseException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new TseException("cloud TSE unreachable: " + e.getMessage(), e);
    }
  }

  /**
   * The address of a path, as a URI: the WebClient's String form escapes the "?" of {@code
   * ?tx_revision=} into the path, and the TSE answers 404.
   */
  private URI target(String path) {
    return URI.create(baseUrl + path);
  }

  private JsonObject get(String path, String token) {
    try (HttpClientResponse res =
        webClient
            .get()
            .uri(target(path))
            .header(HeaderNames.AUTHORIZATION, "Bearer " + token)
            .request()) {
      String text = res.as(String.class);
      if (res.status().code() == 401) {
        throw new Unauthorized("cloud TSE refused the bearer token for " + path);
      }
      if (res.status().code() != 200) {
        LOG.log(
            System.Logger.Level.WARNING,
            "cloud TSE GET {0}: {1} {2}",
            path,
            res.status().code(),
            text);
        throw new TseException(
            "cloud TSE answered HTTP " + res.status().code() + " for " + path, null);
      }
      return parse(text);
    } catch (TseException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new TseException("cloud TSE unreachable: " + e.getMessage(), e);
    }
  }

  private JsonObject put(String path, String token, JsonObject body) {
    try (HttpClientResponse res =
        webClient
            .put()
            .uri(target(path))
            .header(HeaderNames.AUTHORIZATION, "Bearer " + token)
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(body.toString())) {
      String text = res.as(String.class);
      if (res.status().code() == 401) {
        throw new Unauthorized("cloud TSE refused the bearer token for " + path);
      }
      if (res.status().code() != 200) {
        LOG.log(
            System.Logger.Level.WARNING,
            "cloud TSE PUT {0}: {1} {2}",
            path,
            res.status().code(),
            text);
        throw new TseException(
            "cloud TSE answered HTTP " + res.status().code() + " for " + path, null);
      }
      return parse(text);
    } catch (TseException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new TseException("cloud TSE unreachable: " + e.getMessage(), e);
    }
  }

  private static JsonObject parse(String text) {
    try (JsonReader r = Json.createReader(new StringReader(text))) {
      return r.readObject();
    }
  }
}
