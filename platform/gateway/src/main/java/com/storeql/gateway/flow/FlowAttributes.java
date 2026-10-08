package com.storeql.gateway.flow;

import com.storeql.ids.Ids;

/**
 * The request properties the flow recording shares between filters. Properties, not headers: a
 * header is the client's until {@code JwtAuthFilter} has replaced it, and a request refused before
 * that point (rate limit, unknown API version, preflight) still carries whatever the client wrote
 * there. A property can only be set by code on this side of the door.
 */
public final class FlowAttributes {

  private FlowAttributes() {}

  /** The id minted for this request before any other filter ran ({@code String}). */
  public static final String REQUEST_ID = "storeql.flow.request-id";

  /** {@code System.nanoTime()} when the request arrived ({@code Long}). */
  public static final String STARTED_NANOS = "storeql.flow.started-nanos";

  /**
   * The business the request is for, set by {@code JwtAuthFilter} only once it has verified a token
   * or API key or checked the storefront the request names ({@code String}). Absent for an
   * anonymous request and for one that failed authentication.
   */
  public static final String TENANT_ID = "storeql.flow.tenant-id";

  /** The verified caller: the token's subject, or the API key's id ({@code String}). */
  public static final String USER_ID = "storeql.flow.user-id";

  /**
   * An id read from a property, in the one form it is stored under.
   *
   * @param value what a filter set: a string, or nothing
   * @return the canonical lowercase UUIDv7 text, or null when it is not an id (so it names nobody)
   */
  public static String canonicalId(Object value) {
    if (!(value instanceof String text)) return null;
    try {
      return Ids.parse(text.trim()).toString();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
