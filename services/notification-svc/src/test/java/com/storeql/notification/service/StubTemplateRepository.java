package com.storeql.notification.service;

import com.storeql.notification.repo.TemplateRepository;
import com.storeql.notification.template.TemplateStore;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link TemplateRepository} for tests that never touches Postgres: no business has written any
 * settings, so {@link MessageTemplateService} falls back to the platform's defaults (English,
 * signed by {@link Businesses#name}). {@code live}/{@code liveAll}/{@code history} are not
 * overridden — a test that needs them wants the real, Postgres-backed IT instead.
 */
public final class StubTemplateRepository extends TemplateRepository {

  @Override
  public Optional<TemplateStore.Settings> settings(UUID tenantId) {
    return Optional.empty();
  }
}
