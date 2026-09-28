package com.storeql.notification.messaging;

import com.storeql.notification.template.Values;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The platform's own words for the password reset email — never a {@link
 * com.storeql.notification.template.Catalogue} message, because it carries a key to a login and a
 * business must not be able to reword it. One entry per login the address holds (a shopper account,
 * or a business's staff, named by the business so the reader can tell several apart), each with its
 * own link, or — for a business whose staff sign in through its own identity provider — a note and
 * no link at all. Written in the language the request named where the platform has it: {@link #EN}
 * (the platform's own) plus the app's ar, bn, gu, pa, pl, ro, ur; any other or missing language
 * reads English. The date the links stop working uses the same UTC-stated moment every other
 * platform message does ({@link Values#moment}).
 */
final class PasswordResetWords {

  private PasswordResetWords() {}

  /** The words rendered for one event, and the language they were actually written in. */
  record Rendered(String subject, String body, String language) {}

  /**
   * One login the address holds, as {@code PasswordResetRequested} lists it.
   *
   * @param userId the login's own id (iam-svc's {@code users.id}), for the handler to choose whose
   *     the notification-log row is; null when the event did not carry one or it did not parse —
   *     the words say the same regardless
   */
  record Entry(String kind, String businessName, String link, UUID userId) {
    static final String SHOPPER = "SHOPPER";
    static final String STAFF = "STAFF";
    static final String STAFF_SSO = "STAFF_SSO";
  }

  /**
   * One language's words. {@code staffLine}/{@code staffSsoLine} take the business's name — or
   * {@code neutralBusiness} in its place when the business's name could not be read — as {@code
   * {0}}; {@code staffLine} takes the link as {@code {1}}; {@code shopperLine} and {@code
   * expiryLine} take one argument each.
   */
  private record Lang(
      String subject,
      String intro,
      String shopperLine,
      String staffLine,
      String staffSsoLine,
      String expiryLine,
      String ignoreLine,
      String neutralBusiness,
      Locale locale) {}

  private static Lang lang(
      String subject,
      String intro,
      String shopperLine,
      String staffLine,
      String staffSsoLine,
      String expiryLine,
      String ignoreLine,
      String neutralBusiness,
      String tag) {
    return new Lang(
        subject,
        intro,
        shopperLine,
        staffLine,
        staffSsoLine,
        expiryLine,
        ignoreLine,
        neutralBusiness,
        Locale.forLanguageTag(tag));
  }

  private static final String EN = "en";

  private static final Lang ENGLISH =
      lang(
          "Reset your password",
          "We received a request to reset the password for this email address.",
          "Shopper account: {0}",
          "Staff — {0}: {1}",
          "Staff — {0}: sign in through your business's own sign-in; its identity provider resets"
              + " your password.",
          "These links stop working at {0} and each works once.",
          "If you did not ask for this, ignore this email — nothing changes.",
          "a business",
          EN);

  private static final Map<String, Lang> LANGS =
      Map.ofEntries(
          Map.entry(EN, ENGLISH),
          Map.entry(
              "pl",
              lang(
                  "Zresetuj swoje hasło",
                  "Otrzymaliśmy prośbę o zresetowanie hasła dla tego adresu e-mail.",
                  "Konto klienta: {0}",
                  "Personel — {0}: {1}",
                  "Personel — {0}: zaloguj się przez własny system logowania Twojej firmy; hasło"
                      + " resetuje jej dostawca tożsamości.",
                  "Te linki przestają działać {0} i każdy z nich działa tylko raz.",
                  "Jeśli to nie Twoja prośba, zignoruj ten e-mail — nic się nie zmieni.",
                  "firma",
                  "pl")),
          Map.entry(
              "ro",
              lang(
                  "Resetează-ți parola",
                  "Am primit o cerere de resetare a parolei pentru această adresă de e-mail.",
                  "Cont de client: {0}",
                  "Personal — {0}: {1}",
                  "Personal — {0}: conectează-te prin sistemul propriu de autentificare al"
                      + " companiei tale; furnizorul ei de identitate resetează parola.",
                  "Aceste linkuri nu mai funcționează după {0} și fiecare poate fi folosit o"
                      + " singură dată.",
                  "Dacă nu ai făcut tu această cerere, ignoră acest e-mail — nimic nu se schimbă.",
                  "o companie",
                  "ro")),
          Map.entry(
              "ar",
              lang(
                  "إعادة تعيين كلمة المرور",
                  "تلقينا طلبًا لإعادة تعيين كلمة المرور لهذا البريد الإلكتروني.",
                  "حساب المتسوق: {0}",
                  "الموظفون — {0}: {1}",
                  "الموظفون — {0}: سجّل الدخول من خلال نظام تسجيل الدخول الخاص بشركتك؛ يقوم مزوّد"
                      + " الهوية الخاص بها بإعادة تعيين كلمة المرور.",
                  "تتوقف هذه الروابط عن العمل في {0} ويعمل كل رابط مرة واحدة فقط.",
                  "إذا لم تطلب ذلك، فتجاهل هذه الرسالة — لن يتغيّر شيء.",
                  "شركة",
                  "ar")),
          Map.entry(
              "ur",
              lang(
                  "اپنا پاس ورڈ ری سیٹ کریں",
                  "اس ای میل ایڈریس کے لیے پاس ورڈ ری سیٹ کرنے کی درخواست ہمیں موصول ہوئی ہے۔",
                  "خریدار اکاؤنٹ: {0}",
                  "عملہ — {0}: {1}",
                  "عملہ — {0}: اپنے کاروبار کے اپنے سائن ان کے ذریعے سائن ان کریں؛ اس کا شناختی"
                      + " فراہم کنندہ پاس ورڈ ری سیٹ کرتا ہے۔",
                  "یہ لنکس {0} پر کام کرنا بند کر دیں گے اور ہر ایک صرف ایک بار کام کرتا ہے۔",
                  "اگر آپ نے یہ درخواست نہیں کی، تو اس ای میل کو نظر انداز کریں — کچھ نہیں بدلے گا۔",
                  "ایک کاروبار",
                  "ur")),
          Map.entry(
              "bn",
              lang(
                  "আপনার পাসওয়ার্ড রিসেট করুন",
                  "এই ইমেল ঠিকানার জন্য পাসওয়ার্ড রিসেট করার একটি অনুরোধ আমরা পেয়েছি।",
                  "ক্রেতা অ্যাকাউন্ট: {0}",
                  "কর্মী — {0}: {1}",
                  "কর্মী — {0}: আপনার ব্যবসার নিজস্ব সাইন-ইন ব্যবস্থার মাধ্যমে সাইন ইন করুন;"
                      + " পাসওয়ার্ড রিসেট করে তাদের আইডেন্টিটি প্রোভাইডার।",
                  "এই লিংকগুলো {0}-এ কাজ করা বন্ধ করে দেবে এবং প্রতিটি একবার কাজ করে।",
                  "আপনি এই অনুরোধ না করলে, এই ইমেলটি উপেক্ষা করুন — কিছুই বদলাবে না।",
                  "একটি ব্যবসা",
                  "bn")),
          Map.entry(
              "gu",
              lang(
                  "તમારો પાસવર્ડ રીસેટ કરો",
                  "આ ઇમેઇલ સરનામા માટે પાસવર્ડ રીસેટ કરવાની વિનંતી અમને મળી છે.",
                  "ગ્રાહક ખાતું: {0}",
                  "સ્ટાફ — {0}: {1}",
                  "સ્ટાફ — {0}: તમારા વ્યવસાયની પોતાની સાઇન-ઇન સિસ્ટમ દ્વારા સાઇન ઇન કરો; તેનું"
                      + " ઓળખ પ્રદાતા પાસવર્ડ રીસેટ કરે છે.",
                  "આ લિંક્સ {0} વાગ્યે કામ કરવાનું બંધ કરી દેશે અને દરેક એક જ વાર કામ કરે છે.",
                  "જો તમે આ વિનંતી કરી ન હોય, તો આ ઇમેઇલને અવગણો — કંઈ બદલાશે નહીં.",
                  "એક વ્યવસાય",
                  "gu")),
          Map.entry(
              "pa",
              lang(
                  "ਆਪਣਾ ਪਾਸਵਰਡ ਰੀਸੈੱਟ ਕਰੋ",
                  "ਇਸ ਈਮੇਲ ਪਤੇ ਲਈ ਪਾਸਵਰਡ ਰੀਸੈੱਟ ਕਰਨ ਦੀ ਬੇਨਤੀ ਸਾਨੂੰ ਮਿਲੀ ਹੈ।",
                  "ਗਾਹਕ ਖਾਤਾ: {0}",
                  "ਸਟਾਫ਼ — {0}: {1}",
                  "ਸਟਾਫ਼ — {0}: ਆਪਣੇ ਕਾਰੋਬਾਰ ਦੇ ਆਪਣੇ ਸਾਈਨ-ਇਨ ਰਾਹੀਂ ਸਾਈਨ ਇਨ ਕਰੋ; ਇਸਦਾ ਪਛਾਣ"
                      + " ਪ੍ਰਦਾਤਾ ਪਾਸਵਰਡ ਰੀਸੈੱਟ ਕਰਦਾ ਹੈ।",
                  "ਇਹ ਲਿੰਕ {0} ਵਜੇ ਕੰਮ ਕਰਨਾ ਬੰਦ ਕਰ ਦੇਣਗੇ ਅਤੇ ਹਰ ਇੱਕ ਸਿਰਫ਼ ਇੱਕ ਵਾਰ ਕੰਮ ਕਰਦਾ ਹੈ।",
                  "ਜੇ ਤੁਸੀਂ ਇਹ ਬੇਨਤੀ ਨਹੀਂ ਕੀਤੀ, ਤਾਂ ਇਸ ਈਮੇਲ ਨੂੰ ਨਜ਼ਰਅੰਦਾਜ਼ ਕਰੋ — ਕੁਝ ਨਹੀਂ ਬਦਲੇਗਾ।",
                  "ਇੱਕ ਕਾਰੋਬਾਰ",
                  "pa")));

  /**
   * Renders one email for every entry the request carries.
   *
   * @param language the request's language, {@code [a-z]{2,3}} or null; any value not among {@link
   *     #LANGS} reads English
   */
  static Rendered render(String language, Instant expiresAt, List<Entry> entries) {
    Lang l = LANGS.getOrDefault(normalize(language), ENGLISH);
    StringBuilder body = new StringBuilder(l.intro());
    for (Entry e : entries) {
      String line = line(l, e);
      if (line == null) continue;
      body.append("\n\n").append(line);
    }
    body.append("\n\n").append(fill(l.expiryLine(), Values.moment(expiresAt, l.locale())));
    body.append("\n\n").append(l.ignoreLine());
    String tag = LANGS.containsKey(normalize(language)) ? normalize(language) : EN;
    return new Rendered(l.subject(), body.toString(), tag);
  }

  private static String line(Lang l, Entry e) {
    return switch (e.kind()) {
      case Entry.SHOPPER -> fill(l.shopperLine(), e.link());
      case Entry.STAFF -> fill(l.staffLine(), businessLabel(l, e.businessName()), e.link());
      case Entry.STAFF_SSO -> fill(l.staffSsoLine(), businessLabel(l, e.businessName()));
      default -> null; // a kind this build does not know: say nothing rather than guess
    };
  }

  private static String businessLabel(Lang l, String businessName) {
    return businessName == null || businessName.isBlank() ? l.neutralBusiness() : businessName;
  }

  private static String normalize(String language) {
    return language == null ? "" : language.strip().toLowerCase(Locale.ROOT);
  }

  /**
   * Numbered placeholders replaced in order — never {@link java.text.MessageFormat}, whose quoting
   * rules a translated apostrophe would trip over.
   */
  private static String fill(String template, String... args) {
    String out = template;
    for (int i = 0; i < args.length; i++) {
      out = out.replace("{" + i + "}", args[i]);
    }
    return out;
  }
}
