import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/admin/sales_invoices_dialog.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// A customer's VAT registration: the country field hints the business's own
// country (SJ-D67) — no single country assumed for a customer's VAT number,
// which the service parses for its own country's shape regardless.
// ---------------------------------------------------------------------------

Future<void> _open(WidgetTester tester, {String? tenantCountry}) async {
  final dio = Dio(BaseOptions(baseUrl: 'http://test'));
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
      child: const MaterialApp(
        home: Scaffold(body: VatRegistrationDialog(customerId: 'cust-1')),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Finder _countryFieldHint() => find.descendant(
  of: find.byKey(const Key('customer-vat-country')),
  matching: find.byType(TextField),
);

void main() {
  testWidgets('hints the business\'s own country', (tester) async {
    await _open(tester, tenantCountry: 'IN');
    expect(
      tester.widget<TextField>(_countryFieldHint()).decoration?.hintText,
      'IN',
    );
  });

  testWidgets(
    'an unknown business country hints nothing, never a fixed default',
    (tester) async {
      await _open(tester, tenantCountry: '');
      expect(
        tester.widget<TextField>(_countryFieldHint()).decoration?.hintText,
        isNull,
      );
    },
  );

  testWidgets(
    'no override at all (tenant info not yet loaded) hints nothing either',
    (tester) async {
      await _open(tester);
      expect(
        tester.widget<TextField>(_countryFieldHint()).decoration?.hintText,
        isNull,
      );
    },
  );
}
