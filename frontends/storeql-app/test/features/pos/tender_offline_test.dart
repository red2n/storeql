import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/offline/offline_queue.dart';
import 'package:storeql_app/core/storage/app_storage.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';
import 'package:storeql_app/features/pos/tender_screen.dart';

// ---------------------------------------------------------------------------
// The entry point to the offline queue: what the till does when Complete Sale
// cannot reach the server. A dropped network must finish the sale locally — the
// customer has handed over cash — while a server that answers "no" must not be
// queued, because replaying it would fail in exactly the same way.
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
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

/// Either drops the connection or answers with a rejection, per [rejectStatus].
class _FailingAdapter implements HttpClientAdapter {
  int? rejectStatus;
  int calls = 0;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions options, Stream<List<int>>? stream, Future<void>? cancel) async {
    calls++;
    if (rejectStatus != null) {
      return ResponseBody.fromString(
        '{"data":null,"error":{"code":"ORDER_STORE_CLOSED","message":"Store is closed."}}',
        rejectStatus!,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType]
        },
      );
    }
    throw DioException(
        requestOptions: options, type: DioExceptionType.connectionError);
  }
}

class _NoopPosSessionNotifier extends PosSessionNotifier {
  _NoopPosSessionNotifier(super.ref);

  @override
  Future<void> restore() async {}
}

class _StubAuthNotifier extends AuthNotifier {
  @override
  Future<AuthState> build() async => const AuthUnauthenticated();
}

/// The cashier signed in at the till when the sale is made.
const _cashierId = '01a0f2b0-611e-7000-8000-0000000000a1';

class _SignedInCashier extends AuthNotifier {
  @override
  Future<AuthState> build() async => const AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: _cashierId,
        tenantId: '01a0f2b0-611e-702c-a97b-d1b8025478f1',
        roles: ['CASHIER'],
        storeIds: ['store-1'],
      );
}

const _line = PosLine(
  variantId: 'v-1',
  sku: 'SKU-1',
  name: 'Product 1',
  qty: 2,
  unitPrice: 6.0,
  currency: 'GBP',
);

class _LoadedCart extends PosCartNotifier {
  _LoadedCart() {
    loadLines(const [_line]);
  }
}

Future<_FailingAdapter> _pumpTender(WidgetTester tester,
    {int? rejectStatus,
    AuthNotifier Function()? auth,
    bool showPrices = true}) async {
  final adapter = _FailingAdapter()..rejectStatus = rejectStatus;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = adapter;

  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      offlineQueueProvider.overrideWith((ref) =>
          OfflineQueueNotifier(ref, storage: _MemStorage(), autoSync: false)),
      posStoresProvider.overrideWith((ref) async => const []),
      posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
      authNotifierProvider.overrideWith(auth ?? _StubAuthNotifier.new),
      posCartProvider.overrideWith((ref) => _LoadedCart()),
      posStoreProvider.overrideWith((ref) => 'store-1'),
      posWalkInPhoneProvider.overrideWith((ref) => '07700900000'),
      // A store that shows no prices: the till places the order without
      // taking payment (catalog mode).
      if (!showPrices) posShowPricesProvider.overrideWith((ref) => false),
    ],
    child: const MaterialApp(home: Scaffold(body: TenderScreen())),
  ));
  // In the app the router watches the sign-in from the start, so it has long
  // been read by the time a sale is made. Nothing here reads it until the sale
  // does, so it is read now and left to settle, as the router would have.
  _container(tester).read(authNotifierProvider);
  await tester.pumpAndSettle();
  return adapter;
}

/// Stage a cash tender for the full balance, then complete the sale.
Future<void> _tenderAndComplete(WidgetTester tester) async {
  await tester.tap(find.widgetWithText(OutlinedButton, 'Cash'));
  await tester.pumpAndSettle();
  await tester.tap(find.widgetWithText(FilledButton, 'Add'));
  await tester.pumpAndSettle();

  await tester.tap(find.widgetWithText(FilledButton, 'Complete Sale'));
  await tester.pumpAndSettle();
}

ProviderContainer _container(WidgetTester tester) =>
    ProviderScope.containerOf(tester.element(find.byType(TenderScreen).first));

void main() {
  testWidgets('a discount larger than the basket is clamped before it is sent',
      (tester) async {
    // The till used to clamp in two places that disagreed: the amount due was
    // capped at the subtotal, but the figure actually sent was not. A cashier who
    // keyed £99 off a £12 basket saw nothing left to pay, tendered it to zero, and
    // the server then refused the whole sale with
    // ORDER_DISCOUNT_EXCEEDS_SUBTOTAL — after the money was in the drawer.
    //
    // Harmless before SJ-D6, because the server discarded client discounts
    // entirely. Honouring them is what made the till's own figure matter.
    await _pumpTender(tester);
    final container = _container(tester);
    container.read(posDiscountProvider.notifier).state = 99.0;
    container.read(posDiscountReasonProvider.notifier).state = 'Manager override';
    await tester.pumpAndSettle();

    // Nothing left to tender — which is exactly the trap: the till considers the
    // sale fully paid, so the cashier can complete it, and the server then
    // refuses the discount the till never clamped.
    await tester.tap(find.widgetWithText(FilledButton, 'Complete Sale'));
    await tester.pumpAndSettle();

    // 2 × £6.00 = £12.00, so that is the most that can come off it.
    final queued = container.read(offlineQueueProvider).single;
    expect(queued.orderRequest['discountAmount'], 12.0);
    expect(queued.total, 0.0);
  });

  testWidgets('a tendered sale is recorded as handed over, not collect-later',
      (tester) async {
    // The till sent PICKUP — "collect later" everywhere else in the platform —
    // for every tendered sale, and nothing ever fulfilled one, so stock was
    // never deducted for a sale rung up at a till (SJ-D40). The server now
    // hands a paid till sale over either way, but the request should say what
    // happened: the goods left with the customer.
    await _pumpTender(tester);
    await _tenderAndComplete(tester);

    final queued = _container(tester).read(offlineQueueProvider).single;
    expect(queued.orderRequest['channel'], 'POS');
    expect(queued.orderRequest['fulfilmentType'], 'INSTORE');
  });

  testWidgets('an unreachable server completes the sale offline and queues it',
      (tester) async {
    await _pumpTender(tester);
    await _tenderAndComplete(tester);

    expect(find.text('Saved offline'), findsOneWidget);
    expect(
        find.textContaining("The server couldn't be reached"), findsOneWidget);

    final queued = _container(tester).read(offlineQueueProvider);
    expect(queued, hasLength(1), reason: 'the sale is held, not lost');
    expect(queued.single.total, 12.0);
    expect(queued.single.itemCount, 2);
    expect(queued.single.storeId, 'store-1');
    expect(queued.single.orderId, isNull, reason: 'the order never landed');
    expect(queued.single.tenders.single.body['method'], 'CASH');
    expect(queued.single.tenders.single.tenderDone, isFalse);
  });

  testWidgets('the offline receipt carries the reference shown to the cashier',
      (tester) async {
    await _pumpTender(tester);
    await _tenderAndComplete(tester);

    final reference = _container(tester).read(offlineQueueProvider).single.reference;
    expect(find.text('Sale #$reference'), findsOneWidget);
  });

  testWidgets('the till is cleared so the next customer can be served',
      (tester) async {
    await _pumpTender(tester);
    await _tenderAndComplete(tester);

    final container = _container(tester);
    expect(container.read(posCartProvider), isEmpty);
    expect(container.read(posWalkInPhoneProvider), '');
    expect(container.read(posDiscountProvider), 0);
  });

  testWidgets('a sale queued offline names the cashier who rang it up',
      (tester) async {
    // The queue outlives a sign-out, and anybody may press Sync now, so the
    // audit trail can only name who made the sale if the till kept it with the
    // sale at the moment it was made — not whoever's session replays it.
    await _pumpTender(tester, auth: _SignedInCashier.new);
    await _tenderAndComplete(tester);

    final queued = _container(tester).read(offlineQueueProvider).single;
    expect(queued.rungUpBy, _cashierId);
    expect(queued.capturedAt, isNotNull,
        reason: 'when it was rung up, for the server to judge it then');
    expect(queued.toJson()['rungUpBy'], _cashierId,
        reason: 'kept in the stored queue, which outlives the sign-in');
  });

  testWidgets('an order placed without prices names the cashier too',
      (tester) async {
    // Catalog mode takes no payment but still queues the order when the
    // server cannot be reached; it is recorded by its own path, which must
    // keep who rang it up as the tendered sale does.
    await _pumpTender(tester, auth: _SignedInCashier.new, showPrices: false);
    await tester.tap(find.widgetWithText(FilledButton, 'Place order'));
    await tester.pumpAndSettle();

    final queued = _container(tester).read(offlineQueueProvider).single;
    expect(queued.orderRequest['awaitingPrice'], isTrue);
    expect(queued.total, 0);
    expect(queued.rungUpBy, _cashierId);
    expect(queued.capturedAt, isNotNull);
  });

  testWidgets('a sale made with nobody signed in names nobody', (tester) async {
    // The server then records the entry as rung up by an unknown member of
    // staff; the till never guesses who it was.
    await _pumpTender(tester);
    await _tenderAndComplete(tester);

    expect(_container(tester).read(offlineQueueProvider).single.rungUpBy,
        isNull);
  });

  testWidgets('a sale the server rejects is not queued', (tester) async {
    // Queueing it would tell the cashier the sale went through, and every replay
    // would be refused for the same reason.
    await _pumpTender(tester, rejectStatus: 422);
    await _tenderAndComplete(tester);

    expect(find.text('Saved offline'), findsNothing);
    expect(find.text('Store is closed.'), findsOneWidget);

    final container = _container(tester);
    expect(container.read(offlineQueueProvider), isEmpty);
    expect(container.read(posCartProvider), isNotEmpty,
        reason: 'the sale is still on the till for the cashier to deal with');
  });
}
