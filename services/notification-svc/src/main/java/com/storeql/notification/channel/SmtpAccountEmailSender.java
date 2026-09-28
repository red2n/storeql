package com.storeql.notification.channel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The real {@link AccountEmailSender}: builds its own {@link SmtpChannel} straight from {@code
 * storeql.notification.smtp.*} (the same keys {@link NotificationChannelProducer} reads for the
 * deployment's general channel), so an account email never depends on — and never falls back to —
 * whatever {@code storeql.notification.channel} happens to be wired to for everything else.
 */
@ApplicationScoped
public class SmtpAccountEmailSender implements AccountEmailSender {

  private static final Logger LOG = System.getLogger(SmtpAccountEmailSender.class.getName());

  @Inject
  @ConfigProperty(name = "storeql.notification.channel", defaultValue = "app")
  String channelName;

  @Inject
  @ConfigProperty(name = "storeql.notification.from", defaultValue = "no-reply@storeql.local")
  String from;

  @Inject
  @ConfigProperty(name = "storeql.notification.smtp.host", defaultValue = "localhost")
  String host;

  @Inject
  @ConfigProperty(name = "storeql.notification.smtp.port", defaultValue = "587")
  int port;

  @Inject
  @ConfigProperty(name = "storeql.notification.smtp.username")
  Optional<String> username;

  @Inject
  @ConfigProperty(name = "storeql.notification.smtp.password")
  Optional<String> password;

  @Inject
  @ConfigProperty(name = "storeql.notification.smtp.starttls", defaultValue = "true")
  boolean startTls;

  @Override
  public boolean live() {
    return "email".equalsIgnoreCase(channelName) || "smtp".equalsIgnoreCase(channelName);
  }

  @Override
  public boolean send(String recipient, String subject, String body) {
    try {
      new SmtpChannel(host, port, blankToNull(username), blankToNull(password), from, startTls)
          .send(null, recipient, subject, body);
      return true;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "account email to {0} failed: {1}", recipient, e.getMessage());
      return false;
    }
  }

  private static String blankToNull(Optional<String> v) {
    return v.filter(s -> !s.isBlank()).orElse(null);
  }
}
