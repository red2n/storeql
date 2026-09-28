// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get signInToContinue => 'Sign in to continue';

  @override
  String get createYourAccount => 'Create your account';

  @override
  String get fieldEmail => 'Email';

  @override
  String get fieldEmailInvalid => 'Enter a valid email';

  @override
  String get fieldPhoneOptional => 'Phone (optional)';

  @override
  String get fieldPassword => 'Password';

  @override
  String get fieldPasswordRequired => 'Enter your password';

  @override
  String fieldPasswordTooShort(int min) {
    return 'Use at least $min characters. A phrase of a few words is easiest to remember and hardest to guess; spaces are fine.';
  }

  @override
  String fieldPasswordTooLong(int max) {
    return 'Use at most $max characters.';
  }

  @override
  String get errPasswordIsIdentity =>
      'The password must not be, or contain, your email address.';

  @override
  String get errPasswordBreached =>
      'This password appears in known data breaches and would be guessed. Choose another.';

  @override
  String get showPassword => 'Show password';

  @override
  String get hidePassword => 'Hide password';

  @override
  String get signInWithBusiness => 'Sign in with your business';

  @override
  String continueWithBusiness(String business) {
    return 'Continue with $business';
  }

  @override
  String get businessSignInNameHelp =>
      'Your business\'s sign-in name. Your manager has it.';

  @override
  String get fieldBusinessSignInName => 'Sign-in name';

  @override
  String get fieldBusinessSignInNameHint => 'e.g. acme-foods';

  @override
  String get actionSignIn => 'Sign in';

  @override
  String get actionCreateAccount => 'Create account';

  @override
  String get actionCancel => 'Cancel';

  @override
  String get actionContinue => 'Continue';

  @override
  String get toggleHaveAccount => 'Already have an account? Sign in';

  @override
  String get toggleNewHere => 'New here? Create an account';

  @override
  String get errInvalidCredentials => 'Invalid email or password.';

  @override
  String get errEmailExists => 'An account with this email already exists.';

  @override
  String get errNetwork => 'Cannot reach the server. Check your connection.';

  @override
  String get errGeneric => 'Something went wrong. Please try again.';

  @override
  String get ssoErrNotFound =>
      'No business signs in with that name. Check it with your manager.';

  @override
  String get ssoErrRequired =>
      'Your business signs you in through its own sign-in page. Use \"Sign in with your business\" below.';

  @override
  String get ssoErrNoAccount =>
      'You signed in with your business, but it has not added you here yet. Ask your manager to add you as staff.';

  @override
  String get ssoErrEmailUnverified =>
      'Your business\'s sign-in page has not verified your email address, so it could not be matched to your login.';

  @override
  String get ssoErrEmailMissing =>
      'Your business\'s sign-in page did not share your email address.';

  @override
  String get ssoErrAlreadyLinked =>
      'Your login is linked to someone else at your business. Ask the owner to unlink it.';

  @override
  String get ssoErrAccountUnavailable =>
      'This login can no longer sign in here.';

  @override
  String get ssoErrCancelled => 'Sign-in was cancelled.';

  @override
  String get ssoErrExpired =>
      'That sign-in took too long or was already used. Start again.';

  @override
  String get ssoErrReauthRequired => 'Sign in with your business again.';

  @override
  String get ssoErrNotReady =>
      'Your business\'s single sign-on is not finished. Ask its owner.';

  @override
  String get ssoErrUnavailable =>
      'Signing in with your business is not available here.';

  @override
  String get ssoErrProviderUnreachable =>
      'Your business\'s sign-in page could not be reached. Try again shortly.';

  @override
  String get errTenantInactive =>
      'This business account is suspended. Contact support.';

  @override
  String get ssoErrGeneric =>
      'Your business\'s sign-in page could not sign you in. Ask its owner to check the settings.';

  @override
  String get forgotPassword => 'Forgot password?';

  @override
  String get forgotPasswordTitle => 'Forgot your password?';

  @override
  String get forgotPasswordIntro =>
      'Enter your email address. If an account uses it, we\'ll send a link to reset the password.';

  @override
  String get forgotPasswordSubmit => 'Send link';

  @override
  String get forgotPasswordSent =>
      'If an account uses that address, we have sent a link. It works for 30 minutes, once.';

  @override
  String get resetPasswordTitle => 'Choose a new password';

  @override
  String get resetPasswordNewLabel => 'New password';

  @override
  String get resetPasswordConfirmLabel => 'Confirm new password';

  @override
  String get resetPasswordMismatch =>
      'This does not match the new password above.';

  @override
  String get resetPasswordSubmit => 'Change password';

  @override
  String get resetPasswordDone => 'Password changed — sign in with it.';

  @override
  String get resetPasswordDoneStorefront =>
      'Shopping from the storefront? You can sign in from the shop.';

  @override
  String get resetPasswordTokenInvalid =>
      'This link has expired or was already used — ask for a new one.';

  @override
  String get resetPasswordRequestNew => 'Ask for a new link';
}
