package com.storeql.payment.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The enabled-methods cache is bounded: it never holds more than its configured size. */
class TenantStoreClientCacheTest {

  @Test
  @DisplayName("The cache keeps at most its configured number of stores, least recently used out")
  void theCacheIsBounded() {
    AtomicInteger reads = new AtomicInteger();
    TenantStoreClient client =
        new TenantStoreClient() {
          @Override
          Optional<Set<String>> fetch(UUID tenantId, UUID storeId) {
            reads.incrementAndGet();
            return Optional.of(Set.of("CASH"));
          }
        };
    client.cacheMaxEntries = 3;
    UUID tenant = Ids.newId();
    UUID first = Ids.newId();

    client.enabledMethods(tenant, first);
    for (int i = 0; i < 10; i++) client.enabledMethods(tenant, Ids.newId());

    assertThat("never more than the bound", client.cacheSize(), is(3));
    int before = reads.get();
    client.enabledMethods(tenant, first);
    assertThat("the oldest was evicted, so it is read again", reads.get(), is(before + 1));
  }

  @Test
  @DisplayName("A second ask inside the TTL is answered from the cache")
  void aSecondAskIsCached() {
    AtomicInteger reads = new AtomicInteger();
    TenantStoreClient client =
        new TenantStoreClient() {
          @Override
          Optional<Set<String>> fetch(UUID tenantId, UUID storeId) {
            reads.incrementAndGet();
            return Optional.of(Set.of("CASH"));
          }
        };
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    client.enabledMethods(tenant, store);
    client.enabledMethods(tenant, store);
    assertThat(reads.get(), is(1));
  }
}
