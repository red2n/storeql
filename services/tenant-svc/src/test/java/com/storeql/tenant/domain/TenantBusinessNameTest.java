package com.storeql.tenant.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Domain.Tenant;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The name a business answers to in public: its legal name, else the name it signed up with. */
class TenantBusinessNameTest {

  private static Tenant tenant(String name, String legalName) {
    Instant now = Instant.now();
    return new Tenant(
        Ids.newId(),
        name,
        legalName,
        Tenant.STATUS_ACTIVE,
        null,
        Ids.newId(),
        "GB",
        "GBP",
        now,
        now,
        null,
        null,
        null,
        null,
        Tenant.MODE_LIVE,
        null);
  }

  @Test
  @DisplayName("The legal name when the business has one, trimmed")
  void theLegalNameWhenThereIsOne() {
    assertThat(
        tenant("Harbour Foods", "  Harbour Foods Trading Ltd ").businessName(),
        is("Harbour Foods Trading Ltd"));
  }

  @Test
  @DisplayName("The name it signed up with when there is no legal name, or only a blank one")
  void theNameWhenThereIsNoLegalName() {
    assertThat(tenant("Harbour Foods", null).businessName(), is("Harbour Foods"));
    assertThat(tenant("Harbour Foods", "").businessName(), is("Harbour Foods"));
    assertThat(tenant("Harbour Foods", " \t ").businessName(), is("Harbour Foods"));
    assertThat(tenant(" Harbour Foods ", null).businessName(), is("Harbour Foods"));
  }

  @Test
  @DisplayName("Null only when the business has no name at all")
  void nullOnlyWithNoNameAtAll() {
    assertThat(tenant(null, null).businessName(), is(nullValue()));
    assertThat(tenant("  ", " ").businessName(), is(nullValue()));
  }
}
