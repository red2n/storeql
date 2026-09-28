// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Bengali Bangla (`bn`).
class AppLocalizationsBn extends AppLocalizations {
  AppLocalizationsBn([String locale = 'bn']) : super(locale);

  @override
  String get signInToContinue => 'চালিয়ে যেতে সাইন ইন করুন';

  @override
  String get createYourAccount => 'আপনার অ্যাকাউন্ট তৈরি করুন';

  @override
  String get fieldEmail => 'ইমেল';

  @override
  String get fieldEmailInvalid => 'একটি সঠিক ইমেল ঠিকানা লিখুন';

  @override
  String get fieldPhoneOptional => 'ফোন (ঐচ্ছিক)';

  @override
  String get fieldPassword => 'পাসওয়ার্ড';

  @override
  String get fieldPasswordRequired => 'আপনার পাসওয়ার্ড লিখুন';

  @override
  String fieldPasswordTooShort(int min) {
    return 'কমপক্ষে $min অক্ষর ব্যবহার করুন। কয়েকটি শব্দ নিয়ে একটি বাক্যাংশ মনে রাখা সবচেয়ে সহজ এবং অনুমান করা সবচেয়ে কঠিন; স্পেস দেওয়া যায়।';
  }

  @override
  String fieldPasswordTooLong(int max) {
    return 'সর্বাধিক $max অক্ষর ব্যবহার করুন।';
  }

  @override
  String get errPasswordIsIdentity =>
      'পাসওয়ার্ডটি আপনার ইমেল ঠিকানা হতে পারবে না, বা তা তার মধ্যে থাকতে পারবে না।';

  @override
  String get errPasswordBreached =>
      'এই পাসওয়ার্ডটি পরিচিত ডেটা লঙ্ঘনে দেখা গেছে এবং সহজেই অনুমান করা যাবে। অন্য একটি বেছে নিন।';

  @override
  String get showPassword => 'পাসওয়ার্ড দেখান';

  @override
  String get hidePassword => 'পাসওয়ার্ড আড়াল করুন';

  @override
  String get signInWithBusiness => 'আপনার ব্যবসা দিয়ে সাইন ইন করুন';

  @override
  String continueWithBusiness(String business) {
    return '$business দিয়ে চালিয়ে যান';
  }

  @override
  String get businessSignInNameHelp =>
      'আপনার ব্যবসার সাইন-ইন নাম। এটি আপনার ম্যানেজারের কাছে আছে।';

  @override
  String get fieldBusinessSignInName => 'সাইন-ইন নাম';

  @override
  String get fieldBusinessSignInNameHint => 'যেমন: acme-foods';

  @override
  String get actionSignIn => 'সাইন ইন করুন';

  @override
  String get actionCreateAccount => 'অ্যাকাউন্ট তৈরি করুন';

  @override
  String get actionCancel => 'বাতিল করুন';

  @override
  String get actionContinue => 'চালিয়ে যান';

  @override
  String get toggleHaveAccount => 'আগে থেকেই অ্যাকাউন্ট আছে? সাইন ইন করুন';

  @override
  String get toggleNewHere => 'নতুন এসেছেন? একটি অ্যাকাউন্ট তৈরি করুন';

  @override
  String get errInvalidCredentials => 'ইমেল অথবা পাসওয়ার্ড সঠিক নয়।';

  @override
  String get errEmailExists => 'এই ইমেল দিয়ে একটি অ্যাকাউন্ট আগে থেকেই আছে।';

  @override
  String get errNetwork =>
      'সার্ভারে পৌঁছানো যাচ্ছে না। আপনার সংযোগ পরীক্ষা করুন।';

  @override
  String get errGeneric => 'কিছু একটা সমস্যা হয়েছে। আবার চেষ্টা করুন।';

  @override
  String get ssoErrNotFound =>
      'এই নামে কোনো ব্যবসা সাইন ইন করে না। আপনার ম্যানেজারের কাছে এটি যাচাই করুন।';

  @override
  String get ssoErrRequired =>
      'আপনার ব্যবসা তার নিজস্ব সাইন-ইন পেজের মাধ্যমে আপনাকে সাইন ইন করায়। নিচের \"আপনার ব্যবসা দিয়ে সাইন ইন করুন\" ব্যবহার করুন।';

  @override
  String get ssoErrNoAccount =>
      'আপনি আপনার ব্যবসা দিয়ে সাইন ইন করেছেন, কিন্তু এখনও এখানে আপনাকে যুক্ত করা হয়নি। আপনাকে কর্মী হিসেবে যুক্ত করতে আপনার ম্যানেজারকে বলুন।';

  @override
  String get ssoErrEmailUnverified =>
      'আপনার ব্যবসার সাইন-ইন পেজ আপনার ইমেল ঠিকানা যাচাই করেনি, তাই এটি আপনার লগইনের সাথে মেলানো যায়নি।';

  @override
  String get ssoErrEmailMissing =>
      'আপনার ব্যবসার সাইন-ইন পেজ আপনার ইমেল ঠিকানা জানায়নি।';

  @override
  String get ssoErrAlreadyLinked =>
      'আপনার লগইন আপনার ব্যবসার অন্য কারও সাথে যুক্ত আছে। এটি বিচ্ছিন্ন করতে মালিককে বলুন।';

  @override
  String get ssoErrAccountUnavailable =>
      'এই লগইন দিয়ে এখানে আর সাইন ইন করা যাবে না।';

  @override
  String get ssoErrCancelled => 'সাইন ইন বাতিল করা হয়েছে।';

  @override
  String get ssoErrExpired =>
      'সাইন ইন করতে খুব বেশি সময় লেগেছে, বা এটি আগেই ব্যবহার হয়ে গেছে। আবার শুরু করুন।';

  @override
  String get ssoErrReauthRequired => 'আপনার ব্যবসা দিয়ে আবার সাইন ইন করুন।';

  @override
  String get ssoErrNotReady =>
      'আপনার ব্যবসার সিঙ্গেল সাইন-অন এখনও শেষ হয়নি। এর মালিককে জিজ্ঞাসা করুন।';

  @override
  String get ssoErrUnavailable =>
      'এখানে আপনার ব্যবসা দিয়ে সাইন ইন করা যায় না।';

  @override
  String get ssoErrProviderUnreachable =>
      'আপনার ব্যবসার সাইন-ইন পেজে পৌঁছানো যায়নি। কিছুক্ষণ পর আবার চেষ্টা করুন।';

  @override
  String get errTenantInactive =>
      'এই ব্যবসার অ্যাকাউন্ট স্থগিত করা হয়েছে। সহায়তার সাথে যোগাযোগ করুন।';

  @override
  String get ssoErrGeneric =>
      'আপনার ব্যবসার সাইন-ইন পেজ আপনাকে সাইন ইন করাতে পারেনি। সেটিংস পরীক্ষা করতে এর মালিককে বলুন।';

  @override
  String get forgotPassword => 'পাসওয়ার্ড ভুলে গেছেন?';

  @override
  String get forgotPasswordTitle => 'আপনি কি আপনার পাসওয়ার্ড ভুলে গেছেন?';

  @override
  String get forgotPasswordIntro =>
      'আপনার ইমেল ঠিকানা লিখুন। যদি কোনো অ্যাকাউন্ট এটি ব্যবহার করে, আমরা পাসওয়ার্ড রিসেট করার জন্য একটি লিঙ্ক পাঠাব।';

  @override
  String get forgotPasswordSubmit => 'লিঙ্ক পাঠান';

  @override
  String get forgotPasswordSent =>
      'যদি কোনো অ্যাকাউন্ট এই ঠিকানাটি ব্যবহার করে, আমরা একটি লিঙ্ক পাঠিয়েছি। এটি ত্রিশ মিনিটের জন্য, একবার কাজ করে।';

  @override
  String get resetPasswordTitle => 'একটি নতুন পাসওয়ার্ড বাছুন';

  @override
  String get resetPasswordNewLabel => 'নতুন পাসওয়ার্ড';

  @override
  String get resetPasswordConfirmLabel => 'নতুন পাসওয়ার্ড নিশ্চিত করুন';

  @override
  String get resetPasswordMismatch =>
      'এটি উপরে দেওয়া নতুন পাসওয়ার্ডের সাথে মিলছে না।';

  @override
  String get resetPasswordSubmit => 'পাসওয়ার্ড পরিবর্তন করুন';

  @override
  String get resetPasswordDone =>
      'পাসওয়ার্ড পরিবর্তিত হয়েছে — এটি দিয়ে সাইন ইন করুন।';

  @override
  String get resetPasswordDoneStorefront =>
      'স্টোরফ্রন্ট থেকে কিনছেন? আপনি দোকান থেকে সাইন ইন করতে পারেন।';

  @override
  String get resetPasswordTokenInvalid =>
      'এই লিঙ্কটির মেয়াদ শেষ হয়ে গেছে বা ইতিমধ্যে ব্যবহার করা হয়েছে — একটি নতুন লিঙ্কের জন্য অনুরোধ করুন।';

  @override
  String get resetPasswordRequestNew => 'নতুন লিঙ্কের জন্য অনুরোধ করুন';
}
