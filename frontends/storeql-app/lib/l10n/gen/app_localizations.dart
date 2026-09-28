import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_ar.dart';
import 'app_localizations_bn.dart';
import 'app_localizations_en.dart';
import 'app_localizations_gu.dart';
import 'app_localizations_pa.dart';
import 'app_localizations_pl.dart';
import 'app_localizations_ro.dart';
import 'app_localizations_ur.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'gen/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations)!;
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('ar'),
    Locale('bn'),
    Locale('en'),
    Locale('gu'),
    Locale('pa'),
    Locale('pl'),
    Locale('ro'),
    Locale('ur'),
  ];

  /// Login screen subtitle (sign-in mode)
  ///
  /// In en, this message translates to:
  /// **'Sign in to continue'**
  String get signInToContinue;

  /// Login screen subtitle (register mode)
  ///
  /// In en, this message translates to:
  /// **'Create your account'**
  String get createYourAccount;

  /// No description provided for @fieldEmail.
  ///
  /// In en, this message translates to:
  /// **'Email'**
  String get fieldEmail;

  /// No description provided for @fieldEmailInvalid.
  ///
  /// In en, this message translates to:
  /// **'Enter a valid email'**
  String get fieldEmailInvalid;

  /// No description provided for @fieldPhoneOptional.
  ///
  /// In en, this message translates to:
  /// **'Phone (optional)'**
  String get fieldPhoneOptional;

  /// No description provided for @fieldPassword.
  ///
  /// In en, this message translates to:
  /// **'Password'**
  String get fieldPassword;

  /// Sign-in: the only check a password gets before it is sent
  ///
  /// In en, this message translates to:
  /// **'Enter your password'**
  String get fieldPasswordRequired;

  /// Sign-up: iam-svc's PasswordPolicy minimum (PASSWORD_TOO_SHORT); also shown under the field as the rule
  ///
  /// In en, this message translates to:
  /// **'Use at least {min} characters. A phrase of a few words is easiest to remember and hardest to guess; spaces are fine.'**
  String fieldPasswordTooShort(int min);

  /// Sign-up: iam-svc's PasswordPolicy maximum (PASSWORD_TOO_LONG)
  ///
  /// In en, this message translates to:
  /// **'Use at most {max} characters.'**
  String fieldPasswordTooLong(int max);

  /// Sign-up refused with PASSWORD_IS_IDENTITY
  ///
  /// In en, this message translates to:
  /// **'The password must not be, or contain, your email address.'**
  String get errPasswordIsIdentity;

  /// Sign-up refused with PASSWORD_BREACHED
  ///
  /// In en, this message translates to:
  /// **'This password appears in known data breaches and would be guessed. Choose another.'**
  String get errPasswordBreached;

  /// Tooltip of the eye button on a password field
  ///
  /// In en, this message translates to:
  /// **'Show password'**
  String get showPassword;

  /// Tooltip of the eye button on a password field
  ///
  /// In en, this message translates to:
  /// **'Hide password'**
  String get hidePassword;

  /// Single sign-on through the business's identity provider: the button and its dialog's title
  ///
  /// In en, this message translates to:
  /// **'Sign in with your business'**
  String get signInWithBusiness;

  /// A password refused because the business signs its staff in through its provider; the server names the business's sign-in name
  ///
  /// In en, this message translates to:
  /// **'Continue with {business}'**
  String continueWithBusiness(String business);

  /// No description provided for @businessSignInNameHelp.
  ///
  /// In en, this message translates to:
  /// **'Your business\'s sign-in name. Your manager has it.'**
  String get businessSignInNameHelp;

  /// No description provided for @fieldBusinessSignInName.
  ///
  /// In en, this message translates to:
  /// **'Sign-in name'**
  String get fieldBusinessSignInName;

  /// No description provided for @fieldBusinessSignInNameHint.
  ///
  /// In en, this message translates to:
  /// **'e.g. acme-foods'**
  String get fieldBusinessSignInNameHint;

  /// No description provided for @actionSignIn.
  ///
  /// In en, this message translates to:
  /// **'Sign in'**
  String get actionSignIn;

  /// No description provided for @actionCreateAccount.
  ///
  /// In en, this message translates to:
  /// **'Create account'**
  String get actionCreateAccount;

  /// No description provided for @actionCancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get actionCancel;

  /// No description provided for @actionContinue.
  ///
  /// In en, this message translates to:
  /// **'Continue'**
  String get actionContinue;

  /// No description provided for @toggleHaveAccount.
  ///
  /// In en, this message translates to:
  /// **'Already have an account? Sign in'**
  String get toggleHaveAccount;

  /// No description provided for @toggleNewHere.
  ///
  /// In en, this message translates to:
  /// **'New here? Create an account'**
  String get toggleNewHere;

  /// No description provided for @errInvalidCredentials.
  ///
  /// In en, this message translates to:
  /// **'Invalid email or password.'**
  String get errInvalidCredentials;

  /// No description provided for @errEmailExists.
  ///
  /// In en, this message translates to:
  /// **'An account with this email already exists.'**
  String get errEmailExists;

  /// No description provided for @errNetwork.
  ///
  /// In en, this message translates to:
  /// **'Cannot reach the server. Check your connection.'**
  String get errNetwork;

  /// No description provided for @errGeneric.
  ///
  /// In en, this message translates to:
  /// **'Something went wrong. Please try again.'**
  String get errGeneric;

  /// Sign-in: single sign-on refusal (SSO_NOT_FOUND)
  ///
  /// In en, this message translates to:
  /// **'No business signs in with that name. Check it with your manager.'**
  String get ssoErrNotFound;

  /// Sign-in: single sign-on refusal (SSO_REQUIRED)
  ///
  /// In en, this message translates to:
  /// **'Your business signs you in through its own sign-in page. Use \"Sign in with your business\" below.'**
  String get ssoErrRequired;

  /// Sign-in: single sign-on refusal (SSO_NO_ACCOUNT)
  ///
  /// In en, this message translates to:
  /// **'You signed in with your business, but it has not added you here yet. Ask your manager to add you as staff.'**
  String get ssoErrNoAccount;

  /// Sign-in: single sign-on refusal (SSO_EMAIL_UNVERIFIED)
  ///
  /// In en, this message translates to:
  /// **'Your business\'s sign-in page has not verified your email address, so it could not be matched to your login.'**
  String get ssoErrEmailUnverified;

  /// Sign-in: single sign-on refusal (SSO_EMAIL_MISSING)
  ///
  /// In en, this message translates to:
  /// **'Your business\'s sign-in page did not share your email address.'**
  String get ssoErrEmailMissing;

  /// Sign-in: single sign-on refusal (SSO_ALREADY_LINKED)
  ///
  /// In en, this message translates to:
  /// **'Your login is linked to someone else at your business. Ask the owner to unlink it.'**
  String get ssoErrAlreadyLinked;

  /// Sign-in: single sign-on refusal (SSO_ACCOUNT_UNAVAILABLE)
  ///
  /// In en, this message translates to:
  /// **'This login can no longer sign in here.'**
  String get ssoErrAccountUnavailable;

  /// Sign-in: single sign-on refusal (SSO_CANCELLED)
  ///
  /// In en, this message translates to:
  /// **'Sign-in was cancelled.'**
  String get ssoErrCancelled;

  /// Sign-in: single sign-on refusal (SSO_STATE_INVALID and SSO_TICKET_INVALID)
  ///
  /// In en, this message translates to:
  /// **'That sign-in took too long or was already used. Start again.'**
  String get ssoErrExpired;

  /// Sign-in: single sign-on refusal (SSO_REAUTH_REQUIRED)
  ///
  /// In en, this message translates to:
  /// **'Sign in with your business again.'**
  String get ssoErrReauthRequired;

  /// Sign-in: single sign-on refusal (SSO_NOT_READY)
  ///
  /// In en, this message translates to:
  /// **'Your business\'s single sign-on is not finished. Ask its owner.'**
  String get ssoErrNotReady;

  /// Sign-in: single sign-on refusal (SSO_UNAVAILABLE)
  ///
  /// In en, this message translates to:
  /// **'Signing in with your business is not available here.'**
  String get ssoErrUnavailable;

  /// Sign-in: single sign-on refusal (SSO_PROVIDER_UNREACHABLE)
  ///
  /// In en, this message translates to:
  /// **'Your business\'s sign-in page could not be reached. Try again shortly.'**
  String get ssoErrProviderUnreachable;

  /// Sign-in: single sign-on refusal (TENANT_INACTIVE)
  ///
  /// In en, this message translates to:
  /// **'This business account is suspended. Contact support.'**
  String get errTenantInactive;

  /// Sign-in: single sign-on refusal (any other single sign-on refusal)
  ///
  /// In en, this message translates to:
  /// **'Your business\'s sign-in page could not sign you in. Ask its owner to check the settings.'**
  String get ssoErrGeneric;

  /// Sign-in card / storefront sign-in dialog: link to the forgot-password request page (not shown on the platform console's login)
  ///
  /// In en, this message translates to:
  /// **'Forgot password?'**
  String get forgotPassword;

  /// Forgot-password request page: heading
  ///
  /// In en, this message translates to:
  /// **'Forgot your password?'**
  String get forgotPasswordTitle;

  /// Forgot-password request page: subheading, shown before anything is submitted
  ///
  /// In en, this message translates to:
  /// **'Enter your email address. If an account uses it, we\'ll send a link to reset the password.'**
  String get forgotPasswordIntro;

  /// Forgot-password request page: submit button
  ///
  /// In en, this message translates to:
  /// **'Send link'**
  String get forgotPasswordSubmit;

  /// Forgot-password request page: shown after ANY answer (known address, unknown, suspended, throttled, even a 400) — never a different word for a different case. Only a network failure (no answer at all) shows errNetwork instead.
  ///
  /// In en, this message translates to:
  /// **'If an account uses that address, we have sent a link. It works for 30 minutes, once.'**
  String get forgotPasswordSent;

  /// Reset-password page (/reset-password/:token): heading
  ///
  /// In en, this message translates to:
  /// **'Choose a new password'**
  String get resetPasswordTitle;

  /// Reset-password page: the new password field
  ///
  /// In en, this message translates to:
  /// **'New password'**
  String get resetPasswordNewLabel;

  /// Reset-password page: the confirmation field
  ///
  /// In en, this message translates to:
  /// **'Confirm new password'**
  String get resetPasswordConfirmLabel;

  /// Reset-password page: the confirmation field does not match the new password
  ///
  /// In en, this message translates to:
  /// **'This does not match the new password above.'**
  String get resetPasswordMismatch;

  /// Reset-password page: submit button
  ///
  /// In en, this message translates to:
  /// **'Change password'**
  String get resetPasswordSubmit;

  /// Reset-password page: success (200 reset:true) — no session is issued, so this leads to sign-in
  ///
  /// In en, this message translates to:
  /// **'Password changed — sign in with it.'**
  String get resetPasswordDone;

  /// Reset-password page: success — a note for a shopper login, whose sign-in lives in the storefront, not the sign-in button shown alongside
  ///
  /// In en, this message translates to:
  /// **'Shopping from the storefront? You can sign in from the shop.'**
  String get resetPasswordDoneStorefront;

  /// Reset-password page: 400 PASSWORD_RESET_TOKEN_INVALID (unknown, expired, used or replaced — one code, so the page cannot tell which)
  ///
  /// In en, this message translates to:
  /// **'This link has expired or was already used — ask for a new one.'**
  String get resetPasswordTokenInvalid;

  /// Reset-password page: button on the token-invalid state, to the forgot-password request page
  ///
  /// In en, this message translates to:
  /// **'Ask for a new link'**
  String get resetPasswordRequestNew;
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) => <String>[
    'ar',
    'bn',
    'en',
    'gu',
    'pa',
    'pl',
    'ro',
    'ur',
  ].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'ar':
      return AppLocalizationsAr();
    case 'bn':
      return AppLocalizationsBn();
    case 'en':
      return AppLocalizationsEn();
    case 'gu':
      return AppLocalizationsGu();
    case 'pa':
      return AppLocalizationsPa();
    case 'pl':
      return AppLocalizationsPl();
    case 'ro':
      return AppLocalizationsRo();
    case 'ur':
      return AppLocalizationsUr();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
