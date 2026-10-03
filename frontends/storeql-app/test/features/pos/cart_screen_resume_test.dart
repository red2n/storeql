import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/pos/cart_screen.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';

// ---------------------------------------------------------------------------
// Regression coverage: resuming a held sale used to silently overwrite
// (discard) whatever the cashier had already scanned into the current sale,
// with no confirmation. The screen must now ask before discarding.
// ---------------------------------------------------------------------------

/// A no-op session notifier — overrides restore() so the real constructor
/// never reaches for the network to restore a clocked-in session.
class _NoopPosSessionNotifier extends PosSessionNotifier {
  _NoopPosSessionNotifier(super.ref);

  @override
  Future<void> restore() async {}
}

/// Answers the resume POST with the held sale's basket.
class _ResumeOk implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async =>
      ResponseBody.fromString(
          '{"data":{"id":"p1","subtotal":5.0,"items":[{"variantId":"held-1","qty":1,"unitPrice":5.0}]}}', 200,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
}

class _Api implements ApiClient {
  @override
  Dio dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = _ResumeOk();
}

Widget _scope(List<Override> overrides) => ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(_Api()),
        // No stores configured — posShowPricesProvider defaults to true and
        // _StoreSelector renders the placeholder without hitting the network.
        posStoresProvider.overrideWith((ref) async => const []),
        posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
        ...overrides,
      ],
      child: const MaterialApp(home: Scaffold(body: PosCartScreen())),
    );

PosLine _line(String variantId, {double qty = 1}) => PosLine(
      variantId: variantId,
      sku: 'SKU-$variantId',
      name: 'Product $variantId',
      qty: qty,
      unitPrice: 5.0,
      currency: 'GBP',
    );

ParkedSale _parkedSale(String id) => ParkedSale(
      id: id,
      customerName: 'Held sale',
      subtotal: 5.0,
      lines: [_line('held-1')],
    );

/// Narrow viewport so the two-pane wide layout (which needs the catalog
/// providers) never builds — only the sale pane, which is what this test
/// exercises.
void _useNarrowViewport(WidgetTester tester) {
  tester.view.physicalSize = const Size(700, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

void _seedCart(WidgetTester tester, List<PosLine> lines) {
  final el = tester.element(find.byType(PosCartScreen).first);
  ProviderScope.containerOf(el).read(posCartProvider.notifier).loadLines(lines);
}

void main() {
  group('PosCartScreen — resume held sale', () {
    testWidgets(
        'resuming with an empty current sale loads the held lines without a prompt',
        (tester) async {
      _useNarrowViewport(tester);
      await tester.pumpWidget(_scope([
        parkedSalesProvider.overrideWith((ref) async => [_parkedSale('p1')]),
      ]));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Resume'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Held sale'));
      await tester.pumpAndSettle();

      // No discard-confirmation dialog — cart was empty, nothing to lose.
      expect(find.text('Discard current sale?'), findsNothing);

      final el = tester.element(find.byType(PosCartScreen).first);
      final cart = ProviderScope.containerOf(el).read(posCartProvider);
      expect(cart.map((l) => l.variantId), ['held-1']);
    });

    testWidgets(
        'resuming with unsaved lines in the current sale prompts before discarding',
        (tester) async {
      _useNarrowViewport(tester);
      await tester.pumpWidget(_scope([
        parkedSalesProvider.overrideWith((ref) async => [_parkedSale('p1')]),
      ]));
      await tester.pumpAndSettle();
      _seedCart(tester, [_line('unsaved-1')]);
      await tester.pump();

      await tester.tap(find.text('Resume'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Held sale'));
      await tester.pumpAndSettle();

      // Confirmation dialog shown; cart must still hold the unsaved line.
      expect(find.text('Discard current sale?'), findsOneWidget);
      final el = tester.element(find.byType(PosCartScreen).first);
      var cart = ProviderScope.containerOf(el).read(posCartProvider);
      expect(cart.map((l) => l.variantId), ['unsaved-1']);

      // Cancelling leaves the current sale untouched.
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      cart = ProviderScope.containerOf(el).read(posCartProvider);
      expect(cart.map((l) => l.variantId), ['unsaved-1']);
    });

    testWidgets('confirming discard replaces the cart with the held sale lines',
        (tester) async {
      _useNarrowViewport(tester);
      await tester.pumpWidget(_scope([
        parkedSalesProvider.overrideWith((ref) async => [_parkedSale('p1')]),
      ]));
      await tester.pumpAndSettle();
      _seedCart(tester, [_line('unsaved-1')]);
      await tester.pump();

      await tester.tap(find.text('Resume'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Held sale'));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Discard & resume'));
      await tester.pumpAndSettle();

      final el = tester.element(find.byType(PosCartScreen).first);
      final cart = ProviderScope.containerOf(el).read(posCartProvider);
      expect(cart.map((l) => l.variantId), ['held-1']);
    });
  });
}
