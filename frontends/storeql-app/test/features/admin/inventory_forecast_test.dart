import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/inventory_forecast_tab.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// The demand forecast tab (06.x): a store's forecasts read with their method and
// accuracy (a dash where the server could not compute one), the run posted for
// the store and its summary shown, and a row opened day by day.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

const _steady = 'v-steady-0001';
const _rare = 'v-rare-00002';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  bool hasForecasts = true;

  @override
  void close({bool force = false}) {}

  static String _row(String variant, String method, bool intermittent, String mape) =>
      '{"id":"f-$variant","storeId":"st-1","variantId":"$variant","method":"$method","intermittent":$intermittent,'
      '${intermittent ? '"fresh":true,"shelfLifeDays":5,"wasteRatePct":1.10,"maxCoverDays":5,"uplift":2.5,"upliftSource":"ITEM","promotedHistoryDays":14,"promotedAheadDays":7,' : '"fresh":false,"seasonalIndices":[0.92,0.92,0.92,0.92,0.92,0.92,0.92,0.92,0.92,0.92,0.92,1.85],'}'
      '"alpha":0.2,"level":7.0,"weekdayProfile":[0.9,0.9,0.9,0.9,1.0,1.3,1.1],"historyFrom":"2026-07-25","historyTo":"2026-09-22",'
      '"historyDays":60,"horizonDays":28,"fromDay":"2026-09-23","next7":49.0,"next28":196.0,"holdoutDays":15,'
      '"mape":$mape,"bias":-2.5,"mase":0.8,"points":[],"computedAt":"2026-09-23T06:00:00Z"}';

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    var body = '{"data":[]}';
    if (o.path.endsWith('/forecasts/run') && o.method == 'POST') {
      body = '{"data":{"storeId":"st-1","variants":2,"byMethod":{"CROSTON_SBA":1,"SES":1},"meanMape":12.5,"horizonDays":28,"computedAt":"2026-09-23T06:00:00Z"}}';
    } else if (o.path.endsWith('/forecasts/st-1/$_steady')) {
      body = '{"data":${_row(_steady, 'SES', false, '12.50').replaceFirst('"points":[]', '"points":[{"day":"2026-09-23","qty":6.3},{"day":"2026-09-24","qty":6.3},{"day":"2026-09-26","qty":9.1}]')}}';
    } else if (o.path.endsWith('/forecasts')) {
      body = hasForecasts
          ? '{"data":[${_row(_steady, 'SES', false, '12.50')},${_row(_rare, 'CROSTON_SBA', true, 'null')}]}'
          : '{"data":[]}';
    }
    return ResponseBody.fromString(body, 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Future<_Server> _pump(WidgetTester tester, {bool hasForecasts = true}) async {
  tester.view.physicalSize = const Size(1400, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final server = _Server()..hasForecasts = hasForecasts;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
        storesProvider.overrideWith(
          (ref) async => const [
            StoreInfo(id: 'st-1', name: 'High Street', code: 'HS', type: 'STORE', status: 'ACTIVE', country: 'GB'),
          ],
        ),
      ],
      child: const MaterialApp(home: Scaffold(body: InventoryForecastTab())),
    ),
  );
  await tester.pumpAndSettle();
  return server;
}

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  setUpAll(initializeDateFormatting);
  testWidgets('a store with no forecasts says so and offers the run', (tester) async {
    await _pump(tester, hasForecasts: false);
    expect(find.textContaining('No forecasts for this store yet'), findsOneWidget);
    expect(find.byKey(const Key('forecast-run')), findsOneWidget);
  });

  testWidgets('each item shows its method and accuracy, with a dash where the server had none', (tester) async {
    await _pump(tester);
    // Methods in words, not their codes.
    expect(find.text('Smoothing (SES)'), findsOneWidget);
    expect(find.text('Croston (SBA)'), findsOneWidget);
    expect(find.text('CROSTON_SBA'), findsNothing);
    expect(find.text('196.0'), findsNWidgets(2), reason: 'expected demand over 28 days, both rows');
    expect(find.text('12.5'), findsOneWidget, reason: 'the steady seller\'s MAPE');
    expect(find.text('—'), findsOneWidget, reason: 'the intermittent row cannot compute a MAPE: a dash, never a zero');
    expect(find.byIcon(Icons.scatter_plot_outlined), findsOneWidget, reason: 'the intermittent item is marked');
    expect(find.text('Fresh · 5 d'), findsOneWidget, reason: 'the fresh item wears its shelf life');
    expect(find.text('Promo ×2.5 · 7 d'), findsOneWidget,
        reason: 'the promoted item wears the uplift and the days a promotion will run');
    expect(find.byIcon(Icons.calendar_month_outlined), findsOneWidget,
        reason: "the item with thirteen months of history wears the year's shape");
    expect(find.text('60 d'), findsNWidgets(2));
  });

  testWidgets('Run forecast posts the store and a four-week horizon, then says what it did', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('forecast-run')));
    await tester.pumpAndSettle();
    final run = server.requests.where((r) => r.path.endsWith('/forecasts/run')).single;
    final body = run.data is String ? jsonDecode(run.data as String) : run.data;
    expect(body['storeId'], 'st-1');
    expect(body['horizonDays'], 28);
    expect(find.textContaining('Forecast 2 variants'), findsOneWidget);
    expect(find.textContaining('mean MAPE 12.5%'), findsOneWidget);
  });

  testWidgets('a row opens the forecast day by day with its weekday profile', (tester) async {
    await _pump(tester);
    await tester.tap(find.text('Smoothing (SES)'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Weekday profile'), findsOneWidget);
    expect(find.text('26 Sept 2026'), findsOneWidget);
    expect(find.text('2026-09-26'), findsNothing);
    expect(find.text('9.1'), findsOneWidget);
    expect(find.textContaining('MAPE 12.5%'), findsOneWidget);
  });
}
