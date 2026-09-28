import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/inventory_screen.dart';
import 'package:storeql_app/shared/widgets/empty_state.dart';

import '../../support/fake_api.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// Receive Stock and Set reorder level name the product, not its id:
//   * with nothing to scan, the person finds the product by its name or its
//     SKU — the catalogue search the till and the storefront use, plus the
//     new lines not yet on sale — and chooses a variant by product name,
//     variant and SKU; the request carries that variant's id, as before;
//   * a scanned barcode still fills the choice, and the lot and expiry read
//     off the case label are said in words and as a date (*4 Oct 2026*),
//     never ISO.
// ---------------------------------------------------------------------------

const _store = '01a0c200-0000-7000-8000-000000000001';
const _mugProduct = '01a0c200-0000-7000-8000-0000000000p1';
const _plateProduct = '01a0c200-0000-7000-8000-0000000000p2';
const _teapotProduct = '01a0c200-0000-7000-8000-0000000000p3';
const _mug = '01a0c200-0000-7000-8000-0000000000v1';
const _sidePlate = '01a0c200-0000-7000-8000-0000000000v2';
const _dinnerPlate = '01a0c200-0000-7000-8000-0000000000v3';
const _teapot = '01a0c200-0000-7000-8000-0000000000v4';

Map<String, dynamic> _product(String id, String name, [String status = 'ACTIVE']) => {
      'id': id,
      'name': name,
      'status': status,
      'sellableOnline': true,
      'sellablePos': true,
      'createdAt': '2026-09-01T09:00:00Z',
    };

Map<String, dynamic> _variant(String id, String productId, String sku, Map<String, String> attrs,
        [String status = 'ACTIVE']) =>
    {
      'id': id,
      'productId': productId,
      'sku': sku,
      'barcode': null,
      'attributes': jsonEncode(attrs),
      'unit': 'EA',
      'status': status,
    };

/// The catalogue as the catalogue search sees it: on sale, or running down.
final _catalogue = [_product(_mugProduct, 'Blue mug'), _product(_plateProduct, 'Plate')];

final _variants = {
  _mugProduct: [_variant(_mug, _mugProduct, 'MUG-BLU', {'size': '250 ml'})],
  _plateProduct: [
    _variant(_dinnerPlate, _plateProduct, 'PLT-DN', {'size': 'Dinner'}),
    _variant(_sidePlate, _plateProduct, 'PLT-SD', {'size': 'Side'}),
    _variant('01a0c200-0000-7000-8000-0000000000v9', _plateProduct, 'PLT-OLD', {'size': 'Old'},
        'DELISTED'),
  ],
  _teapotProduct: [_variant(_teapot, _teapotProduct, 'TEA-POT', {})],
};

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  /// Bodies posted, by the path's last two segments.
  final Map<String, Map<String, dynamic>> posted = {};

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    final qp = o.queryParameters;
    if (o.method == 'POST') {
      final data = o.data is Map<String, dynamic> ? o.data as Map<String, dynamic> : const <String, dynamic>{};
      posted[path.split('/').reversed.take(2).toList().reversed.join('/')] = data;
      return jsonResponse('{"data":{}}', 201);
    }
    if (path.endsWith('/admin/tenant')) {
      return jsonResponse('{"data":{"id":"t","name":"Shop","status":"ACTIVE","currency":"GBP",'
          '"country":"GB"}}');
    }
    if (path.endsWith('/admin/stores')) {
      return jsonResponse('{"data":[{"id":"$_store","name":"Leeds","code":"LDS","type":"STORE",'
          '"status":"ACTIVE"}],"meta":{"nextCursor":null}}');
    }
    if (path.endsWith('/catalog/scan')) {
      return jsonResponse(jsonEncode({
        'data': {
          'item': {
            'variantId': _mug,
            'productId': _mugProduct,
            'productName': 'Blue mug',
            'sku': 'MUG-BLU',
            'attributes': jsonEncode({'size': '250 ml'}),
          },
          'code': {'batch': 'L123', 'expiry': '2026-10-04'},
        },
      }));
    }
    if (path.endsWith('/catalog/products')) {
      final sku = qp['sku'] as String?;
      final q = (qp['q'] as String?)?.toLowerCase();
      final hits = [
        for (final p in _catalogue)
          if ((sku != null &&
                  _variants[p['id']]!.any((v) => v['sku'] == sku && v['status'] == 'ACTIVE')) ||
              (q != null && (p['name'] as String).toLowerCase().contains(q)))
            p,
      ];
      return jsonResponse(jsonEncode({'data': hits}));
    }
    if (path.endsWith('/admin/products') && qp['status'] == 'NEW_LINE') {
      return jsonResponse(jsonEncode({
        'data': [_product(_teapotProduct, 'Teapot', 'NEW_LINE')],
        'meta': {'nextCursor': null},
      }));
    }
    final variants = RegExp(r'/admin/products/([^/]+)/variants$').firstMatch(path);
    if (variants != null) {
      return jsonResponse(jsonEncode({'data': _variants[variants.group(1)] ?? const []}));
    }
    if (path.endsWith('/admin/inventory/levels/summary')) {
      return jsonResponse('{"data":{"skuCount":0,"lowStockCount":0}}');
    }
    if (path.endsWith('/admin/inventory/levels')) {
      return jsonResponse('{"data":[],"meta":{"nextCursor":null}}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _pump(WidgetTester tester,
    {Size size = const Size(1280, 900), double textScale = 1}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth('OWNER')),
      // The camera, as a case label read by it.
      inventoryBarcodeScannerProvider.overrideWithValue((_) async => '0105012345678900'),
    ],
    child: MaterialApp(
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(textScale)),
        child: child!,
      ),
      home: const Scaffold(body: InventoryScreen()),
    ),
  ));
  await tester.pumpAndSettle();
  return server;
}

Future<void> _openReceive(WidgetTester tester) async {
  await tester.tap(find.widgetWithText(FilledButton, 'Receive Stock'));
  await tester.pumpAndSettle();
}

Future<void> _chooseStore(WidgetTester tester) async {
  await tester.tap(find.widgetWithText(DropdownButtonFormField<String>, 'Store *'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Leeds (LDS)').last);
  await tester.pumpAndSettle();
}

final _productField = find.widgetWithText(TextFormField, 'Product *');

/// Opens the product search and types [text] into it.
Future<void> _search(WidgetTester tester, String text) async {
  await tester.tap(_productField);
  await tester.pumpAndSettle();
  await tester.enterText(find.widgetWithText(TextField, 'Name or SKU'), text);
  await tester.pump(const Duration(milliseconds: 400));
  await tester.pumpAndSettle();
}

/// The search's result for [title], a list row.
Finder _result(String title) => find.widgetWithText(ListTile, title);

void _expectNoIds() {
  expect(find.textContaining('Variant ID'), findsNothing);
  expect(find.textContaining('UUID'), findsNothing);
  expect(find.textContaining('0000000000v'), findsNothing);
}

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  setUpAll(initializeDateFormatting);

  group('Receive Stock', () {
    testWidgets('a scanned case label fills the product and says its expiry as a date',
        (tester) async {
      final server = await _pump(tester);
      await _openReceive(tester);
      await tester.tap(find.byTooltip('Scan barcode'));
      await tester.pumpAndSettle();

      expect(find.text('Read off the label: lot L123 · expiry 4 Oct 2026'), findsOneWidget);
      expect(find.textContaining('2026-10-04'), findsNothing);
      expect(find.text('Blue mug · 250 ml · MUG-BLU'), findsOneWidget);
      _expectNoIds();

      await _chooseStore(tester);
      await tester.enterText(find.widgetWithText(TextFormField, 'Quantity *'), '12');
      await tester.tap(find.widgetWithText(FilledButton, 'Receive'));
      await tester.pumpAndSettle();

      final body = server.posted['inventory/receive']!;
      expect(body['variantId'], _mug);
      expect(body['batchNo'], 'L123');
      expect(body['expiryDate'], '2026-10-04');
    });

    testWidgets('with nothing to scan, the product is found by name, not typed as an id',
        (tester) async {
      final server = await _pump(tester);
      await _openReceive(tester);
      _expectNoIds();
      expect(_productField, findsOneWidget);

      await _search(tester, 'mug');
      expect(_result('Blue mug'), findsOneWidget);
      expect(
          find.descendant(of: _result('Blue mug'), matching: find.text('250 ml · SKU MUG-BLU')),
          findsOneWidget);
      // The till's own search: the catalogue, by name.
      expect(
          server.requests.any((r) =>
              r.path.endsWith('/catalog/products') && r.queryParameters['q'] == 'mug'),
          isTrue);

      await tester.tap(_result('Blue mug'));
      await tester.pumpAndSettle();
      expect(find.text('Blue mug · 250 ml · MUG-BLU'), findsOneWidget);
      _expectNoIds();

      await _chooseStore(tester);
      await tester.enterText(find.widgetWithText(TextFormField, 'Quantity *'), '12');
      await tester.tap(find.widgetWithText(FilledButton, 'Receive'));
      await tester.pumpAndSettle();
      expect(server.posted['inventory/receive']!['variantId'], _mug);
    });

    testWidgets('a product with several variants lists each, with its SKU; delisted ones not',
        (tester) async {
      await _pump(tester);
      await _openReceive(tester);
      await _search(tester, 'plate');

      expect(find.text('Dinner · SKU PLT-DN'), findsOneWidget);
      expect(find.text('Side · SKU PLT-SD'), findsOneWidget);
      expect(find.textContaining('PLT-OLD'), findsNothing);
    });

    testWidgets('an exact SKU, typed in any case, finds its variant first', (tester) async {
      final server = await _pump(tester);
      await _openReceive(tester);
      await _search(tester, 'plt-sd');

      final rows = find.byType(ListTile);
      expect(rows, findsNWidgets(2));
      expect(find.descendant(of: rows.first, matching: find.text('Side · SKU PLT-SD')),
          findsOneWidget);

      await tester.tap(rows.first);
      await tester.pumpAndSettle();
      expect(find.text('Plate · Side · PLT-SD'), findsOneWidget);
      await _chooseStore(tester);
      await tester.enterText(find.widgetWithText(TextFormField, 'Quantity *'), '3');
      await tester.tap(find.widgetWithText(FilledButton, 'Receive'));
      await tester.pumpAndSettle();
      expect(server.posted['inventory/receive']!['variantId'], _sidePlate);
    });

    testWidgets('a new line, not yet on sale, is found too, and says so', (tester) async {
      await _pump(tester);
      await _openReceive(tester);
      await _search(tester, 'teapot');

      expect(_result('Teapot'), findsOneWidget);
      expect(find.descendant(of: _result('Teapot'), matching: find.text('SKU TEA-POT · New line')),
          findsOneWidget);
    });

    testWidgets('a search that finds nothing says what it looked for', (tester) async {
      await _pump(tester);
      await _openReceive(tester);
      await _search(tester, 'zzz');

      expect(find.widgetWithText(EmptyState, 'Nothing matches “zzz”'), findsOneWidget);
      expect(find.byType(ListTile), findsNothing);
    });

    testWidgets('receiving without a product asks for one', (tester) async {
      final server = await _pump(tester);
      await _openReceive(tester);
      await _chooseStore(tester);
      await tester.enterText(find.widgetWithText(TextFormField, 'Quantity *'), '3');
      await tester.tap(find.widgetWithText(FilledButton, 'Receive'));
      await tester.pumpAndSettle();

      expect(find.text('Choose a product'), findsOneWidget);
      expect(server.posted, isEmpty);
    });

    testWidgets('on a phone at 200% text the search and its results fit', (tester) async {
      await _pump(tester, size: const Size(390, 844), textScale: 2);
      await _openReceive(tester);
      await tester.ensureVisible(_productField);
      await tester.pumpAndSettle();
      await _search(tester, 'plate');
      expect(tester.takeException(), isNull);
      expect(find.text('Side · SKU PLT-SD'), findsOneWidget);
    });
  });

  group('Set reorder level', () {
    testWidgets('from the Thresholds tab, the product is found by name, not typed as an id',
        (tester) async {
      final server = await _pump(tester);
      await tester.tap(find.widgetWithText(Tab, 'Thresholds'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Set reorder level'));
      await tester.pumpAndSettle();
      _expectNoIds();

      await _chooseStore(tester);
      await _search(tester, 'mug');
      await tester.tap(_result('Blue mug'));
      await tester.pumpAndSettle();
      expect(find.text('Blue mug · 250 ml · MUG-BLU'), findsOneWidget);

      await tester.enterText(find.widgetWithText(TextFormField, 'Threshold *'), '6');
      await tester.tap(find.widgetWithText(FilledButton, 'Save'));
      await tester.pumpAndSettle();
      final body = server.posted['inventory/thresholds']!;
      expect(body['variantId'], _mug);
      expect(body['storeId'], _store);
    });
  });
}
