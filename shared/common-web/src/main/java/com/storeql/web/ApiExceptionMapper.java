package com.storeql.web;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Maps {@link ApiException} to the standard {@link ApiResponse} envelope with the intended HTTP
 * status. Registered automatically via {@code @Provider} (Helidon MP scans it).
 */
@Provider
public class ApiExceptionMapper implements ExceptionMapper<ApiException> {

  private static final Logger LOG = Logger.getLogger(ApiExceptionMapper.class.getName());

  /**
   * @param ex the API exception thrown by service-layer code
   * @return a response with {@code ex}'s status and an {@link ApiResponse#error} envelope
   */
  @Override
  public Response toResponse(ApiException ex) {
    // A refusal (4xx) is the caller's answer and is not logged. A 5xx says this service could not
    // do its job — a peer down, a provider unavailable — and is logged once, with the cause, so the
    // reason survives the problem body that the caller sees.
    if (ex.status() >= 500) {
      LOG.log(Level.WARNING, ex.status() + " " + ex.code() + ": " + ex.getMessage(), ex.getCause());
    }
    return Response.status(ex.status())
        .type("application/json")
        .entity(ApiResponse.error(ex.toErrorBody()))
        .build();
  }
}
