package com.storeql.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * How a delivery is signed (22.6), to the Standard Webhooks specification: {@code v1,<base64>} of
 * an HMAC-SHA256 under the secret's bytes over {@code id.timestamp.body}; a receiver verifies over
 * the body exactly as received; a body, an id, a second or a secret changed is a signature that
 * does not verify, and a header carrying two signatures verifies if one of them does.
 */
class WebhookSignerTest {

  private static final String SECRET =
      "whsec_"
          + Base64.getEncoder().encodeToString("a-thirty-two-byte-secret-for-tests!!".getBytes());
  private static final String ID = "019987d0-0f1e-7c3b-8a4d-3e2f1a0b9e01";

  @Test
  void theHeaderIsTheVersionAndTheDigestOverIdSecondAndBody() {
    String header = WebhookSigner.sign(SECRET, ID, 1_790_000_000L, "{\"type\":\"OrderPlaced\"}");
    assertTrue(header.startsWith("v1,"), header);
    byte[] digest = Base64.getDecoder().decode(header.substring(3));
    assertEquals(32, digest.length, "SHA-256");
    assertEquals(
        header,
        WebhookSigner.sign(SECRET, ID, 1_790_000_000L, "{\"type\":\"OrderPlaced\"}"),
        "the same input signs the same way");
  }

  @Test
  void aReceiverVerifiesAndAnythingChangedDoesNot() {
    String body = "{\"type\":\"OrderPlaced\",\"data\":{\"orderId\":\"x\"}}";
    String header = WebhookSigner.sign(SECRET, ID, 1_790_000_000L, body);
    assertTrue(WebhookSigner.verify(SECRET, ID, "1790000000", header, body));
    assertFalse(
        WebhookSigner.verify(SECRET, ID, "1790000000", header, body + " "), "the body changed");
    assertFalse(WebhookSigner.verify(SECRET, ID, "1790000001", header, body), "the second changed");
    assertFalse(
        WebhookSigner.verify(
            SECRET, "019987d0-0f1e-7c3b-8a4d-3e2f1a0b9e02", "1790000000", header, body),
        "another delivery's id");
    assertFalse(
        WebhookSigner.verify(
            "whsec_"
                + Base64.getEncoder()
                    .encodeToString("another-secret-of-thirty-two-bytes!".getBytes()),
            ID,
            "1790000000",
            header,
            body),
        "another secret");
    assertTrue(
        WebhookSigner.verify(SECRET, ID, "1790000000", "v1,bm9wZQ== " + header, body),
        "two signatures in flight: one that verifies is enough");
    assertFalse(WebhookSigner.verify(SECRET, ID, "1790000000", "nonsense", body));
    assertFalse(WebhookSigner.verify(SECRET, ID, "soon", header, body));
    assertFalse(WebhookSigner.verify(SECRET, ID, "1790000000", null, body));
    assertFalse(
        WebhookSigner.verify("whsec_%%%", ID, "1790000000", header, body),
        "a secret that is not base64");
  }
}
