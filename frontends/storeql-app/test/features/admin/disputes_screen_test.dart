import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/format.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/disputes_screen.dart';

import '../../support/fake_api.dart';
import '../../support/mid_word.dart';

// ---------------------------------------------------------------------------
// The chargeback register (11.9): what needs an answer is said first and with
// its date, a dispute past its date says so, the answer is given once and sent
// as typed, and only a dispute the acquirer told the business about can be
// closed by hand.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  Map<String, dynamic> file;

  _Server(this.file);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.method == 'GET' && o.path.endsWith('/admin/disputes')) {
      final wanted = o.queryParameters['status'];
      final all = [_needs, _overdue, _stripe];
      return jsonResponse(jsonEncode({'data': all.where((d) => wanted == null || d['status'] == wanted).toList()}));
    }
    if (o.method == 'POST' && o.path.endsWith('/evidence')) {
      final dispute = Map<String, dynamic>.from(file['dispute'] as Map)..['status'] = 'UNDER_REVIEW';
      file = {
        'dispute': dispute,
        'history': [...(file['history'] as List), {'kind': 'EVIDENCE_SUBMITTED', 'detail': 'Kept here', 'at': '2026-09-18T10:00:00Z'}],
        'evidence': {...(o.data as Map), 'submittedBy': 'u', 'submittedAt': '2026-09-18T10:00:00Z'},
      };
    }
    if (o.method == 'POST' && o.path.endsWith('/resolve')) {
      file = {...file, 'dispute': Map<String, dynamic>.from(file['dispute'] as Map)..['status'] = (o.data as Map)['outcome']};
    }
    return jsonResponse(jsonEncode({'data': file}));
  }
}

Map<String, dynamic> _dispute(String id, String status, {String provider = 'MANUAL', bool overdue = false}) => {
      'id': id,
      'paymentId': 'p-$id',
      'orderId': 'o-$id',
      'provider': provider,
      'reference': 'CB-$id',
      'amount': 45.99,
      'feeAmount': 15,
      'currency': 'GBP',
      'reason': 'PRODUCT_NOT_RECEIVED',
      'status': status,
      'fundsWithdrawn': true,
      'evidenceDueBy': '2026-09-27T23:59:00Z',
      'overdue': overdue,
      'openedAt': '2026-09-17T09:30:00Z',
    };

final _needs = _dispute('1', 'NEEDS_RESPONSE');
final _overdue = _dispute('2', 'NEEDS_RESPONSE', overdue: true);
final _stripe = _dispute('3', 'UNDER_REVIEW', provider: 'STRIPE');

Future<_Server> _pump(WidgetTester tester, Map<String, dynamic> file, Widget child,
    {Size size = const Size(1400, 2400)}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(file);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
      child: MaterialApp(home: Scaffold(body: child)),
    ),
  );
  await tester.pumpAndSettle();
  return server;
}

Map<String, dynamic> _fileOf(Map<String, dynamic> dispute) => {
      'dispute': dispute,
      'history': [
        {'kind': 'OPENED', 'detail': 'Recorded from the acquirer\'s notice', 'at': '2026-09-17T09:30:00Z'},
        {'kind': 'FUNDS_WITHDRAWN', 'detail': '45.99 GBP and a fee of 15', 'at': '2026-09-17T09:30:00Z'},
      ],
      'evidence': null,
    };

// The due date, as the shop reads it: its own clock, not the stored UTC one.
final _due = AppFormat.dateTime('2026-09-27T23:59:00Z');

void main() {
  // The register writes its dates with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);

  testWidgets('the register says what is disputed, why, and by when it must be answered', (tester) async {
    final server = await _pump(tester, _fileOf(_needs), const DisputesScreen());

    expect(find.text('£45.99 · Says they never got it'), findsNWidgets(3));
    expect(find.text('Answer by $_due'), findsOneWidget);
    expect(find.text('The date to answer has passed ($_due)'), findsOneWidget);
    expect(find.textContaining('GBP'), findsNothing, reason: 'money in its own form, not a number and a code');
    expect(find.textContaining('UTC'), findsNothing, reason: 'dates on the shop\'s clock');
    expect(find.textContaining('2026-09-'), findsNothing, reason: 'no ISO dates');
    expect(find.text('With the bank'), findsNWidgets(2), reason: 'the badge and the filter');

    await tester.tap(find.widgetWithText(ChoiceChip, 'Needs an answer'));
    await tester.pumpAndSettle();
    expect(server.requests.last.queryParameters['status'], 'NEEDS_RESPONSE');
    expect(find.text('£45.99 · Says they never got it'), findsNWidgets(2));
  });

  testWidgets('on a phone the title keeps its words, the actions go under it, and the page is inset 16', (tester) async {
    await _pump(tester, _fileOf(_needs), const DisputesScreen(), size: const Size(390, 844));

    final title = find.text('Chargebacks');
    expect(breaksMidWord(tester, title), isFalse, reason: 'the title is never squeezed to a few letters a line');
    expect(tester.getTopLeft(find.byKey(const Key('dispute-record'))).dy,
        greaterThanOrEqualTo(tester.getBottomLeft(title).dy),
        reason: 'the actions wrap under the title');
    expect(tester.getTopLeft(title).dx, 16, reason: 'the page gutter on a phone');
  });

  testWidgets('just under 600px the actions are under the title too, and 200% text on a phone does not overflow', (tester) async {
    await _pump(tester, _fileOf(_needs), const DisputesScreen(), size: const Size(599, 900));
    final title = find.text('Chargebacks');
    expect(breaksMidWord(tester, title), isFalse);
    expect(tester.getTopLeft(find.byKey(const Key('dispute-record'))).dy,
        greaterThanOrEqualTo(tester.getBottomLeft(title).dy));

    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    tester.view.physicalSize = const Size(390, 844);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await tester.scrollUntilVisible(find.byKey(const Key('dispute-3')), 200);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('a dispute\'s sums are money and its history is dated on the shop\'s clock', (tester) async {
    await _pump(tester, _fileOf(_needs), const DisputeDialog(id: '1'));

    expect(find.text('£45.99 · Needs an answer'), findsOneWidget);
    expect(find.textContaining('fee £15.00'), findsOneWidget);
    expect(find.textContaining('answer by $_due'), findsOneWidget);
    expect(find.text(AppFormat.dateTime('2026-09-17T09:30:00Z')), findsNWidgets(2));
    expect(find.textContaining('UTC'), findsNothing);
  });

  testWidgets('a dispute is answered once, with what was typed, and then it is with the bank', (tester) async {
    final server = await _pump(tester, _fileOf(_needs), const DisputeDialog(id: '1'));

    expect(find.text('The bank took the money — 45.99 GBP and a fee of 15'), findsOneWidget);
    expect(find.textContaining('told by the acquirer'), findsOneWidget);
    await tester.tap(find.byKey(const Key('dispute-answer')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('evidence-productDescription')), 'Two crates of oranges');
    await tester.enterText(find.byKey(const Key('evidence-receiptReference')), 'R-1042');
    await tester.tap(find.byKey(const Key('dispute-send')));
    await tester.pumpAndSettle();

    final sent = server.requests.lastWhere((r) => r.path.endsWith('/evidence'));
    expect(sent.data, {'productDescription': 'Two crates of oranges', 'receiptReference': 'R-1042'}, reason: 'empty fields are not sent');
    expect(find.textContaining('With the bank'), findsOneWidget);
    expect(find.text('The answer given'), findsOneWidget);
    expect(find.byKey(const Key('dispute-answer')), findsNothing, reason: 'a scheme takes evidence once');
  });

  testWidgets('past its date a dispute cannot be answered, only closed', (tester) async {
    await _pump(tester, _fileOf(_overdue), const DisputeDialog(id: '2'));
    expect(find.byKey(const Key('dispute-answer')), findsNothing);
    expect(find.byKey(const Key('dispute-accept')), findsOneWidget);
    expect(find.byKey(const Key('dispute-lost')), findsOneWidget);
  });

  testWidgets('a provider\'s dispute is not decided by hand', (tester) async {
    await _pump(tester, _fileOf(_stripe), const DisputeDialog(id: '3'));
    expect(find.textContaining('told by STRIPE'), findsOneWidget);
    expect(find.byKey(const Key('dispute-won')), findsNothing);
    expect(find.byKey(const Key('dispute-lost')), findsNothing);
    expect(find.byKey(const Key('dispute-accept')), findsOneWidget);
  });

  testWidgets('the acquirer\'s dispute is closed by saying how it ended', (tester) async {
    final server = await _pump(tester, _fileOf(_needs), const DisputeDialog(id: '1'));
    await tester.tap(find.byKey(const Key('dispute-won')));
    await tester.pumpAndSettle();
    expect(server.requests.last.data, {'outcome': 'WON'});
    expect(find.textContaining('Won'), findsWidgets);
    expect(find.byKey(const Key('dispute-accept')), findsNothing, reason: 'closed: nothing left to do');
  });

  testWidgets('recording a chargeback asks for the payment, the case number and the date', (tester) async {
    final server = await _pump(tester, _fileOf(_needs), const RecordDisputeDialog());
    await tester.tap(find.byKey(const Key('dispute-save')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('dispute-record-error')), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'POST'), isEmpty, reason: 'nothing is sent until it is complete');
  });
}
