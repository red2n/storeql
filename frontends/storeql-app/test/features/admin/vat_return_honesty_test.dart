import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/pricing_screen.dart';

// ---------------------------------------------------------------------------
// SJ-D39: the VAT return screen presents nine boxes in the shape of an HMRC
// return and only five are computed. It must say so on its face — a "0.00" in
// box 4 reads as a figure, and box 5 built on it is overstated by exactly the
// VAT the business may reclaim.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final bool honest;
  _Server({required this.honest});

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    String body = '{"data":[]}';
    if (o.path.endsWith('/vat-return')) {
      body = honest
          ? '{"data":{"box1":40.00,"box2":0,"box3":40.00,"box4":30.00,"box5":10.00,"box6":200.00,"box7":150.00,"box8":0,"box9":0,'
              '"computedBoxes":[1,3,4,5,6,7],"notComputedBoxes":[2,8,9],"fitToFile":true,'
              '"caveat":"Boxes 4 and 7 come from the supplier invoices purchasing captured. Boxes 2, 8 and 9 are zero because no Northern Ireland protocol trade is modelled."}}'
          : '{"data":{"box1":40.00,"box2":0,"box3":40.00,"box4":0,"box5":40.00,"box6":200.00,"box7":0,"box8":0,"box9":0}}';
    }
    return ResponseBody.fromString(body, 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Future<void> _pump(WidgetTester tester, {required bool honest}) async {
  tester.view.physicalSize = const Size(1200, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = _Server(honest: honest);
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: const MaterialApp(home: PricingScreen()),
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.text('VAT Return'));
  await tester.pumpAndSettle();
}

void main() {
  setUpAll(initializeDateFormatting);
  testWidgets('with box 4 real, the return is fit to file and says what the zero boxes assume',
      (tester) async {
    await _pump(tester, honest: true);
    expect(find.byKey(const Key('vat-return-caveat')), findsNothing,
        reason: 'no red notice once every computed box is real');
    expect(find.byKey(const Key('vat-return-note')), findsOneWidget);
    expect(find.textContaining('Northern Ireland'), findsWidgets);
    // Box 4 carries the reclaim, box 7 the net purchases; 2, 8 and 9 are not modelled.
    expect(find.text('£30.00'), findsOneWidget);
    expect(find.text('£150.00'), findsOneWidget);
    expect(find.byKey(const Key('vat-box-2-not-computed')), findsOneWidget);
    expect(find.byKey(const Key('vat-box-8-not-computed')), findsOneWidget);
    expect(find.byKey(const Key('vat-box-9-not-computed')), findsOneWidget);
    expect(find.byKey(const Key('vat-box-4-not-computed')), findsNothing);
    expect(find.textContaining('Not modelled'), findsNWidgets(3));
    expect(find.textContaining('Not computed'), findsNothing);
  });

  testWidgets('an older server that does not say defaults to not fit to file', (tester) async {
    await _pump(tester, honest: false);
    expect(find.byKey(const Key('vat-return-caveat')), findsOneWidget);
  });
}
