package com.storeql.reporting.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Subscribes to the catalogue events that place each variant in a category (19.x, sales by
 * category). Delegates dispatch to {@link CatalogueEventDispatcher}.
 */
@ApplicationScoped
class CatalogueEventConsumer extends BaseKafkaConsumer {

  private static final List<String> TOPICS =
      List.of("storeql.catalog.product-categorised", "storeql.catalog.variant-created");

  @Inject CatalogueEventDispatcher dispatcher;

  @Override
  protected List<String> topics() {
    return TOPICS;
  }

  @Override
  protected String consumerName() {
    return "reporting-catalogue-event-consumer";
  }

  @Override
  protected String groupId() {
    return "reporting-svc-catalogue";
  }

  @Override
  protected void handle(String topic, String value) {
    dispatcher.dispatch(topic, value);
  }
}
