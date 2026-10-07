package com.storeql.purchase.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Accounting connectors (17.9): the accounting package a business keeps its books in, and the
 * journals the ledger pushes to it.
 *
 * <p>A business connects one package — Xero, QuickBooks Online, Sage Business Cloud Accounting, or
 * the platform's own stand-in — with the identifiers the package needs and the tokens it issued,
 * maps its nominal codes onto the package's accounts, and from a day of its choosing every journal
 * the ledger posts is pushed once, as that package's journal, with what came back kept on a log. A
 * journal the package refuses waits with the reason and a next try; one that got no answer from a
 * package with no idempotency key waits for a person, because a second try might book it twice.
 */
public final class Accounting {

  private Accounting() {}

  public static final String XERO = "XERO";
  public static final String QUICKBOOKS = "QUICKBOOKS";
  public static final String SAGE = "SAGE";

  /**
   * The platform standing in for a package: delivers in-process, so a stack with no package can be
   * driven.
   */
  public static final String SIMULATED = "SIMULATED";

  public static final String ACTIVE = "ACTIVE";
  public static final String DISABLED = "DISABLED";

  public static final String PENDING = "PENDING";
  public static final String DELIVERED = "DELIVERED";
  public static final String FAILED = "FAILED";
  public static final String UNCERTAIN = "UNCERTAIN";
  public static final String SKIPPED = "SKIPPED";
  public static final List<String> SYNC_STATUSES =
      List.of(PENDING, DELIVERED, FAILED, UNCERTAIN, SKIPPED);

  /** How many tries a journal gets by the clock before it waits for a person. */
  public static final int MAX_ATTEMPTS = 5;

  public static final Pattern NOMINAL_CODE = Pattern.compile("[A-Za-z0-9]{1,10}");

  /** Ledger-side of a code that the SIMULATED package refuses, from its {@code refuse} setting. */
  public static final String SETTING_REFUSE = "refuse";

  /**
   * One package the platform can push to.
   *
   * @param required the settings a connection must carry (the organisation, company or business)
   * @param optional the settings it may carry
   * @param tokens where the tokens come from, in a sentence
   */
  public record Provider(
      String code, String name, List<String> required, List<String> optional, String tokens) {
    public Provider {
      required = List.copyOf(required);
      optional = List.copyOf(optional);
    }

    public boolean needsCredentials() {
      return !SIMULATED.equals(code);
    }
  }

  public static final List<Provider> CATALOGUE =
      List.of(
          new Provider(
              XERO,
              "Xero",
              List.of("tenantId"),
              List.of(),
              "The organisation's tenant id, and an access token with the refresh token, client id"
                  + " and client secret of a Xero custom connection (accounting.transactions and"
                  + " accounting.settings.read)"),
          new Provider(
              QUICKBOOKS,
              "QuickBooks Online",
              List.of("realmId"),
              List.of("environment"),
              "The company's realm id, PRODUCTION or SANDBOX, and an access token with the refresh"
                  + " token, client id and client secret of an Intuit app (com.intuit.quickbooks.accounting)"),
          new Provider(
              SAGE,
              "Sage Business Cloud Accounting",
              List.of("businessId"),
              List.of(),
              "The business id, and an access token with the refresh token, client id and client"
                  + " secret of a Sage developer app (full_access)"),
          new Provider(
              SIMULATED,
              "Simulated (the platform's stand-in)",
              List.of(),
              List.of(SETTING_REFUSE),
              "None: journals are delivered in-process. A refuse setting names one account the"
                  + " stand-in does not know, to rehearse a refusal"));

  public static Optional<Provider> provider(String code) {
    return CATALOGUE.stream().filter(p -> p.code().equals(code)).findFirst();
  }

  /**
   * A QuickBooks Online company (realm) id: Intuit's are digits, and every address of the company
   * is built from it.
   */
  public static final Pattern REALM_ID = Pattern.compile("[0-9]{1,32}");

  /**
   * QuickBooks Online's environments, as its driver reads the {@code environment} setting: in any
   * case, around any spaces. Left out, a connection is to production.
   */
  public static final List<String> QUICKBOOKS_ENVIRONMENTS = List.of("PRODUCTION", "SANDBOX");

  /** Whether a QuickBooks environment setting names the sandbox, read as the driver reads it. */
  public static boolean quickBooksSandbox(String environment) {
    return environment != null && "SANDBOX".equals(environment.trim().toUpperCase(Locale.ROOT));
  }

  /**
   * Why a setting's value cannot be used, or empty when it can. Every setting goes into an address
   * or a header, so none may hold a line break or another control character; a QuickBooks realm id
   * is the company's digits, and its environment one of the two its driver knows — anything else
   * would have been read as production.
   */
  public static Optional<String> settingProblem(String provider, String key, String value) {
    if (value == null || value.chars().anyMatch(Character::isISOControl)) {
      return Optional.of("the setting '" + key + "' is one line of text");
    }
    if (QUICKBOOKS.equals(provider)
        && "realmId".equals(key)
        && !REALM_ID.matcher(value).matches()) {
      return Optional.of(
          "QuickBooks Online's realmId is the company id, digits only (Intuit shows it as the"
              + " Company ID in the company's settings)");
    }
    if (QUICKBOOKS.equals(provider)
        && "environment".equals(key)
        && !QUICKBOOKS_ENVIRONMENTS.contains(value.trim().toUpperCase(Locale.ROOT))) {
      return Optional.of(
          "QuickBooks Online's environment is PRODUCTION or SANDBOX (or left out, for"
              + " production), not '"
              + value.trim()
              + "'");
    }
    return Optional.empty();
  }

  /**
   * Why a connection's settings, as kept, cannot be used, or empty when they can: a setting the
   * package needs that is not there, or one {@link #settingProblem} refuses. The rule a connection
   * is made under, read again before anything is sent, so a connection kept before a rule existed
   * is refused with its reason rather than sent where it should not go.
   */
  public static Optional<String> settingsProblem(String provider, Map<String, String> settings) {
    Optional<Provider> known = provider(provider);
    if (known.isPresent()) {
      for (String key : known.get().required()) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) {
          return Optional.of(known.get().name() + " needs the setting '" + key + "'");
        }
      }
    }
    for (var e : new java.util.TreeMap<>(settings).entrySet()) {
      Optional<String> problem = settingProblem(provider, e.getKey(), e.getValue());
      if (problem.isPresent()) return problem;
    }
    return Optional.empty();
  }

  /** How long to wait before the next try, by how many have been made. */
  public static Duration backoff(int attempts) {
    return switch (Math.max(1, attempts)) {
      case 1 -> Duration.ofMinutes(1);
      case 2 -> Duration.ofMinutes(5);
      case 3 -> Duration.ofMinutes(30);
      case 4 -> Duration.ofHours(2);
      default -> Duration.ofHours(8);
    };
  }

  /**
   * The package a business is connected to.
   *
   * @param settings the package's identifiers, as its provider names them
   * @param credentialsSealed the tokens, sealed; null for a package that needs none
   * @param syncFrom journals dated from this day are pushed
   */
  public record Connection(
      UUID id,
      UUID tenantId,
      String provider,
      String status,
      Map<String, String> settings,
      String credentialsSealed,
      LocalDate syncFrom,
      UUID createdBy,
      Instant createdAt,
      Instant updatedAt,
      Instant lastSyncAt,
      String lastError,
      String disabledReason) {
    public Connection {
      settings = Map.copyOf(settings);
    }

    public boolean active() {
      return ACTIVE.equals(status);
    }

    public String setting(String key) {
      return settings.get(key);
    }
  }

  /**
   * What a package issued: an access token, and what refreshes it when it can (RFC 6749 §6).
   *
   * @param expiresAt when the access token stops, when known
   */
  public record Credentials(
      String accessToken,
      String refreshToken,
      String clientId,
      String clientSecret,
      Instant expiresAt) {

    public boolean canRefresh() {
      return notBlank(refreshToken) && notBlank(clientId) && notBlank(clientSecret);
    }

    /** Whether the access token is gone, or will be within the leeway. */
    public boolean expiringBy(Instant now, long leewaySeconds) {
      return expiresAt != null && !expiresAt.isAfter(now.plusSeconds(leewaySeconds));
    }

    private static boolean notBlank(String s) {
      return s != null && !s.isBlank();
    }
  }

  /** One of the business's nominal codes, onto the package's account. */
  public record Mapping(
      UUID connectionId, String nominalCode, String externalAccount, String externalName) {}

  /**
   * One journal's journey to the package.
   *
   * @param externalId what the package called it, once delivered
   * @param entryDate the journal's date, read beside it
   * @param total its total debit, read beside it
   */
  public record Sync(
      UUID id,
      UUID tenantId,
      UUID connectionId,
      UUID journalId,
      String status,
      int attempts,
      String externalId,
      String lastError,
      Instant nextAttemptAt,
      Instant leasedUntil,
      Instant createdAt,
      Instant deliveredAt,
      LocalDate entryDate,
      String description,
      String sourceType,
      java.math.BigDecimal total) {}

  /** One try, append-only. */
  public record Attempt(
      UUID id,
      UUID tenantId,
      UUID syncId,
      int attempt,
      Instant at,
      Integer statusCode,
      String error,
      String snippet,
      int durationMs) {}

  /** What a person decided about a push whose outcome was unknown. */
  public record Resolution(String outcome, UUID by, Instant at, String note) {}

  public static final String LANDED = "LANDED";
  public static final String NOT_LANDED = "NOT_LANDED";

  public record Counts(int pending, int delivered, int failed, int uncertain, int skipped) {}

  /** What one pass did. */
  public record Run(int queued, int delivered, int failed, int uncertain) {}
}
