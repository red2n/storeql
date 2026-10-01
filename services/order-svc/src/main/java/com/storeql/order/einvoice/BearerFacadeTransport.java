package com.storeql.order.einvoice;

import com.storeql.order.config.Json;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.time.Duration;
import java.util.function.BiFunction;

/**
 * What the Peppol access point and France's platform have in common: an HTTP facade at a base URL,
 * a bearer key, JSON in and out, and the same reading of an answer — a 2xx to make an outcome of, a
 * 4xx that is the network's refusal, anything else or nothing at all to try again later.
 */
abstract class BearerFacadeTransport implements EInvoiceTransport {

  /** What the facade answered. */
  record Reply(int status, String body) {

    boolean ok() {
      return status >= 200 && status < 300;
    }

    /** A refusal of this document, as opposed to a failure of the facade. */
    boolean refused() {
      return status == 400 || status == 404 || status == 409 || status == 422;
    }

    JsonObject object() {
      return BearerFacadeTransport.object(body);
    }
  }

  String baseUrl = "";
  String apiKey = "";
  private WebClient web;

  /** The facade's name in messages: "the access point", "the platform". */
  abstract String facade();

  /** Called once configured, by CDI or a test. */
  final void configure(String baseUrl, String apiKey) {
    this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
    this.apiKey = apiKey == null ? "" : apiKey.strip();
    web =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(15))
            .build();
  }

  @Override
  public boolean isConfigured() {
    return !baseUrl.isBlank() && !apiKey.isBlank();
  }

  /**
   * Posts JSON to the facade.
   *
   * @throws TransportException when the facade could not be reached or failed on its side
   */
  final Reply post(String path, JsonObject body) {
    try (HttpClientResponse res =
        web.post(baseUrl + path)
            .header(HeaderNames.AUTHORIZATION, "Bearer " + apiKey)
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .header(HeaderNames.ACCEPT, "application/json")
            .submit(body.toString())) {
      return answered(res);
    } catch (TransportException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new TransportException(facade() + " could not be reached: " + e.getMessage(), e);
    }
  }

  /**
   * Reads from the facade.
   *
   * @throws TransportException as {@link #post}
   */
  final Reply get(String path) {
    try (HttpClientResponse res =
        web.get(baseUrl + path)
            .header(HeaderNames.AUTHORIZATION, "Bearer " + apiKey)
            .header(HeaderNames.ACCEPT, "application/json")
            .request()) {
      return answered(res);
    } catch (TransportException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new TransportException(facade() + " could not be reached: " + e.getMessage(), e);
    }
  }

  /**
   * Asks the facade whether it knows us, without sending a document.
   *
   * <p>A HEAD-shaped question against a path the facade serves for exactly this: an answer of any
   * kind means it is reachable, an authentication refusal means the key is wrong, and a timeout
   * means the network is down. Distinguishing those three is the whole value of a check — "it did
   * not work" is what a shop already knows.
   */
  final Readiness probe(String path) {
    if (!isConfigured()) {
      return Readiness.refused(
          "this deployment holds no address or key for " + facade() + ", so nothing can be sent");
    }
    int status;
    // Deliberately not get(): that reading throws for anything it does not recognise, so a facade
    // answering "not you" would arrive here as "could not be reached" — the one confusion the check
    // exists to prevent, since a wrong key is fixed by a person and a network down is fixed by
    // waiting.
    try (HttpClientResponse res =
        web.get(baseUrl + path)
            .header(HeaderNames.AUTHORIZATION, "Bearer " + apiKey)
            .header(HeaderNames.ACCEPT, "application/json")
            .request()) {
      status = res.status().code();
    } catch (RuntimeException e) {
      return Readiness.unreachable(facade() + " could not be reached: " + e.getMessage());
    }
    if (status == 401 || status == 403) {
      return Readiness.refused(
          facade() + " answered but would not have us: the key it holds is not the one we sent");
    }
    if (status >= 500) {
      return Readiness.unreachable(
          facade() + " answered HTTP " + status + ": it is there, and having trouble of its own");
    }
    // Anything else — including a 404 for a probe path a provider does not serve — means it is
    // there
    // and talking to us, which is what a check can honestly establish.
    return Readiness.ready(facade() + " answered, and took the key we hold");
  }

  /**
   * What a deposit came to: the facade's refusal as a rejection, its acceptance as the outcome it
   * states, under the id it gave the document.
   *
   * @param outcome the facade's answer read as an outcome
   */
  final Dispatch dispatched(Reply reply, BiFunction<JsonObject, String, Outcome> outcome) {
    if (!reply.ok()) {
      return new Dispatch(
          null,
          Outcome.rejected(
              reason(reply.object(), "refused with HTTP " + reply.status()), reply.body()));
    }
    JsonObject o = reply.object();
    String ref = o.getString("id", null);
    if (ref == null || ref.isBlank()) {
      throw new TransportException(facade() + " answered without an id for the document", null);
    }
    return new Dispatch(ref, outcome.apply(o, reply.body()));
  }

  private Reply answered(HttpClientResponse res) {
    Reply reply =
        new Reply(res.status().code(), res.entity().hasEntity() ? res.as(String.class) : "");
    if (!reply.ok() && !reply.refused()) {
      throw new TransportException(facade() + " answered HTTP " + reply.status(), null);
    }
    return reply;
  }

  /** The facade's reason for a refusal, wherever it put it. */
  static String reason(JsonObject o, String fallback) {
    for (String key : new String[] {"reason", "message", "label", "motif"}) {
      if (o.containsKey(key)
          && !o.isNull(key)
          && o.get(key).getValueType() == JsonValue.ValueType.STRING) {
        String s = o.getString(key);
        if (!s.isBlank()) return s;
      }
    }
    if (o.containsKey("error") && o.get("error").getValueType() == JsonValue.ValueType.OBJECT) {
      return reason(o.getJsonObject("error"), fallback);
    }
    return fallback;
  }

  static JsonObject object(String json) {
    try (JsonReader r =
        Json.createReader(new StringReader(json == null || json.isBlank() ? "{}" : json))) {
      return r.readObject();
    } catch (RuntimeException e) {
      return Json.createObjectBuilder().build();
    }
  }
}
