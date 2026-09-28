// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Arabic (`ar`).
class AppLocalizationsAr extends AppLocalizations {
  AppLocalizationsAr([String locale = 'ar']) : super(locale);

  @override
  String get signInToContinue => 'سجّل الدخول للمتابعة';

  @override
  String get createYourAccount => 'أنشئ حسابك';

  @override
  String get fieldEmail => 'البريد الإلكتروني';

  @override
  String get fieldEmailInvalid => 'أدخل بريدًا إلكترونيًا صالحًا';

  @override
  String get fieldPhoneOptional => 'الهاتف (اختياري)';

  @override
  String get fieldPassword => 'كلمة المرور';

  @override
  String get fieldPasswordRequired => 'أدخل كلمة المرور';

  @override
  String fieldPasswordTooShort(int min) {
    return 'استخدم $min حرفًا على الأقل. عبارة من كلمات قليلة هي الأسهل تذكرًا والأصعب تخمينًا؛ المسافات مسموحة.';
  }

  @override
  String fieldPasswordTooLong(int max) {
    return 'استخدم $max حرفًا كحد أقصى.';
  }

  @override
  String get errPasswordIsIdentity =>
      'يجب ألا تكون كلمة المرور عنوان بريدك الإلكتروني أو تحتوي عليه.';

  @override
  String get errPasswordBreached =>
      'ظهرت هذه كلمة المرور في تسريبات بيانات معروفة ويسهل تخمينها. اختر كلمة أخرى.';

  @override
  String get showPassword => 'إظهار كلمة المرور';

  @override
  String get hidePassword => 'إخفاء كلمة المرور';

  @override
  String get signInWithBusiness => 'تسجيل الدخول عبر شركتك';

  @override
  String continueWithBusiness(String business) {
    return 'المتابعة مع $business';
  }

  @override
  String get businessSignInNameHelp =>
      'اسم تسجيل الدخول الخاص بشركتك. يملكه مديرك.';

  @override
  String get fieldBusinessSignInName => 'اسم تسجيل الدخول';

  @override
  String get fieldBusinessSignInNameHint => 'مثال: acme-foods';

  @override
  String get actionSignIn => 'تسجيل الدخول';

  @override
  String get actionCreateAccount => 'إنشاء حساب';

  @override
  String get actionCancel => 'إلغاء';

  @override
  String get actionContinue => 'متابعة';

  @override
  String get toggleHaveAccount => 'لديك حساب بالفعل؟ تسجيل الدخول';

  @override
  String get toggleNewHere => 'جديد هنا؟ إنشاء حساب';

  @override
  String get errInvalidCredentials =>
      'البريد الإلكتروني أو كلمة المرور غير صحيحة.';

  @override
  String get errEmailExists => 'يوجد حساب بهذا البريد الإلكتروني مسبقًا.';

  @override
  String get errNetwork => 'تعذّر الوصول إلى الخادم. تحقّق من اتصالك.';

  @override
  String get errGeneric => 'حدث خطأ ما. حاول مرة أخرى.';

  @override
  String get ssoErrNotFound =>
      'لا توجد شركة تسجّل الدخول بهذا الاسم. تحقّق منه مع مديرك.';

  @override
  String get ssoErrRequired =>
      'شركتك تسجّل دخولك من خلال صفحة تسجيل الدخول الخاصة بها. استخدم «تسجيل الدخول عبر شركتك» أدناه.';

  @override
  String get ssoErrNoAccount =>
      'سجّلت الدخول عبر شركتك، لكنها لم تُضِفك هنا بعد. اطلب من مديرك أن يضيفك كموظف.';

  @override
  String get ssoErrEmailUnverified =>
      'صفحة تسجيل الدخول الخاصة بشركتك لم تُوثّق عنوان بريدك الإلكتروني، فلم يتمكن من مطابقته مع حسابك.';

  @override
  String get ssoErrEmailMissing =>
      'صفحة تسجيل الدخول الخاصة بشركتك لم تُشارك عنوان بريدك الإلكتروني.';

  @override
  String get ssoErrAlreadyLinked =>
      'حسابك مرتبط بشخص آخر في شركتك. اطلب من المالك إلغاء هذا الربط.';

  @override
  String get ssoErrAccountUnavailable =>
      'لا يمكن لهذا الحساب تسجيل الدخول هنا بعد الآن.';

  @override
  String get ssoErrCancelled => 'تم إلغاء تسجيل الدخول.';

  @override
  String get ssoErrExpired =>
      'استغرق تسجيل الدخول وقتًا طويلًا أو تم استخدامه من قبل. ابدأ من جديد.';

  @override
  String get ssoErrReauthRequired => 'سجّل الدخول عبر شركتك مرة أخرى.';

  @override
  String get ssoErrNotReady =>
      'تسجيل الدخول الموحّد لشركتك غير مكتمل. اسأل مالكها.';

  @override
  String get ssoErrUnavailable => 'تسجيل الدخول عبر شركتك غير متاح هنا.';

  @override
  String get ssoErrProviderUnreachable =>
      'تعذّر الوصول إلى صفحة تسجيل الدخول الخاصة بشركتك. حاول مرة أخرى بعد قليل.';

  @override
  String get errTenantInactive => 'حساب هذه الشركة مُعلَّق. تواصل مع الدعم.';

  @override
  String get ssoErrGeneric =>
      'لم تتمكن صفحة تسجيل الدخول الخاصة بشركتك من تسجيل دخولك. اطلب من مالكها التحقق من الإعدادات.';

  @override
  String get forgotPassword => 'نسيت كلمة المرور؟';

  @override
  String get forgotPasswordTitle => 'هل نسيت كلمة المرور؟';

  @override
  String get forgotPasswordIntro =>
      'أدخل عنوان بريدك الإلكتروني. إذا كان أي حساب يستخدمه، سنرسل رابطًا لإعادة تعيين كلمة المرور.';

  @override
  String get forgotPasswordSubmit => 'إرسال الرابط';

  @override
  String get forgotPasswordSent =>
      'إذا كان أي حساب يستخدم هذا العنوان، فقد أرسلنا رابطًا. يعمل لمدة ثلاثين دقيقة، ولمرة واحدة فقط.';

  @override
  String get resetPasswordTitle => 'اختر كلمة مرور جديدة';

  @override
  String get resetPasswordNewLabel => 'كلمة المرور الجديدة';

  @override
  String get resetPasswordConfirmLabel => 'تأكيد كلمة المرور الجديدة';

  @override
  String get resetPasswordMismatch =>
      'هذا لا يطابق كلمة المرور الجديدة المذكورة أعلاه.';

  @override
  String get resetPasswordSubmit => 'تغيير كلمة المرور';

  @override
  String get resetPasswordDone => 'تم تغيير كلمة المرور — سجّل الدخول بها.';

  @override
  String get resetPasswordDoneStorefront =>
      'تتسوّق من المتجر الإلكتروني؟ يمكنك تسجيل الدخول من المتجر.';

  @override
  String get resetPasswordTokenInvalid =>
      'انتهت صلاحية هذا الرابط أو تم استخدامه من قبل — اطلب رابطًا جديدًا.';

  @override
  String get resetPasswordRequestNew => 'طلب رابط جديد';
}
