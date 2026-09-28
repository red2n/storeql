package com.storeql.customer.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.storeql.customer.domain.Domain.Customer;
import com.storeql.customer.domain.Domain.MarketingPreference;
import com.storeql.customer.dto.Dtos.MarketingAllowanceResponse;
import com.storeql.customer.dto.Dtos.MarketingChannelChoice;
import com.storeql.customer.dto.Dtos.SetMarketingPreferencesRequest;
import com.storeql.customer.repo.CustomerRepository;
import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The MARKETING purpose gate as {@link MarketingConsentService} enforces it: a channel cannot be
 * switched on while it refuses, a request that only withdraws channels never needs it at all, and
 * {@link MarketingConsentService#allowance} answers no rather than sending — the same gate both
 * times.
 */
@ExtendWith(MockitoExtension.class)
class MarketingConsentServiceGateTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID CUSTOMER_ID = Ids.newId();

  @Mock CustomerRepository repo;
  @Mock PrivacyService privacy;
  private MarketingConsentService service;
  private Customer customer;

  @BeforeEach
  void setUp() {
    service = new MarketingConsentService();
    service.repo = repo;
    service.privacy = privacy;
    customer =
        new Customer(
            CUSTOMER_ID,
            TENANT,
            null,
            "a@example.com",
            null,
            "A",
            "B",
            null,
            null,
            Customer.STATUS_ACTIVE,
            null,
            null,
            Instant.now(),
            Instant.now(),
            null,
            null,
            null);
  }

  @Test
  void switchingAChannelOnIsRefusedWhileTheGateBlocks() {
    when(privacy.marketingChannelBlocked(TENANT, CUSTOMER_ID)).thenReturn(true);
    var req =
        new SetMarketingPreferencesRequest(
            List.of(
                new MarketingChannelChoice(
                    MarketingPreference.CHANNEL_EMAIL, true, MarketingPreference.BASIS_CONSENT)),
            "Send me offers");
    ApiException e =
        assertThrows(
            ApiException.class, () -> service.setPreferences(TENANT, customer, req, "STAFF", null));
    assertThat(e.status(), is(409));
    assertThat(e.code(), is("MARKETING_PURPOSE_NOT_GRANTED"));
    // Refused before anything is written: nothing of the request is half-applied.
    verify(repo, never()).recordConsent(anyList());
  }

  @Test
  void switchingAChannelOnSucceedsWhenTheGateStandsOpen() {
    when(privacy.marketingChannelBlocked(TENANT, CUSTOMER_ID)).thenReturn(false);
    when(repo.recordConsent(anyList())).thenReturn(1);
    when(repo.listPreferences(TENANT, CUSTOMER_ID)).thenReturn(List.of());
    var req =
        new SetMarketingPreferencesRequest(
            List.of(
                new MarketingChannelChoice(
                    MarketingPreference.CHANNEL_EMAIL, true, MarketingPreference.BASIS_CONSENT)),
            "Send me offers");
    service.setPreferences(TENANT, customer, req, "STAFF", null);
    verify(repo).recordConsent(anyList());
  }

  @Test
  void aRequestThatOnlyWithdrawsChannelsNeverAsksTheGateAtAll() {
    when(repo.recordConsent(anyList())).thenReturn(1);
    when(repo.listPreferences(TENANT, CUSTOMER_ID)).thenReturn(List.of());
    var req =
        new SetMarketingPreferencesRequest(
            List.of(new MarketingChannelChoice(MarketingPreference.CHANNEL_EMAIL, false, null)),
            null);
    service.setPreferences(TENANT, customer, req, "STAFF", null);
    verifyNoMoreInteractions(privacy);
  }

  @Test
  void theGateIsAskedOnceEvenForSeveralChannelsGrantedAtOnce() {
    when(privacy.marketingChannelBlocked(TENANT, CUSTOMER_ID)).thenReturn(false);
    when(repo.recordConsent(anyList())).thenReturn(2);
    when(repo.listPreferences(TENANT, CUSTOMER_ID)).thenReturn(List.of());
    var req =
        new SetMarketingPreferencesRequest(
            List.of(
                new MarketingChannelChoice(MarketingPreference.CHANNEL_EMAIL, true, null),
                new MarketingChannelChoice(MarketingPreference.CHANNEL_SMS, true, null)),
            "Send me offers");
    service.setPreferences(TENANT, customer, req, "STAFF", null);
    verify(privacy, times(1)).marketingChannelBlocked(TENANT, CUSTOMER_ID);
  }

  @Test
  void purposeGateBlocksNeverThrowsAndDefaultsToRefusalWhenUnreadable() {
    when(privacy.marketingChannelBlocked(TENANT, CUSTOMER_ID))
        .thenThrow(new ApiException(503, "TENANT_PROFILE_UNAVAILABLE", "down", List.of()));
    assertThat(
        "silence is not consent, even about the gate's own availability",
        service.purposeGateBlocks(TENANT, CUSTOMER_ID),
        is(true));
  }

  @Test
  void allowanceAnswersNoWhenTheGateBlocksEvenThoughTheChannelIsOn() {
    when(repo.findById(TENANT, CUSTOMER_ID)).thenReturn(Optional.of(customer));
    when(repo.findPreference(TENANT, CUSTOMER_ID, MarketingPreference.CHANNEL_EMAIL))
        .thenReturn(
            Optional.of(
                new MarketingPreference(
                    TENANT,
                    CUSTOMER_ID,
                    MarketingPreference.CHANNEL_EMAIL,
                    true,
                    MarketingPreference.BASIS_CONSENT,
                    Instant.now())));
    when(privacy.marketingChannelBlocked(TENANT, CUSTOMER_ID)).thenReturn(true);
    MarketingAllowanceResponse r = service.allowance(TENANT, CUSTOMER_ID, "EMAIL");
    assertThat(r.allowed(), is(false));
    assertThat(r.unsubscribeToken(), is((String) null));
  }
}
