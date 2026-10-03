import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
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

  // ── Net content, tare and volume: read as typed, or refused ─────────────
  //
  // This save replaces every detail, so a figure the dialog could not read
  // was sent as none and wiped: Romanian's 0,5 kg of net content, and the unit
  // price a shelf edge must show with it. Each is read the way the app's
  // language writes a number and sent as the figure typed, or refused under
  // its field with nothing saved.
  group('net content, tare and volume are read as typed, or refused', () {
    tearDown(() => Intl.defaultLocale = null);

    String? says(WidgetTester tester, String key) =>
        tester.widget<TextField>(find.byKey(Key(key))).decoration?.errorText;

    Future<void> type(WidgetTester tester, String key, String text) async {
      for (var i = 1; i <= text.length; i++) {
        await tester.enterText(find.byKey(Key(key)), text.substring(0, i));
        await tester.pump();
      }
    }

    for (final (locale, net, tare, sentNet, sentTare) in [
      ('ro', '0,5', '0,025', '0.5', '0.025'),
      ('en_GB', '0.5', '0.025', '0.5', '0.025'),
      ('en', '1.125', '0.025', '1.125', '0.025'),
      ('pl', '1,125', '0.025', '1.125', '0.025'),
      ('ar', '0\u066B5', '0\u066B025', '0.5', '0.025'),
    ]) {
      testWidgets('in $locale, $net net and $tare tare are saved as $sentNet and $sentTare',
          (tester) async {
        Intl.defaultLocale = locale;
        final p = await _open(tester);
        await type(tester, 'net-content', net);
        await type(tester, 'tare-weight', tare);
        await type(tester, 'deposit-volume', '330');
        for (final f in ['net-content', 'tare-weight', 'deposit-volume']) {
          expect(says(tester, f), isNull, reason: f);
        }
        await tester.tap(find.text('Save'));
        await tester.pumpAndSettle();
        final sent = _json(p.puts['compliance']);
        expect(sent['netContent'], sentNet);
        expect(sent['tareWeight'], sentTare);
        expect(sent['depositVolumeMl'], 330);
      });
    }

    // What the item already holds is written the way the language writes a
    // number, so saving something else leaves it as it was.
    for (final (locale, shownNet, shownTare) in [
      ('ro', '0,5', '0,025'),
      ('pl', '0,5', '0,025'),
      ('en', '0.5', '0.025'),
    ]) {
      testWidgets('in $locale, a held 0.5 net and 0.025 tare show as $shownNet and $shownTare and are kept',
          (tester) async {
        Intl.defaultLocale = locale;
        final p = await _open(
            tester,
            product: _Product()
              ..compliance = '{"soldBy":"WEIGHT","netContent":0.5,"netContentUom":"KG",'
                  '"tareWeight":0.025,"catchWeight":false,"depositMaterial":"GLASS","depositVolumeMl":330}');
        expect(tester.widget<TextField>(find.byKey(const Key('net-content'))).controller!.text, shownNet);
        expect(tester.widget<TextField>(find.byKey(const Key('tare-weight'))).controller!.text, shownTare);
        for (final f in ['net-content', 'tare-weight', 'deposit-volume']) {
          expect(says(tester, f), isNull, reason: f);
        }
        await tester.tap(find.text('Save'));
        await tester.pumpAndSettle();
        final sent = _json(p.puts['compliance']);
        expect(sent['netContent'], '0.5');
        expect(sent['tareWeight'], '0.025');
        expect(sent['depositVolumeMl'], 330);
      });
    }

    for (final (locale, field, typed, why) in [
      ('ro', 'net-content', '1.250',
          'Type the amount without thousands separators. Decimals go after a comma.'),
      ('en_GB', 'net-content', '0,5',
          'Type the amount without thousands separators. Decimals go after a point.'),
      ('en', 'tare-weight', '0,025',
          'Type the amount without thousands separators. Decimals go after a point.'),
      ('pl', 'net-content', '1.250',
          'A point may group thousands here. Type the figure without grouping, with any decimals after a comma.'),
      ('ar', 'tare-weight', '-1', 'Type the amount without a sign.'),
      ('en_GB', 'net-content', '.', 'Type the amount in digits.'),
      ('ro', 'tare-weight', ',', 'Type the amount in digits.'),
      ('ar', 'net-content', '\u066B', 'Type the amount in digits.'),
      ('en', 'deposit-volume', '330.', 'Whole amounts only.'),
      ('en', 'deposit-volume', '-', 'Type the amount without a sign.'),
      ('en', 'net-content', '5e2', 'Only digits and a decimal point.'),
    ]) {
      testWidgets('in $locale, "$typed" in $field is refused under it and nothing is saved',
          (tester) async {
        Intl.defaultLocale = locale;
        final p = await _open(
            tester,
            product: _Product()
              ..compliance = '{"soldBy":"WEIGHT","netContent":0.5,"netContentUom":"KG",'
                  '"tareWeight":0.025,"catchWeight":false}');
        await tester.enterText(find.byKey(Key(field)), typed);
        await tester.pump();
        expect(says(tester, field), why);
        await tester.tap(find.text('Save'));
        await tester.pumpAndSettle();
        expect(p.puts, isEmpty, reason: 'never sent as none, which wipes what the item holds');
        expect(find.text('A figure cannot be read. Correct the one marked.'), findsOneWidget);
      });
    }
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
