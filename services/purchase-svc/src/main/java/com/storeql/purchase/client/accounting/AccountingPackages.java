package com.storeql.purchase.client.accounting;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Every package the platform can push to, by provider code. */
@ApplicationScoped
public class AccountingPackages {

  @Inject Instance<AccountingPackage> discovered;

  private final Map<String, AccountingPackage> byProvider = new HashMap<>();

  @PostConstruct
  void init() {
    for (AccountingPackage p : discovered) {
      byProvider.put(p.provider(), p);
    }
  }

  public Optional<AccountingPackage> forProvider(String provider) {
    return Optional.ofNullable(byProvider.get(provider));
  }
}
