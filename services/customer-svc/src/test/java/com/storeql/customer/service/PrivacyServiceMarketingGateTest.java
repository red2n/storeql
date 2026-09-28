package com.storeql.customer.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeql.customer.domain.Privacy;
import com.storeql.customer.domain.Privacy.PurposeConsent;
import com.storeql.customer.repo.PrivacyRepository;
import com.storeql.ids.Ids;
import com.storeql.service.Jurisdictions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link PrivacyService#marketingChannelBlocked}: the MARKETING purpose gate a channel is switched
 * on or sent against. Granted or withdrawn is decided from the person's own answer alone, without
 * ever asking Jurisdictions — the DPDP test is reached only when nobody has answered.
 */
@ExtendWith(MockitoExtension.class)
class PrivacyServiceMarketingGateTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID CUSTOMER = Ids.newId();

  @Mock PrivacyRepository repo;
  @Mock Jurisdictions jurisdictions;
  private PrivacyService service;

  @BeforeEach
  void setUp() {
    service = new PrivacyService();
    service.repo = repo;
    service.jurisdictions = jurisdictions;
  }

  @Test
  void aGrantedPurposeNeverBlocksAndNeverAsksJurisdictions() {
    when(repo.consents(TENANT, CUSTOMER)).thenReturn(List.of(consent(true)));
    assertThat(service.marketingChannelBlocked(TENANT, CUSTOMER), is(false));
    verifyNoInteractions(jurisdictions);
  }

  @Test
  void aWithdrawnPurposeAlwaysBlocksWhateverTheCountry() {
    when(repo.consents(TENANT, CUSTOMER)).thenReturn(List.of(consent(false)));
    assertThat(service.marketingChannelBlocked(TENANT, CUSTOMER), is(true));
    // Withdrawn is withdrawn: no jurisdiction test decides it either way.
    verifyNoInteractions(jurisdictions);
  }

  @Test
  void anUnansweredPurposeBlocksOnlyUnderAPerPurposeConsentLaw() {
    when(repo.consents(TENANT, CUSTOMER)).thenReturn(List.of());
    when(jurisdictions.inForce(eq(TENANT), eq(Privacy.OBLIGATION_DPDP), any())).thenReturn(true);
    assertThat(
        "the DPDP obligation binds: a channel needs the purpose granted first",
        service.marketingChannelBlocked(TENANT, CUSTOMER),
        is(true));
  }

  @Test
  void anUnansweredPurposeStandsOnItsOwnElsewhere() {
    when(repo.consents(TENANT, CUSTOMER)).thenReturn(List.of());
    when(jurisdictions.inForce(eq(TENANT), eq(Privacy.OBLIGATION_DPDP), any())).thenReturn(false);
    assertThat(
        "no per-purpose consent law binds: a channel's own consent is enough",
        service.marketingChannelBlocked(TENANT, CUSTOMER),
        is(false));
  }

  @Test
  void aPurposeAnsweredForSomethingElseIsTheSameAsUnanswered() {
    // LOYALTY granted says nothing about MARKETING.
    when(repo.consents(TENANT, CUSTOMER))
        .thenReturn(
            List.of(
                new PurposeConsent(
                    TENANT, CUSTOMER, Privacy.PURPOSE_LOYALTY, true, null, null, Instant.now())));
    when(jurisdictions.inForce(eq(TENANT), eq(Privacy.OBLIGATION_DPDP), any())).thenReturn(true);
    assertThat(service.marketingChannelBlocked(TENANT, CUSTOMER), is(true));
  }

  private static PurposeConsent consent(boolean granted) {
    return new PurposeConsent(
        TENANT, CUSTOMER, Privacy.PURPOSE_MARKETING, granted, null, null, Instant.now());
  }
}
