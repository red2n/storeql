import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/features/storefront/cart_screen.dart';
import 'package:storeql_app/features/storefront/orders_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';

// ---------------------------------------------------------------------------
// Order orchestration on the storefront: a delivery the shop serving the
// postcode cannot fill alone comes back in parts from several shops. The
// shopper sees the parts before any money moves, pays once for the whole
// checkout, and the history says which delivery each part belongs to.
// ---------------------------------------------------------------------------

const _leeds = '01a0d910-611e-703c-a378-a4972ea461e1';
const _york = '01a0d910-611e-703c-a378-a4972ea461e2';
const _p1 = '01a0d910-611e-702d-bfe9-b7296be05941';
const _p2 = '01a0d910-611e-702d-bfe9-b7296be05942';
const _group = '01a0d910-611e-702d-bfe9-b7296be059a1';

class _SignedIn extends StorefrontAuthNotifier {
  _SignedIn() {
    state = const StorefrontAuthState(
        accessToken: 'tok', refreshToken: 'ref', email: 'sam@example.com');
  }
}

class _LocalOrders extends StorefrontOrdersNotifier {
  _LocalOrders() {
    state = const [];
  }

  @override
  Future<void> add(StorefrontOrderRecord record) async {
    state = [record, ...state];
  }
}

/// order-svc and payment-svc as the checkout meets them.
class _Server extends Interceptor {
  final List<RequestOptions> requests = [];
  final bool split;
  final bool unfulfillable;
  _Server({this.split = true, this.unfulfillable = false});

  @override
  void onRequest(RequestOptions o, RequestInterceptorHandler h) {
    requests.add(o);
    if (o.method == 'POST' && o.path.endsWith('/orders')) {
      if (unfulfillable) {
        h.reject(DioException(
          requestOptions: o,
          response: Response(requestOptions: o, statusCode: 409, data: {
            'code': 'ORDER_UNFULFILLABLE',
            'detail': 'no combination of the business\'s shops holds everything',
          }),
          type: DioExceptionType.badResponse,
        ));
        return;
      }
      h.resolve(Response(requestOptions: o, statusCode: 201, data: {
        'data': {
          'id': _p1,
          'status': 'PENDING',
          'storeId': _leeds,
          'total': split ? 3.37 : 4.50,
          'currency': 'GBP',
          if (split)
            'group': {
              'id': _group,
              'total': 5.05,
              'currency': 'GBP',
              'parts': [
                {'orderId': _p1, 'storeId': _leeds, 'status': 'PENDING', 'total': 3.37, 'units': 2},
                {'orderId': _p2, 'storeId': _york, 'status': 'PENDING', 'total': 1.68, 'units': 3},
              ],
            },
        }
      }));
      return;
    }
    if (o.method == 'POST' && o.path.endsWith('/payments/online')) {
      h.resolve(Response(requestOptions: o, statusCode: 201, data: {'data': {}}));
      return;
    }
    h.reject(DioException(requestOptions: o, type: DioExceptionType.connectionError));
  }

  List<RequestOptions> get payments =>
      requests.where((r) => r.path.endsWith('/payments/online')).toList();
}

/// A delivery checkout of apples and pears, paid by card, up to the review sheet's pay button.
Future<_Server> _checkout(WidgetTester tester, _Server server) async {
  tester.view.physicalSize = const Size(800, 1600);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      storefrontConfigProvider.overrideWith(
          (ref) async => const StorefrontConfig(showPrices: true, storeName: 'Leeds')),
      storefrontDioProvider.overrideWith((ref) {
        final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
        dio.interceptors.add(server);
        return dio;
      }),
      storefrontAuthProvider.overrideWith((ref) => _SignedIn()),
      serverOrdersProvider.overrideWith((ref) async => const []),
      storefrontOrdersProvider.overrideWith((ref) => _LocalOrders()),
      storefrontStoresProvider.overrideWith((ref) async => [
            const StoreSummary(id: _leeds, name: 'Leeds', showPrices: true),
            const StoreSummary(id: _york, name: 'York', showPrices: true),
          ]),
    ],
    child: const MaterialApp(home: Scaffold(body: StorefrontCartScreen())),
  ));
  final el = tester.element(find.byType(StorefrontCartScreen).first);
  final cart = ProviderScope.containerOf(el).read(cartProvider.notifier);
  cart.add(CartLine(
      variantId: 'apples', productName: 'Apples', sku: 'APL', unitPrice: 1.5, currency: 'GBP'));
  cart.add(CartLine(
      variantId: 'pears', productName: 'Pears', sku: 'PER', unitPrice: 0.5, currency: 'GBP'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Deliver to home'));
  await tester.pumpAndSettle();
  Future<void> type(String label, String text) async {
    await tester.enterText(find.widgetWithText(TextFormField, label), text);
  }

  await type('Address line 1', '1 Park Row');
  await type('City', 'Leeds');
  await type('Postal code', 'LS1 5AB');
  await type('Recipient name', 'Sam Shopper');
  await type('Recipient phone', '07700900123');
  await tester.pump();
  await tester.ensureVisible(find.text('Review order'));
  await tester.tap(find.text('Review order'));
  await tester.pumpAndSettle();
  // Money reads with the symbol, not the code.
  await tester.tap(find.textContaining('Pay £').last);
  await tester.pumpAndSettle();
  return server;
}

void main() {
  setUpAll(initializeDateFormatting);
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));

  testWidgets('the parts are shown before payment and the checkout is paid once',
      (tester) async {
    final server = await _checkout(tester, _Server());
    expect(find.text('Your order comes in 2 parts'), findsOneWidget);
    expect(find.byKey(const Key('split-part-$_p1')), findsOneWidget);
    expect(find.byKey(const Key('split-part-$_p2')), findsOneWidget);
    expect(find.text('York'), findsOneWidget);
    expect(find.text('£1.68'), findsOneWidget);
    expect(server.payments, isEmpty, reason: 'nothing is charged before the shopper agrees');

    await tester.tap(find.byKey(const Key('split-pay')));
    await tester.pumpAndSettle();
    expect(server.payments, hasLength(1));
    final body = server.payments.single.data as Map<String, dynamic>;
    expect(body['groupId'], _group);
    expect(body['amount'], 5.05);
    expect(body.containsKey('orderId'), isFalse);
    expect(find.text('Arrives in 2 parts: 2 items from Leeds, 3 from York'), findsOneWidget);
    expect(find.text('£5.05 paid'), findsOneWidget);
  });

  testWidgets('declining the parts charges nothing', (tester) async {
    final server = await _checkout(tester, _Server());
    await tester.tap(find.text('Not now'));
    await tester.pumpAndSettle();
    expect(server.payments, isEmpty);
    expect(find.textContaining('Nothing was charged'), findsOneWidget);
  });

  testWidgets('an order from one shop is paid as before', (tester) async {
    final server = await _checkout(tester, _Server(split: false));
    expect(find.text('Your order comes in 2 parts'), findsNothing);
    final body = server.payments.single.data as Map<String, dynamic>;
    expect(body['orderId'], _p1);
    expect(body.containsKey('groupId'), isFalse);
  });

  testWidgets('an order no mix of shops can fill says so', (tester) async {
    await _checkout(tester, _Server(unfulfillable: true));
    expect(find.textContaining('our shops can\'t gather everything'), findsOneWidget);
  });

  testWidgets('the history says which delivery a part belongs to', (tester) async {
    tester.view.physicalSize = const Size(800, 1200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    ServerOrderSummary part(String id, String store) => ServerOrderSummary(
          id: id,
          storeId: store,
          fulfilmentType: 'DELIVERY',
          status: 'CONFIRMED',
          total: 1.0,
          currency: 'GBP',
          placedAt: DateTime.now(),
          groupId: _group,
        );
    await tester.pumpWidget(ProviderScope(
      overrides: [
        storefrontConfigProvider.overrideWith(
            (ref) async => const StorefrontConfig(showPrices: true, storeName: 'Leeds')),
        storefrontAuthProvider.overrideWith((ref) => _SignedIn()),
        serverOrdersProvider.overrideWith((ref) async => [part(_p1, _leeds), part(_p2, _york)]),
        storefrontStoresProvider.overrideWith((ref) async => [
              const StoreSummary(id: _leeds, name: 'Leeds', showPrices: true),
              const StoreSummary(id: _york, name: 'York', showPrices: true),
            ]),
        myRecallNoticesProvider.overrideWith((ref) async => const []),
      ],
      child: const MaterialApp(home: Scaffold(body: StorefrontOrdersScreen())),
    ));
    await tester.pumpAndSettle();
    expect(find.textContaining('Part of a delivery in 2 parts · from York'), findsOneWidget);
    expect(find.textContaining('Part of a delivery in 2 parts · from Leeds'), findsOneWidget);
  });
}
