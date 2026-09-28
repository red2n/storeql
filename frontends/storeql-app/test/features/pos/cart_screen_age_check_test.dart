import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/pos/cart_screen.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// The age check at the register, driven the way a cashier drives it: scan a
// barcode, and see whether the item reaches the sale.
//
// product-svc has known which items are age-restricted since 69f2d03, and the
// till never asked. Every test here is about the moment between the scan and
// the item landing in the basket.
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

/// Routes the three calls a scan makes. Age-check answers are set per variant.
class _Till implements HttpClientAdapter {
  final Map<String, (int, String)> ageCheck = {};
  final List<RequestOptions> requests = [];
  bool failRecord = false;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    var status = 200;
    var body = '{"data":[]}';
    if (o.path.contains('/catalog/scan')) {
      // /catalog/scan (07.15): one route for every kind of code, with the code in a query
      // parameter because a GS1 Digital Link is a URI, and the item wrapped in `item` beside
      // what the code itself carried in `code`.
      final code = '${o.queryParameters['code']}';
      body = '{"data":{"item":{"variantId":"v-$code","sku":"$code","productName":"Item $code"}}}';
    } else if (o.path.contains('/prices/resolve')) {
      body = '{"data":{"unitPrice":8.0,"currency":"GBP"}}';
    } else if (o.path.endsWith('/compliance')) {
      // Every item here is sold by the each; the till now asks how an item is
      // sold, and refuses to guess when the answer is unreadable.
      body = '{"data":{"soldBy":"EACH","catchWeight":false}}';
    } else if (o.path.endsWith('/pos/age-checks')) {
      if (failRecord) status = 503;
      body = failRecord
          ? '{"error":{"code":"SERVICE_UNAVAILABLE","message":"down"}}'
          : '{"data":{"id":"chk-1","outcome":"REFUSED"}}';
    } else if (o.path.endsWith('/age-check')) {
      final variant = o.path.split('/')[o.path.split('/').length - 2];
      final answer = ageCheck[variant] ?? (200, '{"data":{"restricted":false}}');
      status = answer.$1;
      body = answer.$2;
    }
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

const _alcohol18 =
    (200, '{"data":{"restricted":true,"category":"ALCOHOL","minimumAge":18,"country":"GB","tenantOverride":false}}');

Future<_Till> _pump(WidgetTester tester) async {
  tester.view.physicalSize = const Size(700, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  final till = _Till();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = till;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
      posStoreProvider.overrideWith((ref) => 'store-1'),
      posStoresProvider.overrideWith((ref) async => const [
            StoreInfo(
                id: 'store-1',
                name: 'High Street',
                code: 'HS',
                type: 'STORE',
                status: 'ACTIVE',
                country: 'GB'),
          ]),
    ],
    child: const MaterialApp(home: Scaffold(body: PosCartScreen())),
  ));
  await tester.pumpAndSettle();
  return till;
}

Future<void> _scan(WidgetTester tester, String code) async {
  await tester.enterText(
      find.widgetWithText(TextField, 'Scan barcode or type SKU…'), code);
  await tester.testTextInput.receiveAction(TextInputAction.done);
  await tester.pumpAndSettle();
}

List<PosLine> _basket(WidgetTester tester) =>
    ProviderScope.containerOf(tester.element(find.byType(PosCartScreen).first))
        .read(posCartProvider);

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  // The cut-off is shown as a date in the app's own locale, as the app does.
  setUpAll(initializeDateFormatting);

  testWidgets('an unrestricted item goes straight in, with no prompt',
      (tester) async {
    await _pump(tester);
    await _scan(tester, 'BREAD');

    expect(find.text('Age-restricted item'), findsNothing);
    expect(_basket(tester), hasLength(1));
  });

  testWidgets('a restricted item waits for the check before it is added',
      (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = _alcohol18;
    await _scan(tester, 'WINE');

    expect(find.text('Age-restricted item'), findsOneWidget);
    // Not in the basket while the cashier is still deciding.
    expect(_basket(tester), isEmpty);

    await tester.tap(find.text('Checked — 18+'));
    await tester.pumpAndSettle();
    expect(_basket(tester), hasLength(1));
  });

  testWidgets('refusing the sale keeps the item out', (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = _alcohol18;
    await _scan(tester, 'WINE');

    await tester.tap(find.text('Refuse sale'));
    await tester.pumpAndSettle();
    expect(_basket(tester), isEmpty);
  });

  testWidgets('the store country is the one whose law is asked about',
      (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = _alcohol18;
    await _scan(tester, 'WINE');

    final ask = till.requests.lastWhere((r) => r.path.endsWith('/age-check'));
    expect(ask.queryParameters['country'], 'GB');
  });

  testWidgets('one check covers the sale for the same age', (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = _alcohol18;
    till.ageCheck['v-BEER'] = _alcohol18;

    await _scan(tester, 'WINE');
    await tester.tap(find.text('Checked — 18+'));
    await tester.pumpAndSettle();

    // A customer already shown to be 18 is not asked again for the next bottle.
    await _scan(tester, 'BEER');
    expect(find.text('Age-restricted item'), findsNothing);
    expect(_basket(tester), hasLength(2));
  });

  testWidgets('a higher age asks again', (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = _alcohol18;
    till.ageCheck['v-LOTTO'] = (
      200,
      '{"data":{"restricted":true,"category":"ALCOHOL","minimumAge":21,"country":"GB","tenantOverride":true}}'
    );

    await _scan(tester, 'WINE');
    await tester.tap(find.text('Checked — 18+'));
    await tester.pumpAndSettle();

    await _scan(tester, 'LOTTO');
    expect(find.text('Checked — 21+'), findsOneWidget);
  });

  testWidgets("a check that can't be made keeps the item out and says why",
      (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = (503, '{"error":{"code":"UPSTREAM_UNAVAILABLE","details":[]}}');
    await _scan(tester, 'WINE');

    // No prompt to click through: there is nothing the cashier could confirm.
    expect(find.text('Age-restricted item'), findsNothing);
    expect(_basket(tester), isEmpty);
    expect(find.textContaining("Couldn't check the age restriction"), findsOneWidget);
  });

  // ── the due-diligence record ───────────────────────────────────────────────

  RequestOptions? recorded(_Till till) {
    final hits = till.requests.where((r) => r.path.endsWith('/pos/age-checks'));
    return hits.isEmpty ? null : hits.last;
  }

  testWidgets('a refusal must say why, and is then written down with its reason',
      (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = _alcohol18;
    await _scan(tester, 'WINE');

    await tester.tap(find.text('Refuse sale'));
    await tester.pumpAndSettle();
    // No reason chosen: the refusal cannot be recorded yet, and nothing was sent.
    final record = tester.widget<FilledButton>(
        find.widgetWithText(FilledButton, 'Record refusal'));
    expect(record.onPressed, isNull);
    expect(recorded(till), isNull);
    expect(_basket(tester), isEmpty);

    await tester.tap(find.text('No ID shown'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Record refusal'));
    await tester.pumpAndSettle();

    expect(_basket(tester), isEmpty);
    final sent = recorded(till)!;
    final body = sent.data as Map<String, dynamic>;
    expect(body['outcome'], 'REFUSED');
    expect(body['reason'], 'NO_ID');
    expect(body['category'], 'ALCOHOL');
    expect(body['minimumAge'], 18);
    expect(body['country'], 'GB');
    expect(body['storeId'], 'store-1');
    expect(body.containsKey('idType'), isFalse);
  });

  testWidgets('a pass is written down, with what was shown when the cashier notes it',
      (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = _alcohol18;
    await _scan(tester, 'WINE');

    await tester.tap(find.text('Driving licence'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Checked — 18+'));
    await tester.pumpAndSettle();

    expect(_basket(tester), hasLength(1));
    final body = recorded(till)!.data as Map<String, dynamic>;
    expect(body['outcome'], 'PASSED');
    expect(body['idType'], 'DRIVING_LICENCE');
    expect(body.containsKey('reason'), isFalse);
  });

  testWidgets('a pass with nothing noted is still written down', (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = _alcohol18;
    await _scan(tester, 'WINE');
    await tester.tap(find.text('Checked — 18+'));
    await tester.pumpAndSettle();

    final body = recorded(till)!.data as Map<String, dynamic>;
    expect(body['outcome'], 'PASSED');
    expect(body.containsKey('idType'), isFalse);
  });

  testWidgets('a refusal stands even when the record cannot be written',
      (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = _alcohol18;
    till.failRecord = true;
    await _scan(tester, 'WINE');

    await tester.tap(find.text('Refuse sale'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Under age'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Record refusal'));
    await tester.pumpAndSettle();

    expect(_basket(tester), isEmpty);
    expect(find.textContaining('could not be recorded'), findsOneWidget);
  });

  testWidgets('one check still covers the sale: the second bottle writes no second record',
      (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = _alcohol18;
    till.ageCheck['v-BEER'] = _alcohol18;
    await _scan(tester, 'WINE');
    await tester.tap(find.text('Checked — 18+'));
    await tester.pumpAndSettle();
    await _scan(tester, 'BEER');

    expect(till.requests.where((r) => r.path.endsWith('/pos/age-checks')).length, 1);
  });

  // ── 10.8: a date of birth, not an age ─────────────────────────────────────

  const tobaccoCutoff = (
    200,
    '{"data":{"restricted":true,"category":"TOBACCO","minimumAge":18,"country":"GB",'
        '"tenantOverride":false,"bornBefore":"2009-01-01","bornBeforeTenantOverride":false}}'
  );

  testWidgets('a pass for 18 does not cover tobacco under a birth-date cut-off',
      (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = _alcohol18;
    till.ageCheck['v-CIGS'] = tobaccoCutoff;
    await _scan(tester, 'WINE');
    await tester.tap(find.text('Checked — 18+'));
    await tester.pumpAndSettle();

    // Shown to be 18 says nothing about the year they were born.
    await _scan(tester, 'CIGS');
    expect(find.text('Checked — 18+, born before 1 Jan 2009'), findsOneWidget);
    expect(_basket(tester), hasLength(1));
    await tester.tap(find.text('Checked — 18+, born before 1 Jan 2009'));
    await tester.pumpAndSettle();
    expect(_basket(tester), hasLength(2));

    final body = recorded(till)!.data as Map<String, dynamic>;
    expect(body['category'], 'TOBACCO');
    expect(body['bornBefore'], '2009-01-01');
    expect(body['bornBeforeStorePolicy'], isFalse);
  });

  testWidgets('a refusal for the date of birth is written down with the cut-off',
      (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-CIGS'] = tobaccoCutoff;
    await _scan(tester, 'CIGS');
    await tester.tap(find.text('Refuse sale'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Born on or after the cut-off date'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Record refusal'));
    await tester.pumpAndSettle();

    expect(_basket(tester), isEmpty);
    final body = recorded(till)!.data as Map<String, dynamic>;
    expect(body['outcome'], 'REFUSED');
    expect(body['reason'], 'BORN_AFTER_CUTOFF');
    expect(body['bornBefore'], '2009-01-01');
    // The customer's date of birth is never sent anywhere.
    expect(body.keys.where((k) => k.toLowerCase().contains('birth') && k != 'bornBefore'
        && k != 'bornBeforeStorePolicy'), isEmpty);
  });

  testWidgets('one passed cut-off check covers the next pack in the same sale',
      (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-CIGS'] = tobaccoCutoff;
    till.ageCheck['v-CIGAR'] = tobaccoCutoff;
    await _scan(tester, 'CIGS');
    await tester.tap(find.text('Checked — 18+, born before 1 Jan 2009'));
    await tester.pumpAndSettle();
    await _scan(tester, 'CIGAR');

    expect(find.text('Age-restricted item'), findsNothing);
    expect(_basket(tester), hasLength(2));
    expect(till.requests.where((r) => r.path.endsWith('/pos/age-checks')).length, 1);
  });
}
