package com.storeql.customer.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.customer.repo.PrivacyRepository;
import com.storeql.customer.repo.PrivacyRepository.Reachable;
import com.storeql.ids.Ids;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link PrivacyService#intimate}: the one place customer-svc hands a phone on to be texted — the
 * SMS channel only accepts E.164, so the normalised form is used when there is one, and the number
 * as typed only when it never normalised.
 */
@ExtendWith(MockitoExtension.class)
class PrivacyServiceReachabilityTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID CUSTOMER = Ids.newId();
  private static final UUID ACTOR = Ids.newId();

  @Mock PrivacyRepository repo;
  @Mock com.storeql.customer.client.NotificationClient notifications;
  private PrivacyService service;

  @BeforeEach
  void setUp() {
    service = new PrivacyService();
    service.repo = repo;
    service.notifications = notifications;
  }

  @Test
  void aTextGoesOutOnTheNormalisedFormWhenThereIsOne() {
    when(repo.reachable(eq(TENANT), any(), eq(PrivacyService.MAX_RECIPIENTS)))
        .thenReturn(List.of(new Reachable(CUSTOMER, null, "512 345 678", "+48512345678")));
    when(notifications.send(
            any(), any(), anyString(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(true);

    service.intimate(
        TENANT, null, "Your data", "What happened and what we did about it.", null, ACTOR);

    ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> recipient = ArgumentCaptor.forClass(String.class);
    verify(notifications)
        .send(
            eq(TENANT),
            eq(ACTOR),
            channel.capture(),
            recipient.capture(),
            any(),
            any(),
            any(),
            any(),
            eq(CUSTOMER));
    assertThat(channel.getValue(), is("SMS"));
    assertThat("E.164, not the number as typed", recipient.getValue(), is("+48512345678"));
  }

  @Test
  void aTextFallsBackToTheNumberAsTypedWhenItNeverNormalised() {
    when(repo.reachable(eq(TENANT), any(), eq(PrivacyService.MAX_RECIPIENTS)))
        .thenReturn(List.of(new Reachable(CUSTOMER, null, "not a real number", null)));
    when(notifications.send(
            any(), any(), anyString(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(true);

    service.intimate(
        TENANT, null, "Your data", "What happened and what we did about it.", null, ACTOR);

    ArgumentCaptor<String> recipient = ArgumentCaptor.forClass(String.class);
    verify(notifications)
        .send(any(), any(), eq("SMS"), recipient.capture(), any(), any(), any(), any(), any());
    assertThat(recipient.getValue(), is("not a real number"));
  }

  @Test
  void emailStillWinsOverPhoneWhenBothArePresent() {
    when(repo.reachable(eq(TENANT), any(), eq(PrivacyService.MAX_RECIPIENTS)))
        .thenReturn(
            List.of(new Reachable(CUSTOMER, "a@example.com", "512 345 678", "+48512345678")));
    when(notifications.send(
            any(), any(), anyString(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(true);

    service.intimate(
        TENANT, null, "Your data", "What happened and what we did about it.", null, ACTOR);

    ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> recipient = ArgumentCaptor.forClass(String.class);
    verify(notifications)
        .send(
            any(),
            any(),
            channel.capture(),
            recipient.capture(),
            any(),
            any(),
            any(),
            any(),
            any());
    assertThat(channel.getValue(), is("EMAIL"));
    assertThat(recipient.getValue(), is("a@example.com"));
  }
}
