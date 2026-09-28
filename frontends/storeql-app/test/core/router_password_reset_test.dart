import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/auth/password_policy.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/router.dart';
import 'package:storeql_app/features/auth/forgot_password_screen.dart';
import 'package:storeql_app/features/auth/reset_password_screen.dart';
import 'package:storeql_app/l10n/gen/app_localizations.dart';

import '../support/fake_api.dart';

// ---------------------------------------------------------------------------
// intent/password-reset.md: no session is ever needed to ask for a link or to
// spend one — mirrors how the pay link in a dunning notice is let through —
// and a signed-in person opening either page is not bounced away mid-reset.
//
// A local pump (not app_router_harness.dart's `followLink`, which this file
// does not own) so `passwordPolicyProvider` — which both new pages read — is
// also overridden: unset, it would reach `publicAuthDioProvider`'s real Dio,
// a real network call this widget test must never make.
// ---------------------------------------------------------------------------

class _Unauthenticated extends AuthNotifier {
  @override
  Future<AuthState> build() async => const AuthUnauthenticated();
}

Future<GoRouter> _open(WidgetTester tester, String link, {String? role}) async {
  tester.platformDispatcher.defaultRouteNameTestValue = link;
  addTearDown(tester.platformDispatcher.clearDefaultRouteNameTestValue);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'));
  late GoRouter router;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authNotifierProvider.overrideWith(
          () => role == null ? _Unauthenticated() : RoleAuth(role),
        ),
        apiClientProvider.overrideWithValue(FakeApiClient(dio)),
        passwordPolicyProvider.overrideWith(
          (ref) async => PasswordPolicy.fallback,
        ),
      ],
      child: Consumer(
        builder: (context, ref, _) {
          router = ref.watch(routerProvider);
          return MaterialApp.router(
            routerConfig: router,
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
          );
        },
      ),
    ),
  );
  await tester.pumpAndSettle();
  return router;
}

void main() {
  testWidgets(
    'an unauthenticated visitor reaches /forgot-password directly — no sign-in redirect',
    (tester) async {
      final router = await _open(tester, '/forgot-password');
      expect(router.state.uri.path, '/forgot-password');
      expect(find.byType(ForgotPasswordScreen), findsOneWidget);
    },
  );

  testWidgets(
    'an unauthenticated visitor reaches /reset-password/:token directly',
    (tester) async {
      final router = await _open(tester, '/reset-password/tok-abc');
      expect(router.state.uri.path, '/reset-password/tok-abc');
      expect(find.byType(ResetPasswordScreen), findsOneWidget);
    },
  );

  testWidgets(
    'a signed-in owner opening /forgot-password is not bounced to their home route',
    (tester) async {
      final router = await _open(tester, '/forgot-password', role: 'OWNER');
      expect(router.state.uri.path, '/forgot-password');
      expect(find.byType(ForgotPasswordScreen), findsOneWidget);
    },
  );

  testWidgets(
    'a signed-in platform administrator opening /reset-password/:token is not bounced to the platform console',
    (tester) async {
      final router = await _open(
        tester,
        '/reset-password/tok-xyz',
        role: 'PLATFORM_ADMIN',
      );
      expect(router.state.uri.path, '/reset-password/tok-xyz');
      expect(find.byType(ResetPasswordScreen), findsOneWidget);
    },
  );
}
