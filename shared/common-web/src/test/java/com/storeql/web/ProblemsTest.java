package com.storeql.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Errors leave as RFC 9457 problem details: a URN type from the code, the code in words as the
 * title, the HTTP status, the message as detail, the request path as instance — and the legacy
 * envelope members beside them. Responses carrying data are untouched.
 */
class ProblemsTest {

  @Test
  void aProblemCarriesTheStandardMembersAndTheLegacyOnes() {
    Problem p =
        Problems.of(
            409,
            ErrorBody.of("PAYMENT_CASH_LIMIT_EXCEEDED", "cash of EUR 1000.00 or more is refused"),
            "/api/payment-svc/payments",
            "req-1");
    assertEquals("urn:storeql:problem:PAYMENT_CASH_LIMIT_EXCEEDED", p.type());
    assertEquals("Payment cash limit exceeded", p.title());
    assertEquals(409, p.status());
    assertEquals("cash of EUR 1000.00 or more is refused", p.detail());
    assertEquals("/api/payment-svc/payments", p.instance());
    assertEquals("PAYMENT_CASH_LIMIT_EXCEEDED", p.code());
    assertEquals("req-1", p.requestId());
    assertEquals("PAYMENT_CASH_LIMIT_EXCEEDED", p.error().code());
    assertEquals("req-1", p.meta().requestId());
  }

  @Test
  void theTitleIsTheCodeInWordsAndABlankCodeIsStillAProblem() {
    assertEquals("Validation failed", Problems.titleOf("VALIDATION_FAILED"));
    assertEquals(
        "Order container not in scheme", Problems.titleOf("ORDER_CONTAINER_NOT_IN_SCHEME"));
    assertEquals("Error", Problems.titleOf("  "));
    Problem p = Problems.of(500, new ErrorBody("", "boom", List.of("a")), null, null);
    assertEquals("urn:storeql:problem:ERROR", p.type());
    assertNull(p.instance());
    assertNull(p.meta());
    assertEquals(List.of("a"), p.details());
  }

  @Test
  void theFilterConvertsAnErrorEnvelopeAndLeavesDataAlone() {
    ContainerRequestContext req = mock(ContainerRequestContext.class);
    UriInfo uri = mock(UriInfo.class);
    when(uri.getRequestUri()).thenReturn(URI.create("http://x/api/order-svc/orders/1"));
    when(req.getUriInfo()).thenReturn(uri);
    when(req.getHeaderString(HttpHeaders.REQUEST_ID)).thenReturn("req-9");
    ContainerResponseContext res = mock(ContainerResponseContext.class);
    when(res.getEntity())
        .thenReturn(ApiResponse.error(ErrorBody.of("ORDER_NOT_FOUND", "order not found")));
    when(res.getStatus()).thenReturn(404);
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
    when(res.getHeaders()).thenReturn(headers);

    new ProblemResponseFilter().filter(req, res);

    ArgumentCaptor<Object> entity = ArgumentCaptor.forClass(Object.class);
    verify(res).setEntity(entity.capture(), any(), eq(Problems.PROBLEM_JSON));
    Problem p = (Problem) entity.getValue();
    assertEquals(404, p.status());
    assertEquals("/api/order-svc/orders/1", p.instance());
    assertEquals("Order not found", p.title());
    assertEquals("req-9", p.requestId());
    assertEquals(Problems.MEDIA_TYPE, headers.getFirst("Content-Type"));
    assertEquals(
        "ORDER_NOT_FOUND",
        headers.getFirst(HttpHeaders.ERROR_CODE),
        "the code is also a header, so the gateway can record it without reading the body");

    ContainerResponseContext ok = mock(ContainerResponseContext.class);
    when(ok.getEntity()).thenReturn(ApiResponse.ok("fine"));
    new ProblemResponseFilter().filter(req, ok);
    verify(ok, never()).setEntity(any(), any(), any());
  }
}
