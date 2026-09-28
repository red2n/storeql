package com.storeql.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.gateway.GatewayConfig;
import jakarta.json.bind.JsonbBuilder;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The one public door refuses a card number. Positive cases are the ways one actually arrives — a
 * note, a nested field, spaces, a form, a query string; negative cases are the retail data that
 * must keep flowing, and the body must be intact for the proxy afterwards.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CardDataGuardFilterTest {

  @Mock ContainerRequestContext ctx;
  @Mock UriInfo uriInfo;
  @Mock GatewayConfig config;

  private CardDataGuardFilter filter;

  @BeforeEach
  void setUp() {
    when(config.cardDataGuardEnabled()).thenReturn(true);
    when(config.cardDataGuardMaxScanBytes()).thenReturn(8 * 1024 * 1024);
    filter = new CardDataGuardFilter();
    filter.config = config;
    when(ctx.getUriInfo()).thenReturn(uriInfo);
    when(uriInfo.getRequestUri()).thenReturn(URI.create("http://gw/api/order-svc/orders"));
    when(ctx.getMethod()).thenReturn("POST");
    when(ctx.getMediaType()).thenReturn(MediaType.APPLICATION_JSON_TYPE);
    when(ctx.hasEntity()).thenReturn(true);
  }

  private void body(String text) {
    when(ctx.getEntityStream())
        .thenReturn(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
  }

  private Response refused() {
    ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
    verify(ctx).abortWith(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("A card number in a note is refused with a code, and never echoed back")
  void refusesACardNumberInANote() throws IOException {
    body("{\"note\":\"customer paid on 4111 1111 1111 1111, ring back\"}");
    filter.filter(ctx);
    Response r = refused();
    assertEquals(400, r.getStatus());
    String entity = JsonbBuilder.create().toJson(r.getEntity());
    assertTrue(entity.contains(CardDataGuardFilter.CODE), entity);
    assertFalse(entity.contains("1111 1111"), "the refusal must not repeat the number");
    assertFalse(entity.contains("4111"), "or any part of it");
  }

  @Test
  @DisplayName("Nested and hyphenated, in a PUT, still a card number")
  void refusesNestedAndHyphenated() throws IOException {
    when(ctx.getMethod()).thenReturn("PUT");
    body("{\"lines\":[{\"sku\":\"A\",\"instructions\":\"card 5555-5555-5555-4444 exp 1/29\"}]}");
    filter.filter(ctx);
    assertEquals(400, refused().getStatus());
  }

  @Test
  @DisplayName("A form-encoded body is decoded before it is scanned")
  void refusesAFormBody() throws IOException {
    when(ctx.getMediaType()).thenReturn(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
    body("name=x&note=card+378282246310005");
    filter.filter(ctx);
    assertEquals(400, refused().getStatus());
  }

  @Test
  @DisplayName("A card number in the query string is refused on any method — it would be logged")
  void refusesAQueryString() throws IOException {
    when(ctx.getMethod()).thenReturn("GET");
    when(ctx.hasEntity()).thenReturn(false);
    when(uriInfo.getRequestUri())
        .thenReturn(
            URI.create("http://gw/api/product-svc/products?search=4111%201111%201111%201111"));
    filter.filter(ctx);
    assertEquals(400, refused().getStatus());
  }

  @Test
  @DisplayName("Barcodes, phone numbers and identifiers pass, and the body is intact afterwards")
  void letsRetailDataThroughIntact() throws IOException {
    String json =
        "{\"barcode\":\"5012345678900\",\"gtin\":\"36123456789012\",\"phone\":\"+44 7911 123456\","
            + "\"imei\":\"353918050478917\",\"ref\":\"ORD-2026-000042\",\"notLuhn\":\"4111111111111112\","
            + "\"id\":\"01234567-8901-7234-8567-890123456789\"}";
    body(json);
    filter.filter(ctx);
    verify(ctx, never()).abortWith(any());
    ArgumentCaptor<java.io.InputStream> stream = ArgumentCaptor.forClass(java.io.InputStream.class);
    verify(ctx).setEntityStream(stream.capture());
    assertEquals(json, new String(stream.getValue().readAllBytes(), StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("A GET without a card number in its query is not touched at all")
  void ignoresPlainReads() throws IOException {
    when(ctx.getMethod()).thenReturn("GET");
    when(ctx.hasEntity()).thenReturn(false);
    when(uriInfo.getRequestUri())
        .thenReturn(URI.create("http://gw/api/product-svc/products?barcode=5012345678900"));
    filter.filter(ctx);
    verify(ctx, never()).abortWith(any());
    verify(ctx, never()).getEntityStream();
  }

  @Test
  @DisplayName(
      "Past the scan cap the rest of the body is not read as text — but the cap is generous")
  void scanIsBounded() throws IOException {
    when(config.cardDataGuardMaxScanBytes()).thenReturn(16);
    body("{\"a\":\"xxxxxxxxxx\",\"note\":\"4111111111111111\"}");
    filter.filter(ctx);
    verify(ctx, never()).abortWith(any());
    when(config.cardDataGuardMaxScanBytes()).thenReturn(8 * 1024 * 1024);
    body("{\"a\":\"xxxxxxxxxx\",\"note\":\"4111111111111111\"}");
    filter.filter(ctx);
    assertEquals(400, refused().getStatus());
  }

  @Test
  @DisplayName("Switched off, it refuses nothing — the setting exists to prove the guard is why")
  void disabledDoesNothing() throws IOException {
    when(config.cardDataGuardEnabled()).thenReturn(false);
    body("{\"note\":\"4111111111111111\"}");
    filter.filter(ctx);
    verify(ctx, never()).abortWith(any());
    verify(ctx, never()).getEntityStream();
  }

  @Test
  @DisplayName("Two ids whose digit groups line up as a card, and a role, are a staff assignment")
  void letsIdsThroughWhateverTheirDigitsAddUpTo() throws IOException {
    // SJ-D67: 5100 7069 8187 0918 is Luhn-valid in Mastercard's range; the id is not a card.
    String json =
        "{\"userId\":\"019993e0-5100-7069-8187-0918e7284e47\","
            + "\"storeId\":\"01998bcd-4236-7684-9322-3069a2373271\",\"role\":\"CASHIER\"}";
    body(json);
    filter.filter(ctx);
    verify(ctx, never()).abortWith(any());
  }
}
