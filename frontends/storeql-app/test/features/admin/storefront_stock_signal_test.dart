import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/inventory_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The storefront stock signal: a business-wide "Only N
// left" threshold — off until an owner (or a business-wide manager) sets it.
// Hidden for storekeepers, cashiers and a store-held manager: the server
// would refuse them (403) if they ever reached it.
// ---------------------------------------------------------------------------

class _StoreHeldManager extends AuthNotifier {
  @override
  Future<AuthState> build() async => const AuthAuthenticated(
    accessToken: 'a',
    refreshToken: 'r',
    userId: 'u',
    tenantId: 't',
    roles: ['MANAGER'],
    storeIds: ['s1'],
  );
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int? threshold;
  _Server({this.threshold});

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async {
    requests.add(o);
    if (o.method == 'GET' &&
        o.path.endsWith('/admin/inventory/storefront-settings')) {
      return jsonResponse(
        jsonEncode({
          'data': {'lowStockThreshold': threshold},
        }),
      );
    }
    if (o.method == 'PUT' &&
        o.path.endsWith('/admin/inventory/storefront-settings')) {
      final body =
          (o.data is String ? jsonDecode(o.data as String) : o.data)
              as Map<String, dynamic>;
      threshold = body['lowStockThreshold'] as int?;
      return jsonResponse(
        jsonEncode({
          'data': {'lowStockThreshold': threshold},
        }),
      );
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _pump(
  WidgetTester tester, {
  required AuthNotifier Function() auth,
  int? threshold,
}) async {
  tester.view.physicalSize = const Size(1200, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(threshold: threshold);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(FakeApiClient(dio)),
        authNotifierProvider.overrideWith(auth),
      ],
      child: const MaterialApp(home: Scaffold(body: InventoryScreen())),
    ),
  );
  await tester.pumpAndSettle();
  return server;
}

void main() {
  testWidgets('an owner sees the button, sets a threshold, and it is sent', (
    tester,
  ) async {
    final server = await _pump(tester, auth: () => RoleAuth('OWNER'));

    expect(find.byKey(const Key('storefront-stock-signal')), findsOneWidget);
    await tester.tap(find.byKey(const Key('storefront-stock-signal')));
    await tester.pumpAndSettle();

    expect(find.text('Off'), findsOneWidget);
    await tester.tap(find.byKey(const Key('stock-signal-on')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('stock-signal-threshold')),
      '5',
    );
    await tester.tap(find.byKey(const Key('stock-signal-save')));
    await tester.pumpAndSettle();

    final put = server.requests.singleWhere((r) => r.method == 'PUT');
    final body =
        (put.data is String ? jsonDecode(put.data as String) : put.data)
            as Map<String, dynamic>;
    expect(body['lowStockThreshold'], 5);
    expect(find.text('Storefront stock signal saved.'), findsOneWidget,
        reason: 'saving closed the dialog with no word of it before this');
  });

  testWidgets('switching it off says so, in words, once saved', (
    tester,
  ) async {
    await _pump(tester, auth: () => RoleAuth('OWNER'), threshold: 5);
    await tester.tap(find.byKey(const Key('storefront-stock-signal')));
    await tester.pumpAndSettle();

    expect(
      tester.widget<SwitchListTile>(find.byKey(const Key('stock-signal-on'))).value,
      isTrue,
      reason: 'a threshold was already set',
    );
    await tester.tap(find.byKey(const Key('stock-signal-on')));
    await tester.tap(find.byKey(const Key('stock-signal-save')));
    await tester.pumpAndSettle();

    expect(find.text('Storefront stock signal switched off.'), findsOneWidget);
  });

  testWidgets('a number outside 1-1000 is refused before it is ever sent', (
    tester,
  ) async {
    final server = await _pump(tester, auth: () => RoleAuth('OWNER'));
    await tester.tap(find.byKey(const Key('storefront-stock-signal')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('stock-signal-on')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('stock-signal-threshold')),
      '1001',
    );
    await tester.tap(find.byKey(const Key('stock-signal-save')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('stock-signal-error')), findsOneWidget);
    expect(server.requests.any((r) => r.method == 'PUT'), isFalse);
  });

  testWidgets('a business-wide manager (no stores) sees the button too', (
    tester,
  ) async {
    await _pump(tester, auth: () => RoleAuth('MANAGER'), threshold: 3);

    expect(find.byKey(const Key('storefront-stock-signal')), findsOneWidget);
  });

  testWidgets('hidden for a store-held manager', (tester) async {
    await _pump(tester, auth: () => _StoreHeldManager());

    expect(find.byKey(const Key('storefront-stock-signal')), findsNothing);
  });

  testWidgets('hidden for a storekeeper', (tester) async {
    await _pump(tester, auth: () => RoleAuth('STOREKEEPER'));

    expect(find.byKey(const Key('storefront-stock-signal')), findsNothing);
  });

  testWidgets('hidden for a cashier', (tester) async {
    await _pump(tester, auth: () => RoleAuth('CASHIER'));

    expect(find.byKey(const Key('storefront-stock-signal')), findsNothing);
  });
}
