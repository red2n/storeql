import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/customer_providers.dart';
import 'package:storeql_app/features/admin/loyalty_programme_dialog.dart';

import 'package:intl/intl.dart';

import '../../support/fake_api.dart' show RoleAuth;
// ---------------------------------------------------------------------------
// The loyalty programme dialog (13.x): the platform's default shown as such,
// the ladder edited and saved with its months and reason, a refusal shown by
// name, and the sweep run now saying what it did. The account model's lines
// (tier · next tier · multiplier; points about to expire) are checked too.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  bool refuseSave = false;

  /// The programme answered to a GET: the platform's default unless set.
  String programme = _default;

  @override
  void close({bool force = false}) {}

  static const _default =
      '{"tiers":[{"name":"BRONZE","threshold":0,"multiplier":1},{"name":"SILVER","threshold":1000,"multiplier":1},'
      '{"name":"GOLD","threshold":5000,"multiplier":1},{"name":"PLATINUM","threshold":20000,"multiplier":1}],"isDefault":true}';

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.path.endsWith('/admin/loyalty/programme') && o.method == 'GET') {
      return _json('{"data":$programme}', 200);
    }
    if (o.path.endsWith('/admin/loyalty/programme') && o.method == 'PUT') {
      if (refuseSave) {
        return _json(
            '{"code":"LOYALTY_TIERS_INVALID","status":400,"error":{"code":"LOYALTY_TIERS_INVALID","message":"thresholds must be ascending"}}',
            400);
      }
      final body = o.data is String ? jsonDecode(o.data as String) : o.data;
      final tiers = (body['tiers'] as List).length;
      // Answered as customer-svc answers: its BigDecimals are JSON numbers.
      final saved = [
        for (final t in body['tiers'] as List)
          {
            for (final e in (t as Map).entries)
              e.key: e.value is String && e.key != 'name' ? num.parse(e.value as String) : e.value,
          },
      ];
      return _json(
          '{"data":{"tiers":${jsonEncode(saved)},"expiryMonths":${body['expiryMonths']},'
          '"qualifyingMonths":${body['qualifyingMonths']},"reason":${jsonEncode(body['reason'])},'
          '"setAt":"2026-09-23T10:00:00Z","isDefault":false,"count":$tiers}}',
          200);
    }
    if (o.path.endsWith('/admin/loyalty/expiry/run') && o.method == 'POST') {
      return _json('{"data":{"customers":2,"points":35.0,"retiered":1}}', 200);
    }
    return _json('{"data":null}', 200);
  }

  static ResponseBody _json(String body, int status) => ResponseBody.fromString(body, status,
      headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
}

Future<_Server> _pump(WidgetTester tester,
    {bool refuseSave = false, RoleAuth? auth, String? programme}) async {
  tester.view.physicalSize = const Size(1200, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final server = _Server()..refuseSave = refuseSave;
  if (programme != null) server.programme = programme;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      key: UniqueKey(),
      overrides: [
        apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
        if (auth != null) authNotifierProvider.overrideWith(() => auth),
      ],
      child: MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (context) => Center(
              child: FilledButton(
                onPressed: () => showDialog<void>(
                    context: context, builder: (_) => const LoyaltyProgrammeDialog()),
                child: const Text('Open'),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('Open'));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  // The app loads intl's date data through flutter_localizations; a plain test loads it itself.
  setUpAll(initializeDateFormatting);

  testWidgets('the default programme is shown as such, with its four tiers', (tester) async {
    await _pump(tester);
    expect(find.textContaining("The platform's default"), findsOneWidget);
    expect(find.byKey(const Key('tier-name-3')), findsOneWidget);
    expect(find.byKey(const Key('tier-name-4')), findsNothing);
  });

  testWidgets('the ladder is edited and saved with its months and reason', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('tier-remove-3')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('tier-threshold-1')), '10');
    await tester.enterText(find.byKey(const Key('tier-multiplier-1')), '1.5');
    await tester.enterText(find.byKey(const Key('programme-expiry')), '12');
    await tester.enterText(find.byKey(const Key('programme-qualifying')), '12');
    await tester.enterText(find.byKey(const Key('programme-reason')), 'the autumn scheme');
    await tester.tap(find.byKey(const Key('programme-save')));
    await tester.pumpAndSettle();
    final put = server.requests.lastWhere((r) => r.method == 'PUT');
    final body = put.data is String ? jsonDecode(put.data as String) : put.data;
    expect(body['expiryMonths'], 12);
    expect(body['qualifyingMonths'], 12);
    expect(body['reason'], 'the autumn scheme');
    expect((body['tiers'] as List).length, 3);
    expect(body['tiers'][1]['name'], 'SILVER');
    // The plain decimals typed, as strings: JSON-B reads them exactly.
    expect(body['tiers'][1]['threshold'], '10');
    expect(body['tiers'][1]['multiplier'], '1.5');
    expect(find.textContaining('Programme saved: 3 tiers, points live 12 months.'), findsOneWidget);
  });

  // The programme is the whole business's: customer-svc answers a caller held to stores 403
  // BUSINESS_WIDE_ONLY on PUT /admin/loyalty/programme. Such a manager reads the ladder, is
  // offered no change to it and is told who makes one; a head-office manager is offered Save.
  //
  // The sweep is the whole business's too: it expires points and re-tiers every store's customers,
  // and customer-svc answers a caller held to stores 403 BUSINESS_WIDE_ONLY on
  // POST /admin/loyalty/expiry/run. Such a manager is not offered it.
  const programmeNote =
      'Only an owner or a head-office manager changes the loyalty programme or runs its sweep.';

  testWidgets('a manager held to stores reads the programme and is offered no change to it', (tester) async {
    final server = await _pump(tester, auth: RoleAuth('MANAGER', storeIds: const ['s-1']));
    expect(find.text(programmeNote), findsOneWidget);
    expect(tester.widget<TextField>(find.byKey(const Key('tier-name-0'))).controller!.text, 'BRONZE');
    for (final key in ['tier-name-0', 'tier-threshold-1', 'tier-multiplier-2', 'programme-expiry', 'programme-qualifying']) {
      expect(tester.widget<TextField>(find.byKey(Key(key))).readOnly, isTrue, reason: key);
    }
    expect(find.byKey(const Key('programme-save')), findsNothing);
    expect(find.byKey(const Key('programme-reason')), findsNothing);
    expect(find.byKey(const Key('tier-add')), findsNothing);
    expect(find.byKey(const Key('tier-remove-0')), findsNothing);
    expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);
  });

  testWidgets('a manager held to stores is not offered the sweep', (tester) async {
    final server = await _pump(tester, auth: RoleAuth('MANAGER', storeIds: const ['s-1']));
    expect(find.byKey(const Key('programme-sweep')), findsNothing);
    expect(find.text('Run the sweep now'), findsNothing);
    expect(server.requests.where((r) => r.path.endsWith('/expiry/run')), isEmpty);
  });

  testWidgets('a head-office manager is offered the sweep', (tester) async {
    final server = await _pump(tester, auth: RoleAuth('MANAGER'));
    await tester.tap(find.byKey(const Key('programme-sweep')));
    await tester.pumpAndSettle();
    expect(server.requests.where((r) => r.path.endsWith('/expiry/run')), hasLength(1));
  });

  testWidgets('a head-office manager is offered the change, and no note', (tester) async {
    await _pump(tester, auth: RoleAuth('MANAGER'));
    expect(find.text(programmeNote), findsNothing);
    expect(find.byKey(const Key('programme-save')), findsOneWidget);
    expect(tester.widget<TextField>(find.byKey(const Key('tier-name-0'))).readOnly, isFalse);
  });

  testWidgets('the reason is capped at 500 characters', (tester) async {
    await _pump(tester);
    await tester.enterText(find.byKey(const Key('programme-reason')), 'r' * 600);
    await tester.pump();
    final f = tester.widget<TextField>(find.byKey(const Key('programme-reason')));
    expect(f.maxLength, 500);
    expect(f.controller!.text.length, 500);
  });

  testWidgets('a refusal is shown by its reason, and the sweep says what it did', (tester) async {
    await _pump(tester, refuseSave: true);
    await tester.enterText(find.byKey(const Key('programme-reason')), 'x');
    await tester.tap(find.byKey(const Key('programme-save')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('programme-refusal')), findsOneWidget);
    expect(find.textContaining('thresholds must be ascending'), findsOneWidget);
    await tester.tap(find.byKey(const Key('programme-sweep')));
    await tester.pumpAndSettle();
    expect(find.textContaining('35 points expired for 2 customers; 1 re-tiered.'), findsOneWidget);
  });

  test('the account lines read: the tier, the way up, the benefit; and what is about to die', () {
    final a = LoyaltyAccount.fromJson({
      'pointsBalance': 27,
      'lifetimePoints': 27,
      'tier': 'SILVER',
      'qualifyingPoints': 27,
      'multiplier': 1.5,
      'nextTier': {'name': 'GOLD', 'threshold': 50, 'pointsToGo': 23},
      'expiringSoon': {'points': 20, 'on': '2026-10-23T11:00:00Z'},
      'expiryMonths': 12,
    });
    expect(a.tierLine, 'SILVER · GOLD in 23 pts · ×1.5');
    expect(a.expiringLine, '20 pts expire 23 Oct 2026');
    final top = LoyaltyAccount.fromJson({'pointsBalance': 0, 'tier': 'PLATINUM', 'multiplier': 1});
    expect(top.tierLine, 'PLATINUM');
    expect(top.expiringLine, '');
  });

  // A tier's threshold and multiplier are read the way the app's language
  // writes a number, with the shared amount reader, and a saved one is
  // written back the same way. One the dialog cannot read is refused under
  // its field and the programme waits: read as a default, Romanian's ×1,5 was
  // saved as ×1, and its 1.000 points as 1.
  group('a tier\'s figures are read as typed, or refused', () {
    Map<String, dynamic> put(_Server server) {
      final o = server.requests.lastWhere((r) => r.method == 'PUT');
      return (o.data is String ? jsonDecode(o.data as String) : o.data) as Map<String, dynamic>;
    }

    String? says(WidgetTester tester, String key) =>
        tester.widget<TextField>(find.byKey(Key(key))).decoration?.errorText;

    testWidgets('in Romanian, ×1,5 is saved as 1.5, never as 1', (tester) async {
      Intl.defaultLocale = 'ro';
      final server = await _pump(tester);
      await tester.enterText(find.byKey(const Key('tier-multiplier-1')), '1,5');
      await tester.enterText(find.byKey(const Key('programme-reason')), 'autumn');
      await tester.pump();
      expect(says(tester, 'tier-multiplier-1'), isNull);
      await tester.tap(find.byKey(const Key('programme-save')));
      await tester.pumpAndSettle();
      expect(put(server)['tiers'][1]['multiplier'], '1.5');
      expect(put(server)['tiers'][1]['threshold'], '1000');
    });

    testWidgets('in Romanian, 1.000 points is refused in words, never saved as 1', (tester) async {
      Intl.defaultLocale = 'ro';
      final server = await _pump(tester);
      await tester.enterText(find.byKey(const Key('tier-threshold-1')), '1.000');
      await tester.enterText(find.byKey(const Key('programme-reason')), 'autumn');
      await tester.pump();
      expect(says(tester, 'tier-threshold-1'),
          'Type the amount without thousands separators. Decimals go after a comma.');
      expect(tester.widget<FilledButton>(find.byKey(const Key('programme-save'))).onPressed, isNull);
      await tester.tap(find.byKey(const Key('programme-save')));
      await tester.pumpAndSettle();
      expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);
    });

    testWidgets('in Romanian, a saved ×1.25 reads 1,25 and is saved unchanged', (tester) async {
      Intl.defaultLocale = 'ro';
      final server = await _pump(tester,
          programme: '{"tiers":[{"name":"BRONZE","threshold":0,"multiplier":1},'
              '{"name":"SILVER","threshold":1500.5,"multiplier":1.25}],"isDefault":false,'
              '"setAt":"2026-09-01T10:00:00Z"}');
      expect(tester.widget<TextField>(find.byKey(const Key('tier-multiplier-1'))).controller!.text,
          '1,25');
      expect(tester.widget<TextField>(find.byKey(const Key('tier-threshold-1'))).controller!.text,
          '1500,5');
      expect(says(tester, 'tier-multiplier-1'), isNull);
      await tester.enterText(find.byKey(const Key('programme-reason')), 'no change');
      await tester.tap(find.byKey(const Key('programme-save')));
      await tester.pumpAndSettle();
      expect(put(server)['tiers'][1]['multiplier'], '1.25');
      expect(put(server)['tiers'][1]['threshold'], '1500.5');
    });

    // A mark alone is no multiplier, and is not blank either: refused, never
    // left out for customer-svc's own ×1.
    for (final (locale, mark) in [('ro', ','), ('en_GB', '.'), ('en', '.'), ('pl', '.'), ('ar', '\u066B')]) {
      testWidgets('in $locale, a multiplier of "$mark" is refused, never saved as ×1', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pump(tester);
        await tester.enterText(find.byKey(const Key('tier-multiplier-1')), mark);
        await tester.enterText(find.byKey(const Key('programme-reason')), 'autumn');
        await tester.pump();
        expect(says(tester, 'tier-multiplier-1'), 'Type the amount in digits.');
        expect(tester.widget<FilledButton>(find.byKey(const Key('programme-save'))).onPressed, isNull);
        await tester.tap(find.byKey(const Key('programme-save')));
        await tester.pumpAndSettle();
        expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);
      });
    }

    // How long points live, and how long earning counts, are whole months.
    // Blank is never and a lifetime; text that is not a number of months was
    // sent as -1 for customer-svc to refuse, and is now refused under its
    // field with nothing sent.
    for (final (field, typed, why) in [
      ('programme-expiry', '12.', 'Whole amounts only.'),
      ('programme-expiry', '.', 'Whole amounts only.'),
      ('programme-expiry', '-', 'Type the amount without a sign.'),
      ('programme-qualifying', '1,000', 'Type the amount without thousands separators.'),
      ('programme-qualifying', '+6', 'Type the amount without a sign.'),
      ('programme-qualifying', '0x10', 'Only digits.'),
    ]) {
      testWidgets('"$typed" months in $field is refused under it, never sent', (tester) async {
        Intl.defaultLocale = 'en_GB';
        final server = await _pump(tester);
        await tester.enterText(find.byKey(Key(field)), typed);
        await tester.enterText(find.byKey(const Key('programme-reason')), 'autumn');
        await tester.pump();
        expect(says(tester, field), why);
        expect(tester.widget<FilledButton>(find.byKey(const Key('programme-save'))).onPressed, isNull);
        await tester.tap(find.byKey(const Key('programme-save')));
        await tester.pumpAndSettle();
        expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);
      });
    }

    testWidgets('months typed are saved as typed; left blank they are left out', (tester) async {
      Intl.defaultLocale = 'en_GB';
      final server = await _pump(tester);
      await tester.enterText(find.byKey(const Key('programme-expiry')), '18');
      await tester.enterText(find.byKey(const Key('programme-qualifying')), ' ');
      await tester.enterText(find.byKey(const Key('programme-reason')), 'autumn');
      await tester.pump();
      await tester.tap(find.byKey(const Key('programme-save')));
      await tester.pumpAndSettle();
      expect(put(server)['expiryMonths'], 18);
      expect(put(server).containsKey('qualifyingMonths'), isFalse);
    });

    // Polish takes a point as the decimal too, but ×1.500 may be a group to
    // whoever typed it: refused, asking for the figure without grouping.
    testWidgets('in Polish, ×1.500 is refused as a possible thousands group', (tester) async {
      Intl.defaultLocale = 'pl';
      final server = await _pump(tester);
      await tester.enterText(find.byKey(const Key('tier-multiplier-1')), '1.500');
      await tester.enterText(find.byKey(const Key('programme-reason')), 'autumn');
      await tester.pump();
      expect(says(tester, 'tier-multiplier-1'),
          'A point may group thousands here. Type the figure without grouping, with any decimals after a comma.');
      await tester.tap(find.byKey(const Key('programme-save')));
      await tester.pumpAndSettle();
      expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);
    });
  });
}
