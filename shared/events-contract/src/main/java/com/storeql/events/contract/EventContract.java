package com.storeql.events.contract;

import java.util.function.Function;

/**
 * One event on the shared catalogue: its type, its topic, whether it names a business, and the
 * reader that parses its payload. The builder is the event class's own {@code payload(...)} (its
 * arguments differ per event, so it cannot be a member here).
 *
 * @param topicTemplate {@code storeql.<domain>.<event>}; a few events are published by several
 *     services and carry {@code {service}} in the domain position
 */
public record EventContract(
    String type, String topicTemplate, TenantScope tenantScope, Function<String, ?> reader) {

  public static final String SERVICE_PLACEHOLDER = "{service}";

  public boolean perService() {
    return topicTemplate.contains(SERVICE_PLACEHOLDER);
  }

  /** The topic of an event published by one service only. */
  public String topic() {
    if (perService()) {
      throw new IllegalStateException(type + " is published by several services: use topicFor");
    }
    return topicTemplate;
  }

  /** The topic a given service publishes this event on, e.g. {@code inventory}. */
  public String topicFor(String service) {
    return topicTemplate.replace(SERVICE_PLACEHOLDER, service);
  }

  /** Parses a payload of this type with its reader. */
  public Object read(String json) {
    return reader.apply(json);
  }
}
