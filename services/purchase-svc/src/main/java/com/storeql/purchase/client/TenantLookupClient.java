package com.storeql.purchase.client;

import com.storeql.einvoice.ElectronicAddress;
import com.storeql.ids.Ids;
import com.storeql.purchase.config.Jsons;
import com.storeql.purchase.config.ServiceConfig;
import com.storeql.service.ServiceReader;
import com.storeql.service.ServiceReader.Reply;
import com.storeql.web.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which business on this platform a network's delivery lands with (07.13, the transport seam).
 *
 * <p>tenant-svc's platform-wide lookup, asked as the platform itself: a delivery carries no tenant,
 * and the answer is what gives it one. By the electronic address the document names for its buyer
 * when it names one, else by the buyer's VAT identifier.
 */
@ApplicationScoped
public class TenantLookupClient {

  static final String TENANT_SERVICE = "tenant-svc";

  static final String PLATFORM_ROLE = "PLATFORM_ADMIN";

  static final String LOOKUP = "/platform/tenants/by-einvoice-address";

  private static final int ATTEMPTS = 2;

  @Inject ServiceConfig config;

  private ServiceReader tenantSvc;

  @PostConstruct
  void init() {
    tenantSvc =
        new ServiceReader(
            config,
            TENANT_SERVICE,
            PLATFORM_ROLE,
            ATTEMPTS,
            ServiceReader.configuredUrl(TENANT_SERVICE));
  }

  /**
   * The one active business holding the address, else the VAT identifier.
   *
   * @param address the buyer's electronic address, or null when the document names none
   * @param vatNumber the buyer's VAT identifier, or null
   * @return the business's tenant id
   * @throws ApiException {@code 404 PURCHASE_EINVOICE_RECEIVER_UNKNOWN} when no active business
   *     holds either, {@code 409 PURCHASE_EINVOICE_RECEIVER_SHARED} when more than one holds the
   *     address, {@code 503 PURCHASE_TENANT_SVC_UNAVAILABLE} when tenant-svc could not answer
   */
  public UUID receiver(ElectronicAddress address, String vatNumber) {
    List<String> named = new ArrayList<>();
    if (address != null) {
      named.add(address.toString());
      Optional<UUID> found =
          lookup(Map.of("scheme", address.scheme(), "id", address.id()), address.toString());
      if (found.isPresent()) return found.get();
    }
    if (vatNumber != null) {
      named.add(vatNumber);
      Optional<UUID> found = lookup(Map.of("vatNumber", vatNumber), vatNumber);
      if (found.isPresent()) return found.get();
    }
    throw ApiException.notFound(
        "PURCHASE_EINVOICE_RECEIVER_UNKNOWN",
        "no business on this platform holds " + String.join(" or ", named));
  }

  private Optional<UUID> lookup(Map<String, String> query, String named) {
    Reply r = tenantSvc.get(null, LOOKUP, query);
    if (r.ok()) {
      try (JsonReader reader = Jsons.PROVIDER.createReader(new StringReader(r.body()))) {
        return Optional.of(Ids.parse(reader.readObject().getJsonObject("data").getString("id")));
      }
    }
    if (r.status() == 404) return Optional.empty();
    if (r.status() == 409) {
      throw ApiException.conflict(
          "PURCHASE_EINVOICE_RECEIVER_SHARED",
          "more than one business on this platform holds "
              + named
              + ", so the delivery lands"
              + " nowhere");
    }
    throw new ApiException(
        503,
        "PURCHASE_TENANT_SVC_UNAVAILABLE",
        "the directory of businesses could not be asked"
            + (r.status() == 0 ? "" : ": HTTP " + r.status()),
        List.of());
  }
}
