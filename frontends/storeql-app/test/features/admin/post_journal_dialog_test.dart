import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/amount_entry.dart';
import 'package:storeql_app/core/format.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/post_journal_dialog.dart';

// ---------------------------------------------------------------------------
// The manual journal (17.1). The server refuses a journal that does not
// balance; the dialog refuses first, with the running difference on screen,
// so a finance user typing twenty lines does not learn on the twenty-first
// that the third was wrong.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int status = 201;

  /// The business's home currency, as tenant-svc answers it; null while the
  /// business cannot be read.
  String? currency = 'GBP';

  /// The journals posted: everything else asked is a read.
  Iterable<RequestOptions> get posts => requests.where((r) => r.method == 'POST');

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.method == 'GET' && o.path.endsWith('/admin/tenant')) {
      return currency == null
          ? ResponseBody.fromString(
              '{"error":{"code":"SERVICE_UNAVAILABLE","message":"tenant-svc is not answering"}}', 503,
              headers: {Headers.contentTypeHeader: [Headers.jsonContentType]})
          : ResponseBody.fromString(
              '{"data":{"id":"t-1","name":"Corner Shop","status":"ACTIVE","currency":"$currency","country":"GB"}}',
              200,
              headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
    }
    if (o.method == 'GET' && o.path.endsWith('/admin/stores')) {
      // Every store of the business: a manager held to stores picks among
      // their own only.
      return ResponseBody.fromString(
          '{"data":[{"id":"s-1","name":"High Street"},{"id":"s-2","name":"Market Square"},'
          '{"id":"s-3","name":"Riverside"}],"meta":{"nextCursor":null}}',
          200,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
    }
    final body = status == 201
        ? '{"data":{"journalId":"j-1","totalDebit":100,"totalCredit":100,"lines":[]}}'
        : '{"error":{"code":"PURCHASE_PERIOD_CLOSED","message":"the accounting period covering 2026-06-15 is closed for this store"}}';
    return ResponseBody.fromString(body, status,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Future<_Server> _pump(WidgetTester tester,
    {int status = 201, List<String> storeIds = const [], String? currency = 'GBP'}) async {
  tester.view.physicalSize = const Size(1200, 900);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final server = _Server()
    ..status = status
    ..currency = currency;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<bool>(
                context: context, builder: (_) => PostJournalDialog(storeIds: storeIds)),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  return server;
}

FilledButton _post(WidgetTester tester) =>
    tester.widget<FilledButton>(find.byKey(const Key('journal-post')));

Future<void> _type(WidgetTester tester, String key, String text) async {
  await tester.enterText(find.byKey(Key(key)), text);
  await tester.pumpAndSettle();
}

/// [text] typed into the field one key at a time, as a person types it.
Future<void> _press(WidgetTester tester, String key, String text) async {
  for (var i = 1; i <= text.length; i++) {
    await tester.enterText(find.byKey(Key(key)), text.substring(0, i));
    await tester.pump();
  }
  await tester.pumpAndSettle();
}

String? _hint(WidgetTester tester, String key) =>
    tester.widget<TextField>(find.byKey(Key(key))).decoration?.hintText;

String _totals(WidgetTester tester) =>
    tester.widget<Text>(find.byKey(const Key('journal-totals'))).data!;

void main() {
  testWidgets('the running difference is on screen and blocks the post until it is zero',
      (tester) async {
    final server = await _pump(tester);
    expect(_post(tester).onPressed, isNull);
    await _type(tester, 'journal-description', 'Opening stock');
    await _type(tester, 'journal-code-0', '1001');
    await _type(tester, 'journal-debit-0', '500');
    await _type(tester, 'journal-code-1', '3000');
    await _type(tester, 'journal-credit-1', '499.99');
    expect(find.textContaining('difference £0.01'), findsOneWidget);
    expect(find.text('Debits and credits differ by £0.01.'), findsOneWidget);
    expect(_post(tester).onPressed, isNull);

    await _type(tester, 'journal-credit-1', '500');
    expect(find.textContaining('difference £0.00'), findsOneWidget);
    expect(_post(tester).onPressed, isNotNull);
    expect(server.posts, isEmpty);
  });

  testWidgets('a balanced journal is sent as the server expects it', (tester) async {
    final server = await _pump(tester);
    await _type(tester, 'journal-date', '2026-02-05');
    await _type(tester, 'journal-description', '  Opening stock  ');
    await _type(tester, 'journal-code-0', '1001');
    await _type(tester, 'journal-name-0', 'Stock');
    await _type(tester, 'journal-debit-0', '500');
    await _type(tester, 'journal-code-1', '3000');
    await _type(tester, 'journal-credit-1', '500');
    await tester.tap(find.byKey(const Key('journal-post')));
    await tester.pumpAndSettle();

    final sent = server.posts.single;
    expect(sent.method, 'POST');
    expect(sent.path, contains('/purchase-svc/nominal-ledger/journals'));
    expect(sent.data['entryDate'], '2026-02-05');
    expect(sent.data['description'], 'Opening stock');
    final lines = sent.data['lines'] as List;
    expect(lines.length, 2);
    expect(lines[0], {'nominalCode': '1001', 'nominalName': 'Stock', 'debit': '500'});
    expect(lines[1], {'nominalCode': '3000', 'credit': '500'});
    expect(sent.data.containsKey('storeId'), isFalse,
        reason: 'held to no store: the business\'s own journal');
    expect(find.byKey(const Key('journal-store')), findsNothing);
    expect(find.text('Post a journal'), findsNothing);
    expect(find.text('Journal posted.'), findsOneWidget);
  });

  testWidgets('a line that is both a debit and a credit, or a bad code, is stopped here',
      (tester) async {
    await _pump(tester);
    await _type(tester, 'journal-description', 'x');
    await _type(tester, 'journal-code-0', '1001');
    await _type(tester, 'journal-debit-0', '10');
    await _type(tester, 'journal-credit-0', '10');
    await _type(tester, 'journal-code-1', '3000');
    await _type(tester, 'journal-credit-1', '10');
    expect(find.text('A line is a debit or a credit, not both.'), findsOneWidget);
    expect(_post(tester).onPressed, isNull);

    await _type(tester, 'journal-credit-0', '');
    await _type(tester, 'journal-code-0', '10 01');
    expect(find.textContaining('nominal code of 1–10 letters or digits'), findsOneWidget);
    expect(_post(tester).onPressed, isNull);

    await _type(tester, 'journal-code-0', '1001');
    await _type(tester, 'journal-date', '5 Feb');
    expect(find.text('Date as yyyy-MM-dd.'), findsOneWidget);
    expect(_post(tester).onPressed, isNull);
  });

  testWidgets('lines are added and removed, never below two', (tester) async {
    await _pump(tester);
    expect(find.byKey(const Key('journal-code-1')), findsOneWidget);
    expect(find.byKey(const Key('journal-code-2')), findsNothing);
    final remove = tester.widget<IconButton>(find.byKey(const Key('journal-remove-0')));
    expect(remove.onPressed, isNull);

    await tester.tap(find.byKey(const Key('journal-add-line')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('journal-code-2')), findsOneWidget);
    await tester.tap(find.byKey(const Key('journal-remove-2')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('journal-code-2')), findsNothing);
  });

  testWidgets('a refusal from the server is shown in words and the journal stays open',
      (tester) async {
    final server = await _pump(tester, status: 409);
    await _type(tester, 'journal-date', '2026-06-15');
    await _type(tester, 'journal-description', 'Late');
    await _type(tester, 'journal-code-0', '1001');
    await _type(tester, 'journal-debit-0', '1');
    await _type(tester, 'journal-code-1', '3000');
    await _type(tester, 'journal-credit-1', '1');
    await tester.tap(find.byKey(const Key('journal-post')));
    await tester.pumpAndSettle();
    expect(server.posts.length, 1);
    expect(find.text('Post a journal'), findsOneWidget);
    expect(find.textContaining('is closed for this store'), findsOneWidget);
    expect(_post(tester).onPressed, isNotNull);
  });

  // purchase-svc posts a journal naming no store at the caller's only store,
  // or as the business's own for a caller held to none; a caller held to two
  // or more who names none is refused (BUSINESS_WIDE_ONLY). So such a caller
  // picks one of their own stores, and it is sent.
  Future<void> balanced(WidgetTester tester) async {
    await _type(tester, 'journal-description', 'Till float top-up');
    await _type(tester, 'journal-code-0', '1001');
    await _type(tester, 'journal-debit-0', '50');
    await _type(tester, 'journal-code-1', '3000');
    await _type(tester, 'journal-credit-1', '50');
  }

  testWidgets('held to two stores, the journal waits for one of theirs and names it',
      (tester) async {
    final server = await _pump(tester, storeIds: const ['s-1', 's-2']);
    await balanced(tester);
    expect(find.text('Choose the store the journal is posted at.'), findsOneWidget);
    expect(_post(tester).onPressed, isNull);

    await tester.tap(find.byKey(const Key('journal-store')));
    await tester.pumpAndSettle();
    expect(find.text('High Street'), findsWidgets);
    expect(find.text('Riverside'), findsNothing, reason: 'not one of theirs');
    await tester.tap(find.text('Market Square').last);
    await tester.pumpAndSettle();
    expect(_post(tester).onPressed, isNotNull);

    await tester.tap(find.byKey(const Key('journal-post')));
    await tester.pumpAndSettle();
    final sent = server.requests.lastWhere((r) => r.method == 'POST');
    expect(sent.data['storeId'], 's-2');
  });

  testWidgets('held to one store, nothing is asked: the journal is posted at it',
      (tester) async {
    final server = await _pump(tester, storeIds: const ['s-1']);
    expect(find.byKey(const Key('journal-store')), findsNothing);
    await balanced(tester);
    expect(_post(tester).onPressed, isNotNull);
    await tester.tap(find.byKey(const Key('journal-post')));
    await tester.pumpAndSettle();
    final sent = server.requests.lastWhere((r) => r.method == 'POST');
    expect(sent.data.containsKey('storeId'), isFalse);
  });

  // A debit or a credit is read the way the app's language writes a number,
  // with the shared amount reader: a figure it cannot read is refused in
  // words, names its line, and holds the journal back. Read as nought, such
  // a line dropped out of the journal unsaid, and the rest still balanced and
  // posted; read with a point where the language groups thousands with one,
  // 1.000 lei went as 1.
  group('every amount is read as typed, or the journal waits', () {
    tearDown(() => Intl.defaultLocale = null);

    Future<void> fourLines(WidgetTester tester, String big) async {
      await _type(tester, 'journal-description', 'Accruals');
      await tester.tap(find.byKey(const Key('journal-add-line')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('journal-add-line')));
      await tester.pumpAndSettle();
      await _type(tester, 'journal-code-0', '1001');
      await _type(tester, 'journal-debit-0', '50');
      await _type(tester, 'journal-code-1', '3000');
      await _type(tester, 'journal-credit-1', '50');
      await _type(tester, 'journal-code-2', '5000');
      await _type(tester, 'journal-debit-2', big);
      await _type(tester, 'journal-code-3', '2100');
      await _type(tester, 'journal-credit-3', big);
    }

    testWidgets('in English, 1,000 on two lines holds the journal and says why, never posting the rest',
        (tester) async {
      Intl.defaultLocale = 'en_GB';
      final server = await _pump(tester);
      await fourLines(tester, '1,000');
      expect(_post(tester).onPressed, isNull, reason: 'never posted as the 50/50 pair alone');
      expect(
          find.text('Debit on line 3: Type the amount without thousands separators. '
              'Decimals go after a point.'),
          findsOneWidget);
      await tester.tap(find.byKey(const Key('journal-post')));
      await tester.pumpAndSettle();
      expect(server.requests.where((r) => r.method == 'POST'), isEmpty);

      await _type(tester, 'journal-debit-2', '1000');
      await _type(tester, 'journal-credit-3', '1000');
      expect(find.textContaining('Dr £1,050.00 · Cr £1,050.00'), findsOneWidget);
      await tester.tap(find.byKey(const Key('journal-post')));
      await tester.pumpAndSettle();
      final lines = server.requests.singleWhere((r) => r.method == 'POST').data['lines'] as List;
      expect(lines.length, 4);
      expect(lines[2], {'nominalCode': '5000', 'debit': '1000'});
      expect(lines[3], {'nominalCode': '2100', 'credit': '1000'});
    });

    testWidgets('in Romanian, 1.000 is refused as a thousands separator, never posted as 1',
        (tester) async {
      Intl.defaultLocale = 'ro';
      final server = await _pump(tester, currency: 'RON');
      await _type(tester, 'journal-description', 'Accruals');
      await _type(tester, 'journal-code-0', '5000');
      await _type(tester, 'journal-debit-0', '1.000');
      await _type(tester, 'journal-code-1', '2100');
      await _type(tester, 'journal-credit-1', '1.000');
      expect(_post(tester).onPressed, isNull);
      expect(
          find.text('Debit on line 1: Type the amount without thousands separators. '
              'Decimals go after a comma.'),
          findsOneWidget);
      await tester.tap(find.byKey(const Key('journal-post')));
      await tester.pumpAndSettle();
      expect(server.requests.where((r) => r.method == 'POST'), isEmpty);
    });

    testWidgets('in Romanian, 12,50 is twelve fifty and is posted as 12.5', (tester) async {
      Intl.defaultLocale = 'ro';
      final server = await _pump(tester, currency: 'RON');
      await _type(tester, 'journal-description', 'Petty cash');
      await _type(tester, 'journal-code-0', '7500');
      await _type(tester, 'journal-debit-0', '12,50');
      await _type(tester, 'journal-code-1', '1230');
      await _type(tester, 'journal-credit-1', '12,50');
      final lei = AppFormat.money(12.5, currencyCode: 'RON');
      expect(lei, contains('12,50'));
      expect(find.textContaining('Dr $lei · Cr $lei'), findsOneWidget);
      expect(_post(tester).onPressed, isNotNull);
      await tester.tap(find.byKey(const Key('journal-post')));
      await tester.pumpAndSettle();
      final lines = server.requests.singleWhere((r) => r.method == 'POST').data['lines'] as List;
      expect(lines[0], {'nominalCode': '7500', 'debit': '12.5'});
      expect(lines[1], {'nominalCode': '1230', 'credit': '12.5'});
    });

    testWidgets('in pounds, a third place or a sign is refused in words', (tester) async {
      Intl.defaultLocale = 'en_GB';
      await _pump(tester);
      expect(_hint(tester, 'journal-debit-0'), '0.00');
      await _type(tester, 'journal-description', 'x');
      await _type(tester, 'journal-code-0', '1001');
      await _type(tester, 'journal-debit-0', '12.505');
      await _type(tester, 'journal-code-1', '3000');
      await _type(tester, 'journal-credit-1', '12.505');
      expect(find.text('Debit on line 1: At most 2 decimal places.'), findsOneWidget);
      expect(_post(tester).onPressed, isNull);
      await _type(tester, 'journal-debit-0', '-12.5');
      expect(find.text('Debit on line 1: Type the amount without a sign.'), findsOneWidget);
    });
  });

  // A ledger amount is money in the business's own currency, at that
  // currency's places — three for the Kuwaiti dinar, none for the yen — never
  // a fixed two. purchase-svc keeps the figure it is sent and checks no scale,
  // so the dialog is where a dinar's third place is taken and half a yen is
  // refused: read at two places whatever the currency, 1.234 dinars could not
  // be posted at all and 100.50 yen could. The sum is exact at those places
  // and the totals are written at them.
  group('amounts are read at the places of the business\'s own currency', () {
    tearDown(() => Intl.defaultLocale = null);

    Future<void> twoLines(WidgetTester tester, String debit, String credit) async {
      await _type(tester, 'journal-description', 'Opening balance');
      await _type(tester, 'journal-code-0', '1001');
      await _press(tester, 'journal-debit-0', debit);
      await _type(tester, 'journal-code-1', '3000');
      await _press(tester, 'journal-credit-1', credit);
    }

    String kwd(num v) => AppFormat.money(v, currencyCode: 'KWD');

    for (final (locale, typed) in [
      ('en_GB', '1.234'),
      ('en', '1.234'),
      ('ro', '1,234'),
      ('pl', '1,234'),
      ('ar', '1٫234'),
    ]) {
      testWidgets('in $locale, a dinar journal of $typed a side is posted as 1.234', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pump(tester, currency: 'KWD');
        await twoLines(tester, typed, typed);
        expect(find.byKey(const Key('journal-blocker')), findsNothing);
        expect(_totals(tester), 'Dr ${kwd(1.234)} · Cr ${kwd(1.234)} · difference ${kwd(0)}');
        expect(_hint(tester, 'journal-debit-0'), AmountMarks.ofApp().hint(3));
        expect(_hint(tester, 'journal-credit-1'), AmountMarks.ofApp().hint(3));
        expect(_post(tester).onPressed, isNotNull);
        await tester.tap(find.byKey(const Key('journal-post')));
        await tester.pumpAndSettle();
        final lines = server.posts.single.data['lines'] as List;
        expect(lines[0], {'nominalCode': '1001', 'debit': '1.234'});
        expect(lines[1], {'nominalCode': '3000', 'credit': '1.234'});
      });
    }

    testWidgets('a dinar journal out by a thousandth waits, and says by how much', (tester) async {
      Intl.defaultLocale = 'en_GB';
      final server = await _pump(tester, currency: 'KWD');
      await twoLines(tester, '1.234', '1.233');
      expect(kwd(0.001), contains('0.001'));
      expect(find.text('Debits and credits differ by ${kwd(0.001)}.'), findsOneWidget);
      expect(_totals(tester), 'Dr ${kwd(1.234)} · Cr ${kwd(1.233)} · difference ${kwd(0.001)}');
      expect(_post(tester).onPressed, isNull);
      await tester.tap(find.byKey(const Key('journal-post')));
      await tester.pumpAndSettle();
      expect(server.posts, isEmpty);
    });

    testWidgets('a dinar\'s fourth place is refused in words', (tester) async {
      Intl.defaultLocale = 'en_GB';
      await _pump(tester, currency: 'KWD');
      await twoLines(tester, '1.2345', '1.2345');
      expect(find.text('Debit on line 1: At most 3 decimal places.'), findsOneWidget);
      expect(_post(tester).onPressed, isNull);
    });

    testWidgets('a yen journal takes whole yen: 100.50 is refused, 100 is posted', (tester) async {
      Intl.defaultLocale = 'en_GB';
      final server = await _pump(tester, currency: 'JPY');
      await twoLines(tester, '100.50', '100.50');
      expect(find.text('Debit on line 1: Whole amounts only.'), findsOneWidget);
      expect(_hint(tester, 'journal-debit-0'), '0');
      expect(_post(tester).onPressed, isNull);
      await tester.tap(find.byKey(const Key('journal-post')));
      await tester.pumpAndSettle();
      expect(server.posts, isEmpty);

      await _press(tester, 'journal-debit-0', '100');
      await _press(tester, 'journal-credit-1', '100');
      String yen(num v) => AppFormat.money(v, currencyCode: 'JPY');
      expect(yen(100), isNot(contains('.')));
      expect(_totals(tester), 'Dr ${yen(100)} · Cr ${yen(100)} · difference ${yen(0)}');
      await tester.tap(find.byKey(const Key('journal-post')));
      await tester.pumpAndSettle();
      final lines = server.posts.single.data['lines'] as List;
      expect(lines[0], {'nominalCode': '1001', 'debit': '100'});
      expect(lines[1], {'nominalCode': '3000', 'credit': '100'});
    });

    // Without the currency there are no places to read an amount at, and a
    // ledger line cannot be corrected, only reversed: the journal waits, in
    // words, rather than be posted at two places and hope.
    testWidgets('while the business\'s currency cannot be read the journal waits, and posts once it is',
        (tester) async {
      Intl.defaultLocale = 'en_GB';
      final server = await _pump(tester, currency: null);
      await twoLines(tester, '100.5', '100.5');
      expect(_post(tester).onPressed, isNull);
      await tester.tap(find.byKey(const Key('journal-post')));
      await tester.pumpAndSettle();
      expect(server.posts, isEmpty);

      // Every attempt of its own has failed: it says so, and offers another.
      await tester.pump(const Duration(minutes: 2));
      await tester.pumpAndSettle();
      expect(
          find.text('The business\'s currency could not be read, so its amounts cannot be taken. '
              'Nothing is posted until it is.'),
          findsOneWidget);
      expect(_post(tester).onPressed, isNull);

      server.currency = 'KWD';
      await tester.tap(find.byKey(const Key('journal-currency-retry')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('journal-blocker')), findsNothing);
      expect(find.byKey(const Key('journal-currency-retry')), findsNothing);
      expect(_hint(tester, 'journal-debit-0'), '0.000');
      await tester.tap(find.byKey(const Key('journal-post')));
      await tester.pumpAndSettle();
      final lines = server.posts.single.data['lines'] as List;
      expect(lines[0], {'nominalCode': '1001', 'debit': '100.5'});
      expect(lines[1], {'nominalCode': '3000', 'credit': '100.5'});
    });
  });
}
