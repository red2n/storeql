import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/tenant_data_providers.dart';
import 'package:storeql_app/features/admin/tenant_data_screen.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// Data export and leaving (21.14). The owner sees what every service holds and
// what each leaves out, and why; the export reads every page into one bundle;
// an import is checked whole before anything is sent, leaves out what is not
// imported, pages its rows and stops at the first refusal, naming the table;
// notice is given, withdrawn and followed through erasure. Anyone but the
// owner is told so and the API is never called.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Auth extends AuthNotifier {
  final List<String> roles;
  _Auth(this.roles);
  @override
  Future<AuthState> build() async => AuthAuthenticated(
    accessToken: 'a',
    refreshToken: 'r',
    userId: 'u',
    tenantId: 't',
    roles: roles,
    permissions: null,
  );
}

Map<String, dynamic> _status(String stage, {String intent = 'SWITCH'}) => {
  'notice': {
    'id': 'n-1',
    'intent': intent,
    'noticeEndsOn': '2026-10-15',
    'transitionEndsOn': '2026-11-14',
    'retrievalEndsOn': '2026-12-14',
    'erasureDueOn': '2026-12-15',
  },
  'stage': stage,
  'evidence': [
    if (stage == 'ERASING') {'service': 'tenant-svc', 'rowsErased': 12},
  ],
  'awaiting': [if (stage == 'ERASING') 'order-svc'],
};

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  Map<String, dynamic>? switching;
  int failStatus = 0;
  String failMessage = '';
  String? refuseTable;

  /// How many rows product-svc says its products table holds.
  int productRows = 3;

  bool called(String method, String pathEnd) =>
      requests.any((o) => o.method == method && o.path.endsWith(pathEnd));

  List<RequestOptions> posts(String pathEnd) => requests
      .where((o) => o.method == 'POST' && o.path.endsWith(pathEnd))
      .toList();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async {
    requests.add(o);
    ResponseBody json(Object body, [int status = 200]) =>
        ResponseBody.fromString(
          jsonEncode(body),
          status,
          headers: {
            Headers.contentTypeHeader: [Headers.jsonContentType],
          },
        );
    final path = o.path;
    if (path.contains('/admin/tenant/switching')) {
      if (o.method == 'GET') {
        return switching == null
            ? json({
                'error': {
                  'code': 'SWITCHING_NO_NOTICE',
                  'message': 'no notice',
                },
              }, 404)
            : json({'data': switching});
      }
      if (failStatus != 0) {
        return json({
          'error': {'code': 'REFUSED', 'message': failMessage},
        }, failStatus);
      }
      return json({'data': switching ?? _status('NOTICE')}, 201);
    }
    final table = RegExp(
      r'^/([a-z-]+)/admin/tenant-data/tables/([a-z_]+)$',
    ).firstMatch(path);
    if (table != null && o.method == 'GET') {
      if (table.group(2) == 'products') {
        final first = o.queryParameters['after'] == null;
        return json({
          'data': {
            'table': 'products',
            'rows': first
                ? [
                    {'id': 'p1'},
                    {'id': 'p2'},
                  ]
                : [
                    {'id': 'p3'},
                  ],
            if (first) 'nextCursor': 'c1',
          },
        });
      }
      return json({
        'data': {
          'table': table.group(2),
          'rows': [
            {'id': '${table.group(1)}-row'},
          ],
        },
      });
    }
    if (table != null && o.method == 'POST') {
      if (table.group(2) == refuseTable) {
        return json({
          'error': {
            'code': 'TENANT_DATA_IMPORT_CONFLICT',
            'message': 'a row in the page is already here for this tenant',
          },
        }, 409);
      }
      return json({
        'data': {
          'table': table.group(2),
          'rows': ((o.data as Map)['rows'] as List).length,
        },
      });
    }
    final manifest = RegExp(r'^/([a-z-]+)/admin/tenant-data$').firstMatch(path);
    if (manifest != null) {
      final svc = manifest.group(1)!;
      return json({
        'data': {
          'format': 'storeql-tenant-data/1',
          'tables': svc == 'product-svc'
              ? [
                  {'name': 'products', 'rows': productRows, 'checksum': 'x'},
                ]
              : svc == 'tenant-svc'
              ? [
                  {
                    'name': 'tenants',
                    'rows': 1,
                    'importSkippedReason': 'its own record',
                  },
                  {'name': 'stores', 'rows': 1},
                ]
              : [
                  {'name': 'things', 'rows': 1},
                ],
          'excludedTables': {
            'outbox': 'delivery machinery',
            if (svc == 'iam-svc')
              'refresh_tokens': 'login session tokens: a credential',
          },
          'excludedColumns': {
            if (svc == 'iam-svc')
              'users.password_hash':
                  "a staff member's password hash: a credential",
          },
          'keptAtErasure': {
            if (svc == 'tenant-svc') 'tenants': 'the record that it left',
          },
        },
      });
    }
    return json({'data': <Object>[]});
  }
}

Dio _dio(_Server server) =>
    Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;

Future<_Server> _pump(
  WidgetTester tester, {
  List<String> roles = const ['OWNER'],
  Map<String, dynamic>? switching,
  int productRows = 3,
  String? refuseTable,
  String? bundle,
}) async {
  tester.view.physicalSize = const Size(1200, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server()
    ..switching = switching
    ..productRows = productRows
    ..refuseTable = refuseTable;
  await tester.pumpWidget(
    ProviderScope(
      key: UniqueKey(),
      overrides: [
        apiClientProvider.overrideWithValue(_FakeApiClient(_dio(server))),
        authNotifierProvider.overrideWith(() => _Auth(roles)),
        if (bundle != null)
          tenantBundlePickerProvider.overrideWithValue(() async => bundle),
      ],
      child: const MaterialApp(home: Scaffold(body: TenantDataScreen())),
    ),
  );
  await tester.pumpAndSettle();
  return server;
}

String _bundle(List<Map<String, Object?>> lines) => [
  jsonEncode({'format': tenantBundleFormat}),
  for (final l in lines) jsonEncode(l),
].join('\n');

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  setUpAll(initializeDateFormatting);

  testWidgets('anyone but the owner is told so and the API is never called', (
    tester,
  ) async {
    for (final role in ['MANAGER', 'CASHIER']) {
      final server = await _pump(tester, roles: [role]);
      expect(find.textContaining('Only the owner'), findsOneWidget);
      expect(find.text('Download everything'), findsNothing);
      expect(server.requests, isEmpty);
    }
  });

  testWidgets(
    'the owner sees what every service holds, and what is left out and why',
    (tester) async {
      final server = await _pump(tester);
      expect(find.textContaining('15 rows in 12 services'), findsOneWidget);
      // Each service by what it holds, in words an owner knows — never the
      // platform's name for it.
      expect(find.text('Products'), findsOneWidget);
      expect(find.text('Staff sign-ins'), findsOneWidget);
      expect(find.text('Reports'), findsOneWidget);
      expect(find.text('Shopping carts'), findsOneWidget);
      expect(find.textContaining('-svc'), findsNothing);
      expect(find.text('3 rows · 1 table'), findsOneWidget);
      expect(find.text('2 rows · 2 tables'), findsOneWidget);
      expect(server.called('GET', '/cart-svc/admin/tenant-data'), isTrue);
      await tester.tap(find.text('What is left out, and why'));
      await tester.pumpAndSettle();
      expect(find.text('Staff sign-ins: users.password_hash'), findsOneWidget);
      expect(find.textContaining('a credential'), findsWidgets);
      expect(find.text('Staff sign-ins: refresh_tokens'), findsOneWidget);
      expect(
        find.text('Business and stores: tenants, kept at erasure'),
        findsOneWidget,
      );
      // The machinery every service leaves out is named once, not twelve times.
      expect(find.text('Staff sign-ins: outbox'), findsNothing);
      expect(find.textContaining('Every service: outbox'), findsOneWidget);
      expect(find.text('No notice given.'), findsOneWidget);
    },
  );

  testWidgets('row counts are grouped, as every other number is', (
    tester,
  ) async {
    await _pump(tester, productRows: 138469);
    expect(find.text('138,469 rows · 1 table'), findsOneWidget);
    expect(find.textContaining('138,481 rows in 12 services'), findsOneWidget);
    expect(find.textContaining('138469'), findsNothing);
  });

  test('the export reads every page of every table into one bundle', () async {
    final server = _Server();
    final bundle = await buildTenantDataBundle(_dio(server), const [
      TenantDataManifest(
        service: 'product-svc',
        format: 'storeql-tenant-data/1',
        tables: [TenantDataTable(name: 'products', rows: 3)],
      ),
      TenantDataManifest(
        service: 'cart-svc',
        format: 'storeql-tenant-data/1',
        tables: [TenantDataTable(name: 'carts', rows: 1)],
      ),
    ]);
    final lines = bundle.split('\n');
    expect(lines.length, 5);
    expect(jsonDecode(lines[0])['format'], tenantBundleFormat);
    expect(jsonDecode(lines[1]), {
      'service': 'product-svc',
      'table': 'products',
      'row': {'id': 'p1'},
    });
    expect(jsonDecode(lines[3])['row'], {'id': 'p3'});
    expect(jsonDecode(lines[4])['table'], 'carts');
    final pages = server.requests
        .where((o) => o.path.endsWith('/tables/products'))
        .toList();
    expect(pages.length, 2);
    expect(pages[1].queryParameters['after'], 'c1');
  });

  testWidgets(
    'an import that stops names the table in words, never the service id',
    (tester) async {
      await _pump(
        tester,
        refuseTable: 'stores',
        bundle: _bundle([
          {
            'service': 'tenant-svc',
            'table': 'stores',
            'row': {'id': 's'},
          },
        ]),
      );
      final start = find.text('Import a bundle');
      await tester.ensureVisible(start);
      await tester.tap(start);
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Import'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('then Business and stores: stores was refused'),
        findsOneWidget,
      );
      expect(find.textContaining('-svc'), findsNothing);
    },
  );

  test(
    'an import pages its rows, leaves out what is not imported, and stops at a refusal',
    () async {
      final server = _Server();
      final rows = [
        {
          'service': 'tenant-svc',
          'table': 'tenants',
          'row': {'id': 't'},
        },
        for (var i = 0; i < 5; i++)
          {
            'service': 'product-svc',
            'table': 'products',
            'row': {'id': 'p$i'},
          },
      ];
      final done = await importTenantDataBundle(
        _dio(server),
        _bundle(rows),
        skip: {'tenant-svc/tenants'},
        pageSize: 2,
      );
      expect(done.complete, isTrue);
      expect(done.rows, 5);
      expect(server.posts('/tables/tenants'), isEmpty);
      expect(
        server
            .posts('/tables/products')
            .map((o) => ((o.data as Map)['rows'] as List).length),
        [2, 2, 1],
      );

      final refused = _Server()..refuseTable = 'stores';
      final stopped = await importTenantDataBundle(
        _dio(refused),
        _bundle([
          {
            'service': 'tenant-svc',
            'table': 'stores',
            'row': {'id': 's'},
          },
          {
            'service': 'product-svc',
            'table': 'products',
            'row': {'id': 'p'},
          },
        ]),
      );
      expect(stopped.complete, isFalse);
      expect(stopped.refusedAt, 'tenant-svc/stores');
      expect(stopped.refusal, contains('already here'));
      expect(refused.posts('/tables/products'), isEmpty);
    },
  );

  test(
    'a file that is not a bundle, or names what is not exported, sends nothing',
    () async {
      final server = _Server();
      final dio = _dio(server);
      Future<void> refuses(String bundle, String message) async {
        await expectLater(
          importTenantDataBundle(dio, bundle),
          throwsA(
            isA<FormatException>().having(
              (e) => e.message,
              'message',
              contains(message),
            ),
          ),
        );
      }

      await refuses('', 'empty');
      await refuses('{"format":"something-else"}', 'Not a StoreQL data bundle');
      await refuses('not json at all', 'Not a StoreQL data bundle');
      await refuses(
        _bundle([
          {
            'service': 'product-svc',
            'table': 'products',
            'row': {'id': 'p'},
          },
          {
            'service': 'evil-svc',
            'table': 'products',
            'row': {'id': 'x'},
          },
        ]),
        'names no table',
      );
      await refuses(
        _bundle([
          {
            'service': 'product-svc',
            'table': 'products; DROP TABLE x',
            'row': {'id': 'x'},
          },
        ]),
        'names no table',
      );
      await refuses(
        _bundle([
          {'service': 'product-svc', 'table': 'products', 'row': 'x'},
        ]),
        'not a row',
      );
      await refuses(
        '${jsonEncode({'format': tenantBundleFormat})}\n{nope',
        'not JSON',
      );
      expect(server.requests, isEmpty);
    },
  );

  testWidgets(
    'notice is checked, given to erase, and a refusal is shown in words',
    (tester) async {
      final server = await _pump(tester);
      await tester.tap(find.widgetWithText(FilledButton, 'Give notice'));
      await tester.pumpAndSettle();
      final ends = find.widgetWithText(TextFormField, 'Notice ends on *');
      await tester.enterText(ends, 'soon');
      await tester.tap(find.widgetWithText(FilledButton, 'Give notice').last);
      await tester.pumpAndSettle();
      expect(find.text('Use YYYY-MM-DD'), findsOneWidget);
      expect(server.called('POST', '/admin/tenant/switching'), isFalse);

      await tester.tap(find.text('Erase the data'));
      await tester.pumpAndSettle();
      expect(find.textContaining('erased when notice ends'), findsOneWidget);
      await tester.enterText(ends, '2026-10-01');
      server
        ..failStatus = 409
        ..failMessage =
            'a notice already stands; withdraw it before giving another';
      await tester.tap(find.widgetWithText(FilledButton, 'Give notice').last);
      await tester.pumpAndSettle();
      final sent = server.posts('/admin/tenant/switching').single.data as Map;
      expect(sent['intent'], 'ERASE');
      expect(sent['noticeEndsOn'], '2026-10-01');
      expect(find.textContaining('a notice already stands'), findsOneWidget);
    },
  );

  testWidgets(
    'a running notice can be withdrawn with a reason; an erasure shows who is left',
    (tester) async {
      final server = await _pump(tester, switching: _status('NOTICE'));
      expect(find.textContaining('Notice is running'), findsOneWidget);
      expect(find.text('15 Dec 2026'), findsOneWidget);
      expect(find.text('Extend transition'), findsOneWidget);
      await tester.tap(find.widgetWithText(TextButton, 'Withdraw notice'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Withdraw'));
      await tester.pumpAndSettle();
      expect(find.text('Required'), findsOneWidget);
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Reason *'),
        'staying after all',
      );
      await tester.tap(find.widgetWithText(FilledButton, 'Withdraw'));
      await tester.pumpAndSettle();
      expect(
        (server.posts('/switching/cancel').single.data as Map)['reason'],
        'staying after all',
      );

      await _pump(tester, switching: _status('ERASING', intent: 'ERASE'));
      expect(find.textContaining('waiting for every service'), findsOneWidget);
      expect(find.text('Waiting for: Orders'), findsOneWidget);
      expect(find.text('Business and stores: 12 rows erased'), findsOneWidget);
      expect(find.text('Withdraw notice'), findsNothing);
      expect(find.text('Extend transition'), findsNothing);
    },
  );
}
