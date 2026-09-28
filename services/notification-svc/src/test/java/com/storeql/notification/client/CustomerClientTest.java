package com.storeql.notification.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Which of a customer record's fields a message goes to. */
class CustomerClientTest {

  /**
   * A recall notice is texted to the number in international form: the record keeps the phone as
   * typed ({@code 07400 123456}), which a text refuses, and its E.164 form beside it. Reading the
   * typed one, a shopper who wrote their number the national way was never texted.
   */
  @Test
  void aTextGoesToTheInternationalFormWhenTheRecordHasOne() {
    JsonObject record =
        Json.createObjectBuilder()
            .add("phone", "07400 123456")
            .add("phoneE164", "+447400123456")
            .build();
    assertEquals(
        Optional.of("+447400123456"), CustomerClient.firstOf(record, "phoneE164", "phone"));
  }

  @Test
  void theNumberAsTypedWhenNoInternationalFormCouldBeRead() {
    JsonObject record = Json.createObjectBuilder().add("phone", "+48 512 345 678").build();
    assertEquals(
        Optional.of("+48 512 345 678"), CustomerClient.firstOf(record, "phoneE164", "phone"));
    JsonObject withNull =
        Json.createObjectBuilder().add("phone", "07400 123456").addNull("phoneE164").build();
    assertEquals(
        Optional.of("07400 123456"), CustomerClient.firstOf(withNull, "phoneE164", "phone"));
  }

  @Test
  void nothingWhenTheRecordHasNeither() {
    JsonObject record =
        Json.createObjectBuilder().add("email", "a@b.example").add("phone", " ").build();
    assertEquals(Optional.empty(), CustomerClient.firstOf(record, "phoneE164", "phone"));
  }
}
