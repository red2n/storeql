// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Gujarati (`gu`).
class AppLocalizationsGu extends AppLocalizations {
  AppLocalizationsGu([String locale = 'gu']) : super(locale);

  @override
  String get signInToContinue => 'ચાલુ રાખવા માટે સાઇન ઇન કરો';

  @override
  String get createYourAccount => 'તમારું ખાતું બનાવો';

  @override
  String get fieldEmail => 'ઇમેઇલ';

  @override
  String get fieldEmailInvalid => 'એક યોગ્ય ઇમેઇલ સરનામું દાખલ કરો';

  @override
  String get fieldPhoneOptional => 'ફોન (વૈકલ્પિક)';

  @override
  String get fieldPassword => 'પાસવર્ડ';

  @override
  String get fieldPasswordRequired => 'તમારો પાસવર્ડ દાખલ કરો';

  @override
  String fieldPasswordTooShort(int min) {
    return 'ઓછામાં ઓછા $min અક્ષરોનો ઉપયોગ કરો. થોડા શબ્દોનું એક વાક્ય યાદ રાખવું સૌથી સહેલું અને અનુમાન લગાવવું સૌથી અઘરું છે; સ્પેસ વાપરી શકાય છે.';
  }

  @override
  String fieldPasswordTooLong(int max) {
    return 'વધુમાં વધુ $max અક્ષરોનો ઉપયોગ કરો.';
  }

  @override
  String get errPasswordIsIdentity =>
      'પાસવર્ડ તમારું ઇમેઇલ સરનામું ન હોવો જોઈએ, અને તેમાં તે સામેલ પણ ન હોવું જોઈએ.';

  @override
  String get errPasswordBreached =>
      'આ પાસવર્ડ જાણીતા ડેટા ભંગોમાં જોવા મળ્યો છે અને સહેલાઈથી અનુમાન લગાવી શકાય છે. બીજો પસંદ કરો.';

  @override
  String get showPassword => 'પાસવર્ડ બતાવો';

  @override
  String get hidePassword => 'પાસવર્ડ છુપાવો';

  @override
  String get signInWithBusiness => 'તમારા વ્યવસાય સાથે સાઇન ઇન કરો';

  @override
  String continueWithBusiness(String business) {
    return '$business સાથે ચાલુ રાખો';
  }

  @override
  String get businessSignInNameHelp =>
      'તમારા વ્યવસાયનું સાઇન-ઇન નામ. તે તમારા મેનેજર પાસે છે.';

  @override
  String get fieldBusinessSignInName => 'સાઇન-ઇન નામ';

  @override
  String get fieldBusinessSignInNameHint => 'દા.ત. acme-foods';

  @override
  String get actionSignIn => 'સાઇન ઇન કરો';

  @override
  String get actionCreateAccount => 'ખાતું બનાવો';

  @override
  String get actionCancel => 'રદ કરો';

  @override
  String get actionContinue => 'ચાલુ રાખો';

  @override
  String get toggleHaveAccount => 'પહેલેથી ખાતું છે? સાઇન ઇન કરો';

  @override
  String get toggleNewHere => 'નવા છો? એક ખાતું બનાવો';

  @override
  String get errInvalidCredentials => 'ઇમેઇલ અથવા પાસવર્ડ ખોટો છે.';

  @override
  String get errEmailExists => 'આ ઇમેઇલ સાથે એક ખાતું પહેલેથી અસ્તિત્વમાં છે.';

  @override
  String get errNetwork => 'સર્વર સુધી પહોંચી શકાતું નથી. તમારું જોડાણ ચકાસો.';

  @override
  String get errGeneric => 'કંઈક ખોટું થયું. ફરી પ્રયાસ કરો.';

  @override
  String get ssoErrNotFound =>
      'આ નામથી કોઈ વ્યવસાય સાઇન ઇન કરતો નથી. તમારા મેનેજર સાથે તે ચકાસો.';

  @override
  String get ssoErrRequired =>
      'તમારો વ્યવસાય તમને તેના પોતાના સાઇન-ઇન પેજ દ્વારા સાઇન ઇન કરાવે છે. નીચે \"તમારા વ્યવસાય સાથે સાઇન ઇન કરો\" નો ઉપયોગ કરો.';

  @override
  String get ssoErrNoAccount =>
      'તમે તમારા વ્યવસાય સાથે સાઇન ઇન કર્યું, પણ તેણે તમને હજી અહીં ઉમેર્યા નથી. તમારા મેનેજરને તમને કર્મચારી તરીકે ઉમેરવા કહો.';

  @override
  String get ssoErrEmailUnverified =>
      'તમારા વ્યવસાયના સાઇન-ઇન પેજે તમારું ઇમેઇલ સરનામું ચકાસ્યું નથી, તેથી તેને તમારા લોગિન સાથે મેળવી શકાયું નહીં.';

  @override
  String get ssoErrEmailMissing =>
      'તમારા વ્યવસાયના સાઇન-ઇન પેજે તમારું ઇમેઇલ સરનામું શેર કર્યું નથી.';

  @override
  String get ssoErrAlreadyLinked =>
      'તમારું લોગિન તમારા વ્યવસાયમાં બીજા કોઈ સાથે જોડાયેલું છે. તેને અલગ કરવા માલિકને કહો.';

  @override
  String get ssoErrAccountUnavailable =>
      'આ લોગિન હવે અહીં સાઇન ઇન કરી શકતું નથી.';

  @override
  String get ssoErrCancelled => 'સાઇન ઇન રદ કરવામાં આવ્યું.';

  @override
  String get ssoErrExpired =>
      'તે સાઇન ઇનમાં બહુ સમય લાગ્યો અથવા તે પહેલેથી વપરાઈ ગયું. ફરી શરૂ કરો.';

  @override
  String get ssoErrReauthRequired => 'તમારા વ્યવસાય સાથે ફરી સાઇન ઇન કરો.';

  @override
  String get ssoErrNotReady =>
      'તમારા વ્યવસાયનું સિંગલ સાઇન-ઓન હજી પૂરું થયું નથી. તેના માલિકને પૂછો.';

  @override
  String get ssoErrUnavailable =>
      'અહીં તમારા વ્યવસાય સાથે સાઇન ઇન કરવું શક્ય નથી.';

  @override
  String get ssoErrProviderUnreachable =>
      'તમારા વ્યવસાયના સાઇન-ઇન પેજ સુધી પહોંચી શકાયું નહીં. થોડી વાર પછી ફરી પ્રયાસ કરો.';

  @override
  String get errTenantInactive =>
      'આ વ્યવસાયનું ખાતું સ્થગિત કરવામાં આવ્યું છે. સપોર્ટનો સંપર્ક કરો.';

  @override
  String get ssoErrGeneric =>
      'તમારા વ્યવસાયનું સાઇન-ઇન પેજ તમને સાઇન ઇન કરાવી શક્યું નહીં. સેટિંગ્સ ચકાસવા તેના માલિકને કહો.';

  @override
  String get forgotPassword => 'પાસવર્ડ ભૂલી ગયા?';

  @override
  String get forgotPasswordTitle => 'શું તમે તમારો પાસવર્ડ ભૂલી ગયા છો?';

  @override
  String get forgotPasswordIntro =>
      'તમારું ઇમેઇલ સરનામું દાખલ કરો. જો કોઈ ખાતું આનો ઉપયોગ કરે છે, તો અમે પાસવર્ડ ફરીથી સેટ કરવા માટે એક લિંક મોકલીશું.';

  @override
  String get forgotPasswordSubmit => 'લિંક મોકલો';

  @override
  String get forgotPasswordSent =>
      'જો કોઈ ખાતું આ સરનામાનો ઉપયોગ કરે છે, તો અમે એક લિંક મોકલી છે. તે ત્રીસ મિનિટ માટે, એક જ વાર કામ કરે છે.';

  @override
  String get resetPasswordTitle => 'નવો પાસવર્ડ પસંદ કરો';

  @override
  String get resetPasswordNewLabel => 'નવો પાસવર્ડ';

  @override
  String get resetPasswordConfirmLabel => 'નવા પાસવર્ડની પુષ્ટિ કરો';

  @override
  String get resetPasswordMismatch =>
      'આ ઉપર આપેલા નવા પાસવર્ડ સાથે મેળ ખાતું નથી.';

  @override
  String get resetPasswordSubmit => 'પાસવર્ડ બદલો';

  @override
  String get resetPasswordDone => 'પાસવર્ડ બદલાયો — તેનાથી સાઇન ઇન કરો.';

  @override
  String get resetPasswordDoneStorefront =>
      'સ્ટોરફ્રન્ટ પરથી ખરીદી કરી રહ્યાં છો? તમે દુકાનમાંથી સાઇન ઇન કરી શકો છો.';

  @override
  String get resetPasswordTokenInvalid =>
      'આ લિંકની મુદત પૂરી થઈ ગઈ છે અથવા તે પહેલેથી જ વપરાઈ ગઈ છે — નવી માટે વિનંતી કરો.';

  @override
  String get resetPasswordRequestNew => 'નવી લિંક માટે વિનંતી કરો';
}
