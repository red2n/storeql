import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/ids.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/customers_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Adding points and issuing store credit by hand are management's
// (customer-svc, 30 Sep 2026): only an owner or manager is offered them, both
// need a reason, adding points carries an Idempotency-Key (the same one on a
// retry of the same request, a new one when it changes), and a refusal reads
// in words. Redeeming, the till's own business, stays open to every role.
// ---------------------------------------------------------------------------

final _ann = {
  'id': 'c-1',
  'email': 'ann@example.com',
  'firstName': 'Ann',
  'lastName': 'Lee',
  'status': 'ACTIVE',
};

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  /// The status the next earn/issue answers with, and its message.
  int grantStatus = 200;
  String grantMessage = 'Only a manager can do that.';

  List<RequestOptions> get grants =>
      requests.where((r) => r.method == 'POST' && (r.path.contains('/loyalty/') || r.path.contains('/store-credit/'))).toList();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (o.method == 'POST') {
      return grantStatus == 200
          ? jsonResponse('{"data":{}}')
          : jsonResponse(jsonEncode({'error': {'code': 'FORBIDDEN', 'message': grantMessage}}), grantStatus);
    }
    if (path == '/customer-svc/customers') {
      return jsonResponse(jsonEncode({'data': {'items': [_ann], 'nextCursor': null}}));
    }
    if (path == '/customer-svc/customers/c-1') return jsonResponse(jsonEncode({'data': _ann}));
    if (path.endsWith('/loyalty')) {
      return jsonResponse(jsonEncode({'data': {'pointsBalance': 30, 'lifetimePoints': 50}}));
    }
    if (path.endsWith('/loyalty/ledger')) return jsonResponse('{"data":[]}');
    if (path.endsWith('/store-credit')) {
      return jsonResponse(jsonEncode({'data': {'balance': 12.5, 'currency': 'GBP'}}));
    }
    if (path.endsWith('/addresses')) return jsonResponse('{"data":[]}');
    return jsonResponse(jsonEncode({'code': 'NOT_FOUND', 'status': 404}), 404);
  }
}

Future<_Server> _open(WidgetTester tester, String role) async {
  tester.view.physicalSize = const Size(1200, 2000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role)),
    ],
    child: const MaterialApp(home: Scaffold(body: CustomersScreen())),
  ));
  for (var i = 0; i < 12; i++) {
    await tester.pump(const Duration(milliseconds: 50));
  }
  await tester.tap(find.text('Ann Lee'));
  for (var i = 0; i < 12; i++) {
    await tester.pump(const Duration(milliseconds: 50));
  }
  return server;
}

Future<void> _fill(WidgetTester tester, String amount, String reason) async {
  await tester.enterText(find.byKey(const Key('grant-amount')), amount);
  await tester.enterText(find.byKey(const Key('grant-reason')), reason);
  await tester.tap(find.byKey(const Key('grant-apply')));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 100));
}

void main() {
  setUpAll(initializeDateFormatting);

  for (final role in ['CASHIER', 'STOREKEEPER']) {
    testWidgets('a $role is not offered adding points or issuing credit, only the till\'s redeem', (tester) async {
      await _open(tester, role);
      expect(find.byKey(const Key('customer-earn-points')), findsNothing);
      expect(find.byKey(const Key('customer-issue-credit')), findsNothing);
      expect(find.text('Redeem points'), findsOneWidget);
      expect(find.text('Redeem credit'), findsOneWidget);
    });
  }

  for (final role in ['OWNER', 'MANAGER']) {
    testWidgets('a $role is offered both', (tester) async {
      await _open(tester, role);
      expect(find.byKey(const Key('customer-earn-points')), findsOneWidget);
      expect(find.byKey(const Key('customer-issue-credit')), findsOneWidget);
    });
  }

  testWidgets('adding points needs a reason, then sends it with an Idempotency-Key', (tester) async {
    final server = await _open(tester, 'MANAGER');
    await tester.tap(find.byKey(const Key('customer-earn-points')));
    await tester.pumpAndSettle();
    // The button and the dialog's title say the same thing.
    expect(find.descendant(of: find.byKey(const Key('customer-earn-points')), matching: find.text('Add points')),
        findsOneWidget);
    expect(find.descendant(of: find.byType(AlertDialog), matching: find.text('Add points')), findsWidgets);

    await _fill(tester, '25', '   ');
    expect(server.grants, isEmpty, reason: 'no reason, nothing sent');
    expect(find.text('Say why. It is kept with your name.'), findsOneWidget);

    await _fill(tester, '25', 'Goodwill after a late order');
    expect(server.grants, hasLength(1));
    final sent = server.grants.single;
    expect(sent.path, '/customer-svc/customers/c-1/loyalty/earn');
    expect(sent.data, {'points': 25.0, 'reason': 'Goodwill after a late order'});
    expect(isV7(sent.headers['Idempotency-Key'] as String), isTrue);
  });

  testWidgets('a refused add reads in words, and a retry of the same request reuses the key; a changed one does not',
      (tester) async {
    final server = await _open(tester, 'OWNER')
      ..grantStatus = 403;
    Future<void> attempt(String amount, String reason) async {
      await tester.tap(find.byKey(const Key('customer-earn-points')));
      await tester.pumpAndSettle();
      await _fill(tester, amount, reason);
      await tester.pumpAndSettle();
    }

    await attempt('10', 'Apology');
    expect(find.text('Only a manager can do that.'), findsOneWidget);
    await attempt('10', 'Apology');
    await attempt('11', 'Apology');
    final keys = server.grants.map((r) => r.headers['Idempotency-Key']).toList();
    expect(keys, hasLength(3));
    expect(keys[1], keys[0], reason: 'same request, same key');
    expect(keys[2], isNot(keys[0]), reason: 'the request changed');

    // Once it lands, the same request is a new one.
    server.grantStatus = 200;
    await attempt('11', 'Apology');
    server.grantMessage = 'x';
    await attempt('11', 'Apology');
    final after = server.grants.map((r) => r.headers['Idempotency-Key']).toList();
    expect(after[3], keys[2], reason: 'the retry that landed kept its key');
    expect(after[4], isNot(after[3]), reason: 'a fresh add after a success is a fresh request');
  });

  testWidgets('issuing store credit needs a reason and sends it; redeem needs none', (tester) async {
    final server = await _open(tester, 'OWNER');
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await _fill(tester, '5', '');
    expect(server.grants, isEmpty);
    expect(find.text('Say why. It is kept with your name.'), findsOneWidget);
    await _fill(tester, '5', 'Damaged goods');
    expect(server.grants.single.path, '/customer-svc/customers/c-1/store-credit/issue');
    expect(server.grants.single.data, {'amount': 5.0, 'reason': 'Damaged goods'});
    expect(isV7(server.grants.single.headers['Idempotency-Key'] as String), isTrue);
    await tester.pumpAndSettle();

    await tester.tap(find.text('Redeem credit'));
    await tester.pumpAndSettle();
    await _fill(tester, '2', '');
    expect(server.grants.last.path, endsWith('/store-credit/redeem'));
  });

  testWidgets('a retried issue reuses its key, a changed one does not, and a success lets it go', (tester) async {
    final server = await _open(tester, 'OWNER')
      ..grantStatus = 500;
    Future<void> attempt(String amount) async {
      await tester.tap(find.byKey(const Key('customer-issue-credit')));
      await tester.pumpAndSettle();
      await _fill(tester, amount, 'Damaged goods');
      await tester.pumpAndSettle();
    }

    await attempt('5');
    await attempt('5');
    await attempt('6');
    server.grantStatus = 200;
    await attempt('6');
    await attempt('6');
    final k = server.grants.map((r) => r.headers['Idempotency-Key']).toList();
    expect(k[1], k[0]);
    expect(k[2], isNot(k[0]));
    expect(k[3], k[2], reason: 'the retry that landed kept its key');
    expect(k[4], isNot(k[3]), reason: 'a new issue after a success is new');
  });

  testWidgets('a refused issue reads in words', (tester) async {
    final server = await _open(tester, 'MANAGER')
      ..grantStatus = 403
      ..grantMessage = 'Only an owner or manager can issue store credit.';
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await _fill(tester, '5', 'Why not');
    await tester.pumpAndSettle();
    expect(find.text('Only an owner or manager can issue store credit.'), findsOneWidget);
    expect(server.grants, hasLength(1));
  });
}
