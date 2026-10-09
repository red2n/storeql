import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/offline/offline_queue.dart';
import 'package:storeql_app/core/storage/app_storage.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';
import 'package:storeql_app/features/pos/tender_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The amount a cashier types for a cash or card tender. Add is on only for an
// amount the till can send as money: a finite figure above zero with no more
// decimals than the sale's currency has (two for pounds, none for yen, three
// for dinars). Dart reads "Infinity" and "NaN" as numbers, and NaN is not <= 0,
// so both used to switch Add on: the balance read as paid and a real order was
// placed before payment-svc refused the amount.
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

class _NoopPosSessionNotifier extends PosSessionNotifier {
  _NoopPosSessionNotifier(super.ref);

  @override
  Future<void> restore() async {}
}

class _StubAuthNotifier extends AuthNotifier {
  @override
  Future<AuthState> build() async => const AuthUnauthenticated();
}

class _Cart extends PosCartNotifier {
  _Cart(PosLine line) {
    loadLines([line]);
  }
}

/// No card machine at the store, so a card tender is added without asking.
class _Server implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
          RequestOptions o, Stream<List<int>>? s, Future<void>? c) async =>
      jsonResponse('{"data":[]}');
}

/// A till selling [qty] of [unitPrice] in [currency].
Future<void> _pump(WidgetTester tester,
    {String currency = 'GBP', double unitPrice = 6.0, double qty = 2}) async {
  tester.view.physicalSize = const Size(800, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final line = PosLine(
    variantId: 'v-1',
    sku: 'SKU-1',
    name: 'Tea',
    qty: qty,
    unitPrice: unitPrice,
    currency: currency,
  );
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = _Server();
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      offlineQueueProvider.overrideWith((ref) =>
          OfflineQueueNotifier(ref, storage: _MemStorage(), autoSync: false)),
      posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
      authNotifierProvider.overrideWith(_StubAuthNotifier.new),
      posCartProvider.overrideWith((ref) => _Cart(line)),
      posStoreProvider.overrideWith((ref) => 'store-1'),
      posStoresProvider.overrideWith((ref) async => const [
            StoreInfo(
              id: 'store-1',
              name: 'High Street',
              code: 'HS',
              type: 'STORE',
              status: 'ACTIVE',
              tillPhone: 'OFF',
            ),
          ]),
    ],
    child: const MaterialApp(home: Scaffold(body: TenderScreen())),
  ));
  await tester.pumpAndSettle();
}

Future<void> _open(WidgetTester tester, String method) async {
  await tester.tap(find.widgetWithText(OutlinedButton, method));
  await tester.pumpAndSettle();
}

Finder get _amount => find.descendant(
    of: find.byType(AlertDialog), matching: find.byType(TextField));

bool _addOn(WidgetTester tester) =>
    tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Add'))
        .onPressed !=
    null;

Future<void> _type(WidgetTester tester, String text) async {
  await tester.enterText(_amount, text);
  await tester.pump();
}

void main() {
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);

  testWidgets('a figure that is not an amount never switches Add on',
      (tester) async {
    await _pump(tester);
    await _open(tester, 'Card');
    for (final text in [
      'Infinity',
      '-Infinity',
      'NaN',
      '0',
      '0.00',
      '-5',
      '12.345',
      '1e3',
      '12,50',
      'twelve',
      ' ',
      '',
      // Parses to Infinity: a figure too large to be money.
      '9' * 400,
    ]) {
      await _type(tester, text);
      expect(_addOn(tester), isFalse, reason: '"$text"');
    }
  });

  testWidgets(
      'a card on a machine the till does not drive asks for its receipt '
      'reference: required, and backing out adds nothing', (tester) async {
    await _pump(tester);
    await _open(tester, 'Card');
    await tester.tap(find.widgetWithText(FilledButton, 'Add'));
    await tester.pumpAndSettle();

    // Asked for, and the button that adds the payment is off until one is typed.
    expect(find.byKey(const Key('tender-machine-reference')), findsOneWidget);
    FilledButton ok() => tester.widget<FilledButton>(
        find.byKey(const Key('tender-machine-reference-ok')));
    expect(ok().onPressed, isNull);
    await tester.enterText(
        find.byKey(const Key('tender-machine-reference-field')), '   ');
    await tester.pump();
    expect(ok().onPressed, isNull, reason: 'spaces are not a reference');

    // Backing out adds no card payment, and the sale is still unpaid.
    await tester.tap(find.widgetWithText(TextButton, 'Cancel'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('tender-machine-reference')), findsNothing);
    expect(
        tester
            .widget<FilledButton>(
                find.widgetWithText(FilledButton, 'Complete Sale'))
            .onPressed,
        isNull,
        reason: 'no card payment was staged');

    // Typing one adds it.
    await _open(tester, 'Card');
    await tester.tap(find.widgetWithText(FilledButton, 'Add'));
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byKey(const Key('tender-machine-reference-field')), 'AUTH 4821');
    await tester.pump();
    expect(ok().onPressed, isNotNull);
    await tester.tap(find.byKey(const Key('tender-machine-reference-ok')));
    await tester.pumpAndSettle();
    expect(
        tester
            .widget<FilledButton>(
                find.widgetWithText(FilledButton, 'Complete Sale'))
            .onPressed,
        isNotNull);
  });

  testWidgets('a finite amount above zero in pence switches Add on',
      (tester) async {
    await _pump(tester);
    await _open(tester, 'Card');
    // It opens on the balance, written in the currency's minor units.
    expect(tester.widget<TextField>(_amount).controller!.text, '12.00');
    expect(_addOn(tester), isTrue);
    for (final text in ['12', '12.5', '12.50', '0.01', '.5']) {
      await _type(tester, text);
      expect(_addOn(tester), isTrue, reason: '"$text"');
    }
  });

  testWidgets('the field says why Add is off', (tester) async {
    await _pump(tester);
    await _open(tester, 'Card');
    await _type(tester, 'Infinity');
    expect(find.text('Enter the amount in figures.'), findsOneWidget);
    await _type(tester, '0');
    expect(find.text('Enter an amount above zero.'), findsOneWidget);
    await _type(tester, '12.345');
    expect(find.text('At most 2 decimal places.'), findsOneWidget);
    await _type(tester, '');
    expect(find.textContaining('Enter'), findsNothing,
        reason: 'an empty field is not yet wrong');
  });

  testWidgets('a "NaN" card tender is never added to the sale', (tester) async {
    await _pump(tester);
    await _open(tester, 'Card');
    await _type(tester, 'NaN');
    await tester.tap(find.widgetWithText(FilledButton, 'Add'),
        warnIfMissed: false);
    await tester.pumpAndSettle();
    // Still open: nothing was added.
    expect(find.byType(AlertDialog), findsOneWidget);
  });

  testWidgets('yen has no decimals: the balance opens whole, and a decimal is '
      'refused', (tester) async {
    await _pump(tester, currency: 'JPY', unitPrice: 600);
    await _open(tester, 'Card');
    expect(tester.widget<TextField>(_amount).controller!.text, '1200');
    expect(_addOn(tester), isTrue);
    await _type(tester, '1200.5');
    expect(_addOn(tester), isFalse);
    expect(find.text('This currency has no decimal places.'), findsOneWidget);
    await _type(tester, '1200');
    expect(_addOn(tester), isTrue);
  });

  testWidgets('dinars have three decimals, and no more', (tester) async {
    await _pump(tester, currency: 'KWD', unitPrice: 1.5);
    await _open(tester, 'Cash');
    expect(tester.widget<TextField>(_amount).controller!.text, '3.000');
    await _type(tester, '3.125');
    expect(_addOn(tester), isTrue);
    await _type(tester, '3.1255');
    expect(_addOn(tester), isFalse);
  });

  testWidgets('a Serbian dinar sale at 129.99 is tendered at 129.99: ISO 4217 '
      'gives the dinar two decimals, whatever intl\'s CLDR digits say',
      (tester) async {
    // intl's CLDR table says the dinar has no decimals; every service prices,
    // rounds and stores it at ISO's two. Read from CLDR the dialog opened at
    // "130" and refused "129.99", and a card sent the machine 130.00.
    await _pump(tester, currency: 'RSD', unitPrice: 129.99, qty: 1);
    await _open(tester, 'Card');
    expect(tester.widget<TextField>(_amount).controller!.text, '129.99');
    expect(_addOn(tester), isTrue);
    expect(find.text('This currency has no decimal places.'), findsNothing);
    await _type(tester, '129.999');
    expect(_addOn(tester), isFalse);
    expect(find.text('At most 2 decimal places.'), findsOneWidget);
    await _type(tester, '64.5');
    expect(_addOn(tester), isTrue, reason: 'a split part in dinars and paras');

    // The card is staged for exactly what was typed, not a whole dinar more.
    await _type(tester, '129.99');
    await tester.tap(find.widgetWithText(FilledButton, 'Add'));
    await tester.pumpAndSettle();
    // A card on a machine the till does not drive asks for its receipt reference.
    await tester.enterText(
        find.byKey(const Key('tender-machine-reference-field')), 'AUTH 4821');
    await tester.pump();
    await tester.tap(find.byKey(const Key('tender-machine-reference-ok')));
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsNothing);
    expect(find.textContaining('129.99'), findsWidgets);
    expect(find.textContaining('130'), findsNothing);
    expect(
        tester
            .widget<FilledButton>(
                find.widgetWithText(FilledButton, 'Complete Sale'))
            .onPressed,
        isNotNull,
        reason: 'the balance is cleared to the para');
  });

  testWidgets('an Iraqi dinar sale has three decimals, as ISO 4217 says',
      (tester) async {
    // CLDR says none; ISO, and the services, say three.
    await _pump(tester, currency: 'IQD', unitPrice: 1.25, qty: 1);
    await _open(tester, 'Card');
    expect(tester.widget<TextField>(_amount).controller!.text, '1.250');
    expect(_addOn(tester), isTrue);
    await _type(tester, '1.125');
    expect(_addOn(tester), isTrue);
    await _type(tester, '1.1255');
    expect(_addOn(tester), isFalse);
    expect(find.text('At most 3 decimal places.'), findsOneWidget);
  });

  testWidgets('cash: Exact fills the balance in the currency\'s own units',
      (tester) async {
    await _pump(tester, currency: 'JPY', unitPrice: 600);
    await _open(tester, 'Cash');
    await _type(tester, '5');
    await tester.tap(find.widgetWithText(ActionChip, 'Exact'));
    await tester.pump();
    expect(tester.widget<TextField>(_amount).controller!.text, '1200');
    expect(_addOn(tester), isTrue);
  });
}
