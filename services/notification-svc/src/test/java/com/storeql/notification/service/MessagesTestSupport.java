package com.storeql.notification.service;

import com.storeql.notification.template.Catalogue;
import com.storeql.notification.template.TemplateStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A {@link Messages} for tests, over templates held in memory. */
public final class MessagesTestSupport {

  private MessagesTestSupport() {}

  /** Templates and settings a test writes directly. */
  public static final class MemoryStore implements TemplateStore {
    public final Map<String, Stored> live = new HashMap<>();
    public Settings settings;

    public void put(
        String type, Catalogue.Form form, String language, String subject, String body) {
      int version =
          (int) live.values().stream().filter(s -> s.messageType().equals(type)).count() + 1;
      live.put(
          type + "/" + form + "/" + language,
          new Stored(
              com.storeql.ids.Ids.newId(),
              type,
              form,
              language,
              version,
              subject,
              body,
              java.time.Instant.now(),
              com.storeql.ids.Ids.newId(),
              null));
    }

    @Override
    public Optional<Stored> live(UUID tenantId, String type, Catalogue.Form form, String language) {
      return Optional.ofNullable(live.get(type + "/" + form + "/" + language));
    }

    @Override
    public Optional<Settings> settings(UUID tenantId) {
      return Optional.ofNullable(settings);
    }
  }

  /**
   * The platform's own words: no business has written any, and the business is "Hollins Grocers" in
   * GB.
   */
  public static Messages platformWords() {
    return with(new MemoryStore(), "GB", "Hollins Grocers");
  }

  public static Messages with(TemplateStore store, String country, String name) {
    Messages m = new Messages();
    m.store = store;
    m.businesses =
        new Businesses() {
          @Override
          public Optional<String> country(UUID tenantId) {
            return Optional.ofNullable(country);
          }

          @Override
          public Optional<String> name(UUID tenantId) {
            return Optional.ofNullable(name);
          }

          @Override
          public boolean sandbox(UUID tenantId) {
            return false;
          }
        };
    return m;
  }
}
