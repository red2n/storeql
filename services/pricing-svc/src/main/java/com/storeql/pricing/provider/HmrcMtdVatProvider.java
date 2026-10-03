package com.storeql.pricing.provider;

import com.storeql.pricing.domain.Domain.VatObligation;
import com.storeql.pricing.domain.Domain.VatRegistration;
import com.storeql.pricing.domain.Domain.VatReturn;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientRequest;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * HMRC's VAT (MTD) API (18.5): obligations, and the return itself, filed with the taxpayer's own
 * OAuth grant and the fraud-prevention headers the API mandates.
 *
 * <p>What is configuration, not code: the application HMRC issues a client id and secret to on its
 * developer hub, and whether this points at the sandbox ({@code test-api.service.hmrc.gov.uk}, the
 * default) or production. What the taxpayer supplies: the grant, through {@link #authorizeUrl} and
 * {@link #exchangeCode}, which the manager performs once from the admin screen.
 */
@ApplicationScoped
public class HmrcMtdVatProvider implements VatSubmissionProvider {

  private static final System.Logger LOG = System.getLogger(HmrcMtdVatProvider.class.getName());
  private static final String ACCEPT = "application/vnd.hmrc.1.0+json";

  @Inject
  @ConfigProperty(
      name = "storeql.mtd.hmrc.base-url",
      defaultValue = "https://test-api.service.hmrc.gov.uk")
  String baseUrl;

  @Inject
  @ConfigProperty(name = "storeql.mtd.hmrc.client-id")
  Optional<String> clientIdConfig;

  @Inject
  @ConfigProperty(name = "storeql.mtd.hmrc.client-secret")
  Optional<String> clientSecretConfig;

  @Inject
  @ConfigProperty(name = "storeql.mtd.vendor.product-name", defaultValue = "StoreQL")
  String vendorProductName;

  @Inject
  @ConfigProperty(name = "storeql.version", defaultValue = "1.0")
  String vendorVersion;

  @Inject TokenCipher cipher;

  String clientId = "";
  String clientSecret = "";
  private WebClient webClient;

  @PostConstruct
  void init() {
    clientId = clientIdConfig.orElse("");
    clientSecret = clientSecretConfig.orElse("");
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(30))
            .build();
  }

  /** For tests: a provider pointed at a stub, with no CDI. */
  static HmrcMtdVatProvider forTest(
      String baseUrl, String clientId, String clientSecret, TokenCipher cipher) {
    HmrcMtdVatProvider p = new HmrcMtdVatProvider();
    p.baseUrl = baseUrl;
    p.clientId = clientId;
    p.clientSecret = clientSecret;
    p.cipher = cipher;
    p.vendorProductName = "StoreQL";
    p.vendorVersion = "test";
    p.webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();
    return p;
  }

  @Override
  public String name() {
    return VatRegistration.PROVIDER_HMRC;
  }

  @Override
  public boolean isConfigured() {
    return !clientId.isBlank() && !clientSecret.isBlank() && cipher.isConfigured();
  }

  /**
   * Where the manager is sent to grant this application access to the taxpayer's VAT account.
   *
   * @param redirectUri where HMRC returns with the code; must match the application's registration
   * @param state opaque value echoed back, to bind the return to the request
   * @return the URL to open
   */
  public String authorizeUrl(String redirectUri, String state) {
    return baseUrl
        + "/oauth/authorize?response_type=code&client_id="
        + enc(clientId)
        + "&scope="
        + enc("read:vat write:vat")
        + "&redirect_uri="
        + enc(redirectUri)
        + "&state="
        + enc(state);
  }

  /** Tokens as HMRC issues them, before encryption. */
  public record Tokens(String accessToken, String refreshToken, Instant expiresAt) {}

  /**
   * Exchanges the code HMRC returned for the taxpayer's tokens.
   *
   * @param code the authorization code
   * @param redirectUri the same redirect the code was issued against
   * @return the tokens
   * @throws ProviderException when HMRC refused the exchange
   */
  public Tokens exchangeCode(String code, String redirectUri) {
    return token(
        "grant_type=authorization_code&code="
            + enc(code)
            + "&redirect_uri="
            + enc(redirectUri)
            + "&client_id="
            + enc(clientId)
            + "&client_secret="
            + enc(clientSecret));
  }

  /** A fresh access token from the refresh token, when the stored one has expired. */
  public Tokens refresh(String refreshToken) {
    return token(
        "grant_type=refresh_token&refresh_token="
            + enc(refreshToken)
            + "&client_id="
            + enc(clientId)
            + "&client_secret="
            + enc(clientSecret));
  }

  private Tokens token(String form) {
    try (HttpClientResponse res =
        webClient
            .post(baseUrl + "/oauth/token")
            .header(HeaderNames.CONTENT_TYPE, "application/x-www-form-urlencoded")
            .submit(form)) {
      String body = res.as(String.class);
      if (res.status().code() != 200) {
        throw new ProviderException(
            "HMRC_OAUTH_REFUSED",
            "HMRC refused the token exchange: HTTP " + res.status().code(),
            false);
      }
      JsonObject j = parse(body);
      long expiresIn =
          j.containsKey("expires_in") ? j.getJsonNumber("expires_in").longValue() : 14400;
      return new Tokens(
          j.getString("access_token"),
          j.getString("refresh_token", null),
          Instant.now().plusSeconds(expiresIn));
    } catch (ProviderException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ProviderException(
          "HMRC_UNREACHABLE", "HMRC could not be reached: " + e.getMessage(), true, e);
    }
  }

  @Override
  public List<VatObligation> obligations(
      VatRegistration reg, Instant from, Instant to, Set<String> filedPeriodKeys) {
    String path =
        "/organisations/vat/" + reg.vrn() + "/obligations?from=" + date(from) + "&to=" + date(to);
    JsonObject j = call("GET", path, reg, null, Map.of());
    List<VatObligation> out = new ArrayList<>();
    var arr = j.getJsonArray("obligations");
    if (arr != null) {
      for (JsonObject o : arr.getValuesAs(JsonObject.class)) {
        out.add(
            new VatObligation(
                o.getString("periodKey"),
                LocalDate.parse(o.getString("start")).atStartOfDay(ZoneOffset.UTC).toInstant(),
                LocalDate.parse(o.getString("end")).atStartOfDay(ZoneOffset.UTC).toInstant(),
                o.containsKey("due")
                    ? LocalDate.parse(o.getString("due")).atStartOfDay(ZoneOffset.UTC).toInstant()
                    : null,
                o.getString("status", VatObligation.STATUS_OPEN),
                o.containsKey("received") && !o.isNull("received")
                    ? LocalDate.parse(o.getString("received"))
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                    : null));
      }
    }
    return out;
  }

  @Override
  public Receipt submit(
      VatRegistration reg, String periodKey, VatReturn b, Map<String, String> clientHeaders) {
    JsonObject body =
        Json.createObjectBuilder()
            .add("periodKey", periodKey)
            .add("vatDueSales", b.box1())
            .add("vatDueAcquisitions", b.box2())
            .add("totalVatDue", b.box3())
            .add("vatReclaimedCurrPeriod", b.box4())
            .add("netVatDue", b.box5())
            .add("totalValueSalesExVAT", b.box6())
            .add("totalValuePurchasesExVAT", b.box7())
            .add("totalValueGoodsSuppliedExVAT", b.box8())
            .add("totalAcquisitionsExVAT", b.box9())
            .add("finalised", true)
            .build();
    JsonObject j =
        call(
            "POST",
            "/organisations/vat/" + reg.vrn() + "/returns",
            reg,
            body.toString(),
            clientHeaders);
    return new Receipt(
        j.containsKey("processingDate")
            ? Instant.parse(j.getString("processingDate"))
            : Instant.now(),
        j.getString("formBundleNumber", null),
        j.getString("paymentIndicator", null),
        j.getString("chargeRefNumber", null),
        j.getString("receiptId", null),
        j.containsKey("receiptTimestamp") ? Instant.parse(j.getString("receiptTimestamp")) : null);
  }

  /**
   * The fraud-prevention headers HMRC mandates; what the server knows plus what the client sent.
   */
  static Map<String, String> fraudHeaders(
      Map<String, String> client, String productName, String version) {
    var h = new java.util.LinkedHashMap<String, String>();
    h.put("Gov-Client-Connection-Method", "WEB_APP_VIA_SERVER");
    h.put("Gov-Vendor-Product-Name", enc(productName));
    h.put("Gov-Vendor-Version", enc(productName) + "=" + enc(version));
    h.put("Gov-Client-Timezone", client.getOrDefault("Gov-Client-Timezone", "UTC+00:00"));
    for (String k :
        List.of(
            "Gov-Client-Public-IP",
            "Gov-Client-Public-Port",
            "Gov-Client-Device-ID",
            "Gov-Client-User-IDs",
            "Gov-Client-Screens",
            "Gov-Client-Window-Size",
            "Gov-Client-Browser-JS-User-Agent",
            "Gov-Client-Browser-Do-Not-Track",
            "Gov-Client-Multi-Factor",
            "Gov-Vendor-Public-IP",
            "Gov-Vendor-Forwarded",
            "Gov-Client-Public-IP-Timestamp")) {
      String v = client.get(k);
      if (v != null && !v.isBlank()) {
        h.put(k, v);
      }
    }
    return h;
  }

  private JsonObject call(
      String method,
      String path,
      VatRegistration reg,
      String body,
      Map<String, String> clientHeaders) {
    if (!reg.connected()) {
      throw new ProviderException(
          "HMRC_NOT_CONNECTED",
          "HMRC has not granted this application access to the VAT account; connect first",
          false);
    }
    String token = cipher.decrypt(reg.accessTokenCipher());
    // A URI, never the String form: that escapes the "?" of the obligations query into the path.
    URI target = URI.create(baseUrl + path);
    HttpClientRequest req =
        "POST".equals(method) ? webClient.post().uri(target) : webClient.get().uri(target);
    req =
        req.header(HeaderNames.ACCEPT, ACCEPT).header(HeaderNames.AUTHORIZATION, "Bearer " + token);
    for (var h : fraudHeaders(clientHeaders, vendorProductName, vendorVersion).entrySet()) {
      req = req.header(HeaderNames.create(h.getKey()), h.getValue());
    }
    try (HttpClientResponse res =
        body == null
            ? req.request()
            : req.header(HeaderNames.CONTENT_TYPE, "application/json").submit(body)) {
      String text = res.as(String.class);
      int status = res.status().code();
      if (status == 200 || status == 201) {
        JsonObject j = text.isBlank() ? Json.createObjectBuilder().build() : parse(text);
        String receiptId = res.headers().first(HeaderNames.create("Receipt-ID")).orElse(null);
        if (receiptId != null && !j.containsKey("receiptId")) {
          j = Json.createObjectBuilder(j).add("receiptId", receiptId).build();
        }
        return j;
      }
      String code = "HMRC_" + status;
      String message = text;
      try {
        JsonObject err = parse(text);
        code = err.getString("code", code);
        message = err.getString("message", text);
        var errors = err.getJsonArray("errors");
        if (errors != null && !errors.isEmpty()) {
          JsonObject first = errors.getJsonObject(0);
          code = first.getString("code", code);
          message = first.getString("message", message);
        }
      } catch (RuntimeException ignored) {
        // not JSON; the raw text is the message
      }
      LOG.log(System.Logger.Level.WARNING, "HMRC {0} {1}: {2} {3}", method, path, status, text);
      throw new ProviderException(code, message, status >= 500 || status == 429);
    } catch (ProviderException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ProviderException(
          "HMRC_UNREACHABLE", "HMRC could not be reached: " + e.getMessage(), true, e);
    }
  }

  private static String date(Instant i) {
    return i.atZone(ZoneOffset.UTC).toLocalDate().toString();
  }

  private static String enc(String v) {
    return URLEncoder.encode(v, StandardCharsets.UTF_8);
  }

  private static JsonObject parse(String text) {
    try (JsonReader r = Json.createReader(new StringReader(text))) {
      return r.readObject();
    }
  }
}
