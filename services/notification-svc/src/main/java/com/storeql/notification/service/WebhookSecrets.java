package com.storeql.notification.service;

import com.storeql.service.SealedSecrets;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The webhook signing secrets at rest (22.6): sealed under {@code storeql.webhooks.secrets-key},
 * this service's own key, so the key that opens an e-invoice provider's secret elsewhere opens
 * nothing here. A secret must be opened to sign each delivery, which is why it is sealed and not
 * hashed like a token.
 */
@ApplicationScoped
public class WebhookSecrets extends SealedSecrets {

  @Inject
  @ConfigProperty(name = "storeql.webhooks.secrets-key")
  Optional<String> webhooksKey;

  @PostConstruct
  void initWebhooks() {
    useKey(webhooksKey == null ? null : webhooksKey.filter(k -> !k.isBlank()).orElse(null));
  }
}
