import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/password_policy.dart';
import 'package:storeql_app/l10n/gen/app_localizations_en.dart';

// ---------------------------------------------------------------------------
// GET /auth/password-policy (public, no token): the published rules a new
// password is held to — read once, cached for the app's lifetime, and never
// left without an answer: unreadable falls back to iam-svc's own built-in
// defaults rather than leaving a form with no rule to show.
// ---------------------------------------------------------------------------

class _Answering implements HttpClientAdapter {
  final ResponseBody Function(RequestOptions) answer;
  _Answering(this.answer);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async => answer(o);
}

ResponseBody _ok(String body) => ResponseBody.fromString(
  body,
  200,
  headers: {
    Headers.contentTypeHeader: [Headers.jsonContentType],
  },
);

void main() {
  group('PasswordPolicy.fromJson', () {
    test('reads every field the endpoint answers', () {
      final policy = PasswordPolicy.fromJson({
        'minLength': 12,
        'maxLength': 64,
        'breachScreened': false,
        'mustNotContainLogin': true,
      });
      expect(policy.minLength, 12);
      expect(policy.maxLength, 64);
      expect(policy.breachScreened, isFalse);
      expect(policy.mustNotContainLogin, isTrue);
    });

    test('a missing field falls back to the built-in default for it alone', () {
      final policy = PasswordPolicy.fromJson({'minLength': 20});
      expect(policy.minLength, 20);
      expect(policy.maxLength, PasswordPolicy.fallback.maxLength);
      expect(policy.breachScreened, PasswordPolicy.fallback.breachScreened);
    });
  });

  group('passwordPolicyProvider', () {
    test('reads the published policy when it can', () async {
      final container = ProviderContainer(
        overrides: [
          publicAuthDioProvider.overrideWithValue(
            Dio(BaseOptions(baseUrl: 'http://test'))
              ..httpClientAdapter = _Answering(
                (_) => _ok(
                  '{"data":{"minLength":12,"maxLength":64,"breachScreened":true,"mustNotContainLogin":true}}',
                ),
              ),
          ),
        ],
      );
      addTearDown(container.dispose);
      final policy = await container.read(passwordPolicyProvider.future);
      expect(policy.minLength, 12);
      expect(policy.maxLength, 64);
    });

    test(
      'falls back to the built-in default when the endpoint cannot be read',
      () async {
        final container = ProviderContainer(
          overrides: [
            publicAuthDioProvider.overrideWithValue(
              Dio(BaseOptions(baseUrl: 'http://test'))
                ..httpClientAdapter = _Answering(
                  (o) => throw DioException(
                    requestOptions: o,
                    type: DioExceptionType.connectionError,
                  ),
                ),
            ),
          ],
        );
        addTearDown(container.dispose);
        final policy = await container.read(passwordPolicyProvider.future);
        expect(policy, PasswordPolicy.fallback);
      },
    );

    test('a malformed answer also falls back, rather than throwing', () async {
      final container = ProviderContainer(
        overrides: [
          publicAuthDioProvider.overrideWithValue(
            Dio(BaseOptions(baseUrl: 'http://test'))
              ..httpClientAdapter = _Answering(
                (_) => _ok('{"data": "not an object"}'),
              ),
          ),
        ],
      );
      addTearDown(container.dispose);
      final policy = await container.read(passwordPolicyProvider.future);
      expect(policy, PasswordPolicy.fallback);
    });
  });

  group('passwordLengthProblem (localized)', () {
    final l = AppLocalizationsEn();
    const policy = PasswordPolicy(
      minLength: 12,
      maxLength: 20,
      breachScreened: true,
      mustNotContainLogin: true,
    );

    test('too short names the policy\'s own minimum', () {
      expect(
        passwordLengthProblem(l, 'eleven char', policy),
        l.fieldPasswordTooShort(12),
      );
    });

    test('too long names the policy\'s own maximum', () {
      expect(
        passwordLengthProblem(l, 'a' * 21, policy),
        l.fieldPasswordTooLong(20),
      );
    });

    test(
      'within the policy is null — identity and breach stay with the server',
      () {
        expect(passwordLengthProblem(l, 'a phrase of words', policy), isNull);
      },
    );
  });

  group('passwordLengthProblemPlain (unlocalized forms)', () {
    const policy = PasswordPolicy(
      minLength: 12,
      maxLength: 20,
      breachScreened: true,
      mustNotContainLogin: true,
    );

    test('says the same rule as passwordRuleText', () {
      expect(
        passwordLengthProblemPlain('short', policy),
        passwordRuleText(policy),
      );
    });

    test('within the policy is null', () {
      expect(passwordLengthProblemPlain('a phrase of words', policy), isNull);
    });
  });
}
