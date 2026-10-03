import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/inventory_yield_tab.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The Inventory screen's "Yield & prep" tab: the template with its cuts and
// expected loss, this month's breakdowns with the loss against expected and the
// month's total; Record a breakdown filling the cuts in at what the template
// expects and posting what actually came out; New template refusing before it
// posts without a primal; and a storekeeper who may record but not define.
// Every figure — what went in, what came out, a cut's share, cost share and
// shelf life — is read the way the app's language writes a number and sent as
// typed, or refused under its field with nothing sent.
// ---------------------------------------------------------------------------

const _store = '01a0b100-0000-7000-8000-000000000001';
const _side = '01a0b100-0000-7000-8000-0000000000a1';
const _sirloin = '01a0b100-0000-7000-8000-0000000000a2';
const _mince = '01a0b100-0000-7000-8000-0000000000a3';
const _template = '01a0b100-0000-7000-8000-0000000000t1';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/admin/stores')) {
      return jsonResponse(
          '{"data":[{"id":"$_store","name":"Butchery","code":"B1","type":"STORE","status":"ACTIVE"}],"meta":{"nextCursor":null}}');
    }
    if (path.endsWith('/yield/templates') && o.method == 'GET') {
      return jsonResponse(
          '{"data":[{"id":"$_template","name":"Side of beef","inputVariantId":"$_side","unit":"kg","active":true,"expectedLossPct":20,"outputs":[{"variantId":"$_sirloin","expectedPct":35,"costShare":60,"shelfLifeDays":5},{"variantId":"$_mince","expectedPct":45,"costShare":40}]}]}');
    }
    if (path.endsWith('/yield/runs') && o.method == 'GET') {
      return jsonResponse(
          '{"data":{"runs":[{"id":"01a0b100-0000-7000-8000-0000000000r1","storeId":"$_store","templateName":"Side of beef","inputQty":100,"inputCost":500.00,"outputQty":78,"lossQty":22,"lossPct":22,"expectedLossQty":20,"lossVariance":2,"lossAtCost":110.00,"reference":"Monday side","recordedAt":"2026-09-24T09:00:00Z","outputs":[]}],"totals":{"runs":1,"inputQty":100,"outputQty":78,"lossQty":22,"expectedLossQty":20,"lossAtCost":110.00}}}');
    }
    if (path.endsWith('/yield/runs') && o.method == 'POST') {
      return jsonResponse(
          '{"data":{"id":"01a0b100-0000-7000-8000-0000000000r2","lossQty":22,"expectedLossQty":20,"lossAtCost":110.00}}',
          201);
    }
    if (path.endsWith('/yield/templates') && o.method == 'POST') {
      return jsonResponse('{"data":{"id":"01a0b100-0000-7000-8000-0000000000t2"}}', 201);
    }
    if (path.endsWith('/admin/products')) {
      return jsonResponse('{"data":[{"id":"p-beef","name":"Beef"}],"meta":{"nextCursor":null}}');
    }
    if (path.endsWith('/admin/products/p-beef/variants')) {
      return jsonResponse(
          '{"data":[{"id":"$_side","productId":"p-beef","sku":"SIDE"},{"id":"$_sirloin","productId":"p-beef","sku":"SIRLOIN"}]}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _pump(WidgetTester tester, {String role = 'MANAGER'}) async {
  tester.view.physicalSize = const Size(1100, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role)),
    ],
    child: const MaterialApp(home: Scaffold(body: InventoryYieldTab())),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  // Dates are written with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);
  testWidgets('the template, its cuts and this month\'s loss against expected are shown',
      (tester) async {
    await _pump(tester);
    expect(find.text('Side of beef'), findsOneWidget);
    expect(find.textContaining('expected loss 20 %'), findsOneWidget);
    expect(find.textContaining('100 in, 78 out'), findsOneWidget);
    expect(find.textContaining('lost 22 (22 %) against 20 expected, 2 over'), findsOneWidget);
    expect(find.byKey(const Key('yield-total-loss')), findsOneWidget);
    expect(find.textContaining('22 of 100 in, against 20 expected'), findsOneWidget);
  });

  testWidgets('Record a breakdown fills the cuts in at what the template expects and posts',
      (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('yield-record')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('yield-store')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Butchery').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('yield-template')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Side of beef').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('yield-input')), '100');
    await tester.pumpAndSettle();
    // Filled in at 35 % and 45 % of what went in.
    expect(
        tester.widget<TextField>(find.byKey(const Key('yield-out-$_sirloin'))).controller!.text,
        '35');
    expect(
        tester.widget<TextField>(find.byKey(const Key('yield-out-$_mince'))).controller!.text,
        '45');
    await tester.enterText(find.byKey(const Key('yield-out-$_sirloin')), '34');
    await tester.enterText(find.byKey(const Key('yield-out-$_mince')), '44');
    await tester.enterText(find.byKey(const Key('yield-reference')), 'Monday side');
    await tester.tap(find.byKey(const Key('yield-run-save')));
    await tester.pumpAndSettle();
    final post = server.requests.lastWhere((r) => r.method == 'POST');
    expect(post.path, endsWith('/admin/inventory/yield/runs'));
    final body = post.data is String ? jsonDecode(post.data as String) : post.data;
    expect(body['storeId'], _store);
    expect(body['templateId'], _template);
    // The plain decimals typed.
    expect(body['inputQty'], '100');
    expect(body['outputs'], [
      {'variantId': _sirloin, 'qty': '34'},
      {'variantId': _mince, 'qty': '44'},
    ]);
    expect(body['reference'], 'Monday side');
    expect(find.textContaining('22 lost against 20 expected'), findsOneWidget);
  });

  testWidgets('New template refuses before posting until the primal is picked', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('yield-new-template')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('yield-name')), 'Whole salmon');
    await tester.enterText(find.byKey(const Key('yield-pct-0')), '55');
    await tester.pumpAndSettle();
    expect(find.text('Expected loss: 45 %'), findsOneWidget);
    await tester.tap(find.byKey(const Key('yield-save')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('yield-refusal')), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'POST'), isEmpty);
  });

  testWidgets('a storekeeper may record a breakdown but not define a template', (tester) async {
    await _pump(tester, role: 'STOREKEEPER');
    expect(find.byKey(const Key('yield-new-template')), findsNothing);
    expect(find.byKey(const Key('yield-end-$_template')), findsNothing);
    expect(find.byKey(const Key('yield-record')), findsOneWidget);
  });

  // What went in and what came out are quantities, to three places: read the
  // way the app's language writes a number, sent as the decimals typed, or
  // refused under the field with nothing recorded. Parsed with a point,
  // Romanian's 34,5 kg of sirloin was recorded as none — all of it loss — and
  // its 1.250 kg as a kilo and a quarter.
  group('a breakdown is recorded as typed, or refused', () {
    tearDown(() => Intl.defaultLocale = null);

    for (final (locale, wentIn, cameOut, filled) in [
      ('ro', '100,5', '34,25', '35,175'),
      ('en_GB', '100.5', '34.25', '35.175'),
      ('en', '100.5', '34.25', '35.175'),
      ('pl', '100,5', '34,25', '35,175'),
      ('ar', '100\u066B5', '34\u066B25', '35.175'),
    ]) {
      testWidgets('in $locale, $wentIn in and $cameOut out go as 100.5 and 34.25', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pump(tester);
        await _openRecord(tester);
        await _type(tester, 'yield-input', wentIn);
        expect(_says(tester, 'yield-input'), isNull);
        // The cuts are filled in the way the language writes a number, so
        // they read back unchanged: 35 % of 100.5.
        expect(_text(tester, 'yield-out-$_sirloin'), filled);
        expect(_says(tester, 'yield-out-$_sirloin'), isNull);
        await _type(tester, 'yield-out-$_sirloin', cameOut);
        expect(_says(tester, 'yield-out-$_sirloin'), isNull);
        await tester.tap(find.byKey(const Key('yield-run-save')));
        await tester.pumpAndSettle();
        final body = _body(server.requests.lastWhere((r) => r.method == 'POST'));
        expect(body['inputQty'], '100.5');
        expect(body['outputs'], [
          {'variantId': _sirloin, 'qty': '34.25'},
          // Untouched: what the template expects, 45 % of 100.5.
          {'variantId': _mince, 'qty': '45.225'},
        ]);
      });
    }

    for (final (locale, typed) in [
      ('ro', '34.5'),
      ('en_GB', '34,5'),
      ('en', '34,5'),
      ('pl', '1.250'),
      ('ar', '-1'),
      ('en_GB', '.'),
      ('ro', ','),
      ('ar', '\u066B'),
      ('en', '1e3'),
    ]) {
      testWidgets('in $locale, "$typed" out is refused under its field and nothing is recorded',
          (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pump(tester);
        await _openRecord(tester);
        await _type(tester, 'yield-input', '100');
        await tester.enterText(find.byKey(const Key('yield-out-$_sirloin')), typed);
        await tester.pump();
        expect(_says(tester, 'yield-out-$_sirloin'), isNotNull);
        await tester.tap(find.byKey(const Key('yield-run-save')));
        await tester.pumpAndSettle();
        expect(server.requests.where((r) => r.method == 'POST'), isEmpty);
        expect(find.text('A figure cannot be read. Correct the one marked.'), findsOneWidget);
      });
    }

    testWidgets('a lone mark for what went in is refused, never read as nothing typed', (tester) async {
      final server = await _pump(tester);
      await _openRecord(tester);
      await tester.enterText(find.byKey(const Key('yield-input')), '.');
      await tester.pump();
      expect(_says(tester, 'yield-input'), 'Type the amount in digits.');
      await tester.tap(find.byKey(const Key('yield-run-save')));
      await tester.pumpAndSettle();
      expect(server.requests.where((r) => r.method == 'POST'), isEmpty);
    });

    testWidgets('a cut left blank came to nothing', (tester) async {
      final server = await _pump(tester);
      await _openRecord(tester);
      await _type(tester, 'yield-input', '100');
      await tester.enterText(find.byKey(const Key('yield-out-$_mince')), '');
      await tester.pump();
      await tester.tap(find.byKey(const Key('yield-run-save')));
      await tester.pumpAndSettle();
      final body = _body(server.requests.lastWhere((r) => r.method == 'POST'));
      expect(body['outputs'], [
        {'variantId': _sirloin, 'qty': '35'},
        {'variantId': _mince, 'qty': '0'},
      ]);
    });
  });

  // A template's shares, cost shares and shelf lives, the same way: a share
  // parsed with a point went as nothing, and a cost share or shelf life the
  // dialog could not read was saved as none — by weight, the primal's date.
  group('a template is saved as typed, or refused', () {
    tearDown(() => Intl.defaultLocale = null);

    for (final (locale, pct, share, sentPct, sentShare) in [
      ('ro', '35,5', '60,25', '35.5', '60.25'),
      ('en_GB', '35.5', '60.25', '35.5', '60.25'),
      ('en', '35.125', '60.25', '35.125', '60.25'),
      ('pl', '35,125', '60,25', '35.125', '60.25'),
      ('ar', '35\u066B5', '60\u066B25', '35.5', '60.25'),
    ]) {
      testWidgets('in $locale, a cut of $pct % carrying $share is saved as $sentPct and $sentShare',
          (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pump(tester);
        await _openTemplate(tester);
        await _type(tester, 'yield-pct-0', pct);
        await _type(tester, 'yield-share-0', share);
        await _type(tester, 'yield-life-0', '5');
        for (final f in ['yield-pct-0', 'yield-share-0', 'yield-life-0']) {
          expect(_says(tester, f), isNull, reason: f);
        }
        await tester.tap(find.byKey(const Key('yield-save')));
        await tester.pumpAndSettle();
        final post = server.requests.lastWhere((r) => r.method == 'POST');
        expect(post.path, endsWith('/admin/inventory/yield/templates'));
        expect(_body(post)['outputs'], [
          {'variantId': _sirloin, 'expectedPct': sentPct, 'costShare': sentShare, 'shelfLifeDays': 5},
        ]);
      });
    }

    testWidgets('a blank cost share and shelf life are left out, for the template\'s own default',
        (tester) async {
      final server = await _pump(tester);
      await _openTemplate(tester);
      await _type(tester, 'yield-pct-0', '35');
      await tester.tap(find.byKey(const Key('yield-save')));
      await tester.pumpAndSettle();
      expect(_body(server.requests.lastWhere((r) => r.method == 'POST'))['outputs'], [
        {'variantId': _sirloin, 'expectedPct': '35'},
      ]);
    });

    for (final (locale, field, typed, says) in [
      ('ro', 'yield-share-0', ',', 'Type the amount in digits.'),
      ('en_GB', 'yield-share-0', '.', 'Type the amount in digits.'),
      ('ar', 'yield-share-0', '\u066B', 'Type the amount in digits.'),
      ('en', 'yield-life-0', '-', 'Type the amount without a sign.'),
      ('en', 'yield-life-0', '5.', 'Whole amounts only.'),
      ('ro', 'yield-pct-0', '35.5',
          'Type the amount without thousands separators. Decimals go after a comma.'),
      ('en', 'yield-pct-0', '35,5',
          'Type the amount without thousands separators. Decimals go after a point.'),
      ('pl', 'yield-share-0', '1.250',
          'A point may group thousands here. Type the figure without grouping, with any decimals after a comma.'),
    ]) {
      testWidgets('in $locale, "$typed" in ${field.split('-')[1]} is refused, never saved as blank',
          (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pump(tester);
        await _openTemplate(tester);
        await _type(tester, 'yield-pct-0', '35');
        await tester.enterText(find.byKey(Key(field)), typed);
        await tester.pump();
        expect(_says(tester, field), says);
        await tester.tap(find.byKey(const Key('yield-save')));
        await tester.pumpAndSettle();
        expect(server.requests.where((r) => r.method == 'POST'), isEmpty);
        expect(find.text('A figure cannot be read. Correct the one marked.'), findsOneWidget);
      });
    }

    testWidgets('a cut with no share is refused in words, never sent as a share of nothing',
        (tester) async {
      final server = await _pump(tester);
      await _openTemplate(tester);
      await tester.tap(find.byKey(const Key('yield-save')));
      await tester.pumpAndSettle();
      expect(server.requests.where((r) => r.method == 'POST'), isEmpty);
      expect(find.textContaining('more than 0'), findsOneWidget);
    });
  });
}

Map<String, dynamic> _body(RequestOptions r) =>
    (r.data is String ? jsonDecode(r.data as String) : r.data) as Map<String, dynamic>;

/// Types [text] into the field keyed [key] one key at a time, as a person does.
Future<void> _type(WidgetTester tester, String key, String text) async {
  for (var i = 1; i <= text.length; i++) {
    await tester.enterText(find.byKey(Key(key)), text.substring(0, i));
    await tester.pump();
  }
}

String? _says(WidgetTester tester, String key) =>
    tester.widget<TextField>(find.byKey(Key(key))).decoration?.errorText;

String _text(WidgetTester tester, String key) =>
    tester.widget<TextField>(find.byKey(Key(key))).controller!.text;

/// Record a breakdown, with the store and the template chosen.
Future<void> _openRecord(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key('yield-record')));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('yield-store')));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Butchery').last);
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('yield-template')));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Side of beef').last);
  await tester.pumpAndSettle();
}

/// New template, named, with the primal and one cut picked.
Future<void> _openTemplate(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key('yield-new-template')));
  await tester.pumpAndSettle();
  await tester.enterText(find.byKey(const Key('yield-name')), 'Side of beef, short');
  for (final (picker, sku) in [(0, 'SIDE'), (1, 'SIRLOIN')]) {
    await tester.tap(find.text('Product *').at(picker));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Beef').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Variant *').at(picker));
    await tester.pumpAndSettle();
    await tester.tap(find.text(sku).last);
    await tester.pumpAndSettle();
  }
}
