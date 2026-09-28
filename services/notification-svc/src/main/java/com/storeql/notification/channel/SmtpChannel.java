package com.storeql.notification.channel;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import java.util.UUID;

/**
 * SMTP delivery via Jakarta Mail. Selected when {@code storeql.notification.channel=smtp}.
 * Credentials and host come from config/secret store (never committed). A send failure throws so
 * the consumer loop retries.
 */
public final class SmtpChannel implements NotificationChannel {

  private final String host;
  private final int port;
  private final String username;
  private final String password;
  private final String from;
  private final boolean startTls;

  /**
   * @param host SMTP server hostname
   * @param port SMTP server port
   * @param username account to authenticate as; blank or {@code null} disables SMTP auth entirely
   * @param password password for {@code username}, from the secret store — never committed
   * @param from the envelope sender address
   * @param startTls whether to upgrade the connection with STARTTLS
   */
  public SmtpChannel(
      String host, int port, String username, String password, String from, boolean startTls) {
    this.host = host;
    this.port = port;
    this.username = username;
    this.password = password;
    this.from = from;
    this.startTls = startTls;
  }

  /**
   * {@inheritDoc}
   *
   * @return always {@code SMTP}
   */
  @Override
  public String name() {
    return "SMTP";
  }

  /**
   * {@inheritDoc}
   *
   * <p>{@code tenantId} is unused here: the recipient is already a globally unique email address,
   * so the message needs no further tenant scoping.
   *
   * @throws IllegalStateException when the message cannot be handed to the SMTP server, so the
   *     consumer loop retries the delivery
   */
  /**
   * An email address, strictly: a store alert is addressed to the store's id and a device push to a
   * login's, and neither is somebody's mailbox — handing one to the server would fail the send and,
   * with it, the in-app copy beside it.
   */
  @Override
  public boolean reaches(String recipient) {
    if (recipient == null || recipient.indexOf('@') < 1) {
      return false;
    }
    try {
      new InternetAddress(recipient, true).validate();
      return true;
    } catch (jakarta.mail.internet.AddressException e) {
      return false;
    }
  }

  @Override
  public void send(UUID tenantId, String recipient, String subject, String body) {
    // The recipient is already a globally-unique email address, so email needs no tenant scoping.
    boolean auth = username != null && !username.isBlank();
    Properties props = new Properties();
    props.put("mail.smtp.host", host);
    props.put("mail.smtp.port", String.valueOf(port));
    props.put("mail.smtp.auth", String.valueOf(auth));
    props.put("mail.smtp.starttls.enable", String.valueOf(startTls));

    Session session =
        auth
            ? Session.getInstance(
                props,
                new Authenticator() {
                  @Override
                  protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                  }
                })
            : Session.getInstance(props);

    try {
      MimeMessage msg = new MimeMessage(session);
      msg.setFrom(new InternetAddress(from));
      msg.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
      msg.setSubject(subject);
      msg.setText(body);
      Transport.send(msg);
    } catch (jakarta.mail.MessagingException e) {
      throw new IllegalStateException(
          "SMTP send to " + recipient + " failed: " + e.getMessage(), e);
    }
  }
}
