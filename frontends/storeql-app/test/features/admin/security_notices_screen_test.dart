import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/format.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/security_notices_screen.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// Security notices a business has been sent (21.15): what is unread, what was
// acknowledged and when, and what the screen does when the server refuses.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Tenant implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  int listStatus = 200;
  String list = '{"data":[]}';
  int ackStatus = 200;
  String ackBody = '{"data":{}}';
  String? listAfterAck;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final ack = o.method == 'POST';
    if (ack && ackStatus == 200 && listAfterAck != null) list = listAfterAck!;
    return ResponseBody.fromString(ack ? ackBody : list, ack ? ackStatus : listStatus, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Future<_Tenant> _pump(WidgetTester tester, void Function(_Tenant) setUp) async {
  tester.view.physicalSize = const Size(1100, 1400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final tenant = _Tenant();
  setUp(tenant);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = tenant;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: const MaterialApp(home: Scaffold(body: SecurityNoticesScreen())),
  ));
  await tester.pumpAndSettle();
  return tenant;
}

String _notice(String id, {String? acknowledgedAt}) => '{"id":"$id","incidentId":"i-$id",'
    '"title":"Security notice: Customer emails exposed ($id)",'
    '"body":"Customer email addresses were exposed. You have 72 hours to tell your supervisory authority.",'
    '"issuedAt":"2026-09-14T08:00:00Z","acknowledgedAt":${acknowledgedAt == null ? 'null' : '"$acknowledgedAt"'},'
    '"acknowledged":${acknowledgedAt != null}}';

String _breach(String id, {bool boardDone = false}) => '{"id":"$id","incidentId":"i-$id",'
    '"title":"Security notice: names read","body":"Names and emails were read.",'
    '"issuedAt":"2026-09-14T08:00:00Z","acknowledgedAt":null,"acknowledged":false,'
    '"regime":"DPDP","binding":false,"bindsFrom":"2027-05-13","duties":['
    '{"duty":"PRINCIPALS_TOLD","citation":"DPDP Rules 2025 r.7(1)","summary":"Each affected person told without delay","dueAt":null,"state":"WAITING"},'
    '{"duty":"BOARD_INTIMATED","citation":"DPDP Rules 2025 r.7(2)(a)","summary":"The Board told without delay","dueAt":null,"state":"${boardDone ? 'DONE' : 'WAITING'}"${boardDone ? ',"doneAt":"2026-09-14T09:00:00Z","reference":"DPB-0042"' : ''}},'
    '{"duty":"BOARD_REPORTED","citation":"DPDP Rules 2025 r.7(2)(b)","summary":"Reported within seventy-two hours","dueAt":"2026-09-17T08:00:00Z","state":"OVERDUE"}]}';

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  // ── 13.12: the business's own duties on a breach ───────────────────────────

  testWidgets('a breach notice lists the DPDP duties with their clocks, and the date the Act binds from',
      (tester) async {
    await _pump(tester, (t) => t.list = '{"data":[${_breach('b1', boardDone: true)}]}');
    expect(find.textContaining("duties under India's DPDP Act (from 13 May 2027)"), findsOneWidget);
    expect(find.byKey(const Key('duty-b1-PRINCIPALS_TOLD')), findsOneWidget);
    expect(find.textContaining('Without delay'), findsWidgets);
    expect(find.textContaining('Overdue: was due'), findsOneWidget);
    expect(find.textContaining('DPB-0042'), findsOneWidget);
    expect(find.byKey(const Key('record-b1-BOARD_INTIMATED')), findsNothing, reason: 'done is done');
    expect(find.text('Tell customers'), findsOneWidget);
    expect(find.text('Record'), findsOneWidget);
  });

  testWidgets('the deadline of a duty is its own line in the title style, apart from the rule it cites',
      (tester) async {
    await _pump(tester, (t) => t.list = '{"data":[${_breach('b1')}]}');
    final due = find.text('Overdue: was due ${AppFormat.dateTime('2026-09-17T08:00:00Z')}');
    expect(due, findsOneWidget, reason: 'the deadline is not run into the citation');
    final rule = find.text('Reported within seventy-two hours — DPDP Rules 2025 r.7(2)(b)');
    expect(rule, findsOneWidget);
    final dueStyle = tester.widget<Text>(due).style!;
    final titleStyle =
        tester.widget<Text>(find.text('Reported to the Board')).style!;
    expect(dueStyle.fontSize, titleStyle.fontSize);
    expect(dueStyle.fontSize, greaterThan(tester.widget<Text>(rule).style!.fontSize!));
    expect(tester.getRect(due).bottom, lessThanOrEqualTo(tester.getRect(rule).top));
    // Overdue says so in words and in the error colour.
    final cs = Theme.of(tester.element(due)).colorScheme;
    expect(dueStyle.color, cs.error);
  });

  testWidgets('on a phone at 200% text a breach notice and its duties still fit', (tester) async {
    await _pump(tester, (t) => t.list = '{"data":[${_breach('b1')}]}');
    tester.view.physicalSize = const Size(390, 844);
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.byKey(const Key('record-b1-BOARD_REPORTED')), 200,
        scrollable: find.byType(Scrollable).first);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('notice cards stand 12px apart', (tester) async {
    await _pump(tester, (t) => t.list = '{"data":[${_notice('n1')},${_notice('n2')}]}');
    final first = tester.getRect(find.byKey(const Key('notice-n1')));
    final second = tester.getRect(find.byKey(const Key('notice-n2')));
    expect(second.top - first.bottom, 12);
  });

  testWidgets('recording a duty posts it once with the reference and note', (tester) async {
    final tenant = await _pump(tester, (t) {
      t.list = '{"data":[${_breach('b1')}]}';
      t.ackBody = '{"data":${_breach('b1', boardDone: true)}}';
    });
    await tester.tap(find.byKey(const Key('record-b1-BOARD_REPORTED')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('duty-reference')), 'DPB-0042');
    await tester.enterText(find.byKey(const Key('duty-note')), 'Filed on the portal.');
    await tester.tap(find.byKey(const Key('duty-save')));
    await tester.pumpAndSettle();
    final posts = tenant.requests.where((r) => r.method == 'POST').toList();
    expect(posts.single.path, '/tenant-svc/admin/tenant/security-notices/b1/reports');
    expect((posts.single.data as Map)['duty'], 'BOARD_REPORTED');
    expect((posts.single.data as Map)['reference'], 'DPB-0042');
    expect((posts.single.data as Map)['note'], 'Filed on the portal.');
  });

  testWidgets('a notice of anything but a breach carries no duties', (tester) async {
    await _pump(tester, (t) => t.list = '{"data":[${_notice('n1')}]}');
    expect(find.byKey(const Key('duties-n1')), findsNothing);
    expect(find.text('Record'), findsNothing);
  });

  setUpAll(initializeDateFormatting);

  testWidgets('an unread notice offers Acknowledge; a read one says when', (tester) async {
    final tenant = await _pump(tester, (t) => t.list = '{"data":[${_notice('n1')},${_notice('n2', acknowledgedAt: '2026-09-14T09:30:00Z')}]}');

    expect(tenant.requests.single.path, '/tenant-svc/admin/tenant/security-notices');
    expect(find.text('Security notice: Customer emails exposed (n1)'), findsOneWidget);
    expect(find.textContaining('72 hours to tell your supervisory authority'), findsNWidgets(2));
    expect(find.text('Sent ${AppFormat.dateTime('2026-09-14T08:00:00Z')}'), findsNWidgets(2));
    expect(find.byKey(const Key('acknowledge-n1')), findsOneWidget);
    expect(find.byKey(const Key('acknowledge-n2')), findsNothing);
    expect(find.text('Acknowledged ${AppFormat.dateTime('2026-09-14T09:30:00Z')}'), findsOneWidget);
  });

  testWidgets('acknowledging posts once, to that notice, and then shows it read', (tester) async {
    final tenant = await _pump(tester, (t) {
      t.list = '{"data":[${_notice('n1')}]}';
      t.listAfterAck = '{"data":[${_notice('n1', acknowledgedAt: '2026-09-14T10:00:00Z')}]}';
    });

    await tester.tap(find.byKey(const Key('acknowledge-n1')));
    await tester.pumpAndSettle();

    final posts = tenant.requests.where((r) => r.method == 'POST').toList();
    expect(posts, hasLength(1));
    expect(posts.single.path, '/tenant-svc/admin/tenant/security-notices/n1/acknowledge');
    expect(find.byKey(const Key('acknowledge-n1')), findsNothing);
    expect(find.text('Acknowledged ${AppFormat.dateTime('2026-09-14T10:00:00Z')}'), findsOneWidget);
  });

  testWidgets('a refused acknowledgement says why and leaves the notice unread', (tester) async {
    await _pump(tester, (t) {
      t.list = '{"data":[${_notice('n1')}]}';
      t.ackStatus = 404;
      t.ackBody = '{"error":{"code":"SECURITY_NOTICE_NOT_FOUND","message":"This business has no such security notice"}}';
    });

    await tester.tap(find.byKey(const Key('acknowledge-n1')));
    await tester.pumpAndSettle();

    expect(find.text('This business has no such security notice'), findsOneWidget);
    final button = tester.widget<ButtonStyleButton>(find.byKey(const Key('acknowledge-n1')));
    expect(button.onPressed, isNotNull, reason: 'it can be tried again');
  });

  testWidgets('no notices says so plainly', (tester) async {
    await _pump(tester, (t) => t.list = '{"data":[]}');
    expect(find.text('The platform has sent this business no security notices.'), findsOneWidget);
    expect(find.byType(Card), findsNothing);
  });

  testWidgets('a failed read says so, with a way to try again', (tester) async {
    await _pump(tester, (t) {
      t.listStatus = 403;
      t.list = '{"error":{"code":"FORBIDDEN","message":"Insufficient role for this operation"}}';
    });
    expect(find.byType(Card), findsNothing);
    expect(find.textContaining('Retry', findRichText: true), findsWidgets);
  });
}
