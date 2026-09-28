package com.storeql.inventory.api;

import com.storeql.ids.Ids;
import com.storeql.ids.Ids.InvalidIdException;
import com.storeql.web.TenantContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Holds a store-scoped caller to the stores they keep, on every route that names a store (SJ-D74).
 *
 * <p>A login assigned to a store, and an API key minted for one, carry the store on the token, and
 * {@code TenantContext.requireStoreAccess} is how a route honours it. Until this filter, only
 * recalls and food-safety records asked: goods could be booked in, adjusted, listed and moved at
 * any store in the business on a login for one branch, which is the shrink hole store assignment
 * exists to close. The rule is one and belongs at the door of the service, so it is asked once here
 * for every store a request names: the {@code store} or {@code storeId} query parameter, and in a
 * JSON body {@code storeId}, {@code fromStoreId} and each {@code items[].storeId}. A transfer's
 * {@code toStoreId} is not the sender's store to keep — the receiving store's keeper is held to it
 * at receipt, in the resource — and routes that name a store only by an order or batch id hold the
 * caller in the resource too, where the store is known. A caller assigned to no store (an owner, a
 * manager of the whole business, a service) is held to none, and a value that is not an id is left
 * for the resource's own 400.
 */
@Provider
@Priority(Priorities.AUTHORIZATION + 10)
public class StoreScopeFilter implements ContainerRequestFilter {

  /** Bodies that name a store are small; anything larger is passed through unread. */
  static final int MAX_INSPECTED_BYTES = 1 << 20;

  private static final List<String> QUERY_NAMES = List.of("store", "storeId");

  @Inject TenantContext ctx;

  @Override
  public void filter(ContainerRequestContext request) throws IOException {
    if (ctx.storeIds().isEmpty()) {
      return;
    }
    var query = request.getUriInfo().getQueryParameters();
    for (String name : QUERY_NAMES) {
      hold(query.getFirst(name));
    }
    MediaType type = request.getMediaType();
    if (request.hasEntity() && type != null && MediaType.APPLICATION_JSON_TYPE.isCompatible(type)) {
      byte[] body = request.getEntityStream().readAllBytes();
      request.setEntityStream(new ByteArrayInputStream(body));
      if (body.length <= MAX_INSPECTED_BYTES) {
        inspect(body);
      }
    }
  }

  private void inspect(byte[] body) {
    JsonValue value;
    try (JsonReader reader = Json.createReader(new ByteArrayInputStream(body))) {
      value = reader.readValue();
    } catch (JsonException | IllegalStateException e) {
      return; // not JSON after all: the resource answers that
    }
    if (value.getValueType() != JsonValue.ValueType.OBJECT) {
      return;
    }
    JsonObject object = value.asJsonObject();
    holdField(object, "storeId");
    holdField(object, "fromStoreId");
    JsonValue items = object.get("items");
    if (items != null && items.getValueType() == JsonValue.ValueType.ARRAY) {
      for (JsonValue item : items.asJsonArray()) {
        if (item.getValueType() == JsonValue.ValueType.OBJECT) {
          holdField(item.asJsonObject(), "storeId");
        }
      }
    }
  }

  private void holdField(JsonObject object, String name) {
    JsonValue v = object.get(name);
    if (v != null && v.getValueType() == JsonValue.ValueType.STRING) {
      hold(((JsonString) v).getString());
    }
  }

  private void hold(String text) {
    if (text == null || text.isBlank()) {
      return;
    }
    UUID storeId;
    try {
      storeId = Ids.parse(text);
    } catch (InvalidIdException e) {
      return; // the resource's 400 INVALID_UUID, as before
    }
    ctx.requireStoreAccess(storeId);
  }
}
