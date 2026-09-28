import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/offline/offline_queue.dart';
import 'package:storeql_app/core/storage/app_storage.dart';
import 'package:storeql_app/features/admin/customer_providers.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';
import 'package:storeql_app/features/pos/tender_screen.dart';

// ---------------------------------------------------------------------------
// Phone-at-the-till on the Tender screen: the field the cashier actually
// finishes at, rather than the snackbar-only block it used to be. A store's
// choice (Required / Optional / Don't ask) is read from `posTillPhoneProvider`,
// which these tests drive by overriding `posStoresProvider` with a store
// carrying the choice under test.
// ---------------------------------------------------------------------------

class _MemStorage implements AppStorage {
  final Map<String, String> data = {};

  @override
  Future<String?> read({required String key}) async => data[key];

  @override
  Future<void> write({required String key, required String? value}) async {
    if (value == null) {
      data.remove(key);
    } else {
      data[key] = value;
    }
  }

  @override
  Future<void> delete({required String key}) async => data.remove(key);

  @override
  Future<void> deleteAll({Set<String> keep = const {}}) async =>
      data.removeWhere((k, _) => !keep.contains(k));
}

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _NoopPosSessionNotifier extends PosSessionNotifier {
  _NoopPosSessionNotifier(super.ref);

  @override
  Future<void> restore() async {}
}

class _StubAuthNotifier extends AuthNotifier {
  @override
  Future<AuthState> build() async => const AuthUnauthenticated();
}

const _line = PosLine(
  variantId: 'v-1',
  sku: 'SKU-1',
  name: 'Product 1',
  qty: 2,
  unitPrice: 6.0,
  currency: 'GBP',
);

class _LoadedCart extends PosCartNotifier {
  _LoadedCart() {
    loadLines(const [_line]);
  }
}

/// Answers every POST /orders with one configurable outcome; everything else
/// (payments, the POS journal, the fiscal receipt poll) gets a bare success —
/// none of these tests need to reach that far, because the outcome below is
/// always a refusal, so `_complete` stops in its catch block.
class _Server implements HttpClientAdapter {
  (int, String, String) orderOutcome = (
    409,
    'ORDER_STORE_CLOSED',
    'Store is closed.',
  );
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.path.endsWith('/orders') && o.method == 'POST') {
      final (status, code, message) = orderOutcome;
      return ResponseBody.fromString(
        '{"data":null,"error":{"code":"$code","message":"$message"}}',
        status,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType]
        },
      );
    }
    return ResponseBody.fromString('{"data":{}}', 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }

  List<RequestOptions> get orderPosts => requests
      .where((r) => r.path.endsWith('/orders') && r.method == 'POST')
      .toList();
}

Future<_Server> _pumpTender(
  WidgetTester tester, {
  required String tillPhone,
  String walkInPhone = '',
}) async {
  tester.view.physicalSize = const Size(800, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;

  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      offlineQueueProvider.overrideWith((ref) =>
          OfflineQueueNotifier(ref, storage: _MemStorage(), autoSync: false)),
      posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
      authNotifierProvider.overrideWith(_StubAuthNotifier.new),
      posCartProvider.overrideWith((ref) => _LoadedCart()),
      posStoreProvider.overrideWith((ref) => 'store-1'),
      posStoresProvider.overrideWith((ref) async => [
            StoreInfo(
              id: 'store-1',
              name: 'High Street',
              code: 'HS',
              type: 'STORE',
              status: 'ACTIVE',
              tillPhone: tillPhone,
            ),
          ]),
      posWalkInPhoneProvider.overrideWith((ref) => walkInPhone),
    ],
    child: const MaterialApp(home: Scaffold(body: TenderScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

/// Stage a cash tender for the full balance (2 × £6 = £12), which settles the
/// sale and enables Complete Sale, without completing it.
Future<void> _stageCashTender(WidgetTester tester) async {
  await tester.tap(find.widgetWithText(OutlinedButton, 'Cash'));
  await tester.pumpAndSettle();
  await tester.tap(find.widgetWithText(FilledButton, 'Add'));
  await tester.pumpAndSettle();
}

Future<void> _tapComplete(WidgetTester tester) async {
  await tester.ensureVisible(find.widgetWithText(FilledButton, 'Complete Sale'));
  await tester.tap(find.widgetWithText(FilledButton, 'Complete Sale'));
  await tester.pumpAndSettle();
}

const _phoneFieldKey = Key('tender-phone-field');
const _blockedMessage = "Enter the customer's number, or attach the customer";

/// The phone field's own `errorText`, read off its decoration rather than
/// searched for as visible text — the hint (`maintainHintSize: true`) stays
/// in the tree at zero opacity, and phone-at-the-till deliberately gives the
/// Required hint and the `ORDER_CONTACT_PHONE_REQUIRED` refusal the same
/// words, so a text search would find both.
String? _phoneFieldError(WidgetTester tester) =>
    tester.widget<TextField>(find.byKey(_phoneFieldKey)).decoration?.errorText;

void main() {
  group('a Required store', () {
    testWidgets(
        'blocks Complete Sale with no number and no customer, at the field',
        (tester) async {
      final server = await _pumpTender(tester, tillPhone: 'REQUIRED');
      await _stageCashTender(tester);

      await _tapComplete(tester);

      expect(_phoneFieldError(tester), _blockedMessage);
      expect(server.orderPosts, isEmpty,
          reason: 'the sale never reached the server');
    });

    testWidgets('allows Complete Sale once a number is typed', (tester) async {
      final server = await _pumpTender(tester, tillPhone: 'REQUIRED');
      await _stageCashTender(tester);
      await _tapComplete(tester);
      expect(_phoneFieldError(tester), _blockedMessage);

      await tester.ensureVisible(find.byKey(_phoneFieldKey));
      await tester.enterText(find.byKey(_phoneFieldKey), '07700 900000');
      await tester.pumpAndSettle();
      expect(_phoneFieldError(tester), isNull,
          reason: 'typing clears the block');

      await _tapComplete(tester);

      expect(server.orderPosts, hasLength(1),
          reason: 'a number was given, so the sale went to the server');
    });

    testWidgets('allows Complete Sale once a customer is attached, phone blank',
        (tester) async {
      final server = await _pumpTender(tester, tillPhone: 'REQUIRED');
      await _stageCashTender(tester);
      await _tapComplete(tester);
      expect(server.orderPosts, isEmpty);

      final container =
          ProviderScope.containerOf(tester.element(find.byType(TenderScreen)));
      container.read(posCustomerProvider.notifier).state = const Customer(
        id: 'c-1',
        email: 'ann@example.com',
        firstName: 'Ann',
        lastName: 'Lee',
        status: 'ACTIVE',
      );
      await tester.pumpAndSettle();
      expect(find.byKey(_phoneFieldKey), findsNothing,
          reason: 'a customer is attached, as today');

      await _tapComplete(tester);

      expect(server.orderPosts, hasLength(1));
    });
  });

  testWidgets('an Optional store completes with the phone field left blank',
      (tester) async {
    final server = await _pumpTender(tester, tillPhone: 'OPTIONAL');
    await _stageCashTender(tester);

    await _tapComplete(tester);

    expect(_phoneFieldError(tester), isNull);
    expect(server.orderPosts, hasLength(1));
  });

  testWidgets("a Don't-ask store shows no phone field, and needs no check",
      (tester) async {
    final server = await _pumpTender(tester, tillPhone: 'OFF');
    expect(find.byKey(_phoneFieldKey), findsNothing);

    await _stageCashTender(tester);
    await _tapComplete(tester);

    expect(server.orderPosts, hasLength(1));
  });

  testWidgets("the field shows whatever was typed on the Sale tab", (tester) async {
    await _pumpTender(tester, tillPhone: 'REQUIRED', walkInPhone: '07700900000');

    final field = tester.widget<TextField>(find.byKey(_phoneFieldKey));
    expect(field.controller?.text, '07700900000');
  });

  group('label and hint follow the store\'s choice', () {
    testWidgets('Required', (tester) async {
      await _pumpTender(tester, tillPhone: 'REQUIRED');
      expect(find.text('Customer phone *'), findsOneWidget);
      expect(find.text('This store asks for a number on every sale'),
          findsOneWidget);
    });

    testWidgets('Optional', (tester) async {
      await _pumpTender(tester, tillPhone: 'OPTIONAL');
      expect(find.text('Customer phone (optional)'), findsOneWidget);
      expect(find.text('Leave blank if the customer prefers not to say'),
          findsOneWidget);
    });
  });

  group('server refusals appear at the field, the sale kept intact', () {
    testWidgets('ORDER_CONTACT_PHONE_REQUIRED', (tester) async {
      final server = await _pumpTender(tester,
          tillPhone: 'REQUIRED', walkInPhone: '07700900000');
      server.orderOutcome = (
        409,
        'ORDER_CONTACT_PHONE_REQUIRED',
        'A phone number or a customer is required.',
      );
      await _stageCashTender(tester);

      await _tapComplete(tester);

      expect(_phoneFieldError(tester), 'This store asks for a number on every sale');
      final container =
          ProviderScope.containerOf(tester.element(find.byType(TenderScreen)));
      expect(container.read(posCartProvider), isNotEmpty,
          reason: 'the sale is still on the till, not cleared');
    });

    testWidgets('ORDER_CONTACT_PHONE_INVALID under Required ends "check it"',
        (tester) async {
      final server = await _pumpTender(tester,
          tillPhone: 'REQUIRED', walkInPhone: 'not-a-phone');
      server.orderOutcome =
          (400, 'ORDER_CONTACT_PHONE_INVALID', 'Not a phone number.');
      await _stageCashTender(tester);

      await _tapComplete(tester);

      expect(_phoneFieldError(tester),
          "That isn't a phone number where this business trades — check it");
      final container =
          ProviderScope.containerOf(tester.element(find.byType(TenderScreen)));
      expect(container.read(posCartProvider), isNotEmpty);
    });

    testWidgets(
        'ORDER_CONTACT_PHONE_INVALID under Optional offers to leave it blank',
        (tester) async {
      final server = await _pumpTender(tester,
          tillPhone: 'OPTIONAL', walkInPhone: 'not-a-phone');
      server.orderOutcome =
          (400, 'ORDER_CONTACT_PHONE_INVALID', 'Not a phone number.');
      await _stageCashTender(tester);

      await _tapComplete(tester);

      expect(
        _phoneFieldError(tester),
        "That isn't a phone number where this business trades — check it, "
        'or leave it blank',
      );
    });
  });
}
