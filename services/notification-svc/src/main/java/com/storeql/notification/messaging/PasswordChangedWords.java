package com.storeql.notification.messaging;

import com.storeql.notification.template.Values;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * The platform's own words for the "your password was changed" email — never a {@link
 * com.storeql.notification.template.Catalogue} message a business could reword. No link, no
 * password, nothing that signs anyone in: it says that the password of a named login changed, when,
 * that every other session ended, and what to do if it was not the reader. Languages are the reset
 * email's (en, ar, bn, gu, pa, pl, ro, ur); any other or missing language reads English. The moment
 * is stated in UTC with its offset ({@link Values#moment}) because the reader's zone is unknown.
 */
final class PasswordChangedWords {

  private PasswordChangedWords() {}

  static final String SHOPPER = "SHOPPER";
  static final String STAFF = "STAFF";

  /** The words rendered for one event, and the language they were written in. */
  record Rendered(String subject, String body, String language) {}

  /**
   * One language. {@code intro} takes the moment as {@code {0}}; {@code staffLine} takes the
   * business's name (or {@code neutralBusiness}) as {@code {0}}.
   */
  private record Lang(
      String subject,
      String intro,
      String shopperLine,
      String staffLine,
      String sessionsLine,
      String notYouLine,
      String neutralBusiness,
      Locale locale) {}

  private static Lang lang(
      String subject,
      String intro,
      String shopperLine,
      String staffLine,
      String sessionsLine,
      String notYouLine,
      String neutralBusiness,
      String tag) {
    return new Lang(
        subject,
        intro,
        shopperLine,
        staffLine,
        sessionsLine,
        notYouLine,
        neutralBusiness,
        Locale.forLanguageTag(tag));
  }

  private static final String EN = "en";

  private static final Lang ENGLISH =
      lang(
          "Your password was changed",
          "The password for your login at this email address was changed at {0}.",
          "Login: shopper account",
          "Login: staff — {0}",
          "Every other session of this login was signed out.",
          "If this was you, nothing more is needed. If it was not you, choose \"Forgot password?\""
              + " on the sign-in page now to set a new one, and tell your administrator or the"
              + " shop you buy from.",
          "a business",
          EN);

  private static final Map<String, Lang> LANGS =
      Map.ofEntries(
          Map.entry(EN, ENGLISH),
          Map.entry(
              "pl",
              lang(
                  "Twoje hasło zostało zmienione",
                  "Hasło do Twojego konta dla tego adresu e-mail zostało zmienione: {0}.",
                  "Konto: klient",
                  "Konto: personel — {0}",
                  "Wszystkie pozostałe sesje tego konta zostały wylogowane.",
                  "Jeśli to Ty, nic więcej nie trzeba robić. Jeśli to nie Ty, wybierz teraz „Nie"
                      + " pamiętasz hasła?” na stronie logowania, aby ustawić nowe, i powiadom"
                      + " swojego administratora lub sklep.",
                  "firma",
                  "pl")),
          Map.entry(
              "ro",
              lang(
                  "Parola ta a fost schimbată",
                  "Parola contului tău pentru această adresă de e-mail a fost schimbată: {0}.",
                  "Cont: client",
                  "Cont: personal — {0}",
                  "Toate celelalte sesiuni ale acestui cont au fost închise.",
                  "Dacă ai fost tu, nu mai trebuie să faci nimic. Dacă nu ai fost tu, alege acum"
                      + " „Ai uitat parola?” pe pagina de autentificare pentru a seta una nouă și"
                      + " anunță administratorul tău sau magazinul.",
                  "o companie",
                  "ro")),
          Map.entry(
              "ar",
              lang(
                  "تم تغيير كلمة المرور الخاصة بك",
                  "تم تغيير كلمة المرور لحسابك المرتبط بهذا البريد الإلكتروني في {0}.",
                  "الحساب: متسوق",
                  "الحساب: موظف — {0}",
                  "تم تسجيل الخروج من جميع الجلسات الأخرى لهذا الحساب.",
                  "إذا كنت أنت من فعل ذلك فلا حاجة لأي إجراء. وإذا لم تكن أنت، فاختر الآن \"نسيت"
                      + " كلمة المرور؟\" في صفحة تسجيل الدخول لتعيين كلمة جديدة، وأبلغ المسؤول أو"
                      + " المتجر.",
                  "شركة",
                  "ar")),
          Map.entry(
              "ur",
              lang(
                  "آپ کا پاس ورڈ تبدیل کر دیا گیا",
                  "اس ای میل ایڈریس والے آپ کے اکاؤنٹ کا پاس ورڈ {0} پر تبدیل کیا گیا۔",
                  "اکاؤنٹ: خریدار",
                  "اکاؤنٹ: عملہ — {0}",
                  "اس اکاؤنٹ کے باقی تمام سیشن ختم کر دیے گئے۔",
                  "اگر یہ آپ ہی تھے تو کچھ کرنے کی ضرورت نہیں۔ اگر آپ نہیں تھے تو ابھی سائن ان"
                      + " صفحے پر \"پاس ورڈ بھول گئے؟\" چنیں تاکہ نیا پاس ورڈ بنائیں، اور اپنے"
                      + " منتظم یا دکان کو بتائیں۔",
                  "ایک کاروبار",
                  "ur")),
          Map.entry(
              "bn",
              lang(
                  "আপনার পাসওয়ার্ড পরিবর্তন করা হয়েছে",
                  "এই ইমেল ঠিকানার জন্য আপনার অ্যাকাউন্টের পাসওয়ার্ড {0}-এ পরিবর্তন করা হয়েছে।",
                  "অ্যাকাউন্ট: ক্রেতা",
                  "অ্যাকাউন্ট: কর্মী — {0}",
                  "এই অ্যাকাউন্টের অন্য সব সেশন সাইন আউট করা হয়েছে।",
                  "এটি আপনি করে থাকলে আর কিছু করার দরকার নেই। আপনি না করে থাকলে এখনই সাইন-ইন"
                      + " পাতায় \"পাসওয়ার্ড ভুলে গেছেন?\" বেছে নতুন পাসওয়ার্ড দিন এবং আপনার"
                      + " প্রশাসক বা দোকানকে জানান।",
                  "একটি ব্যবসা",
                  "bn")),
          Map.entry(
              "gu",
              lang(
                  "તમારો પાસવર્ડ બદલવામાં આવ્યો છે",
                  "આ ઇમેઇલ સરનામા માટેના તમારા ખાતાનો પાસવર્ડ {0} વાગ્યે બદલવામાં આવ્યો હતો.",
                  "ખાતું: ગ્રાહક",
                  "ખાતું: સ્ટાફ — {0}",
                  "આ ખાતાના બાકીના બધા સત્રો સાઇન આઉટ કરવામાં આવ્યા છે.",
                  "જો આ તમે હતા તો વધુ કંઈ કરવાની જરૂર નથી. જો તમે ન હતા તો હવે સાઇન-ઇન પેજ પર"
                      + " \"પાસવર્ડ ભૂલી ગયા?\" પસંદ કરી નવો પાસવર્ડ સેટ કરો અને તમારા એડમિનિસ્ટ્રેટર"
                      + " અથવા દુકાનને જણાવો.",
                  "એક વ્યવસાય",
                  "gu")),
          Map.entry(
              "pa",
              lang(
                  "ਤੁਹਾਡਾ ਪਾਸਵਰਡ ਬਦਲ ਦਿੱਤਾ ਗਿਆ ਹੈ",
                  "ਇਸ ਈਮੇਲ ਪਤੇ ਲਈ ਤੁਹਾਡੇ ਖਾਤੇ ਦਾ ਪਾਸਵਰਡ {0} ਵਜੇ ਬਦਲਿਆ ਗਿਆ ਸੀ।",
                  "ਖਾਤਾ: ਗਾਹਕ",
                  "ਖਾਤਾ: ਸਟਾਫ਼ — {0}",
                  "ਇਸ ਖਾਤੇ ਦੇ ਬਾਕੀ ਸਾਰੇ ਸੈਸ਼ਨ ਸਾਈਨ ਆਊਟ ਕਰ ਦਿੱਤੇ ਗਏ ਹਨ।",
                  "ਜੇ ਇਹ ਤੁਸੀਂ ਸੀ ਤਾਂ ਹੋਰ ਕੁਝ ਕਰਨ ਦੀ ਲੋੜ ਨਹੀਂ। ਜੇ ਤੁਸੀਂ ਨਹੀਂ ਸੀ ਤਾਂ ਹੁਣੇ ਸਾਈਨ-ਇਨ"
                      + " ਪੰਨੇ 'ਤੇ \"ਪਾਸਵਰਡ ਭੁੱਲ ਗਏ?\" ਚੁਣ ਕੇ ਨਵਾਂ ਪਾਸਵਰਡ ਬਣਾਓ ਅਤੇ ਆਪਣੇ ਪ੍ਰਬੰਧਕ ਜਾਂ"
                      + " ਦੁਕਾਨ ਨੂੰ ਦੱਸੋ।",
                  "ਇੱਕ ਕਾਰੋਬਾਰ",
                  "pa")));

  /**
   * Renders the email.
   *
   * @param language {@code [a-z]{2,3}} or null; any value not among the platform's languages reads
   *     English
   * @param kind {@link #SHOPPER} or {@link #STAFF}; anything else is worded as a shopper login
   * @param businessName for staff, the business's name; null says "a business"
   */
  static Rendered render(String language, Instant changedAt, String kind, String businessName) {
    String key = language == null ? "" : language.strip().toLowerCase(Locale.ROOT);
    Lang l = LANGS.getOrDefault(key, ENGLISH);
    String account =
        STAFF.equals(kind)
            ? l.staffLine()
                .replace(
                    "{0}",
                    businessName == null || businessName.isBlank()
                        ? l.neutralBusiness()
                        : businessName)
            : l.shopperLine();
    String body =
        l.intro().replace("{0}", Values.moment(changedAt, l.locale()))
            + "\n\n"
            + account
            + "\n\n"
            + l.sessionsLine()
            + "\n\n"
            + l.notYouLine();
    return new Rendered(l.subject(), body, LANGS.containsKey(key) ? key : EN);
  }
}
