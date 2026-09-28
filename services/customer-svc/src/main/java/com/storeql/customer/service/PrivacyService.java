package com.storeql.customer.service;

import com.storeql.customer.client.NotificationClient;
import com.storeql.customer.domain.Domain.Customer;
import com.storeql.customer.domain.Privacy;
import com.storeql.customer.domain.Privacy.ConsentEntry;
import com.storeql.customer.domain.Privacy.GuardianConsent;
import com.storeql.customer.domain.Privacy.Intimation;
import com.storeql.customer.domain.Privacy.Notice;
import com.storeql.customer.domain.Privacy.PurposeConsent;
import com.storeql.customer.domain.Privacy.Request;
import com.storeql.customer.domain.Privacy.Settings;
import com.storeql.customer.repo.CustomerRepository;
import com.storeql.customer.repo.PrivacyRepository;
import com.storeql.customer.repo.PrivacyRepository.Reachable;
import com.storeql.ids.Ids;
import com.storeql.service.Jurisdictions;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A person's privacy under India's DPDP Act (13.12), offered to every business and enforced as the
 * Act asks where it binds: the notice per language, consent per purpose withdrawn in one step, the
 * grievance contact, the request queue with its published period, a child's guardian, and the
 * breach told to customers.
 */
@ApplicationScoped
public class PrivacyService {

  private static final Logger LOG = System.getLogger(PrivacyService.class.getName());

  static final int MAX_NAME = 120;
  static final int MAX_CONTACT = 200;
  static final int MAX_ADDRESS = 500;
  static final int MAX_TITLE = 200;
  static final int MAX_NOTICE = 20_000;
  static final int MAX_DETAIL = 2_000;
  static final int MAX_SUBJECT = 200;
  static final int MAX_BODY = 4_000;
  static final int MAX_OPEN_REQUESTS = 20;
  static final int MAX_NAMED = 500;
  static final int MAX_RECIPIENTS = 5_000;
  static final int LOG_PAGE = 100;

  /** Eight digits in a row read as a document number, which a reference must never carry. */
  private static final Pattern NUMBER_RUN = Pattern.compile("\\d{8,}");

  static final String INTIMATION_TYPE = "DATA_BREACH_INTIMATION";

  @Inject PrivacyRepository repo;
  @Inject CustomerRepository customers;
  @Inject Jurisdictions jurisdictions;
  @Inject TenantProfiles profiles;
  @Inject NotificationClient notifications;

  Clock clock = Clock.systemUTC();

  /** A change to the business's settings; null keeps what is set. */
  public record SettingsChange(
      String grievanceName,
      String grievanceEmail,
      String grievancePhone,
      String grievanceAddress,
      Integer responseDays) {}

  /** The notice as a shopper reads it: what was served and everything around it. */
  public record NoticeView(
      String requested,
      String served,
      Notice notice,
      List<Notice> available,
      Settings settings,
      boolean dpdp,
      LocalDate dpdpFrom) {}

  /** A person's consents with what bears on them: a child's guardian, the notice read. */
  public record ConsentsView(
      List<PurposeConsent> consents,
      boolean child,
      Optional<GuardianConsent> guardian,
      Map<String, Notice> notices) {}

  /** One purpose chosen. */
  public record Choice(String purpose, boolean granted) {}

  // ── settings ──────────────────────────────────────────────────────────────

  public Settings settings(UUID tenantId) {
    return repo.findSettings(tenantId).orElse(Settings.none(tenantId));
  }

  /**
   * @throws ApiException {@code 400 PRIVACY_RESPONSE_DAYS_INVALID}, {@code
   *     PRIVACY_GRIEVANCE_CONTACT_INVALID}
   */
  public Settings setSettings(UUID tenantId, SettingsChange c, UUID actor) {
    Settings was = settings(tenantId);
    int days = c.responseDays() == null ? was.responseDays() : c.responseDays();
    if (days < 1 || days > Privacy.MAX_RESPONSE_DAYS) {
      throw ApiException.badRequest(
          "PRIVACY_RESPONSE_DAYS_INVALID",
          "a request is answered within 1 to "
              + Privacy.MAX_RESPONSE_DAYS
              + " days: the Rules allow no longer (r.14(3))");
    }
    String email = clip(c.grievanceEmail(), MAX_CONTACT);
    if (email != null && (!email.contains("@") || email.contains(" "))) {
      throw ApiException.badRequest(
          "PRIVACY_GRIEVANCE_CONTACT_INVALID", "the grievance email is not an address");
    }
    Settings s =
        new Settings(
            tenantId,
            clip(c.grievanceName(), MAX_NAME),
            email,
            clip(c.grievancePhone(), MAX_CONTACT),
            clip(c.grievanceAddress(), MAX_ADDRESS),
            days,
            now(),
            actor);
    repo.upsertSettings(s);
    return s;
  }

  // ── notices ───────────────────────────────────────────────────────────────

  public List<Notice> notices(UUID tenantId) {
    return repo.currentNotices(tenantId);
  }

  /**
   * @throws ApiException {@code 400 PRIVACY_LANGUAGE_UNKNOWN}, {@code PRIVACY_NOTICE_TEXT_INVALID}
   */
  public Notice publish(UUID tenantId, String languageRaw, String title, String body, UUID actor) {
    String language = language(languageRaw);
    String t = title == null ? "" : title.strip();
    String b = body == null ? "" : body.strip();
    if (t.isEmpty() || t.length() > MAX_TITLE || b.isEmpty() || b.length() > MAX_NOTICE) {
      throw ApiException.badRequest(
          "PRIVACY_NOTICE_TEXT_INVALID",
          "a notice has a title of up to "
              + MAX_TITLE
              + " characters and a body of up to "
              + MAX_NOTICE);
    }
    return repo.publish(Ids.newId(), tenantId, language, t, b, now(), actor);
  }

  /**
   * The notice as a shopper reads it, in the language asked for when the business has published
   * one, else in English, else none yet.
   *
   * @throws ApiException {@code 400 PRIVACY_LANGUAGE_UNKNOWN} for a language not offered
   */
  public NoticeView notice(UUID tenantId, String languageRaw) {
    String requested =
        languageRaw == null || languageRaw.isBlank()
            ? Privacy.LANGUAGE_DEFAULT
            : language(languageRaw);
    List<Notice> available = repo.currentNotices(tenantId);
    Notice served =
        available.stream()
            .filter(n -> requested.equals(n.language()))
            .findFirst()
            .or(
                () ->
                    available.stream()
                        .filter(n -> Privacy.LANGUAGE_DEFAULT.equals(n.language()))
                        .findFirst())
            .orElse(null);
    return new NoticeView(
        requested,
        served == null ? null : served.language(),
        served,
        available,
        settings(tenantId),
        dpdp(tenantId),
        dpdpFrom(tenantId));
  }

  /** The day the Act starts to bind this business, when its country's register names one. */
  public LocalDate dpdpFrom(UUID tenantId) {
    return jurisdictions.obligations(tenantId, profiles.requireCountry(tenantId)).stream()
        .filter(o -> Privacy.OBLIGATION_DPDP.equals(o.code()))
        .map(Jurisdictions.Obligation::effectiveFrom)
        .findFirst()
        .orElse(null);
  }

  // ── consents ──────────────────────────────────────────────────────────────

  public ConsentsView consents(UUID tenantId, UUID customerId) {
    Customer c = customer(tenantId, customerId);
    Map<String, Notice> notices = new HashMap<>();
    for (Notice n : repo.currentNotices(tenantId)) notices.put(n.language(), n);
    return new ConsentsView(
        repo.consents(tenantId, customerId),
        Privacy.isChild(c.dob(), today()),
        repo.guardian(tenantId, customerId).filter(GuardianConsent::standing),
        notices);
  }

  /**
   * Records what a person chose, purpose by purpose, against the notice they read.
   *
   * @param languageRaw the language the person read the notice in, or null for English
   * @param actor the staff member acting for the customer, or null when the customer chose
   * @throws ApiException {@code 400 PRIVACY_CHOICES_REQUIRED}, {@code PRIVACY_PURPOSE_UNKNOWN};
   *     {@code 409 PRIVACY_GUARDIAN_CONSENT_REQUIRED} for a child's tracking purpose without a
   *     guardian's consent; {@code 409 PRIVACY_NOTICE_REQUIRED} where the Act binds and no notice
   *     is published
   */
  public List<PurposeConsent> choose(
      UUID tenantId,
      UUID customerId,
      List<Choice> choices,
      String languageRaw,
      String source,
      UUID actor) {
    if (choices == null || choices.isEmpty()) {
      throw ApiException.badRequest("PRIVACY_CHOICES_REQUIRED", "name at least one purpose");
    }
    Customer c = customer(tenantId, customerId);
    boolean child = Privacy.isChild(c.dob(), today());
    boolean guarded =
        repo.guardian(tenantId, customerId).filter(GuardianConsent::standing).isPresent();
    boolean granting = false;
    List<ConsentEntry> entries = new ArrayList<>();
    Instant at = now();
    String language =
        languageRaw == null || languageRaw.isBlank()
            ? Privacy.LANGUAGE_DEFAULT
            : language(languageRaw);
    Notice read =
        repo.currentNotice(tenantId, language)
            .or(() -> repo.currentNotice(tenantId, Privacy.LANGUAGE_DEFAULT))
            .orElse(null);
    for (Choice ch : choices) {
      String purpose = ch.purpose() == null ? "" : ch.purpose().strip().toUpperCase(Locale.ROOT);
      if (!Privacy.PURPOSES.contains(purpose)) {
        throw ApiException.badRequest(
            "PRIVACY_PURPOSE_UNKNOWN",
            "a purpose is one of " + String.join(", ", Privacy.PURPOSES));
      }
      if (ch.granted() && child && !guarded && Privacy.TRACKING.contains(purpose)) {
        throw ApiException.conflict(
            "PRIVACY_GUARDIAN_CONSENT_REQUIRED",
            "a person under eighteen is not tracked, profiled or marketed to without a parent's"
                + " verifiable consent (DPDP Act s.9)");
      }
      granting |= ch.granted();
      entries.add(
          new ConsentEntry(
              Ids.newId(),
              tenantId,
              customerId,
              purpose,
              ch.granted(),
              source,
              read == null ? null : read.language(),
              read == null ? null : read.version(),
              actor,
              at));
    }
    if (granting && read == null && dpdp(tenantId)) {
      throw ApiException.conflict(
          "PRIVACY_NOTICE_REQUIRED",
          "consent is informed only against a published notice: publish one first (DPDP Act"
              + " s.6(1), Rules r.3)");
    }
    repo.record(entries);
    return repo.consents(tenantId, customerId);
  }

  /** Withdraws every consent a person has given, in one step: as easy as giving it (s.6(4)). */
  public List<PurposeConsent> withdrawAll(UUID tenantId, UUID customerId, UUID actor) {
    customer(tenantId, customerId);
    Instant at = now();
    List<ConsentEntry> entries = new ArrayList<>();
    for (PurposeConsent pc : repo.consents(tenantId, customerId)) {
      if (!pc.granted()) continue;
      entries.add(
          new ConsentEntry(
              Ids.newId(),
              tenantId,
              customerId,
              pc.purpose(),
              false,
              Privacy.SOURCE_WITHDRAW_ALL,
              pc.noticeLanguage(),
              pc.noticeVersion(),
              actor,
              at));
    }
    if (!entries.isEmpty()) repo.record(entries);
    return repo.consents(tenantId, customerId);
  }

  public List<ConsentEntry> consentLog(UUID tenantId, UUID customerId) {
    customer(tenantId, customerId);
    return repo.consentLog(tenantId, customerId, LOG_PAGE);
  }

  // ── guardian ──────────────────────────────────────────────────────────────

  public Optional<GuardianConsent> guardian(UUID tenantId, UUID customerId) {
    customer(tenantId, customerId);
    return repo.guardian(tenantId, customerId);
  }

  /**
   * Records a parent's or guardian's consent for a child, and how they were verified.
   *
   * @throws ApiException {@code 400 PRIVACY_GUARDIAN_NAME_INVALID}, {@code
   *     PRIVACY_VERIFICATION_UNKNOWN}, {@code PRIVACY_REFERENCE_IS_A_NUMBER}; {@code 409
   *     PRIVACY_NOT_A_CHILD} for a person whose date of birth makes them an adult
   */
  public GuardianConsent recordGuardian(
      UUID tenantId,
      UUID customerId,
      String guardianName,
      String verificationRaw,
      String reference,
      UUID actor) {
    Customer c = customer(tenantId, customerId);
    if (c.dob() != null && !Privacy.isChild(c.dob(), today())) {
      throw ApiException.conflict(
          "PRIVACY_NOT_A_CHILD", "this person is of age: their own consent is theirs to give");
    }
    String name = guardianName == null ? "" : guardianName.strip();
    if (name.isEmpty() || name.length() > MAX_NAME) {
      throw ApiException.badRequest(
          "PRIVACY_GUARDIAN_NAME_INVALID",
          "the guardian's name is 1 to " + MAX_NAME + " characters");
    }
    String verification =
        verificationRaw == null ? "" : verificationRaw.strip().toUpperCase(Locale.ROOT);
    if (!Privacy.VERIFICATIONS.contains(verification)) {
      throw ApiException.badRequest(
          "PRIVACY_VERIFICATION_UNKNOWN",
          "the parent is verified by " + String.join(", ", Privacy.VERIFICATIONS) + " (r.10)");
    }
    String ref = clip(reference, MAX_CONTACT);
    if (ref != null && NUMBER_RUN.matcher(ref).find()) {
      throw ApiException.badRequest(
          "PRIVACY_REFERENCE_IS_A_NUMBER",
          "a reference notes what was seen, never a document's number");
    }
    GuardianConsent g =
        new GuardianConsent(
            tenantId, customerId, name, verification, ref, now(), actor, null, null);
    repo.saveGuardian(g);
    return g;
  }

  /**
   * Withdraws a guardian's consent; the child's tracking consents fall with it.
   *
   * @throws ApiException {@code 404 PRIVACY_GUARDIAN_CONSENT_NOT_FOUND}
   */
  public GuardianConsent withdrawGuardian(UUID tenantId, UUID customerId, UUID actor) {
    customer(tenantId, customerId);
    Instant at = now();
    List<ConsentEntry> falling = new ArrayList<>();
    for (PurposeConsent pc : repo.consents(tenantId, customerId)) {
      if (pc.granted() && Privacy.TRACKING.contains(pc.purpose())) {
        falling.add(
            new ConsentEntry(
                Ids.newId(),
                tenantId,
                customerId,
                pc.purpose(),
                false,
                Privacy.SOURCE_GUARDIAN,
                pc.noticeLanguage(),
                pc.noticeVersion(),
                actor,
                at));
      }
    }
    if (!repo.withdrawGuardian(tenantId, customerId, actor, at, falling)) {
      throw ApiException.notFound(
          "PRIVACY_GUARDIAN_CONSENT_NOT_FOUND", "no guardian's consent stands for this person");
    }
    return repo.guardian(tenantId, customerId).orElseThrow();
  }

  // ── requests ──────────────────────────────────────────────────────────────

  /**
   * Opens a request, due within the business's published period.
   *
   * @throws ApiException {@code 400 PRIVACY_REQUEST_KIND_UNKNOWN}, {@code
   *     PRIVACY_REQUEST_DETAIL_INVALID}, {@code PRIVACY_NOMINEE_REQUIRED}; {@code 409
   *     PRIVACY_REQUESTS_OPEN_LIMIT}
   */
  public Request openRequest(
      UUID tenantId,
      UUID customerId,
      String kindRaw,
      String detail,
      String nomineeName,
      String nomineeContact) {
    customer(tenantId, customerId);
    String kind = kindRaw == null ? "" : kindRaw.strip().toUpperCase(Locale.ROOT);
    if (!Privacy.REQUEST_KINDS.contains(kind)) {
      throw ApiException.badRequest(
          "PRIVACY_REQUEST_KIND_UNKNOWN",
          "a request is one of " + String.join(", ", Privacy.REQUEST_KINDS));
    }
    String d = clip(detail, MAX_DETAIL + 1);
    if (d != null && d.length() > MAX_DETAIL) {
      throw ApiException.badRequest(
          "PRIVACY_REQUEST_DETAIL_INVALID", "a request says what it asks in up to " + MAX_DETAIL);
    }
    String nominee = clip(nomineeName, MAX_NAME);
    if (Privacy.REQUEST_NOMINATION.equals(kind) && nominee == null) {
      throw ApiException.badRequest(
          "PRIVACY_NOMINEE_REQUIRED", "a nomination names who may act for you (s.14)");
    }
    long open = repo.requestsOf(tenantId, customerId).stream().filter(Request::open).count();
    if (open >= MAX_OPEN_REQUESTS) {
      throw ApiException.conflict(
          "PRIVACY_REQUESTS_OPEN_LIMIT",
          "you have " + open + " requests open already; the business answers them first");
    }
    Instant at = now();
    return repo.open(
        new Request(
            Ids.newId(),
            tenantId,
            customerId,
            kind,
            d,
            nominee,
            clip(nomineeContact, MAX_CONTACT),
            at,
            Privacy.dueOn(at, settings(tenantId).responseDays()),
            Privacy.STATUS_OPEN,
            null,
            null,
            null));
  }

  /**
   * @throws ApiException {@code 400 PRIVACY_REQUEST_STATUS_UNKNOWN}
   */
  public List<Request> requests(UUID tenantId, String statusRaw, int limit) {
    String status =
        statusRaw == null || statusRaw.isBlank()
            ? null
            : statusRaw.strip().toUpperCase(Locale.ROOT);
    if (status != null && !Privacy.STATUSES.contains(status)) {
      throw ApiException.badRequest(
          "PRIVACY_REQUEST_STATUS_UNKNOWN",
          "a status is one of " + String.join(", ", Privacy.STATUSES));
    }
    return repo.requests(tenantId, status, limit);
  }

  public List<Request> requestsOf(UUID tenantId, UUID customerId) {
    customer(tenantId, customerId);
    return repo.requestsOf(tenantId, customerId);
  }

  /**
   * @throws ApiException {@code 400 PRIVACY_RESOLUTION_INVALID}; {@code 404
   *     PRIVACY_REQUEST_NOT_FOUND}; {@code 409 PRIVACY_REQUEST_SETTLED}
   */
  public Request resolve(UUID tenantId, UUID id, String statusRaw, String resolution, UUID actor) {
    String status = statusRaw == null ? "" : statusRaw.strip().toUpperCase(Locale.ROOT);
    String r = resolution == null ? "" : resolution.strip();
    if (!(Privacy.STATUS_RESOLVED.equals(status) || Privacy.STATUS_REFUSED.equals(status))
        || r.isEmpty()
        || r.length() > MAX_DETAIL) {
      throw ApiException.badRequest(
          "PRIVACY_RESOLUTION_INVALID",
          "a request is RESOLVED or REFUSED, with what was done in up to " + MAX_DETAIL);
    }
    Request was =
        repo.findRequest(tenantId, id)
            .orElseThrow(
                () -> ApiException.notFound("PRIVACY_REQUEST_NOT_FOUND", "no such request"));
    if (!repo.resolve(tenantId, id, status, r, actor, now())) {
      throw ApiException.conflict(
          "PRIVACY_REQUEST_SETTLED", "this request was " + was.status().toLowerCase(Locale.ROOT));
    }
    return repo.findRequest(tenantId, id).orElseThrow();
  }

  // ── breach intimations ────────────────────────────────────────────────────

  /**
   * Tells customers of a breach, each by email where there is one and by text where there is only a
   * phone, and keeps what was sent and to how many (r.7(1), r.7(2)(b)(vi)).
   *
   * @param only the customers affected, or empty for everyone the business can reach
   * @throws ApiException {@code 400 PRIVACY_INTIMATION_TEXT_INVALID}, {@code
   *     PRIVACY_INTIMATION_TOO_MANY_NAMED}; {@code 409 PRIVACY_NOBODY_TO_TELL}
   */
  public Intimation intimate(
      UUID tenantId, UUID noticeId, String subject, String body, List<UUID> only, UUID actor) {
    String s = subject == null ? "" : subject.strip();
    String b = body == null ? "" : body.strip();
    if (s.isEmpty() || s.length() > MAX_SUBJECT || b.isEmpty() || b.length() > MAX_BODY) {
      throw ApiException.badRequest(
          "PRIVACY_INTIMATION_TEXT_INVALID",
          "an intimation has a subject of up to "
              + MAX_SUBJECT
              + " and a body of up to "
              + MAX_BODY);
    }
    if (only != null && only.size() > MAX_NAMED) {
      throw ApiException.badRequest(
          "PRIVACY_INTIMATION_TOO_MANY_NAMED",
          "name up to " + MAX_NAMED + " customers, or none for everyone");
    }
    List<Reachable> people = repo.reachable(tenantId, only, MAX_RECIPIENTS);
    if (people.isEmpty()) {
      throw ApiException.conflict(
          "PRIVACY_NOBODY_TO_TELL", "no customer named can be reached by email or phone");
    }
    UUID id = Ids.newId();
    int failures = 0;
    for (Reachable p : people) {
      boolean email = p.email() != null && !p.email().isBlank();
      // The SMS channel only accepts E.164: the normalised form when there is one, the
      // number as typed only when it never normalised.
      String phone = p.phoneE164() != null ? p.phoneE164() : p.phone();
      boolean sent =
          notifications.send(
              tenantId,
              actor,
              email ? "EMAIL" : "SMS",
              email ? p.email() : phone,
              s,
              b,
              INTIMATION_TYPE,
              Ids.derived(id, p.id().toString()),
              p.id());
      if (!sent) failures++;
    }
    if (failures > 0) {
      LOG.log(
          Level.WARNING, "breach intimation {0}: {1} of {2} not sent", id, failures, people.size());
    }
    return repo.recordIntimation(
        new Intimation(id, tenantId, noticeId, s, b, now(), actor, people.size(), failures));
  }

  public List<Intimation> intimations(UUID tenantId) {
    return repo.intimations(tenantId, LOG_PAGE);
  }

  // ── the marketing-channel gate ────────────────────────────────────────

  /**
   * Whether the MARKETING purpose gate refuses a channel being switched on, or a marketing message
   * being sent, right now.
   *
   * <p>Refused while the purpose stands withdrawn — its latest entry {@code granted = false},
   * whatever the country. Where nobody has ever answered it, refused only where a per-purpose
   * consent law binds the business ({@link #dpdp}); elsewhere a channel's own consent stands on its
   * own (PECR-style channel consent / soft opt-in) — no country is named here, only the business's
   * own jurisdiction data. Granted, never refused.
   *
   * @param tenantId owning tenant
   * @param customerId the person a channel would be switched on, or sent to
   * @return {@code true} when the gate refuses
   * @throws ApiException 503 {@code TENANT_PROFILE_UNAVAILABLE} / {@code OBLIGATIONS_UNAVAILABLE}
   *     when nobody has answered the purpose and whether a per-purpose law binds cannot be read —
   *     refuses rather than guesses, same as every other {@link Jurisdictions} caller
   */
  public boolean marketingChannelBlocked(UUID tenantId, UUID customerId) {
    return repo.consents(tenantId, customerId).stream()
        .filter(p -> Privacy.PURPOSE_MARKETING.equals(p.purpose()))
        .findFirst()
        .map(p -> !p.granted())
        .orElseGet(() -> dpdp(tenantId));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** Whether the Act binds this business today. */
  public boolean dpdp(UUID tenantId) {
    return jurisdictions.inForce(tenantId, Privacy.OBLIGATION_DPDP, today());
  }

  private Customer customer(UUID tenantId, UUID customerId) {
    return customers
        .findById(tenantId, customerId)
        .orElseThrow(() -> ApiException.notFound("CUSTOMER_NOT_FOUND", "no such customer"));
  }

  private static String language(String raw) {
    String code = Privacy.language(raw);
    if (code == null) {
      throw ApiException.badRequest(
          "PRIVACY_LANGUAGE_UNKNOWN",
          "a notice is in English or one of the Eighth Schedule's languages: "
              + String.join(", ", Privacy.LANGUAGE_ORDER));
    }
    return code;
  }

  private static String clip(String s, int max) {
    if (s == null) return null;
    String t = s.strip();
    if (t.isEmpty()) return null;
    return t.length() > max ? t.substring(0, max) : t;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
