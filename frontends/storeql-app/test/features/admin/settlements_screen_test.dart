import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/admin/settlements_screen.dart';

import '../../support/fake_api.dart';
import '../../support/mid_word.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// Card settlements (11.10): a payout says what it needs of the person looking
// at it; a line that did not match is decided with a reason and sent as
// decided; a payout is signed off only once nothing is open, and not at all
// once it is in the books; an import is not sent until it can be read; and the
// card payments no payout has covered are listed with how long they have
// waited.
// ---------------------------------------------------------------------------

Map<String, dynamic> _batch(String id, String status, int open) => {
      'id': id,
      'provider': 'WORLDPAY',
      'reference': 'WP-$id',
      'currency': 'GBP',
      'payoutDate': '2026-09-15',
      'salesAmount': 165.99,
      'refundAmount': 5.99,
      'chargebackAmount': 0,
      'feeAmount': 17.49,
      'netAmount': 142.51,
      'lineCount': 4,
      'openExceptions': open,
      'status': status,
    };

Map<String, dynamic> _line(int no, String type, String reference, String match, {num? expected, String? resolution, String? note}) => {
      'id': 'l$no',
      'lineNo': no,
      'type': type,
      'reference': reference,
      'gross': type == 'ADJUSTMENT' ? -50 : 45,
      'fee': type == 'ADJUSTMENT' ? 0 : 0.7,
      'net': type == 'ADJUSTMENT' ? -50 : 44.3,
      'matchStatus': match,
      'open': resolution == null && match != 'MATCHED',
      'expectedAmount': expected,
      'resolution': resolution,
      'note': note,
    };

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  Map<String, dynamic> batch;
  List<Map<String, dynamic>> lines;
  int? refuseWith;
  String refusal = 'SETTLEMENT_AMOUNT_DIFFERS';

  _Server(this.batch, this.lines);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.method == 'GET' && o.path.endsWith('/unsettled')) {
      return jsonResponse(jsonEncode({
        'data': [
          {'paymentId': 'p-old', 'orderId': '01a0d950-611e-702d-bfe9-b7296be05941', 'reference': 'AUTH-0421', 'method': 'CARD', 'amount': 75, 'capturedAt': '2026-09-11T10:00:00Z', 'daysOutstanding': 6},
        ],
      }));
    }
    if (o.method == 'GET' && o.path.endsWith('/admin/settlements')) {
      return jsonResponse(jsonEncode({
        'data': [_batch('1', 'RECONCILED', 0), _batch('2', 'EXCEPTIONS', 2), _batch('3', 'READY', 0)],
      }));
    }
    if (o.method == 'POST' && refuseWith != null) {
      return jsonResponse(jsonEncode({'code': refusal, 'detail': 'refused', 'status': refuseWith}), refuseWith!);
    }
    if (o.method == 'POST' && o.path.endsWith('/resolve')) {
      final decided = (o.data as Map)['resolution'] as String;
      final id = o.path.split('/lines/').last.split('/').first;
      lines = [
        for (final l in lines)
          if (l['id'] == id) {...l, 'resolution': decided, 'open': false, 'note': (o.data as Map)['note']} else l,
      ];
      final open = lines.where((l) => l['open'] == true).length;
      batch = {...batch, 'openExceptions': open, 'status': open == 0 ? 'READY' : 'EXCEPTIONS'};
    }
    if (o.method == 'POST' && o.path.endsWith('/reconcile')) {
      batch = {...batch, 'status': 'RECONCILED'};
      return jsonResponse(jsonEncode({'data': batch}));
    }
    if (o.method == 'POST' && o.path.endsWith('/admin/settlements')) {
      return jsonResponse(jsonEncode({'data': batch}), 201);
    }
    final onlyOpen = o.queryParameters['open'] == true;
    return jsonResponse(jsonEncode({
      'data': {'batch': batch, 'lines': lines.where((l) => !onlyOpen || l['open'] == true).toList()},
    }));
  }
}

Future<_Server> _pump(WidgetTester tester, Widget child,
    {Map<String, dynamic>? batch, List<Map<String, dynamic>>? lines, Size size = const Size(1400, 2400)}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(
    batch ?? _batch('2', 'EXCEPTIONS', 2),
    lines ??
        [
          _line(1, 'SALE', 'AUTH-1', 'MATCHED'),
          _line(2, 'SALE', 'AUTH-SHORT', 'AMOUNT_MISMATCH', expected: 50),
          _line(3, 'ADJUSTMENT', 'RESERVE-HELD', 'UNMATCHED'),
        ],
  );
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(FakeApiClient(dio)),
        tenantInfoProvider.overrideWith((ref) async => const TenantInfo(
            id: 't', name: 'Corner Shop', status: 'ACTIVE', currency: 'GBP', country: 'GB')),
      ],
      child: MaterialApp(home: Scaffold(body: child)),
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
  // The list writes its dates with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);

  testWidgets('a payout says what it needs, and what has not been paid out is a list of its own', (tester) async {
    await _pump(tester, const SettlementsScreen());

    // Money in the currency's own form, dates as a person writes them.
    expect(find.text('£142.51 · WORLDPAY WP-1'), findsOneWidget);
    expect(find.text('Paid 15 Sept 2026 · 2 of 4 lines need a decision'), findsOneWidget);
    expect(find.text('Paid 15 Sept 2026 · 4 lines · fees £17.49'), findsNWidgets(2));
    expect(find.textContaining('142.51 GBP'), findsNothing);
    expect(find.textContaining('2026-09-15'), findsNothing);
    expect(find.text('Needs decisions'), findsOneWidget);
    expect(find.text('Ready to sign off'), findsOneWidget);
    expect(find.text('Reconciled'), findsOneWidget);

    await tester.tap(find.byKey(const Key('settlement-unsettled')));
    await tester.pumpAndSettle();
    expect(find.text('£75.00 · Card · AUTH-0421'), findsOneWidget);
    expect(find.text('6 days'), findsOneWidget);
    expect(find.text('Taken 11 Sept 2026 · order …6be05941'), findsOneWidget);
    expect(find.textContaining('CARD'), findsNothing);
    expect(find.textContaining('2026-09-11'), findsNothing);
    expect(find.textContaining('p-old'), findsNothing);
  });

  testWidgets('only a payout with decisions open is in the error colour', (tester) async {
    await _pump(tester, const SettlementsScreen());
    final error = Theme.of(tester.element(find.byType(SettlementsScreen))).colorScheme.error;
    Color? iconOf(String id) =>
        tester.widget<Icon>(find.descendant(of: find.byKey(Key('settlement-$id')), matching: find.byType(Icon)).first).color;

    expect(iconOf('2'), error, reason: 'EXCEPTIONS: lines still need a decision');
    expect(iconOf('3'), isNot(error), reason: 'READY: nothing is wrong, it only waits to be signed off');
    expect(iconOf('1'), isNot(error), reason: 'RECONCILED');
  });

  testWidgets('on a phone the title keeps its words, the actions go under it, and the page is inset 16', (tester) async {
    await _pump(tester, const SettlementsScreen(), size: const Size(390, 844));

    final title = find.text('Card settlements');
    expect(breaksMidWord(tester, title), isFalse, reason: 'the title never breaks mid-word beside the buttons');
    expect(tester.getTopLeft(find.byKey(const Key('settlement-import'))).dy,
        greaterThanOrEqualTo(tester.getBottomLeft(title).dy),
        reason: 'the actions wrap under the title');
    expect(tester.getTopLeft(title).dx, 16, reason: 'the page gutter on a phone');
    expect(find.text('£142.51 · WORLDPAY WP-1'), findsOneWidget);
  });

  testWidgets('just under 600px the actions are under the title too, and 200% text on a phone does not overflow', (tester) async {
    await _pump(tester, const SettlementsScreen(), size: const Size(599, 900));
    final title = find.text('Card settlements');
    expect(breaksMidWord(tester, title), isFalse);
    expect(tester.getTopLeft(find.byKey(const Key('settlement-import'))).dy,
        greaterThanOrEqualTo(tester.getBottomLeft(title).dy));

    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    tester.view.physicalSize = const Size(390, 844);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(tester.getTopLeft(find.byKey(const Key('settlement-import'))).dy,
        greaterThanOrEqualTo(tester.getBottomLeft(title).dy));
    // The header scrolls away; the payouts under it lay out whole.
    await tester.scrollUntilVisible(find.byKey(const Key('settlement-3')), 200);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('Ready to sign off'), findsOneWidget);
  });

  testWidgets('a payout opens on what needs a decision, and every line is a tap away', (tester) async {
    final server = await _pump(tester, const SettlementDialog(id: '2'));

    expect(server.requests.first.queryParameters['open'], true);
    expect(find.text('Lines that need a decision'), findsOneWidget);
    expect(find.textContaining('AUTH-SHORT'), findsOneWidget);
    expect(find.textContaining('We hold £50.00'), findsOneWidget);
    expect(find.textContaining('Nothing here answers to it'), findsOneWidget);
    expect(find.textContaining('AUTH-1'), findsNothing, reason: 'a line that matched needs nobody');
    expect(find.byKey(const Key('settlement-sign-off')), findsNothing, reason: 'not while anything is open');

    await tester.tap(find.byKey(const Key('settlement-toggle-lines')));
    await tester.pumpAndSettle();
    expect(server.requests.last.queryParameters['open'], false);
    expect(find.textContaining('AUTH-1 '), findsNothing);
    expect(find.text('1. Sale £45.00 · AUTH-1'), findsOneWidget);
    expect(find.byKey(const Key('settlement-decide-1')), findsNothing, reason: 'nothing to decide on a match');
  });

  testWidgets('a decision is sent as decided, a reason is asked for, and then the payout can be signed off', (tester) async {
    final server = await _pump(tester, const SettlementDialog(id: '2'));

    // The short-paid one: the difference is what is offered first, and it wants a reason.
    await tester.tap(find.byKey(const Key('settlement-decide-2')));
    await tester.pumpAndSettle();
    expect(find.text('Right one, different sum: accept the difference'), findsOneWidget);
    await tester.tap(find.byKey(const Key('decide-save')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('decide-error')), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'POST'), isEmpty, reason: 'nothing is sent without a reason');
    await tester.enterText(find.byKey(const Key('decide-note')), 'Tip adjusted at the terminal');
    await tester.tap(find.byKey(const Key('decide-save')));
    await tester.pumpAndSettle();
    final first = server.requests.lastWhere((r) => r.method == 'POST');
    expect(first.path, endsWith('/2/lines/l2/resolve'));
    expect(first.data, {'resolution': 'DIFFERENCE_ACCEPTED', 'note': 'Tip adjusted at the terminal'});

    // The reserve is about no one payment: unallocated is all there is.
    await tester.tap(find.byKey(const Key('settlement-decide-3')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('decide-target')), findsNothing);
    await tester.enterText(find.byKey(const Key('decide-note')), 'Rolling reserve');
    await tester.tap(find.byKey(const Key('decide-save')));
    await tester.pumpAndSettle();
    expect(server.requests.lastWhere((r) => r.method == 'POST').data, {'resolution': 'UNALLOCATED', 'note': 'Rolling reserve'});

    expect(find.text('Nothing needs a decision'), findsOneWidget);
    await tester.tap(find.byKey(const Key('settlement-sign-off')));
    await tester.pumpAndSettle();
    expect(server.requests.last.path, endsWith('/2'));
    expect(server.requests.any((r) => r.path.endsWith('/2/reconcile')), isTrue);
    expect(find.textContaining('Reconciled'), findsOneWidget);
    expect(find.byKey(const Key('settlement-sign-off')), findsNothing, reason: 'signed off once');
  });

  testWidgets('a line is pointed at its payment by id, and a refusal is said in words', (tester) async {
    final server = await _pump(
      tester,
      const SettlementDialog(id: '2'),
      lines: [_line(1, 'SALE', 'AUTH-TYPED-WRONG', 'UNMATCHED')],
      batch: _batch('2', 'EXCEPTIONS', 1),
    );
    await tester.tap(find.byKey(const Key('settlement-decide-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('decide-save')));
    await tester.pumpAndSettle();
    expect(find.text('Say which payment it is about.'), findsOneWidget);

    server.refuseWith = 409;
    await tester.enterText(find.byKey(const Key('decide-target')), 'p-77');
    await tester.tap(find.byKey(const Key('decide-save')));
    await tester.pumpAndSettle();
    expect(server.requests.lastWhere((r) => r.method == 'POST').data, {'resolution': 'MATCHED_BY_HAND', 'targetId': 'p-77'});
    expect(find.byKey(const Key('settlement-error')), findsOneWidget);
    expect(find.textContaining('The sums differ'), findsOneWidget);
    expect(find.byKey(const Key('settlement-decide-1')), findsOneWidget, reason: 'still to be decided');
  });

  testWidgets('a payout in the books offers nothing more to do', (tester) async {
    await _pump(
      tester,
      const SettlementDialog(id: '1'),
      batch: _batch('1', 'RECONCILED', 0),
      lines: [_line(1, 'SALE', 'AUTH-1', 'UNMATCHED', resolution: 'UNALLOCATED', note: 'Not ours')],
    );
    await tester.tap(find.byKey(const Key('settlement-toggle-lines')));
    await tester.pumpAndSettle();
    expect(find.textContaining('Sent to unallocated receipts · fee £0.70 · Not ours'), findsOneWidget);
    expect(find.byKey(const Key('settlement-decide-1')), findsNothing);
    expect(find.byKey(const Key('settlement-sign-off')), findsNothing);
  });

  testWidgets('an import is not sent until it says who paid and has a file, and then carries both', (tester) async {
    final server = await _pump(tester, const ImportSettlementDialog(content: 'type,reference,gross\nSALE,A1,10.00\n'));

    await tester.tap(find.byKey(const Key('import-save')));
    await tester.pumpAndSettle();
    expect(find.text('Say who paid: the acquirer or provider\'s name.'), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'POST'), isEmpty);

    await tester.enterText(find.byKey(const Key('import-provider')), 'Worldpay');
    await tester.enterText(find.byKey(const Key('import-reference')), 'WP-9');
    await tester.enterText(find.byKey(const Key('import-declared')), 'ten pounds');
    await tester.tap(find.byKey(const Key('import-save')));
    await tester.pumpAndSettle();
    expect(find.text('The sum paid is a number, like 1234.56.'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('import-declared')), '10.00');
    await tester.tap(find.byKey(const Key('import-save')));
    await tester.pumpAndSettle();
    final sent = server.requests.lastWhere((r) => r.method == 'POST');
    expect(sent.data, {'provider': 'Worldpay', 'format': 'STOREQL', 'reference': 'WP-9', 'declaredNet': 10.0, 'content': 'type,reference,gross\nSALE,A1,10.00\n'});
    expect(sent.headers['Idempotency-Key'], isNotNull);
  });

  testWidgets('a file that does not add up is said in words, and no file at all is asked for', (tester) async {
    final server = await _pump(tester, const ImportSettlementDialog(content: 'type,reference,gross\nSALE,A1,10.00\n'));
    server
      ..refuseWith = 400
      ..refusal = 'SETTLEMENT_OUT_OF_BALANCE';
    await tester.enterText(find.byKey(const Key('import-provider')), 'Worldpay');
    await tester.tap(find.byKey(const Key('import-save')));
    await tester.pumpAndSettle();
    expect(find.textContaining('does not add up to the sum paid'), findsOneWidget);
    expect(find.byKey(const Key('import-save')), findsOneWidget, reason: 'still open, to be put right');
  });

  testWidgets('no file, no import', (tester) async {
    final server = await _pump(tester, const ImportSettlementDialog());
    await tester.enterText(find.byKey(const Key('import-provider')), 'Worldpay');
    await tester.tap(find.byKey(const Key('import-save')));
    await tester.pumpAndSettle();
    expect(find.text('Choose the settlement file.'), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'POST'), isEmpty);
  });
}
