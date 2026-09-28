import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/platform/billing_screen.dart';

import '../../support/fake_api.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// The platform's own billing (21.9): what is owed, oldest first, and whether the
// platform can invoice at all — which is worth its own card, because a platform
// that has not said who it is looks exactly like a platform with no customers.
// ---------------------------------------------------------------------------

Map<String, dynamic> _profile() => {
      'legalName': 'StoreQL Platform Ltd',
      'country': 'IE',
      'invoicePrefix': 'INV',
      'paymentTermsDays': 14,
      'taxRate': 0.23,
      'vatNumber': 'IE1234567X',
    };

List<Map<String, dynamic>> _owed() => [
      {
        'id': 'i1',
        'number': 'INV-2026-000041',
        'issueDate': '2026-08-01',
        'dueDate': '2026-08-15',
        'currency': 'EUR',
        'totalAmount': 12.30,
        'outstanding': 12.30,
        'taxTreatment': 'DOMESTIC',
      },
      {
        'id': 'i2',
        'number': 'INV-2026-000042',
        'issueDate': '2099-01-01',
        'dueDate': '2099-01-15',
        'currency': 'EUR',
        'totalAmount': 20.00,
        'outstanding': 7.70,
        'taxTreatment': 'DOMESTIC',
      },
    ];

/// The profile is asked for separately and may legitimately answer 409.
class _Server implements HttpClientAdapter {
  final Map<String, dynamic>? profile;
  final List<Map<String, dynamic>> owed;
  final List<Map<String, dynamic>> stages;

  /// Businesses the platform names when asked for one by id.
  final Map<String, String> byId;

  _Server({
    required this.profile,
    required this.owed,
    this.stages = const [],
    this.byId = const {},
  });

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    // One business, asked for by id: named when the platform has it.
    final one = RegExp(r'/platform/tenants/([^/]+)$').firstMatch(o.path);
    if (one != null) {
      final name = byId[one.group(1)];
      return name == null
          ? jsonResponse(jsonEncode({'error': {'code': 'TENANT_NOT_FOUND'}}), 404)
          : jsonResponse(jsonEncode({'data': {'id': one.group(1), 'name': name}}));
    }
    if (o.path.contains('/dunning/overdue')) {
      return jsonResponse(jsonEncode({'data': stages}));
    }
    if (o.path.contains('/receivables')) {
      return jsonResponse(jsonEncode({'data': owed}));
    }
    if (profile == null) {
      return jsonResponse(
        jsonEncode({
          'code': 'BILLING_PROFILE_NOT_SET',
          'error': {'code': 'BILLING_PROFILE_NOT_SET'},
        }),
        409,
      );
    }
    return jsonResponse(jsonEncode({'data': profile}));
  }
}

/// The businesses the overdue list's tenant ids resolve against.
const _cornerShop = PlatformTenant(
  id: 't1',
  name: 'Corner Shop',
  status: 'ACTIVE',
  country: 'GB',
  currency: 'GBP',
  createdAt: '2026-01-01T00:00:00Z',
);

Future<void> _pump(
  WidgetTester tester, {
  Map<String, dynamic>? profile,
  List<Map<String, dynamic>>? owed,
  List<Map<String, dynamic>>? stages,
  List<PlatformTenant> tenants = const [],
  Map<String, String> byId = const {},
}) async {
  tester.view.physicalSize = const Size(1200, 2000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter =
        _Server(
            profile: profile, owed: owed ?? const [], stages: stages ?? const [], byId: byId);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(FakeApiClient(dio)),
        allTenantsProvider.overrideWith((ref) async => tenants),
      ],
      child: const MaterialApp(home: Scaffold(body: PlatformBillingScreen())),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  // Due dates are shown as dates (15 Aug 2026), which needs the locale data.
  setUpAll(initializeDateFormatting);

  testWidgets('the platform says who it invoices as, and on what terms', (tester) async {
    await _pump(tester, profile: _profile(), owed: _owed());

    expect(find.byKey(const Key('profile')), findsOneWidget);
    expect(find.byKey(const Key('profile-unset')), findsNothing);
    expect(find.text('StoreQL Platform Ltd'), findsOneWidget);
    expect(find.textContaining('IE · INV-… · 14 days · 23.00%'), findsOneWidget);
    expect(find.text('VAT IE1234567X'), findsOneWidget);
  });

  testWidgets('a platform that has not said who it is is told that nothing can be billed', (tester) async {
    await _pump(tester, profile: null, owed: _owed());

    expect(find.byKey(const Key('profile-unset')), findsOneWidget);
    expect(find.byKey(const Key('profile')), findsNothing);
    expect(find.textContaining('nothing can be billed'), findsOneWidget);
  });

  testWidgets('what is owed is totalled, and the overdue ones are marked', (tester) async {
    await _pump(tester, profile: _profile(), owed: _owed());

    // 12.30 outstanding plus 7.70 of a part-paid invoice, in the invoices' own currency.
    expect(find.byKey(const Key('total-owed')), findsOneWidget);
    expect(find.text('€20.00'), findsOneWidget);
    expect(find.text('€12.30'), findsOneWidget);
    expect(find.text('€7.70'), findsOneWidget);
    expect(find.byKey(const Key('owed-INV-2026-000041')), findsOneWidget);
    expect(find.text('Overdue since 15 Aug 2026'), findsOneWidget);
    // The 2099 one is not overdue yet, and says when it falls due instead.
    expect(find.text('Due 15 Jan 2099'), findsOneWidget);
    expect(find.byIcon(Icons.warning_amber), findsOneWidget);
  });

  testWidgets('what is owed in two currencies is two totals, never one sum', (tester) async {
    await _pump(
      tester,
      profile: _profile(),
      owed: [
        ..._owed().take(1),
        {
          'id': 'i3',
          'number': 'INV-2026-000043',
          'issueDate': '2099-01-01',
          'dueDate': '2099-01-15',
          'currency': 'GBP',
          'totalAmount': 5.50,
          'outstanding': 5.50,
          'taxTreatment': 'DOMESTIC',
        },
      ],
    );

    expect(find.text('€12.30 · £5.50'), findsOneWidget);
    expect(find.text('£5.50'), findsOneWidget);
  });

  testWidgets('an overdue invoice names the business that owes it', (tester) async {
    await _pump(
      tester,
      profile: _profile(),
      owed: _owed(),
      stages: [
        {
          'invoiceId': 'i1',
          'tenantId': 't1',
          'number': 'INV-2026-000041',
          'daysOverdue': 41,
          'stage': null,
          'nextStep': null,
        },
      ],
      tenants: const [_cornerShop],
    );

    expect(find.text('Corner Shop · Overdue since 15 Aug 2026'), findsOneWidget);
    // Not due yet, so not on the overdue list: nothing says whose it is.
    expect(find.text('Due 15 Jan 2099'), findsOneWidget);
  });

  testWidgets('an invoice not yet due names the business from its own tenant id', (tester) async {
    // The overdue list holds overdue invoices alone, so it cannot name one not yet due. The
    // receivable says whose it is itself.
    await _pump(
      tester,
      profile: _profile(),
      owed: [
        for (final r in _owed()) {...r, 'tenantId': 't1'},
      ],
      stages: const [],
      tenants: const [_cornerShop],
    );

    expect(find.text('Corner Shop · Due 15 Jan 2099'), findsOneWidget);
    expect(find.text('Corner Shop · Overdue since 15 Aug 2026'), findsOneWidget);
  });

  testWidgets('a business past the first page of the tenant list is still named', (tester) async {
    // The list answers its first page: twenty businesses, none of them this one.
    final firstPage = [
      for (var i = 0; i < 20; i++)
        PlatformTenant(
          id: 'p$i',
          name: 'Shop $i',
          status: 'ACTIVE',
          country: 'GB',
          currency: 'GBP',
          createdAt: '2026-01-01T00:00:00Z',
        ),
    ];
    await _pump(
      tester,
      profile: _profile(),
      owed: [
        for (final r in _owed()) {...r, 'tenantId': 't-far'},
      ],
      tenants: firstPage,
      byId: const {'t-far': 'Far Away Deli'},
    );

    expect(find.text('Far Away Deli · Due 15 Jan 2099'), findsOneWidget);
    expect(find.text('Far Away Deli · Overdue since 15 Aug 2026'), findsOneWidget);
  });

  testWidgets('a business the console does not know leaves the row to its invoice number', (tester) async {
    await _pump(
      tester,
      profile: _profile(),
      owed: [
        for (final r in _owed()) {...r, 'tenantId': 't-unknown'},
      ],
      tenants: const [_cornerShop],
    );

    expect(find.text('INV-2026-000042'), findsOneWidget);
    expect(find.text('Due 15 Jan 2099'), findsOneWidget);
    expect(find.textContaining('t-unknown'), findsNothing, reason: 'an id is not a name');
  });

  test('an invoice due today is not overdue until the day is out', () {
    const r = Receivable(
      id: 'i',
      number: 'INV-2026-000050',
      issueDate: '2026-09-11',
      dueDate: '2026-09-25',
      currency: 'GBP',
      totalAmount: 10,
      outstanding: 10,
      taxTreatment: 'DOMESTIC',
    );

    expect(r.overdueOn(DateTime(2026, 9, 25, 23, 59)), isFalse);
    expect(r.overdueOn(DateTime(2026, 9, 26)), isTrue);
  });

  testWidgets('nothing owed says so, rather than showing an empty list', (tester) async {
    await _pump(tester, profile: _profile(), owed: const []);

    expect(find.byKey(const Key('owed-none')), findsOneWidget);
    expect(find.text('Every invoice is settled.'), findsOneWidget);
    expect(find.text('nothing'), findsOneWidget);
  });

  testWidgets('a chased invoice says what has been done and what is coming', (tester) async {
    // The point of the column: an operator who can see "suspended next" acts before a customer
    // telephones to say the till has stopped working.
    await _pump(
      tester,
      profile: _profile(),
      owed: _owed(),
      stages: [
        {
          'invoiceId': 'i1',
          'number': 'INV-2026-000041',
          'daysOverdue': 5,
          'stage': 'REMINDER_5',
          'nextStep': 'SUSPENDED',
        },
      ],
    );

    expect(find.textContaining('reminder 5'), findsOneWidget);
    expect(find.textContaining('suspended next'), findsOneWidget);
    expect(find.byKey(const Key('suspended-count')), findsNothing);
  });

  testWidgets('a suspended business is unmistakable and counted', (tester) async {
    await _pump(
      tester,
      profile: _profile(),
      owed: _owed(),
      stages: [
        {
          'invoiceId': 'i1',
          'number': 'INV-2026-000041',
          'daysOverdue': 20,
          'stage': 'SUSPENDED',
          'nextStep': 'UNCOLLECTIBLE',
        },
      ],
    );

    expect(find.byKey(const Key('suspended-count')), findsOneWidget);
    expect(find.text('1 suspended'), findsOneWidget);
    expect(find.byIcon(Icons.block), findsOneWidget);
    expect(find.textContaining('written off next'), findsOneWidget);
  });

  testWidgets('arrears still show when dunning says nothing about them', (tester) async {
    // Dunning is the platform's own and may be switched off. What is owed is a different question.
    await _pump(tester, profile: _profile(), owed: _owed(), stages: const []);

    expect(find.byKey(const Key('owed-INV-2026-000041')), findsOneWidget);
    expect(find.byKey(const Key('suspended-count')), findsNothing);
    expect(find.textContaining('reminder'), findsNothing);
  });
}
