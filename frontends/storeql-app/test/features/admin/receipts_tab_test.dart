import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/shared/widgets/status_badge.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/admin/receipts_tab.dart';

// ---------------------------------------------------------------------------
// The legal receipt register: the series a store runs, the documents in
// order, and the inspector's question answered from the table. A manager sets
// a prefix; nothing here renumbers, deletes or edits a receipt, and the test
// would fail if it did.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
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
      );
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  bool intact = true;
  int putStatus = 200;
  String putError = 'RECEIPT_PREFIX_INVALID';
  String regime = 'NONE';
  int settingsPutStatus = 200;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    String body;
    var status = 200;
    if (o.path.endsWith('/fiscal-receipts/series') && o.method == 'PUT') {
      status = putStatus;
      body = status == 200
          ? '{"data":{"storeId":"s1","seriesCode":"MAIN","period":"2026","nextNumber":3,"prefix":"GB-A"}}'
          : '{"error":{"code":"$putError","message":"a prefix is letters, digits and hyphens, at most 16"}}';
    } else if (o.path.endsWith('/fiscal-receipts/settings') && o.method == 'PUT') {
      status = settingsPutStatus;
      body = status == 200
          ? '{"data":{"storeId":"s1","regime":"DE_KASSENSICHV","taxRegistrationNumber":"DE123456789","tse":{"provider":"SIMULATED","clientId":"till-1","serialNumber":"0123456789abcdef0123456789abcdef","signatureCounter":0},"regimes":["NONE","DE_KASSENSICHV","PT_SAFT"],"tseProviders":["SIMULATED"],"ptKeyConfigured":false}}'
          : '{"error":{"code":"FISCAL_TAX_NUMBER_REQUIRED","message":"A German store needs its Steuernummer or USt-IdNr on the register"}}';
    } else if (o.path.endsWith('/fiscal-receipts/settings')) {
      body = regime == 'NONE'
          ? '{"data":{"storeId":"s1","regime":"NONE","regimes":["NONE","DE_KASSENSICHV","PT_SAFT"],"tseProviders":["SIMULATED"],"ptKeyConfigured":false}}'
          : '{"data":{"storeId":"s1","regime":"DE_KASSENSICHV","taxRegistrationNumber":"DE123456789","tse":{"provider":"SIMULATED","clientId":"till-1","serialNumber":"0123456789abcdef0123456789abcdef","signatureCounter":2},"regimes":["NONE","DE_KASSENSICHV","PT_SAFT"],"tseProviders":["SIMULATED"],"ptKeyConfigured":false}}';
    } else if (o.path.endsWith('/fiscal-receipts/series')) {
      body = '{"data":[{"storeId":"s1","seriesCode":"MAIN","period":"2026","nextNumber":3,"prefix":"GB-A"}]}';
    } else if (o.path.endsWith('/fiscal-receipts/audit')) {
      body = intact
          ? '{"data":{"firstNumber":1,"lastNumber":2,"issued":2,"expected":2,"intact":true,"gaps":[],"chainIntact":true,"chainFrom":1}}'
          : '{"data":{"firstNumber":1,"lastNumber":5,"issued":3,"expected":5,"intact":false,"gaps":[{"from":2,"to":3}],"chainIntact":false,"chainFrom":1,"chainBrokenAt":4}}';
    } else if (o.path.endsWith('/fiscal-receipts/export') && o.queryParameters['format'] == 'saft-pt') {
      return ResponseBody.fromString(
          '<?xml version="1.0" encoding="UTF-8"?><AuditFile xmlns="urn:OECD:StandardAuditFile-Tax:PT_1.04_01"><Header><AuditFileVersion>1.04_01</AuditFileVersion></Header></AuditFile>',
          200,
          headers: {Headers.contentTypeHeader: ['application/xml']});
    } else if (o.path.endsWith('/fiscal-receipts/export') && o.queryParameters['format'] == 'dsfinvk') {
      return ResponseBody.fromBytes(
          [0x50, 0x4b, 0x03, 0x04], 200, headers: {Headers.contentTypeHeader: ['application/zip']});
    } else if (o.path.endsWith('/fiscal-receipts/export')) {
      return ResponseBody.fromString(
          'number,fullNumber,issuedAt,orderId,currency,grossTotal,taxTotal,voidedAt,voidReason,prevHash,hash\n'
          '1,GB-A-2026-000001,2026-09-12T10:00:00Z,01a090ae-611e-701e-a773-cff68a489efe,GBP,12.5000,2.0800,,,GENESIS,ab12\n'
          '2,GB-A-2026-000002,2026-09-12T10:05:00Z,01a090ae-611e-701e-a773-cff68a489eff,GBP,3.0000,0.5000,2026-09-12T10:06:00Z,wrong item,ab12,cd34\n',
          200,
          headers: {Headers.contentTypeHeader: ['text/csv']});
    } else if (o.path.endsWith('/fiscal-receipts/export')) {
      // (text formats are handled above; the zip below)
      body = '{"data":[]}';
    } else if (o.path.endsWith('/fiscal-receipts')) {
      body = regime == 'NONE'
          ? '{"data":['
              '{"fullNumber":"GB-A-2026-000001","number":1,"orderId":"01a090ae-611e-701e-a773-cff68a489efe","issuedAt":"2026-09-12T10:00:00Z","grossTotal":12.50,"currency":"GBP"},'
              '{"fullNumber":"GB-A-2026-000002","number":2,"orderId":"01a090ae-611e-701e-a773-cff68a489eff","issuedAt":"2026-09-12T10:05:00Z","grossTotal":3.00,"currency":"GBP","voidedAt":"2026-09-12T10:06:00Z"}'
              ']}'
          : '{"data":['
              '{"fullNumber":"DE-B-2026-000001","number":1,"orderId":"01a090ae-611e-701e-a773-cff68a489efe","issuedAt":"2026-09-12T10:00:00Z","grossTotal":11.90,"currency":"EUR","regime":"DE_KASSENSICHV","tse":{"signatureCounter":1,"signature":"SIG"}},'
              '{"fullNumber":"DE-B-2026-000002","number":2,"orderId":"01a090ae-611e-701e-a773-cff68a489eff","issuedAt":"2026-09-12T10:05:00Z","grossTotal":3.00,"currency":"EUR","regime":"DE_KASSENSICHV","tse":{"error":"cloud TSE unreachable"}}'
              ']}';
    } else {
      body = '{"data":[]}';
    }
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Future<_Server> _pump(WidgetTester tester,
    {String role = 'MANAGER', bool intact = true, String regime = 'NONE'}) async {
  tester.view.physicalSize = const Size(1100, 2000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final server = _Server()
    ..intact = intact
    ..regime = regime;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => _Auth(role)),
      storesProvider.overrideWith((ref) async => const [
            StoreInfo(id: 's1', name: 'High Street', code: 'HS', type: 'STORE', status: 'ACTIVE', country: 'GB'),
          ]),
    ],
    child: const MaterialApp(home: Scaffold(body: ReceiptsTab())),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  // Receipts are dated with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);
  testWidgets('the series, the audit and the receipts in order are shown', (tester) async {
    final server = await _pump(tester);
    expect(find.text('MAIN · 2026'), findsOneWidget);
    expect(find.text('prefix GB-A · next number 3'), findsOneWidget);
    expect(find.text('Sequence intact'), findsOneWidget);
    expect(find.text('GB-A-2026-000001'), findsOneWidget);
    expect(find.text('GB-A-2026-000002'), findsOneWidget);
    // A voided sale keeps its number and is shown as void, not removed: a
    // badge in words, with its amount as money.
    expect(find.widgetWithText(StatusBadge, 'Void'), findsOneWidget);
    expect(find.textContaining('VOID'), findsNothing);
    expect(find.textContaining(RegExp(r'[A-Z]{3} \d')), findsNothing,
        reason: 'money as a symbol and grouping, never a code and a bare number');
    // Every read went to the admin routes, filtered by store, series and year.
    final audit = server.requests.firstWhere((r) => r.path.endsWith('/audit'));
    expect(audit.queryParameters, {'storeId': 's1', 'series': 'MAIN', 'period': DateTime.now().year.toString()});
    // Nothing on this screen deletes or edits a receipt.
    expect(server.requests.where((r) => r.method != 'GET'), isEmpty);
  });

  testWidgets('a broken sequence is shown with the missing range', (tester) async {
    await _pump(tester, intact: false);
    expect(find.text('Sequence has gaps'), findsOneWidget);
    expect(find.text('Missing 2–3'), findsOneWidget);
    expect(find.textContaining('issued 3 · expected 5'), findsOneWidget);
    // The hash chain is a second, independent verdict: an altered document, not a missing one.
    expect(find.textContaining('Hash chain broken at no. 4'), findsOneWidget);
  });

  testWidgets('an intact hash chain says so, from the first chained number', (tester) async {
    await _pump(tester);
    expect(find.textContaining('Hash chain intact from no. 1'), findsOneWidget);
  });

  testWidgets('the register exports as CSV with the hash chain, to copy', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.text('Export'));
    await tester.pumpAndSettle();
    expect(find.text('Export register'), findsOneWidget);
    expect(find.textContaining('2 documents'), findsOneWidget);
    final csv = tester.widget<SelectableText>(find.byKey(const Key('receipt-export-csv'))).data!;
    expect(csv, contains('GB-A-2026-000002'));
    expect(csv, contains('prevHash,hash'));
    final export = server.requests.singleWhere((r) => r.path.endsWith('/fiscal-receipts/export'));
    expect(export.queryParameters['format'], 'csv');
    expect(export.queryParameters['storeId'], 's1');
    expect(server.requests.where((r) => r.method != 'GET'), isEmpty);
  });

  testWidgets('a manager sets a prefix; the server refuses a bad one and the screen says so',
      (tester) async {
    final server = await _pump(tester)..putStatus = 400;
    await tester.tap(find.text('Set prefix'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Prefix'), 'not a prefix!');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.textContaining('letters, digits and hyphens'), findsOneWidget);
    expect(find.byType(AlertDialog), findsOneWidget, reason: 'the dialog stays open to fix it');

    server.putStatus = 200;
    await tester.enterText(find.widgetWithText(TextField, 'Prefix'), 'GB-A');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsNothing);
    final put = server.requests.lastWhere((r) => r.method == 'PUT');
    expect(put.path, endsWith('/admin/fiscal-receipts/series'));
    final body = put.data is String ? jsonDecode(put.data as String) : put.data;
    expect(body, {'storeId': 's1', 'seriesCode': 'MAIN', 'period': '2026', 'prefix': 'GB-A'});
  });

  testWidgets('a cashier reads nothing here that they could change', (tester) async {
    await _pump(tester, role: 'CASHIER');
    // The screen renders read-only for the role that cannot set a prefix — the
    // server refuses it anyway; this keeps the button from being offered.
    expect(find.text('Set prefix'), findsNothing);
    expect(find.byKey(const Key('regime-change')), findsNothing);
  });

  // ── the fiscal regime (18.5) ─────────────────────────────────────────────

  testWidgets('a store under NONE says so, and its rows carry no stamp', (tester) async {
    await _pump(tester);
    expect(find.byKey(const Key('regime-label')), findsOneWidget);
    expect(find.text('None — the register alone'), findsOneWidget);
    expect(find.textContaining('TSE #'), findsNothing);
  });

  testWidgets('a German store shows its module, and each receipt its signature counter or the outage',
      (tester) async {
    await _pump(tester, regime: 'DE_KASSENSICHV');
    expect(find.text('Germany — KassenSichV (TSE, DSFinV-K)'), findsOneWidget);
    expect(find.textContaining('Security module SIMULATED'), findsOneWidget);
    expect(find.textContaining('signatures so far 2'), findsOneWidget);
    expect(find.text('TSE #1'), findsOneWidget);
    expect(find.text('TSE ausgefallen'), findsOneWidget);
  });

  testWidgets('a manager places a store under Germany; the server refuses without a tax number and the screen says so',
      (tester) async {
    final server = await _pump(tester)..settingsPutStatus = 400;
    await tester.tap(find.byKey(const Key('regime-change')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('regime-select')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Germany — KassenSichV (TSE, DSFinV-K)').last);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('regime-tse-provider')), findsOneWidget);
    await tester.tap(find.byKey(const Key('regime-save')));
    await tester.pumpAndSettle();
    expect(find.textContaining('Steuernummer or USt-IdNr'), findsOneWidget);
    expect(find.byType(AlertDialog), findsOneWidget, reason: 'the dialog stays open to fix it');

    server.settingsPutStatus = 200;
    await tester.enterText(find.byKey(const Key('regime-tax-number')), 'DE123456789');
    await tester.tap(find.byKey(const Key('regime-save')));
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsNothing);
    final put = server.requests.lastWhere((r) => r.method == 'PUT');
    expect(put.path, endsWith('/admin/fiscal-receipts/settings'));
    final body = put.data is String ? jsonDecode(put.data as String) : put.data;
    expect(body['storeId'], 's1');
    expect(body['regime'], 'DE_KASSENSICHV');
    expect(body['taxRegistrationNumber'], 'DE123456789');
    expect(body['tseProvider'], 'SIMULATED', reason: 'a store with no device registers one');
  });

  testWidgets('Portugal without a key on the server is said plainly in the dialog', (tester) async {
    await _pump(tester);
    await tester.tap(find.byKey(const Key('regime-change')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('regime-select')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Portugal — certified software (SAF-T)').last);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('regime-pt-no-key')), findsOneWidget);
    expect(find.widgetWithText(TextField, 'NIF'), findsOneWidget);
  });

  testWidgets('the export offers the inspector\'s file for the regime: SAF-T as text to copy',
      (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.text('Export'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('receipt-export-format')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('SAF-T (PT) — Portugal').last);
    await tester.pumpAndSettle();
    final xml = tester.widget<SelectableText>(find.byKey(const Key('receipt-export-csv'))).data!;
    expect(xml, contains('AuditFileVersion'));
    expect(find.text('Copy XML'), findsOneWidget);
    final export = server.requests.lastWhere((r) => r.path.endsWith('/fiscal-receipts/export'));
    expect(export.queryParameters['format'], 'saft-pt');
  });

  testWidgets('a German store\'s export defaults to DSFinV-K, fetched as a zip to save', (tester) async {
    final server = await _pump(tester, regime: 'DE_KASSENSICHV');
    await tester.tap(find.text('Export'));
    await tester.pumpAndSettle();
    expect(find.text('Save zip'), findsOneWidget);
    expect(find.textContaining('index.xml'), findsOneWidget);
    // Nothing is fetched until the manager asks for the file.
    expect(server.requests.where((r) => r.path.endsWith('/fiscal-receipts/export')), isEmpty);
  });
}
