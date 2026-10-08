import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/format.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/router.dart';
import 'package:storeql_app/core/theme.dart';
import 'package:storeql_app/features/admin/admin_shell.dart';
import 'package:storeql_app/features/admin/system_health_providers.dart';
import 'package:storeql_app/features/admin/system_health_screen.dart';
import 'package:storeql_app/shared/util/status_labels.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// System health (the intent page of the same name): one screen for the people
// who run the system. Live counters from the gateway (windows, a failure rate,
// a verdict in words, a per-minute strip, the busiest areas), the last day's
// failures (newest first, in plain words, each opening a sheet with the request
// id to quote), and the work waiting for a person (a tile per queue). It asks
// every five seconds, never twice at once, only while it is on screen, and keeps
// the last good figures when a refresh fails. A login without the permission, or
// held to stores, is told so and nothing is asked.
// ---------------------------------------------------------------------------

const _summaryPath = '/v1/system-health/summary';
const _failuresPath = '/v1/system-health/failures';
const _waitingPath = '/reporting-svc/admin/reports/system-health/waiting-work';

const _reqA = '0199a0b0-0000-7000-8000-0000000000a1';
const _reqB = '0199a0b0-0000-7000-8000-0000000000a2';
const _reqC = '0199a0b0-0000-7000-8000-0000000000a3';
const _reqD = '0199a0b0-0000-7000-8000-0000000000a4';

Map<String, dynamic> _window(int total, int failed, int client) => {
      'total': total,
      'succeeded': total - failed - client,
      'failed': failed,
      'clientErrors': client,
      'failureRate': total == 0 ? null : failed / total,
    };

Map<String, dynamic> _summary({
  bool available = true,
  int total5 = 1000,
  int failed5 = 4,
}) {
  final base = DateTime.utc(2026, 10, 7, 9, 0);
  return {
    'generatedAt': '2026-10-07T09:00:00Z',
    'available': available,
    'droppedSinceStart': 0,
    'windows': {
      'last5Minutes': _window(total5, failed5, 20),
      'lastHour': _window(12000, 60, 100),
      'last24Hours': _window(150000, 900, 2000),
    },
    'byGroup': [
      {'group': 'order-svc', 'total': 5000, 'failed': 10},
      {'group': 'payment-svc', 'total': 3000, 'failed': 40},
      {'group': 'inventory-svc', 'total': 2000, 'failed': 0},
    ],
    'perMinute': [
      for (var i = 0; i < 60; i++)
        {
          'at': base.subtract(Duration(minutes: 59 - i)).toIso8601String(),
          'total': 100 + i,
          'failed': i % 7 == 0 ? 3 : 0,
        },
    ],
    'perHour': [
      for (var i = 0; i < 24; i++)
        {
          'at': base.subtract(Duration(hours: 23 - i)).toIso8601String(),
          'total': 5000 + i * 10,
          'failed': i % 5 == 0 ? 20 : 2,
        },
    ],
  };
}

Map<String, dynamic> _failure(
  String id, {
  String at = '2026-10-07T09:12:30Z',
  String method = 'POST',
  String route = '/v1/order-svc/orders/{id}/cancel',
  String group = 'order-svc',
  int status = 503,
  String? code = 'UPSTREAM_UNAVAILABLE',
  String? userId = 'u-1',
  int ms = 5003,
}) =>
    {
      'at': at,
      'requestId': id,
      'method': method,
      'routePattern': route,
      'group': group,
      'status': status,
      'code': code,
      'userId': userId,
      'ms': ms,
    };

final _firstPage = {
  'items': [
    _failure(_reqA),
    _failure(_reqB,
        at: '2026-10-07T09:10:00Z',
        method: 'POST',
        route: '/v1/iam-svc/auth/login',
        group: 'iam-svc',
        status: 429,
        code: 'RATE_LIMITED',
        userId: null,
        ms: 2),
    _failure(_reqC,
        at: '2026-10-07T09:05:00Z',
        method: 'GET',
        route: '/v1/payment-svc/payments/{id}',
        group: 'payment-svc',
        status: 500,
        code: null,
        userId: 'u-0199a0b0-0000-7000-8000-000000000009',
        ms: 120),
  ],
  'nextCursor': 'c1',
};

final _secondPage = {
  'items': [
    _failure(_reqD,
        at: '2026-10-07T08:00:00Z',
        method: 'GET',
        route: '/v1/customer-svc/customers',
        group: 'customer-svc',
        status: 401,
        code: 'UNAUTHORIZED',
        userId: 'u-1',
        ms: 4),
  ],
  'nextCursor': null,
};

Map<String, dynamic> _waiting() => {
      'generatedAt': '2026-10-07T09:00:00Z',
      'items': [
        {
          'kind': 'PURCHASE_ORDER_APPROVAL',
          'label': 'Purchase orders waiting for approval',
          'count': 0,
          'opens': '/admin/procurement',
          'note': 'Approval limits are not set, so orders are not held for approval',
        },
        {
          'kind': 'PAYMENT_RUN',
          'label': 'Supplier payment runs to approve',
          'count': 2,
          'opens': '/admin/procurement?tab=payments',
          'note': null,
        },
        {
          'kind': 'SUPPLIER_INVOICE',
          'label': 'Supplier invoices flagged',
          'count': null,
          'opens': '/admin/procurement?tab=invoices',
          'note': null,
        },
        {
          'kind': 'ACCOUNTING_SYNC',
          'label': 'Accounting entries that may not have landed',
          'count': 1,
          'opens': '/admin/accounting',
          'note': null,
        },
        {
          'kind': 'CARD_REFUND',
          'label': 'Card refunds needing attention',
          'count': 4,
          'opens': '/admin/refunds',
          'note': null,
        },
        {
          'kind': 'PRIVACY_REQUEST',
          'label': 'Privacy requests open',
          'count': 7,
          'opens': '/admin/privacy',
          'note': null,
        },
      ],
      'unreachable': ['SUPPLIER_INVOICE'],
    };

String _problem(String code, String message, int status) => jsonEncode({
      'type': 'urn:storeql:problem:$code',
      'status': status,
      'detail': message,
      'code': code,
      'error': {'code': code, 'message': message},
    });

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  Map<String, dynamic> summary = _summary();
  Map<String, dynamic> firstPage = _firstPage;
  Map<String, dynamic> waiting = _waiting();

  /// Every request answers this status (and body) when set: a refresh that fails.
  int failAll = 0;
  String failCode = 'UPSTREAM_UNAVAILABLE';

  /// When set, each request waits here until the test completes it.
  Completer<void>? gate;

  List<RequestOptions> of(String path) => requests.where((r) => r.path.endsWith(path)).toList();
  int get summaries => of(_summaryPath).length;
  int get failuresAsked => of(_failuresPath).length;
  int get waitingAsked => of(_waitingPath).length;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final hold = gate;
    if (hold != null) await hold.future;
    if (failAll != 0) {
      return jsonResponse(_problem(failCode, 'refused', failAll), failAll);
    }
    if (o.path.endsWith(_summaryPath)) return jsonResponse(jsonEncode({'data': summary}));
    if (o.path.endsWith(_failuresPath)) {
      final page = o.queryParameters['after'] == 'c1' ? _secondPage : firstPage;
      return jsonResponse(jsonEncode({'data': page}));
    }
    if (o.path.endsWith(_waitingPath)) return jsonResponse(jsonEncode({'data': waiting}));
    if (o.path.contains('/auth/admin/staff-users')) {
      return jsonResponse('{"data":[{"userId":"u-1","email":"ana@shop.test"}]}');
    }
    return jsonResponse('{"data":[]}');
  }
}

class _Auth extends AuthNotifier {
  final List<String> roles;
  final List<String>? permissions;
  final List<String> storeIds;
  _Auth(this.roles, this.permissions, this.storeIds);

  @override
  Future<AuthState> build() async => AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: 'u',
        tenantId: 't',
        roles: roles,
        permissions: permissions,
        storeIds: storeIds,
      );
}

class _Page {
  final _Server server;
  final GoRouter router;
  _Page(this.server, this.router);
}

Future<_Page> _pump(
  WidgetTester tester, {
  Size size = const Size(1180, 4000),
  List<String> roles = const ['MANAGER'],
  List<String>? permissions,
  List<String> storeIds = const [],
  _Server? server,
  double textScale = 1,
  Widget Function(Widget screen)? around,
  bool settle = true,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final srv = server ?? _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = srv;
  final router = GoRouter(
    initialLocation: '/admin/system-health',
    routes: [
      GoRoute(
        path: '/admin/system-health',
        builder: (_, _) {
          const screen = Scaffold(body: SystemHealthScreen());
          return around == null ? screen : around(screen);
        },
      ),
      for (final path in ['/admin/procurement', '/admin/integrations', '/admin/privacy'])
        GoRoute(path: path, builder: (_, _) => Scaffold(body: Text('opened $path'))),
    ],
  );
  addTearDown(router.dispose);
  await tester.pumpWidget(ProviderScope(
    key: UniqueKey(),
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => _Auth(roles, permissions, storeIds)),
    ],
    child: MaterialApp.router(
      theme: AppTheme.light,
      routerConfig: router,
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(textScale)),
        child: child!,
      ),
    ),
  ));
  if (settle) await _flush(tester);
  return _Page(srv, router);
}

/// Lets what is under way finish: the router's first frames, the requests and
/// their answers. Not pumpAndSettle: the page keeps a timer, and a skeleton
/// shimmers until the first answer.
Future<void> _flush(WidgetTester tester) async {
  for (var i = 0; i < 12; i++) {
    await tester.pump(const Duration(milliseconds: 10));
  }
}

/// One poll: the clock moves on by [by] and the answers come in.
Future<void> _tick(WidgetTester tester, [Duration by = const Duration(seconds: 5)]) async {
  await tester.pump(by);
  await _flush(tester);
}

Finder _in(String key, Finder inner) => find.descendant(of: find.byKey(Key(key)), matching: inner);

void main() {
  setUpAll(initializeDateFormatting);

  group('the page at three widths', () {
    for (final (label, size) in [
      ('phone', const Size(390, 5000)),
      ('tablet', const Size(820, 4200)),
      ('desktop', const Size(1180, 3600)),
    ]) {
      testWidgets('$label: counters, failures and waiting work, with nothing overflowing', (tester) async {
        await _pump(tester, size: size);

        expect(find.text('System health'), findsOneWidget);
        // Live counters: the three windows, the failure rate as a percent, a verdict in words.
        expect(find.text('Last 5 minutes'), findsOneWidget);
        expect(find.text('Last hour'), findsOneWidget);
        expect(find.text('Last 24 hours'), findsOneWidget);
        expect(_in('health-window-last5Minutes', find.text('0.4%')), findsOneWidget);
        expect(_in('health-window-lastHour', find.text('0.5%')), findsOneWidget);
        expect(_in('health-window-last24Hours', find.text('0.6%')), findsOneWidget);
        expect(_in('health-window-last5Minutes', find.text('1,000')), findsOneWidget);
        expect(_in('health-verdict', find.text('Healthy')), findsOneWidget);
        expect(find.byKey(const Key('health-sparkline-minute')), findsOneWidget);
        expect(find.byKey(const Key('health-sparkline-hour')), findsOneWidget);
        // The busiest areas, in words, never a service name.
        expect(_in('health-group-order-svc', find.text('Orders')), findsOneWidget);
        expect(_in('health-group-payment-svc', find.text('Payments')), findsOneWidget);
        expect(_in('health-group-inventory-svc', find.text('Stock')), findsOneWidget);
        expect(_in('health-group-order-svc', find.textContaining('5,000 requests')), findsOneWidget);
        expect(_in('health-group-payment-svc', find.textContaining('40 failed')), findsOneWidget);
        expect(find.textContaining('order-svc'), findsNothing);

        // Failures: reason in plain words, the raw status and code small, the area.
        expect(find.byKey(const Key('failure-$_reqA')), findsOneWidget);
        expect(find.text('A service could not be reached'), findsOneWidget);
        expect(find.text('503 · UPSTREAM_UNAVAILABLE'), findsOneWidget);
        expect(find.text('Too many requests too quickly'), findsOneWidget);
        expect(find.text('The service hit an error'), findsOneWidget);
        expect(find.text('500'), findsOneWidget);

        // Waiting for a person: one tile per queue.
        for (final kind in [
          'PURCHASE_ORDER_APPROVAL',
          'PAYMENT_RUN',
          'SUPPLIER_INVOICE',
          'ACCOUNTING_SYNC',
          'CARD_REFUND',
          'PRIVACY_REQUEST',
        ]) {
          expect(find.byKey(Key('waiting-$kind')), findsOneWidget, reason: kind);
        }
        expect(find.text('Purchase orders waiting for approval'), findsOneWidget);
        expect(_in('waiting-PAYMENT_RUN', find.text('2')), findsOneWidget);
        expect(_in('waiting-PRIVACY_REQUEST', find.text('7')), findsOneWidget);

        expect(tester.takeException(), isNull);
      });
    }

    testWidgets('large text on a phone still lays out', (tester) async {
      await _pump(tester, size: const Size(390, 9000), textScale: 2);
      expect(find.text('Last 5 minutes'), findsOneWidget);
      expect(find.byKey(const Key('waiting-PRIVACY_REQUEST')), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('a phone puts the windows in one column, a tablet in a row', (tester) async {
      await _pump(tester, size: const Size(390, 5000));
      final a = tester.getTopLeft(find.byKey(const Key('health-window-last5Minutes')));
      final b = tester.getTopLeft(find.byKey(const Key('health-window-lastHour')));
      expect(b.dx, a.dx);
      expect(b.dy, greaterThan(a.dy));

      await _pump(tester, size: const Size(820, 4200));
      final c = tester.getTopLeft(find.byKey(const Key('health-window-last5Minutes')));
      final d = tester.getTopLeft(find.byKey(const Key('health-window-lastHour')));
      expect(d.dy, c.dy);
      expect(d.dx, greaterThan(c.dx));
    });

    testWidgets('a phone is not given a refresh button of its own row; a tablet and a desktop are', (tester) async {
      await _pump(tester, size: const Size(390, 5000));
      expect(find.byKey(const Key('health-refresh')), findsNothing);
      await _pump(tester, size: const Size(820, 4200));
      expect(find.byKey(const Key('health-refresh')), findsOneWidget);
    });

    testWidgets('the failures are a table with headings from desktop width, a card list below it', (tester) async {
      await _pump(tester, size: const Size(1180, 3600));
      expect(find.text('What happened'), findsOneWidget);
      expect(find.text('Who'), findsOneWidget);
      await _pump(tester, size: const Size(820, 4200));
      expect(find.text('What happened'), findsNothing);
    });

    testWidgets('the gutter is the page gutter: 16 on a phone, 24 wider', (tester) async {
      await _pump(tester, size: const Size(390, 5000));
      expect(tester.getTopLeft(find.text('System health')).dx, 16);
      expect(tester.getTopLeft(find.byKey(const Key('health-window-last5Minutes'))).dx, 16);
      await _pump(tester, size: const Size(820, 4200));
      expect(tester.getTopLeft(find.text('System health')).dx, 24);
      expect(tester.getTopLeft(find.byKey(const Key('health-window-last5Minutes'))).dx, 24);
    });
  });

  group('the counters', () {
    testWidgets('where the gateway sends the contract, the page asks the v1 routes with the first page of 20', (tester) async {
      final page = await _pump(tester);
      expect(page.server.summaries, 1);
      expect(page.server.failuresAsked, 1);
      expect(page.server.waitingAsked, 1);
      final q = page.server.of(_failuresPath).single.queryParameters;
      expect(q['limit'], 20);
      expect(q.containsKey('after'), isFalse);
      // The business is never named: it comes from the token.
      for (final r in page.server.requests) {
        expect(r.queryParameters.keys, isNot(contains('tenantId')));
        expect(r.method, 'GET');
      }
    });

    testWidgets('a window with nothing in it reads — for its rate, and the verdict says there is no traffic yet', (tester) async {
      final srv = _Server()..summary = _summary(total5: 0, failed5: 0);
      await _pump(tester, server: srv);
      expect(_in('health-verdict', find.text('No traffic yet')), findsOneWidget);
      expect(_in('health-window-last5Minutes', find.text('—')), findsOneWidget);
      expect(_in('health-window-last5Minutes', find.text('0%')), findsNothing);
    });

    testWidgets('a high failure rate reads Unhealthy, a middling one Degraded', (tester) async {
      final srv = _Server()..summary = _summary(total5: 100, failed5: 10);
      await _pump(tester, server: srv);
      expect(_in('health-verdict', find.text('Unhealthy')), findsOneWidget);

      final srv2 = _Server()..summary = _summary(total5: 100, failed5: 2);
      await _pump(tester, server: srv2);
      expect(_in('health-verdict', find.text('Degraded')), findsOneWidget);
    });

    testWidgets('the strips say what they show to a screen reader', (tester) async {
      final handle = tester.ensureSemantics();
      await _pump(tester);
      expect(
        find.bySemanticsLabel(RegExp('Requests each minute over the last hour')),
        findsOneWidget,
      );
      expect(
        find.bySemanticsLabel(RegExp('Requests each hour over the last 24 hours')),
        findsOneWidget,
      );
      expect(
        find.bySemanticsLabel(RegExp(r'Last 5 minutes: 1,000 requests, 4 failed, failure rate 0\.4%')),
        findsOneWidget,
      );
      handle.dispose();
    });

    testWidgets('when the figures are unavailable the page says so in place of the counters and still shows waiting work',
        (tester) async {
      final srv = _Server()..summary = {..._summary(available: false, total5: 0, failed5: 0), 'byGroup': [], 'perMinute': []};
      final page = await _pump(tester, server: srv);
      expect(find.text('Live figures are unavailable right now'), findsWidgets);
      expect(find.byKey(const Key('health-unavailable')), findsOneWidget);
      expect(find.byKey(const Key('health-window-last5Minutes')), findsNothing);
      expect(find.byKey(const Key('health-verdict')), findsNothing);
      // Not "no failures": it cannot say.
      expect(find.text('No failures in the last 24 hours'), findsNothing);
      expect(page.server.failuresAsked, 0);
      // Waiting work is another source.
      expect(find.byKey(const Key('waiting-PAYMENT_RUN')), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  });

  group('failures', () {
    testWidgets('are listed newest first with the area and the person by name', (tester) async {
      await _pump(tester);
      final a = tester.getTopLeft(find.byKey(const Key('failure-$_reqA'))).dy;
      final b = tester.getTopLeft(find.byKey(const Key('failure-$_reqB'))).dy;
      final c = tester.getTopLeft(find.byKey(const Key('failure-$_reqC'))).dy;
      expect(a, lessThan(b));
      expect(b, lessThan(c));
      // The time in the reader's own zone and locale, from the instant.
      expect(_in('failure-$_reqA', find.textContaining(AppFormat.dateTime('2026-10-07T09:12:30Z'))), findsOneWidget);
      // People by login; a short reference only while the name is unknown; nobody when no one was signed in.
      expect(_in('failure-$_reqA', find.textContaining('ana@shop.test')), findsOneWidget);
      expect(_in('failure-$_reqB', find.textContaining('No one signed in')), findsOneWidget);
      expect(_in('failure-$_reqC', find.textContaining('00000009')), findsOneWidget);
      expect(_in('failure-$_reqA', find.textContaining('Orders')), findsOneWidget);
    });

    testWidgets('a code the page has no words for still reads in words, with the code small', (tester) async {
      final srv = _Server()
        ..firstPage = {
          'items': [_failure(_reqA, status: 503, code: 'SOMETHING_NEW_HAPPENED')],
          'nextCursor': null,
        };
      await _pump(tester, server: srv);
      expect(find.text('A service was unavailable'), findsOneWidget);
      expect(find.text('503 · SOMETHING_NEW_HAPPENED'), findsOneWidget);
    });

    testWidgets('Load more asks for the next page by its cursor and adds it under the first', (tester) async {
      final page = await _pump(tester);
      expect(find.byKey(const Key('failure-$_reqD')), findsNothing);
      await tester.tap(find.byKey(const Key('failures-load-more')));
      await _flush(tester);
      final asked = page.server.of(_failuresPath);
      expect(asked.length, 2);
      expect(asked.last.queryParameters['after'], 'c1');
      expect(find.byKey(const Key('failure-$_reqD')), findsOneWidget);
      expect(find.byKey(const Key('failure-$_reqA')), findsOneWidget);
      // The last page has no cursor: no more to load.
      expect(find.byKey(const Key('failures-load-more')), findsNothing);
    });

    testWidgets('a refresh keeps the pages already loaded', (tester) async {
      final page = await _pump(tester);
      await tester.tap(find.byKey(const Key('failures-load-more')));
      await _flush(tester);
      expect(find.byKey(const Key('failure-$_reqD')), findsOneWidget);
      await _tick(tester);
      expect(page.server.failuresAsked, 3);
      expect(find.byKey(const Key('failure-$_reqD')), findsOneWidget);
      // Once each, however often the first page is read again.
      expect(find.byKey(const Key('failure-$_reqA')), findsOneWidget);
    });

    testWidgets('no failures says so in words', (tester) async {
      final srv = _Server()..firstPage = {'items': [], 'nextCursor': null};
      await _pump(tester, server: srv);
      expect(find.text('No failures in the last 24 hours'), findsOneWidget);
    });

    testWidgets('tapping one opens its detail with the request id, which can be copied', (tester) async {
      String? copied;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (call) async {
          if (call.method == 'Clipboard.setData') copied = (call.arguments as Map)['text'] as String?;
          return null;
        },
      );
      addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null));
      await _pump(tester);

      await tester.tap(find.byKey(const Key('failure-$_reqA')));
      await tester.pumpAndSettle();
      expect(find.text('Request id'), findsOneWidget);
      expect(find.text(_reqA), findsOneWidget);
      expect(find.text('POST'), findsOneWidget);
      expect(find.text('/v1/order-svc/orders/{id}/cancel'), findsOneWidget);
      expect(find.text('503'), findsOneWidget);
      expect(find.text('UPSTREAM_UNAVAILABLE'), findsWidgets);
      expect(find.text('5,003 ms'), findsOneWidget);
      expect(find.text('ana@shop.test'), findsWidgets);

      await tester.tap(find.byKey(const Key('failure-copy-request-id')));
      await tester.pump();
      expect(copied, _reqA);
      expect(find.text('Request id copied'), findsOneWidget);
      await tester.pump(const Duration(seconds: 5));
    });

    testWidgets('on a phone the detail is a sheet from the bottom', (tester) async {
      await _pump(tester, size: const Size(390, 5000));
      await tester.ensureVisible(find.byKey(const Key('failure-$_reqB')));
      await tester.tap(find.byKey(const Key('failure-$_reqB')));
      await tester.pumpAndSettle();
      expect(find.byType(BottomSheet), findsOneWidget);
      expect(find.text(_reqB), findsOneWidget);
      expect(find.text('No one signed in'), findsWidgets);
      expect(tester.takeException(), isNull);
    });
  });

  group('waiting work', () {
    testWidgets('a queue that could not be reached reads Couldn\'t check, never 0', (tester) async {
      await _pump(tester);
      expect(_in('waiting-SUPPLIER_INVOICE', find.text("Couldn't check")), findsOneWidget);
      expect(_in('waiting-SUPPLIER_INVOICE', find.text('0')), findsNothing);
      // A real zero is a zero, and its note says what a zero means here.
      expect(_in('waiting-PURCHASE_ORDER_APPROVAL', find.text('0')), findsOneWidget);
      expect(
        _in('waiting-PURCHASE_ORDER_APPROVAL',
            find.text('Approval limits are not set, so orders are not held for approval')),
        findsOneWidget,
      );
    });

    testWidgets('a source named unreachable is not a count even when the list says a number', (tester) async {
      final srv = _Server();
      (srv.waiting['items'] as List)[1]['count'] = 5;
      srv.waiting['unreachable'] = ['PAYMENT_RUN'];
      await _pump(tester, server: srv);
      expect(_in('waiting-PAYMENT_RUN', find.text("Couldn't check")), findsOneWidget);
      expect(_in('waiting-PAYMENT_RUN', find.text('5')), findsNothing);
    });

    // The server counts a queue only up to a cap and says so with `capped`: the
    // app shows the count it was given and a plus, and knows no cap of its own.
    group('a queue counted only up to a cap', () {
      // The queue's entry as a map the test may put any value in (the fixture's
      // literal infers narrower value types for some entries).
      Map<String, dynamic> item(_Server srv, String kind) {
        final items = srv.waiting['items'] as List;
        final at = items.indexWhere((i) => (i as Map)['kind'] == kind);
        final copy = Map<String, dynamic>.of(items[at] as Map<String, dynamic>);
        items[at] = copy;
        return copy;
      }

      testWidgets('reads with a plus sign and says "or more" to a screen reader', (tester) async {
        final handle = tester.ensureSemantics();
        final srv = _Server();
        item(srv, 'PAYMENT_RUN')
          ..['count'] = 1000
          ..['capped'] = true;
        await _pump(tester, server: srv);

        expect(_in('waiting-PAYMENT_RUN', find.text('1,000+')), findsOneWidget);
        expect(_in('waiting-PAYMENT_RUN', find.text('1,000')), findsNothing);
        expect(
          find.bySemanticsLabel(RegExp('Supplier payment runs to approve: 1,000 or more waiting')),
          findsOneWidget,
        );
        expect(find.bySemanticsLabel(RegExp(r'Supplier payment runs to approve: 1,000 waiting')), findsNothing);
        expect(tester.takeException(), isNull);
        handle.dispose();
      });

      testWidgets('the app knows no cap of its own: it shows the count it was given and a plus', (tester) async {
        final srv = _Server();
        item(srv, 'PRIVACY_REQUEST')
          ..['count'] = 250
          ..['capped'] = true;
        await _pump(tester, server: srv);
        expect(_in('waiting-PRIVACY_REQUEST', find.text('250+')), findsOneWidget);
        expect(_in('waiting-PRIVACY_REQUEST', find.text('250')), findsNothing);
      });

      testWidgets('the number is grouped for the locale, the plus after it', (tester) async {
        final srv = _Server();
        item(srv, 'PAYMENT_RUN')
          ..['count'] = 12345
          ..['capped'] = true;
        await _pump(tester, server: srv);
        expect(_in('waiting-PAYMENT_RUN', find.text('${AppFormat.count(12345)}+')), findsOneWidget);
      });

      testWidgets('an uncapped count is unchanged, even one that equals the cap', (tester) async {
        final handle = tester.ensureSemantics();
        final srv = _Server();
        item(srv, 'PAYMENT_RUN')
          ..['count'] = 1000
          ..['capped'] = false;
        await _pump(tester, server: srv);

        expect(_in('waiting-PAYMENT_RUN', find.text('1,000')), findsOneWidget);
        expect(_in('waiting-PAYMENT_RUN', find.text('1,000+')), findsNothing);
        expect(
          find.bySemanticsLabel(RegExp('Supplier payment runs to approve: 1,000 waiting')),
          findsOneWidget,
        );
        // The others on the page read as they did.
        expect(_in('waiting-PRIVACY_REQUEST', find.text('7')), findsOneWidget);
        expect(find.textContaining('+'), findsNothing);
        handle.dispose();
      });

      testWidgets('a missing `capped` member is treated as false', (tester) async {
        final handle = tester.ensureSemantics();
        final srv = _Server();
        final payment = item(srv, 'PAYMENT_RUN')..['count'] = 1000;
        expect(payment.containsKey('capped'), isFalse);
        await _pump(tester, server: srv);

        expect(_in('waiting-PAYMENT_RUN', find.text('1,000')), findsOneWidget);
        expect(find.textContaining('+'), findsNothing);
        expect(
          find.bySemanticsLabel(RegExp('Supplier payment runs to approve: 1,000 waiting')),
          findsOneWidget,
        );
        handle.dispose();
      });

      testWidgets('a count that is not known still reads Couldn\'t check, whatever `capped` says', (tester) async {
        final srv = _Server();
        item(srv, 'SUPPLIER_INVOICE').addAll({'count': null, 'capped': true});
        await _pump(tester, server: srv);
        expect(_in('waiting-SUPPLIER_INVOICE', find.text("Couldn't check")), findsOneWidget);
        expect(find.descendant(of: find.byKey(const Key('waiting-SUPPLIER_INVOICE')), matching: find.textContaining('+')),
            findsNothing);

        // Named unreachable with a number and a plus: still unknown, never a count.
        final srv2 = _Server();
        item(srv2, 'PAYMENT_RUN').addAll({'count': 1000, 'capped': true});
        srv2.waiting['unreachable'] = ['PAYMENT_RUN'];
        await _pump(tester, server: srv2);
        expect(_in('waiting-PAYMENT_RUN', find.text("Couldn't check")), findsOneWidget);
        expect(find.descendant(of: find.byKey(const Key('waiting-PAYMENT_RUN')), matching: find.textContaining('+')),
            findsNothing);
      });

      testWidgets('the tile is still the same link, and the note still shows', (tester) async {
        final srv = _Server();
        item(srv, 'PURCHASE_ORDER_APPROVAL')
          ..['count'] = 1000
          ..['capped'] = true;
        final page = await _pump(tester, server: srv);
        expect(_in('waiting-PURCHASE_ORDER_APPROVAL', find.text('1,000+')), findsOneWidget);
        expect(
          _in('waiting-PURCHASE_ORDER_APPROVAL',
              find.text('Approval limits are not set, so orders are not held for approval')),
          findsOneWidget,
        );
        await tester.ensureVisible(find.byKey(const Key('waiting-PURCHASE_ORDER_APPROVAL')));
        await tester.tap(find.byKey(const Key('waiting-PURCHASE_ORDER_APPROVAL')));
        await tester.pumpAndSettle();
        expect(page.router.state.uri.toString(), '/admin/procurement?tab=purchase-orders');
      });

      test('the model reads `capped` as a bool, absent or not a true as false', () {
        WaitingItem read(Map<String, dynamic> extra) => WaitingWork.fromJson({
              'items': [
                {'kind': 'PAYMENT_RUN', 'label': 'Runs', 'count': 5, ...extra},
              ],
            }).items.single;

        expect(read({}).capped, isFalse);
        expect(read({'capped': false}).capped, isFalse);
        expect(read({'capped': null}).capped, isFalse);
        expect(read({'capped': true}).capped, isTrue);
        expect(read({'capped': true}).count, 5);
        // No count, nothing to be capped.
        expect(read({'count': null, 'capped': true}).capped, isFalse);
        // An unreachable queue is unknown whatever else came with it.
        final unreachable = WaitingWork.fromJson({
          'items': [
            {'kind': 'PAYMENT_RUN', 'label': 'Runs', 'count': 1000, 'capped': true},
          ],
          'unreachable': ['PAYMENT_RUN'],
        }).items.single;
        expect(unreachable.count, isNull);
        expect(unreachable.capped, isFalse);
      });
    });

    testWidgets('a tile opens the screen that settles it, at the app\'s own address', (tester) async {
      final page = await _pump(tester);
      await tester.ensureVisible(find.byKey(const Key('waiting-PAYMENT_RUN')));
      await tester.tap(find.byKey(const Key('waiting-PAYMENT_RUN')));
      await tester.pumpAndSettle();
      expect(page.router.state.uri.toString(), '/admin/procurement?tab=payments');
      expect(find.text('opened /admin/procurement'), findsOneWidget);
    });

    testWidgets('each queue maps to the screen that has it; a queue with no screen is not a link', (tester) async {
      expect(waitingWorkRoute('PURCHASE_ORDER_APPROVAL', null), '/admin/procurement?tab=purchase-orders');
      expect(waitingWorkRoute('PAYMENT_RUN', '/admin/procurement?tab=payments'), '/admin/procurement?tab=payments');
      expect(waitingWorkRoute('SUPPLIER_INVOICE', null), '/admin/procurement?tab=invoices');
      expect(waitingWorkRoute('ACCOUNTING_SYNC', '/admin/accounting'), '/admin/integrations');
      expect(waitingWorkRoute('PRIVACY_REQUEST', '/admin/privacy'), '/admin/privacy');
      // Card refund dues have no screen yet, whatever the server's `opens` says.
      expect(waitingWorkRoute('CARD_REFUND', '/admin/refunds'), isNull);
      // A kind this app has not met opens only where the app has a page.
      expect(waitingWorkRoute('NEW_QUEUE', '/admin/privacy'), '/admin/privacy');
      expect(waitingWorkRoute('NEW_QUEUE', '/admin/nowhere'), isNull);
      expect(waitingWorkRoute('NEW_QUEUE', null), isNull);

      final page = await _pump(tester);
      await tester.ensureVisible(find.byKey(const Key('waiting-CARD_REFUND')));
      await tester.tap(find.byKey(const Key('waiting-CARD_REFUND')));
      await tester.pumpAndSettle();
      expect(page.router.state.uri.toString(), '/admin/system-health');
    });
  });

  group('polling', () {
    test('asks every five seconds', () {
      final container = ProviderContainer();
      addTearDown(container.dispose);
      expect(container.read(systemHealthPollIntervalProvider), const Duration(seconds: 5));
    });

    testWidgets('refreshes on its own, all three sources each time', (tester) async {
      final page = await _pump(tester);
      expect(page.server.summaries, 1);

      page.server.summary = _summary(total5: 2000, failed5: 100);
      await _tick(tester);
      expect(page.server.summaries, 2);
      expect(page.server.failuresAsked, 2);
      expect(page.server.waitingAsked, 2);
      // The new figures are on the page: 100 of 2,000 failed.
      expect(_in('health-window-last5Minutes', find.text('5%')), findsOneWidget);
      expect(_in('health-verdict', find.text('Unhealthy')), findsOneWidget);

      await _tick(tester);
      expect(page.server.summaries, 3);
    });

    testWidgets('never asks again while an answer is still outstanding', (tester) async {
      final page = await _pump(tester);
      expect(page.server.summaries, 1);

      final gate = Completer<void>();
      page.server.gate = gate;
      await _tick(tester); // the next poll starts and waits at the server
      expect(page.server.summaries, 2);
      await _tick(tester);
      await _tick(tester);
      await _tick(tester);
      expect(page.server.summaries, 2, reason: 'three ticks went by with one request outstanding');

      page.server.gate = null;
      gate.complete();
      await _flush(tester);
      await _tick(tester);
      expect(page.server.summaries, 3);
    });

    testWidgets('keeps the last good figures when a refresh fails, and says so quietly', (tester) async {
      final page = await _pump(tester);
      expect(find.byKey(const Key('health-refresh-failed')), findsNothing);
      expect(find.byKey(const Key('health-updated')), findsOneWidget);

      page.server.failAll = 503;
      await _tick(tester);
      expect(_in('health-window-last5Minutes', find.text('0.4%')), findsOneWidget);
      expect(find.byKey(const Key('failure-$_reqA')), findsOneWidget);
      expect(find.byKey(const Key('waiting-PAYMENT_RUN')), findsOneWidget);
      expect(find.byKey(const Key('health-refresh-failed')), findsOneWidget);
      expect(find.textContaining("Couldn't refresh"), findsOneWidget);
      // Not the full-page error: the figures are still there.
      expect(find.text('Retry'), findsNothing);

      page.server.failAll = 0;
      await _tick(tester);
      expect(find.byKey(const Key('health-refresh-failed')), findsNothing);
    });

    testWidgets('a first load that fails says so with a Retry, and Retry asks again', (tester) async {
      final srv = _Server()..failAll = 503;
      await _pump(tester, server: srv);
      expect(find.byKey(const Key('health-load-failed')), findsOneWidget);
      expect(find.text('Retry'), findsOneWidget);
      expect(find.byKey(const Key('health-window-last5Minutes')), findsNothing);

      srv.failAll = 0;
      await tester.tap(find.text('Retry'));
      await _flush(tester);
      expect(find.byKey(const Key('health-window-last5Minutes')), findsOneWidget);
    });

    testWidgets('the refresh button asks now, without waiting for the clock', (tester) async {
      final page = await _pump(tester);
      await tester.tap(find.byKey(const Key('health-refresh')));
      await _flush(tester);
      expect(page.server.summaries, 2);
    });

    testWidgets('pauses while the screen is not showing, and asks again at once when it is', (tester) async {
      final visible = ValueNotifier(true);
      addTearDown(visible.dispose);
      final page = await _pump(
        tester,
        around: (screen) => ValueListenableBuilder<bool>(
          valueListenable: visible,
          builder: (_, on, _) => TickerMode(enabled: on, child: screen),
        ),
      );
      expect(page.server.summaries, 1);

      visible.value = false;
      await tester.pump();
      await _tick(tester, const Duration(seconds: 30));
      expect(page.server.summaries, 1, reason: 'covered by another page');

      visible.value = true;
      await _flush(tester);
      expect(page.server.summaries, 2, reason: 'it catches up on showing');
      await _tick(tester);
      expect(page.server.summaries, 3);
    });

    testWidgets('pauses while the app is in the background', (tester) async {
      final page = await _pump(tester);
      expect(page.server.summaries, 1);

      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      await tester.pump();
      await _tick(tester, const Duration(seconds: 30));
      expect(page.server.summaries, 1);

      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await _flush(tester);
      expect(page.server.summaries, 2);
    });

    testWidgets('stops when the screen goes: no timer is left running', (tester) async {
      final page = await _pump(tester);
      expect(page.server.summaries, 1);
      page.router.go('/admin/privacy');
      await tester.pumpAndSettle();
      await _tick(tester, const Duration(seconds: 60));
      expect(page.server.summaries, 1);
    });
  });

  group('who may see it', () {
    testWidgets('a 403 SYSTEM_HEALTH_NOT_PERMITTED is a state of its own, and nothing else is asked', (tester) async {
      final srv = _Server()
        ..failAll = 403
        ..failCode = 'SYSTEM_HEALTH_NOT_PERMITTED';
      await _pump(tester, server: srv);
      expect(find.byKey(const Key('health-not-permitted')), findsOneWidget);
      expect(find.textContaining('system.health permission'), findsOneWidget);
      expect(srv.requests.length, 1, reason: 'only the first read, which was refused');
      await _tick(tester, const Duration(seconds: 60));
      expect(srv.requests.length, 1, reason: 'and it does not keep asking');
      expect(find.byKey(const Key('health-window-last5Minutes')), findsNothing);
    });

    testWidgets('a 403 BUSINESS_WIDE_ONLY says it is for people not held to particular stores', (tester) async {
      final srv = _Server()
        ..failAll = 403
        ..failCode = 'BUSINESS_WIDE_ONLY';
      await _pump(tester, server: srv);
      expect(find.byKey(const Key('health-business-wide-only')), findsOneWidget);
      expect(find.textContaining('not held to particular stores'), findsOneWidget);
      expect(srv.requests.length, 1);
    });

    testWidgets('a manager whose permissions lack it is told so without a request', (tester) async {
      final srv = _Server();
      await _pump(tester, server: srv, permissions: const ['staff.manage']);
      expect(find.byKey(const Key('health-not-permitted')), findsOneWidget);
      expect(srv.requests, isEmpty);
    });

    testWidgets('a manager holding just this permission may see it', (tester) async {
      final srv = _Server();
      await _pump(tester, server: srv, permissions: const ['system.health']);
      expect(find.byKey(const Key('health-window-last5Minutes')), findsOneWidget);
    });

    testWidgets('a manager held to stores is told it is business-wide, without a request', (tester) async {
      final srv = _Server();
      await _pump(tester, server: srv, storeIds: const ['s1']);
      expect(find.byKey(const Key('health-business-wide-only')), findsOneWidget);
      expect(srv.requests, isEmpty);
    });

    testWidgets('the owner sees it', (tester) async {
      await _pump(tester, roles: const ['OWNER']);
      expect(find.byKey(const Key('health-window-last5Minutes')), findsOneWidget);
    });
  });

  group('the menu', () {
    Future<void> shell(WidgetTester tester, List<String> roles,
        {List<String>? permissions, List<String> storeIds = const []}) async {
      tester.view.physicalSize = const Size(1400, 2600);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = _Server();
      await tester.pumpWidget(ProviderScope(
        key: UniqueKey(),
        overrides: [
          apiClientProvider.overrideWithValue(FakeApiClient(dio)),
          authNotifierProvider.overrideWith(() => _Auth(roles, permissions, storeIds)),
        ],
        child: MaterialApp(
          theme: AppTheme.light,
          home: const AdminShell(currentLocation: '/admin/retention', child: SizedBox.shrink()),
        ),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('System health is listed to an owner and a manager', (tester) async {
      await shell(tester, ['OWNER']);
      expect(find.text('System health'), findsOneWidget);
      await shell(tester, ['MANAGER']);
      expect(find.text('System health'), findsOneWidget);
      await shell(tester, ['MANAGER'], permissions: ['system.health']);
      expect(find.text('System health'), findsOneWidget);
    });

    testWidgets('it is not listed to a cashier, a storekeeper, or a manager whose permissions lack it', (tester) async {
      await shell(tester, ['CASHIER']);
      expect(find.text('System health'), findsNothing);
      await shell(tester, ['STOREKEEPER']);
      expect(find.text('System health'), findsNothing);
      await shell(tester, ['MANAGER'], permissions: ['staff.manage', 'finance.payments']);
      expect(find.text('System health'), findsNothing);
      await shell(tester, ['MANAGER'], permissions: const []);
      expect(find.text('System health'), findsNothing);
    });

    testWidgets('it is not listed to a manager held to stores, though the others stay', (tester) async {
      await shell(tester, ['MANAGER'], storeIds: ['s1']);
      expect(find.text('System health'), findsNothing);
      expect(find.text('Security notices'), findsOneWidget);
    });

    test('a storekeeper\'s menu never offers it, and the app has the address', () {
      expect(storekeeperAdminRoutes, isNot(contains('/admin/system-health')));
      expect(storekeeperAdminAllowed('/admin/system-health'), isFalse);
      expect(adminRoutes, contains('/admin/system-health'));
    });
  });

  group('the words', () {
    test('the verdict is judged on the five-minute window: under 1% healthy, under 5% degraded, else unhealthy', () {
      HealthWindow w(int total, int failed) => HealthWindow(
            total: total,
            succeeded: total - failed,
            failed: failed,
            clientErrors: 0,
            failureRate: total == 0 ? null : failed / total,
          );
      expect(healthyBelow, 0.01);
      expect(degradedBelow, 0.05);
      expect(healthVerdict(w(0, 0)), HealthVerdict.noTraffic);
      expect(healthVerdict(w(1000, 0)), HealthVerdict.healthy);
      expect(healthVerdict(w(1000, 9)), HealthVerdict.healthy);
      expect(healthVerdict(w(1000, 10)), HealthVerdict.degraded);
      expect(healthVerdict(w(1000, 49)), HealthVerdict.degraded);
      expect(healthVerdict(w(1000, 50)), HealthVerdict.unhealthy);
      expect(healthVerdict(w(1000, 1000)), HealthVerdict.unhealthy);
      expect(HealthVerdict.noTraffic.label, 'No traffic yet');
      expect(HealthVerdict.healthy.label, 'Healthy');
      expect(HealthVerdict.degraded.label, 'Degraded');
      expect(HealthVerdict.unhealthy.label, 'Unhealthy');
      expect(HealthVerdict.healthy.tone, StatusTone.success);
      expect(HealthVerdict.degraded.tone, StatusTone.warning);
      expect(HealthVerdict.unhealthy.tone, StatusTone.error);
      expect(HealthVerdict.noTraffic.tone, StatusTone.neutral);
    });

    test('a window with no rate sent is judged from its counts', () {
      const w = HealthWindow(total: 100, succeeded: 98, failed: 2, clientErrors: 0, failureRate: null);
      expect(healthVerdict(w), HealthVerdict.degraded);
    });

    test('a failure reads as a reason in words, from its code first and its status after', () {
      expect(failureReason(503, 'UPSTREAM_UNAVAILABLE'), 'A service could not be reached');
      expect(failureReason(504, 'UPSTREAM_TIMEOUT'), 'A service took too long to answer');
      expect(failureReason(429, 'RATE_LIMITED'), 'Too many requests too quickly');
      expect(failureReason(401, 'UNAUTHORIZED'), 'The sign-in was missing or had expired');
      expect(failureReason(500, 'INTERNAL_ERROR'), 'The service hit an unexpected error');
      // Nothing from the code: the status class speaks.
      expect(failureReason(500, null), 'The service hit an error');
      expect(failureReason(502, 'NEVER_HEARD_OF_IT'), 'A service could not be reached');
      expect(failureReason(503, 'NEVER_HEARD_OF_IT'), 'A service was unavailable');
      expect(failureReason(504, null), 'A service took too long to answer');
      expect(failureReason(401, null), 'The sign-in was refused');
      expect(failureReason(403, null), 'The request was not allowed');
      expect(failureReason(413, null), 'The request was too large');
      expect(failureReason(429, null), 'Too many requests');
      expect(failureReason(null, null), 'The request failed');
      expect(failureReason(418, null), 'The request failed');
      // Never a code as the words.
      for (final code in ['UPSTREAM_UNAVAILABLE', 'RATE_LIMITED', 'LOGIN_LOCKED', 'PLAN_RATE_LIMIT_REACHED']) {
        expect(failureReason(500, code).contains('_'), isFalse, reason: code);
      }
    });

    test('a failure is either Failed (the system could not answer) or Refused (it turned the request away)', () {
      for (final status in [500, 502, 503, 504]) {
        expect(failureKindLabel(status), 'Failed', reason: '$status');
        expect(failureKindTone(status), StatusTone.error);
      }
      for (final status in [401, 403, 413, 429]) {
        expect(failureKindLabel(status), 'Refused', reason: '$status');
        expect(failureKindTone(status), StatusTone.warning);
      }
    });

    test('an area reads as what it does, with or without the service suffix, and a new one in words', () {
      expect(routeGroupLabel('order-svc'), 'Orders');
      expect(routeGroupLabel('order'), 'Orders');
      expect(routeGroupLabel('payment-svc'), 'Payments');
      expect(routeGroupLabel('inventory-svc'), 'Stock');
      expect(routeGroupLabel('iam-svc'), 'Sign-in and accounts');
      expect(routeGroupLabel('reporting-svc'), 'Reports');
      expect(routeGroupLabel('brand-new-svc'), 'Brand new');
      expect(routeGroupLabel(null), 'Other');
      expect(routeGroupLabel(''), 'Other');
    });
  });
}
