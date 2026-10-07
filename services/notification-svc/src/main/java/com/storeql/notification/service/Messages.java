package com.storeql.notification.service;

import com.storeql.notification.template.Catalogue;
import com.storeql.notification.template.Template;
import com.storeql.notification.template.TemplateStore;
import com.storeql.notification.template.Values;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Writes a message (13.x, message templates): the business's own words where it has written them,
 * the platform's where it has not, in the reader's language where the business has words in it.
 *
 * <p>The words are chosen in this order: the business's template in the reader's language, then in
 * the business's own default language, then the platform's, which is English. Whatever language the
 * words are in, the money, dates and numbers in them are written the way that language writes them,
 * in the business's country — so a Polish message from a British shop says {@code 12,50 GBP}, and
 * an English one {@code £12.50}.
 */
@ApplicationScoped
public class Messages {

  /** The language the platform's own words are in. */
  public static final String PLATFORM_LANGUAGE = "en";

  static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");

  private static final System.Logger LOG = System.getLogger(Messages.class.getName());

  @Inject TemplateStore store;
  @Inject Businesses businesses;

  /**
   * A message to be written.
   *
   * @param language the reader's language, when it is known; null for the business's own
   */
  public record Message(String type, Catalogue.Form form, String language, Values values) {}

  /**
   * A message written.
   *
   * @param language the language it was written in
   * @param template {@code default} for the platform's words, {@code v3} for a business's third
   */
  public record Composed(String subject, String body, String language, String template) {}

  public Composed compose(UUID tenantId, Message m) {
    Catalogue.MessageType type = Catalogue.get(m.type());
    Catalogue.FormSpec spec =
        type.form(m.form())
            .orElseThrow(
                () -> new IllegalArgumentException(m.type() + " is not sent as " + m.form()));
    Optional<TemplateStore.Settings> settings =
        tenantId == null ? Optional.empty() : store.settings(tenantId);
    String house = houseLanguage(settings);
    String wanted =
        m.language() != null && LANGUAGE.matcher(m.language()).matches() ? m.language() : house;

    Optional<TemplateStore.Stored> own =
        tenantId == null
            ? Optional.empty()
            : chooseOwnWords(tenantId, type.key(), m.form(), wanted, house);
    Values values = m.values();
    if (!values.has("shop")) values.text("shop", signOff(tenantId, settings));
    if (own.isPresent()) {
      TemplateStore.Stored t = own.get();
      return write(
          tenantId,
          m.form().hasSubject() ? t.subject() : spec.subject(),
          t.body(),
          t.language(),
          "v" + t.version(),
          values);
    }
    return write(tenantId, spec.subject(), spec.body(), PLATFORM_LANGUAGE, "default", values);
  }

  /**
   * The business's own words a message in this language goes out in, if it has any: its live
   * version in that language, else its live version in its default language, else none, and then
   * the platform's words go out. A version whose words do not parse is not words to send, so it is
   * passed over like a retired or never-written one. {@link #compose} writes with this rule, and
   * the template editor reads it to show what would be sent for a language whose own version is
   * retired, was never written or cannot be written.
   *
   * @param language the reader's language, as the editor has it (already a language)
   */
  public Optional<TemplateStore.Stored> ownWords(
      UUID tenantId, String messageType, Catalogue.Form form, String language) {
    return chooseOwnWords(
        tenantId, messageType, form, language, houseLanguage(store.settings(tenantId)));
  }

  private Optional<TemplateStore.Stored> chooseOwnWords(
      UUID tenantId, String messageType, Catalogue.Form form, String wanted, String house) {
    Optional<TemplateStore.Stored> own =
        store.live(tenantId, messageType, form, wanted).filter(t -> parses(t, form));
    if (own.isEmpty() && !wanted.equals(house)) {
      own = store.live(tenantId, messageType, form, house).filter(t -> parses(t, form));
    }
    return own;
  }

  /**
   * Whether the business has a live version in this language whose words do not parse, so that
   * {@link #ownWords} and {@link #compose} pass it over. The template editor asks it so that it can
   * say the version is there but cannot be sent, rather than that the language has none.
   *
   * @param language the reader's language, as the editor has it (already a language)
   */
  public boolean ownWordsUnusable(
      UUID tenantId, String messageType, Catalogue.Form form, String language) {
    return store
        .live(tenantId, messageType, form, language)
        .map(t -> problem(t, form).isPresent())
        .orElse(false);
  }

  /**
   * Whether the words can be written: the parts a message in this form is written from parse. A
   * save refuses words that do not parse; a stored version that fails here got in some other way,
   * or was saved under an earlier grammar. Passed over, the next words in the order go out — the
   * house language's, else the platform's — and the editor shows those.
   */
  private static boolean parses(TemplateStore.Stored t, Catalogue.Form form) {
    Optional<String> problem = problem(t, form);
    problem.ifPresent(
        p ->
            LOG.log(
                System.Logger.Level.WARNING,
                "template {0} v{1} is passed over: {2}",
                t.id(),
                t.version(),
                p));
    return problem.isEmpty();
  }

  /** What is wrong with the words, or empty when the parts a message in this form needs parse. */
  private static Optional<String> problem(TemplateStore.Stored t, Catalogue.Form form) {
    try {
      // A form with no subject is written with the catalogue's, so the stored subject is not
      // checked.
      if (form.hasSubject()) {
        Template.parse(t.subject());
      }
      Template.parse(t.body());
      return Optional.empty();
    } catch (Template.Invalid e) {
      return Optional.of(String.valueOf(e.getMessage()));
    }
  }

  /** What a business's messages go out in when the reader's language is not known. */
  private static String houseLanguage(Optional<TemplateStore.Settings> settings) {
    return settings.map(TemplateStore.Settings::defaultLanguage).orElse(PLATFORM_LANGUAGE);
  }

  private Composed write(
      UUID tenantId, String subject, String body, String language, String ref, Values values) {
    Locale locale = locale(language, tenantId);
    var scope = values.in(locale);
    return new Composed(
        Template.parse(subject).render(scope).strip(),
        Template.parse(body).render(scope).stripTrailing(),
        language,
        ref);
  }

  /** The language, in the business's country when it can be read. */
  Locale locale(String language, UUID tenantId) {
    String country = tenantId == null ? null : businesses.country(tenantId).orElse(null);
    return country == null
        ? Locale.forLanguageTag(language)
        : new Locale.Builder().setLanguage(language).setRegion(country).build();
  }

  private String signOff(UUID tenantId, Optional<TemplateStore.Settings> settings) {
    return settings
        .map(TemplateStore.Settings::signOff)
        .filter(s -> !s.isBlank())
        .or(() -> tenantId == null ? Optional.empty() : businesses.name(tenantId))
        .orElse("StoreQL");
  }
}
