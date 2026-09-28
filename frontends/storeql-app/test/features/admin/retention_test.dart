import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/format.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/theme.dart';
import 'package:storeql_app/features/admin/retention_screen.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// Data retention (21.16), from the admin screen.
//
// The server holds the floors and refuses a period under them; what the screen
// must get right is what it says — the law's floor beside each class, "not
// set" as the gap it is — and what it sends: a period no shorter than the
// floor, a hold with its subject and reason, a release with its reason, and a
// purge run against the service that owns the class. A storekeeper reads and
// changes nothing.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Adapter implements HttpClientAdapter {
  final List<RequestOptions> writes = [];
  (int, String) sheet = (200, _sheet);
  (int, String) setReply = (200, '{"data":{"code":"TRANSACTIONS","periodDays":3650}}');

  @override
  void close({bool force = false}) {}

  ResponseBody _json(String body, int status) => ResponseBody.fromString(body, status,
      headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    final path = o.path;
    if (o.method != 'GET') {
      writes.add(o);
      if (o.method == 'PUT') return _json(setReply.$2, setReply.$1);
      if (path.endsWith('/sweep')) return _json('{"data":{"dataClass":"ORDER_PERSONAL_DATA","rowsAffected":3,"heldSkipped":1}}', 200);
      if (path.endsWith('/holds')) return _json('{"data":{"id":"h-2","subjectKind":"ORDER","subjectId":"o-1","reason":"Dispute","active":true}}', 201);
      return _json('{"data":{"id":"h-1","active":false}}', 200);
    }
    if (path.endsWith('/admin/tenant/retention/runs')) return _json(_runs, 200);
    if (path.endsWith('/admin/tenant/retention')) return _json(sheet.$2, sheet.$1);
    return _json('{"data":[]}', 200);
  }
}

class _Auth extends AuthNotifier {
  final String role;
  _Auth(this.role);

  @override
  Future<AuthState> build() async => AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: 'user-1',
        tenantId: 'tenant-1',
        roles: [role],
        storeIds: const [],
      );
}

const _sheet = '''
{"data":{"country":"GB","countries":["DE","GB"],"classes":[
 {"code":"CUSTOMER_RECORDS","name":"Customer records","purgeKind":"ANONYMISE","purgedBy":"customer-svc","description":"A customer record once inactive."},
 {"code":"NOTIFICATION_LOG","name":"Messages sent","purgeKind":"DELETE","purgedBy":"notification-svc","description":"The log of messages sent.","periodDays":365},
 {"code":"ORDER_PERSONAL_DATA","name":"Personal details on settled orders","purgeKind":"ANONYMISE","purgedBy":"order-svc","description":"The delivery details on a settled order.","periodDays":0},
 {"code":"TRANSACTIONS","name":"Transactions","purgeKind":"KEEP","description":"The VAT record of each sale.","floorDays":2920,"floorScope":"DE","floorCitation":"Abgabenordnung §147(3)","floorSummary":"Eight years."}],
 "holds":[{"id":"h-1","subjectKind":"CUSTOMER","subjectId":"01a09509-72ec-72e9-9f08-94a93df26a36","dataClass":"CUSTOMER_RECORDS","reason":"Open dispute","placedAt":"2026-09-10T09:00:00Z","active":true}]}}''';
const _runs = '''
{"data":[{"id":"r-1","service":"order-svc","dataClass":"ORDER_PERSONAL_DATA","cutoff":"2026-09-14T03:00:00Z","rowsAffected":12,"heldSkipped":2,"startedAt":"2026-09-14T03:00:00Z","finishedAt":"2026-09-14T03:00:03Z"}]}''';

Future<_Adapter> _pump(WidgetTester tester, {String role = 'MANAGER', _Adapter? adapter}) async {
  final a = adapter ?? _Adapter();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = a;
  tester.view.physicalSize = const Size(1400, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => _Auth(role)),
    ],
    // The app's own theme: its Card has no margin, so any gap between the
    // class cards is the screen's.
    child: MaterialApp(
      theme: AppTheme.light,
      home: const Scaffold(body: RetentionScreen()),
    ),
  ));
  await tester.pumpAndSettle();
  return a;
}

Map<String, dynamic> _body(RequestOptions o) =>
    (o.data is String ? jsonDecode(o.data as String) : o.data) as Map<String, dynamic>;

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  setUpAll(initializeDateFormatting);

  testWidgets('the schedule shows the law\'s floor, what is set, and what is not', (tester) async {
    await _pump(tester);
    expect(find.text('Trading in DE, GB'), findsOneWidget);
    expect(find.textContaining('at least 8 years (DE) — Abgabenordnung'), findsOneWidget);
    // Two classes are unset in the fixture, and each says so as the gap it is.
    expect(find.text('Not set — nothing is purged, and the record art.30 asks for is missing.'), findsNWidgets(2));
    expect(
        tester.widget<Text>(find.byKey(const Key('retention-period-TRANSACTIONS'))).data,
        'Not set — nothing is purged, and the record art.30 asks for is missing.');
    expect(find.text('Anonymised at once, by order-svc.'), findsOneWidget);
    expect(find.text('Deleted after 1 year, by notification-svc.'), findsOneWidget);
    expect(find.textContaining('Customer …'), findsOneWidget);
    expect(find.textContaining('12 purged · 2 held'), findsOneWidget);
  });

  testWidgets('holds and purge runs name the class, not its code', (tester) async {
    await _pump(tester);
    expect(find.textContaining('· Customer records'), findsOneWidget);
    expect(find.text('Personal details on settled orders'), findsWidgets);
    expect(
      find.descendant(
        of: find.byKey(const Key('retention-run-row-r-1')),
        matching: find.text('Personal details on settled orders'),
      ),
      findsOneWidget,
    );
    expect(find.textContaining('CUSTOMER_RECORDS'), findsNothing);
    expect(find.textContaining('ORDER_PERSONAL_DATA'), findsNothing);
  });

  testWidgets('dates are written as AppFormat writes them everywhere else', (tester) async {
    await _pump(tester);
    final placed = AppFormat.date('2026-09-10T09:00:00Z');
    final cutoff = AppFormat.date('2026-09-14T03:00:00Z');
    final finished = AppFormat.dateTime('2026-09-14T03:00:03Z');
    // en_GB abbreviates September "Sept"; a DateFormat with no locale says "Sep".
    expect(placed, contains('Sept 2026'));
    expect(find.textContaining('placed $placed'), findsOneWidget);
    expect(find.textContaining('older than $cutoff · $finished'), findsOneWidget);
    expect(find.textContaining('Sep 2026'), findsNothing);
  });

  testWidgets('the class cards stand 8 apart instead of touching', (tester) async {
    await _pump(tester);
    final first = tester.getRect(find.byKey(const Key('retention-class-CUSTOMER_RECORDS')));
    final second = tester.getRect(find.byKey(const Key('retention-class-NOTIFICATION_LOG')));
    expect(second.top - first.bottom, 8);
  });

  testWidgets('a period under the floor never leaves the screen; one above it is saved', (tester) async {
    final adapter = await _pump(tester);
    await tester.tap(find.byKey(const Key('retention-set-TRANSACTIONS')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('retention-period')), '2190');
    await tester.tap(find.byKey(const Key('retention-save')));
    await tester.pumpAndSettle();
    expect(find.textContaining('at least 2920 days'), findsOneWidget);
    expect(adapter.writes, isEmpty);
    await tester.enterText(find.byKey(const Key('retention-period')), 'ten');
    await tester.tap(find.byKey(const Key('retention-save')));
    await tester.pumpAndSettle();
    expect(find.text('Enter a number of days from 0 to 36500.'), findsOneWidget);
    // One day under the floor is still under it.
    await tester.enterText(find.byKey(const Key('retention-period')), '2919');
    await tester.tap(find.byKey(const Key('retention-save')));
    await tester.pumpAndSettle();
    expect(find.textContaining('at least 2920 days'), findsOneWidget);
    expect(adapter.writes, isEmpty);
    await tester.enterText(find.byKey(const Key('retention-period')), '3650');
    await tester.tap(find.byKey(const Key('retention-save')));
    await tester.pumpAndSettle();
    final put = adapter.writes.single;
    expect(put.method, 'PUT');
    expect(put.path, endsWith('/admin/tenant/retention/TRANSACTIONS'));
    expect(_body(put), {'periodDays': 3650});
  });

  testWidgets("the server's refusal is shown in its words", (tester) async {
    final adapter = _Adapter()
      ..setReply = (400, '{"error":{"code":"RETENTION_BELOW_LEGAL_MINIMUM","message":"The law requires at least 2190 days for TRANSACTIONS (VATA 1994)"}}');
    await _pump(tester, adapter: adapter);
    await tester.tap(find.byKey(const Key('retention-set-CUSTOMER_RECORDS')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('retention-period')), '30');
    await tester.tap(find.byKey(const Key('retention-save')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('retention-set-error')), findsOneWidget);
    expect(find.textContaining('at least 2190 days'), findsOneWidget);
  });

  testWidgets('a hold is placed with its subject and reason, released with a reason, and a purge run', (tester) async {
    final adapter = await _pump(tester);
    await tester.tap(find.byKey(const Key('retention-hold-place')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('retention-hold-save')));
    await tester.pumpAndSettle();
    expect(find.text('Say why the hold is placed.'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('retention-hold-reason')), 'Dispute');
    await tester.tap(find.text('An order'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('retention-hold-subject')), 'o-1');
    await tester.tap(find.byKey(const Key('retention-hold-save')));
    await tester.pumpAndSettle();
    final placed = adapter.writes.singleWhere((w) => w.path.endsWith('/retention/holds'));
    expect(_body(placed), {'subjectKind': 'ORDER', 'subjectId': 'o-1', 'reason': 'Dispute'});

    await tester.tap(find.byKey(const Key('retention-hold-release-h-1')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('retention-reason')), 'Settled');
    await tester.tap(find.byKey(const Key('retention-reason-confirm')));
    await tester.pumpAndSettle();
    final released = adapter.writes.singleWhere((w) => w.path.endsWith('/holds/h-1/release'));
    expect(_body(released), {'reason': 'Settled'});

    await tester.tap(find.byKey(const Key('retention-run-order-svc')));
    await tester.pumpAndSettle();
    final run = adapter.writes.singleWhere((w) => w.path.endsWith('/sweep'));
    expect(run.path, endsWith('/order-svc/admin/orders/retention/sweep'));
    expect(find.text('order-svc: 3 purged, 1 held'), findsOneWidget);
  });

  testWidgets('a storekeeper reads and changes nothing; a failed load is an error', (tester) async {
    await _pump(tester, role: 'STOREKEEPER');
    expect(find.byKey(const Key('retention-set-TRANSACTIONS')), findsNothing);
    expect(find.byKey(const Key('retention-hold-place')), findsNothing);
    expect(find.byKey(const Key('retention-run-order-svc')), findsNothing);
    expect(find.byKey(const Key('retention-hold-release-h-1')), findsNothing);

    final broken = _Adapter()..sheet = (503, '{"error":{"code":"UNAVAILABLE","message":"Service unavailable"}}');
    await tester.pumpWidget(const SizedBox());
    await _pump(tester, adapter: broken);
    expect(find.text('Service unavailable'), findsOneWidget);
    expect(find.text('Not set — nothing is purged, and the record art.30 asks for is missing.'), findsNothing);
  });
}
