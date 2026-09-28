// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Urdu (`ur`).
class AppLocalizationsUr extends AppLocalizations {
  AppLocalizationsUr([String locale = 'ur']) : super(locale);

  @override
  String get signInToContinue => 'جاری رکھنے کے لیے سائن ان کریں';

  @override
  String get createYourAccount => 'اپنا اکاؤنٹ بنائیں';

  @override
  String get fieldEmail => 'ای میل';

  @override
  String get fieldEmailInvalid => 'ایک درست ای میل پتہ درج کریں';

  @override
  String get fieldPhoneOptional => 'فون (اختیاری)';

  @override
  String get fieldPassword => 'پاس ورڈ';

  @override
  String get fieldPasswordRequired => 'اپنا پاس ورڈ درج کریں';

  @override
  String fieldPasswordTooShort(int min) {
    return 'کم از کم $min حروف استعمال کریں۔ چند الفاظ پر مشتمل ایک فقرہ یاد رکھنا سب سے آسان اور اندازہ لگانا سب سے مشکل ہوتا ہے؛ اسپیس دینا ٹھیک ہے۔';
  }

  @override
  String fieldPasswordTooLong(int max) {
    return 'زیادہ سے زیادہ $max حروف استعمال کریں۔';
  }

  @override
  String get errPasswordIsIdentity =>
      'پاس ورڈ آپ کا ای میل پتہ نہیں ہونا چاہیے، اور نہ ہی اس میں شامل ہونا چاہیے۔';

  @override
  String get errPasswordBreached =>
      'یہ پاس ورڈ معلوم ڈیٹا رساؤ میں سامنے آ چکا ہے اور آسانی سے اس کا اندازہ لگایا جا سکتا ہے۔ کوئی دوسرا پاس ورڈ منتخب کریں۔';

  @override
  String get showPassword => 'پاس ورڈ دکھائیں';

  @override
  String get hidePassword => 'پاس ورڈ چھپائیں';

  @override
  String get signInWithBusiness => 'اپنے کاروبار کے ساتھ سائن ان کریں';

  @override
  String continueWithBusiness(String business) {
    return '$business کے ساتھ جاری رکھیں';
  }

  @override
  String get businessSignInNameHelp =>
      'آپ کے کاروبار کا سائن ان نام۔ یہ آپ کے مینیجر کے پاس ہے۔';

  @override
  String get fieldBusinessSignInName => 'سائن ان نام';

  @override
  String get fieldBusinessSignInNameHint => 'مثلاً: acme-foods';

  @override
  String get actionSignIn => 'سائن ان کریں';

  @override
  String get actionCreateAccount => 'اکاؤنٹ بنائیں';

  @override
  String get actionCancel => 'منسوخ کریں';

  @override
  String get actionContinue => 'جاری رکھیں';

  @override
  String get toggleHaveAccount => 'پہلے سے اکاؤنٹ ہے؟ سائن ان کریں';

  @override
  String get toggleNewHere => 'یہاں نئے ہیں؟ ایک اکاؤنٹ بنائیں';

  @override
  String get errInvalidCredentials => 'ای میل یا پاس ورڈ درست نہیں ہے۔';

  @override
  String get errEmailExists => 'اس ای میل کے ساتھ ایک اکاؤنٹ پہلے سے موجود ہے۔';

  @override
  String get errNetwork => 'سرور تک نہیں پہنچا جا سکا۔ اپنا کنکشن چیک کریں۔';

  @override
  String get errGeneric => 'کچھ غلط ہو گیا۔ دوبارہ کوشش کریں۔';

  @override
  String get ssoErrNotFound =>
      'اس نام سے کوئی کاروبار سائن ان نہیں کرتا۔ اپنے مینیجر سے اس کی تصدیق کریں۔';

  @override
  String get ssoErrRequired =>
      'آپ کا کاروبار آپ کو اپنے ہی سائن ان صفحے کے ذریعے سائن ان کراتا ہے۔ نیچے دیا گیا \"اپنے کاروبار کے ساتھ سائن ان کریں\" استعمال کریں۔';

  @override
  String get ssoErrNoAccount =>
      'آپ نے اپنے کاروبار کے ساتھ سائن ان کیا، لیکن اس نے ابھی تک آپ کو یہاں شامل نہیں کیا۔ اپنے مینیجر سے کہیں کہ آپ کو ملازم کے طور پر شامل کریں۔';

  @override
  String get ssoErrEmailUnverified =>
      'آپ کے کاروبار کے سائن ان صفحے نے آپ کے ای میل پتے کی تصدیق نہیں کی، اس لیے اسے آپ کے لاگ ان سے نہیں ملایا جا سکا۔';

  @override
  String get ssoErrEmailMissing =>
      'آپ کے کاروبار کے سائن ان صفحے نے آپ کا ای میل پتہ فراہم نہیں کیا۔';

  @override
  String get ssoErrAlreadyLinked =>
      'آپ کا لاگ ان آپ کے کاروبار میں کسی اور سے منسلک ہے۔ مالک سے اسے الگ کرنے کے لیے کہیں۔';

  @override
  String get ssoErrAccountUnavailable =>
      'یہ لاگ ان اب یہاں سائن ان نہیں کر سکتا۔';

  @override
  String get ssoErrCancelled => 'سائن ان منسوخ کر دیا گیا۔';

  @override
  String get ssoErrExpired =>
      'اس سائن ان میں بہت وقت لگ گیا یا یہ پہلے ہی استعمال ہو چکا تھا۔ دوبارہ شروع کریں۔';

  @override
  String get ssoErrReauthRequired =>
      'اپنے کاروبار کے ساتھ دوبارہ سائن ان کریں۔';

  @override
  String get ssoErrNotReady =>
      'آپ کے کاروبار کا سنگل سائن آن ابھی مکمل نہیں ہوا۔ اس کے مالک سے پوچھیں۔';

  @override
  String get ssoErrUnavailable =>
      'یہاں آپ کے کاروبار کے ساتھ سائن ان کرنا دستیاب نہیں ہے۔';

  @override
  String get ssoErrProviderUnreachable =>
      'آپ کے کاروبار کے سائن ان صفحے تک نہیں پہنچا جا سکا۔ تھوڑی دیر میں دوبارہ کوشش کریں۔';

  @override
  String get errTenantInactive =>
      'اس کاروبار کا اکاؤنٹ معطل کر دیا گیا ہے۔ سپورٹ سے رابطہ کریں۔';

  @override
  String get ssoErrGeneric =>
      'آپ کے کاروبار کا سائن ان صفحہ آپ کو سائن ان نہیں کرا سکا۔ اس کے مالک سے سیٹنگز چیک کرنے کے لیے کہیں۔';

  @override
  String get forgotPassword => 'پاس ورڈ بھول گئے؟';

  @override
  String get forgotPasswordTitle => 'کیا آپ اپنا پاس ورڈ بھول گئے ہیں؟';

  @override
  String get forgotPasswordIntro =>
      'اپنا ای میل پتہ درج کریں۔ اگر کوئی اکاؤنٹ یہ پتہ استعمال کرتا ہے، تو ہم پاس ورڈ ری سیٹ کرنے کے لیے ایک لنک بھیجیں گے۔';

  @override
  String get forgotPasswordSubmit => 'لنک بھیجیں';

  @override
  String get forgotPasswordSent =>
      'اگر کوئی اکاؤنٹ اس پتے کو استعمال کرتا ہے، تو ہم نے ایک لنک بھیج دیا ہے۔ یہ تیس منٹ کے لیے، صرف ایک بار کام کرتا ہے۔';

  @override
  String get resetPasswordTitle => 'نیا پاس ورڈ منتخب کریں';

  @override
  String get resetPasswordNewLabel => 'نیا پاس ورڈ';

  @override
  String get resetPasswordConfirmLabel => 'نئے پاس ورڈ کی تصدیق کریں';

  @override
  String get resetPasswordMismatch =>
      'یہ اوپر دیے گئے نئے پاس ورڈ سے میل نہیں کھاتا۔';

  @override
  String get resetPasswordSubmit => 'پاس ورڈ بدلیں';

  @override
  String get resetPasswordDone => 'پاس ورڈ بدل دیا گیا — اس سے سائن ان کریں۔';

  @override
  String get resetPasswordDoneStorefront =>
      'اسٹور فرنٹ سے خریداری کر رہے ہیں؟ آپ دکان سے سائن ان کر سکتے ہیں۔';

  @override
  String get resetPasswordTokenInvalid =>
      'یہ لنک ختم ہو گیا ہے یا پہلے ہی استعمال ہو چکا ہے — نیا لنک طلب کریں۔';

  @override
  String get resetPasswordRequestNew => 'نیا لنک طلب کریں';
}
