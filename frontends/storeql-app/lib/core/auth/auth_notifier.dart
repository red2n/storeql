import 'dart:convert';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../constants.dart';
import '../network/api_error.dart';
import '../network/api_client.dart';
import '../storage/app_storage.dart';
import 'auth_state.dart';
import 'passkeys.dart';
import 'sso.dart';

final authNotifierProvider = AsyncNotifierProvider<AuthNotifier, AuthState>(
  AuthNotifier.new,
  // Never retried. Riverpod retries a failed build by default, and a sign-in is
  // not a thing to do twice: a single sign-on return that was refused would be
  // run again with nothing left to finish, and the reason it was refused replaced
  // by a blank sign-in screen a moment after it appeared.
  retry: (_, _) => null,
);

class AuthNotifier extends AsyncNotifier<AuthState> {
  final AppStorage _storage = const AppStorage();

  @override
  Future<AuthState> build() => _restoreFromStorage();

  Future<AuthState> _restoreFromStorage() async {
    // Back from a business's identity provider (20.x): finish that sign-in first.
    final back = ssoReturnAtLaunch;
    ssoReturnAtLaunch = null;
    if (back != null) return _finishSso(back);
    final access = await _storage.read(key: StorageKeys.accessToken);
    final refresh = await _storage.read(key: StorageKeys.refreshToken);
    if (access == null || refresh == null) return const AuthUnauthenticated();
    return _decode(access, refresh);
  }

  Future<void> login(String email, String password) async {
    state = const AsyncValue.loading();
    state = await AsyncValue.guard(() async {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.iam}/auth/login',
        data: {'email': email, 'password': password},
      );
      return _afterPassword(resp.data['data'] as Map<String, dynamic>, platform: false);
    });
  }

  /// Platform console login — distinct endpoint from [login]: a store/tenant staff
  /// credential is never valid here, and a platform-admin credential is never valid
  /// on the store/POS login screen.
  Future<void> platformLogin(String email, String password) async {
    state = const AsyncValue.loading();
    state = await AsyncValue.guard(() async {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.iam}/auth/platform-login',
        data: {'email': email, 'password': password},
      );
      return _afterPassword(resp.data['data'] as Map<String, dynamic>, platform: true);
    });
  }

  /// Starts a sign-in through a business's identity provider (20.x), found by the
  /// sign-in name the business chose. The browser leaves the app for the provider
  /// and comes back to it; the verifier kept here is what lets this browser, and
  /// only this one, finish the sign-in.
  Future<void> startSso(String slug) async {
    state = const AsyncValue.loading();
    try {
      final verifier = newSsoVerifier();
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.iam}/auth/sso/start',
        data: {
          'slug': slug.trim().toLowerCase(),
          'codeChallenge': ssoChallenge(verifier),
          'returnTo': '${ssoBrowser.origin}/',
        },
      );
      ssoBrowser.keepVerifier(verifier);
      // Loading until the page is left: nothing more happens on this one.
      await ssoBrowser.go((resp.data['data'] as Map<String, dynamic>)['authorizationUrl'] as String);
    } catch (e, st) {
      state = AsyncValue.error(e, st);
    }
  }

  /// The provider sent the browser back: the ticket and the kept verifier are
  /// traded for what a password sign-in answers.
  Future<AuthState> _finishSso(SsoReturn back) async {
    final verifier = ssoBrowser.takeVerifier();
    switch (back) {
      case SsoFailed(:final code):
        throw SsoError(code);
      case SsoTicket(:final ticket):
        // No verifier: this browser did not start the sign-in the ticket names.
        if (verifier == null) throw const SsoError('SSO_TICKET_INVALID');
        final resp = await ref.read(apiClientProvider).dio.post(
          '/${ApiConstants.iam}/auth/sso/token',
          data: {'ticket': ticket, 'codeVerifier': verifier},
        );
        return _afterPassword(resp.data['data'] as Map<String, dynamic>, platform: false);
    }
  }

  /// What a right password leads to (20.12): the session, a second factor owed,
  /// or a factor that has to be set up first.
  Future<AuthState> _afterPassword(Map<String, dynamic> data, {required bool platform}) async {
    if (data['mfaRequired'] == true) {
      return AuthSecondFactorOwed(
        mfaToken: data['mfaToken'] as String,
        methods: (data['mfaMethods'] as List<dynamic>? ?? const []).map((m) => m.toString()).toList(),
        platform: platform,
      );
    }
    if (data['mfaEnrolmentRequired'] == true) {
      return AuthEnrolmentOwed(enrolmentToken: data['accessToken'] as String, platform: platform);
    }
    return _saveAndDecode(data);
  }

  /// Answers the second factor of a waiting sign-in with a code from an
  /// authenticator app or a recovery code. A wrong answer keeps the wait open
  /// and says so; an ended wait goes back to the password.
  Future<void> answerSecondFactor(String method, String code) =>
      _answer({'method': method, 'code': code.trim()});

  /// Answers it with a passkey: the server's challenge, the browser's ceremony,
  /// the assertion back.
  Future<void> answerWithPasskey() async {
    final owed = state.value;
    if (owed is! AuthSecondFactorOwed) return;
    try {
      final options = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.iam}/auth/mfa/login/passkey-options',
        data: {'mfaToken': owed.mfaToken},
      );
      final assertion = await passkeys.get(options.data['data'] as Map<String, dynamic>);
      await _answer({'method': 'PASSKEY', 'assertion': assertion});
    } on PasskeyCancelled {
      state = AsyncValue.data(owed.withError(null));
    } catch (e) {
      state = AsyncValue.data(owed.withError(_secondFactorError(e)));
    }
  }

  Future<void> _answer(Map<String, dynamic> answer) async {
    final owed = state.value;
    if (owed is! AuthSecondFactorOwed) return;
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.iam}/auth/mfa/login',
        data: {'mfaToken': owed.mfaToken, ...answer},
      );
      state = AsyncValue.data(await _saveAndDecode(resp.data['data'] as Map<String, dynamic>));
    } catch (e) {
      if (apiErrorCode(e) == 'MFA_CHALLENGE_EXPIRED') {
        // The wait is over — too many wrong answers, or too long: the password again.
        state = const AsyncValue.data(AuthUnauthenticated());
        return;
      }
      state = AsyncValue.data(owed.withError(_secondFactorError(e)));
    }
  }

  String _secondFactorError(Object e) => switch (apiErrorCode(e)) {
        'MFA_CODE_INVALID' => 'That did not match. Try the next code.',
        'MFA_LOCKED' => 'Too many wrong answers. Wait a quarter of an hour and sign in again.',
        'LOGIN_LOCKED' => 'Too many failed sign-ins from here. Try again later.',
        _ => friendlyError(e),
      };

  /// Gives up on a waiting sign-in, or on a set-up that was owed.
  void cancelSecondFactor() => state = const AsyncValue.data(AuthUnauthenticated());

  /// The session a set-up answered with, once its owner has seen the recovery codes.
  Future<void> completeEnrolment(Map<String, dynamic> tokens) async {
    state = AsyncValue.data(await _saveAndDecode(tokens));
  }

  Future<void> register(String email, String password, String? phone) async {
    state = const AsyncValue.loading();
    state = await AsyncValue.guard(() async {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.iam}/auth/register',
        data: {'email': email, 'password': password, 'phone': ?phone},
      );
      return _saveAndDecode(resp.data['data'] as Map<String, dynamic>);
    });
  }

  // Called after onboarding steps so the JWT picks up the new tenantId
  Future<void> refresh() async {
    final refresh = await _storage.read(key: StorageKeys.refreshToken);
    if (refresh == null || refresh.isEmpty) {
      // A sandbox session has no refresh token (22.8): when its one access
      // token runs out, the owner is back in the live business.
      final live = await _storage.read(key: StorageKeys.liveAccessToken);
      if (live != null && live.isNotEmpty) {
        await leaveSandbox();
      }
      return;
    }
    state = await AsyncValue.guard(() async {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.iam}/auth/refresh',
        data: {'refreshToken': refresh},
      );
      return _saveAndDecode(resp.data['data'] as Map<String, dynamic>);
    });
  }

  Future<void> logout() async {
    final refresh = await _storage.read(key: StorageKeys.refreshToken);
    if (refresh != null) {
      try {
        await ref.read(apiClientProvider).dio.post(
          '/${ApiConstants.iam}/auth/logout',
          data: {'refreshToken': refresh},
        );
      } catch (_) {}
    }
    // Everything except the POS offline queue: unreplayed sales are money the
    // server has not been told about, and they outlive the cashier's shift.
    await _storage.deleteAll(keep: const {StorageKeys.posOfflineSales});
    state = const AsyncValue.data(AuthUnauthenticated());
  }

  /// Into the business's sandbox (22.8): trades the live owner's token for one
  /// that names the sandbox as its tenant, keeping the live tokens aside so
  /// [leaveSandbox] needs no sign-in. The sandbox session lasts one access
  /// token; it has no refresh token.
  Future<void> enterSandbox() async {
    final resp = await ref
        .read(apiClientProvider)
        .dio
        .post('/${ApiConstants.iam}/auth/sandbox/token');
    final data = resp.data['data'] as Map<String, dynamic>;
    final liveAccess = await _storage.read(key: StorageKeys.accessToken);
    final liveRefresh = await _storage.read(key: StorageKeys.refreshToken);
    if (liveAccess != null) {
      await _storage.write(key: StorageKeys.liveAccessToken, value: liveAccess);
    }
    if (liveRefresh != null) {
      await _storage.write(
          key: StorageKeys.liveRefreshToken, value: liveRefresh);
    }
    final access = data['accessToken'] as String;
    await _storage.write(key: StorageKeys.accessToken, value: access);
    await _storage.write(key: StorageKeys.refreshToken, value: '');
    state = AsyncValue.data(_decode(access, ''));
  }

  /// Back from the sandbox to the live business, on the tokens kept aside;
  /// signed out if they are gone.
  Future<void> leaveSandbox() async {
    final liveAccess = await _storage.read(key: StorageKeys.liveAccessToken);
    final liveRefresh = await _storage.read(key: StorageKeys.liveRefreshToken);
    await _storage.write(key: StorageKeys.liveAccessToken, value: '');
    await _storage.write(key: StorageKeys.liveRefreshToken, value: '');
    if (liveAccess == null ||
        liveAccess.isEmpty ||
        liveRefresh == null ||
        liveRefresh.isEmpty) {
      await _storage.deleteAll(keep: const {StorageKeys.posOfflineSales});
      state = const AsyncValue.data(AuthUnauthenticated());
      return;
    }
    state = AsyncValue.data(await _saveAndDecode(
        {'accessToken': liveAccess, 'refreshToken': liveRefresh}));
    // The live access token may have run out meanwhile.
    await refresh();
  }

  Future<AuthState> _saveAndDecode(Map<String, dynamic> tokenData) async {
    final access = tokenData['accessToken'] as String;
    final refresh = tokenData['refreshToken'] as String;
    await _storage.write(key: StorageKeys.accessToken, value: access);
    await _storage.write(key: StorageKeys.refreshToken, value: refresh);
    return _decode(access, refresh);
  }

  AuthState _decode(String access, String refresh) {
    try {
      final parts = access.split('.');
      if (parts.length != 3) return const AuthUnauthenticated();
      final payload = utf8.decode(base64Url.decode(base64Url.normalize(parts[1])));
      final claims = jsonDecode(payload) as Map<String, dynamic>;
      return AuthAuthenticated(
        accessToken: access,
        refreshToken: refresh,
        userId: claims['sub'] as String? ?? '',
        tenantId: claims['tenant'] as String?,
        roles: (claims['roles'] as List<dynamic>?)
                ?.map((r) => r.toString())
                .toList() ??
            [],
        email: claims['email'] as String?,
        storeIds: (claims['storeIds'] as List<dynamic>?)
                ?.map((s) => s.toString())
                .toList() ??
            [],
        permissions: (claims['perms'] as List<dynamic>?)
            ?.map((s) => s.toString())
            .toList(),
        sandbox: (claims['amr'] as List<dynamic>?)
                ?.map((s) => s.toString())
                .contains('sandbox') ??
            false,
      );
    } catch (_) {
      return const AuthUnauthenticated();
    }
  }
}
