import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
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
    // The plain decimal typed, as a string: JSON-B reads it exactly.
    expect(created.data['value'], '4');
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

  // Every figure is read the way the app's language writes a number, with
  // the shared amount reader. One the dialog cannot read is refused under its
  // field and nothing is sent: read as null, Romanian's minimum order of
  // 12,50 went as no minimum at all and English's 1,000 uses as no cap; read
  // with a point, 1.250 lei off went as 1,25.
  group('figures are read as typed, or refused', () {
    tearDown(() => Intl.defaultLocale = null);

    String? says(WidgetTester tester, Finder field) =>
        tester.widget<TextField>(field).decoration?.errorText;

    Finder labelled(String label) => find.widgetWithText(TextField, label);

    testWidgets('in Romanian, a minimum order of 12,50 is sent as 12.5, never as none',
        (tester) async {
      Intl.defaultLocale = 'ro';
      final server = await _open(tester);
      await tester.enterText(labelled('Name *'), 'Zece la sută');
      await tester.enterText(find.byKey(const Key('promo-value')), '10');
      await tester.enterText(labelled('Min order (opt)'), '12,50');
      await tester.enterText(labelled('Max uses (opt)'), '1000');
      await tester.tap(find.text('Create'));
      await tester.pumpAndSettle();
      final created = server.posts.singleWhere((p) => p.path.endsWith('/admin/promotions'));
      expect(created.data['value'], '10');
      expect(created.data['minOrderAmount'], '12.5');
      expect(created.data['maxRedemptions'], 1000);
      expect(created.data['priority'], 100);
    });

    for (final (locale, value, says_) in [
      ('ro', '1.250', 'Decimals go after a comma.'),
      ('en_GB', '1,250', 'Decimals go after a point.'),
    ]) {
      testWidgets('in $locale, $value off is refused in words and nothing is made', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _open(tester);
        await _pickType(tester, 'Amount off the basket');
        await tester.enterText(labelled('Name *'), 'Big saving');
        await tester.enterText(find.byKey(const Key('promo-value')), value);
        await tester.pump();
        expect(says(tester, find.byKey(const Key('promo-value'))),
            'Type the amount without thousands separators. $says_');
        await tester.tap(find.text('Create'));
        await tester.pumpAndSettle();
        expect(server.posts, isEmpty);
      });
    }

    testWidgets('in English, 1,000 uses or a minimum of 12,50 is refused, never sent as none',
        (tester) async {
      Intl.defaultLocale = 'en_GB';
      final server = await _open(tester);
      await tester.enterText(labelled('Name *'), 'Ten off');
      await tester.enterText(find.byKey(const Key('promo-value')), '10');
      await tester.enterText(labelled('Max uses (opt)'), '1,000');
      await tester.enterText(labelled('Min order (opt)'), '12,50');
      await tester.pump();
      expect(says(tester, labelled('Max uses (opt)')),
          'Type the amount without thousands separators.');
      expect(says(tester, labelled('Min order (opt)')),
          'Type the amount without thousands separators. Decimals go after a point.');
      await tester.tap(find.text('Create'));
      await tester.pumpAndSettle();
      expect(server.posts, isEmpty, reason: 'never uncapped, never no minimum');
      expect(find.text('A figure cannot be read. Correct the one marked.'), findsOneWidget);
    });

    // A mark or a sign alone is no figure, and is not blank either: refused,
    // never left out for pricing-svc's default (no minimum, priority 100).
    for (final (locale, minOrder, priority) in [
      ('ro', ',', '-'),
      ('en_GB', '.', '+'),
      ('en', '.', '\u2212'),
      ('pl', ',', '-'),
      ('ar', '\u066B', '-'),
    ]) {
      testWidgets('in $locale, a minimum order of "$minOrder" or a priority of "$priority" is refused',
          (tester) async {
        Intl.defaultLocale = locale;
        final server = await _open(tester);
        await tester.enterText(labelled('Name *'), 'Ten off');
        await tester.enterText(find.byKey(const Key('promo-value')), '10');
        await tester.enterText(labelled('Min order (opt)'), minOrder);
        await tester.enterText(labelled('Priority'), priority);
        await tester.pump();
        expect(says(tester, labelled('Min order (opt)')), 'Type the amount in digits.');
        expect(says(tester, labelled('Priority')), 'Type the digits after the sign.');
        await tester.tap(find.text('Create'));
        await tester.pumpAndSettle();
        expect(server.posts, isEmpty, reason: 'never no minimum, never priority 100');
      });
    }
  });
}
