package com.storeql.iam.service;

import com.storeql.iam.repo.RefreshTokenRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Deletes refresh tokens that expired more than the retention ago, once an interval, in batches, so
 * the table does not grow with every sign-in and refresh for ever. Operational data only: a token
 * is no business history. The first run waits one interval.
 */
@ApplicationScoped
public class RefreshTokenPurge {

  private static final System.Logger LOG = System.getLogger(RefreshTokenPurge.class.getName());

  @Inject RefreshTokenRepository tokens;

  @Inject
  @ConfigProperty(name = "storeql.iam.refresh-token-purge.enabled", defaultValue = "true")
  boolean enabled;

  @Inject
  @ConfigProperty(name = "storeql.iam.refresh-token-purge.retention-days", defaultValue = "7")
  int retentionDays;

  @Inject
  @ConfigProperty(name = "storeql.iam.refresh-token-purge.interval-seconds", defaultValue = "86400")
  long intervalSeconds;

  @Inject
  @ConfigProperty(name = "storeql.iam.refresh-token-purge.batch-size", defaultValue = "5000")
  int batchSize;

  private ScheduledExecutorService scheduler;

  /** Forces CDI to create this bean at start-up. */
  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {}

  @PostConstruct
  void start() {
    if (!enabled) return;
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "refresh-token-purge");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        this::runQuietly, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
  }

  /** One purge: batches until a batch comes back short. */
  public int runQuietly() {
    int total = 0;
    try {
      int n;
      do {
        n = tokens.purgeExpired(retentionDays, Math.max(1, batchSize));
        total += n;
      } while (n >= Math.max(1, batchSize));
      if (total > 0) LOG.log(System.Logger.Level.INFO, "refresh tokens purged: {0}", total);
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.WARNING, "refresh-token purge deferred: {0}", e.getMessage());
    }
    return total;
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
  }
}
