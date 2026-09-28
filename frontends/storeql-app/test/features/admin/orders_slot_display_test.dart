import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/orders_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The window an order holds (delivery-and-collection-slots) on the back
// office's Orders screen: the store's own local date and clock, exactly as
// the server sent them — two stores in two zones each show their own, with
// no conversion in common.
// ---------------------------------------------------------------------------

const _london = '01a0f200-0000-7000-8000-00000000a001';
const _warsaw = '01a0f200-0000-7000-8000-00000000a002';
const _noSlot = '01a0f200-0000-7000-8000-00000000a003';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async {
    requests.add(o);
    if (o.path.endsWith('/order-svc/orders') && o.method == 'GET') {
      return jsonResponse(
        '{"data":['
        '{"id":"$_london","storeId":"store-ldn","channel":"ONLINE","fulfilmentType":"DELIVERY",'
        '"status":"CONFIRMED","total":12.5,"currency":"GBP","createdAt":"2026-09-25T09:00:00Z",'
        '"slot":{"date":"2026-09-27","startTime":"17:00","endTime":"19:00","timeZone":"Europe/London"}},'
        '{"id":"$_warsaw","storeId":"store-waw","channel":"ONLINE","fulfilmentType":"PICKUP",'
        '"status":"CONFIRMED","total":8.0,"currency":"PLN","createdAt":"2026-09-25T09:05:00Z",'
        '"slot":{"date":"2026-09-27","startTime":"18:00","endTime":"20:00","timeZone":"Europe/Warsaw"}},'
        '{"id":"$_noSlot","storeId":"store-ldn","channel":"ONLINE","fulfilmentType":"PICKUP",'
        '"status":"CONFIRMED","total":3.0,"currency":"GBP","createdAt":"2026-09-25T09:10:00Z"}'
        '],"meta":{"nextCursor":null}}',
      );
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<void> _pump(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1200, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = _Server();
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(FakeApiClient(dio)),
        authNotifierProvider.overrideWith(() => RoleAuth('OWNER')),
      ],
      child: const MaterialApp(home: Scaffold(body: AdminOrdersScreen())),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  setUpAll(initializeDateFormatting);
  // The windows below are read the British way (Sun 27 Sept): pinned, since the
  // app itself assumes no country for English.
  setUp(() => Intl.defaultLocale = 'en_GB');

  testWidgets(
    'each order shows its own store\'s window, in its own local clock',
    (tester) async {
      await _pump(tester);

      // Worded with which kind of window it is, and the weekday, through
      // AppFormat in the app's language.
      expect(find.textContaining('Delivery · Sun 27 Sept, 17:00–19:00'),
          findsOneWidget);
      expect(find.textContaining('Collection · Sun 27 Sept, 18:00–20:00'),
          findsOneWidget);
      expect(find.textContaining('2026-09-27'), findsNothing);
    },
  );

  testWidgets('an order with no window shows none — only the two with one do', (
    tester,
  ) async {
    await _pump(tester);

    // Three orders, two with a window: exactly two occurrences of a window
    // line, never a third for the one placed with no slot at all.
    expect(find.textContaining('Delivery · Sun 27 Sept, 17:00–19:00'),
        findsOneWidget);
    expect(find.textContaining('Collection · Sun 27 Sept, 18:00–20:00'),
        findsOneWidget);
    expect(find.textContaining('–'), findsNWidgets(2));
  });
}
