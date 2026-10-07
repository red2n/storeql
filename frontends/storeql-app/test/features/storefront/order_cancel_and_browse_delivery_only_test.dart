import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/features/storefront/orders_screen.dart';
import 'package:storeql_app/features/storefront/product_list_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Catalogue: online/unpaid-order-sweeper-and-cancellation gap 1 (a shopper
// cancels their own pending order) and online/storefront-browsing gap 1 (a
// delivery-only shop says so while browsing).
// ---------------------------------------------------------------------------

const _pending = '01a0d930-0000-7000-8000-0000000000a1';
const _confirmed = '01a0d930-0000-7000-8000-0000000000a2';

class _SignedIn extends StorefrontAuthNotifier {
  _SignedIn() {
    state = const StorefrontAuthState(
        accessToken: 'tok', refreshToken: 'ref', email: 'sam@example.com');
  }
}

/// Serves the order list, records every POST, and answers the cancel as told.
class _Orders implements HttpClientAdapter {
  final List<RequestOptions> posts = [];
  final int cancelStatus;
  final String cancelBody;
  bool cancelled = false;
  _Orders({this.cancelStatus = 200, this.cancelBody = '{"data":{}}'});

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    if (o.method == 'POST') {
      posts.add(o);
      if (cancelStatus < 300) cancelled = true;
      return jsonResponse(cancelBody, cancelStatus);
    }
    if (o.path.endsWith('/orders/mine')) {
      return jsonResponse('{"data":['
          '{"id":"$_pending","storeId":"s","fulfilmentType":"PICKUP","status":"${cancelled ? 'CANCELLED' : 'PENDING'}","total":5,"currency":"GBP","createdAt":"2026-09-25T09:00:00Z"},'
          '{"id":"$_confirmed","storeId":"s","fulfilmentType":"PICKUP","status":"CONFIRMED","total":6,"currency":"GBP","createdAt":"2026-09-25T09:05:00Z"}'
          ']}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Orders> _open(WidgetTester tester, _Orders server) async {
  tester.view.physicalSize = const Size(800, 1400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
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

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('only a pending order offers Cancel order', (tester) async {
    await _open(tester, _Orders());
    expect(find.byKey(const Key('cancel-order-$_pending')), findsOneWidget);
    expect(find.byKey(const Key('cancel-order-$_confirmed')), findsNothing);
    expect(find.text('Cancel order'), findsOneWidget);
  });

  testWidgets('keeping the order sends nothing', (tester) async {
    final server = await _open(tester, _Orders());
    await tester.tap(find.byKey(const Key('cancel-order-$_pending')));
    await tester.pumpAndSettle();
    expect(find.text('Cancel this order?'), findsOneWidget);
    await tester.tap(find.byKey(const Key('cancel-order-keep')));
    await tester.pumpAndSettle();
    expect(server.posts, isEmpty);
    expect(find.byKey(const Key('cancel-order-$_pending')), findsOneWidget);
  });

  testWidgets('confirming cancels through order-svc and the order reads cancelled',
      (tester) async {
    final server = await _open(tester, _Orders());
    await tester.tap(find.byKey(const Key('cancel-order-$_pending')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('cancel-order-confirm')));
    await tester.pumpAndSettle();
    expect(server.posts, hasLength(1));
    expect(server.posts.single.path, endsWith('/orders/$_pending/cancel'));
    expect(find.text('Order cancelled.'), findsOneWidget);
    expect(find.text('Cancelled'), findsOneWidget);
    expect(find.byKey(const Key('cancel-order-$_pending')), findsNothing);
  });

  testWidgets('a refusal is shown in words and the order stays', (tester) async {
    final server = await _open(
        tester,
        _Orders(
            cancelStatus: 409,
            cancelBody:
                '{"error":{"code":"ORDER_NOT_CANCELLABLE","message":"This order can no longer be cancelled."}}'));
    await tester.tap(find.byKey(const Key('cancel-order-$_pending')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('cancel-order-confirm')));
    await tester.pumpAndSettle();
    expect(server.posts, hasLength(1));
    expect(find.textContaining('can no longer be cancelled'), findsOneWidget);
    expect(find.byKey(const Key('cancel-order-$_pending')), findsOneWidget);
  });

  Future<void> browse(WidgetTester tester, bool pickupOffered) async {
    tester.view.physicalSize = const Size(1280, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        storefrontTenantProvider.overrideWith((ref) => 't-1'),
        storefrontStoresProvider.overrideWith((ref) async =>
            const [StoreSummary(id: 's-1', name: 'Hub', showPrices: true)]),
        storefrontConfigProvider.overrideWith((ref) async => StorefrontConfig(
            showPrices: true, storeName: 'Hub', pickupOffered: pickupOffered)),
        storefrontPromotionsProvider.overrideWith((ref) async => const []),
        storefrontCategoriesProvider.overrideWith((ref) async => const []),
        storefrontAvailabilityProvider.overrideWith((ref) async => const {}),
        storefrontProductsProvider((query: '', categoryId: null))
            .overrideWith((ref) async => const []),
      ],
      child: const MaterialApp(home: Scaffold(body: ProductListScreen())),
    ));
    await tester.pump(const Duration(milliseconds: 600));
    await tester.pump();
    await tester.pump();
  }

  testWidgets('browsing a dark store says it delivers only', (tester) async {
    await browse(tester, false);
    expect(find.byKey(const Key('browse-delivery-only')), findsOneWidget);
    expect(find.textContaining('delivers only'), findsOneWidget);
  });

  testWidgets('browsing a shop that offers collection shows no such notice',
      (tester) async {
    await browse(tester, true);
    expect(find.byKey(const Key('browse-delivery-only')), findsNothing);
  });
}
