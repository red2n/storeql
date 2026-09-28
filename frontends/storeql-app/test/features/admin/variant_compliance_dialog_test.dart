import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/admin/variant_compliance_dialog.dart';

// ---------------------------------------------------------------------------
// The allergens and origin dialog. The rule it exists to keep: saving never
// declares allergens by accident, because an empty declaration is a positive
// statement that a product contains none of the fourteen.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Product implements HttpClientAdapter {
  String status = 'NOT_APPLICABLE';
  String declared = '[]';
  String compliance = '{"soldBy":"EACH","catchWeight":false}';
  final Map<String, Object?> puts = {};

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    var body = '{"data":{}}';
    if (o.method == 'PUT') {
      puts[o.path.split('/').last] = o.data;
    } else if (o.path.endsWith('/catalog/allergens')) {
      body = '{"data":[{"code":"MILK","name":"Milk"},{"code":"NUTS","name":"Tree nuts"},'
          '{"code":"CELERY","name":"Celery"}]}';
    } else if (o.path.endsWith('/allergens')) {
      body = '{"data":{"variantId":"v-1","status":"$status","allergens":$declared}}';
    } else if (o.path.endsWith('/compliance')) {
      body = '{"data":$compliance}';
    }
    return ResponseBody.fromString(body, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Future<_Product> _open(WidgetTester tester, {_Product? product, String? tenantCountry}) async {
  tester.view.physicalSize = const Size(1400, 2000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  final p = product ?? _Product();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = p;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      if (tenantCountry != null)
        tenantInfoProvider.overrideWith((ref) async => TenantInfo(
              id: 't-1',
              name: 'Test',
              status: 'ACTIVE',
              currency: '',
              country: tenantCountry,
            )),
    ],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<bool>(
              context: context,
              builder: (_) => const VariantComplianceDialog(
                variant: VariantInfo(
                    id: 'v-1', productId: 'p-1', sku: 'FLAP-1', status: 'ACTIVE'),
              ),
            ),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  return p;
}

Finder _segment(String code, String label) =>
    find.descendant(of: find.byKey(Key('allergen-$code')), matching: find.text(label));

Map<String, dynamic> _json(Object? data) =>
    (data is String ? jsonDecode(data) : data) as Map<String, dynamic>;

Finder _originField() =>
    find.byWidgetPredicate((w) => w is TextField && w.decoration?.labelText == 'Country');

void main() {
  testWidgets('an undeclared food item says it is on the gaps list', (tester) async {
    await _open(tester, product: _Product()..status = 'UNDECLARED');
    expect(find.textContaining('on the allergen gaps list'), findsOneWidget);
    expect(find.text('Milk'), findsOneWidget);
  });

  testWidgets('saving without the check declares nothing', (tester) async {
    final p = await _open(tester);
    await tester.tap(find.text('Food product'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    expect(_json(p.puts['compliance'])['food'], isTrue);
    // No allergen PUT: an empty one would declare the product free from all fourteen.
    expect(p.puts.containsKey('allergens'), isFalse);
  });

  testWidgets('declaring none takes the explicit check, then sends an empty declaration',
      (tester) async {
    final p = await _open(tester, product: _Product()..status = 'UNDECLARED');
    await tester.tap(find.text('I have checked the label: it contains none of the 14 allergens'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Save and declare'));
    await tester.pumpAndSettle();

    expect(_json(p.puts['allergens'])['allergens'], isEmpty);
  });

  testWidgets('contains and may-contain are sent as chosen', (tester) async {
    final p = await _open(tester, product: _Product()..status = 'UNDECLARED');
    await tester.tap(_segment('MILK', 'Contains'));
    await tester.tap(_segment('NUTS', 'May contain'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('I have checked this declaration against the label'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Save and declare'));
    await tester.pumpAndSettle();

    final sent = (_json(p.puts['allergens'])['allergens'] as List).cast<Map>();
    expect(sent, containsAll([
      {'code': 'MILK', 'presence': 'CONTAINS'},
      {'code': 'NUTS', 'presence': 'MAY_CONTAIN'},
    ]));
    expect(sent, hasLength(2));
  });

  testWidgets('changing an answer after checking takes the check back', (tester) async {
    await _open(tester, product: _Product()..status = 'UNDECLARED');
    await tester.tap(find.text('I have checked the label: it contains none of the 14 allergens'));
    await tester.pumpAndSettle();
    expect(find.text('Save and declare'), findsOneWidget);

    await tester.tap(_segment('CELERY', 'Contains'));
    await tester.pumpAndSettle();
    // What was checked is no longer what would be sent.
    expect(find.text('Save and declare'), findsNothing);
    expect(find.text('Save'), findsOneWidget);
  });

  testWidgets('saving sends every compliance field, so none is wiped', (tester) async {
    final p = await _open(
        tester,
        product: _Product()
          ..compliance = '{"countryOfOrigin":"ES","originDetail":"Produce of Spain",'
              '"soldBy":"WEIGHT","netContentUom":"KG","catchWeight":false,'
              '"restrictionCategory":"ALCOHOL"}');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    final body = _json(p.puts['compliance']);
    expect(body['countryOfOrigin'], 'ES');
    expect(body['originDetail'], 'Produce of Spain');
    expect(body['soldBy'], 'WEIGHT');
    expect(body['netContentUom'], 'KG');
    expect(body['restrictionCategory'], 'ALCOHOL');
  });

  testWidgets('the drinks container is sent as material and volume (09.16)', (tester) async {
    final p = await _open(tester);
    await tester.dragUntilVisible(
      find.byKey(const Key('deposit-material')),
      find.byType(SingleChildScrollView).first,
      const Offset(0, -200),
    );
    await tester.tap(find.byKey(const Key('deposit-material')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('PET plastic').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('deposit-volume')), '500');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    final sent = _json(p.puts['compliance']);
    expect(sent['depositMaterial'], 'PET');
    expect(sent['depositVolumeMl'], 500);
  });

  testWidgets('a recorded container is shown, and clearing it sends neither', (tester) async {
    final p = await _open(
        tester,
        product: _Product()
          ..compliance =
              '{"variantId":"v-1","soldBy":"EACH","catchWeight":false,"depositMaterial":"GLASS","depositVolumeMl":330}');
    await tester.dragUntilVisible(
      find.byKey(const Key('deposit-material')),
      find.byType(SingleChildScrollView).first,
      const Offset(0, -200),
    );
    expect(find.text('Glass'), findsOneWidget);
    expect(find.text('330'), findsOneWidget);
    await tester.tap(find.byKey(const Key('deposit-material')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Not a drinks container').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('deposit-volume')), '');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    final sent = _json(p.puts['compliance']);
    expect(sent['depositMaterial'], isNull);
    expect(sent['depositVolumeMl'], isNull);
  });

  // ── Origin country: a hint, never a default (SJ-D67) ────────────────────

  testWidgets('the origin country hints the business\'s own country, never a fixed one',
      (tester) async {
    await _open(tester, tenantCountry: 'IN');
    expect(tester.widget<TextField>(_originField()).decoration?.hintText, 'IN');
  });

  testWidgets('an unknown business country hints nothing, rather than a fixed default',
      (tester) async {
    await _open(tester, tenantCountry: '');
    expect(tester.widget<TextField>(_originField()).decoration?.hintText, isNull);
  });
}
