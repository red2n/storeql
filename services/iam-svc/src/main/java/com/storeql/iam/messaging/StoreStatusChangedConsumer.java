package com.storeql.iam.messaging;

import com.storeql.service.BaseStoreStatusChangedConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Keeps iam-svc's local store-status projection current, so a POS session cannot be opened against
 * a store that has since closed or been suspended.
 */
@ApplicationScoped
class StoreStatusChangedConsumer extends BaseStoreStatusChangedConsumer {

  @Inject PosSessionStoreStatusHandler posSessionHandler;
  @Inject StoreTypeHandler storeTypeHandler;

  @Override
  protected String consumerName() {
    return "iam-store-status-changed-consumer";
  }

  @Override
  protected String groupId() {
    return "iam-svc-store-status";
  }

  /**
   * Updates the shared {@code store_status} projection (inherited handler), then dispatches to
   * {@link PosSessionStoreStatusHandler} to end active POS sessions at a store that just went
   * non-ACTIVE. Kafka lifecycle stays here; both pieces of business logic live in Handler beans
   * (SRP — see docs/coding-standards.md §2.1).
   */
  @Override
  protected void handle(String topic, String value) {
    super.handle(topic, value);
    posSessionHandler.handle(value);
    storeTypeHandler.handle(value);
  }
}
