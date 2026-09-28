import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../constants.dart';
import '../../l10n/gen/app_localizations.dart';

/// iam-svc's published `PasswordPolicy` (NIST SP 800-63B-4): the rules a new
/// password is held to — the same ones the server checks on sign-up, on a
/// change and on a reset. Read once from `GET /auth/password-policy` (public,
/// no token) so every form that sets a password can show the rule before
/// anything is typed, rather than learning it from a refusal.
class PasswordPolicy {
  final int minLength;
  final int maxLength;

  /// Whether the server screens a new password against known breaches
  /// (PASSWORD_BREACHED). Shown as a rule when true; never asserted by the
  /// app itself, which cannot check it.
  final bool breachScreened;

  /// Whether a new password must not be, or contain, the login's own email
  /// (PASSWORD_IS_IDENTITY). iam-svc always sets this, but the app reads it
  /// rather than assuming it.
  final bool mustNotContainLogin;

  const PasswordPolicy({
    required this.minLength,
    required this.maxLength,
    required this.breachScreened,
    required this.mustNotContainLogin,
  });

  /// What this app assumes until the published policy loads, or when it
  /// cannot be read at all (offline, the gateway down): iam-svc's own
  /// built-in defaults, mirrored here so a form never waits on the network
  /// to show a rule.
  static const fallback = PasswordPolicy(
    minLength: 15,
    maxLength: 128,
    breachScreened: true,
    mustNotContainLogin: true,
  );

  factory PasswordPolicy.fromJson(Map<String, dynamic> json) => PasswordPolicy(
    minLength: json['minLength'] as int? ?? fallback.minLength,
    maxLength: json['maxLength'] as int? ?? fallback.maxLength,
    breachScreened: json['breachScreened'] as bool? ?? fallback.breachScreened,
    mustNotContainLogin:
        json['mustNotContainLogin'] as bool? ?? fallback.mustNotContainLogin,
  );
}

/// A tokenless Dio for iam-svc's public auth surface: the password policy, and
/// the forgot/reset calls that follow it (none takes or needs a session — a
/// stale Authorization header from a signed-in tab is never sent here).
final publicAuthDioProvider = Provider<Dio>((ref) {
  return Dio(
    BaseOptions(
      baseUrl: ApiConstants.baseUrl,
      connectTimeout: const Duration(seconds: 8),
      receiveTimeout: const Duration(seconds: 15),
      headers: const {'Content-Type': 'application/json'},
    ),
  );
});

/// The published policy, read once and cached for the app's lifetime — like
/// `storefrontConfigProvider`, this is NOT autoDispose: every screen that sets
/// a password (sign-up, change-password, reset) reads the same answer, so the
/// rule shown never flickers between the fallback and the server's own numbers
/// as someone navigates between them. Falls back to [PasswordPolicy.fallback]
/// when the endpoint cannot be read, rather than leaving a form with no rule
/// to show.
final passwordPolicyProvider = FutureProvider<PasswordPolicy>((ref) async {
  try {
    final resp = await ref
        .read(publicAuthDioProvider)
        .get('/${ApiConstants.iam}/auth/password-policy');
    return PasswordPolicy.fromJson(
      Map<String, dynamic>.from(resp.data['data'] as Map),
    );
  } catch (_) {
    return PasswordPolicy.fallback;
  }
});

/// The policy to show right now: the server's once it has loaded, the
/// built-in default before that or if it never does. Never null, never a
/// wait.
PasswordPolicy watchPasswordPolicy(WidgetRef ref) =>
    ref.watch(passwordPolicyProvider).value ?? PasswordPolicy.fallback;

/// Why [password] would not do as a new password, in the policy's own
/// localized words — its length only. Identity and breach checks stay with
/// the server (20.x), which says so by its own code when it refuses one of
/// the right length.
String? passwordLengthProblem(
  AppLocalizations l,
  String? password,
  PasswordPolicy policy,
) {
  final length = (password ?? '').runes.length;
  if (length < policy.minLength) {
    return l.fieldPasswordTooShort(policy.minLength);
  }
  if (length > policy.maxLength) {
    return l.fieldPasswordTooLong(policy.maxLength);
  }
  return null;
}

/// The plain-English form of the rule, for a form that has not been
/// localized. Says the same thing [passwordLengthProblem] would refuse a too
/// -short password with, so a person reads one rule, not two.
String passwordRuleText(PasswordPolicy policy) =>
    'At least ${policy.minLength} characters — a phrase of a few words is easiest';

/// [passwordLengthProblem], in plain English, for a form that has not been
/// localized.
String? passwordLengthProblemPlain(String? password, PasswordPolicy policy) {
  final length = (password ?? '').runes.length;
  if (length < policy.minLength) return passwordRuleText(policy);
  if (length > policy.maxLength) {
    return 'At most ${policy.maxLength} characters.';
  }
  return null;
}

/// A `400` refusal's own code (`PASSWORD_TOO_SHORT`, `PASSWORD_TOO_LONG`,
/// `PASSWORD_IS_IDENTITY`, `PASSWORD_BREACHED` — [PasswordPolicy.check] in
/// iam-svc), worded the same way this policy's own rule and validator already
/// put it, so a refusal reads like the rule just shown rather than a second,
/// differently phrased sentence. Null for any other code (or none at all —
/// a wrong *current* password is a `401`, worded by the caller, never this),
/// so the caller falls back to the server's own message.
String? passwordPolicyRefusal(String? code, PasswordPolicy policy) {
  switch (code) {
    case 'PASSWORD_TOO_SHORT':
      return passwordRuleText(policy);
    case 'PASSWORD_TOO_LONG':
      return 'At most ${policy.maxLength} characters.';
    case 'PASSWORD_IS_IDENTITY':
      return "Your password can't be, or contain, your email address.";
    case 'PASSWORD_BREACHED':
      return 'That password has appeared in known data breaches. Please choose another.';
    default:
      return null;
  }
}
