import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:storeql_app/core/constants.dart';
import 'package:storeql_app/core/theme.dart';
import 'package:storeql_app/features/storefront/accessibility_screen.dart';
import 'package:storeql_app/features/storefront/product_list_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';
import 'package:storeql_app/features/storefront/storefront_shell.dart';
import 'package:storeql_app/features/storefront/survey_widgets.dart';

// ---------------------------------------------------------------------------
// The accessible storefront (12.11, WCAG 2.1 AA via EN 301 549 for the European
// Accessibility Act): every control a shopper can press is named, at least
// 24 by 24, and legible; text at twice its size cuts nothing off; the moving
// offers can be stopped and stay still for reduced motion; the add control and
// the cart bar say what they hold; pictures that repeat a name are silent; the
// survey can be answered from a keyboard; and the statement is reachable from
// the account menu, read in headed sections, and never opens with a
// placeholder name when the shop's details cannot be read. It speaks for the
// business (Corner Stores Ltd), never for one of its stores (Corner Shop).
// ---------------------------------------------------------------------------

const _butter = StoreProduct(id: 'p-1', name: 'Crunchy peanut butter');
const _oats = StoreProduct(id: 'p-2', name: 'Rolled oats');
const _butterJar = StoreVariant(id: 'v-1', sku: 'PB-1');
const _oatsBag = StoreVariant(id: 'v-2', sku: 'OAT-1');
const _price =
    ResolvedPrice(unitPrice: 2.08, totalWithVat: 2.50, currency: 'GBP');

/// WCAG 2.2 success criterion 2.5.8, the level AA minimum; Material's own 48 is the stricter
/// Android guideline and is not what the statement promises.
const _target24 = MinimumTapTargetGuideline(
  size: Size(24, 24),
  link: 'https://www.w3.org/TR/WCAG22/#target-size-minimum',
);

/// A network that answers nothing, so anything not overridden fails soft as it would offline.
Dio _offline() {
  final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
  dio.interceptors.add(InterceptorsWrapper(
    onRequest: (o, h) => h.reject(DioException(
        requestOptions: o, type: DioExceptionType.connectionError)),
  ));
  return dio;
}

List<Override> _shop({
  bool showPrices = true,
  bool configReadable = true,
  List<CartLine> Function()? cart,
}) =>
    [
      storefrontDioProvider.overrideWithValue(_offline()),
      storefrontTenantProvider.overrideWith((ref) => 't-1'),
      storefrontStoresProvider.overrideWith((ref) async => const [
            StoreSummary(id: 's-1', name: 'High Street', showPrices: true)
          ]),
      storefrontSuspendedProvider.overrideWith((ref) async => false),
      // Unreadable: the real provider, over a network that answers nothing.
      if (configReadable) ...[
        storefrontConfigProvider.overrideWith((ref) async =>
            StorefrontConfig(showPrices: showPrices, storeName: 'Corner Shop')),
        // The business the shop belongs to: the one the accessibility statement speaks for.
        storefrontBusinessNameProvider
            .overrideWith((ref) async => 'Corner Stores Ltd'),
      ],
      storefrontAvailabilityProvider.overrideWith((ref) async => const {
        'v-1': StockInfo(inStock: true),
        'v-2': StockInfo(inStock: false),
      }),
      storefrontPromotionsProvider.overrideWith((ref) async => const []),
      storefrontCategoriesProvider.overrideWith(
          (ref) async => const [StoreCategory(id: 'c-1', name: 'Pantry')]),
      storefrontProductsProvider((query: '', categoryId: null))
          .overrideWith((ref) async => const [_butter, _oats]),
      productCardOfferProvider('p-1')
          .overrideWith((ref) async => const CardOffer(_butterJar, _price)),
      productCardOfferProvider('p-2')
          .overrideWith((ref) async => const CardOffer(_oatsBag, _price)),
      productFirstVariantProvider('p-1')
          .overrideWith((ref) async => _butterJar),
      productFirstVariantProvider('p-2').overrideWith((ref) async => _oatsBag),
      if (cart != null)
        cartProvider.overrideWith((ref) {
          final notifier = CartNotifier();
          cart().forEach(notifier.add);
          return notifier;
        }),
    ];

CartLine _jarInCart({bool priced = true}) => CartLine(
      variantId: 'v-1',
      productName: 'Crunchy peanut butter',
      sku: 'PB-1',
      unitPrice: priced ? 2.50 : 0,
      currency: priced ? 'GBP' : '',
    );

/// The storefront shell as the router builds it, at a given size, text scale and motion setting.
Future<void> _pumpShop(
  WidgetTester tester,
  List<Override> overrides, {
  String at = '/store/products',
  Size size = const Size(1280, 900),
  double textScale = 1,
  bool reduceMotion = false,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final router = GoRouter(
    initialLocation: at,
    routes: [
      ShellRoute(
        builder: (context, state, child) => StorefrontShell(
            currentLocation: state.matchedLocation, child: child),
        routes: [
          GoRoute(
              path: '/store/products',
              builder: (_, _) => const ProductListScreen()),
          GoRoute(
              path: '/store/accessibility',
              builder: (_, _) => const StorefrontAccessibilityScreen()),
          GoRoute(path: '/store/cart', builder: (_, _) => const Text('Cart')),
        ],
      ),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: overrides,
    child: MaterialApp.router(
      theme: AppTheme.light,
      routerConfig: router,
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(
          textScaler: TextScaler.linear(textScale),
          disableAnimations: reduceMotion,
        ),
        child: child!,
      ),
    ),
  ));
  await tester.pump();
  // The list screen picks a store after the first frame and asks about preferences 500 ms later.
  await tester.pump(const Duration(milliseconds: 600));
  await tester.pump();
  // Data that resolved during that frame reaches the widgets watching it on the next.
  await tester.pump();
}

/// The statement's own list, not the shell's navigation, which scrolls too.
final _statementScroll = find
    .descendant(
        of: find.byType(StorefrontAccessibilityScreen),
        matching: find.byType(Scrollable))
    .first;

/// Unmounts the tree so the offers timer and any snack bar are cancelled before the test ends.
Future<void> _leave(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox());
  await tester.pump(const Duration(seconds: 1));
}

Future<void> _meetsAllGuidelines(WidgetTester tester) async {
  await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
  await expectLater(tester, meetsGuideline(_target24));
  await expectLater(tester, meetsGuideline(textContrastGuideline));
}

double _offerPage(WidgetTester tester) =>
    tester.widget<PageView>(find.byType(PageView)).controller!.page!;

void main() {
  setUp(() => FlutterSecureStorage.setMockInitialValues({
        StorageKeys.sfGenderAsked: 'true',
        StorageKeys.sfPrefsAsked: 'true',
      }));

  testWidgets(
      'every control on the shop floor is named, big enough to press and legible, '
      'on a desktop grid and a phone list', (tester) async {
    final semantics = tester.ensureSemantics();
    for (final size in const [Size(1280, 900), Size(400, 860)]) {
      await _pumpShop(tester, _shop(cart: () => [_jarInCart()]), size: size);
      await _meetsAllGuidelines(tester);
      await _leave(tester);
    }
    semantics.dispose();
  });

  testWidgets('text at twice its size cuts nothing off on a phone',
      (tester) async {
    FlutterSecureStorage.setMockInitialValues(
        {}); // a first visit: the sheet asks about the shopper
    await _pumpShop(tester, _shop(cart: () => [_jarInCart()]),
        size: const Size(400, 860), textScale: 2);
    await tester.pumpAndSettle();
    expect(find.text('Tell us about yourself'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.tap(find.text('Skip'));
    await tester.pumpAndSettle();
    expect(find.text('Tell us about yourself'), findsNothing);
    // The banner grows with the text, so the products sit further down: scroll to them, which also lays
    // their rows out at this size.
    await tester.scrollUntilVisible(find.text('Rolled oats'), 200,
        scrollable: find
            .descendant(
                of: find.byType(ProductListScreen),
                matching: find.byType(Scrollable))
            .first);
    expect(find.text('Rolled oats'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await _leave(tester);

    await _pumpShop(tester, _shop(),
        at: '/store/accessibility', size: const Size(400, 860), textScale: 2);
    await tester.scrollUntilVisible(
        find.byKey(const Key('accessibility-feedback')), 300,
        scrollable: _statementScroll);
    await tester.tap(find.byKey(const Key('accessibility-feedback')));
    await tester.pumpAndSettle();
    expect(find.text('Send feedback'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await _leave(tester);
  });

  testWidgets(
      'the offers move by themselves, stop when paused however often the button '
      'is pressed, and never move for a shopper who asked for less motion',
      (tester) async {
    await _pumpShop(tester, _shop());
    await tester.pump(const Duration(seconds: 4));
    await tester.pump(const Duration(milliseconds: 500));
    expect(_offerPage(tester).round(), 1);

    await tester.tap(find.byTooltip('Pause offers'));
    await tester.pump();
    await tester.pump(const Duration(seconds: 9));
    expect(_offerPage(tester).round(), 1);

    // Pressed 24 more times — 25 in all, so still stopped — and nothing moves.
    for (var i = 0; i < 24; i++) {
      await tester.tap(find.byKey(const Key('offers-pause')));
      await tester.pump();
    }
    expect(find.byTooltip('Play offers'), findsOneWidget);
    await tester.pump(const Duration(seconds: 9));
    expect(_offerPage(tester).round(), 1);

    await tester.tap(find.byTooltip('Play offers'));
    await tester.pump();
    await tester.pump(const Duration(seconds: 4));
    await tester.pump(const Duration(milliseconds: 500));
    expect(_offerPage(tester).round(), 2);
    await _leave(tester);

    await _pumpShop(tester, _shop(), reduceMotion: true);
    await tester.pump(const Duration(seconds: 13));
    expect(_offerPage(tester).round(), 0);
    await _leave(tester);
  });

  testWidgets(
      'the add control names each step, announces the count as it changes, and '
      'survives being hammered in both directions', (tester) async {
    final semantics = tester.ensureSemantics();
    await _pumpShop(tester, _shop(), size: const Size(400, 860));
    // The oats are out of stock: one add button, for the butter only.
    expect(find.byIcon(Icons.add_shopping_cart), findsOneWidget);
    await tester.tap(find.byIcon(Icons.add_shopping_cart));
    await tester.pump();
    expect(tester.getSemantics(find.bySemanticsLabel('1 in cart')),
        isSemantics(label: '1 in cart', isLiveRegion: true));
    expect(tester.getSemantics(find.bySemanticsLabel('Add one')),
        isSemantics(isButton: true, hasTapAction: true));

    for (var i = 0; i < 40; i++) {
      await tester.tap(find.bySemanticsLabel('Add one'));
      await tester.pump();
    }
    expect(find.bySemanticsLabel('41 in cart'), findsOneWidget);
    expect(find.bySemanticsLabel('41 items in the cart, £102.50. View cart'),
        findsOneWidget);

    for (var i = 0; i < 41; i++) {
      await tester.tap(find.bySemanticsLabel('Remove one'));
      await tester.pump();
    }
    expect(find.bySemanticsLabel('Remove one'), findsNothing);
    expect(find.bySemanticsLabel(RegExp(r'in cart$')), findsNothing);
    expect(find.byIcon(Icons.add_shopping_cart), findsOneWidget);
    expect(find.bySemanticsLabel(RegExp('View cart')), findsNothing);
    await _leave(tester);
    semantics.dispose();
  });

  testWidgets(
      'the cart bar is one button that says what the cart holds, and gives no '
      'price in a shop that hides prices', (tester) async {
    final semantics = tester.ensureSemantics();
    await _pumpShop(tester, _shop(cart: () => [_jarInCart()]));
    expect(
        tester.getSemantics(
            find.bySemanticsLabel('1 item in the cart, £2.50. View cart')),
        isSemantics(isButton: true, hasTapAction: true));
    await _leave(tester);

    await _pumpShop(tester,
        _shop(showPrices: false, cart: () => [_jarInCart(priced: false)]));
    expect(
        find.bySemanticsLabel('1 item in the cart. View cart'), findsOneWidget);
    // Only the cart bar's own label: the offer banners mention £ amounts.
    expect(find.bySemanticsLabel(RegExp(r'in the cart.*(£|GBP|0\.00)')),
        findsNothing);
    await _leave(tester);
    semantics.dispose();
  });

  testWidgets(
      'a picture that only repeats the product name is silent, and the sponsored '
      'tile says it is a link, sponsored, and opens elsewhere', (tester) async {
    final semantics = tester.ensureSemantics();
    await _pumpShop(tester, _shop());
    // The colour tiles draw the initials of the name beside them; a screen reader must not say "CP".
    expect(find.bySemanticsLabel(RegExp(r'^[A-Z]{1,3}$')), findsNothing);
    expect(
        find.bySemanticsLabel(RegExp('Crunchy peanut butter')), findsWidgets);
    expect(
        tester.getSemantics(find.bySemanticsLabel(RegExp('FeatureFragment'))),
        isSemantics(isLink: true, hint: 'Sponsored. Opens in a new window'));
    await _leave(tester);
    semantics.dispose();
  });

  testWidgets(
      'a shopper is asked about themselves on a first visit, and not again once '
      'they have answered or skipped (SJ-D62)', (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    await _pumpShop(tester, _shop());
    await tester.pumpAndSettle();
    expect(find.text('Tell us about yourself'), findsOneWidget);
    await tester.tap(find.text('Skip'));
    await tester.pumpAndSettle();
    expect(find.text('Tell us about yourself'), findsNothing);
    await _leave(tester);

    // The next visit: a new session reads the stored answer before deciding.
    await _pumpShop(tester, _shop());
    await tester.pump(const Duration(seconds: 2));
    expect(find.text('Tell us about yourself'), findsNothing);
    await _leave(tester);
  });

  testWidgets(
      'a signed-out shopper reaches the statement from the account menu and reads '
      'it in headed sections, and a barrier can be reported from it',
      (tester) async {
    final semantics = tester.ensureSemantics();
    await _pumpShop(tester, _shop());
    await tester.tap(find.byTooltip('Account'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Accessibility'));
    await tester.pumpAndSettle();

    // The business, not the store: the service provider the European Accessibility Act means.
    expect(find.textContaining('Corner Stores Ltd wants everyone'), findsOneWidget);
    expect(find.textContaining('Corner Shop wants everyone'), findsNothing);
    expect(find.textContaining('partially conformant'), findsOneWidget);
    await _meetsAllGuidelines(tester);

    // Kept to a reading measure, the statement runs longer than the window: each heading is
    // scrolled to before it is read.
    for (final heading in const [
      'Accessibility statement',
      'How far this shop meets the standard',
      'What is known not to work well yet',
    ]) {
      await tester.scrollUntilVisible(find.text(heading), 200,
          scrollable: _statementScroll);
      expect(tester.getSemantics(find.text(heading)),
          isSemantics(label: heading, isHeader: true));
    }

    await tester.scrollUntilVisible(
        find.byKey(const Key('accessibility-feedback')), 300,
        scrollable: _statementScroll);
    await tester.tap(find.byKey(const Key('accessibility-feedback')));
    await tester.pumpAndSettle();
    expect(tester.getSemantics(find.text('Send feedback')),
        isSemantics(label: 'Send feedback', isHeader: true));
    expect(find.widgetWithText(FilterChip, 'Accessibility'), findsOneWidget);
    await _leave(tester);
    semantics.dispose();
  });

  testWidgets(
      'when the shop details cannot be read the statement still renders, naming '
      '"This shop" and never the placeholder', (tester) async {
    await _pumpShop(tester, _shop(configReadable: false),
        at: '/store/accessibility');
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.textContaining('This shop wants everyone'), findsOneWidget);
    expect(find.textContaining('- wants everyone'), findsNothing);
    await _leave(tester);
  });

  testWidgets(
      'the post-order survey rates by named faces that a keyboard can reach and '
      'choose', (tester) async {
    final semantics = tester.ensureSemantics();
    await tester.pumpWidget(ProviderScope(
      child: MaterialApp(
        theme: AppTheme.light,
        home: Scaffold(
          body: Builder(
            builder: (context) => TextButton(
              onPressed: () => showPostOrderSurveySheet(context, 'o-1'),
              child: const Text('Open survey'),
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('Open survey'));
    await tester.pump();
    await tester.pumpAndSettle();

    expect(tester.getSemantics(find.text('Quick feedback')),
        isSemantics(label: 'Quick feedback', isHeader: true));
    for (var i = 1; i <= 5; i++) {
      expect(find.bySemanticsLabel('Rate $i of 5'), findsOneWidget);
    }
    FilledButton submit() => tester
        .widget<FilledButton>(find.widgetWithText(FilledButton, 'Submit'));
    expect(submit().onPressed, isNull);

    // Tab to the third face, as a keyboard user would, then press Space.
    final third = Focus.of(tester.element(find.descendant(
        of: find.bySemanticsLabel('Rate 3 of 5'),
        matching: find.byType(AnimatedContainer))));
    var presses = 0;
    while (!third.hasPrimaryFocus && presses < 20) {
      await tester.sendKeyEvent(LogicalKeyboardKey.tab);
      await tester.pump();
      presses++;
    }
    expect(third.hasPrimaryFocus, isTrue,
        reason: 'the face is not reachable by Tab');
    await tester.sendKeyEvent(LogicalKeyboardKey.space);
    await tester.pump();

    expect(tester.getSemantics(find.bySemanticsLabel('Rate 3 of 5')),
        isSemantics(isSelected: true));
    expect(tester.getSemantics(find.bySemanticsLabel('Rate 2 of 5')),
        isSemantics(isSelected: false));
    expect(submit().onPressed, isNotNull);
    await _leave(tester);
    semantics.dispose();
  });
}
