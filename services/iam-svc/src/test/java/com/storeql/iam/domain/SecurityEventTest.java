package com.storeql.iam.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** What of the audit log's free text a reader is shown. */
class SecurityEventTest {

  @Test
  void detailIsShownOnlyForCodesWhoseDetailIsKnownToBeSafe() {
    assertEquals("TOTP", SecurityEvent.visibleDetail("MFA_LOGIN_FAILED", "TOTP"));
    assertEquals("id prefix", SecurityEvent.visibleDetail("API_KEY_REVOKED", "id prefix"));
  }

  @Test
  void anAddressTypedAtASignInIsNeverShown() {
    for (String action :
        new String[] {
          "LOGIN_FAILED",
          "LOGIN_OK",
          "USER_REGISTERED",
          "BUSINESS_SIGNED_UP",
          "STAFF_PROVISIONED",
          "STAFF_PROVISIONED_REUSE",
          "PASSWORD_CHANGED",
          "PLATFORM_LOGIN_OK",
          "PLATFORM_LOGIN_FAILED"
        }) {
      assertNull(SecurityEvent.visibleDetail(action, "someone@example.com"), action);
    }
  }

  @Test
  void anUnknownOrMissingCodeShowsNothing() {
    assertNull(SecurityEvent.visibleDetail("SOMETHING_NEW", "free text"));
    assertNull(SecurityEvent.visibleDetail(null, "free text"));
    assertNull(SecurityEvent.visibleDetail("MFA_LOGIN_OK", null));
  }
}
