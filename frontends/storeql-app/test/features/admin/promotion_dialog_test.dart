import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/pricing_screen.dart';

// ---------------------------------------------------------------------------
// Promotion scoping (03.8) on screen. Every promotion used to be scoped to the
// whole shop silently; the dialog now asks where a deal applies — everything,
// one category, one variant — and offers the seventh type, any N for a price.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> posts = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    if (o.method == 'POST') {
      posts.add(o);
      final body = o.path.endsWith('/admin/promotions')
          ? '{"data":{"id":"p-new","name":"x","type":"MIX_MATCH","value":4,"active":true,"startsAt":"2020-01-01T00:00:00Z","priority":10,"exclusive":false}}'
          : '{"data":{}}';
      return ResponseBody.fromString(body, 201,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
    }
    String body = '{"data":[]}';
    if (o.path.endsWith('/admin/categories')) {
      body = '{"data":[{"id":"cat-drinks","name":"Drinks","status":"ACTIVE","createdAt":""},'
          '{"id":"cat-old","name":"Old","status":"INACTIVE","createdAt":""}]}';
    }
    return ResponseBody.fromString(body, 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Future<_Server> _open(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1200, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    key: UniqueKey(),
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: const MaterialApp(home: Scaffold(body: PricingScreen())),
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Promotions'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('New promotion').first);
  await tester.pumpAndSettle();
  return server;
}

Future<void> _pickType(WidgetTester tester, String label) async {
  await tester.tap(find.text('% off each item').last);
  await tester.pumpAndSettle();
  await tester.tap(find.text(label).last);
  await tester.pumpAndSettle();
}

Future<void> _pickScope(WidgetTester tester, String label) async {
  await tester.tap(find.byKey(const Key('promo-scope')));
  await tester.pumpAndSettle();
  await tester.tap(find.text(label).last);
  await tester.pumpAndSettle();
}

void main() {
  // The dialog's dates are written with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);
  testWidgets('a deal applies to everything unless told otherwise, and says so', (tester) async {
    final server = await _open(tester);
    expect(find.text('Everything'), findsOneWidget);
    await tester.enterText(find.widgetWithText(TextField, 'Name *'), 'Ten off');
    await tester.enterText(find.byKey(const Key('promo-value')), '10');
    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();
    final scope = server.posts.singleWhere((p) => p.path.endsWith('/items'));
    expect(scope.data, {'scopeType': 'ALL'});
  });

  testWidgets('any N for a price sends the bundle size and price, scoped to a category',
      (tester) async {
    final server = await _open(tester);
    await _pickType(tester, 'Any N for a price');
    expect(find.text('Bundle price'), findsOneWidget);
    await tester.enterText(find.widgetWithText(TextField, 'Name *'), 'Any 3 for 4');
    await tester.enterText(find.byKey(const Key('promo-value')), '4');
    await tester.enterText(find.byKey(const Key('promo-bundle-size')), '3');
    await _pickScope(tester, 'One category');
    await tester.pumpAndSettle();
    // Only active categories are offered.
    await tester.tap(find.byKey(const Key('promo-category')));
    await tester.pumpAndSettle();
    expect(find.text('Old'), findsNothing);
    await tester.tap(find.text('Drinks').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();

    final created = server.posts.singleWhere((p) => p.path.endsWith('/admin/promotions'));
    expect(created.data['type'], 'MIX_MATCH');
    expect(created.data['value'], 4.0);
    expect(created.data['buyQty'], 3);
    expect(created.data.containsKey('getQty'), isFalse);
    final scope = server.posts.singleWhere((p) => p.path.endsWith('/items'));
    expect(scope.data, {'scopeType': 'CATEGORY', 'scopeId': 'cat-drinks'});
  });

  testWidgets('a bundle of one, or a category scope with no category, is stopped before it is sent',
      (tester) async {
    final server = await _open(tester);
    await _pickType(tester, 'Any N for a price');
    await tester.enterText(find.widgetWithText(TextField, 'Name *'), 'Any 1 for 4');
    await tester.enterText(find.byKey(const Key('promo-value')), '4');
    await tester.enterText(find.byKey(const Key('promo-bundle-size')), '1');
    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();
    expect(find.text('A bundle is at least two units.'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('promo-bundle-size')), '3');
    await _pickScope(tester, 'One category');
    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();
    expect(find.text('Choose the category the deal applies to.'), findsOneWidget);
    expect(server.posts, isEmpty);
  });
}
