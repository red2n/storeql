package com.storeql.purchase.client.accounting;

import com.storeql.purchase.domain.Accounting;
import com.storeql.purchase.domain.Domain;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * An accounting package's API, as the platform pushes to it (17.9): one journal at a time, and its
 * chart of accounts to map onto. Each package is one class here; nothing else in the service knows
 * a package's URL, headers or JSON.
 */
public interface AccountingPackage {

  String provider();

  /**
   * Whether a write that got no answer is safe to send again: true where the package honours an
   * idempotency key of the journal's id, false where a second try might book the journal twice.
   */
  boolean idempotentWrites();

  /**
   * Pushes one journal as the package's own journal.
   *
   * @param account the business's nominal code onto the package's account identifier
   * @throws Refused when the package answered and said no
   * @throws Unreachable when it did not answer, or answered nothing readable
   */
  Pushed push(
      Accounting.Connection connection,
      Accounting.Credentials credentials,
      Domain.Journal journal,
      Function<String, String> account);

  /** The package's chart of accounts, active ones only. */
  List<ExternalAccount> accounts(
      Accounting.Connection connection, Accounting.Credentials credentials);

  /**
   * Trades a refresh token for a new access token at the package's token endpoint.
   *
   * @throws Refused when the package will not, which needs a person
   */
  Accounting.Credentials refresh(Accounting.Credentials credentials, Instant now);

  /** What the package called the journal. */
  record Pushed(String externalId) {}

  /**
   * One of the package's accounts.
   *
   * @param id what a journal line names the account by in this package
   * @param code the package's own code for it, or empty when it has none
   */
  record ExternalAccount(String id, String code, String name, String type) {}

  /**
   * The package answered, and said no; or the request could not be formed from the connection's
   * settings, and nothing was sent ({@link #UNSENT}).
   */
  final class Refused extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** The status of a refusal made here, before anything left: no package answered it. */
    public static final int UNSENT = 0;

    private final int status;
    private final boolean retryable;

    public Refused(int status, String detail, boolean retryable) {
      super(detail);
      this.status = status;
      this.retryable = retryable;
    }

    /**
     * A request that could not be formed — an address or a header the connection's settings cannot
     * make. Nothing was sent, and the clock cannot mend a setting: a person does.
     */
    public static Refused unsent(String detail) {
      return new Refused(UNSENT, detail, false);
    }

    /** As {@link #unsent(String)}, keeping what made the request impossible to form. */
    public static Refused unsent(String detail, Throwable cause) {
      Refused r = new Refused(UNSENT, detail, false);
      r.initCause(cause);
      return r;
    }

    /** The package's HTTP status, or {@link #UNSENT}. */
    public int status() {
      return status;
    }

    /** Whether the request left at all: false for a refusal made before anything was sent. */
    public boolean sent() {
      return status != UNSENT;
    }

    /** Whether the clock should try again: true unless it needs a person (credentials). */
    public boolean retryable() {
      return retryable;
    }
  }

  /** The package did not answer, or answered nothing readable. */
  final class Unreachable extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final boolean requestSent;

    public Unreachable(String detail, boolean requestSent, Throwable cause) {
      super(detail, cause);
      this.requestSent = requestSent;
    }

    /** Whether the request may have reached the package before the answer was lost. */
    public boolean requestSent() {
      return requestSent;
    }
  }
}
