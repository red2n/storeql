// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Romanian Moldavian Moldovan (`ro`).
class AppLocalizationsRo extends AppLocalizations {
  AppLocalizationsRo([String locale = 'ro']) : super(locale);

  @override
  String get signInToContinue => 'Conectează-te pentru a continua';

  @override
  String get createYourAccount => 'Creează-ți contul';

  @override
  String get fieldEmail => 'E-mail';

  @override
  String get fieldEmailInvalid => 'Introdu o adresă de e-mail validă';

  @override
  String get fieldPhoneOptional => 'Telefon (opțional)';

  @override
  String get fieldPassword => 'Parolă';

  @override
  String get fieldPasswordRequired => 'Introdu parola';

  @override
  String fieldPasswordTooShort(int min) {
    return 'Folosește cel puțin $min caractere. O expresie din câteva cuvinte este cel mai ușor de reținut și cel mai greu de ghicit; spațiile sunt permise.';
  }

  @override
  String fieldPasswordTooLong(int max) {
    return 'Folosește cel mult $max caractere.';
  }

  @override
  String get errPasswordIsIdentity =>
      'Parola nu trebuie să fie, sau să conțină, adresa ta de e-mail.';

  @override
  String get errPasswordBreached =>
      'Această parolă apare în scurgeri de date cunoscute și ar putea fi ghicită. Alege alta.';

  @override
  String get showPassword => 'Arată parola';

  @override
  String get hidePassword => 'Ascunde parola';

  @override
  String get signInWithBusiness => 'Conectează-te prin compania ta';

  @override
  String continueWithBusiness(String business) {
    return 'Continuă cu $business';
  }

  @override
  String get businessSignInNameHelp =>
      'Numele de conectare al companiei tale. Managerul tău îl are.';

  @override
  String get fieldBusinessSignInName => 'Nume de conectare';

  @override
  String get fieldBusinessSignInNameHint => 'de ex. acme-foods';

  @override
  String get actionSignIn => 'Conectează-te';

  @override
  String get actionCreateAccount => 'Creează un cont';

  @override
  String get actionCancel => 'Anulează';

  @override
  String get actionContinue => 'Continuă';

  @override
  String get toggleHaveAccount => 'Ai deja un cont? Conectează-te';

  @override
  String get toggleNewHere => 'Ești nou aici? Creează un cont';

  @override
  String get errInvalidCredentials => 'E-mail sau parolă incorectă.';

  @override
  String get errEmailExists => 'Există deja un cont cu acest e-mail.';

  @override
  String get errNetwork =>
      'Serverul nu poate fi contactat. Verifică-ți conexiunea.';

  @override
  String get errGeneric => 'Ceva nu a funcționat. Încearcă din nou.';

  @override
  String get ssoErrNotFound =>
      'Nicio companie nu se conectează sub acest nume. Verifică-l cu managerul tău.';

  @override
  String get ssoErrRequired =>
      'Compania ta te conectează prin propria ei pagină de conectare. Folosește „Conectează-te prin compania ta” mai jos.';

  @override
  String get ssoErrNoAccount =>
      'Te-ai conectat prin compania ta, dar aceasta nu te-a adăugat încă aici. Cere-i managerului tău să te adauge ca angajat.';

  @override
  String get ssoErrEmailUnverified =>
      'Pagina de conectare a companiei tale nu a verificat adresa ta de e-mail, așa că nu a putut fi asociată cu contul tău.';

  @override
  String get ssoErrEmailMissing =>
      'Pagina de conectare a companiei tale nu a transmis adresa ta de e-mail.';

  @override
  String get ssoErrAlreadyLinked =>
      'Contul tău este asociat cu altcineva din compania ta. Cere-i proprietarului să anuleze această asociere.';

  @override
  String get ssoErrAccountUnavailable =>
      'Acest cont nu mai poate fi folosit pentru a te conecta aici.';

  @override
  String get ssoErrCancelled => 'Conectarea a fost anulată.';

  @override
  String get ssoErrExpired =>
      'Conectarea a durat prea mult sau a fost deja folosită. Începe din nou.';

  @override
  String get ssoErrReauthRequired => 'Conectează-te din nou prin compania ta.';

  @override
  String get ssoErrNotReady =>
      'Conectarea unică a companiei tale nu este finalizată. Întreabă-l pe proprietar.';

  @override
  String get ssoErrUnavailable =>
      'Conectarea prin compania ta nu este disponibilă aici.';

  @override
  String get ssoErrProviderUnreachable =>
      'Pagina de conectare a companiei tale nu a putut fi contactată. Încearcă din nou în scurt timp.';

  @override
  String get errTenantInactive =>
      'Contul acestei companii este suspendat. Contactează asistența.';

  @override
  String get ssoErrGeneric =>
      'Pagina de conectare a companiei tale nu a putut să te conecteze. Cere-i proprietarului să verifice setările.';

  @override
  String get forgotPassword => 'Ai uitat parola?';

  @override
  String get forgotPasswordTitle => 'Ai uitat parola?';

  @override
  String get forgotPasswordIntro =>
      'Introdu adresa de e-mail. Dacă este folosită de un cont, îți vom trimite un link pentru resetarea parolei.';

  @override
  String get forgotPasswordSubmit => 'Trimite linkul';

  @override
  String get forgotPasswordSent =>
      'Dacă un cont folosește această adresă, am trimis un link. Este valabil treizeci de minute și poate fi folosit o singură dată.';

  @override
  String get resetPasswordTitle => 'Alege o parolă nouă';

  @override
  String get resetPasswordNewLabel => 'Parolă nouă';

  @override
  String get resetPasswordConfirmLabel => 'Confirmă parola nouă';

  @override
  String get resetPasswordMismatch =>
      'Nu se potrivește cu parola nouă introdusă mai sus.';

  @override
  String get resetPasswordSubmit => 'Schimbă parola';

  @override
  String get resetPasswordDone =>
      'Parola a fost schimbată — conectează-te cu ea.';

  @override
  String get resetPasswordDoneStorefront =>
      'Faci cumpărături din magazinul online? Te poți conecta din magazin.';

  @override
  String get resetPasswordTokenInvalid =>
      'Acest link a expirat sau a fost deja folosit — cere unul nou.';

  @override
  String get resetPasswordRequestNew => 'Cere un link nou';
}
