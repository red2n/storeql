// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Polish (`pl`).
class AppLocalizationsPl extends AppLocalizations {
  AppLocalizationsPl([String locale = 'pl']) : super(locale);

  @override
  String get signInToContinue => 'Zaloguj się, aby kontynuować';

  @override
  String get createYourAccount => 'Utwórz konto';

  @override
  String get fieldEmail => 'E-mail';

  @override
  String get fieldEmailInvalid => 'Wprowadź prawidłowy adres e-mail';

  @override
  String get fieldPhoneOptional => 'Telefon (opcjonalnie)';

  @override
  String get fieldPassword => 'Hasło';

  @override
  String get fieldPasswordRequired => 'Wpisz hasło';

  @override
  String fieldPasswordTooShort(int min) {
    return 'Użyj co najmniej $min znaków. Kilka słów to hasło najłatwiejsze do zapamiętania i najtrudniejsze do odgadnięcia; spacje są dozwolone.';
  }

  @override
  String fieldPasswordTooLong(int max) {
    return 'Użyj co najwyżej $max znaków.';
  }

  @override
  String get errPasswordIsIdentity =>
      'Hasło nie może być Twoim adresem e-mail ani go zawierać.';

  @override
  String get errPasswordBreached =>
      'To hasło pojawiło się w znanych wyciekach danych i łatwo je odgadnąć. Wybierz inne.';

  @override
  String get showPassword => 'Pokaż hasło';

  @override
  String get hidePassword => 'Ukryj hasło';

  @override
  String get signInWithBusiness => 'Zaloguj się przez swoją firmę';

  @override
  String continueWithBusiness(String business) {
    return 'Kontynuuj przez $business';
  }

  @override
  String get businessSignInNameHelp =>
      'Nazwa logowania Twojej firmy. Zna ją Twój kierownik.';

  @override
  String get fieldBusinessSignInName => 'Nazwa logowania';

  @override
  String get fieldBusinessSignInNameHint => 'np. acme-foods';

  @override
  String get actionSignIn => 'Zaloguj się';

  @override
  String get actionCreateAccount => 'Utwórz konto';

  @override
  String get actionCancel => 'Anuluj';

  @override
  String get actionContinue => 'Kontynuuj';

  @override
  String get toggleHaveAccount => 'Masz już konto? Zaloguj się';

  @override
  String get toggleNewHere => 'Nowy użytkownik? Utwórz konto';

  @override
  String get errInvalidCredentials => 'Nieprawidłowy e-mail lub hasło.';

  @override
  String get errEmailExists => 'Konto z tym adresem e-mail już istnieje.';

  @override
  String get errNetwork =>
      'Nie można połączyć się z serwerem. Sprawdź połączenie.';

  @override
  String get errGeneric => 'Coś poszło nie tak. Spróbuj ponownie.';

  @override
  String get ssoErrNotFound =>
      'Nie ma firmy logującej się pod tą nazwą. Sprawdź ją u swojego kierownika.';

  @override
  String get ssoErrRequired =>
      'Twoja firma loguje Cię przez własną stronę logowania. Użyj przycisku „Zaloguj się przez swoją firmę” poniżej.';

  @override
  String get ssoErrNoAccount =>
      'Zalogowano Cię przez firmę, ale nie dodała Cię jeszcze tutaj. Poproś kierownika o dodanie Cię do personelu.';

  @override
  String get ssoErrEmailUnverified =>
      'Strona logowania Twojej firmy nie potwierdziła Twojego adresu e-mail, więc nie można go dopasować do Twojego konta.';

  @override
  String get ssoErrEmailMissing =>
      'Strona logowania Twojej firmy nie przekazała Twojego adresu e-mail.';

  @override
  String get ssoErrAlreadyLinked =>
      'Twoje konto jest powiązane z inną osobą w Twojej firmie. Poproś właściciela o usunięcie tego powiązania.';

  @override
  String get ssoErrAccountUnavailable =>
      'To konto nie może się już tutaj logować.';

  @override
  String get ssoErrCancelled => 'Logowanie zostało anulowane.';

  @override
  String get ssoErrExpired =>
      'To logowanie trwało zbyt długo lub zostało już użyte. Zacznij od nowa.';

  @override
  String get ssoErrReauthRequired => 'Zaloguj się ponownie przez swoją firmę.';

  @override
  String get ssoErrNotReady =>
      'Logowanie jednokrotne Twojej firmy nie jest jeszcze gotowe. Zapytaj jej właściciela.';

  @override
  String get ssoErrUnavailable =>
      'Logowanie przez firmę nie jest tutaj dostępne.';

  @override
  String get ssoErrProviderUnreachable =>
      'Nie udało się połączyć ze stroną logowania Twojej firmy. Spróbuj ponownie za chwilę.';

  @override
  String get errTenantInactive =>
      'Konto tej firmy jest zawieszone. Skontaktuj się z pomocą techniczną.';

  @override
  String get ssoErrGeneric =>
      'Strona logowania Twojej firmy nie mogła Cię zalogować. Poproś jej właściciela o sprawdzenie ustawień.';

  @override
  String get forgotPassword => 'Nie pamiętasz hasła?';

  @override
  String get forgotPasswordTitle => 'Nie pamiętasz hasła?';

  @override
  String get forgotPasswordIntro =>
      'Wpisz swój adres e-mail. Jeśli jest powiązany z kontem, wyślemy link do zresetowania hasła.';

  @override
  String get forgotPasswordSubmit => 'Wyślij link';

  @override
  String get forgotPasswordSent =>
      'Jeśli jakieś konto używa tego adresu, wysłaliśmy na niego link. Działa trzydzieści minut i tylko raz.';

  @override
  String get resetPasswordTitle => 'Ustaw nowe hasło';

  @override
  String get resetPasswordNewLabel => 'Nowe hasło';

  @override
  String get resetPasswordConfirmLabel => 'Potwierdź nowe hasło';

  @override
  String get resetPasswordMismatch =>
      'To nie zgadza się z nowym hasłem podanym powyżej.';

  @override
  String get resetPasswordSubmit => 'Zmień hasło';

  @override
  String get resetPasswordDone => 'Hasło zmienione — zaloguj się nim.';

  @override
  String get resetPasswordDoneStorefront =>
      'Robisz zakupy w sklepie internetowym? Możesz zalogować się w sklepie.';

  @override
  String get resetPasswordTokenInvalid =>
      'Ten link wygasł albo został już użyty — poproś o nowy.';

  @override
  String get resetPasswordRequestNew => 'Poproś o nowy link';
}
