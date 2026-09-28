// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Panjabi Punjabi (`pa`).
class AppLocalizationsPa extends AppLocalizations {
  AppLocalizationsPa([String locale = 'pa']) : super(locale);

  @override
  String get signInToContinue => 'ਜਾਰੀ ਰੱਖਣ ਲਈ ਸਾਈਨ ਇਨ ਕਰੋ';

  @override
  String get createYourAccount => 'ਆਪਣਾ ਖਾਤਾ ਬਣਾਓ';

  @override
  String get fieldEmail => 'ਈਮੇਲ';

  @override
  String get fieldEmailInvalid => 'ਇੱਕ ਸਹੀ ਈਮੇਲ ਪਤਾ ਦਰਜ ਕਰੋ';

  @override
  String get fieldPhoneOptional => 'ਫੋਨ (ਵਿਕਲਪਿਕ)';

  @override
  String get fieldPassword => 'ਪਾਸਵਰਡ';

  @override
  String get fieldPasswordRequired => 'ਆਪਣਾ ਪਾਸਵਰਡ ਦਰਜ ਕਰੋ';

  @override
  String fieldPasswordTooShort(int min) {
    return 'ਘੱਟੋ-ਘੱਟ $min ਅੱਖਰਾਂ ਦੀ ਵਰਤੋਂ ਕਰੋ। ਕੁਝ ਸ਼ਬਦਾਂ ਦਾ ਇੱਕ ਵਾਕ ਯਾਦ ਰੱਖਣਾ ਸਭ ਤੋਂ ਸੌਖਾ ਅਤੇ ਅੰਦਾਜ਼ਾ ਲਗਾਉਣਾ ਸਭ ਤੋਂ ਔਖਾ ਹੁੰਦਾ ਹੈ; ਸਪੇਸ ਵਰਤੀ ਜਾ ਸਕਦੀ ਹੈ।';
  }

  @override
  String fieldPasswordTooLong(int max) {
    return 'ਵੱਧ ਤੋਂ ਵੱਧ $max ਅੱਖਰਾਂ ਦੀ ਵਰਤੋਂ ਕਰੋ।';
  }

  @override
  String get errPasswordIsIdentity =>
      'ਪਾਸਵਰਡ ਤੁਹਾਡਾ ਈਮੇਲ ਪਤਾ ਨਹੀਂ ਹੋਣਾ ਚਾਹੀਦਾ, ਅਤੇ ਨਾ ਹੀ ਇਸ ਵਿੱਚ ਉਹ ਸ਼ਾਮਲ ਹੋਣਾ ਚਾਹੀਦਾ ਹੈ।';

  @override
  String get errPasswordBreached =>
      'ਇਹ ਪਾਸਵਰਡ ਜਾਣੇ-ਪਛਾਣੇ ਡਾਟਾ ਭੰਗਾਂ ਵਿੱਚ ਦੇਖਿਆ ਗਿਆ ਹੈ ਅਤੇ ਇਸਦਾ ਅੰਦਾਜ਼ਾ ਸੌਖਿਆਂ ਹੀ ਲੱਗ ਸਕਦਾ ਹੈ। ਕੋਈ ਹੋਰ ਚੁਣੋ।';

  @override
  String get showPassword => 'ਪਾਸਵਰਡ ਦਿਖਾਓ';

  @override
  String get hidePassword => 'ਪਾਸਵਰਡ ਲੁਕਾਓ';

  @override
  String get signInWithBusiness => 'ਆਪਣੇ ਕਾਰੋਬਾਰ ਨਾਲ ਸਾਈਨ ਇਨ ਕਰੋ';

  @override
  String continueWithBusiness(String business) {
    return '$business ਨਾਲ ਜਾਰੀ ਰੱਖੋ';
  }

  @override
  String get businessSignInNameHelp =>
      'ਤੁਹਾਡੇ ਕਾਰੋਬਾਰ ਦਾ ਸਾਈਨ-ਇਨ ਨਾਮ। ਇਹ ਤੁਹਾਡੇ ਮੈਨੇਜਰ ਕੋਲ ਹੈ।';

  @override
  String get fieldBusinessSignInName => 'ਸਾਈਨ-ਇਨ ਨਾਮ';

  @override
  String get fieldBusinessSignInNameHint => 'ਜਿਵੇਂ: acme-foods';

  @override
  String get actionSignIn => 'ਸਾਈਨ ਇਨ ਕਰੋ';

  @override
  String get actionCreateAccount => 'ਖਾਤਾ ਬਣਾਓ';

  @override
  String get actionCancel => 'ਰੱਦ ਕਰੋ';

  @override
  String get actionContinue => 'ਜਾਰੀ ਰੱਖੋ';

  @override
  String get toggleHaveAccount => 'ਪਹਿਲਾਂ ਤੋਂ ਹੀ ਖਾਤਾ ਹੈ? ਸਾਈਨ ਇਨ ਕਰੋ';

  @override
  String get toggleNewHere => 'ਨਵੇਂ ਹੋ? ਇੱਕ ਖਾਤਾ ਬਣਾਓ';

  @override
  String get errInvalidCredentials => 'ਈਮੇਲ ਜਾਂ ਪਾਸਵਰਡ ਗਲਤ ਹੈ।';

  @override
  String get errEmailExists => 'ਇਸ ਈਮੇਲ ਨਾਲ ਇੱਕ ਖਾਤਾ ਪਹਿਲਾਂ ਹੀ ਮੌਜੂਦ ਹੈ।';

  @override
  String get errNetwork => 'ਸਰਵਰ ਤੱਕ ਨਹੀਂ ਪਹੁੰਚਿਆ ਜਾ ਸਕਿਆ। ਆਪਣਾ ਕਨੈਕਸ਼ਨ ਜਾਂਚੋ।';

  @override
  String get errGeneric => 'ਕੁਝ ਗਲਤ ਹੋ ਗਿਆ। ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੋ।';

  @override
  String get ssoErrNotFound =>
      'ਇਸ ਨਾਮ ਨਾਲ ਕੋਈ ਕਾਰੋਬਾਰ ਸਾਈਨ ਇਨ ਨਹੀਂ ਕਰਦਾ। ਆਪਣੇ ਮੈਨੇਜਰ ਨਾਲ ਇਹ ਜਾਂਚੋ।';

  @override
  String get ssoErrRequired =>
      'ਤੁਹਾਡਾ ਕਾਰੋਬਾਰ ਤੁਹਾਨੂੰ ਆਪਣੇ ਖੁਦ ਦੇ ਸਾਈਨ-ਇਨ ਪੇਜ ਰਾਹੀਂ ਸਾਈਨ ਇਨ ਕਰਵਾਉਂਦਾ ਹੈ। ਹੇਠਾਂ \"ਆਪਣੇ ਕਾਰੋਬਾਰ ਨਾਲ ਸਾਈਨ ਇਨ ਕਰੋ\" ਵਰਤੋ।';

  @override
  String get ssoErrNoAccount =>
      'ਤੁਸੀਂ ਆਪਣੇ ਕਾਰੋਬਾਰ ਨਾਲ ਸਾਈਨ ਇਨ ਕੀਤਾ, ਪਰ ਇਸਨੇ ਤੁਹਾਨੂੰ ਹਾਲੇ ਇੱਥੇ ਸ਼ਾਮਲ ਨਹੀਂ ਕੀਤਾ। ਆਪਣੇ ਮੈਨੇਜਰ ਨੂੰ ਤੁਹਾਨੂੰ ਕਰਮਚਾਰੀ ਵਜੋਂ ਸ਼ਾਮਲ ਕਰਨ ਲਈ ਕਹੋ।';

  @override
  String get ssoErrEmailUnverified =>
      'ਤੁਹਾਡੇ ਕਾਰੋਬਾਰ ਦੇ ਸਾਈਨ-ਇਨ ਪੇਜ ਨੇ ਤੁਹਾਡੇ ਈਮੇਲ ਪਤੇ ਦੀ ਪੁਸ਼ਟੀ ਨਹੀਂ ਕੀਤੀ, ਇਸ ਲਈ ਇਸਨੂੰ ਤੁਹਾਡੇ ਲੌਗਇਨ ਨਾਲ ਮਿਲਾਇਆ ਨਹੀਂ ਜਾ ਸਕਿਆ।';

  @override
  String get ssoErrEmailMissing =>
      'ਤੁਹਾਡੇ ਕਾਰੋਬਾਰ ਦੇ ਸਾਈਨ-ਇਨ ਪੇਜ ਨੇ ਤੁਹਾਡਾ ਈਮੇਲ ਪਤਾ ਸਾਂਝਾ ਨਹੀਂ ਕੀਤਾ।';

  @override
  String get ssoErrAlreadyLinked =>
      'ਤੁਹਾਡਾ ਲੌਗਇਨ ਤੁਹਾਡੇ ਕਾਰੋਬਾਰ ਵਿੱਚ ਕਿਸੇ ਹੋਰ ਨਾਲ ਜੁੜਿਆ ਹੋਇਆ ਹੈ। ਇਸਨੂੰ ਵੱਖ ਕਰਨ ਲਈ ਮਾਲਕ ਨੂੰ ਕਹੋ।';

  @override
  String get ssoErrAccountUnavailable =>
      'ਇਹ ਲੌਗਇਨ ਹੁਣ ਇੱਥੇ ਸਾਈਨ ਇਨ ਨਹੀਂ ਕਰ ਸਕਦਾ।';

  @override
  String get ssoErrCancelled => 'ਸਾਈਨ ਇਨ ਰੱਦ ਕਰ ਦਿੱਤਾ ਗਿਆ।';

  @override
  String get ssoErrExpired =>
      'ਉਸ ਸਾਈਨ ਇਨ ਵਿੱਚ ਬਹੁਤ ਸਮਾਂ ਲੱਗ ਗਿਆ ਜਾਂ ਇਹ ਪਹਿਲਾਂ ਹੀ ਵਰਤਿਆ ਜਾ ਚੁੱਕਾ ਸੀ। ਦੁਬਾਰਾ ਸ਼ੁਰੂ ਕਰੋ।';

  @override
  String get ssoErrReauthRequired => 'ਆਪਣੇ ਕਾਰੋਬਾਰ ਨਾਲ ਦੁਬਾਰਾ ਸਾਈਨ ਇਨ ਕਰੋ।';

  @override
  String get ssoErrNotReady =>
      'ਤੁਹਾਡੇ ਕਾਰੋਬਾਰ ਦਾ ਸਿੰਗਲ ਸਾਈਨ-ਆਨ ਹਾਲੇ ਪੂਰਾ ਨਹੀਂ ਹੋਇਆ। ਇਸਦੇ ਮਾਲਕ ਨੂੰ ਪੁੱਛੋ।';

  @override
  String get ssoErrUnavailable =>
      'ਇੱਥੇ ਆਪਣੇ ਕਾਰੋਬਾਰ ਨਾਲ ਸਾਈਨ ਇਨ ਕਰਨਾ ਉਪਲਬਧ ਨਹੀਂ ਹੈ।';

  @override
  String get ssoErrProviderUnreachable =>
      'ਤੁਹਾਡੇ ਕਾਰੋਬਾਰ ਦੇ ਸਾਈਨ-ਇਨ ਪੇਜ ਤੱਕ ਨਹੀਂ ਪਹੁੰਚਿਆ ਜਾ ਸਕਿਆ। ਥੋੜ੍ਹੀ ਦੇਰ ਬਾਅਦ ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੋ।';

  @override
  String get errTenantInactive =>
      'ਇਸ ਕਾਰੋਬਾਰ ਦਾ ਖਾਤਾ ਮੁਅੱਤਲ ਕਰ ਦਿੱਤਾ ਗਿਆ ਹੈ। ਸਹਾਇਤਾ ਨਾਲ ਸੰਪਰਕ ਕਰੋ।';

  @override
  String get ssoErrGeneric =>
      'ਤੁਹਾਡੇ ਕਾਰੋਬਾਰ ਦਾ ਸਾਈਨ-ਇਨ ਪੇਜ ਤੁਹਾਨੂੰ ਸਾਈਨ ਇਨ ਨਹੀਂ ਕਰਵਾ ਸਕਿਆ। ਸੈਟਿੰਗਾਂ ਜਾਂਚਣ ਲਈ ਇਸਦੇ ਮਾਲਕ ਨੂੰ ਕਹੋ।';

  @override
  String get forgotPassword => 'ਪਾਸਵਰਡ ਭੁੱਲ ਗਏ?';

  @override
  String get forgotPasswordTitle => 'ਕੀ ਤੁਸੀਂ ਆਪਣਾ ਪਾਸਵਰਡ ਭੁੱਲ ਗਏ ਹੋ?';

  @override
  String get forgotPasswordIntro =>
      'ਆਪਣਾ ਈਮੇਲ ਪਤਾ ਦਰਜ ਕਰੋ। ਜੇ ਕੋਈ ਖਾਤਾ ਇਸਦੀ ਵਰਤੋਂ ਕਰਦਾ ਹੈ, ਤਾਂ ਅਸੀਂ ਪਾਸਵਰਡ ਰੀਸੈੱਟ ਕਰਨ ਲਈ ਇੱਕ ਲਿੰਕ ਭੇਜਾਂਗੇ।';

  @override
  String get forgotPasswordSubmit => 'ਲਿੰਕ ਭੇਜੋ';

  @override
  String get forgotPasswordSent =>
      'ਜੇ ਕੋਈ ਖਾਤਾ ਇਸ ਪਤੇ ਦੀ ਵਰਤੋਂ ਕਰਦਾ ਹੈ, ਤਾਂ ਅਸੀਂ ਇੱਕ ਲਿੰਕ ਭੇਜ ਦਿੱਤਾ ਹੈ। ਇਹ ਤੀਹ ਮਿੰਟ ਲਈ, ਇੱਕ ਵਾਰ ਕੰਮ ਕਰਦਾ ਹੈ।';

  @override
  String get resetPasswordTitle => 'ਨਵਾਂ ਪਾਸਵਰਡ ਚੁਣੋ';

  @override
  String get resetPasswordNewLabel => 'ਨਵਾਂ ਪਾਸਵਰਡ';

  @override
  String get resetPasswordConfirmLabel => 'ਨਵੇਂ ਪਾਸਵਰਡ ਦੀ ਪੁਸ਼ਟੀ ਕਰੋ';

  @override
  String get resetPasswordMismatch =>
      'ਇਹ ਉੱਪਰ ਦਿੱਤੇ ਨਵੇਂ ਪਾਸਵਰਡ ਨਾਲ ਮੇਲ ਨਹੀਂ ਖਾਂਦਾ।';

  @override
  String get resetPasswordSubmit => 'ਪਾਸਵਰਡ ਬਦਲੋ';

  @override
  String get resetPasswordDone => 'ਪਾਸਵਰਡ ਬਦਲ ਗਿਆ — ਇਸ ਨਾਲ ਸਾਈਨ ਇਨ ਕਰੋ।';

  @override
  String get resetPasswordDoneStorefront =>
      'ਸਟੋਰਫਰੰਟ ਤੋਂ ਖਰੀਦਦਾਰੀ ਕਰ ਰਹੇ ਹੋ? ਤੁਸੀਂ ਦੁਕਾਨ ਤੋਂ ਸਾਈਨ ਇਨ ਕਰ ਸਕਦੇ ਹੋ।';

  @override
  String get resetPasswordTokenInvalid =>
      'ਇਹ ਲਿੰਕ ਖਤਮ ਹੋ ਗਿਆ ਹੈ ਜਾਂ ਪਹਿਲਾਂ ਹੀ ਵਰਤਿਆ ਜਾ ਚੁੱਕਾ ਹੈ — ਨਵੇਂ ਲਈ ਬੇਨਤੀ ਕਰੋ।';

  @override
  String get resetPasswordRequestNew => 'ਨਵੇਂ ਲਿੰਕ ਲਈ ਬੇਨਤੀ ਕਰੋ';
}
