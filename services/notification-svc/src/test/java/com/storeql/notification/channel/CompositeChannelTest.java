package com.storeql.notification.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompositeChannelTest {

  private static final UUID TENANT = Ids.newId();

  private static final class CountingChannel implements NotificationChannel {
    final String id;
    int sends;
    boolean fail;
    boolean emailOnly;

    CountingChannel(String id) {
      this.id = id;
    }

    @Override
    public String name() {
      return id;
    }

    @Override
    public boolean reaches(String recipient) {
      return !emailOnly || recipient.contains("@");
    }

    @Override
    public void send(UUID tenantId, String recipient, String subject, String body) {
      if (fail) throw new IllegalStateException(id + " boom");
      sends++;
    }
  }

  /**
   * A store alert is addressed to the store's id. On an email deployment it used to go to the SMTP
   * server too, fail there ("Invalid Addresses"), and take the in-app copy down with it: retried
   * five times and dead-lettered, the business never told (found by k6 chargeback-flow with email
   * on). Now it is the in-app feed's alone, and the log says APP.
   */
  @Test
  void aRecipientTheExternalChannelCannotReachGoesInAppOnly() {
    CountingChannel app = new CountingChannel("APP");
    CountingChannel smtp = new CountingChannel("SMTP");
    smtp.emailOnly = true;
    smtp.fail = true; // would throw if it were ever asked
    CompositeChannel composite = new CompositeChannel(app, smtp);
    String storeId = Ids.newId().toString();

    composite.send(TENANT, storeId, "Chargeback", "body");

    assertEquals(1, app.sends);
    assertEquals("APP", composite.nameFor(storeId));
    assertEquals("SMTP", composite.nameFor("owner@shop.example"));
  }

  @Test
  void theSmtpChannelReachesMailboxesOnly() {
    SmtpChannel smtp = new SmtpChannel("localhost", 25, null, null, "from@x.example", false);
    assertTrue(smtp.reaches("owner@shop.example"));
    assertTrue(smtp.reaches("Anna.Nowak+orders@sklep.example.pl"));
    assertFalse(smtp.reaches(Ids.newId().toString()));
    assertFalse(smtp.reaches("+48512345678"));
    assertFalse(smtp.reaches("@nobody"));
    assertFalse(smtp.reaches("two words@x.example"));
    assertFalse(smtp.reaches(null));
  }

  @Test
  void sendsInAppThenExternalAndReportsExternalName() {
    CountingChannel app = new CountingChannel("APP");
    CountingChannel smtp = new CountingChannel("SMTP");
    CompositeChannel composite = new CompositeChannel(app, smtp);

    assertEquals("SMTP", composite.name());
    composite.send(TENANT, "a@b.com", "Hi", "body");
    assertEquals(1, app.sends);
    assertEquals(1, smtp.sends);
  }

  @Test
  void externalFailurePropagatesAfterInApp() {
    CountingChannel app = new CountingChannel("APP");
    CountingChannel smtp = new CountingChannel("SMTP");
    smtp.fail = true;
    CompositeChannel composite = new CompositeChannel(app, smtp);

    assertThrows(
        IllegalStateException.class, () -> composite.send(TENANT, "a@b.com", "Hi", "body"));
    assertEquals(1, app.sends, "in-app still ran before external failure");
    assertEquals(0, smtp.sends);
  }
}
