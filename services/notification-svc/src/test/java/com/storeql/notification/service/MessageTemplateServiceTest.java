package com.storeql.notification.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.notification.dto.TemplateDtos;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A preview's sample amounts follow the business's own home currency — never a hard-coded literal —
 * and fall back to the platform's neutral one when the currency cannot be read. No Mockito: {@link
 * StubTemplateRepository} stands in for Postgres, {@link MessagesTestSupport} for {@link Messages},
 * and a tiny {@link Businesses} of the test's own for the currency each case names.
 */
class MessageTemplateServiceTest {

  private static MessageTemplateService serviceWith(Businesses businesses) {
    MessageTemplateService service = new MessageTemplateService();
    service.repo = new StubTemplateRepository();
    service.messages = MessagesTestSupport.with(new MessagesTestSupport.MemoryStore(), null, null);
    service.businesses = businesses;
    return service;
  }

  private static Businesses businessOf(String currency) {
    return new Businesses() {
      @Override
      public Optional<String> country(UUID tenantId) {
        return Optional.empty();
      }

      @Override
      public Optional<String> name(UUID tenantId) {
        return Optional.of("Test Shop");
      }

      @Override
      public boolean sandbox(UUID tenantId) {
        return false;
      }

      @Override
      public Optional<String> currency(UUID tenantId) {
        return Optional.ofNullable(currency);
      }
    };
  }

  private static TemplateDtos.Preview previewOrderConfirmed(MessageTemplateService service) {
    return service.preview(
        Ids.newId(),
        "ORDER_CONFIRMED",
        "EMAIL",
        "en",
        new TemplateDtos.TemplateRequest(
            "Your order is confirmed",
            "Thanks for your order!\n\nOrder {{order}}\nTotal: {{total}}\n\n— {{shop}}"));
  }

  @Test
  void aBusinessWhoseCurrencyIsPlnPreviewsInPln() {
    var preview = previewOrderConfirmed(serviceWith(businessOf("PLN")));
    assertTrue(preview.body().contains("PLN"), preview.body());
    assertFalse(preview.body().contains("GBP"), preview.body());
    assertFalse(preview.body().contains("£"), preview.body());
  }

  @Test
  void aBusinessWhoseCurrencyIsInrPreviewsInInr() {
    var preview = previewOrderConfirmed(serviceWith(businessOf("INR")));
    assertTrue(preview.body().contains("₹"), preview.body());
    assertFalse(preview.body().contains("GBP"), preview.body());
    assertFalse(preview.body().contains("£"), preview.body());
  }

  @Test
  void withNoReadableCurrencyThePlatformsNeutralSampleIsShownAndNoCountryIsAssumed() {
    var preview = previewOrderConfirmed(serviceWith(businessOf(null)));
    assertTrue(preview.body().contains("XYZ"), preview.body());
    assertFalse(preview.body().contains("GBP"), preview.body());
    assertFalse(preview.body().contains("£"), preview.body());
  }
}
