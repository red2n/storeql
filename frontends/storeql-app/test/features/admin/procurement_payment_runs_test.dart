import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/shared/widgets/status_badge.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/payment_runs_tab.dart';
import 'package:storeql_app/features/admin/procurement_screen.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// Supplier payment runs (17.10). A manager holding finance.payments reviews a
// proposed run — what each supplier is paid, the credit notes offset, the
// supplier left out, the bank-details warning — approves someone else's run,
// downloads the bank file and marks an approved run paid after confirming.
// The proposer cannot approve their own run unless they own the business;
// anyone without finance.payments is told so and the API is never called; bad
// dates and half-keyed bank details are refused before a request is sent.
// Bank-standard files (17.12): the formats a run's currency can be paid in,
// the bank's status report read back, a close match held until released with
// what was checked, and the paying accounts.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Auth extends AuthNotifier {
  final List<String> roles;
  final List<String>? permissions;
  final String userId;
  _Auth(this.roles, this.permissions, this.userId);
  @override
  Future<AuthState> build() async => AuthAuthenticated(
    accessToken: 'a',
    refreshToken: 'r',
    userId: userId,
    tenantId: 't',
    roles: roles,
    permissions: permissions,
  );
}

Map<String, dynamic> _run(String status) => {
  'id': 'r-1',
  'reference': 'PAY260913-3F9A1C',
  'status': status,
  'payUpTo': '2026-09-20',
  'paymentDate': '2026-09-13',
  'currency': 'GBP',
  'total': 55.0,
  'proposedBy': 'u-1',
  'suppliers': [
    {
      'supplierId': 's-1',
      'name': 'Acme Ltd',
      'net': 35.0,
      'remittanceEmailOnFile': true,
      'warnings': ['BANK_DETAILS_CHANGED_RECENTLY'],
      'documents': [
        {
          'type': 'INVOICE',
          'documentId': 'i-1',
          'reference': 'INV-A1',
          'dueDate': '2026-08-14',
          'amount': 40.0,
        },
        {
          'type': 'CREDIT_NOTE',
          'documentId': 'v-1',
          'reference': 'CN-A',
          'amount': 5.0,
        },
      ],
    },
    {
      'supplierId': 's-2',
      'name': 'Muster GmbH',
      'net': 20.0,
      'remittanceEmailOnFile': false,
      'warnings': <String>[],
      'documents': [
        {
          'type': 'INVOICE',
          'documentId': 'i-2',
          'reference': 'INV-B1',
          'amount': 20.0,
        },
      ],
    },
  ],
  'excluded': [
    {
      'supplierId': 's-3',
      'name': 'No Bank Ltd',
      'reason': 'NO_BANK_DETAILS',
      'net': 12.0,
    },
  ],
};

/// The run with the bank's check on Muster's payment (17.12).
Map<String, dynamic> _checked(String status, Map<String, dynamic> check) =>
    _run(status)..['suppliers'][1]['bankCheck'] = check;

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  Map<String, dynamic> run = _run('PROPOSED');
  int failStatus = 0;
  String failCode = '';
  String failMessage = '';

  bool called(String method, String pathEnd) =>
      requests.any((o) => o.method == method && o.path.endsWith(pathEnd));

  RequestOptions last(String method, String pathEnd) =>
      requests.lastWhere((o) => o.method == method && o.path.endsWith(pathEnd));

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
    if (o.path.endsWith('/bank-file')) {
      return ResponseBody.fromString(
        'payee_name,sort_code\r\n',
        200,
        headers: {
          Headers.contentTypeHeader: ['text/csv'],
        },
      );
    }
    if (o.path.endsWith('/paying-accounts')) {
      return json({
        'data': [
          {
            'currency': 'GBP',
            'accountName': 'Corner Shop Ltd',
            'sortCode': '402811',
            'accountNumberMasked': '****5678',
            'serviceUserNumber': '123456',
            'sendsBacs': true,
            'sendsSepa': false,
          },
        ],
      });
    }
    if ((o.method == 'POST' || o.method == 'PUT') &&
        (o.path.contains('/payment-runs') || o.path.endsWith('/suppliers'))) {
      if (failStatus != 0) {
        return json({
          'error': {'code': failCode, 'message': failMessage},
        }, failStatus);
      }
      if (o.path.endsWith('/suppliers')) {
        return json({
          'data': {'id': 's-9', 'name': 'Acme'},
        }, 201);
      }
      if (o.path.endsWith('/payment-runs')) return json({'data': run}, 201);
      return json({'data': run});
    }
    if (o.path.endsWith('/payment-runs')) {
      return json({
        'data': [run],
      });
    }
    // The tenant the dialogs start from (SJ-D53).
    if (o.path.endsWith('/admin/tenant')) {
      return json({
        'data': {
          'id': 't',
          'name': 'Shop',
          'status': 'ACTIVE',
          'currency': 'GBP',
          'country': 'GB',
        },
      });
    }
    return json({'data': <Object>[]});
  }
}

Future<_Server> _pump(
  WidgetTester tester, {
  List<String> roles = const ['MANAGER'],
  List<String>? permissions,
  String userId = 'u-2',
  String status = 'PROPOSED',
  String tab = 'Payments',
  Map<String, dynamic>? musterCheck,
  List<Override> overrides = const [],
}) async {
  tester.view.physicalSize = const Size(1200, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server()
    ..run = musterCheck == null ? _run(status) : _checked(status, musterCheck);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      key: UniqueKey(),
      overrides: [
        apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
        authNotifierProvider.overrideWith(
          () => _Auth(roles, permissions, userId),
        ),
        ...overrides,
      ],
      child: const MaterialApp(home: ProcurementScreen()),
    ),
  );
  await tester.pumpAndSettle();
  await tester.tap(find.text(tab));
  await tester.pumpAndSettle();
  return server;
}

FilledButton _filled(WidgetTester tester, String label) =>
    tester.widget<FilledButton>(find.widgetWithText(FilledButton, label).first);

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  // Dates are written with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);
  testWidgets('a finance manager reviews a proposed run and approves it', (
    tester,
  ) async {
    final server = await _pump(tester);

    expect(find.text('PAY260913-3F9A1C'), findsOneWidget);
    // The run's status in words, its days as dates.
    expect(find.widgetWithText(StatusBadge, 'Proposed'), findsOneWidget);
    expect(find.textContaining('pay on 13 Sept 2026 · due by 20 Sept 2026'), findsOneWidget);
    expect(find.textContaining('PROPOSED'), findsNothing);
    expect(find.text('Acme Ltd'), findsOneWidget);
    expect(find.text('Muster GmbH'), findsOneWidget);
    expect(find.textContaining('Credit note CN-A'), findsOneWidget);
    expect(
      find.textContaining('Bank details changed in the last 14 days'),
      findsOneWidget,
    );
    expect(find.textContaining('No remittance email'), findsOneWidget);
    expect(find.text('No Bank Ltd'), findsOneWidget);
    expect(find.text('No bank details on file'), findsOneWidget);
    // Nothing is filed or paid before approval.
    expect(find.text('Bank file'), findsNothing);
    expect(find.text('Mark paid'), findsNothing);

    await tester.tap(find.widgetWithText(FilledButton, 'Approve'));
    await tester.pumpAndSettle();
    expect(server.called('POST', '/payment-runs/r-1/approve'), isTrue);
    expect(find.text('Payment run PAY260913-3F9A1C approved.'), findsOneWidget);
  });

  testWidgets('the proposer cannot approve their own run; the owner can', (
    tester,
  ) async {
    final server = await _pump(tester, userId: 'u-1');
    expect(_filled(tester, 'Approve').onPressed, isNull);
    await tester.tap(
      find.widgetWithText(FilledButton, 'Approve'),
      warnIfMissed: false,
    );
    await tester.pumpAndSettle();
    expect(server.called('POST', '/approve'), isFalse);

    await _pump(tester, roles: const ['OWNER'], userId: 'u-1');
    expect(_filled(tester, 'Approve').onPressed, isNotNull);
  });

  testWidgets(
    'without finance.payments the tab says so and never calls the API',
    (tester) async {
      final narrowed = await _pump(
        tester,
        permissions: const ['purchasing.approve'],
      );
      expect(
        find.textContaining('need the finance.payments permission'),
        findsOneWidget,
      );
      expect(find.text('Propose run'), findsNothing);
      expect(narrowed.called('GET', '/payment-runs'), isFalse);

      final storekeeper = await _pump(tester, roles: const ['STOREKEEPER']);
      expect(
        find.textContaining('need the finance.payments permission'),
        findsOneWidget,
      );
      expect(storekeeper.called('GET', '/payment-runs'), isFalse);
    },
  );

  testWidgets(
    'an approved run is paid only after confirming, and a refusal is shown in words',
    (tester) async {
      final server = await _pump(tester, status: 'APPROVED');
      expect(find.text('Approve'), findsNothing);

      await tester.tap(find.text('Bank file'));
      await tester.pumpAndSettle();
      // A sterling run is offered Bacs and the CSV, never a SEPA file.
      expect(find.text('Bacs Standard 18'), findsOneWidget);
      expect(find.text('CSV for bulk upload'), findsOneWidget);
      expect(find.text('SEPA credit transfer (pain.001)'), findsNothing);
      await tester.tap(find.text('Bacs Standard 18'));
      await tester.pumpAndSettle();
      expect(server.called('GET', '/payment-runs/r-1/bank-file'), isTrue);
      expect(
        server.last('GET', '/bank-file').queryParameters['format'],
        'BACS18',
      );
      expect(
        find.text('Bank file for PAY260913-3F9A1C downloaded.'),
        findsOneWidget,
      );

      await tester.tap(find.widgetWithText(FilledButton, 'Mark paid'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Not yet'));
      await tester.pumpAndSettle();
      expect(server.called('POST', '/pay'), isFalse);

      server
        ..failStatus = 409
        ..failCode = 'PURCHASE_PAYMENT_RUN_BANK_DETAILS_CHANGED'
        ..failMessage =
            'bank details changed after this run was approved; cancel it and propose again';
      await tester.tap(find.widgetWithText(FilledButton, 'Mark paid'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Mark paid').last);
      await tester.pumpAndSettle();
      expect(server.called('POST', '/payment-runs/r-1/pay'), isTrue);
      expect(
        find.textContaining('bank details changed after this run was approved'),
        findsOneWidget,
      );
    },
  );

  testWidgets('proposing checks the dates first and shows why nothing is due', (
    tester,
  ) async {
    final server = await _pump(tester);
    await tester.tap(find.text('Propose run'));
    await tester.pumpAndSettle();

    final dueBy = find.widgetWithText(TextFormField, 'Pay invoices due by');
    await tester.enterText(dueBy, '2026-02-30');
    await tester.tap(find.widgetWithText(FilledButton, 'Propose'));
    await tester.pumpAndSettle();
    expect(find.text('Not a date'), findsOneWidget);
    await tester.enterText(dueBy, 'soon');
    await tester.tap(find.widgetWithText(FilledButton, 'Propose'));
    await tester.pumpAndSettle();
    expect(find.text('Use YYYY-MM-DD'), findsOneWidget);
    expect(server.called('POST', '/payment-runs'), isFalse);

    await tester.enterText(dueBy, '2026-09-20');
    server
      ..failStatus = 409
      ..failCode = 'PURCHASE_PAYMENT_RUN_NOTHING_DUE'
      ..failMessage = 'nothing payable is due by 2026-09-20 in GBP';
    await tester.tap(find.widgetWithText(FilledButton, 'Propose'));
    await tester.pumpAndSettle();
    expect(
      find.text('nothing payable is due by 2026-09-20 in GBP'),
      findsOneWidget,
    );
    expect(server.last('POST', '/payment-runs').data['payUpTo'], '2026-09-20');

    server.failStatus = 0;
    await tester.tap(find.widgetWithText(FilledButton, 'Propose'));
    await tester.pumpAndSettle();
    expect(find.text('Propose payment run'), findsNothing);
    expect(find.textContaining('Proposed PAY260913-3F9A1C'), findsOneWidget);
    expect(find.textContaining('1 left out'), findsOneWidget);
  });

  testWidgets('cancelling a run needs a reason', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.widgetWithText(TextButton, 'Cancel run'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Cancel run'));
    await tester.pumpAndSettle();
    expect(find.text('Required'), findsOneWidget);
    expect(server.called('POST', '/cancel'), isFalse);

    await tester.enterText(
      find.widgetWithText(TextFormField, 'Reason *'),
      'Supplier disputes INV-A1',
    );
    await tester.tap(find.widgetWithText(FilledButton, 'Cancel run'));
    await tester.pumpAndSettle();
    expect(
      server.last('POST', '/payment-runs/r-1/cancel').data['reason'],
      'Supplier disputes INV-A1',
    );
  });

  testWidgets(
    'bank details on a supplier: checked before sending, and only for finance',
    (tester) async {
      final server = await _pump(tester, tab: 'Suppliers');
      await tester.tap(find.text('Add supplier'));
      await tester.pumpAndSettle();
      expect(find.text('Bank details'), findsOneWidget);

      Future<void> add() async {
        final button = find.widgetWithText(FilledButton, 'Add');
        await tester.ensureVisible(button);
        await tester.tap(button);
        await tester.pumpAndSettle();
      }

      await tester.enterText(
        find.widgetWithText(TextFormField, 'Supplier name *'),
        'Acme',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Remittance email'),
        'not-an-email',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Sort code'),
        'ab-cd-ef',
      );
      await add();
      expect(find.text('Not an email address'), findsOneWidget);
      expect(find.text('Six digits'), findsOneWidget);
      expect(find.text('Required with a sort code'), findsOneWidget);
      expect(find.text('Required with bank details'), findsOneWidget);
      expect(server.called('POST', '/suppliers'), isFalse);

      await tester.enterText(
        find.widgetWithText(TextFormField, 'Remittance email'),
        'ap@acme.example',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Sort code'),
        '12-34-56',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Account number'),
        '31415926',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Account holder name'),
        'Acme Ltd',
      );
      await add();
      final sent = server.last('POST', '/suppliers').data as Map;
      expect(sent['bankSortCode'], '12-34-56');
      expect(sent['bankAccountNumber'], '31415926');
      expect(sent['bankAccountName'], 'Acme Ltd');
      expect(sent['remittanceEmail'], 'ap@acme.example');

      // A manager whose role was narrowed out of finance never sees the fields.
      final narrowed = await _pump(
        tester,
        tab: 'Suppliers',
        permissions: const ['purchasing.approve'],
      );
      await tester.tap(find.text('Add supplier'));
      await tester.pumpAndSettle();
      expect(find.text('Bank details'), findsNothing);
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Supplier name *'),
        'Plain Ltd',
      );
      await add();
      final plain = narrowed.last('POST', '/suppliers').data as Map;
      expect(plain.containsKey('bankSortCode'), isFalse);
    },
  );

  testWidgets(
    "the bank's answer holds a close match until a manager releases it",
    (tester) async {
      String? picked;
      final server = await _pump(
        tester,
        status: 'APPROVED',
        overrides: [
          statusReportPickerProvider.overrideWithValue(() async => picked),
        ],
      );
      final answer = find.widgetWithText(OutlinedButton, "Bank's answer");

      // Dismissed, or a file too large to be a report: nothing is sent.
      await tester.tap(answer);
      await tester.pumpAndSettle();
      picked = 'x' * (maxStatusReportChars + 1);
      await tester.tap(answer);
      await tester.pumpAndSettle();
      expect(
        find.text('That file is too large to be a status report.'),
        findsOneWidget,
      );
      expect(server.called('POST', '/status-report'), isFalse);

      picked = '<Document/>';
      server.run = _checked('APPROVED', {
        'endToEndId': 'e2e-2',
        'status': 'ACCP',
        'payeeMatch': 'CMTC',
        'matchedName': 'MUSTER HANDELS GMBH',
        'held': true,
        'releasable': true,
      });
      await tester.tap(answer);
      await tester.pumpAndSettle();
      final sent = server.last('POST', '/payment-runs/r-1/status-report');
      expect(sent.data, '<Document/>');
      expect(sent.contentType, startsWith('application/xml'));
      expect(
        find.text(
          'Status report read: the bank holds 1 payment(s) in PAY260913-3F9A1C.',
        ),
        findsOneWidget,
      );
      expect(
        find.text(
          'Close match: the bank holds the account as "MUSTER HANDELS GMBH"',
        ),
        findsOneWidget,
      );
      expect(find.textContaining('1 held by the bank'), findsOneWidget);
      expect(_filled(tester, 'Mark paid').onPressed, isNull);

      await tester.tap(find.widgetWithText(TextButton, 'Release'));
      await tester.pumpAndSettle();
      expect(find.text('Release the payment to Muster GmbH?'), findsOneWidget);
      await tester.tap(find.widgetWithText(FilledButton, 'Release'));
      await tester.pumpAndSettle();
      expect(find.text('Required'), findsOneWidget);
      expect(server.called('POST', '/release'), isFalse);
      await tester.enterText(
        find.widgetWithText(TextFormField, 'What you checked *'),
        'Rang Muster on the number we hold',
      );
      await tester.tap(find.widgetWithText(FilledButton, 'Release'));
      await tester.pumpAndSettle();
      expect(
        server
            .last('POST', '/payment-runs/r-1/payments/s-2/release')
            .data['reason'],
        'Rang Muster on the number we hold',
      );
      expect(find.text('Payment to Muster GmbH released.'), findsOneWidget);
    },
  );

  testWidgets('a payee the bank could not match cannot be released', (
    tester,
  ) async {
    await _pump(
      tester,
      status: 'APPROVED',
      musterCheck: {
        'endToEndId': 'e2e-2',
        'status': 'PDNG',
        'payeeMatch': 'NMTC',
        'held': true,
        'releasable': false,
      },
    );
    expect(
      find.textContaining('could not match the name to the account'),
      findsOneWidget,
    );
    expect(find.widgetWithText(TextButton, 'Release'), findsNothing);
    expect(_filled(tester, 'Mark paid').onPressed, isNull);
  });

  testWidgets(
    'paying accounts: listed, checked before sending, refusals shown',
    (tester) async {
      final server = await _pump(tester);
      Future<void> open() async {
        await tester.tap(find.text('Paying accounts'));
        await tester.pumpAndSettle();
      }

      Future<void> save() async {
        await tester.tap(find.widgetWithText(FilledButton, 'Save account'));
        await tester.pumpAndSettle();
      }

      Finder field(String label) => find.widgetWithText(TextFormField, label);

      await open();
      expect(find.text('GBP · Corner Shop Ltd'), findsOneWidget);
      expect(find.textContaining('****5678'), findsOneWidget);
      // The tenant's own currency to start from (SJ-D53).
      expect(
        tester.widget<TextFormField>(field('Currency *')).controller!.text,
        'GBP',
      );

      await tester.enterText(field('Sort code'), 'ab-cd-ef');
      await tester.enterText(field('Bacs service user number'), '12345');
      await save();
      expect(find.text('Required'), findsOneWidget);
      expect(find.text('Six digits'), findsNWidgets(2));
      expect(find.text('Required with a sort code'), findsOneWidget);
      expect(server.called('PUT', '/paying-accounts/GBP'), isFalse);

      await tester.enterText(field('Account holder name *'), 'Corner Shop Ltd');
      await tester.enterText(field('Sort code'), '40-28-11');
      await tester.enterText(field('Account number'), '12345678');
      await tester.enterText(field('Bacs service user number'), '123456');
      await save();
      final sent =
          server.last('PUT', '/payment-runs/paying-accounts/GBP').data as Map;
      expect(sent['sortCode'], '40-28-11');
      expect(sent['serviceUserNumber'], '123456');
      expect(sent['iban'], isNull);
      expect(
        find.text('GBP payments are now made from Corner Shop Ltd.'),
        findsOneWidget,
      );

      await open();
      await tester.enterText(field('Currency *'), 'eur');
      await tester.enterText(field('Account holder name *'), 'Corner Shop BV');
      await tester.enterText(field('Sort code'), '40-28-11');
      await tester.enterText(field('Account number'), '12345678');
      server
        ..failStatus = 400
        ..failCode = 'PURCHASE_PAYING_ACCOUNT_INVALID'
        ..failMessage = 'a euro paying account needs its IBAN';
      await save();
      expect(server.called('PUT', '/paying-accounts/EUR'), isTrue);
      expect(find.text('a euro paying account needs its IBAN'), findsOneWidget);
      expect(find.text('Paying accounts'), findsWidgets);
    },
  );
}
