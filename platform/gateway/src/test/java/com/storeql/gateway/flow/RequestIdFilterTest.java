package com.storeql.gateway.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.web.HttpHeaders;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The request id is minted first, by the gateway, and nobody else's value is ever used. */
class RequestIdFilterTest {

  private static ContainerRequestContext request(MultivaluedMap<String, String> headers) {
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);
    when(ctx.getHeaders()).thenReturn(headers);
    return ctx;
  }

  @Test
  @DisplayName("A client-supplied X-Request-Id is overwritten, never trusted or echoed")
  void clientValueIsOverwritten() {
    MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
    headers.putSingle(HttpHeaders.REQUEST_ID, "attacker-chosen\r\nSet-Cookie: x=1");
    ContainerRequestContext ctx = request(headers);

    new RequestIdFilter().filter(ctx);

    String id = headers.getFirst(HttpHeaders.REQUEST_ID);
    assertNotEquals("attacker-chosen\r\nSet-Cookie: x=1", id);
    assertEquals(1, headers.get(HttpHeaders.REQUEST_ID).size(), "one value, not two");
    assertTrue(Ids.isV7(Ids.parse(id)), "a UUIDv7 minted here: " + id);
    verify(ctx).setProperty(FlowAttributes.REQUEST_ID, id);
  }

  @Test
  @DisplayName("A request with no id gets one, and every request gets its own")
  void everyRequestGetsItsOwn() {
    MultivaluedMap<String, String> first = new MultivaluedHashMap<>();
    MultivaluedMap<String, String> second = new MultivaluedHashMap<>();
    new RequestIdFilter().filter(request(first));
    new RequestIdFilter().filter(request(second));
    assertFalse(first.getFirst(HttpHeaders.REQUEST_ID).isBlank());
    assertNotEquals(
        first.getFirst(HttpHeaders.REQUEST_ID), second.getFirst(HttpHeaders.REQUEST_ID));
  }

  @Test
  @DisplayName(
      "The arrival time is noted for the duration, in nanoseconds from the monotonic clock")
  void arrivalIsNoted() {
    ContainerRequestContext ctx = request(new MultivaluedHashMap<>());
    long before = System.nanoTime();
    new RequestIdFilter().filter(ctx);
    ArgumentCaptor<Object> started = ArgumentCaptor.forClass(Object.class);
    verify(ctx)
        .setProperty(
            org.mockito.ArgumentMatchers.eq(FlowAttributes.STARTED_NANOS), started.capture());
    long at = (Long) started.getValue();
    assertTrue(at - before >= 0 && at - before < 5_000_000_000L);
  }

  @Test
  @DisplayName("It runs before everything, the CORS preflight included")
  void runsFirst() throws Exception {
    assertTrue(RequestIdFilter.class.isAnnotationPresent(PreMatching.class));
    int mine = RequestIdFilter.class.getAnnotation(jakarta.annotation.Priority.class).value();
    int cors =
        com.storeql.gateway.filters.CorsFilter.class
            .getAnnotation(jakarta.annotation.Priority.class)
            .value();
    assertTrue(mine < cors, "id filter " + mine + " must come before CORS " + cors);
  }

  @Test
  @DisplayName("The recording filter runs last on the way out, after CORS and the security headers")
  void recordsLast() {
    int mine = FlowRecordingFilter.class.getAnnotation(jakarta.annotation.Priority.class).value();
    for (Class<?> other :
        new Class<?>[] {
          com.storeql.gateway.filters.CorsFilter.class,
          com.storeql.gateway.filters.SecurityHeadersFilter.class,
          com.storeql.gateway.filters.ApiVersionDeprecationFilter.class,
          com.storeql.gateway.filters.BruteForceFilter.class,
          com.storeql.web.ProblemResponseFilter.class
        }) {
      int theirs = other.getAnnotation(jakarta.annotation.Priority.class).value();
      assertTrue(
          mine < theirs,
          "response filters run in descending priority, so "
              + mine
              + " runs after "
              + other.getSimpleName()
              + " "
              + theirs);
    }
  }
}
