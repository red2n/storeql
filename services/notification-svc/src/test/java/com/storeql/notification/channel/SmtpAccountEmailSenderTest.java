package com.storeql.notification.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * An account email (a password reset, a password change) never goes through the deployment's
 * configured channel, but it is logged beside every other email, so the log must spell its channel
 * as they do: one name for email sent by SMTP, whichever code sent it.
 */
class SmtpAccountEmailSenderTest {

  @Test
  void anAccountEmailIsLoggedOnTheChannelTheDeploymentsOwnEmailIsLoggedOn() {
    SmtpChannel smtp =
        new SmtpChannel(
            "127.0.0.1",
            25,
            null,
            null,
            "from@x.example",
            false,
            new SmtpChannel.Timeouts(500, 500, 500, 1));

    SmtpAccountEmailSender account = new SmtpAccountEmailSender();

    assertEquals("SMTP", account.channel());
    assertEquals(smtp.name(), account.channel());
    assertEquals(smtp.nameFor("someone@example.com"), account.channel());
  }
}
