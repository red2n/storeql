package com.storeql.reporting.service;

import com.storeql.reporting.client.PendingWorkClient;
import com.storeql.reporting.config.WaitingWorkRoutes;
import com.storeql.reporting.domain.PendingWork.Readings;
import com.storeql.reporting.domain.PendingWork.Report;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Function;

/**
 * The system-health screen's waiting work: asks the services that own each queue for their own
 * count (reporting-svc holds none of it) and assembles one answer with {@link WaitingWork}.
 *
 * <p>The answer is kept per business for a few seconds in {@link WaitingWorkCache}, so open
 * dashboards poll that rather than the owning services. Who may ask is judged by the caller before
 * this is reached; the cache holds no permission of its own.
 */
@ApplicationScoped
public class WaitingWorkService {

  @Inject PendingWorkClient client;
  @Inject WaitingWorkCache cache;

  private Clock clock = Clock.systemUTC();
  private Function<UUID, Readings> reader = this::read;

  /**
   * For tests: stand-ins for the three services and the clock, and the cache to keep reports in.
   */
  static WaitingWorkService forTest(
      Function<UUID, Readings> reader, Clock clock, WaitingWorkCache cache) {
    WaitingWorkService s = new WaitingWorkService();
    s.reader = reader;
    s.clock = clock;
    s.cache = cache;
    return s;
  }

  /**
   * @param tenantId the caller's business
   * @return one item per kind of waiting work; a source that could not be reached is null and named
   */
  public Report waitingWork(UUID tenantId) {
    return cache.get(
        tenantId,
        () ->
            WaitingWork.assemble(clock.instant(), reader.apply(tenantId), WaitingWorkRoutes.OPENS));
  }

  private Readings read(UUID tenantId) {
    return client.read(tenantId);
  }
}
