package com.storeql.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A 5xx answer leaves its cause in the log; a refusal does not. */
class ApiExceptionMapperLoggingTest {

  private final Logger logger = Logger.getLogger(ApiExceptionMapper.class.getName());
  private final List<LogRecord> records = new ArrayList<>();
  private final Handler capture =
      new Handler() {
        @Override
        public void publish(LogRecord r) {
          records.add(r);
        }

        @Override
        public void flush() {
          // nothing buffered
        }

        @Override
        public void close() {
          // nothing to release
        }
      };

  @BeforeEach
  void listen() {
    logger.addHandler(capture);
  }

  @AfterEach
  void stop() {
    logger.removeHandler(capture);
  }

  @Test
  @DisplayName("A dependency that could not answer is logged once, with its cause")
  void aServerSideFailureIsLoggedWithItsCause() {
    IOException cause = new IOException("connection reset by peer");
    ApiException ex =
        new ApiException(
            503, "ORDER_PRICING_UNAVAILABLE", "pricing-svc call failed", List.of(), cause);

    int status = new ApiExceptionMapper().toResponse(ex).getStatus();

    assertEquals(503, status);
    assertEquals(1, records.size());
    assertEquals(Level.WARNING, records.get(0).getLevel());
    assertTrue(records.get(0).getMessage().contains("ORDER_PRICING_UNAVAILABLE"));
    assertSame(cause, records.get(0).getThrown());
  }

  @Test
  @DisplayName("A refusal is the caller's answer and leaves nothing in the log")
  void aRefusalIsNotLogged() {
    new ApiExceptionMapper()
        .toResponse(
            ApiException.badRequest("SOMETHING_INVALID", "the caller sent something wrong"));
    new ApiExceptionMapper().toResponse(ApiException.notFound("NOT_FOUND", "no such thing"));

    assertEquals(0, records.size());
  }
}
