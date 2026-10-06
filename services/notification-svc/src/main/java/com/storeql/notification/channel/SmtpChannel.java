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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SMTP delivery via Jakarta Mail. Selected when {@code storeql.notification.channel=smtp}.
 * Credentials and host come from config/secret store (never committed). A send failure throws so
 * the consumer loop retries.
 */
public final class SmtpChannel implements NotificationChannel {

  private final String from;
  private final Timeouts timeouts;
  private final Session session;

  private static final ReentrantLock POOL_LOCK = new ReentrantLock();
  private static ExecutorService sharedSenders;

  /**
   * Network limits of one SMTP conversation, all in milliseconds, and the size of the shared sender
   * pool. Jakarta Mail's own defaults are infinite, so every one must be positive.
   *
   * @param connectMs TCP connect timeout
   * @param readMs socket read timeout
   * @param writeMs socket write timeout
   * @param workers platform threads that run the (carrier-pinning) Transport calls
   */
  public record Timeouts(int connectMs, int readMs, int writeMs, int workers) {
    /** The defaults: 5 s to connect, 10 s per read and write, four sender threads. */
    public static final Timeouts DEFAULTS = new Timeouts(5_000, 10_000, 10_000, 4);

    /** Rejects a zero or negative limit, which would mean "wait forever" to Jakarta Mail. */
    public Timeouts {
      if (connectMs <= 0 || readMs <= 0 || writeMs <= 0 || workers <= 0) {
        throw new IllegalArgumentException("SMTP timeouts and workers must be positive");
      }
    }

    long overallMs() {
      return (long) connectMs + readMs + writeMs + 5_000L;
    }
  }

  /**
   * One pool for the whole service: Angus Mail's Transport methods are synchronized, which pins a
   * virtual thread's carrier for the whole conversation, so the conversation runs on a platform
   * thread and the request thread only waits on a Future (which does not pin).
   */
  private static ExecutorService senders(int workers) {
    POOL_LOCK.lock();
    try {
      if (sharedSenders == null) {
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf =
            r -> {
              Thread t = new Thread(r, "smtp-sender-" + n.incrementAndGet());
              t.setDaemon(true);
              return t;
            };
        sharedSenders = Executors.newFixedThreadPool(workers, tf);
      }
      return sharedSenders;
    } finally {
      POOL_LOCK.unlock();
    }
  }

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
    this(host, port, username, password, from, startTls, Timeouts.DEFAULTS);
  }

  /**
   * As above, with explicit network limits.
   *
   * @param timeouts connect/read/write limits and the sender pool size
   */
  public SmtpChannel(
      String host,
      int port,
      String username,
      String password,
      String from,
      boolean startTls,
      Timeouts timeouts) {
    this.timeouts = timeouts;
    this.from = from;
    boolean auth = username != null && !username.isBlank();
    Properties props = new Properties();
    props.put("mail.smtp.host", host);
    props.put("mail.smtp.port", String.valueOf(port));
    props.put("mail.smtp.auth", String.valueOf(auth));
    props.put("mail.smtp.starttls.enable", String.valueOf(startTls));
    props.put("mail.smtp.connectiontimeout", String.valueOf(timeouts.connectMs()));
    props.put("mail.smtp.timeout", String.valueOf(timeouts.readMs()));
    props.put("mail.smtp.writetimeout", String.valueOf(timeouts.writeMs()));
    this.session =
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
  }

  /**
   * What the notification log calls email sent by SMTP, wherever it is sent from: the deployment's
   * own default channel, and the account emails ({@link AccountEmailSender}), which never go
   * through it.
   */
  public static final String NAME = "SMTP";

  /**
   * {@inheritDoc}
   *
   * @return always {@code SMTP}
   */
  @Override
  public String name() {
    return NAME;
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
    try {
      MimeMessage msg = new MimeMessage(session);
      msg.setFrom(new InternetAddress(from));
      msg.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
      msg.setSubject(subject);
      msg.setText(body);
      deliver(msg, recipient);
    } catch (jakarta.mail.MessagingException e) {
      throw new IllegalStateException(
          "SMTP send to " + recipient + " failed: " + e.getMessage(), e);
    }
  }

  private void deliver(MimeMessage msg, String recipient) {
    Future<?> job =
        senders(timeouts.workers())
            .submit(
                () -> {
                  Transport.send(msg);
                  return null;
                });
    try {
      job.get(timeouts.overallMs(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      job.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("SMTP send to " + recipient + " interrupted", e);
    } catch (TimeoutException e) {
      job.cancel(true);
      throw new IllegalStateException("SMTP send to " + recipient + " timed out", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      throw new IllegalStateException(
          "SMTP send to " + recipient + " failed: " + cause.getMessage(), e);
    }
  }
}
