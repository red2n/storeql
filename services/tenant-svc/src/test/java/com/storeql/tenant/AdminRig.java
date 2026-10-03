package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import java.io.StringReader;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * What the admin integration tests share: a business with an owner and a store, callers of every
 * kind (the way the gateway stamps them: tenant, user, roles, stores, permissions), and answers
 * read as JSON. Every call goes through the JDK client so PATCH works.
 */
final class AdminRig {

  private AdminRig() {}

  /** A caller as the gateway would stamp it. Stores and permissions may be null. */
  record Who(String tenant, String user, String roles, String stores, String permissions) {

    Who withStores(String stores) {
      return new Who(tenant, user, roles, stores, permissions);
    }

    Who withPermissions(String permissions) {
      return new Who(tenant, user, roles, stores, permissions);
    }
  }

  /** One answer. */
  record Answer(int status, String body) {

    JsonObject json() {
      try (var r = Json.createReader(new StringReader(body))) {
        return r.readObject();
      }
    }

    JsonObject data() {
      return json().getJsonObject("data");
    }

    JsonArray list() {
      return json().getJsonArray("data");
    }

    /** The stable machine code of a refusal, or an empty string. */
    String code() {
      try {
        JsonObject j = json();
        if (j.containsKey("code") && !j.isNull("code")) return j.getString("code");
        JsonObject err =
            j.containsKey("error") && !j.isNull("error") ? j.getJsonObject("error") : null;
        return err != null && err.containsKey("code") ? err.getString("code") : "";
      } catch (RuntimeException e) {
        return "";
      }
    }

    /** {@code meta.nextCursor}, or null. */
    String next() {
      JsonObject meta = json().getJsonObject("meta");
      return meta != null && meta.containsKey("nextCursor") && !meta.isNull("nextCursor")
          ? meta.getString("nextCursor")
          : null;
    }

    String text() {
      return status + " " + body;
    }
  }

  /** A business: its id, its owner's login and its first store. */
  record Biz(String tenant, String ownerId, String store) {

    Who owner() {
      return new Who(tenant, ownerId, "OWNER", null, null);
    }

    Who manager() {
      return new Who(tenant, Ids.newId().toString(), "MANAGER", null, null);
    }

    Who manager(String... stores) {
      return new Who(tenant, Ids.newId().toString(), "MANAGER", String.join(",", stores), null);
    }

    Who as(String role) {
      return new Who(tenant, Ids.newId().toString(), role, null, null);
    }
  }

  static Answer call(WebTarget target, String method, String pathAndQuery, String json, Who who) {
    try {
      HttpRequest.Builder b =
          HttpRequest.newBuilder(target.getUri().resolve(pathAndQuery))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json");
      if (who.tenant() != null) b.header("X-Tenant-Id", who.tenant());
      if (who.user() != null) b.header("X-User-Id", who.user());
      if (who.roles() != null) b.header("X-Roles", who.roles());
      if (who.stores() != null) b.header("X-Store-Ids", who.stores());
      if (who.permissions() != null) b.header("X-Permissions", who.permissions());
      b.method(
          method,
          json == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json));
      HttpResponse<String> r =
          HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
      return new Answer(r.statusCode(), r.body());
    } catch (java.io.IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  static String q(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** A business with its owner and one store. */
  static Biz biz(WebTarget target, String name, String country, String currency) {
    String owner = Ids.newId().toString();
    Answer t =
        call(
            target,
            "POST",
            "/onboarding/tenants",
            "{\"businessName\":\""
                + name
                + " "
                + Ids.newId()
                + "\",\"country\":\""
                + country
                + "\",\"currency\":\""
                + currency
                + "\"}",
            new Who(null, owner, null, null, null));
    assertThat(t.text(), t.status(), is(201));
    String tenant = t.data().getString("id");
    Biz b = new Biz(tenant, owner, null);
    String store = addStore(target, b, "Main", country);
    return new Biz(tenant, owner, store);
  }

  /** A store added by the owner, its id. The zone is the caller's to choose; UTC is not assumed. */
  static String addStore(WebTarget target, Biz biz, String name, String country) {
    Answer s =
        call(
            target,
            "POST",
            "/admin/stores",
            "{\"name\":\""
                + name
                + "\",\"code\":\"S-"
                + tail()
                + "\",\"line1\":\"1 Main Road\",\"country\":\""
                + country
                + "\",\"city\":\"Town\",\"pincode\":\"10001\",\"timezone\":\"Europe/Paris\"}",
            biz.owner());
    assertThat(s.text(), s.status(), is(201));
    return s.data().getString("id");
  }

  static Answer assign(WebTarget target, Who caller, String user, String store, String role) {
    return call(
        target,
        "POST",
        "/admin/staff",
        "{\"userId\":\"" + user + "\",\"storeId\":\"" + store + "\",\"role\":\"" + role + "\"}",
        caller);
  }

  /** Every entry of the audit log a caller can read, walked page by page (newest first). */
  static List<JsonObject> audit(WebTarget target, Who who, String query) {
    List<JsonObject> out = new ArrayList<>();
    String after = null;
    do {
      String path =
          "/admin/tenant/audit?limit=100"
              + (query == null || query.isEmpty() ? "" : "&" + query)
              + (after == null ? "" : "&after=" + q(after));
      Answer a = call(target, "GET", path, null, who);
      assertThat(a.text(), a.status(), is(200));
      a.list().forEach(v -> out.add(v.asJsonObject()));
      after = a.next();
    } while (after != null);
    return out;
  }

  /** A fresh code fragment: the tail of a new id (its head is a timestamp, which repeats). */
  static String tail() {
    String id = Ids.newId().toString();
    return id.substring(id.length() - 12);
  }
}
