import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/order_limits_card.dart';
import 'package:storeql_app/features/admin/stores_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The two order time limits (order-svc, 30 Sep 2026): how long an unpaid order
// is held and how long an order may wait for its price. Both are off until set
// (the hold falls back to the platform's default); management reads them; only
// a caller held to no store changes them.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int? pendingHours;
  int? flag;
  int? cancel;
  bool refusePriceWait = false;

  @override
  void close({bool force = false}) {}

  Map<String, dynamic> _body(RequestOptions o) =>
      (o.data is String ? jsonDecode(o.data as String) : o.data) as Map<String, dynamic>;

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final p = o.path;
    if (p.endsWith('/admin/orders/settings/pending-limit')) {
      if (o.method == 'PUT') pendingHours = _body(o)['pendingLimitHours'] as int?;
      return jsonResponse(jsonEncode({
        'data': {
          'pendingLimitHours': pendingHours,
          'effectiveHours': pendingHours ?? 24,
          'usingDefault': pendingHours == null,
        }
      }));
    }
    if (p.endsWith('/admin/orders/settings/price-wait')) {
      if (o.method == 'PUT') {
        if (refusePriceWait) {
          return jsonResponse('{"error":{"code":"ORDER_PRICE_WAIT_INVALID","message":""}}', 400);
        }
        flag = _body(o)['flagMinutes'] as int?;
        cancel = _body(o)['cancelMinutes'] as int?;
      }
      return jsonResponse(jsonEncode({
        'data': {'flagMinutes': flag, 'cancelMinutes': cancel}
      }));
    }
    if (p.endsWith('/admin/stores') && o.method == 'GET') {
      return jsonResponse('{"data":[{"id":"store-1","name":"Main","code":"MAIN","type":"STORE",'
          '"status":"ACTIVE","timezone":"UTC"}],"meta":{}}');
    }
    return jsonResponse('{"data":{},"meta":{}}');
  }

  List<RequestOptions> puts(String tail) =>
      requests.where((r) => r.method == 'PUT' && r.path.endsWith(tail)).toList();
}

Future<_Server> _pump(WidgetTester tester,
    {String role = 'OWNER', List<String> storeIds = const [], _Server? server}) async {
  final srv = server ?? _Server();
  tester.view.physicalSize = const Size(1200, 1800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(
          Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = srv)),
      authNotifierProvider.overrideWith(() => RoleAuth(role, storeIds: storeIds)),
    ],
    child: const MaterialApp(home: StoresScreen()),
  ));
  await tester.pumpAndSettle();
  return srv;
}

void main() {
  test('minutes read in words', () {
    expect(minutesInWords(45), '45 minutes');
    expect(minutesInWords(1), '1 minute');
    expect(minutesInWords(60), '1 hour');
    expect(minutesInWords(150), '2 hours 30 minutes');
  });

  testWidgets('with nothing set, the card says the platform default holds and price waits have no limit',
      (tester) async {
    await _pump(tester);
    final summary = tester.widget<Text>(find.byKey(const Key('order-limits-summary'))).data!;
    expect(summary, contains('held for 24 hours'));
    expect(summary, contains("the platform's own limit"));
    expect(summary, contains('has no limit'));
  });

  testWidgets('the limits set read in words', (tester) async {
    await _pump(tester, server: _Server()
      ..pendingHours = 6
      ..flag = 30
      ..cancel = 120);
    final summary = tester.widget<Text>(find.byKey(const Key('order-limits-summary'))).data!;
    expect(summary, contains('held for 6 hours, then cancelled'));
    expect(summary, isNot(contains("platform's own")));
    expect(summary, contains('told when an order has waited 30 minutes'));
    expect(summary, contains('cancelled, and its stock released, after 2 hours'));
  });

  testWidgets('an owner sets both and both are sent', (tester) async {
    final server = await _pump(tester);
    await tester.ensureVisible(find.byKey(const Key('order-limits-edit')));
    await tester.tap(find.byKey(const Key('order-limits-edit')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('order-limit-hours')), '12');
    await tester.enterText(find.byKey(const Key('order-limit-flag')), '30');
    await tester.enterText(find.byKey(const Key('order-limit-cancel')), '90');
    await tester.tap(find.byKey(const Key('order-limits-save')));
    await tester.pumpAndSettle();
    expect(server.puts('/pending-limit').single.data, {'pendingLimitHours': 12});
    expect(server.puts('/price-wait').single.data, {'flagMinutes': 30, 'cancelMinutes': 90});
    expect(find.text('Order time limits saved.'), findsOneWidget);
    expect(tester.widget<Text>(find.byKey(const Key('order-limits-summary'))).data,
        contains('held for 12 hours, then cancelled'));
  });

  testWidgets('emptying a field puts the default or "never" back, sent as null', (tester) async {
    final server = await _pump(tester, server: _Server()
      ..pendingHours = 6
      ..flag = 30
      ..cancel = 120);
    await tester.ensureVisible(find.byKey(const Key('order-limits-edit')));
    await tester.tap(find.byKey(const Key('order-limits-edit')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('order-limit-hours')), '');
    await tester.enterText(find.byKey(const Key('order-limit-flag')), '');
    await tester.enterText(find.byKey(const Key('order-limit-cancel')), '');
    await tester.tap(find.byKey(const Key('order-limits-save')));
    await tester.pumpAndSettle();
    expect(server.puts('/pending-limit').single.data, {'pendingLimitHours': null});
    expect(server.puts('/price-wait').single.data, {'flagMinutes': null, 'cancelMinutes': null});
  });

  testWidgets('bad values are refused before anything is sent', (tester) async {
    final server = await _pump(tester);
    await tester.ensureVisible(find.byKey(const Key('order-limits-edit')));
    await tester.tap(find.byKey(const Key('order-limits-edit')));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('order-limit-hours')), '0');
    await tester.tap(find.byKey(const Key('order-limits-save')));
    await tester.pumpAndSettle();
    expect(find.textContaining('whole number of hours'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('order-limit-hours')), '');
    await tester.enterText(find.byKey(const Key('order-limit-flag')), '60');
    await tester.enterText(find.byKey(const Key('order-limit-cancel')), '30');
    await tester.tap(find.byKey(const Key('order-limits-save')));
    await tester.pumpAndSettle();
    expect(find.textContaining('not shorter than the first'), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);
  });

  // Each limit is the whole number typed, or it is refused under its field
  // and nothing is saved. Read as a number literal, 0x0C hours was saved as
  // twelve and +0x1E minutes as thirty.
  for (final (locale, key, typed) in const [
    ('en_GB', 'order-limit-hours', '0x0C'),
    ('ro', 'order-limit-flag', '+0x1E'),
    ('pl', 'order-limit-cancel', '0X5A'),
    ('ar', 'order-limit-hours', '0xc'),
    ('en', 'order-limit-flag', '30.'),
  ]) {
    testWidgets('in $locale, "$typed" in $key is refused, never saved as another figure',
        (tester) async {
      Intl.defaultLocale = locale;
      addTearDown(() => Intl.defaultLocale = null);
      final server = await _pump(tester);
      await tester.ensureVisible(find.byKey(const Key('order-limits-edit')));
      await tester.tap(find.byKey(const Key('order-limits-edit')));
      await tester.pumpAndSettle();
      final field = find.byKey(Key(key));
      for (var i = 1; i <= typed.length; i++) {
        await tester.enterText(field, typed.substring(0, i));
        await tester.pump();
      }
      await tester.tap(find.byKey(const Key('order-limits-save')));
      await tester.pumpAndSettle();
      expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);
      expect(tester.widget<TextField>(field).decoration?.errorText, isNotNull);
    });
  }

  testWidgets("a refusal reads in words and keeps the dialog open", (tester) async {
    await _pump(tester, server: _Server()..refusePriceWait = true);
    await tester.ensureVisible(find.byKey(const Key('order-limits-edit')));
    await tester.tap(find.byKey(const Key('order-limits-edit')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('order-limit-flag')), '30');
    await tester.tap(find.byKey(const Key('order-limits-save')));
    await tester.pumpAndSettle();
    expect(find.textContaining('A price wait is at least 1 minute'), findsOneWidget);
    expect(find.byKey(const Key('order-limits-save')), findsOneWidget);
  });

  testWidgets('a manager held to a store reads the limits but is offered no change, and is told who does',
      (tester) async {
    await _pump(tester, role: 'MANAGER', storeIds: const ['store-1']);
    expect(find.byKey(const Key('order-limits-summary')), findsOneWidget);
    expect(find.byKey(const Key('order-limits-edit')), findsNothing);
    expect(find.byKey(const Key('order-limits-business-wide-note')), findsOneWidget);
    expect(find.text('Only an owner or a head-office manager changes this.'), findsWidgets);
  });

  testWidgets('a head-office manager (no store held) is offered the change', (tester) async {
    await _pump(tester, role: 'MANAGER');
    expect(find.byKey(const Key('order-limits-edit')), findsOneWidget);
    expect(find.byKey(const Key('order-limits-business-wide-note')), findsNothing);
  });

  testWidgets('a storekeeper is shown none of it and does not ask', (tester) async {
    final server = await _pump(tester, role: 'STOREKEEPER', storeIds: const ['store-1']);
    expect(find.byKey(const Key('order-limits-card')), findsNothing);
    expect(server.requests.where((r) => r.path.contains('/settings/')), isEmpty);
  });
}
