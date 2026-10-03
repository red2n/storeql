package com.storeql.notification.provider;

import com.storeql.notification.json.Jsons;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Texts through Twilio's Messages API — the carrier account most SMS providers copy the shape of.
 * Configured when the account SID, the auth token and the sending number are set; the base URL is
 * overridable so the tests can stand a fake at the other end.
 */
@ApplicationScoped
public class TwilioSmsProvider implements SmsProvider {

  public static final String NAME = "TWILIO";

  @Inject
  @ConfigProperty(
      name = "storeql.notification.sms.twilio.base-url",
      defaultValue = "https://api.twilio.com")
  String baseUrl;

  @Inject
  @ConfigProperty(name = "storeql.notification.sms.twilio.account-sid")
  Optional<String> accountSidConfig;

  @Inject
  @ConfigProperty(name = "storeql.notification.sms.twilio.auth-token")
  Optional<String> authTokenConfig;

  @Inject
  @ConfigProperty(name = "storeql.notification.sms.twilio.from")
  Optional<String> fromConfig;

  String accountSid = "";
  String authToken = "";
  String from = "";
  private WebClient webClient;

  @PostConstruct
  void init() {
    accountSid = accountSidConfig.orElse("").trim();
    authToken = authTokenConfig.orElse("").trim();
    from = fromConfig.orElse("").trim();
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(15))
            .build();
  }

  static TwilioSmsProvider forTest(String baseUrl, String sid, String token, String from) {
    TwilioSmsProvider p = new TwilioSmsProvider();
    p.baseUrl = baseUrl;
    p.accountSid = sid;
    p.authToken = token;
    p.from = from;
    p.webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(5))
            .build();
    return p;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isConfigured() {
    return !accountSid.isEmpty() && !authToken.isEmpty() && !from.isEmpty();
  }

  @Override
  public String send(String to, String body) {
    if (!isConfigured()) {
      throw new ProviderException("SMS_NOT_CONFIGURED", "Twilio is not configured", false);
    }
    String form =
        "To="
            + URLEncoder.encode(to, StandardCharsets.UTF_8)
            + "&From="
            + URLEncoder.encode(from, StandardCharsets.UTF_8)
            + "&Body="
            + URLEncoder.encode(body, StandardCharsets.UTF_8);
    String basic =
        Base64.getEncoder()
            .encodeToString((accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));
    try (HttpClientResponse res =
        webClient
            .post(baseUrl + "/2010-04-01/Accounts/" + accountSid + "/Messages.json")
            .header(HeaderNames.AUTHORIZATION, "Basic " + basic)
            .header(HeaderNames.CONTENT_TYPE, "application/x-www-form-urlencoded")
            .submit(form)) {
      int status = res.status().code();
      String text = Bodies.text(res);
      if (status == 201 || status == 200) {
        return field(text, "sid", "unknown");
      }
      if (status >= 500 || status == 429) {
        throw new ProviderException("SMS_PROVIDER_UNAVAILABLE", "Twilio answered " + status, true);
      }
      throw new ProviderException(
          "SMS_REJECTED_" + field(text, "code", String.valueOf(status)),
          field(text, "message", "Twilio answered " + status),
          false);
    } catch (ProviderException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ProviderException(
          "SMS_PROVIDER_UNREACHABLE", "Twilio could not be reached: " + e.getMessage(), true, e);
    }
  }

  private static String field(String json, String key, String fallback) {
    try (JsonReader r = Jsons.reader(new StringReader(json))) {
      JsonObject o = r.readObject();
      if (!o.containsKey(key) || o.isNull(key)) return fallback;
      return o.get(key).getValueType() == jakarta.json.JsonValue.ValueType.STRING
          ? o.getString(key)
          : o.get(key).toString();
    } catch (RuntimeException e) {
      return fallback;
    }
  }
}
