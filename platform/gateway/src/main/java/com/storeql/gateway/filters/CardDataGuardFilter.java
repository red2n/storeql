package com.storeql.gateway.filters;

import com.storeql.gateway.GatewayConfig;
import com.storeql.web.ApiResponse;
import com.storeql.web.CardData;
import com.storeql.web.ErrorBody;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Refuses any request that carries a payment card number, before it reaches a service.
 *
 * <p>The PCI DSS scope decision — SAQ-A, no card detail reaches any StoreQL service because the
 * customer completes against the payment provider — is true of the checkout the platform built and
 * false of every free-text field the platform has, until something enforces it. This does: the body
 * of every POST, PUT and PATCH and the query string of every request are scanned for a card number
 * (see {@link CardData} for what counts and what deliberately does not), and one found is answered
 * {@code 400 CARD_DATA_NOT_ACCEPTED} without the request going anywhere and without the number
 * being echoed or logged. A staff member pasting a card number into an order note, a customer
 * typing one into a delivery instruction, an integration posting one in a CSV: all stopped at the
 * one public door.
 *
 * <p>The scan is bounded: a body past {@code storeql.gateway.card-data-guard.max-scan-bytes} is
 * scanned up to that point, which is more than any text body the API takes and covers the start of
 * an image upload, where a card number could not be anyway. Only that window is read here, and a
 * binary body (an image, a PDF, an octet stream) is not scanned at all.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHORIZATION + 50)
public class CardDataGuardFilter implements ContainerRequestFilter {

  static final String CODE = "CARD_DATA_NOT_ACCEPTED";
  static final String MESSAGE =
      "Card numbers are not accepted by this API: payment card details go to the payment provider"
          + " only, never to StoreQL";

  @Inject GatewayConfig config;

  @Override
  @SuppressWarnings("PMD.CloseResource") // the stream is handed on to the proxy, not dropped
  public void filter(ContainerRequestContext requestContext) throws IOException {
    if (!config.cardDataGuardEnabled()) {
      return;
    }
    String query = requestContext.getUriInfo().getRequestUri().getRawQuery();
    if (query != null && CardData.containsPan(URLDecoder.decode(query, StandardCharsets.UTF_8))) {
      refuse(requestContext);
      return;
    }
    if (!hasBody(requestContext.getMethod()) || !requestContext.hasEntity()) {
      return;
    }
    if (isBinary(requestContext.getMediaType())) {
      // A document or an image is not text a card number is typed into; it is not decoded to a
      // String to look for one.
      return;
    }
    InputStream in = requestContext.getEntityStream();
    int scan = Math.max(config.cardDataGuardMaxScanBytes(), 0);
    // Only the scanned window is read here; the rest of the stream is handed on unread, so a large
    // body is never copied a second time just to be looked at.
    byte[] head = in.readNBytes(scan);
    requestContext.setEntityStream(new SequenceInputStream(new ByteArrayInputStream(head), in));
    String text = new String(head, StandardCharsets.UTF_8);
    if (isFormEncoded(requestContext.getMediaType())) {
      // Percent-encoded spaces and pluses between the digit groups are still a card number.
      text = URLDecoder.decode(text, StandardCharsets.UTF_8);
    }
    if (CardData.containsPan(text)) {
      refuse(requestContext);
    }
  }

  private static boolean hasBody(String method) {
    String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
    return "POST".equals(m) || "PUT".equals(m) || "PATCH".equals(m);
  }

  /** Media types that carry bytes, not text: scanning them as a String only costs heap. */
  static boolean isBinary(MediaType type) {
    if (type == null) return false;
    String main = type.getType().toLowerCase(Locale.ROOT);
    String sub = type.getSubtype().toLowerCase(Locale.ROOT);
    return "image".equals(main)
        || "audio".equals(main)
        || "video".equals(main)
        || ("application".equals(main)
            && ("pdf".equals(sub) || "octet-stream".equals(sub) || "zip".equals(sub)));
  }

  private static boolean isFormEncoded(MediaType type) {
    return type != null
        && "application".equalsIgnoreCase(type.getType())
        && "x-www-form-urlencoded".equalsIgnoreCase(type.getSubtype());
  }

  private static void refuse(ContainerRequestContext requestContext) {
    requestContext.abortWith(
        Response.status(Response.Status.BAD_REQUEST)
            .type(MediaType.APPLICATION_JSON)
            .entity(ApiResponse.error(ErrorBody.of(CODE, MESSAGE)))
            .build());
  }
}
