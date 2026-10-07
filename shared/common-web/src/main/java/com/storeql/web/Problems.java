package com.storeql.web;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.Locale;

/** Builds RFC 9457 {@link Problem problem details} from the platform's error envelope. */
public final class Problems {

  /** The media type of every error response (RFC 9457 §3). */
  public static final String MEDIA_TYPE = "application/problem+json";

  public static final MediaType PROBLEM_JSON = MediaType.valueOf(MEDIA_TYPE);

  /** The prefix of every problem type: the stable code, as a URN nobody has to resolve. */
  public static final String TYPE_PREFIX = "urn:storeql:problem:";

  private Problems() {}

  /**
   * A problem for an error envelope.
   *
   * @param status the HTTP status
   * @param error the envelope's error
   * @param instance the request path, or null
   * @param requestId the correlation id, or null
   * @return the problem, its legacy members carried along
   */
  public static Problem of(int status, ErrorBody error, String instance, String requestId) {
    return of(status, error, instance, requestId, null);
  }

  /** A problem for an error envelope that carried metadata. */
  public static Problem of(
      int status, ErrorBody error, String instance, String requestId, ApiResponse.Meta meta) {
    String code = error.code() == null || error.code().isBlank() ? "ERROR" : error.code();
    String rid = requestId != null ? requestId : meta == null ? null : meta.requestId();
    return new Problem(
        TYPE_PREFIX + code,
        titleOf(code),
        status,
        error.message(),
        instance,
        code,
        error.details(),
        rid,
        error,
        meta == null && rid == null ? null : meta != null ? meta : ApiResponse.Meta.of(rid));
  }

  /** A problem response for an error, typed {@code application/problem+json}. */
  public static Response.ResponseBuilder response(
      int status, ErrorBody error, String instance, String requestId) {
    return Response.status(status).type(MEDIA_TYPE).entity(of(status, error, instance, requestId));
  }

  private static final java.util.regex.Pattern WORD_BREAK =
      java.util.regex.Pattern.compile("[_\\s]+");

  /** The code in words: {@code PAYMENT_CASH_LIMIT_EXCEEDED} reads "Payment cash limit exceeded". */
  public static String titleOf(String code) {
    String[] words = WORD_BREAK.split(code.trim());
    StringBuilder b = new StringBuilder();
    for (String w : words) {
      if (w.isEmpty()) continue;
      String lower = w.toLowerCase(Locale.ROOT);
      if (b.length() == 0) {
        b.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
      } else {
        b.append(' ').append(lower);
      }
    }
    return b.length() == 0 ? "Error" : b.toString();
  }

  /** The request path for {@code instance}, or null when there is no request. */
  public static String instanceOf(UriInfo uriInfo) {
    if (uriInfo == null) return null;
    try {
      return uriInfo.getRequestUri().getPath();
    } catch (RuntimeException e) {
      return null;
    }
  }
}
