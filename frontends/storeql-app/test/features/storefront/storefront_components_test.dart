import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/constants.dart';
import 'package:storeql_app/core/spacing.dart';
import 'package:storeql_app/core/theme.dart';
import 'package:storeql_app/features/storefront/cart_line.dart';
import 'package:storeql_app/features/storefront/cart_screen.dart';
import 'package:storeql_app/features/storefront/order_summary.dart';
import 'package:storeql_app/features/storefront/orders_screen.dart';
import 'package:storeql_app/features/storefront/product_card.dart';
import 'package:storeql_app/features/storefront/product_list_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';
import 'package:storeql_app/features/storefront/storefront_widgets.dart';
import 'package:storeql_app/shared/widgets/error_view.dart';
import 'package:storeql_app/shared/widgets/skeleton.dart';
import 'package:storeql_app/shared/widgets/status_badge.dart';

// ---------------------------------------------------------------------------
// The storefront's shared pieces from the design system — Skeleton,
// ProductCard, CartLine and OrderSummary — as the shop, the cart and the order
// history use them, and open issues on those screens: the category chips
// keep their height at 200% text, a mouse can reload the order history (a
// guest's, already current, offers no button), and the history's heading
// starts at the cards' edge.
// ---------------------------------------------------------------------------

const _butter = StoreProduct(id: 'p-1', name: 'Crunchy peanut butter');
const _oats = StoreProduct(id: 'p-2', name: 'Rolled oats');
const _butterJar = StoreVariant(id: 'v-1', sku: 'PB-1');
const _oatsBag = StoreVariant(id: 'v-2', sku: 'OAT-1');
const _price =
    ResolvedPrice(unitPrice: 2.08, totalWithVat: 2.50, currency: 'GBP');
const _leeds = '01a0d950-611e-703c-a378-a4972ea461e1';

List<Override> _shop({Future<List<StoreProduct>>? products}) => [
      storefrontTenantProvider.overrideWith((ref) => 't-1'),
      storefrontStoresProvider.overrideWith((ref) async => const [
            StoreSummary(id: 's-1', name: 'High Street', showPrices: true)
          ]),
      storefrontConfigProvider.overrideWith((ref) async =>
          const StorefrontConfig(showPrices: true, storeName: 'Corner Shop')),
      storefrontAvailabilityProvider.overrideWith((ref) async => const {
        'v-1': StockInfo(inStock: true),
        'v-2': StockInfo(inStock: true),
      }),
      storefrontPromotionsProvider.overrideWith((ref) async => const []),
      storefrontCategoriesProvider.overrideWith((ref) async => const [
            StoreCategory(id: 'c-1', name: 'Pantry'),
            StoreCategory(id: 'c-2', name: 'Breakfast'),
          ]),
      storefrontProductsProvider((query: '', categoryId: null)).overrideWith(
          (ref) => products ?? Future.value(const [_butter, _oats])),
      productCardOfferProvider('p-1')
          .overrideWith((ref) async => const CardOffer(_butterJar, _price)),
      productCardOfferProvider('p-2')
          .overrideWith((ref) async => const CardOffer(_oatsBag, _price)),
      productFirstVariantProvider('p-1')
          .overrideWith((ref) async => _butterJar),
      productFirstVariantProvider('p-2').overrideWith((ref) async => _oatsBag),
    ];

Future<void> _pump(
  WidgetTester tester,
  Widget screen,
  List<Override> overrides, {
  Size size = const Size(1280, 900),
  double textScale = 1,
  bool reduceMotion = false,
  ThemeData? theme,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: overrides,
    child: MaterialApp(
      theme: theme ?? AppTheme.light,
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(
          textScaler: TextScaler.linear(textScale),
          disableAnimations: reduceMotion,
        ),
        child: child!,
      ),
      home: Scaffold(body: screen),
    ),
  ));
  await tester.pump();
}

/// The shop after its first frame, its store pick and its preferences check.
Future<void> _pumpShop(WidgetTester tester, List<Override> overrides,
    {Size size = const Size(1280, 900),
    double textScale = 1,
    ThemeData? theme}) async {
  await _pump(tester, const ProductListScreen(), overrides,
      size: size, textScale: textScale, theme: theme);
  await tester.pump(const Duration(milliseconds: 600));
  await tester.pump();
  await tester.pump();
}

/// Unmounts the tree so the offers timer and any snack bar end with the test.
Future<void> _leave(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox());
  await tester.pump(const Duration(seconds: 1));
}

class _SignedIn extends StorefrontAuthNotifier {
  _SignedIn() {
    state = const StorefrontAuthState(
        accessToken: 'tok', refreshToken: 'ref', email: 'sam@example.com');
  }
}

ServerOrderSummary _order(String status) => ServerOrderSummary(
      id: '01a0d950-611e-702d-bfe9-b7296be05941',
      storeId: _leeds,
      fulfilmentType: 'PICKUP',
      status: status,
      total: 7.5,
      currency: 'GBP',
      placedAt: DateTime.utc(2026, 9, 20, 10, 30),
    );

List<Override> _history(FutureOr<List<ServerOrderSummary>?> Function() load) => [
      storefrontConfigProvider.overrideWith((ref) async =>
          const StorefrontConfig(showPrices: true, storeName: 'Leeds')),
      storefrontAuthProvider.overrideWith((ref) => _SignedIn()),
      serverOrdersProvider.overrideWith((ref) async => load()),
      storefrontStoresProvider.overrideWith((ref) async => const [
            StoreSummary(id: _leeds, name: 'Leeds', showPrices: true),
          ]),
      myRecallNoticesProvider.overrideWith((ref) async => const []),
    ];

/// A guest's history: the orders placed on this device, nothing more.
class _DeviceOrders extends StorefrontOrdersNotifier {
  _DeviceOrders(List<StorefrontOrderRecord> records) {
    state = records;
  }
}

List<Override> _guestHistory() => [
      storefrontConfigProvider.overrideWith((ref) async =>
          const StorefrontConfig(showPrices: true, storeName: 'Leeds')),
      storefrontOrdersProvider.overrideWith((ref) => _DeviceOrders([
            StorefrontOrderRecord(
              orderId: '01a0d950-611e-702d-bfe9-b7296be05941',
              total: 7.5,
              currency: 'GBP',
              itemCount: 2,
              placedAt: DateTime.utc(2026, 9, 20, 10, 30),
              storeName: 'Leeds',
            ),
          ])),
    ];

/// Where the order cards' column starts: the page gutter, or on a window
/// wider than the 640 reading width and its gutters, where that width,
/// centred, begins.
double _columnStart(double width) {
  final gutter = width < AppBreakpoints.medium ? AppSpacing.lg : AppSpacing.xl;
  return math.max(gutter, (width - AppBreakpoints.formMaxWidth) / 2);
}

CartLine _line(String variantId, String name,
        {double price = 2.5, String? productId}) =>
    CartLine(
      variantId: variantId,
      productId: productId,
      productName: name,
      sku: 'SKU-$variantId',
      unitPrice: price,
      currency: 'GBP',
    );

List<Override> _cart(List<CartLine> Function() lines, {bool showPrices = true}) => [
      storefrontConfigProvider.overrideWith((ref) async =>
          StorefrontConfig(showPrices: showPrices, storeName: 'Leeds')),
      cartProvider.overrideWith((ref) {
        final notifier = CartNotifier();
        lines().forEach(notifier.add);
        return notifier;
      }),
    ];

void main() {
  setUpAll(initializeDateFormatting);
  setUp(() => FlutterSecureStorage.setMockInitialValues({
        StorageKeys.sfGenderAsked: 'true',
        StorageKeys.sfPrefsAsked: 'true',
      }));

  group('Skeleton', () {
    Widget blocks() => const Skeleton(
          label: 'Loading products',
          child: Column(children: [
            SkeletonBlock(width: 120, height: 120),
            SkeletonLine(widthFactor: 0.6),
          ]),
        );

    testWidgets('shimmers, holds still for less motion, and reads as one label',
        (tester) async {
      final semantics = tester.ensureSemantics();
      await _pump(tester, blocks(), const []);
      expect(tester.hasRunningAnimations, isTrue);
      expect(find.bySemanticsLabel('Loading products'), findsOneWidget);
      await _leave(tester);

      await _pump(tester, blocks(), const [], reduceMotion: true);
      expect(tester.hasRunningAnimations, isFalse);
      // Blocks only, on the placeholder role: no shader, no moving highlight.
      expect(find.byType(ShaderMask), findsNothing);
      final block = tester.widget<Container>(find
          .descendant(
              of: find.byType(SkeletonBlock), matching: find.byType(Container))
          .first);
      expect((block.decoration! as BoxDecoration).color,
          AppTheme.light.colorScheme.surfaceContainerHighest);
      semantics.dispose();
    });

    testWidgets('a line grows with the text size', (tester) async {
      await _pump(tester, blocks(), const [], textScale: 2);
      final line = tester.getSize(find
          .descendant(
              of: find.byType(SkeletonLine),
              matching: find.byType(SkeletonBlock))
          .first);
      expect(line.height, 28);
    });
  });

  group('Shop', () {
    testWidgets(
        'the first load shows cards and rows in grey, not a spinner, on a '
        'desktop grid and a phone list', (tester) async {
      final never = Completer<List<StoreProduct>>();
      for (final size in const [Size(1280, 900), Size(390, 844)]) {
        await _pumpShop(tester, _shop(products: never.future), size: size);
        expect(find.byType(CircularProgressIndicator), findsNothing);
        expect(find.byType(Skeleton), findsOneWidget);
        expect(find.byType(SkeletonBlock), findsWidgets);
        await _leave(tester);
      }
    });

    testWidgets(
        'every product in the grid is a ProductCard with square media, the '
        'price in ink, and the add control inside', (tester) async {
      await _pumpShop(tester, _shop());
      expect(find.byType(ProductCard), findsNWidgets(2));
      final card = find.byKey(const ValueKey('p-1'));
      expect(card, findsOneWidget);
      expect(tester.widget(card), isA<ProductCard>());
      final media = tester.getSize(find.byKey(const Key('product-card-media')).first);
      expect((media.width - media.height).abs(), lessThan(media.width * 0.15),
          reason: 'the picture is (near enough) square: $media');
      final price = tester.widget<Text>(
          find.descendant(of: card, matching: find.text('£2.50')));
      expect(price.style?.color, AppTheme.light.colorScheme.onSurface);
      expect(price.style?.fontWeight, FontWeight.w700);
      expect(
          find.descendant(
              of: card, matching: find.byIcon(Icons.add_shopping_cart)),
          findsOneWidget);
      expect(find.descendant(of: card, matching: find.text('In stock')),
          findsOneWidget);
      await _leave(tester);
    });

    testWidgets('a promotion word sits over the picture on the accent badge',
        (tester) async {
      await _pump(
          tester,
          const SizedBox(
            width: 200,
            height: 340,
            child: ProductCard(product: _butter, promoLabel: 'Offer'),
          ),
          _shop());
      await tester.pumpAndSettle();
      final badge = tester.widget<StatusBadge>(find.byType(StatusBadge));
      expect(badge.label, 'Offer');
      expect(badge.tone, StatusTone.accent);
      final media = tester.getRect(find.byKey(const Key('product-card-media')));
      expect(media.contains(tester.getCenter(find.text('Offer'))), isTrue);
      await _leave(tester);
    });

    testWidgets(
        'the grid tiles are about 180–220 wide on a desktop and widen with '
        'enlarged text, which cuts nothing off', (tester) async {
      await _pumpShop(tester, _shop());
      final normal = tester.getSize(find.byType(ProductCard).first).width;
      expect(normal, inInclusiveRange(170, 230));
      await _leave(tester);

      await _pumpShop(tester, _shop(), textScale: 2);
      await tester.pumpAndSettle();
      final large = tester.getSize(find.byType(ProductCard).first).width;
      expect(large, greaterThan(normal * 1.5));
      expect(tester.takeException(), isNull);
      await _leave(tester);
    });

    testWidgets(
        'at 200% text the category and in-stock chips keep their full height',
        (tester) async {
      // The test font's lines are exactly 1em tall, so at 200% a chip still
      // squeezes into the old 48px row here. The app's platform fonts run
      // taller — Bengali, Gujarati, Punjabi, Arabic and Urdu lines are 1.5em
      // and more — and that is where the fixed row cut the chips off.
      final light = AppTheme.light;
      final deviceLines = light.copyWith(
        chipTheme: light.chipTheme.copyWith(
          labelStyle: light.chipTheme.labelStyle?.copyWith(height: 1.5),
        ),
      );
      await _pumpShop(tester, _shop(),
          size: const Size(390, 844), textScale: 2, theme: deviceLines);
      await tester.pumpAndSettle();
      for (final chip in [
        find.widgetWithText(ChoiceChip, 'All'),
        find.widgetWithText(ChoiceChip, 'Pantry'),
        // Built, though past the end of the screen: reached by scrolling.
        find.widgetWithText(FilterChip, 'In stock'),
      ]) {
        expect(chip, findsOneWidget);
        final box = tester.getRect(chip);
        final natural = tester
            .renderObject<RenderBox>(chip)
            .getMaxIntrinsicHeight(double.infinity);
        expect(box.height, greaterThanOrEqualTo(natural - 0.5),
            reason: 'squeezed to ${box.height} of $natural');
        final label = tester.getRect(
            find.descendant(of: chip, matching: find.byType(Text)).first);
        expect(label.top, greaterThanOrEqualTo(box.top - 0.5));
        expect(label.bottom, lessThanOrEqualTo(box.bottom + 0.5));
        // Nothing of the chip lies outside the row that scrolls them.
        final row = tester.getRect(find
            .ancestor(of: chip, matching: find.byType(Scrollable))
            .first);
        expect(box.top, greaterThanOrEqualTo(row.top - 0.5));
        expect(box.bottom, lessThanOrEqualTo(row.bottom + 0.5));
      }
      expect(tester.takeException(), isNull);
      await _leave(tester);
    });

    testWidgets(
        'the fallback offers promise nothing and name no currency — the '
        "second banner is the store's own name, not a delivery threshold",
        (tester) async {
      await _pumpShop(tester, _shop());
      final carousel = find.byType(PageView);
      expect(
          find.descendant(
              of: carousel, matching: find.text('Everyday low prices')),
          findsOneWidget);

      await tester.tap(find.byWidgetPredicate(
          (w) => w is Semantics && w.properties.label == 'Offer 2 of 3'));
      await tester.pumpAndSettle();

      expect(find.descendant(of: carousel, matching: find.text('Corner Shop')),
          findsOneWidget,
          reason: "the store's own name, from storefrontConfigProvider — "
              'never a hard-coded price threshold that assumes a currency '
              'no one set');
      expect(
          find.descendant(
              of: carousel, matching: find.text('Thanks for shopping with us')),
          findsOneWidget);
      expect(find.descendant(of: carousel, matching: find.textContaining('£')),
          findsNothing);
      expect(
          find.descendant(
              of: carousel, matching: find.textContaining('delivery over')),
          findsNothing);
      await _leave(tester);
    });
  });

  group('Order history', () {
    testWidgets('the first load shows order cards in grey, not a spinner',
        (tester) async {
      final never = Completer<List<ServerOrderSummary>?>();
      await _pump(tester, const StorefrontOrdersScreen(),
          _history(() => never.future),
          size: const Size(390, 844));
      expect(find.byType(CircularProgressIndicator), findsNothing);
      expect(find.byType(Skeleton), findsOneWidget);
      await _leave(tester);
    });

    testWidgets(
        'a mouse can reload the orders and recalls from a Refresh button',
        (tester) async {
      var loads = 0;
      await _pump(
          tester,
          const StorefrontOrdersScreen(),
          _history(() => [_order(++loads == 1 ? 'PENDING' : 'CONFIRMED')]));
      await tester.pumpAndSettle();
      expect(find.text('Pending'), findsOneWidget);

      await tester.tap(find.byTooltip('Refresh'));
      await tester.pumpAndSettle();
      expect(loads, 2);
      expect(find.text('Confirmed'), findsOneWidget);
      expect(find.text('Pending'), findsNothing);
      await _leave(tester);
    }, variant: TargetPlatformVariant.only(TargetPlatform.linux));

    testWidgets(
        'a reload that fails shows the error at once, with Refresh still '
        'enabled, rather than grey cards for half a minute of retries',
        (tester) async {
      var loads = 0;
      await _pump(tester, const StorefrontOrdersScreen(), _history(() {
        if (++loads == 1) return [_order('PENDING')];
        throw Exception('offline');
      }));
      await tester.pumpAndSettle();
      expect(find.text('Pending'), findsOneWidget);

      await tester.tap(find.byTooltip('Refresh'));
      await tester.pump(const Duration(seconds: 1));
      expect(find.byType(Skeleton), findsNothing);
      expect(find.byType(ErrorView), findsOneWidget);
      expect(loads, 2, reason: 'a refused reload is not retried behind the page');
      final refresh = find.ancestor(
          of: find.byTooltip('Refresh'), matching: find.byType(IconButton));
      expect(tester.widget<IconButton>(refresh.first).onPressed, isNotNull);
      await _leave(tester);
    }, variant: TargetPlatformVariant.only(TargetPlatform.linux));

    testWidgets('a first load that fails says so at once', (tester) async {
      var loads = 0;
      await _pump(tester, const StorefrontOrdersScreen(), _history(() {
        loads++;
        throw Exception('offline');
      }), size: const Size(390, 844));
      await tester.pump(const Duration(seconds: 1));
      expect(find.byType(Skeleton), findsNothing);
      expect(find.byType(ErrorView), findsOneWidget);
      expect(loads, 1);
      await _leave(tester);
    });

    testWidgets(
        "the heading starts at the cards' edge with no button beside it — "
        'signed in on a touch screen, as a guest, and over an error',
        (tester) async {
      Future<void> check(String what, Size size, List<Override> overrides,
          {bool cards = true, bool rtl = false}) async {
        await _pump(
            tester,
            Directionality(
              textDirection: rtl ? TextDirection.rtl : TextDirection.ltr,
              child: const StorefrontOrdersScreen(),
            ),
            overrides,
            size: size);
        await tester.pump(const Duration(seconds: 1));
        await tester.pumpAndSettle();
        final title = tester.getRect(find.text('My orders'));
        final edge = _columnStart(size.width);
        final reason = '$what at ${size.width.toInt()} wide';
        if (rtl) {
          expect(title.right, moreOrLessEquals(size.width - edge),
              reason: reason);
        } else {
          expect(title.left, moreOrLessEquals(edge), reason: reason);
        }
        if (cards) {
          final card = tester.getRect(find.byType(Card).first);
          expect(rtl ? title.right : title.left,
              moreOrLessEquals(rtl ? card.right : card.left),
              reason: reason);
        }
        await _leave(tester);
      }

      expect(defaultTargetPlatform, TargetPlatform.android);
      for (final size in const [
        Size(390, 844),
        Size(820, 1180),
        Size(1280, 900),
      ]) {
        await check('signed in', size, _history(() => [_order('CONFIRMED')]));
        await check('a guest', size, _guestHistory());
        await check('an error', size,
            _history(() => throw Exception('offline')),
            cards: false);
      }
      // The start is the reading direction's: the right-hand edge in Arabic.
      await check('right to left', const Size(1280, 900),
          _history(() => [_order('CONFIRMED')]),
          rtl: true);
      expect(tester.takeException(), isNull);
    });

    testWidgets(
        'on a desktop browser only the signed-in history offers Refresh — a '
        "guest's is this device's own, with nothing to read again",
        (tester) async {
      await _pump(tester, const StorefrontOrdersScreen(), _guestHistory());
      await tester.pumpAndSettle();
      expect(find.text('My orders'), findsOneWidget);
      expect(find.text('Deliver to home'), findsNothing);
      expect(find.textContaining('Collect from Leeds'), findsOneWidget);
      expect(find.byTooltip('Refresh'), findsNothing,
          reason: 'a button that could only ever be grey');
      await _leave(tester);

      // Signed in, in a window too narrow for one row: the button wraps
      // under the heading, and the heading keeps to the cards' edge.
      await _pump(tester, const StorefrontOrdersScreen(),
          _history(() => [_order('CONFIRMED')]),
          size: const Size(400, 800));
      await tester.pumpAndSettle();
      final refresh = find.byTooltip('Refresh');
      expect(refresh, findsOneWidget);
      final title = tester.getRect(find.text('My orders'));
      final card = tester.getRect(find.byType(Card).first);
      expect(title.left, moreOrLessEquals(card.left));
      expect(tester.getRect(refresh).top, greaterThan(title.bottom));
      expect(tester.getRect(refresh).left, moreOrLessEquals(card.left));
      await _leave(tester);
    }, variant: TargetPlatformVariant.only(TargetPlatform.linux));

    testWidgets('touch screens pull to refresh and get no button',
        (tester) async {
      var loads = 0;
      await _pump(tester, const StorefrontOrdersScreen(),
          _history(() => [_order(++loads == 1 ? 'PENDING' : 'FULFILLED')]),
          size: const Size(390, 844));
      await tester.pumpAndSettle();
      expect(defaultTargetPlatform, TargetPlatform.android);
      expect(find.byTooltip('Refresh'), findsNothing);
      expect(find.byType(RefreshIndicator), findsOneWidget);
      await _leave(tester);
    });
  });

  group('Cart', () {
    test('a line goes back where it was, once', () {
      final cart = CartNotifier()
        ..add(_line('v-1', 'Apples'))
        ..add(_line('v-2', 'Bread'));
      final apples = cart.state.first;
      cart.remove('v-1');
      cart.insert(0, apples);
      expect(cart.state.map((l) => l.productName), ['Apples', 'Bread']);
      // Already back (a second Undo, or added again meanwhile): no second line.
      cart.insert(1, _line('v-1', 'Apples'));
      expect(cart.state.map((l) => l.productName), ['Apples', 'Bread']);
      // Past the end is the end.
      cart.insert(9, _line('v-3', 'Milk'));
      expect(cart.state.map((l) => l.productName), ['Apples', 'Bread', 'Milk']);
    });

    test('a line keeps its product when its quantity changes', () {
      final cart = CartNotifier()..add(_line('v-1', 'Apples', productId: 'p-9'));
      cart.setQty('v-1', 4);
      expect(cart.state.single.productId, 'p-9');
      expect(cart.state.single.qty, 4);
    });

    testWidgets('a line shows its product\'s own picture, as the shop does',
        (tester) async {
      await _pump(
          tester,
          const StorefrontCartScreen(),
          _cart(() => [
                _line('v-1', 'Apples', productId: 'p-1'),
                // Added before lines knew their product: its initials stay.
                _line('v-2', 'Bread'),
              ]),
          size: const Size(800, 1200));
      await tester.pumpAndSettle();
      final picture = tester.widget<ProductImageThumb>(find.descendant(
          of: find.widgetWithText(CartLineTile, 'Apples'),
          matching: find.byType(ProductImageThumb)));
      expect(picture.productId, 'p-1');
      expect(
          find.descendant(
              of: find.widgetWithText(CartLineTile, 'Bread'),
              matching: find.byType(ProductImageThumb)),
          findsNothing);
      final initials = tester.widget<ProductThumb>(find.descendant(
          of: find.widgetWithText(CartLineTile, 'Bread'),
          matching: find.byType(ProductThumb)));
      expect(initials.seed, 'Bread');
      await _leave(tester);
    });

    testWidgets('a product added from the shop carries its product id into the cart',
        (tester) async {
      await _pumpShop(tester, _shop());
      await tester.tap(find.byTooltip('Add to cart').first);
      await tester.pump();
      final container = ProviderScope.containerOf(
          tester.element(find.byType(ProductListScreen)));
      final line = container.read(cartProvider).single;
      expect(line.productId, anyOf('p-1', 'p-2'));
      expect(line.productName,
          line.productId == 'p-1' ? 'Crunchy peanut butter' : 'Rolled oats');
      await _leave(tester);
    });

    testWidgets(
        'each line is a CartLine in one card; at one the minus removes it, '
        'and Undo puts it back where it was', (tester) async {
      await _pump(
          tester,
          const StorefrontCartScreen(),
          _cart(() => [_line('v-1', 'Apples'), _line('v-2', 'Bread')]),
          size: const Size(800, 1200));
      await tester.pumpAndSettle();
      expect(find.byType(CartLineTile), findsNWidgets(2));
      final cards = {
        for (final tile in find.byType(CartLineTile).evaluate())
          find
              .ancestor(of: find.byWidget(tile.widget), matching: find.byType(Card))
              .evaluate()
              .first,
      };
      expect(cards, hasLength(1), reason: 'the lines share one card');

      final apples = find.widgetWithText(CartLineTile, 'Apples');
      expect(
          find.descendant(
              of: apples, matching: find.byIcon(Icons.delete_outline)),
          findsOneWidget);
      await tester.tap(
          find.descendant(of: apples, matching: find.byTooltip('Remove from cart')));
      await tester.pumpAndSettle();
      expect(find.text('Apples'), findsNothing);
      expect(find.text('Removed Apples'), findsOneWidget);

      await tester.tap(find.text('Undo'));
      await tester.pumpAndSettle();
      expect(find.byType(CartLineTile), findsNWidgets(2));
      expect(tester.getTopLeft(find.text('Apples')).dy,
          lessThan(tester.getTopLeft(find.text('Bread')).dy),
          reason: 'back in its own place, above the bread');
      await _leave(tester);
    });

    testWidgets('above one the minus takes one off', (tester) async {
      await _pump(tester, const StorefrontCartScreen(),
          _cart(() => [_line('v-1', 'Apples')..qty = 3]),
          size: const Size(800, 1200));
      await tester.pumpAndSettle();
      expect(find.byTooltip('Remove from cart'), findsNothing);
      await tester.tap(find.byTooltip('Remove one'));
      await tester.pumpAndSettle();
      expect(find.bySemanticsLabel('2 in cart'), findsOneWidget);
      await _leave(tester);
    });

    testWidgets('on a touch screen a line swipes away, with Undo',
        (tester) async {
      await _pump(tester, const StorefrontCartScreen(),
          _cart(() => [_line('v-1', 'Apples'), _line('v-2', 'Bread')]),
          size: const Size(390, 844));
      await tester.pumpAndSettle();
      await tester.drag(find.text('Apples'), const Offset(-600, 0));
      await tester.pumpAndSettle();
      expect(find.text('Apples'), findsNothing);
      expect(find.byType(CartLineTile), findsOneWidget);
      await tester.tap(find.text('Undo'));
      await tester.pumpAndSettle();
      expect(find.text('Apples'), findsOneWidget);
      await _leave(tester);
    });

    testWidgets(
        'the totals are an OrderSummary with the button inside from tablet '
        'width, and in the bar under the scroll on a phone', (tester) async {
      await _pump(tester, const StorefrontCartScreen(),
          _cart(() => [_line('v-1', 'Apples')..qty = 2, _line('v-2', 'Bread')]),
          size: const Size(800, 1200));
      await tester.pumpAndSettle();
      final summary = find.byType(OrderSummary);
      expect(summary, findsOneWidget);
      expect(find.descendant(of: summary, matching: find.text('Review order')),
          findsOneWidget);
      expect(find.descendant(of: summary, matching: find.text('Items (3)')),
          findsOneWidget);
      final total = tester.widget<Text>(
          find.descendant(of: summary, matching: find.text('£7.50')).last);
      expect(total.style?.fontSize, 22);
      expect(total.style?.fontWeight, FontWeight.w700);
      expect(total.style?.color, AppTheme.light.colorScheme.onSurface);
      await _leave(tester);

      await _pump(tester, const StorefrontCartScreen(),
          _cart(() => [_line('v-1', 'Apples')..qty = 2, _line('v-2', 'Bread')]),
          size: const Size(390, 844));
      await tester.pumpAndSettle();
      expect(find.byType(OrderSummary), findsOneWidget);
      expect(
          find.descendant(
              of: find.byType(OrderSummary),
              matching: find.text('Review order')),
          findsNothing);
      final bar = find.byType(OrderSummaryBar);
      expect(bar, findsOneWidget);
      expect(find.descendant(of: bar, matching: find.text('Review order')),
          findsOneWidget);
      expect(find.descendant(of: bar, matching: find.text('£7.50')),
          findsOneWidget);
      // The sticky CTA floats over the summary scrolling under it: shadow-2,
      // not the plain bar's hairline.
      final raised = tester.widget<DecoratedBox>(find
          .descendant(of: bar, matching: find.byType(DecoratedBox))
          .first);
      final decoration = raised.decoration as BoxDecoration;
      expect(decoration.boxShadow, AppShadow.level2);
      expect(decoration.border, isNull);
      expect(tester.takeException(), isNull);
      await _leave(tester);
    });

    testWidgets('a shop that hides prices shows the count and no amounts',
        (tester) async {
      await _pump(
          tester,
          const StorefrontCartScreen(),
          _cart(() => [_line('v-1', 'Apples', price: 0)..qty = 2],
              showPrices: false),
          size: const Size(800, 1200));
      await tester.pumpAndSettle();
      final summary = find.byType(OrderSummary);
      expect(find.descendant(of: summary, matching: find.text('Items (2)')),
          findsOneWidget);
      expect(find.text('Total (incl. VAT)'), findsNothing);
      expect(find.textContaining('£'), findsNothing);
      await _leave(tester);
    });
  });

  testWidgets(
      'the cart and the order history are named, big enough to press and '
      'legible, on a phone and a desktop', (tester) async {
    final semantics = tester.ensureSemantics();
    const target24 = MinimumTapTargetGuideline(
      size: Size(24, 24),
      link: 'https://www.w3.org/TR/WCAG22/#target-size-minimum',
    );
    Future<void> meets() async {
      await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
      await expectLater(tester, meetsGuideline(target24));
      await expectLater(tester, meetsGuideline(textContrastGuideline));
    }

    for (final size in const [Size(390, 844), Size(1280, 900)]) {
      await _pump(tester, const StorefrontCartScreen(),
          _cart(() => [_line('v-1', 'Apples'), _line('v-2', 'Bread')..qty = 2]),
          size: size);
      await tester.pumpAndSettle();
      await meets();
      // A screen reader can take a line out without swiping.
      if (size.width < 600) {
        expect(
            tester.getSemantics(find.byType(CartLineTile).first),
            isSemantics(customActions: const [
              CustomSemanticsAction(label: 'Remove from cart'),
            ]));
      }
      await _leave(tester);

      await _pump(tester, const StorefrontOrdersScreen(),
          _history(() => [_order('PENDING')]),
          size: size);
      await tester.pumpAndSettle();
      await meets();
      await _leave(tester);
    }
    semantics.dispose();
  });

  group('OrderSummary', () {
    testWidgets(
        'savings read with a minus in the success colour, labels are muted '
        'and values in ink, and the nudge sits under the button',
        (tester) async {
      await _pump(
          tester,
          OrderSummary(
            itemCount: 4,
            subtotal: '£12.00',
            savings: '£1.50',
            delivery: 'Free',
            total: '£10.50',
            action: FilledButton(onPressed: () {}, child: const Text('Checkout')),
            nudge: const Text('Spend £4.50 more for free delivery'),
          ),
          const []);
      final cs = AppTheme.light.colorScheme;
      final saving = tester.widget<Text>(find.text('−£1.50'));
      expect(saving.style?.color, StatusColors.light.success);
      expect(tester.widget<Text>(find.text('Items (4)')).style?.color,
          cs.onSurfaceVariant);
      expect(tester.widget<Text>(find.text('£12.00')).style?.color,
          cs.onSurface);
      expect(find.text('Delivery'), findsOneWidget);
      expect(find.text('Free'), findsOneWidget);
      expect(
          tester.getTopLeft(find.text('Spend £4.50 more for free delivery')).dy,
          greaterThan(tester.getTopLeft(find.text('Checkout')).dy));
    });
  });
}
