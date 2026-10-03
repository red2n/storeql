package com.storeql.purchase.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.EInvoiceInbox.Settings;
import com.storeql.purchase.repo.EInvoiceInboxRepository;
import com.storeql.service.SealedSecrets;
import com.storeql.web.ApiException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the inbox settings are refused for on a deployment that cannot seal a credential: no
 * database and no network, and nothing written when it refuses.
 */
class EInvoiceFetchServiceTest {

  /** A Polish tax number with its check digit, as KsefInboxIT's business has. */
  private static final String NIP = "5260250274";

  private static final SecureRandom RANDOM = new SecureRandom();

  /** The business's settings: none held, and a count of what is written. */
  private static final class Nothing extends EInvoiceInboxRepository {
    int upserts;

    @Override
    public Optional<Settings> find(UUID tenantId) {
      return Optional.empty();
    }

    @Override
    public void upsert(Settings s, boolean replaceSecret) {
      upserts++;
    }
  }

  /**
   * The service over the settings, answering what it stored with nothing: only the write counts.
   */
  private static EInvoiceFetchService service(Nothing repo, SealedSecrets secrets) {
    EInvoiceFetchService s =
        new EInvoiceFetchService() {
          @Override
          public SettingsView settings(UUID tenantId) {
            return null;
          }
        };
    s.repo = repo;
    s.secrets = secrets;
    return s;
  }

  private static String newKey() {
    byte[] key = new byte[32];
    RANDOM.nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }

  @Test
  @DisplayName(
      "A deployment with no key to seal with keeps no credential: 409 PURCHASE_SECRETS_KEY_MISSING")
  void aDeploymentWithNoKeyKeepsNoCredential() {
    Nothing repo = new Nothing();
    EInvoiceFetchService noKey = service(repo, SealedSecrets.forTest(null));

    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                noKey.setSettings(
                    Ids.newId(), "KSEF", "SIMULATED", NIP, "token-" + Ids.newId(), Ids.newId()));

    assertThat(e.status(), is(409));
    assertThat(e.code(), is("PURCHASE_SECRETS_KEY_MISSING"));
    assertThat(e.getMessage(), containsString("no key"));
    assertThat("nothing was stored", repo.upserts, is(0));

    // With a key the same settings are kept: it was the missing key that was refused.
    EInvoiceFetchService withKey = service(repo, SealedSecrets.forTest(newKey()));
    withKey.setSettings(Ids.newId(), "KSEF", "SIMULATED", NIP, "token-" + Ids.newId(), Ids.newId());
    assertThat("stored once a key is there", repo.upserts, is(1));
  }

  @Test
  @DisplayName("Settings that name no credential need no key")
  void settingsThatNameNoCredentialNeedNoKey() {
    Nothing repo = new Nothing();
    EInvoiceFetchService noKey = service(repo, SealedSecrets.forTest(null));

    noKey.setSettings(Ids.newId(), "KSEF", "SIMULATED", NIP, null, Ids.newId());

    assertThat("kept without a credential", repo.upserts, is(1));
  }
}
