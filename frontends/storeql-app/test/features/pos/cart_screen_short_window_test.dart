import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/pos/cart_screen.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';

// ---------------------------------------------------------------------------
// A phone turned on its side (844 × 390) is wide enough for both panes but
// leaves the sale pane under 300px of height — less than its fixed rows
// (store, customer, barcode, actions, folded totals) need. The till must stack
// its panes there and let the top of the sale scroll, never overflow.
// ---------------------------------------------------------------------------

class _NoopPosSessionNotifier extends PosSessionNotifier {
  _NoopPosSessionNotifier(super.ref);

  @override
  Future<void> restore() async {}
}

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

/// Answers every read with an empty list: no recalls, no instruments.
class _Quiet implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
          RequestOptions o, Stream<List<int>>? s, Future<void>? c) async =>
      ResponseBody.fromString('{"data":[]}', 200, headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      });
}

PosLine _line(String id, String name) => PosLine(
      variantId: id,
      sku: 'SKU-$id',
      name: name,
      qty: 1,
      unitPrice: 2.5,
      currency: 'GBP',
    );

/// The POS shell around the screen at [size]: its app bar, and from 800 wide
/// the navigation rail beside the content (an 80px rail and its divider).
///
/// Under 800 wide the real shell puts a NavigationBar below the content;
/// [empty] leaves the till as it is between customers.
Future<void> _pumpTill(WidgetTester tester, Size size,
    {double textScale = 1.0, bool empty = false}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = _Quiet();
  const screen = PosCartScreen();
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      posStoresProvider.overrideWith((ref) async => const []),
      posCategoriesProvider.overrideWith((ref) async => const []),
      posCatalogProvider.overrideWith((ref, f) async => const []),
      posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
    ],
    child: MaterialApp(
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context)
            .copyWith(textScaler: TextScaler.linear(textScale)),
        child: child!,
      ),
      home: Scaffold(
        appBar: AppBar(title: const Text('POS Terminal')),
        bottomNavigationBar: size.width >= 800
            ? null
            : NavigationBar(destinations: const [
                NavigationDestination(icon: Icon(Icons.point_of_sale), label: 'Sale'),
                NavigationDestination(icon: Icon(Icons.payments), label: 'Tender'),
                NavigationDestination(icon: Icon(Icons.money), label: 'Cash'),
              ]),
        body: size.width >= 800
            ? const Row(children: [
                SizedBox(width: 80),
                VerticalDivider(width: 1),
                Expanded(child: screen),
              ])
            : screen,
      ),
    ),
  ));
  await tester.pumpAndSettle();
  if (empty) return;
  final el = tester.element(find.byType(PosCartScreen));
  ProviderScope.containerOf(el).read(posCartProvider.notifier).loadLines([
    _line('a', 'Tilda Pure Basmati Rice 1kg'),
    _line('b', 'Whole milk 2 pints'),
    _line('c', 'Sourdough loaf'),
  ]);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
      'a phone in landscape stacks the panes and the sale fits without overflowing',
      (tester) async {
    await _pumpTill(tester, const Size(844, 390));

    expect(tester.takeException(), isNull,
        reason: 'the sale pane must not overflow a 390px-tall window');
    // Stacked: the catalog is behind Browse, not squeezed in beside the sale.
    expect(find.byTooltip('Browse products').evaluate().isNotEmpty ||
            find.text('Browse').evaluate().isNotEmpty,
        isTrue);
    expect(find.text('Search products…'), findsNothing);
    // The barcode field is still where a cashier's eye starts.
    expect(find.byType(TextField).hitTestable(), findsWidgets);

    // The top of the pane scrolls with the lines (the pane's own scroll view
    // is the first Scrollable in it), so the last line is reachable.
    final pane = find
        .descendant(
            of: find.byType(PosCartScreen), matching: find.byType(Scrollable))
        .first;
    await tester.scrollUntilVisible(find.text('Sourdough loaf'), 50,
        scrollable: pane);
    await tester.pumpAndSettle();
    expect(find.text('Sourdough loaf').hitTestable(), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a phone in portrait at 200% text still fits the sale',
      (tester) async {
    await _pumpTill(tester, const Size(390, 844), textScale: 2.0);
    expect(tester.takeException(), isNull,
        reason: 'the fixed rows at double size must scroll, not overflow');
  });

  testWidgets('a tablet in landscape keeps both panes, the header pinned',
      (tester) async {
    await _pumpTill(tester, const Size(1180, 820));
    expect(tester.takeException(), isNull);
    expect(find.text('Search products…'), findsOneWidget,
        reason: 'the catalog stays beside the sale where there is room');
    expect(find.text('Browse'), findsNothing);
  });

  // Windows taller than a phone on its side but still too short for the
  // pinned sale beside the catalog: an idle till (the state between
  // customers) and a busy one must neither overflow.
  for (final (size, scale) in const [
    (Size(1024, 480), 1.0),
    (Size(1024, 500), 1.0),
    (Size(1024, 520), 1.0),
    (Size(1366, 500), 1.0),
    (Size(760, 500), 1.0),
    (Size(760, 620), 1.0),
    (Size(760, 660), 1.0),
    (Size(1180, 820), 1.7),
    (Size(1024, 768), 1.3),
  ]) {
    for (final empty in const [true, false]) {
      testWidgets(
          '${size.width.toInt()}x${size.height.toInt()} at ${scale}x text, '
          '${empty ? 'an idle' : 'a busy'} till fits without overflowing',
          (tester) async {
        await _pumpTill(tester, size, textScale: scale, empty: empty);
        expect(tester.takeException(), isNull);
        // Pinned beside the catalog, the header leaves the lines real room:
        // at least the first line shows whole, not a sliver of it.
        if (!empty && find.text('Search products…').evaluate().isNotEmpty) {
          final first = find.text('Tilda Pure Basmati Rice 1kg');
          expect(first.hitTestable(), findsOneWidget);
          final pane = tester.getRect(find
              .ancestor(of: first, matching: find.byType(ListView))
              .first);
          expect(pane.height, greaterThan(100),
              reason: 'the sale lines get room to be read, not 0–45px');
        }
      });
    }
  }
}
