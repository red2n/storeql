import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/format.dart';
import 'package:storeql_app/features/storefront/orders_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// An order still waiting for payment lapses (order-svc, 30 Sep 2026: the
// business's own limit, else the platform's). The shopper's history says when,
// in words. The history list carries no expiry, so a PENDING order asks for
// its own; an order that is not waiting for payment says nothing of it.
// ---------------------------------------------------------------------------

const _pending = '01a0d930-0000-7000-8000-0000000000a1';
const _confirmed = '01a0d930-0000-7000-8000-0000000000a2';

class _SignedIn extends StorefrontAuthNotifier {
  _SignedIn() {
    state = const StorefrontAuthState(
        accessToken: 'tok', refreshToken: 'ref', email: 'sam@example.com');
  }
}

class _Orders implements HttpClientAdapter {
  final List<RequestOptions> gets = [];

  /// What the order itself says, and what the list says, of when it lapses.
  String? orderExpiresAt;
  String? listExpiresAt;
  bool orderReadFails = false;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    if (o.path.endsWith('/orders/mine')) {
      final exp = listExpiresAt == null ? '' : ',"expiresAt":"$listExpiresAt"';
      return jsonResponse('{"data":['
          '{"id":"$_pending","storeId":"s","fulfilmentType":"PICKUP","status":"PENDING","total":5,"currency":"GBP","createdAt":"2026-09-25T09:00:00Z"$exp},'
          '{"id":"$_confirmed","storeId":"s","fulfilmentType":"PICKUP","status":"CONFIRMED","total":6,"currency":"GBP","createdAt":"2026-09-25T09:05:00Z"}'
          ']}');
    }
    if (o.method == 'GET' && o.path.contains('/orders/')) {
      gets.add(o);
      if (orderReadFails) return jsonResponse('{"error":{"code":"X","message":"no"}}', 500);
      final exp = orderExpiresAt == null ? '' : ',"expiresAt":"$orderExpiresAt"';
      return jsonResponse('{"data":{"id":"${o.path.split('/').last}","status":"PENDING"$exp}}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Orders> _open(WidgetTester tester, _Orders server) async {
  tester.view.physicalSize = const Size(800, 1400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      storefrontDioProvider.overrideWithValue(dio),
      storefrontAuthProvider.overrideWith((ref) => _SignedIn()),
      storefrontStoresProvider.overrideWith((ref) async =>
          const [StoreSummary(id: 's', name: 'Leeds', showPrices: true)]),
      myRecallNoticesProvider.overrideWith((ref) async => const []),
    ],
    child: const MaterialApp(home: Scaffold(body: StorefrontOrdersScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

String _in(Duration d) => DateTime.now().toUtc().add(d).toIso8601String();

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('a pending order says when it lapses, from its own answer', (tester) async {
    final at = _in(const Duration(days: 2));
    final server = await _open(tester, _Orders()..orderExpiresAt = at);
    expect(server.gets.single.path, endsWith('/orders/$_pending'));
    expect(find.textContaining('Lapses if not paid by ${AppFormat.dateTime(at)}'), findsOneWidget);
  });

  testWidgets('an expiry the list already carries is used without asking again', (tester) async {
    final at = _in(const Duration(days: 1));
    final server = await _open(tester, _Orders()..listExpiresAt = at);
    expect(server.gets, isEmpty);
    expect(find.textContaining('Lapses if not paid by'), findsOneWidget);
  });

  testWidgets('an expiry already past reads as lapsing shortly, not as a date in the past',
      (tester) async {
    await _open(tester, _Orders()..orderExpiresAt = _in(const Duration(minutes: -5)));
    expect(find.textContaining('Lapsing shortly if not paid'), findsOneWidget);
    expect(find.textContaining('Lapses if not paid by'), findsNothing);
  });

  testWidgets('an order not waiting for payment says nothing of it, and is never asked', (tester) async {
    final server = await _open(tester, _Orders()..orderExpiresAt = _in(const Duration(days: 2)));
    // Only the pending order asked.
    expect(server.gets.map((r) => r.path), everyElement(endsWith('/orders/$_pending')));
    expect(find.textContaining('Lapses if not paid by'), findsOneWidget);
  });

  testWidgets('when the expiry cannot be read the order shows no expiry line and no error', (tester) async {
    await _open(tester, _Orders()..orderReadFails = true);
    expect(find.textContaining('Lapses'), findsNothing);
    expect(find.textContaining('Lapsing'), findsNothing);
    expect(find.byKey(const Key('cancel-order-$_pending')), findsOneWidget);
  });
}
