import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/features/pos/pos_age_check.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// The till's age check. The cases that matter most are the ones where the
// answer is "couldn't tell": each of them must keep the item out of the sale,
// because a till that treats not knowing as not restricted sells alcohol to a
// child while every screen says it asked.
// ---------------------------------------------------------------------------

class _Stub implements HttpClientAdapter {
  int status = 200;
  String body = '{"data":{"restricted":false}}';
  RequestOptions? last;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<List<int>>? requestStream, Future<void>? cancelFuture) async {
    last = options;
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Dio _dio(_Stub stub) =>
    Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = stub;

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  // The cut-off is shown as a date in the app's own locale, as the app does.
  setUpAll(initializeDateFormatting);

  group('checkAgeRestriction', () {
    test('an unrestricted item passes, and the store country is what is asked',
        () async {
      final stub = _Stub();
      final r = await checkAgeRestriction(_dio(stub), 'v-1', 'gb');

      expect(r, isA<AgeCheckNotRestricted>());
      expect(stub.last!.path, '/product-svc/catalog/variants/v-1/age-check');
      expect(stub.last!.queryParameters['country'], 'GB');
    });

    test('a restricted item comes back with its category, age and country',
        () async {
      final stub = _Stub()
        ..body = '{"data":{"restricted":true,"category":"ALCOHOL",'
            '"minimumAge":20,"country":"JP","tenantOverride":false}}';
      final r = await checkAgeRestriction(_dio(stub), 'v-1', 'JP');

      expect(r, isA<AgeCheckRestricted>());
      final check = r as AgeCheckRestricted;
      // The same bottle is 18 in the UK; the age belongs to the country.
      expect(check.minimumAge, 20);
      expect(check.category, 'ALCOHOL');
      expect(check.country, 'JP');
      expect(check.storePolicy, isFalse);
    });

    test('a store policy stricter than the law is labelled as policy', () async {
      final stub = _Stub()
        ..body = '{"data":{"restricted":true,"category":"ALCOHOL",'
            '"minimumAge":25,"country":"GB","tenantOverride":true}}';
      final r = await checkAgeRestriction(_dio(stub), 'v-1', 'GB')
          as AgeCheckRestricted;
      expect(r.storePolicy, isTrue);
    });

    test('an answer with no restricted flag is not taken as unrestricted',
        () async {
      // product-svc leaves null fields out of the JSON. A reply missing the
      // flag looks exactly like an unrestricted item that lost a field.
      final stub = _Stub()..body = '{"data":{"country":"GB","tenantOverride":false}}';
      expect(await checkAgeRestriction(_dio(stub), 'v-1', 'GB'),
          isA<AgeCheckBlocked>());
    });

    test('a restricted item with no rule for the country is blocked', () async {
      final stub = _Stub()
        ..status = 400
        ..body = '{"error":{"code":"PRODUCT_NO_AGE_RULE",'
            '"message":"No age rule for KNIVES in IN","details":[]}}';
      final r = await checkAgeRestriction(_dio(stub), 'v-1', 'IN');

      expect(r, isA<AgeCheckBlocked>());
      expect((r as AgeCheckBlocked).message, contains('no minimum age is set for IN'));
    });

    test('a store with no country is blocked, and nothing is guessed', () async {
      final stub = _Stub();
      expect(await checkAgeRestriction(_dio(stub), 'v-1', null),
          isA<AgeCheckBlocked>());
      expect(await checkAgeRestriction(_dio(stub), 'v-1', '-'),
          isA<AgeCheckBlocked>());
      // No request at all: there is no country it would be right to ask about.
      expect(stub.last, isNull);
    });

    test('a birth-date cut-off comes back as a date, and whose rule it is',
        () async {
      final stub = _Stub()
        ..body = '{"data":{"restricted":true,"category":"TOBACCO",'
            '"minimumAge":18,"country":"GB","tenantOverride":false,'
            '"bornBefore":"2009-01-01","bornBeforeTenantOverride":false}}';
      final r = await checkAgeRestriction(_dio(stub), 'v-1', 'GB')
          as AgeCheckRestricted;
      expect(r.bornBefore, DateTime(2009, 1, 1));
      expect(r.bornBeforeIso, '2009-01-01');
      expect(r.bornBeforeStorePolicy, isFalse);
    });

    test('a cut-off the till cannot read keeps the item out', () async {
      // Not "no cut-off": selling on the age alone would sell tobacco to the
      // very generation the rule exists for.
      for (final bad in ['"01/01/2009"', '"2009-02-30"', '20090101', '"soon"', '{}']) {
        final stub = _Stub()
          ..body = '{"data":{"restricted":true,"category":"TOBACCO",'
              '"minimumAge":18,"country":"GB","bornBefore":$bad}}';
        expect(await checkAgeRestriction(_dio(stub), 'v-1', 'GB'),
            isA<AgeCheckBlocked>(), reason: bad);
      }
    });

    test('a failed call is blocked, not waved through', () async {
      final stub = _Stub()
        ..status = 503
        ..body = '{"error":{"code":"UPSTREAM_UNAVAILABLE","details":[]}}';
      expect(await checkAgeRestriction(_dio(stub), 'v-1', 'GB'),
          isA<AgeCheckBlocked>());
    });
  });

  group('AgeVerificationDialog', () {
    const check = AgeCheckRestricted(
        category: 'ALCOHOL', minimumAge: 18, country: 'GB', storePolicy: false);

    // Shared with the tests: a refusal completes after the helper returns,
    // once a reason is chosen, and the test reads what the dialog decided.
    AgeDecision? result;

    Future<AgeDecision?> openAndChoose(WidgetTester tester, String button,
        {AgeCheckRestricted c = check}) async {
      result = null;
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              result = await showDialog<AgeDecision>(
                context: context,
                barrierDismissible: false,
                builder: (_) =>
                    AgeVerificationDialog(itemName: 'Rioja 75cl', check: c),
              );
            },
            child: const Text('open'),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
      await tester.tap(find.text(button));
      await tester.pumpAndSettle();
      return result;
    }

    testWidgets('says what to check, and against which law', (tester) async {
      await tester.pumpWidget(const MaterialApp(
          home: Scaffold(
              body: AgeVerificationDialog(itemName: 'Rioja 75cl', check: check))));
      expect(find.text('Rioja 75cl'), findsOneWidget);
      expect(find.text('Alcohol'), findsOneWidget);
      expect(find.text('The customer must be 18 or over.'), findsOneWidget);
      expect(find.text('Legal minimum in the United Kingdom.'), findsOneWidget);
    });

    testWidgets('a stricter store policy is shown as policy, not as law',
        (tester) async {
      await tester.pumpWidget(const MaterialApp(
          home: Scaffold(
              body: AgeVerificationDialog(
                  itemName: 'Rioja 75cl',
                  check: AgeCheckRestricted(
                      category: 'ALCOHOL',
                      minimumAge: 25,
                      country: 'GB',
                      storePolicy: true)))));
      expect(find.textContaining('Store policy in the United Kingdom'), findsOneWidget);
      expect(find.text('Checked — 25+'), findsOneWidget);
    });

    testWidgets('refusing asks why, and the answer is the record', (tester) async {
      // 'Refuse sale' alone decides nothing: the dialog stays open for a reason.
      expect(await openAndChoose(tester, 'Refuse sale'), isNull);
      expect(find.text('Why is the sale refused?'), findsOneWidget);
      await tester.tap(find.text('Under age'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Record refusal'));
      await tester.pumpAndSettle();
      expect(result, isA<AgeRefused>());
      expect((result as AgeRefused).reason, 'UNDER_AGE');
    });

    testWidgets('a cut-off says the date, whose rule it is, and offers its reason',
        (tester) async {
      final c = AgeCheckRestricted(
          category: 'TOBACCO',
          minimumAge: 18,
          country: 'GB',
          storePolicy: false,
          bornBefore: DateTime(2009, 1, 1));
      expect(await openAndChoose(tester, 'Refuse sale', c: c), isNull);
      expect(find.text('Born on or after the cut-off date'), findsOneWidget);
      // The date makes the dialog taller than a small screen; the cashier scrolls to the reason.
      await tester.ensureVisible(find.text('Born on or after the cut-off date'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Born on or after the cut-off date'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Record refusal'));
      await tester.pumpAndSettle();
      expect((result as AgeRefused).reason, 'BORN_AFTER_CUTOFF');
    });

    testWidgets('the cut-off is on the screen and on the button', (tester) async {
      await tester.pumpWidget(MaterialApp(
          home: Scaffold(
              body: AgeVerificationDialog(
                  itemName: 'Cigarettes 20',
                  check: AgeCheckRestricted(
                      category: 'TOBACCO',
                      minimumAge: 18,
                      country: 'GB',
                      storePolicy: false,
                      bornBefore: DateTime(2009, 1, 1))))));
      expect(find.text('And born before 1 Jan 2009.'), findsOneWidget);
      expect(find.textContaining('can never be sold this'), findsOneWidget);
      expect(find.text('Checked — 18+, born before 1 Jan 2009'), findsOneWidget);
    });

    testWidgets('without a cut-off the date reason is not offered', (tester) async {
      expect(await openAndChoose(tester, 'Refuse sale'), isNull);
      expect(find.text('Born on or after the cut-off date'), findsNothing);
    });

    testWidgets('confirming the check lets it in', (tester) async {
      expect(await openAndChoose(tester, 'Checked — 18+'), isA<AgePassed>());
    });
  });

  group('one check per sale', () {
    test('clearing the cart clears the check — the next sale is the next customer',
        () {
      final cart = PosCartNotifier()..ageVerifiedUpTo = 18;
      cart.clear();
      expect(cart.ageVerifiedUpTo, 0);
    });

    test('an age does not cover a birth-date cut-off; an earlier date covers a later one',
        () {
      final cart = PosCartNotifier()..recordAgePass(18, null);
      expect(cart.coversAgeCheck(18, null), isTrue);
      expect(cart.coversAgeCheck(18, DateTime(2009, 1, 1)), isFalse);
      cart.recordAgePass(18, DateTime(2009, 1, 1));
      expect(cart.coversAgeCheck(18, DateTime(2009, 1, 1)), isTrue);
      // Shown to be born before 2009 is also born before 2010, not before 2008.
      expect(cart.coversAgeCheck(18, DateTime(2010, 1, 1)), isTrue);
      expect(cart.coversAgeCheck(18, DateTime(2008, 6, 1)), isFalse);
      expect(cart.coversAgeCheck(21, DateTime(2009, 1, 1)), isFalse);
      // A later pass never loosens what was shown.
      cart.recordAgePass(18, DateTime(2010, 1, 1));
      expect(cart.verifiedBornBefore, DateTime(2009, 1, 1));
      cart.clear();
      expect(cart.verifiedBornBefore, isNull);
    });

    test('resuming a parked sale asks again', () {
      final cart = PosCartNotifier()
        ..ageVerifiedUpTo = 18
        ..verifiedBornBefore = DateTime(2009, 1, 1);
      cart.loadLines(const []);
      expect(cart.ageVerifiedUpTo, 0);
      expect(cart.verifiedBornBefore, isNull);
    });
  });
}
