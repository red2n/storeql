package com.storeql.purchase.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.einvoice.ElectronicAddress;
import com.storeql.ids.Ids;
import com.storeql.purchase.config.ServiceConfig;
import com.storeql.service.ServiceReader;
import com.storeql.test.JsonStub;
import com.storeql.web.ApiException;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which business a delivery lands with, asked of tenant-svc's directory, and what is said when the
 * directory cannot answer: a delivery that cannot be routed is refused as unavailable, never given
 * to a business by guess, and the directory's own answers (nobody holds it, more than one does) are
 * not mistaken for an outage.
 */
class TenantLookupClientTest {

  private static final ElectronicAddress ADDRESS = ElectronicAddress.parse("9930", "DE123456789");

  /** The client reading the directory at the address, as it does once discovery has told it. */
  private static TenantLookupClient clientAt(String baseUrl) throws ReflectiveOperationException {
    TenantLookupClient client = new TenantLookupClient();
    Field reader = TenantLookupClient.class.getDeclaredField("tenantSvc");
    reader.setAccessible(true);
    reader.set(
        client,
        new ServiceReader(
            new ServiceConfig(),
            TenantLookupClient.TENANT_SERVICE,
            TenantLookupClient.PLATFORM_ROLE,
            2,
            Optional.of(baseUrl)));
    return client;
  }

  @Test
  @DisplayName(
      "A directory that answers with an error is unavailable: 503 PURCHASE_TENANT_SVC_UNAVAILABLE")
  void aDirectoryThatAnswersWithAnErrorIsUnavailable() throws Exception {
    try (JsonStub directory = JsonStub.start()) {
      directory.on("GET", TenantLookupClient.LOOKUP, 500, "{\"error\":{\"code\":\"DOWN\"}}");
      TenantLookupClient client = clientAt(directory.baseUrl());

      ApiException e = assertThrows(ApiException.class, () -> client.receiver(ADDRESS, null));

      assertThat(e.status(), is(503));
      assertThat(e.code(), is("PURCHASE_TENANT_SVC_UNAVAILABLE"));
      assertThat(e.getMessage(), containsString("HTTP 500"));
      // The VAT identifier is asked about the same way, and fails the same way.
      ApiException byVat =
          assertThrows(ApiException.class, () -> client.receiver(null, "DE123456789"));
      assertThat(byVat.status(), is(503));
      assertThat(byVat.code(), is("PURCHASE_TENANT_SVC_UNAVAILABLE"));
    }
  }

  @Test
  @DisplayName("A directory that cannot be reached at all is unavailable too, naming no status")
  void aDirectoryThatCannotBeReachedIsUnavailable() throws Exception {
    String gone;
    try (JsonStub directory = JsonStub.start()) {
      gone = directory.baseUrl();
    }
    TenantLookupClient client = clientAt(gone);

    ApiException e = assertThrows(ApiException.class, () -> client.receiver(ADDRESS, null));

    assertThat(e.status(), is(503));
    assertThat(e.code(), is("PURCHASE_TENANT_SVC_UNAVAILABLE"));
    assertThat(e.getMessage(), not(containsString("HTTP")));
  }

  @Test
  @DisplayName(
      "Nobody holding an address, or several holding it, is the directory's answer and not an outage")
  void theDirectorysOwnAnswersAreNotAnOutage() throws Exception {
    try (JsonStub directory = JsonStub.start()) {
      directory.on(
          "GET", TenantLookupClient.LOOKUP, 404, "{\"error\":{\"code\":\"TENANT_NOT_FOUND\"}}");
      TenantLookupClient client = clientAt(directory.baseUrl());
      ApiException unknown = assertThrows(ApiException.class, () -> client.receiver(ADDRESS, null));
      assertThat(unknown.status(), is(404));
      assertThat(unknown.code(), is("PURCHASE_EINVOICE_RECEIVER_UNKNOWN"));

      directory.on("GET", TenantLookupClient.LOOKUP, 409, "{\"error\":{\"code\":\"SHARED\"}}");
      ApiException shared = assertThrows(ApiException.class, () -> client.receiver(ADDRESS, null));
      assertThat(shared.status(), is(409));
      assertThat(shared.code(), is("PURCHASE_EINVOICE_RECEIVER_SHARED"));

      UUID holder = Ids.newId();
      directory.on("GET", TenantLookupClient.LOOKUP, 200, "{\"data\":{\"id\":\"" + holder + "\"}}");
      assertThat("the one business that holds it", client.receiver(ADDRESS, null), is(holder));
    }
  }
}
