import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/constants.dart';
import 'package:storeql_app/core/ids.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/offline/offline_queue.dart';
import 'package:storeql_app/core/offline/offline_sale.dart';
import 'package:storeql_app/core/offline/offline_synced.dart';
import 'package:storeql_app/core/storage/app_storage.dart';

/// A sale's local id: a UUIDv7, as the till mints it, and the base of every key its steps send.
const _saleId = '01a0c830-0e7a-7b3c-9d2e-5f1a2b3c4d5e';

// ---------------------------------------------------------------------------
// Replay is the dangerous half of an offline till: every request in a queued
// sale is sent again, possibly after one of them already landed. These tests pin
// the two properties that make that safe — the idempotency keys come from the
// stored sale rather than the attempt, and a step that has landed is not redone.
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

class _FakeApiClient implements ApiClient {
  // Not `final`: ApiClient declares `late final Dio dio`, which carries an
  // implicit setter that an implementing class has to satisfy too.
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

/// Records every request, and can be switched between "server reachable",
/// "network gone", and "server rejects".
class _ScriptedAdapter implements HttpClientAdapter {
  final List<({String path, String? idempotencyKey})> calls = [];

  /// When set, every request fails as if the network were gone.
  bool offline = false;

  /// path fragment → status to answer with instead of success.
  final Map<String, int> rejectWith = {};

  /// Fails only the Nth matching request, then behaves normally — used to cut
  /// the line partway through a sale.
  String? failAfterPath;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<List<int>>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    calls.add((
      path: options.path,
      idempotencyKey: options.headers['Idempotency-Key'] as String?,
    ));

    if (offline) {
      throw DioException(
          requestOptions: options, type: DioExceptionType.connectionError);
    }
    for (final entry in rejectWith.entries) {
      if (options.path.contains(entry.key)) {
        return ResponseBody.fromString(
          '{"data":null,"error":{"code":"ORDER_BAD","message":"rejected"}}',
          entry.value,
          headers: {
            Headers.contentTypeHeader: [Headers.jsonContentType]
          },
        );
      }
    }
    if (failAfterPath != null && options.path.contains(failAfterPath!)) {
      offline = true; // everything after this request finds the line dead
    }

    final body = options.path.contains('/orders')
        ? '{"data":{"id":"order-1","total":5.00}}'
        : '{"data":{"id":"x"}}';
    return ResponseBody.fromString(body, 201, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }

  List<String> get paths => calls.map((c) => c.path).toList();
  int countOf(String fragment) =>
      paths.where((p) => p.contains(fragment)).length;
}

OfflineSale _sale({
  String id = _saleId,
  String? orderId,
  List<OfflineTender>? tenders,
}) =>
    OfflineSale(
      id: id,
      capturedAt: DateTime.utc(2026, 9, 8, 11, 30),
      storeId: 'store-1',
      currency: 'GBP',
      orderRequest: const {'storeId': 'store-1', 'channel': 'POS'},
      tenders: tenders ??
          const [OfflineTender(body: {'method': 'CASH'}, amount: 5.0)],
      total: 5.0,
      itemCount: 1,
      orderId: orderId,
    );

({ProviderContainer container, _ScriptedAdapter adapter, _MemStorage storage})
    _harness({_MemStorage? reuseStorage}) {
  final adapter = _ScriptedAdapter();
  final dio = Dio(BaseOptions(baseUrl: ApiConstants.baseUrl))
    ..httpClientAdapter = adapter;
  final storage = reuseStorage ?? _MemStorage();
  final container = ProviderContainer(overrides: [
    apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
    offlineQueueProvider.overrideWith(
        (ref) => OfflineQueueNotifier(ref, storage: storage, autoSync: false)),
    offlineSyncedProvider
        .overrideWith((ref) => SyncedSalesNotifier(ref, storage: storage)),
  ]);
  addTearDown(container.dispose);
  return (container: container, adapter: adapter, storage: storage);
}

void main() {
  _lossTests();

  test('an enqueued sale is on disk before the cashier is told it is saved',
      () async {
    final h = _harness();
    await h.container.read(offlineQueueProvider.notifier).enqueue(_sale());

    expect(h.container.read(offlineQueueCountProvider), 1);
    final stored =
        jsonDecode(h.storage.data[StorageKeys.posOfflineSales]!) as List;
    expect(stored, hasLength(1));
    expect((stored.single as Map)['id'], _saleId);
  });

  test('replay uses the keys stored with the sale, not fresh ones', () async {
    // This is the property the whole design rests on: the same sale replayed
    // twice presents the same Idempotency-Key, so order-svc and payment-svc
    // return the original rows instead of creating second ones.
    final h = _harness();
    final notifier = h.container.read(offlineQueueProvider.notifier);
    await notifier.enqueue(_sale());
    await notifier.sync();

    expect(h.adapter.calls.map((c) => c.idempotencyKey), [
      derivedId(_saleId, 'order'),
      derivedId(_saleId, 'pay:0'),
      null, // the journal write is idempotent on the order id, so it needs no key
    ]);
    expect(h.container.read(offlineQueueProvider), isEmpty,
        reason: 'a fully accepted sale leaves the queue');
    expect(h.storage.data[StorageKeys.posOfflineSales], '[]');
  });

  test('a sale that lands is remembered as synced, with the order the server gave it',
      () async {
    // The offline receipt was printed with no legal number. The number is
    // issued when the replayed payment completes the sale, and the cashier
    // has to be able to find it from the reference on that receipt.
    final h = _harness();
    final notifier = h.container.read(offlineQueueProvider.notifier);
    await notifier.enqueue(_sale());
    await notifier.sync();

    final synced = h.container.read(offlineSyncedProvider);
    expect(synced, hasLength(1));
    expect(synced.single.id, _saleId);
    expect(synced.single.orderId, 'order-1');
    expect(synced.single.reference, '3C4D5E',
        reason: 'the id\'s random tail in capitals, as the offline receipt printed it');
    expect(synced.single.fiscalNumber, isNull,
        reason: 'the number is looked up when someone asks, not during replay');
    // Replay made no extra request for it: the queue owes the server writes,
    // not questions, and a till syncing fifty sales must not wait on fifty.
    expect(h.adapter.countOf('fiscal-receipt'), 0);
    // And it is on disk, so a restarted till still knows.
    final stored = jsonDecode(h.storage.data[StorageKeys.posOfflineSynced]!) as List;
    expect((stored.single as Map)['orderId'], 'order-1');
  });

  test('a sale whose order already landed does not place it again', () async {
    // The network died after the order was accepted. Re-posting would be
    // harmless (order-svc replays the key) but wasteful, and the queue knows.
    final h = _harness();
    final notifier = h.container.read(offlineQueueProvider.notifier);
    await notifier.enqueue(_sale(orderId: 'order-1'));
    await notifier.sync();

    // `/orders` must not be re-posted — but `/pos/log/orders/…` legitimately
    // contains it, so match the placement path exactly rather than by substring.
    expect(h.adapter.paths.where((p) => p.endsWith('/orders')), isEmpty);
    expect(h.adapter.countOf('/payments'), 1);
    expect(h.adapter.countOf('/pos/log/orders/'), 1);
  });

  test('a tender that already landed is not recorded twice', () async {
    final h = _harness();
    final notifier = h.container.read(offlineQueueProvider.notifier);
    await notifier.enqueue(_sale(orderId: 'order-1', tenders: const [
      OfflineTender(body: {'method': 'CASH'}, amount: 2.0, tenderDone: true),
      OfflineTender(body: {'method': 'CARD'}, amount: 3.0),
    ]));
    await notifier.sync();

    expect(h.adapter.countOf('/payments'), 1);
    expect(h.adapter.calls.first.idempotencyKey, derivedId(_saleId, 'pay:1'),
        reason: 'the key is positional, so the second tender keeps its own key');
  });

  test('a gift card is redeemed after its tender, and only once', () async {
    final h = _harness();
    final notifier = h.container.read(offlineQueueProvider.notifier);
    await notifier.enqueue(_sale(tenders: const [
      OfflineTender(
          body: {'method': 'GIFT_CARD'}, amount: 5.0, giftCardCode: 'GC-1'),
    ]));
    await notifier.sync();

    expect(h.adapter.paths, [
      '/${ApiConstants.order}/orders',
      '/${ApiConstants.payment}/payments',
      '/${ApiConstants.order}/gift-cards/GC-1/redeem',
      '/${ApiConstants.order}/pos/log/orders/order-1',
    ]);
  });

  test('a sale that only owes its journal replays just that', () async {
    // The money landed and the line died before the journal. Re-sending the
    // order or the tender would be wasted work; the queue knows what is left.
    final h = _harness();
    final notifier = h.container.read(offlineQueueProvider.notifier);
    await notifier.enqueue(_sale(orderId: 'order-1', tenders: const [
      OfflineTender(body: {'method': 'CASH'}, amount: 5.0, tenderDone: true),
    ]));
    await notifier.sync();

    expect(h.adapter.paths, ['/${ApiConstants.order}/pos/log/orders/order-1']);
    expect(h.container.read(offlineQueueProvider), isEmpty);
  });

  test('a journalled sale is not journalled again', () async {
    final h = _harness();
    final notifier = h.container.read(offlineQueueProvider.notifier);
    await notifier.enqueue(_sale(orderId: 'order-1', tenders: const [
      OfflineTender(body: {'method': 'CASH'}, amount: 5.0, tenderDone: true),
    ]).copyWith(posLogDone: true));
    await notifier.sync();

    expect(h.adapter.calls, isEmpty, reason: 'nothing was owed');
    expect(h.container.read(offlineQueueProvider), isEmpty);
  });

  test('progress is kept when the line drops mid-sale', () async {
    // The order lands, then the network dies before the tender. The sale must
    // stay queued, remembering that the order is already placed.
    final h = _harness();
    final notifier = h.container.read(offlineQueueProvider.notifier);
    h.adapter.failAfterPath = '/orders';
    await notifier.enqueue(_sale());
    await notifier.sync();

    final held = h.container.read(offlineQueueProvider).single;
    expect(held.orderId, 'order-1');
    expect(held.tenders.single.tenderDone, isFalse);
    expect(held.status, OfflineSaleStatus.pending);
    expect(held.attempts, 1);

    // On the next attempt it resumes at the tender rather than re-placing.
    h.adapter.offline = false;
    h.adapter.calls.clear();
    await notifier.sync();
    expect(h.adapter.paths.where((p) => p.endsWith('/orders')), isEmpty);
    expect(h.container.read(offlineQueueProvider), isEmpty);
  });

  test('a sale the server rejects is parked, not dropped and not retried',
      () async {
    // The customer has already paid, so a rejected sale must stay visible for a
    // human — but retrying it would loop forever.
    final h = _harness();
    final notifier = h.container.read(offlineQueueProvider.notifier);
    h.adapter.rejectWith['/orders'] = 400;
    await notifier.enqueue(_sale());
    await notifier.sync();

    final parked = h.container.read(offlineQueueProvider).single;
    expect(parked.status, OfflineSaleStatus.failed);
    expect(parked.lastError, 'rejected');

    await notifier.sync();
    expect(h.adapter.countOf('/orders'), 1, reason: 'not retried while failed');
  });

  test('one rejected sale does not block the ones behind it', () async {
    final h = _harness();
    final notifier = h.container.read(offlineQueueProvider.notifier);
    await notifier.enqueue(
        _sale(id: 'pos-1700000000001').copyWith(status: OfflineSaleStatus.failed));
    await notifier.enqueue(_sale(id: 'pos-1700000000002'));
    await notifier.sync();

    expect(h.container.read(offlineQueueProvider).map((s) => s.id),
        ['pos-1700000000001']);
  });

  test('an unreachable server stops the run instead of hammering every sale',
      () async {
    final h = _harness();
    final notifier = h.container.read(offlineQueueProvider.notifier);
    h.adapter.offline = true;
    await notifier.enqueue(_sale(id: 'pos-1700000000001'));
    await notifier.enqueue(_sale(id: 'pos-1700000000002'));
    await notifier.sync();

    expect(h.adapter.calls, hasLength(1));
    expect(h.container.read(offlineQueueProvider), hasLength(2));
  });

  test('retry puts a parked sale back in line', () async {
    final h = _harness();
    final notifier = h.container.read(offlineQueueProvider.notifier);
    h.adapter.rejectWith['/orders'] = 400;
    await notifier.enqueue(_sale());
    await notifier.sync();

    h.adapter.rejectWith.clear();
    await notifier.retry(_saleId);
    expect(h.container.read(offlineQueueProvider), isEmpty);
  });

  test('discard removes the sale and the server is never told', () async {
    final h = _harness();
    final notifier = h.container.read(offlineQueueProvider.notifier);
    await notifier.enqueue(_sale());
    await notifier.discard(_saleId);

    expect(h.container.read(offlineQueueProvider), isEmpty);
    expect(h.adapter.calls, isEmpty);
    expect(h.storage.data[StorageKeys.posOfflineSales], '[]');
  });

  test('the queue comes back after the app is killed and restarted', () async {
    final h = _harness();
    await h.container
        .read(offlineQueueProvider.notifier)
        .enqueue(_sale(orderId: 'order-1'));

    // A fresh container over the same storage — i.e. the till was restarted.
    final restarted = _harness(reuseStorage: h.storage);
    await restarted.container.read(offlineQueueProvider.notifier).restore();

    final held = restarted.container.read(offlineQueueProvider).single;
    expect(held.id, _saleId);
    expect(held.orderId, 'order-1');
  });

  test('a corrupt queue file leaves the till usable', () async {
    final storage = _MemStorage();
    storage.data[StorageKeys.posOfflineSales] = 'not json';
    final h = _harness(reuseStorage: storage);
    await h.container.read(offlineQueueProvider.notifier).restore();

    expect(h.container.read(offlineQueueProvider), isEmpty);
    expect(storage.data[StorageKeys.posOfflineSales], 'not json',
        reason: 'unreadable sales are money — kept on disk for recovery');
  });
}

// ---------------------------------------------------------------------------
// Losing a queued sale is the worst thing this class can do: the customer has
// paid, the till said "saved", and the server never hears about it. These pin
// the two ways it used to happen, both found in the branch review.
// ---------------------------------------------------------------------------

void _lossTests() {
  test('a corrupt queue is moved aside, not overwritten by the next sale',
      () async {
    final storage = _MemStorage();
    // Whatever this is, it is not a queue this build can parse — but the sales
    // inside it are money.
    storage.data[StorageKeys.posOfflineSales] = '{not json at all';

    final h = _harness(reuseStorage: storage);
    await h.container.read(offlineQueueProvider.notifier).enqueue(_sale());

    // The unreadable payload survives somewhere a developer can reach it.
    expect(storage.data[StorageKeys.posOfflineSalesCorrupt], '{not json at all');
    // …and the till still works: the new sale is durable.
    expect(storage.data[StorageKeys.posOfflineSales], contains(_saleId));
  });

  test('a sale taken before restore finishes does not replace what is on disk',
      () async {
    final storage = _MemStorage();
    storage.data[StorageKeys.posOfflineSales] =
        jsonEncode([_sale(id: 'pos-already-queued').toJson()]);

    final h = _harness(reuseStorage: storage);
    // Deliberately NOT awaiting restore first — this is the startup window. The
    // notifier's constructor kicks restore off; enqueue used to race it and
    // write a one-entry list over the restored queue.
    await h.container
        .read(offlineQueueProvider.notifier)
        .enqueue(_sale(id: 'pos-taken-at-startup'));

    final onDisk = jsonDecode(storage.data[StorageKeys.posOfflineSales]!) as List;
    final ids = onDisk.map((e) => (e as Map)['id']).toList();
    expect(ids, containsAll(['pos-already-queued', 'pos-taken-at-startup']));
    expect(h.container.read(offlineQueueProvider).length, 2);
  });
}
