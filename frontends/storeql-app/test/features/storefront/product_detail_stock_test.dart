import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/storefront/product_detail_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';

// ---------------------------------------------------------------------------
// The priced product page's Add button: an out-of-stock option is disabled
// and says so, the same as the product card — never an always-on button that
// lets a shopper add something the store cannot fill. A variant the supplier
// ships per order (StockInfo.dropship) has no shelf to be out of, so it is
// never held back by this.
// ---------------------------------------------------------------------------

const _pid = 'prod-1';
const _price =
    ResolvedPrice(unitPrice: 2.08, totalWithVat: 2.50, currency: 'GBP');

/// Rejects every request — the price and availability of the one variant
/// under test are named explicitly below; nothing else this page reaches for
/// (recall notices, reviews, similar products) should make a real call.
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

List<Override> _overrides(StockInfo stock) => [
      _failingDio(),
      storefrontConfigProvider.overrideWith((ref) async =>
          const StorefrontConfig(showPrices: true, storeName: 'Test Store')),
      storefrontProductProvider(_pid).overrideWith(
          (ref) async => const StoreProduct(id: _pid, name: 'Test Product')),
      storefrontVariantsProvider(_pid).overrideWith(
          (ref) async => [const StoreVariant(id: 'v1', sku: 'SKU-001')]),
      storefrontAvailabilityProvider
          .overrideWith((ref) async => {'v1': stock}),
      variantPriceProvider('v1').overrideWith((ref) async => _price),
    ];

Future<void> _pump(WidgetTester tester, StockInfo stock) async {
  await tester.pumpWidget(ProviderScope(
    overrides: _overrides(stock),
    child: const MaterialApp(
        home: Scaffold(body: ProductDetailScreen(productId: _pid))),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('an in-stock option keeps Add enabled', (tester) async {
    await _pump(tester, const StockInfo(inStock: true));

    final button =
        tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Add'));
    expect(button.onPressed, isNotNull);
    expect(find.text('Out of stock'), findsNothing);
  });

  testWidgets('an out-of-stock option disables Add and says so', (
    tester,
  ) async {
    await _pump(tester, const StockInfo(inStock: false));

    expect(find.text('Add'), findsNothing);
    final button = tester.widget<FilledButton>(
        find.widgetWithText(FilledButton, 'Out of stock'));
    expect(button.onPressed, isNull,
        reason: 'consistent with the product card: never addable while out '
            'of stock');
  });

  testWidgets(
      'a dropshipped option keeps Add enabled even reported out of stock',
      (tester) async {
    await _pump(
        tester, const StockInfo(inStock: false, dropship: true));

    final button =
        tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Add'));
    expect(button.onPressed, isNotNull,
        reason: 'the supplier ships it per order — there is no shelf to be '
            'out of');
    expect(find.text('Out of stock'), findsNothing);
  });
}
