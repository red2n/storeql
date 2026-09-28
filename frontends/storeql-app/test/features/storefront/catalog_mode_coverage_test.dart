import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/features/storefront/cart_screen.dart';
import 'package:storeql_app/features/storefront/orders_screen.dart';
import 'package:storeql_app/features/storefront/product_detail_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';
import 'package:storeql_app/features/storefront/storefront_shell.dart';
import 'package:storeql_app/features/storefront/storefront_widgets.dart';

// ── Test doubles ──────────────────────────────────────────────────────────────

/// Signed-in auth without touching FlutterSecureStorage in the constructor.
/// Extends the real notifier so overrideWith type-checks; the synchronous state
/// assignment in the body wins the race against the async _load() (reads null
/// from storage in tests → returns early → signed-in state is preserved).
class _FakeSignedInAuth extends StorefrontAuthNotifier {
  _FakeSignedInAuth() {
    state = const StorefrontAuthState(
        accessToken: 'tok', refreshToken: 'ref', email: 'test@example.com');
  }
}

/// Pre-seeded local order history; skips _persist() so secure storage is not
/// exercised in tests.
class _FakeLocalOrders extends StorefrontOrdersNotifier {
  _FakeLocalOrders(List<StorefrontOrderRecord> records) {
    state = records;
  }

  @override
  Future<void> add(StorefrontOrderRecord record) async {
    state = [record, ...state]; // no _persist() in tests
  }
}

/// Captures every Dio request path and the body of the first POST to the
/// order-svc endpoint. Used by the API flow-guard tests.
class _RecordingInterceptor extends Interceptor {
  final List<String> paths = [];
  Map<String, dynamic>? orderPostPayload;

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    paths.add('${options.method} ${options.path}');

    if (options.method == 'POST' && options.path.contains('order-svc/orders')) {
      orderPostPayload = options.data as Map<String, dynamic>?;
      handler.resolve(Response(
        requestOptions: options,
        statusCode: 201,
        data: {
          'data': {
            'id': '01a090ae-611e-702d-bfe9-b7296be05944',
            'status': 'PENDING',
            'total': 0.0,
            'currency': 'GBP',
          }
        },
      ));
      return;
    }

    // Reject everything else immediately so providers settle without delay.
    handler.reject(DioException(
      requestOptions: options,
      type: DioExceptionType.connectionError,
      message: 'test-only: no real server',
    ));
  }
}

// ── Provider overrides ────────────────────────────────────────────────────────

Override _catalogConfig({String storeName = 'Test Store'}) =>
    storefrontConfigProvider.overrideWith(
        (ref) async => StorefrontConfig(showPrices: false, storeName: storeName));

Override _failingDio() => storefrontDioProvider.overrideWith((ref) {
      final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
      dio.interceptors.add(InterceptorsWrapper(
        onRequest: (opts, handler) => handler.reject(DioException(
          requestOptions: opts,
          type: DioExceptionType.connectionError,
        )),
      ));
      return dio;
    });

Override _recordingDio(_RecordingInterceptor interceptor) =>
    storefrontDioProvider.overrideWith((ref) {
      final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
      dio.interceptors.add(interceptor);
      return dio;
    });

Override _signedIn() =>
    storefrontAuthProvider.overrideWith((ref) => _FakeSignedInAuth());

Override _localOrders(List<StorefrontOrderRecord> orders) =>
    storefrontOrdersProvider.overrideWith((ref) => _FakeLocalOrders(orders));

Override _serverOrders(List<ServerOrderSummary> orders) =>
    serverOrdersProvider.overrideWith((ref) async => orders);

// ── Widget harness ────────────────────────────────────────────────────────────

Widget _scope(Widget child, {List<Override> overrides = const []}) =>
    ProviderScope(
      overrides: overrides,
      child: MaterialApp(home: Scaffold(body: child)),
    );

void _setViewport(WidgetTester tester) {
  tester.view.physicalSize = const Size(800, 1200);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

void _seedCart(WidgetTester tester, List<CartLine> lines) {
  final el = tester.element(find.byType(StorefrontCartScreen).first);
  final notifier = ProviderScope.containerOf(el).read(cartProvider.notifier);
  for (final l in lines) {
    notifier.add(l);
  }
}

CartLine _catalogLine({String variantId = 'v1', String sku = 'SKU-001'}) =>
    CartLine(
      variantId: variantId,
      productName: 'Widget A',
      sku: sku,
      unitPrice: 0,
      currency: '',
    );

// ═════════════════════════════════════════════════════════════════════════════
// Tests
// ═════════════════════════════════════════════════════════════════════════════

void main() {
  setUpAll(initializeDateFormatting);

  // ── P1 / P2: OfferPriceAdd widget (product listing card) ──────────────────

  group('Catalog mode — OfferPriceAdd (product listing)', () {
    testWidgets('in-stock variant shows "In stock" badge — no price',
        (tester) async {
      const product = StoreProduct(id: 'p1', name: 'Widget A');
      await tester.pumpWidget(_scope(
        const OfferPriceAdd(product: product),
        overrides: [
          _catalogConfig(),
          storefrontAvailabilityProvider.overrideWith(
              (ref) async => {'v1': const StockInfo(inStock: true)}),
          productFirstVariantProvider('p1').overrideWith(
              (ref) async => const StoreVariant(id: 'v1', sku: 'SKU-001')),
        ],
      ));
      await tester.pumpAndSettle();

      expect(find.text('In stock'), findsOneWidget);
      // No price digits (e.g. "9.99", "0.00") should appear
      expect(find.textContaining(RegExp(r'\d+\.\d{2}')), findsNothing);
    });

    testWidgets('out-of-stock variant shows "Out of stock" badge — no price',
        (tester) async {
      const product = StoreProduct(id: 'p1', name: 'Widget A');
      await tester.pumpWidget(_scope(
        const OfferPriceAdd(product: product),
        overrides: [
          _catalogConfig(),
          storefrontAvailabilityProvider.overrideWith(
              (ref) async => {'v1': const StockInfo(inStock: false)}),
          productFirstVariantProvider('p1').overrideWith(
              (ref) async => const StoreVariant(id: 'v1', sku: 'SKU-001')),
        ],
      ));
      await tester.pumpAndSettle();

      expect(find.text('Out of stock'), findsOneWidget);
      expect(find.textContaining(RegExp(r'\d+\.\d{2}')), findsNothing);
    });

    testWidgets(
        'tapping add-to-cart stores unitPrice=0 and currency="" on the cart line',
        (tester) async {
      const product = StoreProduct(id: 'p1', name: 'Widget A');
      await tester.pumpWidget(_scope(
        const OfferPriceAdd(product: product),
        overrides: [
          _catalogConfig(),
          storefrontAvailabilityProvider.overrideWith(
              (ref) async => {'v1': const StockInfo(inStock: true)}),
          productFirstVariantProvider('p1').overrideWith(
              (ref) async => const StoreVariant(id: 'v1', sku: 'SKU-001')),
        ],
      ));
      await tester.pumpAndSettle();

      // Add-to-cart button is visible in catalog mode for in-stock items
      expect(find.byIcon(Icons.add_shopping_cart), findsOneWidget);
      await tester.tap(find.byIcon(Icons.add_shopping_cart));
      await tester.pumpAndSettle();

      final el = tester.element(find.byType(OfferPriceAdd).first);
      final cart = ProviderScope.containerOf(el).read(cartProvider);
      expect(cart, hasLength(1));
      expect(cart.first.unitPrice, equals(0.0),
          reason: 'No price is fetched in catalog mode — server prices the order');
      expect(cart.first.currency, equals(''),
          reason: 'Currency must be empty so the server knows to price the line');
    });
  });

  // ── P3: Product detail screen ─────────────────────────────────────────────

  group('Catalog mode — product detail screen variant row', () {
    testWidgets('variant row shows stock badge and Add button — no price text',
        (tester) async {
      const pid = 'prod-1';
      await tester.pumpWidget(_scope(
        const ProductDetailScreen(productId: pid),
        overrides: [
          _catalogConfig(),
          _failingDio(), // prevents any real network calls for other providers
          storefrontProductProvider(pid).overrideWith(
              (ref) async => const StoreProduct(id: pid, name: 'Test Product')),
          storefrontVariantsProvider(pid).overrideWith(
              (ref) async => [const StoreVariant(id: 'v1', sku: 'SKU-001')]),
          storefrontAvailabilityProvider.overrideWith(
              (ref) async => {'v1': const StockInfo(inStock: true)}),
        ],
      ));
      await tester.pumpAndSettle();

      expect(find.text('In stock'), findsOneWidget);
      expect(find.text('Add'), findsOneWidget);
      // Priced path never runs — no price or currency text should appear
      expect(find.textContaining(RegExp(r'\d+\.\d{2}')), findsNothing);
      expect(find.textContaining('GBP'), findsNothing);
      expect(find.textContaining('£'), findsNothing); // money reads £, not GBP
    });
  });

  // ── C1 / C2 / C3: Cart screen — line items and fulfilment banner ──────────

  group('Catalog mode — cart screen line items', () {
    testWidgets('cart line subtitle shows SKU only — no unit price',
        (tester) async {
      _setViewport(tester);
      await tester.pumpWidget(_scope(
        const StorefrontCartScreen(),
        overrides: [_catalogConfig()],
      ));
      _seedCart(tester, [_catalogLine(sku: 'MY-SKU')]);
      await tester.pumpAndSettle();

      // SKU must appear in the subtitle
      expect(find.textContaining('MY-SKU'), findsOneWidget);
      // Unit price must not appear (0.00 or any decimal)
      expect(find.textContaining('0.00'), findsNothing);
      expect(find.textContaining(RegExp(r'\d+\.\d{2}')), findsNothing);
    });

    testWidgets('no trailing price column on any cart line', (tester) async {
      _setViewport(tester);
      await tester.pumpWidget(_scope(
        const StorefrontCartScreen(),
        overrides: [_catalogConfig()],
      ));
      _seedCart(tester, [_catalogLine()]);
      await tester.pumpAndSettle();

      // No currency symbol or code on screen at all
      expect(find.textContaining('GBP'), findsNothing);
      expect(find.textContaining('£'), findsNothing);
    });

    testWidgets(
        'fulfilment banner shows deferred-pricing text — no amount (PICKUP)',
        (tester) async {
      _setViewport(tester);
      await tester.pumpWidget(_scope(
        const StorefrontCartScreen(),
        overrides: [_catalogConfig(storeName: 'Shelf J Store')],
      ));
      _seedCart(tester, [_catalogLine()]);
      await tester.pumpAndSettle();

      // PICKUP is the default fulfilment mode
      expect(
        find.textContaining('price & payment confirmed in store'),
        findsOneWidget,
      );
      expect(find.textContaining('GBP'), findsNothing);
      expect(find.textContaining('£'), findsNothing); // money reads £, not GBP
    });

    testWidgets(
        'fulfilment banner shows deferred-pricing text — no amount (DELIVERY)',
        (tester) async {
      _setViewport(tester);
      await tester.pumpWidget(_scope(
        const StorefrontCartScreen(),
        overrides: [_catalogConfig()],
      ));
      _seedCart(tester, [_catalogLine()]);
      await tester.pumpAndSettle();

      await tester.tap(find.text('Deliver to home'));
      await tester.pumpAndSettle();

      expect(
        find.textContaining('price & payment confirmed on delivery'),
        findsOneWidget,
      );
      expect(find.textContaining('GBP'), findsNothing);
      expect(find.textContaining('£'), findsNothing); // money reads £, not GBP
    });
  });

  // ── S1: Storefront shell — persistent bottom cart bar ────────────────────

  group('Catalog mode — storefront shell cart bar', () {
    testWidgets('cart bar shows item count — no currency total', (tester) async {
      _setViewport(tester);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          _catalogConfig(),
          _failingDio(),
          // Prevent storefrontSuspendedProvider from attempting a Dio call
          storefrontSuspendedProvider.overrideWith((ref) async => false),
        ],
        child: const MaterialApp(
          home: StorefrontShell(
            currentLocation: '/store/products',
            child: SizedBox.expand(),
          ),
        ),
      ));

      // Seed 2 distinct variants so they appear as 2 separate cart lines
      final el = tester.element(find.byType(StorefrontShell).first);
      final notifier = ProviderScope.containerOf(el).read(cartProvider.notifier);
      notifier.add(_catalogLine(variantId: 'v1'));
      notifier.add(_catalogLine(variantId: 'v2'));
      await tester.pumpAndSettle();

      // Cart bar must display "2 items", not a price
      expect(find.textContaining('2 item'), findsOneWidget);
      expect(find.textContaining('GBP'), findsNothing);
      expect(find.textContaining('£'), findsNothing); // money reads £, not GBP
    });
  });

  // ── O1 / O2: Orders screen ────────────────────────────────────────────────

  group('Catalog mode — orders screen', () {
    testWidgets(
        'signed-in server-order tile shows deferred text — no price amount',
        (tester) async {
      _setViewport(tester);
      await tester.pumpWidget(_scope(
        const StorefrontOrdersScreen(),
        overrides: [
          _catalogConfig(),
          _signedIn(),
          _serverOrders([
            ServerOrderSummary(
              id: '01a090ae-611e-702e-9d92-a0191d984b46',
              storeId: 'store-1',
              fulfilmentType: 'PICKUP',
              status: 'PENDING',
              total: 19.99,
              currency: 'GBP',
              placedAt: DateTime.now().subtract(const Duration(hours: 1)),
            ),
          ]),
          // storeNames map — empty list gives no store name lookup errors
          storefrontStoresProvider.overrideWith((ref) async => []),
        ],
      ));
      await tester.pumpAndSettle();

      expect(find.textContaining('19.99'), findsNothing);
      expect(find.textContaining('GBP'), findsNothing);
      expect(find.textContaining('£'), findsNothing); // money reads £, not GBP
      expect(
        find.textContaining(RegExp(r'Price in store|Price on delivery')),
        findsOneWidget,
      );
    });

    testWidgets(
        'guest local-order tile shows deferred text — no price amount',
        (tester) async {
      _setViewport(tester);
      await tester.pumpWidget(_scope(
        const StorefrontOrdersScreen(),
        overrides: [
          _catalogConfig(),
          _localOrders([
            StorefrontOrderRecord(
              orderId: 'local-001-xxxx-yyyy-zzzzzzzz',
              total: 12.0,
              currency: 'GBP',
              itemCount: 3,
              placedAt: DateTime.now().subtract(const Duration(hours: 2)),
              storeName: 'Test Store',
              fulfilmentType: 'PICKUP',
            ),
          ]),
        ],
      ));
      await tester.pumpAndSettle();

      expect(find.textContaining('12.00'), findsNothing);
      expect(find.textContaining('GBP'), findsNothing);
      expect(find.textContaining('£'), findsNothing); // money reads £, not GBP
      expect(
        find.textContaining(RegExp(r'Price in store|Price on delivery')),
        findsOneWidget,
      );
    });
  });

  // ── A1 – A4: API flow guard (checkout POST inspection) ───────────────────

  group('Catalog mode — API flow guard', () {
    // Shared helper: sets up a catalog-mode cart screen with a recording Dio
    // interceptor, seeds the cart, triggers checkout, and returns the interceptor
    // for assertions.
    Future<_RecordingInterceptor> setupAndCheckout(
      WidgetTester tester,
      List<CartLine> lines,
    ) async {
      final interceptor = _RecordingInterceptor();
      _setViewport(tester);
      await tester.pumpWidget(_scope(
        const StorefrontCartScreen(),
        overrides: [
          _catalogConfig(),
          _recordingDio(interceptor),
          // Checkout requires a signed-in customer; empty server history keeps
          // the pending-order guard quiet.
          _signedIn(),
          _serverOrders([]),
          // Prevent StorefrontOrdersNotifier._persist() from calling
          // FlutterSecureStorage.write(), which hangs in headless tests.
          _localOrders([]),
        ],
      ));
      _seedCart(tester, lines);
      await tester.pumpAndSettle();

      // Pickup checkout requires a contact phone before anything else fires.
      await tester.enterText(
          find.widgetWithText(TextField, 'Contact phone *'), '07700900000');
      await tester.pump();

      // New checkout flow: Review order → review sheet → Place order.
      await tester.ensureVisible(find.text('Review order'));
      await tester.tap(find.text('Review order'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Place order'));
      await tester.pumpAndSettle();

      return interceptor;
    }

    testWidgets('checkout POST body sends unitPrice=0 for every cart item',
        (tester) async {
      final interceptor = await setupAndCheckout(tester, [
        _catalogLine(variantId: 'v1', sku: 'SKU-001'),
        _catalogLine(variantId: 'v2', sku: 'SKU-002'),
        _catalogLine(variantId: 'v3', sku: 'SKU-003'),
      ]);

      expect(interceptor.orderPostPayload, isNotNull,
          reason: 'Order POST was not captured — checkout may not have fired');
      final items = interceptor.orderPostPayload!['items'] as List;
      expect(items, hasLength(3));
      for (final raw in items) {
        final item = raw as Map<String, dynamic>;
        expect(item['unitPrice'], equals(0),
            reason:
                'unitPrice must be 0 in catalog mode — the server prices the order');
      }
    });

    testWidgets(
        'checkout POST leaves the currency out when cart lines carry none (SJ-D53)',
        (tester) async {
      // Catalog mode: CartLine.currency is '' (no price-resolve call is ever made).
      // The currency is omitted and order-svc stamps the tenant's own; the app
      // used to fill in pounds for every catalog-mode order.
      final interceptor =
          await setupAndCheckout(tester, [_catalogLine()]);

      expect(interceptor.orderPostPayload, isNotNull);
      expect(
        interceptor.orderPostPayload!.containsKey('currency'),
        isFalse,
        reason: 'an unknown currency is left to the server, never guessed',
      );
    });

    testWidgets(
        'payment-svc endpoint is never called in catalog mode',
        (tester) async {
      final interceptor =
          await setupAndCheckout(tester, [_catalogLine()]);

      final paymentCalls =
          interceptor.paths.where((p) => p.contains('payment-svc')).toList();
      expect(
        paymentCalls,
        isEmpty,
        reason:
            'Catalog mode defers payment to pickup/delivery — payment-svc must never be called',
      );
    });

    testWidgets(
        'success dialog contains no price or currency text after catalog-mode order',
        (tester) async {
      await setupAndCheckout(tester, [_catalogLine()]);

      // The order-placed dialog must be visible
      expect(find.byType(AlertDialog), findsOneWidget);

      // No price or currency anywhere in the dialog
      expect(find.textContaining('GBP'), findsNothing);
      expect(find.textContaining('£'), findsNothing);
      expect(find.textContaining(RegExp(r'\d+\.\d{2}')), findsNothing);

      // Title confirms payment was NOT taken (not "Payment successful")
      expect(find.text('Order placed'), findsOneWidget);

      // Deferred-payment copy must be visible
      expect(
        find.textContaining('Price & payment will be confirmed in store'),
        findsOneWidget,
      );
    });
  });
}
