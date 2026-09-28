package com.storeql.notification.service;

import com.storeql.notification.channel.SmsChannel;
import com.storeql.notification.dto.TemplateDtos;
import com.storeql.notification.repo.TemplateRepository;
import com.storeql.notification.template.Catalogue;
import com.storeql.notification.template.Template;
import com.storeql.notification.template.TemplateStore;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A business putting its messages in its own words: what there is to write, the words each message
 * goes out in now, a new version saved, a version retired, a draft previewed, and how the
 * business's messages are signed and in what language they go when the reader's is not known.
 *
 * <p>A template is judged before it is kept, never when a message is due: it must parse, use only
 * the values its message has, keep every part the message may not leave out, and — filled with the
 * message's sample values — fit the form it goes out in. A preview runs the same checks and says
 * what they found instead of refusing, so a draft can be seen half-written.
 */
@ApplicationScoped
public class MessageTemplateService {

  private static final int HISTORY = 20;

  /**
   * The platform's own neutral stand-in for a preview or a check when the business's currency
   * cannot be read — never a country's currency, and never a literal like the GBP the sample used
   * to hard-code. Not a real ISO 4217 code, so the amount is written out followed by this code
   * rather than a guessed symbol for a currency nobody named.
   */
  private static final String NEUTRAL_CURRENCY = "XYZ";

  @Inject TemplateRepository repo;
  @Inject Messages messages;
  @Inject Businesses businesses;

  /** One thing wrong with a template. */
  public record Problem(String code, String message, List<String> names) {

    public Problem {
      names = List.copyOf(names);
    }
  }

  // ── what there is to write ─────────────────────────────────────────────────────────────────────

  public List<TemplateDtos.MessageView> catalogue(UUID tenantId) {
    List<TemplateStore.Stored> live = repo.liveAll(tenantId);
    return Catalogue.all().stream()
        .map(
            t ->
                new TemplateDtos.MessageView(
                    t.key(),
                    t.title(),
                    t.audience().name(),
                    t.why(),
                    t.variables().stream()
                        .map(
                            v -> new TemplateDtos.VariableView(v.name(), v.kind(), v.description()))
                        .toList(),
                    t.forms().stream()
                        .map(
                            f ->
                                new TemplateDtos.FormView(
                                    f.form().name(),
                                    f.form().subjectMax,
                                    f.form().bodyMax,
                                    f.required().stream()
                                        .map(g -> List.copyOf(new TreeSet<>(g)))
                                        .toList(),
                                    live.stream()
                                        .filter(
                                            s ->
                                                s.messageType().equals(t.key())
                                                    && s.form() == f.form())
                                        .map(
                                            s ->
                                                new TemplateDtos.Written(
                                                    s.language(),
                                                    s.version(),
                                                    s.createdAt().toString()))
                                        .toList()))
                        .toList()))
        .toList();
  }

  /**
   * The words one message, form and language goes out in now: the business's live version, or the
   * platform's words when it has none — the draft an editor starts from.
   */
  public TemplateDtos.TemplateView get(UUID tenantId, String type, String form, String language) {
    Catalogue.MessageType t = type(type);
    Catalogue.FormSpec spec = form(t, form);
    String lang = language(language);
    var history = repo.history(tenantId, t.key(), spec.form(), lang, HISTORY);
    var live = history.stream().filter(s -> s.retiredAt() == null).findFirst();
    var versions =
        history.stream()
            .map(
                s ->
                    new TemplateDtos.Version(
                        s.version(),
                        s.subject(),
                        s.body(),
                        s.createdAt().toString(),
                        s.retiredAt() == null ? null : s.retiredAt().toString()))
            .toList();
    return live.map(
            s ->
                new TemplateDtos.TemplateView(
                    t.key(),
                    spec.form().name(),
                    lang,
                    "BUSINESS",
                    s.version(),
                    s.subject(),
                    s.body(),
                    versions))
        .orElseGet(
            () ->
                new TemplateDtos.TemplateView(
                    t.key(),
                    spec.form().name(),
                    lang,
                    "DEFAULT",
                    null,
                    spec.form().hasSubject() ? spec.subject() : "",
                    spec.body(),
                    versions));
  }

  /**
   * Saves the next version.
   *
   * @throws ApiException 400 or 422 with the first problem found, and every name it concerns
   */
  public TemplateDtos.TemplateView put(
      UUID tenantId,
      UUID by,
      String type,
      String form,
      String language,
      TemplateDtos.TemplateRequest req) {
    Catalogue.MessageType t = type(type);
    Catalogue.FormSpec spec = form(t, form);
    String lang = language(language);
    String subject = spec.form().hasSubject() ? nz(req.subject()).strip() : "";
    String body = nz(req.body());
    List<Problem> problems = check(tenantId, t, spec, lang, subject, body);
    if (!problems.isEmpty()) {
      Problem p = problems.get(0);
      throw new ApiException(
          "TEMPLATE_PART_REQUIRED".equals(p.code()) ? 422 : 400, p.code(), p.message(), p.names());
    }
    repo.publish(tenantId, t.key(), spec.form(), lang, subject, body, by, Instant.now());
    return get(tenantId, type, form, language);
  }

  /** Retires the live version: the platform's words again. */
  public void retire(UUID tenantId, UUID by, String type, String form, String language) {
    Catalogue.MessageType t = type(type);
    Catalogue.FormSpec spec = form(t, form);
    if (!repo.retire(tenantId, t.key(), spec.form(), language(language), by, Instant.now())) {
      throw ApiException.notFound(
          "TEMPLATE_NOT_WRITTEN", "This message already goes out in the platform's words");
    }
  }

  /** A draft, written out with the message's sample values, and what is wrong with it. */
  public TemplateDtos.Preview preview(
      UUID tenantId, String type, String form, String language, TemplateDtos.TemplateRequest req) {
    Catalogue.MessageType t = type(type);
    Catalogue.FormSpec spec = form(t, form);
    String lang = language(language);
    String subject = spec.form().hasSubject() ? nz(req.subject()) : spec.subject();
    String body = nz(req.body());
    List<Problem> problems = check(tenantId, t, spec, lang, subject, body);
    var scope =
        t.sample()
            .apply(currency(tenantId))
            .text("shop", signOff(tenantId))
            .in(locale(lang, tenantId));
    String renderedSubject = written(subject, scope, true);
    String renderedBody = written(body, scope, false);
    return new TemplateDtos.Preview(
        spec.form().hasSubject() ? renderedSubject : null,
        renderedBody,
        spec.form() == Catalogue.Form.SMS && renderedBody != null
            ? SmsChannel.parts(renderedBody)
            : null,
        problems.stream()
            .map(p -> new TemplateDtos.PreviewProblem(p.code(), p.message(), p.names()))
            .toList());
  }

  /** A template written out, or null when it does not parse — already among the problems. */
  private static String written(
      String template, java.util.function.Function<String, Object> scope, boolean subject) {
    try {
      String out = Template.parse(template).render(scope);
      return subject ? out.strip() : out.stripTrailing();
    } catch (Template.Invalid e) {
      return null;
    }
  }

  // ── settings ──────────────────────────────────────────────────────────────────────────────────

  public TemplateDtos.Settings settings(UUID tenantId) {
    var s = repo.settings(tenantId);
    return new TemplateDtos.Settings(
        s.map(TemplateStore.Settings::defaultLanguage).orElse(Messages.PLATFORM_LANGUAGE),
        s.map(TemplateStore.Settings::signOff).orElse(null),
        signOff(tenantId));
  }

  public TemplateDtos.Settings putSettings(UUID tenantId, UUID by, TemplateDtos.Settings req) {
    String signOff =
        req.signOff() == null || req.signOff().isBlank() ? null : req.signOff().strip();
    repo.putSettings(tenantId, language(req.defaultLanguage()), signOff, by, Instant.now());
    return settings(tenantId);
  }

  // ── the checks ─────────────────────────────────────────────────────────────────────────────────

  List<Problem> check(
      UUID tenantId,
      Catalogue.MessageType t,
      Catalogue.FormSpec spec,
      String language,
      String subject,
      String body) {
    List<Problem> out = new ArrayList<>();
    if (spec.form().hasSubject() && subject.isBlank()) {
      out.add(new Problem("TEMPLATE_SUBJECT_REQUIRED", "Write a subject", List.of()));
    }
    if (body.isBlank()) {
      out.add(new Problem("TEMPLATE_BODY_REQUIRED", "Write the message", List.of()));
      return out;
    }
    Template s;
    Template b;
    try {
      s = Template.parse(subject);
      b = Template.parse(body);
    } catch (Template.Invalid e) {
      out.add(new Problem("TEMPLATE_INVALID", e.getMessage(), List.of()));
      return out;
    }
    Set<String> used = new TreeSet<>(s.names());
    used.addAll(b.names());
    Set<String> known = t.names();
    List<String> unknown = used.stream().filter(n -> !known.contains(n)).toList();
    if (!unknown.isEmpty()) {
      out.add(
          new Problem(
              "TEMPLATE_VARIABLE_UNKNOWN",
              "This message has no " + String.join(", ", unknown),
              unknown));
    }
    List<String> missing =
        spec.required().stream()
            .filter(group -> group.stream().noneMatch(used::contains))
            .map(group -> String.join("|", new TreeSet<>(group)))
            .toList();
    if (!missing.isEmpty()) {
      out.add(
          new Problem(
              "TEMPLATE_PART_REQUIRED",
              "This message must say "
                  + missing.stream()
                      .map(g -> g.contains("|") ? "one of " + g.replace("|", ", ") : g)
                      .collect(Collectors.joining("; ")),
              missing));
    }
    var scope =
        t.sample()
            .apply(currency(tenantId))
            .text("shop", signOff(tenantId))
            .in(locale(language, tenantId));
    String writtenSubject = s.render(scope).strip();
    String writtenBody = b.render(scope).stripTrailing();
    if (spec.form().hasSubject() && writtenSubject.length() > spec.form().subjectMax) {
      out.add(
          new Problem(
              "TEMPLATE_TOO_LONG",
              "The subject comes to "
                  + writtenSubject.length()
                  + " characters; "
                  + spec.form().subjectMax
                  + " is the most a "
                  + spec.form().name().toLowerCase(Locale.ROOT)
                  + " shows",
              List.of("subject")));
    }
    if (writtenBody.length() > spec.form().bodyMax) {
      out.add(
          new Problem(
              "TEMPLATE_TOO_LONG",
              "The message comes to "
                  + writtenBody.length()
                  + " characters with sample values; "
                  + spec.form().bodyMax
                  + " is the most",
              List.of("body")));
    }
    return out;
  }

  private String signOff(UUID tenantId) {
    return repo.settings(tenantId)
        .map(TemplateStore.Settings::signOff)
        .filter(s -> !s.isBlank())
        .or(() -> businesses.name(tenantId))
        .orElse("StoreQL");
  }

  private Locale locale(String language, UUID tenantId) {
    return messages.locale(language, tenantId);
  }

  /**
   * What a sample's money values are shown in — the business's own home currency, or the platform's
   * neutral stand-in when it cannot be read. Never a country is assumed from the business's
   * language or the caller's own currency.
   */
  private String currency(UUID tenantId) {
    return businesses.currency(tenantId).orElse(NEUTRAL_CURRENCY);
  }

  private static Catalogue.MessageType type(String key) {
    return Catalogue.find(key)
        .orElseThrow(() -> ApiException.notFound("MESSAGE_UNKNOWN", "There is no message " + key));
  }

  private static Catalogue.FormSpec form(Catalogue.MessageType t, String form) {
    Catalogue.Form f;
    try {
      f = Catalogue.Form.valueOf(form == null ? "" : form.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          404, "MESSAGE_FORM_UNKNOWN", "Forms are EMAIL, SMS, PUSH and ALERT", List.of(), e);
    }
    return t.form(f)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "MESSAGE_FORM_UNKNOWN", t.title() + " is not sent as " + f.name()));
  }

  static String language(String language) {
    String l = language == null ? "" : language.strip().toLowerCase(Locale.ROOT);
    if (!Messages.LANGUAGE.matcher(l).matches()) {
      throw ApiException.badRequest(
          "TEMPLATE_LANGUAGE_INVALID", "A language is its ISO 639 code: en, pl, hi, pa…");
    }
    return l;
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
