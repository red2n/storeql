import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/onboarding/onboarding_wizard.dart';

// ---------------------------------------------------------------------------
// Setting up a business starts with nothing chosen (SJ-D53). The wizard used to
// open on the United Kingdom and pounds, so a business that did not look twice
// was created in the wrong country and currency. Now neither is preselected,
// submitting without them is refused before any request, and choosing a
// country suggests the currency it trades in.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  _Server({this.plansStatus = 200});

  /// What `GET /plans` answers: the price list, or a refusal to read it.
  final int plansStatus;
  final List<RequestOptions> requests = [];

  static const starter = '019987a0-0f1e-7c3b-8a4d-3e2f1a0b9c81';
  static const growth = '019987a0-0f1e-7c3b-8a4d-3e2f1a0b9c82';

  List<RequestOptions> get posts =>
      requests.where((r) => r.method == 'POST').toList();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async {
    requests.add(o);
    final headers = {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    };
    if (o.method == 'GET' && o.path.endsWith('/plans')) {
      if (plansStatus != 200) {
        return ResponseBody.fromString(
          '{"error":{"code":"SERVICE_UNAVAILABLE","message":"later"}}',
          plansStatus,
          headers: headers,
        );
      }
      return ResponseBody.fromString(
        jsonEncode({
          'data': [
            {
              'id': starter,
              'code': 'STARTER',
              'name': 'Starter',
              'description': 'For one shop.',
              'billingInterval': 'MONTH',
              'trialDays': 14,
              'isDefault': true,
              'prices': [
                {'currency': 'GBP', 'amount': 49},
              ],
              'includes': [
                {'key': 'stores.max', 'label': 'Stores', 'limitValue': 1},
              ],
            },
            {
              'id': growth,
              'code': 'GROWTH',
              'name': 'Growth',
              'description': 'For a chain.',
              'billingInterval': 'MONTH',
              'trialDays': 0,
              'isDefault': false,
              'prices': [
                {'currency': 'GBP', 'amount': 149},
              ],
              'includes': [],
            },
          ],
        }),
        200,
        headers: headers,
      );
    }
    return ResponseBody.fromString(
      '{"data":{"id":"t-1","name":"Kyoto Market"}}',
      201,
      headers: headers,
    );
  }
}

Future<_Server> _pump(WidgetTester tester, {int plansStatus = 200}) async {
  tester.view.physicalSize = const Size(1400, 5000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(plansStatus: plansStatus);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
      child: const MaterialApp(home: OnboardingWizard()),
    ),
  );
  await tester.pumpAndSettle();
  return server;
}

void main() {
  testWidgets('no country or currency is chosen for the business', (
    tester,
  ) async {
    final server = await _pump(tester);
    expect(find.text('United Kingdom (GB)'), findsNothing);
    expect(find.textContaining('British Pound'), findsNothing);

    await tester.enterText(
      find.widgetWithText(TextFormField, 'Business name *'),
      'Kyoto Market',
    );
    await tester.tap(find.text('Continue'));
    await tester.pumpAndSettle();
    expect(find.text('Choose a country'), findsOneWidget);
    expect(find.text('Choose a currency'), findsOneWidget);
    expect(server.posts, isEmpty);
  });

  testWidgets('choosing Japan suggests yen, and the business is created in both', (
    tester,
  ) async {
    final server = await _pump(tester);
    await tester.enterText(
      find.widgetWithText(TextFormField, 'Business name *'),
      'Kyoto Market',
    );
    await tester.tap(
      find.widgetWithText(DropdownButtonFormField<String>, 'Country *'),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Japan (JP)').last);
    await tester.pumpAndSettle();
    expect(find.text('JPY — Japanese Yen'), findsOneWidget);

    await tester.tap(find.text('Continue'));
    // A progress indicator spins while the request is out, so settle by time.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    final sent = server.posts.single;
    expect(sent.path, endsWith('/onboarding/tenants'));
    expect((sent.data as Map)['country'], 'JP');
    expect((sent.data as Map)['currency'], 'JPY');
    // Nothing chosen: the platform's default plan goes with the signup.
    expect((sent.data as Map)['planId'], _Server.starter);
  });

  // ── the price list (21.13) ──────────────────────────────────────────────────

  testWidgets('the plans on sale are offered with the default chosen, and the chosen one is sent', (
    tester,
  ) async {
    final server = await _pump(tester);
    expect(find.text('Starter — £49.00 a month · 14-day free trial'), findsOneWidget);
    expect(find.text('For one shop. Includes Stores.'), findsOneWidget);

    await tester.enterText(
      find.widgetWithText(TextFormField, 'Business name *'),
      'Kyoto Market',
    );
    await tester.tap(find.widgetWithText(DropdownButtonFormField<String>, 'Plan *'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Growth — £149.00 a month · no free trial').last);
    await tester.pumpAndSettle();
    expect(find.text('For a chain.'), findsOneWidget);
    await tester.tap(
      find.widgetWithText(DropdownButtonFormField<String>, 'Country *'),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Japan (JP)').last);
    await tester.pumpAndSettle();

    await tester.tap(find.text('Continue'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    final sent = server.posts.single;
    expect((sent.data as Map)['planId'], _Server.growth);
  });

  testWidgets('when the price list cannot be read the business still signs up, on the platform\'s default', (
    tester,
  ) async {
    final server = await _pump(tester, plansStatus: 503);
    expect(find.widgetWithText(DropdownButtonFormField<String>, 'Plan *'), findsNothing);
    expect(find.textContaining('start on the standard plan'), findsOneWidget);

    await tester.enterText(
      find.widgetWithText(TextFormField, 'Business name *'),
      'Kyoto Market',
    );
    await tester.tap(
      find.widgetWithText(DropdownButtonFormField<String>, 'Country *'),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Japan (JP)').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Continue'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    final sent = server.posts.single;
    expect((sent.data as Map).containsKey('planId'), isFalse);
  });
}
