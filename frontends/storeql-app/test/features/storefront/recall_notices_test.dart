import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/features/storefront/recall_notice_card.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';

import 'package:intl/intl.dart';
// A product safety recall on something the shopper bought (05.10): the notice
// as the shop wrote it, headline first; the remedy chosen once through the
// shopper's own route; a refusal shown in words; nothing asked for when signed
// out; and a failed check never reading as "no recalls".

class _FakeAuth extends StorefrontAuthNotifier {
  _FakeAuth({bool signedIn = true}) {
    if (signedIn) {
      state = const StorefrontAuthState(
          accessToken: 'tok', refreshToken: 'ref', email: 'shopper@example.com');
    }
  }
}

class _Recorder {
  final List<RequestOptions> calls = [];
  final Map<String, dynamic> responses;
  final Map<String, ({int status, String code, String message})> refusals;

  _Recorder({Map<String, dynamic> responses = const {}, this.refusals = const {}})
      : responses = Map.of(responses);

  Dio dio() {
    final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
    dio.interceptors.add(InterceptorsWrapper(onRequest: (opts, handler) {
      calls.add(opts);
      final key = '${opts.method} ${opts.path}';
      final refusal = refusals[key];
      if (refusal != null) {
        handler.reject(DioException(
          requestOptions: opts,
          response: Response(
            requestOptions: opts,
            statusCode: refusal.status,
            data: {'error': {'code': refusal.code, 'message': refusal.message}},
          ),
          type: DioExceptionType.badResponse,
        ));
        return;
      }
      final body = responses[key];
      // The card refetches after a choice; the server would then list the notice as chosen.
      if (opts.method == 'POST' && key.endsWith('/remedy')) {
        responses['GET /order-svc/orders/recall-notices/mine'] = [body];
      }
      handler.resolve(Response(
        requestOptions: opts,
        statusCode: 200,
        data: {'data': body, 'error': null, 'meta': {}},
      ));
    }));
    return dio;
  }
}

Map<String, dynamic> _notice({String status = 'ISSUED', String? remedy}) => {
      'id': 'n-1',
      'recallId': 'r-1',
      'reference': 'FSA-PRIN-42',
      'hazard': 'ALLERGEN',
      'reason': 'Peanut not on the label',
      'customerNotice': 'Do not eat it. Bring it back to any store.',
      'remedies': ['REFUND', 'REPLACEMENT'],
      'contactPhone': '0800 100 200',
      'contactUrl': 'https://recall.example.com',
      'orderId': 'o-1',
      'soldAt': '2026-09-12T10:15:00Z',
      'status': status,
      'remedy': ?remedy,
      'lines': [
        {'variantId': 'v-1', 'productName': 'Crunchy peanut butter', 'batchNo': 'L1', 'expiryDate': '2026-10-01', 'qty': 2}
      ],
    };

Future<void> _pump(WidgetTester tester, _Recorder recorder, {bool signedIn = true}) async {
  // A fresh tree each time: a ProviderScope whose overrides change in place keeps its old container.
  await tester.pumpWidget(const SizedBox());
  await tester.pumpWidget(ProviderScope(
    overrides: [
      storefrontAuthProvider.overrideWith((ref) => _FakeAuth(signedIn: signedIn)),
      storefrontDioProvider.overrideWith((ref) => recorder.dio()),
    ],
    child: const MaterialApp(home: Scaffold(body: SingleChildScrollView(child: RecallNoticesSection()))),
  ));
  await tester.pumpAndSettle();
}

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  // The card writes its dates with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);

  testWidgets('the notice, headline first, and the remedy chosen once', (tester) async {
    final recorder = _Recorder(responses: {
      'GET /order-svc/orders/recall-notices/mine': [_notice()],
      'POST /order-svc/orders/recall-notices/n-1/remedy': _notice(status: 'REMEDY_CHOSEN', remedy: 'REFUND'),
    });
    await _pump(tester, recorder);

    expect(find.text('PRODUCT SAFETY RECALL'), findsOneWidget);
    expect(find.text('FSA-PRIN-42'), findsOneWidget);
    // Both dates written the same way (en_GB abbreviates September "Sept").
    expect(find.text('Crunchy peanut butter, lot L1, best before 1 Oct 2026 — bought 12 Sept 2026'), findsOneWidget);
    expect(find.text('Stop using this product immediately. Do not eat it. Bring it back to any store.'), findsOneWidget);
    expect(find.text('Why: Peanut not on the label'), findsOneWidget);
    expect(find.text('Contact: 0800 100 200 · https://recall.example.com'), findsOneWidget);
    expect(find.text('I want a refund'), findsOneWidget);
    expect(find.text('I want a replacement'), findsOneWidget);
    expect(find.text('I want a repair'), findsNothing);

    await tester.tap(find.byKey(const Key('recall-choose-REFUND')));
    await tester.pumpAndSettle();
    final post = recorder.calls.singleWhere((c) => c.method == 'POST');
    expect(post.path, '/order-svc/orders/recall-notices/n-1/remedy');
    expect(post.data, {'remedy': 'REFUND'});
    expect(find.byKey(const Key('recall-notice-chosen')), findsOneWidget);
    expect(find.textContaining('You chose a refund. Bring the product to any of our stores'), findsOneWidget);
    expect(find.byKey(const Key('recall-choose-REFUND')), findsNothing);
  });

  testWidgets('a refusal is shown in words, and a settled notice says so', (tester) async {
    final recorder = _Recorder(
      responses: {
        'GET /order-svc/orders/recall-notices/mine': [_notice()],
      },
      refusals: {
        'POST /order-svc/orders/recall-notices/n-1/remedy': (
          status: 409,
          code: 'RECALL_REMEDY_ALREADY_CHOSEN',
          message: 'A remedy was already chosen: REFUND',
        ),
      },
    );
    await _pump(tester, recorder);
    await tester.tap(find.byKey(const Key('recall-choose-REPLACEMENT')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('recall-notice-refused')), findsOneWidget);
    expect(find.textContaining('already chosen'), findsOneWidget);

    final settled = _Recorder(responses: {
      'GET /order-svc/orders/recall-notices/mine': [_notice(status: 'RESOLVED', remedy: 'REFUND')..['resolution'] = 'REFUNDED'],
    });
    await _pump(tester, settled);
    expect(find.text('Settled: you were refunded.'), findsOneWidget);
    expect(find.byKey(const Key('recall-choose-REFUND')), findsNothing);
  });

  testWidgets('signed out nothing is asked; a failed check is an error, never silence', (tester) async {
    final quiet = _Recorder();
    await _pump(tester, quiet, signedIn: false);
    expect(quiet.calls, isEmpty);
    expect(find.text('PRODUCT SAFETY RECALL'), findsNothing);

    final broken = _Recorder(refusals: {
      'GET /order-svc/orders/recall-notices/mine': (status: 503, code: 'UNAVAILABLE', message: 'Service unavailable'),
    });
    await _pump(tester, broken);
    expect(find.byKey(const Key('recall-notices-error')), findsOneWidget);
    expect(find.textContaining("couldn't be checked"), findsOneWidget);
  });
}
