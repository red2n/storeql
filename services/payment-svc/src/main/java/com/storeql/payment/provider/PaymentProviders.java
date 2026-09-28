package com.storeql.payment.provider;

import com.storeql.payment.domain.Domain.PaymentIntent;
import com.storeql.service.TenantProfiles;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Resolves the configured {@link PaymentProvider}, and is the only place that decides which one is
 * in use.
 *
 * <p>Providers are discovered through CDI rather than listed here, so adding Razorpay means adding
 * an {@code @ApplicationScoped} class and nothing else. The active one comes from {@code
 * storeql.payment.provider} (golden rule #5: config is external, and credentials never live in code
 * or images).
 */
@ApplicationScoped
public class PaymentProviders {

  private static final Logger LOG = System.getLogger(PaymentProviders.class.getName());

  @Inject Instance<PaymentProvider> discovered;

  @Inject
  @ConfigProperty(name = "storeql.payment.provider", defaultValue = PaymentIntent.PROVIDER_MANUAL)
  String configuredProvider;

  @Inject TenantProfiles profiles;

  private final Map<String, PaymentProvider> byName = new HashMap<>();
  private PaymentProvider active;

  /**
   * Indexes every provider bean and resolves the configured one.
   *
   * <p>An unknown name fails startup rather than defaulting to MANUAL. Silently falling back would
   * mean a deployment that believes it is charging cards is quietly taking no money at all — the
   * exact defect this whole feature exists to fix, reintroduced by a typo.
   */
  @PostConstruct
  void init() {
    register(discovered);
  }

  /** For tests: these providers, this configuration, and tenant-svc as the profiles say. */
  public static PaymentProviders forTest(
      Iterable<PaymentProvider> providers, String configured, TenantProfiles profiles) {
    PaymentProviders p = new PaymentProviders();
    p.configuredProvider = configured;
    p.profiles = profiles;
    p.register(providers);
    return p;
  }

  private void register(Iterable<PaymentProvider> providers) {
    for (PaymentProvider p : providers) {
      byName.put(p.name().toUpperCase(Locale.ROOT), p);
    }
    String wanted =
        configuredProvider == null ? "" : configuredProvider.trim().toUpperCase(Locale.ROOT);
    active = byName.get(wanted);
    if (active == null) {
      throw new IllegalStateException(
          "storeql.payment.provider is '"
              + configuredProvider
              + "', which is not a known provider. Known: "
              + byName.keySet());
    }
    if (PaymentIntent.PROVIDER_MANUAL.equals(active.name())) {
      LOG.log(
          Level.WARNING,
          "payment-svc is running with the MANUAL provider: online payments are recorded but NO"
              + " money is authorised or captured. Set storeql.payment.provider for any environment"
              + " that takes real money.");
    } else {
      LOG.log(Level.INFO, "payment provider: {0}", active.name());
    }
  }

  /**
   * @return the provider this service is configured to use; never null after startup
   */
  public PaymentProvider active() {
    return active;
  }

  /**
   * The provider that takes this business's money (22.8): the configured one — except for a
   * sandbox, which is always MANUAL, so nothing a sandbox does authorises or captures a penny
   * however the stack is configured. A business whose profile cannot be read is not taken for a
   * sandbox; the payment path reads the profile for the currency anyway and fails closed there.
   */
  public PaymentProvider forTenant(UUID tenantId) {
    if (profiles != null && profiles.isSandbox(tenantId)) {
      PaymentProvider manual = byName.get(PaymentIntent.PROVIDER_MANUAL);
      if (manual != null) {
        return manual;
      }
      LOG.log(
          Level.WARNING,
          "No MANUAL provider registered; sandbox {0} pays through {1}",
          tenantId,
          active.name());
    }
    return active;
  }

  /**
   * Looks up a provider by name, for the webhook endpoint — a webhook names its own provider in the
   * path, and must be verified by that provider's implementation rather than by whichever one
   * happens to be active.
   *
   * @param name provider name, case-insensitive
   * @return the provider, or null if no such provider is deployed
   */
  public PaymentProvider forName(String name) {
    return name == null ? null : byName.get(name.trim().toUpperCase(Locale.ROOT));
  }

  /**
   * @return {@code true} if no real provider is configured and no money is actually moving
   */
  public boolean isManual() {
    return PaymentIntent.PROVIDER_MANUAL.equals(active.name());
  }
}
