import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/spacing.dart';
import 'package:storeql_app/features/admin/billing_screen.dart';

import '../../support/fake_api.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// What a business pays the platform (21.9). The two things an owner needs to see
// before they cost money: that the subscription ends at the period end, and that
// an unchecked VAT number means VAT is being charged that need not be.
// ---------------------------------------------------------------------------

Map<String, dynamic> _subscription({
  String status = 'ACTIVE',
  bool cancelAtPeriodEnd = false,
  String? pendingPlanId,
  String? trialEnd,
  String? vatNumber = 'DE123456789',
  bool vatChecked = true,
  String vatCheckSource = 'VIES',
}) => {
      'subscription': {
        'id': 's1',
        'planId': 'p1',
        'planCode': 'STARTER',
        'planName': 'Starter',
        'status': status,
        'priceAmount': 29,
        'currency': 'GBP',
        'billingInterval': 'MONTH',
        'periodStart': '2026-09-01',
        'periodEnd': '2026-10-01',
        'trialEnd': trialEnd,
        'pendingPlanId': pendingPlanId,
        'cancelAtPeriodEnd': cancelAtPeriodEnd,
        'buyer': {
          'name': 'Weinhaus GmbH',
          'country': 'DE',
          'vatNumber': vatNumber,
          'vatChecked': vatChecked,
          'vatCheckSource': vatChecked ? vatCheckSource : null,
        },
        'billingEmail': 'owner@example.com',
      },
      'events': <Map<String, dynamic>>[],
    };

List<Map<String, dynamic>> _invoices() => [
      {
        'id': 'i1',
        'number': 'INV-2026-000041',
        'status': 'OPEN',
        'issueDate': '2026-09-01',
        'dueDate': '2026-09-15',
        'currency': 'GBP',
        'totalAmount': 34.80,
        'outstanding': 34.80,
        'taxTreatment': 'DOMESTIC',
      },
      {
        'id': 'i2',
        'number': 'INV-2026-000040',
        'status': 'PAID',
        'issueDate': '2026-08-01',
        'dueDate': '2026-08-15',
        'currency': 'GBP',
        'totalAmount': 40.8,
        'outstanding': 0,
        'taxTreatment': 'REVERSE_CHARGE',
      },
    ];

/// The screen asks for the subscription and the invoices separately, so the fake
/// answers by path rather than returning one body to everything.
class _Server implements HttpClientAdapter {
  final Map<String, dynamic>? subscription;
  final List<Map<String, dynamic>> invoices;

  _Server({required this.subscription, required this.invoices});

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    if (o.path.contains('/invoices')) {
      return jsonResponse(jsonEncode({'data': invoices}));
    }
    return jsonResponse(jsonEncode({'data': subscription ?? <String, dynamic>{}}));
  }
}

Future<void> _pump(
  WidgetTester tester, {
  Map<String, dynamic>? subscription,
  List<Map<String, dynamic>>? invoices,
  Size size = const Size(1200, 2400),
  double textScale = 1,
  ThemeData? theme,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = _Server(
      subscription: subscription,
      invoices: invoices ?? const [],
    );
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
      child: MaterialApp(
        theme: theme,
        builder: (context, child) => MediaQuery.withClampedTextScaling(
          minScaleFactor: textScale,
          maxScaleFactor: textScale,
          child: child!,
        ),
        home: const Scaffold(body: BillingScreen()),
      ),
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
  setUpAll(initializeDateFormatting);

  testWidgets('the subscription says what it costs and for which period', (tester) async {
    await _pump(tester, subscription: _subscription(), invoices: _invoices());

    expect(find.byKey(const Key('subscription')), findsOneWidget);
    expect(find.text('Starter'), findsOneWidget);
    // Its status in words, not the stored code.
    expect(find.text('Active'), findsOneWidget);
    expect(find.text('ACTIVE'), findsNothing);
    // Money, not a code and a raw number.
    expect(find.text('£29.00 per month'), findsOneWidget);
    // The period ends the day before the next begins: its last day is shown, not its exclusive end.
    expect(find.text('Billed 1 Sept 2026 to 30 Sept 2026'), findsOneWidget);
    expect(find.byKey(const Key('note-ending')), findsNothing);
  });

  testWidgets('every subscription status reads as words', (tester) async {
    for (final (code, words) in [
      ('TRIALING', 'Trial'),
      ('PAST_DUE', 'Past due'),
      ('SUSPENDED', 'Suspended'),
      ('CANCELLED', 'Cancelled'),
    ]) {
      await _pump(tester, subscription: _subscription(status: code), invoices: const []);
      expect(find.text(words), findsOneWidget, reason: code);
      expect(find.text(code), findsNothing, reason: code);
    }
  });

  testWidgets('on a phone the page sits 16 in from the edge', (tester) async {
    await _pump(tester, subscription: _subscription(), invoices: _invoices(), size: const Size(390, 1600));
    expect(tester.getTopLeft(find.byKey(const Key('subscription'))).dx, AppSpacing.lg);
    expect(tester.getTopRight(find.byKey(const Key('subscription'))).dx, 390 - AppSpacing.lg);
    expect(tester.takeException(), isNull);
  });

  testWidgets('where the platform\'s notices go is said, and its absence is a warning', (tester) async {
    await _pump(tester, subscription: _subscription(), invoices: _invoices());
    expect(find.text('Invoices and payment notices go to owner@example.com.'), findsOneWidget);

    final none = _subscription();
    (none['subscription'] as Map<String, dynamic>)['billingEmail'] = null;
    await _pump(tester, subscription: none, invoices: _invoices());
    expect(
      find.text('No billing email: the platform cannot tell you when an invoice is late.'),
      findsOneWidget,
    );
  });

  testWidgets('an unchecked VAT number says that VAT is being charged because of it', (tester) async {
    // The consequence is money, so it is said and not implied.
    await _pump(
      tester,
      subscription: _subscription(vatChecked: false),
      invoices: _invoices(),
    );

    expect(find.text('DE123456789 · not checked yet, so VAT is charged'), findsOneWidget);
  });

  testWidgets('a checked number says so, and names what checked it', (tester) async {
    await _pump(tester, subscription: _subscription(), invoices: _invoices());

    expect(find.text('DE123456789 · checked with VIES'), findsOneWidget);
  });

  testWidgets('a number checked by a person says so in words, not by its source code', (tester) async {
    await _pump(tester, subscription: _subscription(vatCheckSource: 'MANUAL'), invoices: _invoices());

    expect(find.text('DE123456789 · checked by hand'), findsOneWidget);
    expect(find.textContaining('MANUAL'), findsNothing);
  });

  testWidgets('no VAT number at all is not the same as an unchecked one', (tester) async {
    await _pump(
      tester,
      subscription: _subscription(vatNumber: null, vatChecked: false),
      invoices: _invoices(),
    );

    expect(find.text('No VAT number given'), findsOneWidget);
  });

  testWidgets('a subscription that ends at the period end warns before it does', (tester) async {
    await _pump(
      tester,
      subscription: _subscription(cancelAtPeriodEnd: true),
      invoices: _invoices(),
    );

    expect(find.byKey(const Key('note-ending')), findsOneWidget);
    expect(find.text('Ends on 1 Oct 2026.'), findsOneWidget);
  });

  testWidgets('a trial says when the first invoice comes', (tester) async {
    await _pump(
      tester,
      subscription: _subscription(status: 'TRIALING', trialEnd: '2026-09-20'),
      invoices: const [],
    );

    expect(find.byKey(const Key('note-trial')), findsOneWidget);
    expect(find.text('Free until 20 Sept 2026. The first invoice comes then.'), findsOneWidget);
    expect(find.text('None yet.'), findsOneWidget);
  });

  testWidgets('being behind is said plainly', (tester) async {
    await _pump(tester, subscription: _subscription(status: 'PAST_DUE'), invoices: _invoices());

    expect(find.byKey(const Key('note-behind')), findsOneWidget);
  });

  testWidgets('a waiting plan change says when it takes effect', (tester) async {
    await _pump(
      tester,
      subscription: _subscription(pendingPlanId: 'p2'),
      invoices: _invoices(),
    );

    expect(find.byKey(const Key('note-pending')), findsOneWidget);
    expect(find.text('A plan change takes effect on 1 Oct 2026.'), findsOneWidget);
  });

  testWidgets('an invoice shows what is owed, and a reverse charge says why there is no VAT', (tester) async {
    await _pump(tester, subscription: _subscription(), invoices: _invoices());

    expect(find.byKey(const Key('invoice-INV-2026-000041')), findsOneWidget);
    expect(find.text('£34.80'), findsOneWidget);
    expect(find.text('£34.80 owed'), findsOneWidget);
    expect(find.textContaining('Issued 1 Sept 2026 · due 15 Sept 2026'), findsOneWidget);
    // A settled invoice is not owed, and says its status instead, as a word.
    expect(find.text('£40.80'), findsOneWidget);
    expect(find.text('Paid'), findsOneWidget);
    expect(find.text('paid'), findsNothing);
    expect(find.textContaining('GBP'), findsNothing);
    expect(find.textContaining('Reverse charge — no VAT'), findsOneWidget);
  });

  testWidgets('a business that is not subscribed is told so, rather than shown an empty card', (tester) async {
    await _pump(tester, subscription: null, invoices: const []);

    expect(find.byKey(const Key('billing-none')), findsOneWidget);
    expect(find.byKey(const Key('subscription')), findsNothing);
  });

  testWidgets('the buyer is invoiced in a country named in words, not its code', (tester) async {
    await _pump(tester, subscription: _subscription(), invoices: _invoices());
    expect(find.text('Weinhaus GmbH · Germany'), findsOneWidget);
    expect(find.textContaining(' · DE'), findsNothing);
  });

  for (final (size, scale) in const [
    (Size(390, 3200), 1.3),
    (Size(390, 3200), 2.0),
    (Size(1280, 2400), 1.3),
    (Size(1280, 2400), 2.0),
  ]) {
    testWidgets(
        'at ${scale}x text, ${size.width.toInt()} wide, an owed and a paid invoice '
        'keep their amount and status without overflowing', (tester) async {
      await _pump(tester,
          subscription: _subscription(),
          invoices: _invoices(),
          size: size,
          textScale: scale);
      expect(tester.takeException(), isNull);
      expect(find.text('£34.80 owed'), findsOneWidget);
      expect(find.text('Paid'), findsOneWidget);
      // The invoice number keeps a readable width beside its amount.
      expect(tester.getSize(find.text('INV-2026-000041')).width, greaterThan(120));
    });
  }
}
