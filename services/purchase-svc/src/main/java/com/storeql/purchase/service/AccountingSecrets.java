package com.storeql.purchase.service;

import com.storeql.purchase.domain.Accounting.Credentials;
import com.storeql.service.SealedSecrets;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.time.Instant;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The tokens an accounting package issued, sealed at rest under a key of their own (17.9): a
 * business's books are reachable with them, so they are kept the way a webhook secret or an
 * identity provider's client secret is — never in the clear, never in a log, never on the wire
 * again.
 */
@ApplicationScoped
@Typed(AccountingSecrets.class)
public class AccountingSecrets extends SealedSecrets {

  @Inject
  @ConfigProperty(name = "storeql.accounting.secrets-key")
  Optional<String> accountingKey;

  @PostConstruct
  void initAccounting() {
    useKey(accountingKey == null ? null : accountingKey.filter(k -> !k.isBlank()).orElse(null));
  }

  public String sealCredentials(Credentials c) {
    JsonObjectBuilder b = Json.createObjectBuilder().add("accessToken", c.accessToken());
    if (c.refreshToken() != null) b.add("refreshToken", c.refreshToken());
    if (c.clientId() != null) b.add("clientId", c.clientId());
    if (c.clientSecret() != null) b.add("clientSecret", c.clientSecret());
    if (c.expiresAt() != null) b.add("expiresAt", c.expiresAt().toString());
    return seal(b.build().toString());
  }

  public Credentials openCredentials(String sealed) {
    try (JsonReader reader = Json.createReader(new StringReader(open(sealed)))) {
      JsonObject o = reader.readObject();
      return new Credentials(
          o.getString("accessToken"),
          o.getString("refreshToken", null),
          o.getString("clientId", null),
          o.getString("clientSecret", null),
          o.containsKey("expiresAt") ? Instant.parse(o.getString("expiresAt")) : null);
    }
  }
}
