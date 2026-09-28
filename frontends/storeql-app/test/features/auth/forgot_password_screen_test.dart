import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:storeql_app/core/auth/password_policy.dart';
import 'package:storeql_app/features/auth/forgot_password_screen.dart';
import 'package:storeql_app/l10n/gen/app_localizations.dart';

// ---------------------------------------------------------------------------
// intent/password-reset.md: the request half. No account enumeration — the
// same words whatever the address, and the same work either way on the
// server. Only a request that never reached the server at all reads
// differently, so a person knows to try again.
// ---------------------------------------------------------------------------

const _sent =
    'If an account uses that address, we have sent a link. It works for 30 minutes, once.';

class _Answering implements HttpClientAdapter {
  final ResponseBody Function(RequestOptions) answer;
  final List<RequestOptions> requests = [];
  _Answering(this.answer);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async {
    requests.add(o);
    return answer(o);
  }
}

ResponseBody _json(String body, [int status = 200]) => ResponseBody.fromString(
  body,
  status,
  headers: {
    Headers.contentTypeHeader: [Headers.jsonContentType],
  },
);

Future<_Answering> _pump(
  WidgetTester tester,
  ResponseBody Function(RequestOptions) answer, {
  Locale? locale,
}) async {
  final adapter = _Answering(answer);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        publicAuthDioProvider.overrideWithValue(
          Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = adapter,
        ),
      ],
      child: MaterialApp(
        locale: locale,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: const ForgotPasswordScreen(),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return adapter;
}

/// A minimal router of the app's own three destinations (never the real
/// storefront or staff sign-in screens, which would reach for their own
/// unmocked providers) — enough to prove where *Cancel* and *Sign in*
/// actually lead, the same way `?from=storefront` reaches [ForgotPasswordScreen]
/// in the real one (core/router.dart).
Future<GoRouter> _openViaRouter(
  WidgetTester tester,
  String location,
  ResponseBody Function(RequestOptions) answer,
) async {
  final adapter = _Answering(answer);
  final router = GoRouter(
    initialLocation: location,
    routes: [
      GoRoute(
        path: '/forgot-password',
        builder: (_, state) =>
            ForgotPasswordScreen(from: state.uri.queryParameters['from']),
      ),
      GoRoute(path: '/login', builder: (_, _) => const Text('STAFF SIGN-IN')),
      GoRoute(path: '/store', builder: (_, _) => const Text('THE SHOP')),
    ],
  );
  addTearDown(router.dispose);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        publicAuthDioProvider.overrideWithValue(
          Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = adapter,
        ),
      ],
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ),
  );
  await tester.pumpAndSettle();
  return router;
}

void main() {
  testWidgets(
    "the card is the sign-in card's own width (420), not the wider page form",
    (tester) async {
      await _pump(tester, (_) => _json('{"data":{"accepted":true}}', 202));
      expect(tester.getSize(find.byType(Card).first).width, 420);
    },
  );

  testWidgets(
    'from=storefront: Cancel leads back to the shop, never staff sign-in',
    (tester) async {
      final router = await _openViaRouter(
        tester,
        '/forgot-password?from=storefront',
        (_) => _json('{"data":{"accepted":true}}', 202),
      );
      await tester.tap(find.byKey(const Key('forgot-cancel')));
      await tester.pumpAndSettle();
      expect(router.state.uri.toString(), '/store');
      expect(find.text('THE SHOP'), findsOneWidget);
    },
  );

  testWidgets(
    'from=storefront: once sent, Sign in also leads back to the shop',
    (tester) async {
      final router = await _openViaRouter(
        tester,
        '/forgot-password?from=storefront',
        (_) => _json('{"data":{"accepted":true}}', 202),
      );
      await tester.enterText(
        find.byKey(const Key('forgot-email')),
        'ana@example.com',
      );
      await tester.tap(find.byKey(const Key('forgot-submit')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('forgot-back-to-sign-in')));
      await tester.pumpAndSettle();
      expect(router.state.uri.toString(), '/store');
    },
  );

  testWidgets(
    "with no from (staff's own link), Cancel leads to sign-in as before",
    (tester) async {
      final router = await _openViaRouter(
        tester,
        '/forgot-password',
        (_) => _json('{"data":{"accepted":true}}', 202),
      );
      await tester.tap(find.byKey(const Key('forgot-cancel')));
      await tester.pumpAndSettle();
      expect(router.state.uri.toString(), '/login');
      expect(find.text('STAFF SIGN-IN'), findsOneWidget);
    },
  );

  testWidgets(
    'a known address answers with the request page\'s one form of words',
    (tester) async {
      await _pump(tester, (_) => _json('{"data":{"accepted":true}}', 202));
      await tester.enterText(
        find.byKey(const Key('forgot-email')),
        'known@example.com',
      );
      await tester.tap(find.byKey(const Key('forgot-submit')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('forgot-result')), findsOneWidget);
      expect(find.text(_sent), findsOneWidget);
    },
  );

  testWidgets(
    'an unknown address answers exactly the same — no account enumeration',
    (tester) async {
      await _pump(tester, (_) => _json('{"data":{"accepted":true}}', 202));
      await tester.enterText(
        find.byKey(const Key('forgot-email')),
        'unknown@example.com',
      );
      await tester.tap(find.byKey(const Key('forgot-submit')));
      await tester.pumpAndSettle();
      expect(find.text(_sent), findsOneWidget);
    },
  );

  testWidgets(
    'even a 400 the server sends back is answered the same way — the server was reached',
    (tester) async {
      await _pump(
        tester,
        (_) => _json(
          '{"error":{"code":"VALIDATION_FAILED","message":"email missing"}}',
          400,
        ),
      );
      await tester.enterText(
        find.byKey(const Key('forgot-email')),
        'weird@example.com',
      );
      await tester.tap(find.byKey(const Key('forgot-submit')));
      await tester.pumpAndSettle();
      expect(find.text(_sent), findsOneWidget);
      expect(find.textContaining('VALIDATION_FAILED'), findsNothing);
    },
  );

  testWidgets(
    'a request that never reaches the server says so, not that a link was sent',
    (tester) async {
      final adapter = await _pump(
        tester,
        (o) => throw DioException(
          requestOptions: o,
          type: DioExceptionType.connectionError,
        ),
      );
      await tester.enterText(
        find.byKey(const Key('forgot-email')),
        'ana@example.com',
      );
      await tester.tap(find.byKey(const Key('forgot-submit')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('forgot-result')), findsNothing);
      expect(find.text(_sent), findsNothing);
      expect(find.byKey(const Key('forgot-network-error')), findsOneWidget);
      expect(adapter.requests, hasLength(1));
    },
  );

  testWidgets('an invalid email is refused before anything is sent', (
    tester,
  ) async {
    final adapter = await _pump(
      tester,
      (_) => _json('{"data":{"accepted":true}}', 202),
    );
    await tester.enterText(
      find.byKey(const Key('forgot-email')),
      'not-an-email',
    );
    await tester.tap(find.byKey(const Key('forgot-submit')));
    await tester.pumpAndSettle();
    expect(adapter.requests, isEmpty);
    expect(find.text(_sent), findsNothing);
  });

  testWidgets('sends the app\'s own current language code, not a region', (
    tester,
  ) async {
    final adapter = await _pump(
      tester,
      (_) => _json('{"data":{"accepted":true}}', 202),
      locale: const Locale('pl'),
    );
    await tester.enterText(
      find.byKey(const Key('forgot-email')),
      'ana@example.com',
    );
    await tester.tap(find.byKey(const Key('forgot-submit')));
    await tester.pumpAndSettle();
    expect((adapter.requests.single.data as Map)['language'], 'pl');
  });
}
