package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Onboards a business for a test, the way an owner signing up does, and returns its id. */
final class TenantOnboarding {

  private TenantOnboarding() {}

  /**
   * The address the owner signed up with, which is where the business's notices go until it says
   * otherwise.
   */
  static String ownerEmail(String name) {
    return name.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
        + "@example.test";
  }

  static String onboard(WebTarget target, String name, String country, String currency) {
    Response r =
        target
            .path("/onboarding/tenants")
            .request(MediaType.APPLICATION_JSON)
            .header("X-User-Id", Ids.newId().toString())
            .header("X-User-Email", ownerEmail(name))
            .post(
                Entity.entity(
                    "{\"businessName\":\""
                        + name
                        + " "
                        + Ids.newId()
                        + "\",\"country\":\""
                        + country
                        + "\",\"currency\":\""
                        + currency
                        + "\"}",
                    MediaType.APPLICATION_JSON));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    int i = body.indexOf("\"id\":\"") + 6;
    return body.substring(i, body.indexOf('"', i));
  }
}
