import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/features/storefront/cart_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';

// ---------------------------------------------------------------------------
// Substitutions for out-of-stock online lines, at checkout: the shopper's
// choice is on unless they turn it off, and it rides the order they place.
// ---------------------------------------------------------------------------

const _leeds = '01a0d950-611e-703c-a378-a4972ea461e1';
const _order = '01a0d950-611e-702d-bfe9-b7296be05941';

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

/// order-svc and payment-svc as the checkout meets them: one shop, paid at once.
class _Server extends Interceptor {
  final List<RequestOptions> requests = [];

  @override
  void onRequest(RequestOptions o, RequestInterceptorHandler h) {
    requests.add(o);
    if (o.method == 'POST' && o.path.endsWith('/orders')) {
      h.resolve(Response(requestOptions: o, statusCode: 201, data: {
        'data': {'id': _order, 'status': 'PENDING', 'storeId': _leeds, 'total': 1.5, 'currency': 'GBP'}
      }));
      return;
    }
    if (o.method == 'POST' && o.path.endsWith('/payments/online')) {
      h.resolve(Response(requestOptions: o, statusCode: 201, data: {'data': {}}));
      return;
    }
    h.reject(DioException(requestOptions: o, type: DioExceptionType.connectionError));
  }

  Map<String, dynamic> get placed =>
      requests.singleWhere((r) => r.method == 'POST' && r.path.endsWith('/orders')).data
          as Map<String, dynamic>;
}

/// A collection checkout of apples, paid by card, through to the placed order.
Future<_Server> _checkout(WidgetTester tester, {required bool allow}) async {
  tester.view.physicalSize = const Size(800, 1600);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final server = _Server();
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
          ]),
    ],
    child: const MaterialApp(home: Scaffold(body: StorefrontCartScreen())),
  ));
  final el = tester.element(find.byType(StorefrontCartScreen).first);
  ProviderScope.containerOf(el).read(cartProvider.notifier).add(CartLine(
      variantId: 'apples', productName: 'Apples', sku: 'APL', unitPrice: 1.5, currency: 'GBP'));
  await tester.pumpAndSettle();
  final toggle = find.byKey(const Key('allow-substitutions'));
  expect(tester.widget<SwitchListTile>(toggle).value, isTrue, reason: 'on unless turned off');
  if (!allow) {
    await tester.ensureVisible(toggle);
    await tester.tap(toggle);
    await tester.pumpAndSettle();
    expect(tester.widget<SwitchListTile>(toggle).value, isFalse);
  }
  await tester.enterText(find.widgetWithText(TextField, 'Contact phone *'), '07700900123');
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

  testWidgets('substitutions are allowed unless the shopper turns them off', (tester) async {
    final server = await _checkout(tester, allow: true);
    expect(server.placed['allowSubstitutions'], isTrue);
  });

  testWidgets('a shopper who wants no substitutes says so on the order', (tester) async {
    final server = await _checkout(tester, allow: false);
    expect(server.placed['allowSubstitutions'], isFalse);
  });
}
