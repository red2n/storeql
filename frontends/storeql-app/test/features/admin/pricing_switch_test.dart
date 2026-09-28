import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/pricing_screen.dart';

// ---------------------------------------------------------------------------
// Switching a promotion or a price list off, from the screen.
//
// SJ-D33 and SJ-D38 were the same defect twice: an `active` column the pricing
// engine reads on every basket, and nothing anywhere in the product that could
// write it. The backend fix is only half — a report with no screen is not a
// built capability, and here the missing screen control WAS the defect: the
// list showed a badge saying "Active" and offered no way to change it.
//
// These tests assert the request that actually leaves the app: the path (it
// must be the /admin/ one, or any cashier can call it), the reason (the server
// requires it in both directions), and that a switch is never sent silently
// without one.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _RecordingAdapter implements HttpClientAdapter {
  final List<RequestOptions> posts = [];
  String promotions = '{"data":[]}';
  String priceLists = '{"data":[]}';

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions options, Stream<List<int>>? stream, Future<void>? cancel) async {
    if (options.method == 'POST') {
      posts.add(options);
      return ResponseBody.fromString('{"data":{}}', 200, headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType]
      });
    }
    final body = options.path.contains('promotions') ? promotions : priceLists;
    return ResponseBody.fromString(body, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

const _promo = '''
{"id":"p-1","name":"Runaway 50%","type":"BASKET_PERCENT","value":50,
 "active":true,"startsAt":"2020-01-01T00:00:00Z","priority":100,"exclusive":false}''';

const _stoppedPromo = '''
{"id":"p-2","name":"Was stopped","type":"BASKET_FLAT","value":5,
 "active":false,"startsAt":"2020-01-01T00:00:00Z","priority":100,"exclusive":false}''';

const _priceList = '''
{"id":"pl-1","name":"Mispriced GBP","channel":"ALL","currency":"GBP",
 "active":true,"effectiveFrom":"2024-01-01T00:00:00Z"}''';

Future<_RecordingAdapter> _pump(WidgetTester tester,
    {String promotions = '{"data":[]}', String priceLists = '{"data":[]}'}) async {
  final adapter = _RecordingAdapter()
    ..promotions = promotions
    ..priceLists = priceLists;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = adapter;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: const MaterialApp(home: PricingScreen()),
  ));
  await tester.pumpAndSettle();
  return adapter;
}

Future<void> _openTab(WidgetTester tester, String tab) async {
  await tester.tap(find.text(tab));
  await tester.pumpAndSettle();
}

void main() {
  // A price list's start is shown as a date, which needs the locale's date data.
  setUpAll(initializeDateFormatting);

  testWidgets('a running promotion offers a way to stop it', (tester) async {
    await _pump(tester, promotions: '{"data":[$_promo]}');
    await _openTab(tester, 'Promotions');

    expect(find.text('Runaway 50%'), findsOneWidget);
    // The control whose absence was the defect.
    expect(find.byTooltip('Stop this promotion'), findsOneWidget);
  });

  testWidgets('stopping one sends the reason to the admin path', (tester) async {
    final adapter = await _pump(tester, promotions: '{"data":[$_promo]}');
    await _openTab(tester, 'Promotions');

    await tester.tap(find.byTooltip('Stop this promotion'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'decimal slipped');
    await tester.tap(find.widgetWithText(FilledButton, 'Stop'));
    await tester.pumpAndSettle();

    expect(adapter.posts, hasLength(1));
    // /admin/ is not decoration: on the open path any staff role could call it.
    expect(adapter.posts.single.path, '/pricing-svc/admin/promotions/p-1/deactivate');
    expect(adapter.posts.single.data, {'reason': 'decimal slipped'});
  });

  testWidgets('a switch with no reason is never sent', (tester) async {
    final adapter = await _pump(tester, promotions: '{"data":[$_promo]}');
    await _openTab(tester, 'Promotions');

    await tester.tap(find.byTooltip('Stop this promotion'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Stop'));
    await tester.pumpAndSettle();

    // The server would refuse it with a 400; not spending the round trip is the
    // point, but so is not leaving the operator guessing why nothing happened.
    expect(adapter.posts, isEmpty);
    expect(find.textContaining('Say why'), findsOneWidget);
  });

  testWidgets('a stopped promotion offers the other direction', (tester) async {
    final adapter = await _pump(tester, promotions: '{"data":[$_stoppedPromo]}');
    await _openTab(tester, 'Promotions');

    expect(find.byTooltip('Start it again'), findsOneWidget);
    await tester.tap(find.byTooltip('Start it again'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'repriced and re-approved');
    await tester.tap(find.widgetWithText(FilledButton, 'Start'));
    await tester.pumpAndSettle();

    // Restarting needs a reason too — the trail that records only stops answers
    // the easier half of "who turned this back on?".
    expect(adapter.posts.single.path, '/pricing-svc/admin/promotions/p-2/activate');
    expect(adapter.posts.single.data, {'reason': 'repriced and re-approved'});
  });

  testWidgets('a price list can be stopped the same way', (tester) async {
    final adapter = await _pump(tester, priceLists: '{"data":[$_priceList]}');

    expect(find.text('Mispriced GBP'), findsOneWidget);
    await tester.tap(find.byTooltip('Stop this price list'));
    await tester.pumpAndSettle();

    // A price list is not a discount — the warning has to say what stopping it
    // actually costs, which is the ability to sell those items at all.
    expect(find.textContaining('cannot be sold'), findsOneWidget);

    await tester.enterText(find.byType(TextField), 'price nobody agreed');
    await tester.tap(find.widgetWithText(FilledButton, 'Stop'));
    await tester.pumpAndSettle();

    expect(adapter.posts.single.path, '/pricing-svc/admin/price-lists/pl-1/deactivate');
    expect(adapter.posts.single.data, {'reason': 'price nobody agreed'});
  });

  testWidgets('creating a price list sends an instant, not a bare date', (tester) async {
    final adapter = await _pump(tester);

    await tester.tap(find.text('New price list'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Name *'), 'Standard GBP');
    await tester.tap(find.widgetWithText(FilledButton, 'Create'));
    await tester.pumpAndSettle();

    expect(adapter.posts, hasLength(1));
    final post = adapter.posts.single;
    // Management-only, like every other price write.
    expect(post.path, '/pricing-svc/admin/price-lists');

    // The column is TIMESTAMPTZ and the server answers a bare '2026-01-01' with
    // INVALID_DATE — so this dialog had never once created a price list. UTC,
    // per golden rule 14: convert at the UI edge, store the instant.
    final from = (post.data as Map)['effectiveFrom'] as String;
    expect(from, endsWith('Z'));
    expect(from, contains('T00:00:00'));
    expect(DateTime.parse(from).isUtc, isTrue);
  });
}
