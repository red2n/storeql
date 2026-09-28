import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/customer_providers.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/pos/cart_screen.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';

// ---------------------------------------------------------------------------
// The Sale tab's customer bar (phone-at-the-till): the words it asks in match
// the store's Required / Optional / Don't-ask choice, Don't ask drops the
// field but keeps the button that attaches a customer, and a customer
// attached hides the field regardless of the choice — as it always did.
// ---------------------------------------------------------------------------

class _NoopPosSessionNotifier extends PosSessionNotifier {
  _NoopPosSessionNotifier(super.ref);

  @override
  Future<void> restore() async {}
}

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

/// Answers every request with empty data — these tests only read the header
/// the sale pane always shows, never scan or price anything.
class _Quiet implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    return ResponseBody.fromString('{"data":[]}', 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

const _phoneFieldKey = Key('pos-customer-phone-field');

Future<void> _pump(
  WidgetTester tester, {
  required String tillPhone,
  Customer? customer,
}) async {
  tester.view.physicalSize = const Size(700, 1000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = _Quiet();
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
      posStoreProvider.overrideWith((ref) => 'store-1'),
      posStoresProvider.overrideWith((ref) async => [
            StoreInfo(
              id: 'store-1',
              name: 'High Street',
              code: 'HS',
              type: 'STORE',
              status: 'ACTIVE',
              country: 'GB',
              tillPhone: tillPhone,
            ),
          ]),
      if (customer != null)
        posCustomerProvider.overrideWith((ref) => customer),
    ],
    child: const MaterialApp(home: Scaffold(body: PosCartScreen())),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('Required: the label and hint ask for a number on every sale',
      (tester) async {
    await _pump(tester, tillPhone: 'REQUIRED');
    expect(find.text('Customer phone *'), findsOneWidget);
    expect(find.text('This store asks for a number on every sale'),
        findsOneWidget);
  });

  testWidgets('Optional: the label and hint let the customer say no',
      (tester) async {
    await _pump(tester, tillPhone: 'OPTIONAL');
    expect(find.text('Customer phone (optional)'), findsOneWidget);
    expect(find.text('Leave blank if the customer prefers not to say'),
        findsOneWidget);
  });

  testWidgets("Don't ask: no phone field, the attach-customer button stays",
      (tester) async {
    await _pump(tester, tillPhone: 'OFF');
    expect(find.byKey(_phoneFieldKey), findsNothing);
    expect(find.widgetWithText(TextButton, 'Add'), findsOneWidget);
  });

  testWidgets('a customer attached hides the field even under Required',
      (tester) async {
    await _pump(
      tester,
      tillPhone: 'REQUIRED',
      customer: const Customer(
        id: 'c-1',
        email: 'ann@example.com',
        firstName: 'Ann',
        lastName: 'Lee',
        status: 'ACTIVE',
      ),
    );
    expect(find.byKey(_phoneFieldKey), findsNothing);
  });
}
