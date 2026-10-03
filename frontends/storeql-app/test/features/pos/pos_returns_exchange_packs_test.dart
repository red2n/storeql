import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/returns_screen.dart';

// ---------------------------------------------------------------------------
// An exchange's new basket is checked by the server as a till sale is (recall,
// scale certificate, sticker), so the till sends what each scan read, keeps
// packs of different lots apart, runs the sale's own recall check first, and
// says the server's refusal in words naming the new line.
// ---------------------------------------------------------------------------

class _Api implements ApiClient {
  @override
  Dio dio;
  _Api(this.dio);
}

class _CashierAuth extends AuthNotifier {
  @override
  Future<AuthState> build() async => const AuthAuthenticated(
      accessToken: 'a', refreshToken: 'r', userId: 'u', roles: ['CASHIER']);
}

/// Codes are `<lot>` or `<lot>/<other>`; 'NOLOT' carries none. All are the one
/// product v-2. A code ending `~M` carries a sticker, `~S` a scale.
class _Server implements HttpClientAdapter {
  _Server({this.recalls = '[]'});
  final String recalls;
  final List<(int, String)> exchangeReplies = [];
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  ResponseBody _r(int status, String body) => ResponseBody.fromString(body, status,
      headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final p = o.path;
    if (o.method == 'GET' && p.endsWith('/orders/by-receipt')) {
      return _r(200, jsonEncode({
        'data': {
          'order': {'id': 'o-1', 'currency': 'GBP'},
          'receiptNumber': '2026-000042',
          'lines': [
            {'variantId': 'v-1', 'soldQty': 2, 'returnedQty': 0, 'returnableQty': 2, 'unitPrice': 6.0},
          ],
        }
      }));
    }
    if (o.method == 'GET' && p.endsWith('/variants/resolve')) {
      return _r(200, '{"data":[{"variantId":"v-1","productName":"Strawberry jam","sku":"JAM-1"}]}');
    }
    if (p.endsWith('/admin/inventory/recalls/active')) return _r(200, '{"data":$recalls}');
    if (o.method == 'GET' && p.endsWith('/catalog/scan')) {
      final code = '${o.queryParameters['code']}';
      final lot = code.split('~').first;
      return _r(200, jsonEncode({
        'data': {
          'item': {'variantId': 'v-2', 'sku': 'MARM-1', 'productName': 'Marmalade'},
          'code': {
            if (lot != 'NOLOT') 'batch': lot,
            if (lot != 'NOLOT') 'expiry': '2027-01-31',
          },
        }
      }));
    }
    if (o.method == 'POST' && p.endsWith('/prices/resolve')) {
      return _r(200, '{"data":{"unitPrice":3.0,"currency":"GBP"}}');
    }
    if (o.method == 'POST' && p.endsWith('/exchange')) {
      if (exchangeReplies.isNotEmpty) {
        final r = exchangeReplies.length == 1 ? exchangeReplies.first : exchangeReplies.removeAt(0);
        return _r(r.$1, r.$2);
      }
      return _r(201, '{"data":{"order":{"id":"o-new","currency":"GBP"},"dueFromCustomer":0,"refundToCustomer":0}}');
    }
    return _r(200, '{"data":{}}');
  }

  List<RequestOptions> get exchanges =>
      requests.where((r) => r.method == 'POST' && r.path.endsWith('/exchange')).toList();
}

Future<ProviderContainer> _pump(WidgetTester tester, _Server server) async {
  tester.view.physicalSize = const Size(800, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  final router = GoRouter(routes: [
    GoRoute(path: '/', builder: (_, _) => const Scaffold(body: PosReturnsScreen())),
    GoRoute(path: '/pos/tender', builder: (_, _) => const Scaffold(body: Text('TENDER'))),
  ]);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_Api(dio)),
      authNotifierProvider.overrideWith(_CashierAuth.new),
      posStoreProvider.overrideWith((ref) => 'store-1'),
    ],
    child: MaterialApp.router(routerConfig: router),
  ));
  final c = ProviderScope.containerOf(tester.element(find.byType(PosReturnsScreen)));
  c.read(authNotifierProvider);
  await tester.pumpAndSettle();
  return c;
}

Future<void> _tap(WidgetTester tester, Finder f) async {
  await tester.ensureVisible(f);
  await tester.pumpAndSettle();
  await tester.tap(f);
  await tester.pumpAndSettle();
}

Future<void> _scan(WidgetTester tester, String code) async {
  final f = find.byKey(const Key('returns-item-field'));
  await tester.ensureVisible(f);
  await tester.enterText(f, code);
  await tester.pumpAndSettle();
  await _tap(tester, find.byKey(const Key('returns-item-add')));
}

/// Sale found, one jam back, exchange chosen.
Future<void> _startExchange(WidgetTester tester) async {
  await tester.enterText(find.byKey(const Key('returns-receipt-field')), '2026-000042');
  await _tap(tester, find.byKey(const Key('returns-find')));
  await _tap(tester, find.byKey(const Key('returns-inc-v-1')));
  await _tap(tester, find.byKey(const Key('returns-condition-v-1-SEALED')));
  await _tap(tester, find.descendant(
      of: find.byKey(const Key('returns-action')), matching: find.text('Exchange')));
}

String _problem(String code, String detail, List<String> details) => jsonEncode({
      'status': 409,
      'code': code,
      'detail': detail,
      'details': details,
      'error': {'code': code, 'message': detail, 'details': details},
    });

const _lotRecall = '''[{"recallId":"r-1","reference":"R-42","kind":"RECALL","hazard":"ALLERGEN",
 "variantId":"v-2","batchNo":"L42"}]''';

PosLine _line({String? batch, String? markdown, String? scale}) => PosLine(
    variantId: 'v-2',
    sku: 'S',
    name: 'N',
    qty: 1,
    unitPrice: 1,
    currency: 'GBP',
    soldBy: 'EACH',
    batchNo: batch,
    expiry: batch == null ? null : DateTime(2027, 1, 31),
    markdownId: markdown,
    weighingInstrumentId: scale);

void main() {
  test('the pack fields of a line are all four, as a sale sends them, and merge only when equal', () {
    expect(packFieldsOf(_line(batch: 'L1', markdown: 'm-1', scale: 'w-1')), {
      'batchNo': 'L1',
      'expiry': '2027-01-31',
      'markdownId': 'm-1',
      'weighingInstrumentId': 'w-1',
    });
    expect(packFieldsOf(_line()), isEmpty);
    expect(samePackLine(_line(batch: 'L1'), _line(batch: 'L1')), isTrue);
    expect(samePackLine(_line(batch: 'L1'), _line(batch: 'L2')), isFalse);
    expect(samePackLine(_line(), _line(markdown: 'm-1')), isFalse);
    expect(samePackLine(_line(), _line(scale: 'w-1')), isFalse);
  });

  testWidgets('each new line carries its lot and expiry, as a sale sends them',
      (tester) async {
    final server = _Server();
    await _pump(tester, server);
    await _startExchange(tester);
    await _scan(tester, 'L43');
    await _tap(tester, find.byKey(const Key('returns-submit')));

    final newItems = (server.exchanges.single.data as Map)['newItems'] as List;
    expect(newItems, [
      {'variantId': 'v-2', 'qty': 1, 'batchNo': 'L43', 'expiry': '2027-01-31'}
    ]);
  });

  testWidgets('two lots stay two lines, the same lot merges', (tester) async {
    final server = _Server();
    await _pump(tester, server);
    await _startExchange(tester);
    await _scan(tester, 'L43');
    await _scan(tester, 'L42');
    await _scan(tester, 'L43');
    expect(find.byKey(const Key('returns-new-v-2-L43')), findsOneWidget);
    expect(find.byKey(const Key('returns-new-v-2-L42')), findsOneWidget);
    await _tap(tester, find.byKey(const Key('returns-submit')));

    final newItems = (server.exchanges.single.data as Map)['newItems'] as List;
    expect(newItems.map((n) => '${n['batchNo']}x${n['qty']}'), ['L43x2', 'L42x1']);
  });

  testWidgets('a recalled lot in the new basket is stopped before any request', (tester) async {
    final server = _Server(recalls: _lotRecall);
    await _pump(tester, server);
    await _startExchange(tester);
    await _scan(tester, 'L43');
    await _scan(tester, 'L42');
    await _tap(tester, find.byKey(const Key('returns-submit')));

    expect(find.text('Do not sell this item'), findsOneWidget);
    expect(find.text('Marmalade'), findsWidgets);
    expect(server.exchanges, isEmpty);
  });

  testWidgets('a pack with no lot under a lot-scoped recall is shown to the cashier',
      (tester) async {
    final server = _Server(recalls: _lotRecall);
    await _pump(tester, server);
    await _startExchange(tester);
    await _scan(tester, 'NOLOT');
    await _tap(tester, find.byKey(const Key('returns-submit')));

    expect(find.text('Check the pack before selling'), findsOneWidget);
    expect(find.textContaining('lot L42'), findsOneWidget);
    expect(server.exchanges, isEmpty);
    // Affected: nothing is sent. Not affected: the exchange goes.
    await _tap(tester, find.text('Affected — remove'));
    expect(server.exchanges, isEmpty);
    await _tap(tester, find.byKey(const Key('returns-submit')));
    await _tap(tester, find.text('Not affected — sell'));
    expect(server.exchanges, hasLength(1));
  });

  testWidgets('a basket that differs only by lot gets a new key, the same basket keeps its key',
      (tester) async {
    final server = _Server();
    server.exchangeReplies.add((500, '{"error":{"code":"X","message":"boom"}}'));
    await _pump(tester, server);
    await _startExchange(tester);
    await _scan(tester, 'L43');
    await _tap(tester, find.byKey(const Key('returns-submit')));
    await _tap(tester, find.byKey(const Key('returns-submit')));
    final keys = server.exchanges.map((r) => r.headers['Idempotency-Key']).toList();
    expect(keys, hasLength(2));
    expect(keys[0], keys[1], reason: 'a retry of the same basket keeps its key');

    // Take the L43 pack off and put L44 in: same variant and quantity.
    await _tap(
        tester,
        find.descendant(
            of: find.byKey(const Key('returns-new-v-2-L43')),
            matching: find.byTooltip('Decrease quantity')));
    await _scan(tester, 'L44');
    await _tap(tester, find.byKey(const Key('returns-submit')));
    final third = server.exchanges.last.headers['Idempotency-Key'];
    expect(third, isNot(keys[0]));
  });

  testWidgets('the server\'s recalled-lot refusal is shown in its words, naming the line',
      (tester) async {
    final server = _Server();
    server.exchangeReplies.add((
      409,
      _problem('ORDER_LINE_RECALLED', 'Marmalade is under recall R-42.',
          ['items[1]: variant v-2 — recall R-42'])
    ));
    await _pump(tester, server);
    await _startExchange(tester);
    await _scan(tester, 'L43');
    await _scan(tester, 'L42');
    await _tap(tester, find.byKey(const Key('returns-submit')));

    expect(find.textContaining('Marmalade is under recall R-42.'), findsOneWidget);
    expect(find.textContaining('Take off: Marmalade, lot L42.'), findsOneWidget);
    expect(find.byKey(const Key('returns-done')), findsNothing);
  });
}
