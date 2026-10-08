package com.storeql.gateway.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What counts as a failure is decided in one place, and these are its edges. */
class FlowOutcomeTest {

  @ParameterizedTest
  @ValueSource(ints = {100, 101, 200, 201, 202, 204, 206, 301, 302, 303, 304, 307, 308})
  @DisplayName("Informational, success and redirect answers are ok")
  void successesAreOk(int status) {
    assertEquals(FlowOutcome.OK, FlowOutcome.of(status));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 402, 404, 405, 406, 408, 409, 410, 412, 415, 418, 422, 423, 428, 451})
  @DisplayName("A normal business answer in the 4xx range is a client error, not a failure")
  void ordinaryClientErrorsAreNotFailures(int status) {
    assertEquals(FlowOutcome.CLIENT, FlowOutcome.of(status));
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 413, 429})
  @DisplayName("A refused request (bad token, no right, too big, too many) is a failure")
  void refusedRequestsAreFailures(int status) {
    assertEquals(FlowOutcome.FAILED, FlowOutcome.of(status));
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 501, 502, 503, 504, 507, 599})
  @DisplayName("Every 5xx is a failure")
  void serverErrorsAreFailures(int status) {
    assertEquals(FlowOutcome.FAILED, FlowOutcome.of(status));
  }

  @Test
  @DisplayName("The edges: 399 is ok, 400 is a client error, 499 a client error, 500 a failure")
  void theEdges() {
    assertEquals(FlowOutcome.OK, FlowOutcome.of(399));
    assertEquals(FlowOutcome.CLIENT, FlowOutcome.of(400));
    assertEquals(FlowOutcome.CLIENT, FlowOutcome.of(499));
    assertEquals(FlowOutcome.FAILED, FlowOutcome.of(500));
  }

  @Test
  @DisplayName("The label is what the counters' field names and the screen read")
  void labelsAreStable() {
    assertEquals("ok", FlowOutcome.OK.label());
    assertEquals("client", FlowOutcome.CLIENT.label());
    assertEquals("failed", FlowOutcome.FAILED.label());
    assertEquals(FlowOutcome.FAILED, FlowOutcome.ofLabel("failed").orElseThrow());
    assertTrue(FlowOutcome.ofLabel("nonsense").isEmpty());
    assertTrue(FlowOutcome.ofLabel(null).isEmpty());
  }
}
