import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/offline/offline_queue.dart';
import 'package:storeql_app/core/offline/offline_sale.dart';
import 'package:storeql_app/core/offline/offline_synced.dart';
import 'package:storeql_app/core/storage/app_storage.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/pos/offline_queue_screen.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';
import 'package:storeql_app/features/pos/pos_shell.dart';

// ---------------------------------------------------------------------------
// Clocked out, the terminal sells nothing — but the sales it took offline are
// still money the server has not been told about, and the cashier must be able
// to look at them. Pending goes through the clock-in gate; Sale, Tender and
// Cash stay behind it.
// ---------------------------------------------------------------------------

class _NoSession extends PosSessionNotifier {
  _NoSession(super.ref);

  @override
  Future<void> restore() async {}
}

/// A clock-in the server takes its time over, and then refuses.
class _SlowRefusal extends PosSessionNotifier {
  _SlowRefusal(super.ref, this.answer);
  final Completer<void> answer;

  @override
  Future<void> restore() async {}

  @override
  Future<void> clockIn(String storeId, {int idleTimeoutSeconds = 900}) =>
      answer.future;
}

class _MemStorage implements AppStorage {
  final Map<String, String> data = {};

  @override
  Future<String?> read({required String key}) async => data[key];

  @override
  Future<void> write({required String key, required String? value}) async {
    if (value == null) {
      data.remove(key);
    } else {
      data[key] = value;
    }
  }

  @override
  Future<void> delete({required String key}) async => data.remove(key);

  @override
  Future<void> deleteAll({Set<String> keep = const {}}) async =>
      data.removeWhere((k, _) => !keep.contains(k));
}

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

OfflineSale _sale() => OfflineSale(
      id: '01a0c830-0e7a-7b3c-9d2e-5f1a2b3c4d5e',
      capturedAt: DateTime.utc(2026, 9, 8, 11, 30),
      storeId: 'store-1',
      currency: 'GBP',
      orderRequest: const {'storeId': 'store-1'},
      tenders: const [OfflineTender(body: {'method': 'CASH'}, amount: 12.5)],
      total: 12.5,
      itemCount: 3,
    );

Future<void> _pump(WidgetTester tester, String location, Widget child) =>
    _pumpApp(tester,
        MaterialApp(home: PosShell(currentLocation: location, child: child)));

/// The shell as the router mounts it, with Sale and Pending behind it.
Future<GoRouter> _pumpRouted(WidgetTester tester,
    {Completer<void>? clockIn}) async {
  final router = GoRouter(initialLocation: '/pos/cart', routes: [
    ShellRoute(
      builder: (_, state, child) =>
          PosShell(currentLocation: state.uri.path, child: child),
      routes: [
        for (final path in ['/pos/cart', '/pos/tender', '/pos/cash'])
          GoRoute(path: path, builder: (_, _) => Text('screen $path')),
        GoRoute(
            path: '/pos/pending',
            builder: (_, _) => const OfflineQueueScreen()),
      ],
    ),
  ]);
  addTearDown(router.dispose);
  await _pumpApp(tester, MaterialApp.router(routerConfig: router),
      clockIn: clockIn);
  return router;
}

Future<void> _pumpApp(WidgetTester tester, Widget app,
    {Completer<void>? clockIn}) async {
  tester.view.physicalSize = const Size(390, 844);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  late OfflineQueueNotifier queue;
  final storage = _MemStorage();
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(
          _FakeApiClient(Dio(BaseOptions(baseUrl: 'http://test')))),
      posSessionProvider.overrideWith((ref) =>
          clockIn == null ? _NoSession(ref) : _SlowRefusal(ref, clockIn)),
      posStoresProvider.overrideWith((ref) async => clockIn == null
          ? const []
          : const [
              StoreInfo(
                  id: 'store-1',
                  name: 'High Street',
                  code: 'HS',
                  type: 'STORE',
                  status: 'ACTIVE'),
            ]),
      offlineQueueProvider.overrideWith((ref) {
        queue = OfflineQueueNotifier(ref, storage: storage, autoSync: false);
        return queue;
      }),
      offlineSyncedProvider
          .overrideWith((ref) => SyncedSalesNotifier(ref, storage: storage)),
    ],
    child: app,
  ));
  await tester.pump();
  await queue.enqueue(_sale());
  // Not settle: a waiting sale's spinner turns for as long as it waits.
  await tester.pump();
  await tester.pump();
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('clocked out, Pending still shows the sales waiting to sync',
      (tester) async {
    await _pump(tester, '/pos/pending', const OfflineQueueScreen());

    expect(find.text('Open a POS session to start selling.'), findsNothing,
        reason: 'the clock-in card must not hide money owed to the server');
    expect(find.byType(OfflineQueueScreen), findsOneWidget);
    expect(find.textContaining('Sale #3C4D5E'), findsOneWidget);
    expect(find.text('1 sale not yet on the server'), findsOneWidget);
  });

  testWidgets('clocked out, the Sale tab is behind the clock-in card',
      (tester) async {
    await _pump(tester, '/pos/cart', const Text('the sale screen'));

    expect(find.text('Open a POS session to start selling.'), findsOneWidget);
    expect(find.text('the sale screen'), findsNothing);
    // The badge still says there is something waiting, from anywhere, and
    // the card offers the way to it.
    expect(find.text('Pending'), findsOneWidget);
    expect(find.text('1'), findsOneWidget);
    expect(find.text('1 sale waiting to sync'), findsOneWidget);
  });

  testWidgets('clocked out, Pending on the bar and the card both reach the queue',
      (tester) async {
    final router = await _pumpRouted(tester);
    expect(find.text('Open a POS session to start selling.'), findsOneWidget);
    expect(find.text('screen /pos/cart'), findsNothing);

    // The clock-in card says what is waiting, and goes there.
    await tester.tap(find.byKey(const Key('pos-clocked-out-pending')));
    await tester.pump();
    await tester.pump();
    expect(router.state.uri.path, '/pos/pending');
    expect(find.byType(OfflineQueueScreen), findsOneWidget);

    // Sale is still behind the gate; the bar's Pending comes back through it.
    await tester.tap(find.text('Sale'));
    await tester.pump();
    await tester.pump();
    expect(find.text('Open a POS session to start selling.'), findsOneWidget);
    await tester.tap(find.text('Pending'));
    await tester.pump();
    await tester.pump();
    expect(find.textContaining('Sale #3C4D5E'), findsOneWidget);
  });

  testWidgets(
      'a clock-in still in flight keeps the card, and one that fails after the '
      'cashier went to Pending throws nothing', (tester) async {
    final answer = Completer<void>();
    final router = await _pumpRouted(tester, clockIn: answer);
    await tester.tap(find.text('Clock in').last);
    await tester.pump();
    expect(find.text('Opening…'), findsOneWidget);
    // The card's way to Pending waits for the answer.
    final toPending = tester.widget<ButtonStyleButton>(
        find.byKey(const Key('pos-clocked-out-pending')));
    expect(toPending.onPressed, isNull);

    // The bar's Pending still leaves the card; then the server refuses.
    router.go('/pos/pending');
    await tester.pump();
    await tester.pump();
    expect(find.byType(OfflineQueueScreen), findsOneWidget);
    answer.completeError(Exception('refused'));
    await tester.pump();
    await tester.pump();
    expect(tester.takeException(), isNull);
  });
}
