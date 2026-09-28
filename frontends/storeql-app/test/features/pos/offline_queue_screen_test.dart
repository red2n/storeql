import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/format.dart';
import 'package:storeql_app/core/offline/offline_queue.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/offline/offline_sale.dart';
import 'package:storeql_app/core/offline/offline_synced.dart';
import 'package:storeql_app/core/storage/app_storage.dart';
import 'package:storeql_app/features/pos/offline_queue_screen.dart';

// ---------------------------------------------------------------------------
// An invisible queue is worse than none: the cashier has taken real money and
// has to be able to see it is still owed to the server. These pin what the
// screen tells them, and that discarding a sale is never one tap away.
// ---------------------------------------------------------------------------

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

OfflineSale _sale({
  String id = 'pos-1700000123456',
  OfflineSaleStatus status = OfflineSaleStatus.pending,
  String? lastError,
}) =>
    OfflineSale(
      id: id,
      capturedAt: DateTime.utc(2026, 9, 8, 11, 30),
      storeId: 'store-1',
      currency: 'GBP',
      orderRequest: const {'storeId': 'store-1'},
      tenders: const [OfflineTender(body: {'method': 'CASH'}, amount: 12.5)],
      total: 12.5,
      itemCount: 3,
      status: status,
      lastError: lastError,
    );

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

/// Answers the receipt-number lookup: issued, or not yet.
class _ReceiptServer implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  String? number;
  _ReceiptServer({this.number});

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (number == null) {
      return ResponseBody.fromString(
          '{"error":{"code":"ORDER_RECEIPT_NOT_ISSUED","details":[]}}', 404,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
    }
    return ResponseBody.fromString(
        '{"data":{"fullNumber":"$number","number":7}}', 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

SyncedSale _synced({String id = 'pos-1700000999999'}) => SyncedSale(
      id: id,
      orderId: '01a090ae-611e-701e-a773-cff68a489efe',
      capturedAt: DateTime.utc(2026, 9, 8, 11, 30),
      syncedAt: DateTime.utc(2026, 9, 8, 11, 45),
      total: 8.75,
      currency: 'GBP',
    );

Future<OfflineQueueNotifier> _pump(WidgetTester tester, List<OfflineSale> sales,
    {List<SyncedSale> synced = const [], _ReceiptServer? server}) async {
  late OfflineQueueNotifier notifier;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server ?? _ReceiptServer();
  final storage = _MemStorage();
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      offlineQueueProvider.overrideWith((ref) {
        notifier = OfflineQueueNotifier(ref, storage: storage, autoSync: false);
        return notifier;
      }),
      offlineSyncedProvider.overrideWith((ref) {
        final n = SyncedSalesNotifier(ref, storage: storage);
        for (final s in synced) {
          n.state = [...n.state, s];
        }
        return n;
      }),
    ],
    child: const MaterialApp(home: Scaffold(body: OfflineQueueScreen())),
  ));
  for (final s in sales) {
    await notifier.enqueue(s);
  }
  await tester.pump();
  return notifier;
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('an empty queue says so rather than showing a blank pane',
      (tester) async {
    await _pump(tester, const []);
    expect(find.text('Everything is synced'), findsOneWidget);
  });

  testWidgets('a synced sale shows the legal receipt number the server issued on replay',
      (tester) async {
    // The offline receipt in the customer's hand says "number not issued yet".
    // This is where the cashier finds it — by the same reference that receipt
    // was printed with.
    final server = _ReceiptServer(number: 'GB-A-2026-000007');
    await _pump(tester, const [], synced: [_synced()], server: server);
    await tester.pumpAndSettle();

    expect(find.text('Everything is synced'), findsOneWidget);
    expect(find.text('Sale #999999  ·  £8.75'), findsOneWidget);
    expect(find.text('Receipt no. GB-A-2026-000007'), findsOneWidget);
    // One bounded server-side wait, on the till-readable path.
    expect(server.requests.single.path, endsWith('/fiscal-receipt'));
    expect(server.requests.single.queryParameters['wait'], 5);
    expect(server.requests.single.path, isNot(contains('/admin/')));
    expect(find.byTooltip('Check again'), findsNothing);
  });

  testWidgets('a synced sale whose number is not issued yet says so, and can be asked again',
      (tester) async {
    final server = _ReceiptServer();
    await _pump(tester, const [], synced: [_synced()], server: server);
    await tester.pumpAndSettle();

    expect(find.textContaining('Receipt number not issued yet'), findsOneWidget);
    expect(find.textContaining('Receipt no. '), findsNothing,
        reason: 'nothing on this screen may pass an order id off as a receipt number');
    // The number arrives; asking again finds it and remembers it.
    server.number = 'GB-A-2026-000008';
    await tester.tap(find.byTooltip('Check again'));
    await tester.pumpAndSettle();
    expect(find.text('Receipt no. GB-A-2026-000008'), findsOneWidget);
    expect(server.requests, hasLength(2));
  });

  testWidgets('a waiting sale shows its reference, total and item count',
      (tester) async {
    await _pump(tester, [_sale()]);

    expect(find.text('Sale #123456  ·  £12.50'), findsOneWidget);
    expect(find.textContaining('3 items'), findsOneWidget);
    expect(find.text('1 sale not yet on the server'), findsOneWidget);
    // Still in line: a spinner, and no way to throw it away.
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    expect(find.byTooltip('Discard'), findsNothing);
  });

  testWidgets('a rejected sale shows why, and offers retry and discard',
      (tester) async {
    await _pump(tester, [
      _sale(status: OfflineSaleStatus.failed, lastError: 'Store is closed.')
    ]);

    expect(find.text('Store is closed.'), findsOneWidget);
    expect(find.text('1 sale not yet on the server · 1 need attention'),
        findsOneWidget);
    expect(find.byTooltip('Try again'), findsOneWidget);
    expect(find.byTooltip('Discard'), findsOneWidget);
  });

  testWidgets('discarding is confirmed first — the customer has already paid',
      (tester) async {
    final notifier = await _pump(tester, [
      _sale(status: OfflineSaleStatus.failed, lastError: 'Rejected.')
    ]);

    await tester.tap(find.byTooltip('Discard'));
    await tester.pumpAndSettle();
    expect(find.text('Discard sale #123456?'), findsOneWidget);

    // Backing out leaves the sale exactly where it was.
    await tester.tap(find.text('Keep'));
    await tester.pumpAndSettle();
    expect(notifier.state, hasLength(1));

    await tester.tap(find.byTooltip('Discard'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Discard'));
    await tester.pumpAndSettle();
    expect(notifier.state, isEmpty);
    expect(find.text('Everything is synced'), findsOneWidget);
  });

  testWidgets('a reference reads in capitals, as order references do',
      (tester) async {
    await _pump(tester, [_sale(id: '01a0c830-0e7a-7b3c-9d2e-5f1a2b3c4d5e')]);

    expect(find.text('Sale #3C4D5E  ·  £12.50'), findsOneWidget);
    expect(find.textContaining('#3c4d5e'), findsNothing);
  });

  testWidgets('a synced sale shows the reference its receipt was printed with',
      (tester) async {
    await _pump(tester, const [],
        synced: [_synced(id: '01a0c830-0e7a-7b3c-9d2e-5f1a2b7c41ae')],
        server: _ReceiptServer(number: 'GB-A-2026-000009'));
    await tester.pumpAndSettle();

    expect(find.text('Sale #7C41AE  ·  £8.75'), findsOneWidget,
        reason: 'the offline receipt printed OfflineSale.reference: capitals');
  });

  testWidgets('the capture time is written like every other date and time',
      (tester) async {
    await _pump(tester, [_sale()]);

    final at = DateTime.utc(2026, 9, 8, 11, 30).toIso8601String();
    expect(find.textContaining(AppFormat.dateTime(at)), findsOneWidget);
    expect(find.textContaining('2026-09-08'), findsNothing,
        reason: 'no hand-built ISO date');
  });

  testWidgets('the status is a MaterialBanner, with Sync now as its action',
      (tester) async {
    await _pump(tester, [_sale()]);

    final banner = find.byType(MaterialBanner);
    expect(banner, findsOneWidget);
    expect(
        find.descendant(
            of: banner, matching: find.text('1 sale not yet on the server')),
        findsOneWidget);
    expect(find.descendant(of: banner, matching: find.text('Sync now')),
        findsOneWidget);
  });
}
