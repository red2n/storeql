package com.storeql.notification.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Choosing a channel by name. */
class ChannelsTest {

  /** A deployment's email channel, standing in for SMTP beside the in-app feed. */
  private static final NotificationChannel EMAIL_DEFAULT =
      new NotificationChannel() {
        @Override
        public String name() {
          return "SMTP";
        }

        @Override
        public void send(UUID tenantId, String recipient, String subject, String body) {}
      };

  private static Channels emailDeployment() {
    Channels c = new Channels();
    c.configured = EMAIL_DEFAULT;
    return c;
  }

  /**
   * In-app asked for by name is the feed alone: on a deployment that emails, a message meant for
   * the feed must not go out by email (found by k6 sandbox-flow once the dev stack emailed).
   */
  @Test
  void appIsTheInAppFeedAloneEvenWhereEmailIsOn() {
    NotificationChannel app = emailDeployment().forName("APP");
    assertEquals("APP", app.name());
    assertEquals("APP", app.nameFor("owner@shop.example"));
  }

  @Test
  void emailAndNoNameAreTheDeploymentsDefault() {
    Channels c = emailDeployment();
    assertSame(EMAIL_DEFAULT, c.forName("EMAIL"));
    assertSame(EMAIL_DEFAULT, c.forName(" email "));
    assertSame(EMAIL_DEFAULT, c.forName(null));
    assertSame(EMAIL_DEFAULT, c.forName(""));
  }

  @Test
  void anUnknownNameIsNone() {
    assertNull(emailDeployment().forName("PIGEON"));
  }
}
