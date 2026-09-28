import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/spacing.dart';
import 'package:storeql_app/features/admin/audit_trail_screen.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';

// ---------------------------------------------------------------------------
// The business audit trail (20.11): order-svc's events and inventory's
// adjustments as one timeline, newest first, naming who did what. The filters
// ask the server; "Load older" pages order-svc only; a refusal is shown in
// words; nothing on the screen can write.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  bool empty = false;
  bool forbidden = false;

  /// Answers the first page with one of every other kind of till event.
  bool everyKind = false;
  final DateTime now = DateTime.now().toUtc();

  /// How long every answer takes.
  Duration delay = Duration.zero;

  String _at(int hoursAgo) => now.subtract(Duration(hours: hoursAgo)).toIso8601String();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (delay > Duration.zero) await Future<void>.delayed(delay);
    String body;
    var status = 200;
    if (o.path.endsWith('/admin/audit/events')) {
      if (forbidden) {
        status = 403;
        body = '{"error":{"code":"FORBIDDEN","message":"management role required"}}';
      } else if (empty) {
        body = '{"data":[],"meta":{}}';
      } else if (o.queryParameters['after'] == 'c1') {
        body = '{"data":[{"id":"e-3","type":"NO_SALE","occurredAt":"${_at(4)}","actorId":"u-3",'
            '"storeId":"s1","reason":"drawer check"}],"meta":{"nextCursor":null}}';
      } else if (everyKind) {
        body = '{"data":['
            '{"id":"e-5","type":"RETURN","occurredAt":"${_at(1)}","actorId":"u-2","storeId":"s1","orderId":"o-5","amount":4.2,"reason":"wrong size","detail":"STORE_CREDIT"},'
            '{"id":"e-6","type":"CANCEL","occurredAt":"${_at(2)}","actorId":"u-1","storeId":"s1","orderId":"o-6","reason":"customer asked","detail":"CONFIRMED"},'
            '{"id":"e-7","type":"NO_SALE","occurredAt":"${_at(3)}","actorId":"u-2","storeId":"s1","reason":"change for the float","detail":"u-1"}'
            '],"meta":{}}';
      } else if (o.queryParameters['type'] == 'VOID') {
        body = '{"data":[{"id":"e-1","type":"VOID","occurredAt":"${_at(1)}","actorId":"u-1",'
            '"storeId":"s1","orderId":"o-1","reason":"rang up twice"}],"meta":{}}';
      } else {
        body = '{"data":['
            '{"id":"e-1","type":"VOID","occurredAt":"${_at(1)}","actorId":"u-1","storeId":"s1","orderId":"o-1","reason":"rang up twice"},'
            '{"id":"e-2","type":"DISCOUNT","occurredAt":"${_at(3)}","actorId":"u-2","storeId":"s1","orderId":"o-2","amount":2.0,"reason":"damaged box","detail":"MANAGER"}'
            '],"meta":{"nextCursor":"c1"}}';
      }
    } else if (o.path.endsWith('/iam-svc/auth/admin/staff-users')) {
      // iam-svc names the business's own staff; anyone else is left out.
      final ids = '${o.queryParameters['ids']}'.split(',');
      body = '{"data":['
          '${[
            if (ids.contains('u-1')) '{"userId":"u-1","email":"ana@shop.test"}',
            if (ids.contains('u-2')) '{"userId":"u-2","email":"ben@shop.test"}',
          ].join(',')}'
          ']}';
    } else if (o.path.endsWith('/admin/products/variants/resolve')) {
      body = '{"data":[{"variantId":"v-1","productName":"Oat milk 1L","sku":"OAT-1"}]}';
    } else if (o.path.endsWith('/admin/inventory/movements')) {
      body = empty
          ? '{"data":[]}'
          : '{"data":[{"id":"m-1","storeId":"s1","variantId":"v-1","type":"ADJUST","qty":-3,'
              '"reasonCode":"DAMAGED","actorId":"u-1","createdAt":"${_at(2)}"}]}';
    } else {
      body = '{"data":[]}';
    }
    return ResponseBody.fromString(body, status,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Future<_Server> _pump(WidgetTester tester,
    {bool empty = false,
    bool forbidden = false,
    bool everyKind = false,
    Size size = const Size(1200, 1400),
    double textScale = 1,
    Duration delay = Duration.zero}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final server = _Server()
    ..empty = empty
    ..forbidden = forbidden
    ..everyKind = everyKind
    ..delay = delay;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      storesProvider.overrideWith((ref) async => const [
            StoreInfo(id: 's1', name: 'High Street', code: 'HS', type: 'STORE', status: 'ACTIVE', country: 'GB'),
          ]),
      tenantInfoProvider.overrideWith((ref) async => const TenantInfo(
          id: 't', name: 'Corner Shop', status: 'ACTIVE', currency: 'GBP', country: 'GB')),
      staffProvider.overrideWith((ref) async => const [
            StaffMember(id: 'a1', userId: 'u-1', storeId: 's1', role: 'MANAGER', assignedAt: ''),
            StaffMember(id: 'a2', userId: 'u-2', storeId: 's1', role: 'CASHIER', assignedAt: ''),
          ]),
    ],
    child: MaterialApp(
      home: MediaQuery.withClampedTextScaling(
        minScaleFactor: textScale,
        maxScaleFactor: textScale,
        child: const Scaffold(body: AuditTrailScreen()),
      ),
    ),
  ));
  await tester.pumpAndSettle();
  if (delay > Duration.zero) {
    for (var i = 0; i < 5; i++) {
      await tester.pump(delay);
      await tester.pumpAndSettle();
    }
  }
  return server;
}

Iterable<RequestOptions> _trailRequests(_Server s) =>
    s.requests.where((r) => r.path.endsWith('/admin/audit/events'));

double _top(WidgetTester tester, String text) => tester.getTopLeft(find.text(text)).dy;

const _adjustment = 'Stock adjustment · -3 × Oat milk 1L';
const _discount = 'Discount · £2.00 · authorised as manager';

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  setUpAll(initializeDateFormatting);

  testWidgets('both sources are one timeline, newest first, naming who did what', (tester) async {
    final server = await _pump(tester);
    expect(find.text('Void'), findsOneWidget);
    expect(find.text(_adjustment), findsOneWidget);
    expect(find.text(_discount), findsOneWidget);
    // The adjustment from inventory sits between the two till events by time.
    expect(_top(tester, 'Void') < _top(tester, _adjustment), isTrue);
    expect(_top(tester, _adjustment) < _top(tester, _discount), isTrue);
    expect(find.textContaining('by ana@shop.test · order o-1 · rang up twice'), findsOneWidget);
    expect(find.textContaining('by ben@shop.test · order o-2 · damaged box'), findsOneWidget);
    expect(find.text('Till'), findsNWidgets(2));
    expect(find.text('Stock'), findsOneWidget);
    // The first page asked order-svc with the period and inventory for adjustments only.
    final first = _trailRequests(server).first;
    expect(first.queryParameters['from'], isNotNull);
    expect(first.queryParameters['to'], isNotNull);
    expect(first.queryParameters.containsKey('type'), isFalse);
    final stock = server.requests.singleWhere((r) => r.path.endsWith('/admin/inventory/movements'));
    expect(stock.queryParameters['type'], 'ADJUST');
    // With rows loaded, the export is offered.
    expect(tester.widget<OutlinedButton>(find.byKey(const Key('audit-export'))).onPressed, isNotNull);
  });

  testWidgets('Load older pages order-svc with its cursor and merges the page in', (tester) async {
    final server = await _pump(tester);
    expect(find.byKey(const Key('audit-load-older')), findsOneWidget);
    await tester.tap(find.byKey(const Key('audit-load-older')));
    await tester.pumpAndSettle();
    expect(_trailRequests(server).last.queryParameters['after'], 'c1');
    expect(find.text('No sale'), findsOneWidget);
    // Someone iam-svc will not name keeps the end of their id.
    expect(find.textContaining('by u-3 · drawer check'), findsOneWidget);
    expect(_top(tester, _discount) < _top(tester, 'No sale'), isTrue);
    // Nothing older remains, so the button goes.
    expect(find.byKey(const Key('audit-load-older')), findsNothing);
    // Adjustments were read once; paging never re-reads them.
    expect(server.requests.where((r) => r.path.endsWith('/admin/inventory/movements')), hasLength(1));
  });

  testWidgets('choosing one kind of action asks the server for it and skips the other source',
      (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('audit-type')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Voids').last);
    await tester.pumpAndSettle();
    expect(_trailRequests(server).last.queryParameters['type'], 'VOID');
    expect(server.requests.where((r) => r.path.endsWith('/admin/inventory/movements')), hasLength(1));
    expect(find.text('Void'), findsOneWidget);
    expect(find.text(_adjustment), findsNothing);
    expect(find.text(_discount), findsNothing);
  });

  testWidgets('staff are named by their login, in the rows and in the Who menu', (tester) async {
    final server = await _pump(tester);
    final lookup = server.requests.lastWhere((r) => r.path.endsWith('/iam-svc/auth/admin/staff-users'));
    expect('${lookup.queryParameters['ids']}'.split(','), containsAll(['u-1', 'u-2']));
    expect(find.textContaining('by ana@shop.test'), findsNWidgets(2));
    await tester.tap(find.byKey(const Key('audit-actor')));
    await tester.pumpAndSettle();
    expect(find.text('ana@shop.test').hitTestable(), findsOneWidget);
    expect(find.text('ben@shop.test').hitTestable(), findsOneWidget);
    expect(find.textContaining('MANAGER'), findsNothing);
    expect(find.textContaining('CASHIER'), findsNothing);
  });

  testWidgets(
      'a name already read stays through a reload, and is not asked for again',
      (tester) async {
    final server = await _pump(tester, delay: const Duration(milliseconds: 50));
    bool lookup(RequestOptions r) => r.path.endsWith('/iam-svc/auth/admin/staff-users');
    expect(find.textContaining('by ben@shop.test'), findsWidgets);
    final asked = server.requests.where(lookup).length;

    Future<void> watchFrames() async {
      // Frame by frame while the trail loads: never the end of a known id.
      for (var i = 0; i < 30; i++) {
        await tester.pump(const Duration(milliseconds: 8));
        expect(find.textContaining('by u-2'), findsNothing);
        expect(find.textContaining('by u-1'), findsNothing);
      }
      await tester.pumpAndSettle();
    }

    // A filter reloads the trail.
    await tester.tap(find.byKey(const Key('audit-type')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Returns').last);
    await watchFrames();
    expect(find.textContaining('by ben@shop.test'), findsWidgets);
    expect(server.requests.where(lookup).length, asked,
        reason: 'everyone on the reloaded trail was already named');

    // Load older brings someone new (u-3): only they are asked about.
    final older = find.text('Load older');
    await tester.ensureVisible(older);
    await tester.tap(older);
    await watchFrames();
    expect(find.textContaining('by ben@shop.test'), findsWidgets);
    final last = server.requests.where(lookup).last;
    expect('${last.queryParameters['ids']}'.split(','), ['u-3']);
  });

  testWidgets('dates are the app\'s own format, not US-style', (tester) async {
    final server = await _pump(tester);
    final void_ = server.now.subtract(const Duration(hours: 1)).toLocal();
    final gb = DateFormat.yMMMd('en_GB').add_Hm().format(void_);
    expect(find.textContaining('$gb · by ana@shop.test'), findsOneWidget);
    expect(find.textContaining(DateFormat.yMMMd('en_US').add_Hm().format(void_)), findsNothing);
    final today = DateTime.now();
    final day = DateTime(today.year, today.month, today.day);
    expect(
      find.text('${DateFormat.yMMMd('en_GB').format(day.subtract(const Duration(days: 29)))} – '
          '${DateFormat.yMMMd('en_GB').format(day)}'),
      findsOneWidget,
    );
  });

  testWidgets('amounts are money and every qualifier is in words', (tester) async {
    await _pump(tester, everyKind: true);
    expect(find.text('Return · £4.20 refunded as store credit'), findsOneWidget);
    expect(find.text('Cancel · the order was confirmed'), findsOneWidget);
    expect(find.text('No sale · authorised by ana@shop.test'), findsOneWidget);
    // The stockroom's reason code reads as a word; a till reason is the cashier's own text.
    expect(find.textContaining('· Damaged'), findsOneWidget);
    expect(find.textContaining('change for the float'), findsOneWidget);
    for (final code in ['STORE_CREDIT', 'CONFIRMED', 'DAMAGED', '4.2 ']) {
      expect(find.textContaining(code), findsNothing, reason: code);
    }
  });

  testWidgets('on a phone the filters fold behind one button and the trail starts on the first screen',
      (tester) async {
    final server = await _pump(tester, size: const Size(390, 844));
    expect(find.byKey(const Key('audit-store')), findsNothing);
    expect(find.byKey(const Key('audit-actor')), findsNothing);
    expect(find.text('Void'), findsOneWidget);
    expect(tester.getBottomLeft(find.text('Void')).dy, lessThan(844));
    // The page sits 16 in from the edge, as everywhere on a phone.
    expect(tester.getTopLeft(find.text('Audit trail')).dx, AppSpacing.lg);

    await tester.tap(find.text('Filters'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('audit-store')), findsOneWidget);
    await tester.tap(find.byKey(const Key('audit-type')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Voids').last);
    await tester.pumpAndSettle();
    expect(_trailRequests(server).last.queryParameters['type'], 'VOID');
    expect(find.text('Filters · 1'), findsOneWidget);
    expect(tester.takeException(), isNull);
    // Clear filters is offered on a phone too, beside the button or under it.
    await tester.tap(find.text('Clear filters'));
    await tester.pumpAndSettle();
    expect(_trailRequests(server).last.queryParameters.containsKey('type'), isFalse);
    expect(find.text('Clear filters'), findsNothing);
    expect(find.text('Filters'), findsOneWidget);
  });

  testWidgets('on a phone with text at 200% the folded filters still fit', (tester) async {
    await _pump(tester, size: const Size(390, 844), textScale: 2);
    // The page's own words take most of a screen at this size; the filters follow them.
    final page = find.byType(Scrollable).first;
    await tester.scrollUntilVisible(find.text('Filters'), 200, scrollable: page);
    await tester.tap(find.text('Filters'));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.byKey(const Key('audit-type')), 200, scrollable: page);
    await tester.tap(find.byKey(const Key('audit-type')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Voids').last);
    await tester.pumpAndSettle();
    expect(find.text('Filters · 1'), findsOneWidget);
    expect(find.text('Clear filters'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('from tablet width the filters sit inline and can be cleared at once', (tester) async {
    final server = await _pump(tester);
    expect(find.text('Filters'), findsNothing);
    expect(find.byKey(const Key('audit-store')), findsOneWidget);
    expect(find.text('Clear filters'), findsNothing);
    await tester.tap(find.byKey(const Key('audit-type')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Voids').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Clear filters'));
    await tester.pumpAndSettle();
    expect(_trailRequests(server).last.queryParameters.containsKey('type'), isFalse);
    expect(find.text('Clear filters'), findsNothing);
  });

  testWidgets('an empty period says so, and there is nothing to export', (tester) async {
    await _pump(tester, empty: true);
    expect(find.textContaining('Nothing recorded in this period'), findsOneWidget);
    expect(tester.widget<OutlinedButton>(find.byKey(const Key('audit-export'))).onPressed, isNull);
  });

  testWidgets("the server's refusal is shown in words", (tester) async {
    await _pump(tester, forbidden: true);
    expect(find.text('management role required'), findsOneWidget);
    expect(find.text('Void'), findsNothing);
  });

  testWidgets('nothing on the screen writes: every request is a GET', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('audit-load-older')));
    await tester.pumpAndSettle();
    expect(server.requests.every((r) => r.method == 'GET'), isTrue);
  });
}
