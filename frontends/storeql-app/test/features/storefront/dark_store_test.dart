import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/storefront/cart_screen.dart';
import 'package:storeql_app/features/storefront/orders_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Ship-from-store and dark-store picking on the shopper's side: a dark store
// sells delivery-only, so the cart offers no collection there; the order
// history says where a picked order is — ready to collect, packed, on its way
// with the carrier, collected; and the till never offers a dark store.
// ---------------------------------------------------------------------------

const _dark = '01a0d930-0000-7000-8000-0000000000e2';
const _leeds = '01a0d930-0000-7000-8000-0000000000e1';

class _SignedIn extends StorefrontAuthNotifier {
  _SignedIn() {
    state = const StorefrontAuthState(
        accessToken: 'tok', refreshToken: 'ref', email: 'sam@example.com');
  }
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('a dark store offers no collection: the checkout is a delivery', (tester) async {
    tester.view.physicalSize = const Size(800, 1400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        storefrontConfigProvider.overrideWith((ref) async => const StorefrontConfig(
            showPrices: true, storeName: 'Online hub', pickupOffered: false)),
      ],
      child: const MaterialApp(home: Scaffold(body: StorefrontCartScreen())),
    ));
    final el = tester.element(find.byType(StorefrontCartScreen).first);
    ProviderScope.containerOf(el).read(cartProvider.notifier).add(CartLine(
        variantId: 'v1', productName: 'Apples', sku: 'APL', unitPrice: 1.5, currency: 'GBP'));
    await tester.pumpAndSettle();
    expect(find.text('Collect from store'), findsNothing);
    expect(find.byKey(const Key('delivery-only')), findsOneWidget);
    expect(find.text('Address line 1'), findsOneWidget, reason: 'the delivery form is open');
  });

  test('the storefront reads a store\'s type and whether it offers collection', () {
    final dark = StoreSummary.fromJson({
      'storeId': _dark,
      'storeName': 'Online hub',
      'showPrices': true,
      'type': 'DARK_STORE',
      'pickupOffered': false,
    });
    expect(dark.pickupOffered, isFalse);
    expect(dark.type, 'DARK_STORE');
    final shop = StoreSummary.fromJson({'storeId': _leeds, 'storeName': 'Leeds', 'showPrices': true});
    expect(shop.pickupOffered, isTrue, reason: 'an answer from before the flag existed is a shop');
  });

  test('a picked order reads where it is, in the shopper\'s words', () {
    ServerOrderSummary order(String type, String status, [Map<String, dynamic>? handover]) =>
        ServerOrderSummary.fromJson({
          'id': 'o',
          'storeId': _leeds,
          'fulfilmentType': type,
          'status': status,
          'total': 5,
          'currency': 'GBP',
          'createdAt': '2026-09-25T09:00:00Z',
          'handover': ?handover,
        });
    expect(order('PICKUP', 'FULFILLED').stageLabel, 'Ready to collect');
    expect(order('DELIVERY', 'FULFILLED').stageLabel, 'Packed');
    expect(order('DELIVERY', 'FULFILLED', {'kind': 'DISPATCHED', 'carrier': 'DPD', 'reference': '1Z1'}).stageLabel,
        'On its way · DPD 1Z1');
    expect(order('DELIVERY', 'FULFILLED', {'kind': 'DISPATCHED', 'carrier': 'Evri'}).stageLabel, 'On its way · Evri');
    expect(order('PICKUP', 'FULFILLED', {'kind': 'COLLECTED'}).stageLabel, 'Collected');
    expect(order('PICKUP', 'CONFIRMED').stageLabel, 'CONFIRMED');
  });

  testWidgets('the history shows the stage of each picked order', (tester) async {
    tester.view.physicalSize = const Size(800, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    ServerOrderSummary order(String id, String type, {String? kind, String? carrier}) =>
        ServerOrderSummary(
          id: id,
          storeId: _leeds,
          fulfilmentType: type,
          status: 'FULFILLED',
          total: 5,
          currency: 'GBP',
          placedAt: DateTime.now(),
          handoverKind: kind,
          handoverCarrier: carrier,
        );
    await tester.pumpWidget(ProviderScope(
      overrides: [
        storefrontConfigProvider.overrideWith(
            (ref) async => const StorefrontConfig(showPrices: true, storeName: 'Leeds')),
        storefrontAuthProvider.overrideWith((ref) => _SignedIn()),
        serverOrdersProvider.overrideWith((ref) async => [
              order('01a0d930-0000-7000-8000-0000000000f1', 'PICKUP'),
              order('01a0d930-0000-7000-8000-0000000000f2', 'DELIVERY', kind: 'DISPATCHED', carrier: 'DPD'),
              order('01a0d930-0000-7000-8000-0000000000f3', 'PICKUP', kind: 'COLLECTED'),
            ]),
        storefrontStoresProvider.overrideWith((ref) async => const [
              StoreSummary(id: _leeds, name: 'Leeds', showPrices: true),
            ]),
        myRecallNoticesProvider.overrideWith((ref) async => const []),
      ],
      child: const MaterialApp(home: Scaffold(body: StorefrontOrdersScreen())),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Ready to collect'), findsOneWidget);
    expect(find.text('On its way · DPD'), findsOneWidget);
    expect(find.text('Collected'), findsOneWidget);
  });

  test('the till never offers a dark store', () async {
    final dio = Dio(BaseOptions(baseUrl: 'http://test'))
      ..httpClientAdapter = _StoresAdapter();
    final container = ProviderContainer(overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
    ]);
    addTearDown(container.dispose);
    final stores = await container.read(posStoresProvider.future);
    expect(stores.map((s) => s.id), [_leeds]);
    expect(stores.single.type, 'STORE');
  });
}

class _StoresAdapter implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async =>
      jsonResponse('{"data":['
          '{"storeId":"$_leeds","storeName":"Leeds","status":"ACTIVE","showPrices":true,"type":"STORE","pickupOffered":true},'
          '{"storeId":"$_dark","storeName":"Online hub","status":"ACTIVE","showPrices":true,"type":"DARK_STORE","pickupOffered":false}'
          ']}');
}
