import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/admin/sales_invoice_providers.dart';
import 'package:storeql_app/features/admin/sales_invoices_dialog.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// A customer's VAT registration: the country is chosen from the list of every
// country, and left blank it is the business's own, which pricing-svc fills in
// (SJ-D67) — no single country assumed for a customer's VAT number. A list and
// not a box of two letters, so "UK" cannot be typed, and the one refusal a
// country can still meet (COUNTRY_INVALID) reads "Choose a country from the
// list." truthfully here as everywhere else.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> posts = [];
  String? refusal;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    if (o.method == 'POST') {
      posts.add(o);
      if (refusal != null) return jsonResponse(refusal!, 400);
      return jsonResponse('{"data":{"customerId":"cust-1","vatRegistered":true}}');
    }
    return jsonResponse('{"data":{}}');
  }

  Map<String, dynamic> body(int i) {
    final d = posts[i].data;
    return (d is String ? jsonDecode(d) : d) as Map<String, dynamic>;
  }
}

Future<_Server> _open(
  WidgetTester tester, {
  String? tenantCountry,
  CustomerVatStatus? current,
}) async {
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  tester.view.physicalSize = const Size(900, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(FakeApiClient(dio)),
        if (tenantCountry != null)
          tenantInfoProvider.overrideWith(
            (ref) async => TenantInfo(
              id: 't-1',
              name: 'Test',
              status: 'ACTIVE',
              currency: '',
              country: tenantCountry,
            ),
          ),
      ],
      child: MaterialApp(
        home: Scaffold(
          body: VatRegistrationDialog(customerId: 'cust-1', current: current),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return server;
}

Finder get _country => find.byKey(const Key('customer-vat-country'));

Future<void> _choose(WidgetTester tester, String label) async {
  await tester.tap(_country);
  await tester.pumpAndSettle();
  await tester.scrollUntilVisible(find.text(label), 400,
      scrollable: find.byType(Scrollable).last);
  await tester.tap(find.text(label).last);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('the country is chosen from a list, never typed', (tester) async {
    await _open(tester, tenantCountry: 'IN');
    expect(
      find.descendant(
          of: _country, matching: find.byType(DropdownButtonFormField<String>)),
      findsOneWidget,
    );
    expect(find.descendant(of: _country, matching: find.byType(TextField)),
        findsNothing);
  });

  testWidgets("left blank it reads as the business's own country, by name",
      (tester) async {
    await _open(tester, tenantCountry: 'IN');
    expect(find.text("The business's own: India (IN)"), findsOneWidget);
  });

  testWidgets(
    'an unknown business country names none, never a fixed default',
    (tester) async {
      await _open(tester, tenantCountry: '');
      expect(find.text("The business's own"), findsOneWidget);
      expect(find.textContaining('(GB)'), findsNothing);
    },
  );

  testWidgets(
    'no override at all (tenant info not yet loaded) names none either',
    (tester) async {
      await _open(tester);
      expect(find.text("The business's own"), findsOneWidget);
    },
  );

  testWidgets(
      'a country chosen is sent as its code; blank sends none, so the service '
      "uses the business's own", (tester) async {
    final server = await _open(tester, tenantCountry: 'IN');
    await tester.enterText(
        find.byKey(const Key('customer-vat-number')), 'GE123456789');
    await _choose(tester, 'Georgia (GE)');
    await tester.tap(find.byKey(const Key('customer-vat-save')));
    await tester.pumpAndSettle();
    expect(server.body(0)['countryCode'], 'GE');
  });

  testWidgets('blank sends no country at all', (tester) async {
    final server = await _open(tester, tenantCountry: 'IN');
    await tester.enterText(
        find.byKey(const Key('customer-vat-number')), '29AAGCB7383J1Z4');
    await tester.tap(find.byKey(const Key('customer-vat-save')));
    await tester.pumpAndSettle();
    expect(server.body(0).containsKey('countryCode'), isFalse);
  });

  testWidgets(
      'a recorded country opens chosen, and can be put back to the business\'s '
      'own', (tester) async {
    final server = await _open(
      tester,
      tenantCountry: 'IN',
      current: CustomerVatStatus.fromJson(const {
        'customerId': 'cust-1',
        'vatRegistered': true,
        'reverseChargeEligible': false,
        'vatNumber': 'MN1234567',
        'countryCode': 'MN',
      }),
    );
    expect(find.text('Mongolia (MN)'), findsOneWidget);
    await tester.tap(_country);
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
        find.text("The business's own: India (IN)"), -400,
        scrollable: find.byType(Scrollable).last);
    await tester.tap(find.text("The business's own: India (IN)").last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('customer-vat-save')));
    await tester.pumpAndSettle();
    expect(server.body(0).containsKey('countryCode'), isFalse);
  });

  testWidgets(
      'a country the service refuses says to choose from the list, which is '
      'what the field is', (tester) async {
    final server = await _open(tester, tenantCountry: 'IN');
    server.refusal = '{"error":{"code":"COUNTRY_INVALID",'
        '"message":"country must be an ISO 3166-1 alpha-2 code such as DE or JP"}}';
    await tester.enterText(
        find.byKey(const Key('customer-vat-number')), 'GE123456789');
    await _choose(tester, 'Georgia (GE)');
    await tester.tap(find.byKey(const Key('customer-vat-save')));
    await tester.pumpAndSettle();
    expect(find.text('Choose a country from the list.'), findsOneWidget);
  });
}
