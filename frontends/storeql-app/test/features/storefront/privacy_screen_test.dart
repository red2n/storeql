import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/features/storefront/privacy_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';

// The shopper's half of the privacy work: the preference centre (PECR reg.22 —
// nothing is on until they turn it on) and the data download (UK GDPR art.20 —
// all of it or none of it, never a quiet half).

class _FakeAuth extends StorefrontAuthNotifier {
  _FakeAuth({bool signedIn = true}) {
    if (signedIn) {
      state = const StorefrontAuthState(
          accessToken: 'tok', refreshToken: 'ref', email: 'shopper@example.com');
    }
  }
}

/// Records what the screen asked the server, and answers with [responses]
/// keyed by "METHOD path".
class _Recorder {
  final List<RequestOptions> calls = [];
  final Map<String, dynamic> responses;
  final Map<String, int> statuses;

  /// Answers built from the request (a notice in the language asked for).
  final Map<String, dynamic Function(RequestOptions)> builders;

  /// How long the server takes over a request, by key.
  final Map<String, Duration> delays;

  /// A refusal's own code and message, by key — default to the export
  /// screen's own (EXPORT_ORDERS_UNAVAILABLE) so every existing test that
  /// only sets [statuses] keeps reading the same body it always has.
  final Map<String, String> errorCodes;
  final Map<String, String> errorMessages;

  _Recorder({
    this.responses = const {},
    this.statuses = const {},
    this.builders = const {},
    this.delays = const {},
    this.errorCodes = const {},
    this.errorMessages = const {},
  });

  Dio dio() {
    final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
    dio.interceptors.add(InterceptorsWrapper(onRequest: (opts, handler) async {
      calls.add(opts);
      final key = '${opts.method} ${opts.path}';
      final delay = delays[key];
      if (delay != null) await Future<void>.delayed(delay);
      final status = statuses[key] ?? 200;
      if (status >= 400) {
        handler.reject(DioException(
          requestOptions: opts,
          response: Response(
            requestOptions: opts,
            statusCode: status,
            data: {
              'error': {
                'code': errorCodes[key] ?? 'EXPORT_ORDERS_UNAVAILABLE',
                'message': errorMessages[key] ??
                    'order-svc could not be reached, so the export would be incomplete',
              }
            },
          ),
          type: DioExceptionType.badResponse,
        ));
        return;
      }
      final build = builders[key];
      handler.resolve(Response(
        requestOptions: opts,
        statusCode: status,
        data: {
          'data': build != null ? build(opts) : responses[key],
          'error': null,
          'meta': {},
        },
      ));
    }));
    return dio;
  }
}

Future<void> _pump(WidgetTester tester, _Recorder recorder,
    {bool signedIn = true, Size size = const Size(900, 1400)}) async {
  // The screen copies the export to the clipboard. Without a handler the
  // platform channel never replies, the button's spinner never stops, and
  // pumpAndSettle waits forever.
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(SystemChannels.platform, (call) async => null);
  addTearDown(() => TestDefaultBinaryMessengerBinding
      .instance.defaultBinaryMessenger
      .setMockMethodCallHandler(SystemChannels.platform, null));

  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);

  // This screen's dates ("13 May 2027", "30 Sept 2026") are about AppFormat
  // writing a date correctly, not about which locale the app defaults to
  // (core/l10n/app_locales_test.dart owns that) — pinned explicitly so it
  // stays true whatever the app's own fallback is.
  Intl.defaultLocale = 'en_GB';
  addTearDown(() => Intl.defaultLocale = null);

  await tester.pumpWidget(ProviderScope(
    overrides: [
      storefrontAuthProvider.overrideWith((ref) => _FakeAuth(signedIn: signedIn)),
      storefrontDioProvider.overrideWith((ref) => recorder.dio()),
    ],
    child: const MaterialApp(home: Scaffold(body: StorefrontPrivacyScreen())),
  ));
  await tester.pumpAndSettle();
}

void main() {
  setUpAll(initializeDateFormatting);
  testWidgets('every channel starts off — silence is not consent',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
    });
    await _pump(tester, recorder);

    final switches = tester.widgetList<SwitchListTile>(find.byType(SwitchListTile));
    expect(switches.length, 4);
    expect(switches.every((s) => s.value == false), isTrue);
  });

  testWidgets('a recorded consent shows as on', (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': [
        {'channel': 'EMAIL', 'granted': true, 'basis': 'CONSENT'},
      ],
    });
    await _pump(tester, recorder);

    final email = tester.widget<SwitchListTile>(find
        .ancestor(
            of: find.text('Email'), matching: find.byType(SwitchListTile))
        .first);
    expect(email.value, isTrue);
  });

  testWidgets('turning a channel on sends the wording it was agreed against',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
      'PUT /customer-svc/customers/me/marketing': <dynamic>[],
    });
    await _pump(tester, recorder);

    await tester.tap(find
        .ancestor(of: find.text('Email'), matching: find.byType(SwitchListTile))
        .first);
    await tester.pumpAndSettle();

    final put = recorder.calls
        .firstWhere((c) => c.method == 'PUT' && c.path.endsWith('/marketing'));
    final body = put.data as Map<String, dynamic>;
    expect((body['channels'] as List).first['channel'], 'EMAIL');
    expect((body['channels'] as List).first['granted'], isTrue);
    expect(body['notice'], isNotNull,
        reason: 'art.7(1): the shop must be able to show what was agreed to');
  });

  testWidgets('turning one off records no wording — an opt-out agrees to nothing',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': [
        {'channel': 'SMS', 'granted': true, 'basis': 'CONSENT'},
      ],
      'PUT /customer-svc/customers/me/marketing': <dynamic>[],
    });
    await _pump(tester, recorder);

    await tester.tap(find
        .ancestor(
            of: find.text('Text message'), matching: find.byType(SwitchListTile))
        .first);
    await tester.pumpAndSettle();

    final put = recorder.calls
        .firstWhere((c) => c.method == 'PUT' && c.path.endsWith('/marketing'));
    final body = put.data as Map<String, dynamic>;
    expect((body['channels'] as List).first['granted'], isFalse);
    expect(body['notice'], isNull);
  });

  testWidgets('the export is shown when it is complete', (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
      'GET /customer-svc/customers/me/export': {
        'exportedAt': '2026-09-12T09:00:00Z',
        'subject': {'loginId': 'abc', 'email': 'shopper@example.com'},
        'orders': [],
      },
    });
    await _pump(tester, recorder);

    await tester.tap(find.text('Download my data'));
    await tester.pumpAndSettle();

    expect(find.text('Your data'), findsWidgets);
    expect(find.textContaining('shopper@example.com'), findsOneWidget);
  });

  testWidgets('a failed export hands over nothing and says so', (tester) async {
    final recorder = _Recorder(
      responses: {'GET /customer-svc/customers/me/marketing': <dynamic>[]},
      statuses: {'GET /customer-svc/customers/me/export': 503},
    );
    await _pump(tester, recorder);

    await tester.tap(find.text('Download my data'));
    await tester.pumpAndSettle();

    expect(find.byType(AlertDialog), findsNothing);
    expect(find.textContaining('incomplete'), findsOneWidget);
  });

  testWidgets('a signed-out visitor is asked to sign in rather than shown switches',
      (tester) async {
    await _pump(tester, _Recorder(), signedIn: false);
    expect(find.byType(SwitchListTile), findsNothing);
    expect(find.text('Sign in'), findsOneWidget);
  });

  // ── 13.12: the notice, consent by purpose, a child, requests ────────────────

  testWidgets('the notice is shown in the language served, with the purposes and the contact',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
      'GET /customer-svc/customers/privacy/notice': {
  'requested': 'hi',
  'served': 'hi',
  'notice': {'language': 'hi', 'languageName': 'Hindi', 'version': 1, 'title': 'हम आपके डेटा का उपयोग कैसे करते हैं', 'body': 'हम आपके ऑर्डर रखते हैं।'},
  'languages': [
    {'code': 'en', 'name': 'English', 'published': true},
    {'code': 'hi', 'name': 'Hindi', 'published': true},
    {'code': 'ta', 'name': 'Tamil', 'published': false},
  ],
  'purposes': [
    {'code': 'LOYALTY', 'text': 'Loyalty: points on what you buy', 'tracking': false},
    {'code': 'MARKETING', 'text': 'Marketing: offers by email', 'tracking': true},
  ],
  'settings': {'grievanceName': 'Grievance Officer', 'grievanceEmail': 'privacy@example.in', 'responseDays': 15, 'hasGrievanceContact': true},
  'dpdp': false,
  'dpdpFrom': '2027-05-13',
},
      'GET /customer-svc/customers/me/privacy': {
  'consents': [
    {'purpose': 'LOYALTY', 'text': 'Loyalty: points on what you buy', 'tracking': false, 'granted': true},
    {'purpose': 'MARKETING', 'text': 'Marketing: offers by email', 'tracking': true, 'granted': false},
    {'purpose': 'PERSONALISATION', 'text': 'Personalisation: suggestions', 'tracking': true, 'granted': false},
    {'purpose': 'ANALYTICS', 'text': 'Analytics: how the shop is used', 'tracking': true, 'granted': false},
  ],
  'child': false,
  'canTrack': true,
  'guardian': null,
},
      'GET /customer-svc/customers/me/privacy/requests': <dynamic>[],
    });
    await _pump(tester, recorder);
    expect(find.text('हम आपके डेटा का उपयोग कैसे करते हैं'), findsOneWidget);
    // The body, the purposes and the contact are the notice's own: one tap opens them.
    await tester.tap(find.byKey(const Key('privacy-notice')));
    await tester.pumpAndSettle();
    expect(find.text('हम आपके ऑर्डर रखते हैं।'), findsOneWidget);
    expect(find.textContaining('Loyalty: points'), findsWidgets);
    expect(find.textContaining('privacy@example.in'), findsOneWidget);
    expect(find.textContaining('answered within 15 days'), findsOneWidget);
    expect(find.textContaining('binds this shop from 13 May 2027'), findsOneWidget);
    final languages = recorder.calls.firstWhere((c) => c.path.endsWith('/privacy/notice'));
    expect(languages.queryParameters['language'], 'en', reason: 'English until the shopper picks');
  });

  testWidgets('each purpose is its own switch, and withdrawing everything is one DELETE',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
      'GET /customer-svc/customers/privacy/notice': {
  'requested': 'hi',
  'served': 'hi',
  'notice': {'language': 'hi', 'languageName': 'Hindi', 'version': 1, 'title': 'हम आपके डेटा का उपयोग कैसे करते हैं', 'body': 'हम आपके ऑर्डर रखते हैं।'},
  'languages': [
    {'code': 'en', 'name': 'English', 'published': true},
    {'code': 'hi', 'name': 'Hindi', 'published': true},
    {'code': 'ta', 'name': 'Tamil', 'published': false},
  ],
  'purposes': [
    {'code': 'LOYALTY', 'text': 'Loyalty: points on what you buy', 'tracking': false},
    {'code': 'MARKETING', 'text': 'Marketing: offers by email', 'tracking': true},
  ],
  'settings': {'grievanceName': 'Grievance Officer', 'grievanceEmail': 'privacy@example.in', 'responseDays': 15, 'hasGrievanceContact': true},
  'dpdp': false,
  'dpdpFrom': '2027-05-13',
},
      'GET /customer-svc/customers/me/privacy': {
  'consents': [
    {'purpose': 'LOYALTY', 'text': 'Loyalty: points on what you buy', 'tracking': false, 'granted': true},
    {'purpose': 'MARKETING', 'text': 'Marketing: offers by email', 'tracking': true, 'granted': false},
    {'purpose': 'PERSONALISATION', 'text': 'Personalisation: suggestions', 'tracking': true, 'granted': false},
    {'purpose': 'ANALYTICS', 'text': 'Analytics: how the shop is used', 'tracking': true, 'granted': false},
  ],
  'child': false,
  'canTrack': true,
  'guardian': null,
},
      'GET /customer-svc/customers/me/privacy/requests': <dynamic>[],
      'PUT /customer-svc/customers/me/privacy/consents': <String, dynamic>{},
      'DELETE /customer-svc/customers/me/privacy/consents': <String, dynamic>{},
    });
    await _pump(tester, recorder);
    final analytics = tester.widget<SwitchListTile>(find.byKey(const Key('consent-ANALYTICS')));
    expect(analytics.value, isFalse);
    await tester.tap(find.byKey(const Key('consent-ANALYTICS')));
    await tester.pumpAndSettle();
    final put = recorder.calls.singleWhere((c) => c.method == 'PUT' && c.path.endsWith('/privacy/consents'));
    final choice = ((put.data as Map)['choices'] as List).single as Map;
    expect(choice['purpose'], 'ANALYTICS');
    expect(choice['granted'], isTrue);
    expect((put.data as Map)['language'], 'en', reason: 'the notice read is named with the consent');
    await tester.scrollUntilVisible(find.byKey(const Key('privacy-withdraw-all')), 200,
        scrollable: find.byType(Scrollable).first);
    await tester.tap(find.byKey(const Key('privacy-withdraw-all')));
    await tester.pumpAndSettle();
    expect(recorder.calls.where((c) => c.method == 'DELETE').single.path, endsWith('/privacy/consents'));
  });

  testWidgets('a child sees the tracking switches held off until a guardian consents',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
      'GET /customer-svc/customers/privacy/notice': {
  'requested': 'hi',
  'served': 'hi',
  'notice': {'language': 'hi', 'languageName': 'Hindi', 'version': 1, 'title': 'हम आपके डेटा का उपयोग कैसे करते हैं', 'body': 'हम आपके ऑर्डर रखते हैं।'},
  'languages': [
    {'code': 'en', 'name': 'English', 'published': true},
    {'code': 'hi', 'name': 'Hindi', 'published': true},
    {'code': 'ta', 'name': 'Tamil', 'published': false},
  ],
  'purposes': [
    {'code': 'LOYALTY', 'text': 'Loyalty: points on what you buy', 'tracking': false},
    {'code': 'MARKETING', 'text': 'Marketing: offers by email', 'tracking': true},
  ],
  'settings': {'grievanceName': 'Grievance Officer', 'grievanceEmail': 'privacy@example.in', 'responseDays': 15, 'hasGrievanceContact': true},
  'dpdp': false,
  'dpdpFrom': '2027-05-13',
},
      'GET /customer-svc/customers/me/privacy': {
  'consents': [
    {'purpose': 'LOYALTY', 'text': 'Loyalty: points on what you buy', 'tracking': false, 'granted': true},
    {'purpose': 'MARKETING', 'text': 'Marketing: offers by email', 'tracking': true, 'granted': false},
    {'purpose': 'PERSONALISATION', 'text': 'Personalisation: suggestions', 'tracking': true, 'granted': false},
    {'purpose': 'ANALYTICS', 'text': 'Analytics: how the shop is used', 'tracking': true, 'granted': false},
  ],
  'child': true,
  'canTrack': false,
  'guardian': null,
},
      'GET /customer-svc/customers/me/privacy/requests': <dynamic>[],
    });
    await _pump(tester, recorder);
    expect(find.byKey(const Key('privacy-child')), findsOneWidget);
    expect(tester.widget<SwitchListTile>(find.byKey(const Key('consent-MARKETING'))).onChanged, isNull);
    expect(tester.widget<SwitchListTile>(find.byKey(const Key('consent-LOYALTY'))).onChanged, isNotNull,
        reason: 'loyalty tracks nobody');
  });

  testWidgets('asking for a right posts the kind, and a nomination names somebody',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
      'GET /customer-svc/customers/privacy/notice': {
  'requested': 'hi',
  'served': 'hi',
  'notice': {'language': 'hi', 'languageName': 'Hindi', 'version': 1, 'title': 'हम आपके डेटा का उपयोग कैसे करते हैं', 'body': 'हम आपके ऑर्डर रखते हैं।'},
  'languages': [
    {'code': 'en', 'name': 'English', 'published': true},
    {'code': 'hi', 'name': 'Hindi', 'published': true},
    {'code': 'ta', 'name': 'Tamil', 'published': false},
  ],
  'purposes': [
    {'code': 'LOYALTY', 'text': 'Loyalty: points on what you buy', 'tracking': false},
    {'code': 'MARKETING', 'text': 'Marketing: offers by email', 'tracking': true},
  ],
  'settings': {'grievanceName': 'Grievance Officer', 'grievanceEmail': 'privacy@example.in', 'responseDays': 15, 'hasGrievanceContact': true},
  'dpdp': false,
  'dpdpFrom': '2027-05-13',
},
      'GET /customer-svc/customers/me/privacy': {
  'consents': [
    {'purpose': 'LOYALTY', 'text': 'Loyalty: points on what you buy', 'tracking': false, 'granted': true},
    {'purpose': 'MARKETING', 'text': 'Marketing: offers by email', 'tracking': true, 'granted': false},
    {'purpose': 'PERSONALISATION', 'text': 'Personalisation: suggestions', 'tracking': true, 'granted': false},
    {'purpose': 'ANALYTICS', 'text': 'Analytics: how the shop is used', 'tracking': true, 'granted': false},
  ],
  'child': false,
  'canTrack': true,
  'guardian': null,
},
      'GET /customer-svc/customers/me/privacy/requests': [
        {'id': 'r-1', 'kind': 'GRIEVANCE', 'dueOn': '2026-09-30', 'status': 'OPEN', 'overdue': false},
      ],
      'POST /customer-svc/customers/me/privacy/requests': <String, dynamic>{'id': 'r-2'},
    });
    await _pump(tester, recorder);
    // The page is a lazy list: the requests sit below the fold until scrolled to the end.
    await tester.drag(find.byType(Scrollable).first, const Offset(0, -4000));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('privacy-ask')));
    await tester.pumpAndSettle();
    expect(find.text('Raise a grievance'), findsOneWidget);
    expect(find.text('Due by 30 Sept 2026'), findsOneWidget);
    await tester.tap(find.byKey(const Key('privacy-ask')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('request-kind')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Nominate someone to act for me').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('request-nominee')), 'Arun');
    await tester.tap(find.byKey(const Key('request-send')));
    await tester.pumpAndSettle();
    final post = recorder.calls.singleWhere((c) => c.method == 'POST');
    expect(post.path, endsWith('/customers/me/privacy/requests'));
    expect((post.data as Map)['kind'], 'NOMINATION');
    expect((post.data as Map)['nomineeName'], 'Arun');
  });

  // ── The design system's Storefront_Privacy card: length, marketing twice, case, width ──

  testWidgets(
      'on a phone the switches come first and the full notice opens on a tap',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
      'GET /customer-svc/customers/privacy/notice': _view(body: _longBody),
      'GET /customer-svc/customers/me/privacy': _mine(),
      'GET /customer-svc/customers/me/privacy/requests': <dynamic>[],
    });
    await _pump(tester, recorder, size: const Size(390, 844));
    expect(find.text(_longBody), findsNothing, reason: 'the body waits behind its title');
    expect(find.text('How we use your data'), findsOneWidget);
    // The first switch is on the first screen, without a scroll.
    expect(tester.getRect(find.byKey(const Key('consent-LOYALTY'))).top, lessThan(844));

    await tester.tap(find.byKey(const Key('privacy-notice')));
    await tester.pumpAndSettle();
    expect(find.text(_longBody), findsOneWidget);
    expect(find.textContaining('privacy@example.in'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
      'the marketing channels sit under the Marketing purpose, and wait for it',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
      'GET /customer-svc/customers/privacy/notice': _view(),
      'GET /customer-svc/customers/me/privacy': _mine(),
      'GET /customer-svc/customers/me/privacy/requests': <dynamic>[],
    });
    await _pump(tester, recorder);
    // Asked once: no separate Marketing section with its own four switches.
    expect(find.byKey(const Key('marketing-section')), findsNothing);
    final purpose = tester.getRect(find.byKey(const Key('consent-MARKETING')));
    final next = tester.getRect(find.byKey(const Key('consent-PERSONALISATION')));
    for (final channel in const ['EMAIL', 'SMS', 'PHONE', 'POST']) {
      final tile = find.byKey(Key('marketing-$channel'));
      final rect = tester.getRect(tile);
      expect(rect.top, greaterThanOrEqualTo(purpose.bottom), reason: '$channel under Marketing');
      expect(rect.bottom, lessThanOrEqualTo(next.top), reason: '$channel before the next purpose');
      // Indented: a channel belongs to the purpose above it.
      final title = tester.getRect(find.descendant(of: tile, matching: find.byType(Text)).first);
      final purposeTitle = tester.getRect(find.text('Marketing'));
      expect(title.left, greaterThan(purposeTitle.left));
      // With Marketing off, a channel cannot be turned on.
      expect(tester.widget<SwitchListTile>(tile).onChanged, isNull);
    }
    expect(find.text('Turn on Marketing to choose how you hear from this shop.'), findsOneWidget);
  });

  testWidgets('with Marketing on, a channel turns on with the wording it was agreed against',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
      'GET /customer-svc/customers/privacy/notice': _view(),
      'GET /customer-svc/customers/me/privacy': _mine(marketing: true),
      'GET /customer-svc/customers/me/privacy/requests': <dynamic>[],
      'PUT /customer-svc/customers/me/marketing': <dynamic>[],
    });
    await _pump(tester, recorder);
    await tester.tap(find.byKey(const Key('marketing-EMAIL')));
    await tester.pumpAndSettle();
    final put = recorder.calls.singleWhere((c) => c.method == 'PUT');
    expect(put.path, endsWith('/customers/me/marketing'));
    final body = put.data as Map<String, dynamic>;
    expect(body['channels'], [
      {'channel': 'EMAIL', 'granted': true}
    ]);
    expect(body['notice'], isNotNull);
  });

  testWidgets(
      'turning Marketing off turns its channels off too, so one is never on without the other',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': [
        {'channel': 'EMAIL', 'granted': true, 'basis': 'CONSENT'},
        {'channel': 'SMS', 'granted': true, 'basis': 'CONSENT'},
        {'channel': 'POST', 'granted': false, 'basis': 'NONE'},
      ],
      'GET /customer-svc/customers/privacy/notice': _view(),
      'GET /customer-svc/customers/me/privacy': _mine(marketing: true),
      'GET /customer-svc/customers/me/privacy/requests': <dynamic>[],
      'PUT /customer-svc/customers/me/privacy/consents': <String, dynamic>{},
      'PUT /customer-svc/customers/me/marketing': <dynamic>[],
    });
    await _pump(tester, recorder);
    await tester.tap(find.byKey(const Key('consent-MARKETING')));
    await tester.pumpAndSettle();
    final purpose = recorder.calls
        .singleWhere((c) => c.method == 'PUT' && c.path.endsWith('/privacy/consents'));
    expect(((purpose.data as Map)['choices'] as List).single,
        {'purpose': 'MARKETING', 'granted': false});
    final channels =
        recorder.calls.singleWhere((c) => c.method == 'PUT' && c.path.endsWith('/marketing'));
    expect((channels.data as Map)['channels'], [
      {'channel': 'EMAIL', 'granted': false},
      {'channel': 'SMS', 'granted': false},
    ]);
    expect((channels.data as Map)['notice'], isNull, reason: 'an opt-out agrees to nothing');
  });

  testWidgets('withdrawing every consent withdraws the channels with them', (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': [
        {'channel': 'PHONE', 'granted': true, 'basis': 'CONSENT'},
      ],
      'GET /customer-svc/customers/privacy/notice': _view(),
      'GET /customer-svc/customers/me/privacy': _mine(marketing: true),
      'GET /customer-svc/customers/me/privacy/requests': <dynamic>[],
      'DELETE /customer-svc/customers/me/privacy/consents': <String, dynamic>{},
      'PUT /customer-svc/customers/me/marketing': <dynamic>[],
    });
    await _pump(tester, recorder);
    await tester.scrollUntilVisible(find.byKey(const Key('privacy-withdraw-all')), 200,
        scrollable: find.byType(Scrollable).first);
    await tester.tap(find.byKey(const Key('privacy-withdraw-all')));
    await tester.pumpAndSettle();
    expect(recorder.calls.where((c) => c.method == 'DELETE').single.path,
        endsWith('/privacy/consents'));
    final channels =
        recorder.calls.singleWhere((c) => c.method == 'PUT' && c.path.endsWith('/marketing'));
    expect((channels.data as Map)['channels'], [
      {'channel': 'PHONE', 'granted': false},
    ]);
  });

  testWidgets('a channel that is on can always be turned off, even with Marketing off',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': [
        {'channel': 'SMS', 'granted': true, 'basis': 'CONSENT'},
      ],
      'GET /customer-svc/customers/privacy/notice': _view(),
      'GET /customer-svc/customers/me/privacy': _mine(),
      'GET /customer-svc/customers/me/privacy/requests': <dynamic>[],
    });
    await _pump(tester, recorder);
    expect(tester.widget<SwitchListTile>(find.byKey(const Key('marketing-SMS'))).onChanged,
        isNotNull);
    expect(tester.widget<SwitchListTile>(find.byKey(const Key('marketing-EMAIL'))).onChanged,
        isNull);
  });

  testWidgets(
      'a channel left on while Marketing is off is named, with one tap to end it',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': [
        {'channel': 'EMAIL', 'granted': true, 'basis': 'CONSENT'},
      ],
      'GET /customer-svc/customers/privacy/notice': _view(),
      'GET /customer-svc/customers/me/privacy': _mine(),
      'GET /customer-svc/customers/me/privacy/requests': <dynamic>[],
      'PUT /customer-svc/customers/me/marketing': <String, dynamic>{},
    });
    await _pump(tester, recorder);
    expect(
        find.text('Email is still on from before Marketing was asked. Turn it '
            'off, or turn on Marketing to keep it.'),
        findsOneWidget);
    // Not the hint that says Marketing must be on first, beside a channel that is on.
    expect(find.text('Turn on Marketing to choose how you hear from this shop.'),
        findsNothing);

    final off = find.byKey(const Key('marketing-stale-off'));
    await tester.ensureVisible(off);
    await tester.tap(off);
    await tester.pumpAndSettle();
    final put = recorder.calls
        .singleWhere((c) => c.method == 'PUT' && c.path.endsWith('/marketing'));
    expect((put.data as Map)['channels'], [
      {'channel': 'EMAIL', 'granted': false},
    ]);
    expect((put.data as Map)['notice'], isNull);
  });

  testWidgets(
      'a channel waiting on Marketing says so beside it, in the same words a refusal would use',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
      'GET /customer-svc/customers/privacy/notice': _view(),
      'GET /customer-svc/customers/me/privacy': _mine(),
      'GET /customer-svc/customers/me/privacy/requests': <dynamic>[],
    });
    await _pump(tester, recorder);
    for (final channel in const ['EMAIL', 'SMS', 'PHONE', 'POST']) {
      expect(
        find.descendant(
            of: find.byKey(Key('marketing-$channel')),
            matching: find.text('Switch on Marketing first')),
        findsOneWidget,
        reason: '$channel says why it cannot be switched on yet',
      );
    }
  });

  testWidgets(
      "the hint stays at full contrast, never the switch's own disabled dimming",
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
      'GET /customer-svc/customers/privacy/notice': _view(),
      'GET /customer-svc/customers/me/privacy': _mine(),
      'GET /customer-svc/customers/me/privacy/requests': <dynamic>[],
    });
    await _pump(tester, recorder);
    final theme =
        Theme.of(tester.element(find.byKey(const Key('marketing-EMAIL'))));
    final hint = tester.widget<Text>(
        find.byKey(const Key('marketing-channel-hint')).first);
    expect(hint.style?.color, theme.colorScheme.onSurfaceVariant);
    expect(hint.style?.color, isNot(theme.disabledColor));
  });

  testWidgets(
      '409 MARKETING_PURPOSE_NOT_GRANTED is worded the same as the disabled switch\'s own hint',
      (tester) async {
    final recorder = _Recorder(
      responses: {
        'GET /customer-svc/customers/me/marketing': <dynamic>[],
        'GET /customer-svc/customers/privacy/notice': _view(),
        // Marketing reads as granted here, so the switch is not disabled
        // client-side — the refusal below is the server's alone, a race with
        // another tab or device that withdrew it a moment before this PUT.
        'GET /customer-svc/customers/me/privacy': _mine(marketing: true),
        'GET /customer-svc/customers/me/privacy/requests': <dynamic>[],
      },
      statuses: {'PUT /customer-svc/customers/me/marketing': 409},
      errorCodes: {'PUT /customer-svc/customers/me/marketing': 'MARKETING_PURPOSE_NOT_GRANTED'},
      errorMessages: {
        'PUT /customer-svc/customers/me/marketing':
            'the person\'s MARKETING purpose stands withdrawn',
      },
    );
    await _pump(tester, recorder);
    expect(tester.widget<SwitchListTile>(find.byKey(const Key('marketing-EMAIL'))).onChanged,
        isNotNull);

    await tester.tap(find.byKey(const Key('marketing-EMAIL')));
    await tester.pumpAndSettle();
    expect(find.text('Switch on Marketing first'), findsOneWidget, reason: 'said by the SnackBar');
    expect(find.textContaining('MARKETING_PURPOSE_NOT_GRANTED'), findsNothing);
    expect(find.textContaining('purpose stands withdrawn'), findsNothing);
  });

  testWidgets(
      'choosing another language keeps an open notice open, in that language, '
      'however long the server takes', (tester) async {
    Map<String, dynamic> notice(RequestOptions o) {
      final language = o.queryParameters['language'] as String? ?? 'en';
      return {
        ..._view(),
        'requested': language,
        'served': language,
        'notice': {
          'language': language,
          'languageName': language == 'hi' ? 'Hindi' : 'English',
          'version': 2,
          'title': 'TITLE-$language',
          'body': 'BODY-$language',
        },
        'languages': [
          {'code': 'en', 'name': 'English', 'published': true},
          {'code': 'hi', 'name': 'Hindi', 'published': true},
        ],
      };
    }

    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
      'GET /customer-svc/customers/me/privacy': _mine(),
      'GET /customer-svc/customers/me/privacy/requests': <dynamic>[],
    }, builders: {
      'GET /customer-svc/customers/privacy/notice': notice,
    }, delays: {
      'GET /customer-svc/customers/privacy/notice':
          const Duration(milliseconds: 300),
    });
    await _pump(tester, recorder);
    await tester.tap(find.byKey(const Key('privacy-notice')));
    await tester.pumpAndSettle();
    expect(find.text('BODY-en'), findsOneWidget);

    await tester.tap(find.byKey(const Key('privacy-language')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Hindi').last);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 150));
    // The card stays while the Hindi notice loads.
    expect(find.byKey(const Key('privacy-notice')), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pumpAndSettle();
    expect(find.text('BODY-hi'), findsOneWidget);
  });

  testWidgets('a purpose reads as a name and a sentence that starts with a capital',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
      'GET /customer-svc/customers/privacy/notice': _view(),
      'GET /customer-svc/customers/me/privacy': _mine(),
      'GET /customer-svc/customers/me/privacy/requests': <dynamic>[],
    });
    await _pump(tester, recorder);
    final loyalty = find.byKey(const Key('consent-LOYALTY'));
    expect(find.descendant(of: loyalty, matching: find.text('Loyalty')), findsOneWidget);
    expect(find.descendant(of: loyalty, matching: find.text('Points on what you buy')),
        findsOneWidget);
    expect(find.text('points on what you buy'), findsNothing);
    expect(find.text('Offers by email'), findsOneWidget);
  });

  testWidgets('on a desktop the page keeps to a reading measure, in the middle', (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
      'GET /customer-svc/customers/privacy/notice': _view(body: _longBody),
      'GET /customer-svc/customers/me/privacy': _mine(),
      'GET /customer-svc/customers/me/privacy/requests': <dynamic>[],
    });
    await _pump(tester, recorder, size: const Size(1280, 1400));
    await tester.tap(find.byKey(const Key('privacy-notice')));
    await tester.pumpAndSettle();
    // About 80 characters of 14px text at most: 552px.
    final body = tester.renderObject<RenderParagraph>(find.text(_longBody));
    expect(body.constraints.maxWidth, lessThanOrEqualTo(552));
    final intro = tester.renderObject<RenderParagraph>(find.textContaining('Each purpose on its own'));
    expect(intro.constraints.maxWidth, lessThanOrEqualTo(552));
    expect(tester.getRect(find.byKey(const Key('consent-LOYALTY'))).left, greaterThan(300));
  });
}

const _longBody = 'We keep your orders so that we can deliver them, answer a question about '
    'them and meet what the law asks of us. We keep your loyalty points and store credit '
    'for as long as you shop here. We never sell what we know about you, and we send you '
    'offers only on the channels you allow. You can ask for a copy of everything we hold, '
    'ask us to correct it or to erase it, and name someone to act for you.';

Map<String, dynamic> _view({String body = 'We keep your orders.'}) => {
      'requested': 'en',
      'served': 'en',
      'notice': {
        'language': 'en',
        'languageName': 'English',
        'version': 2,
        'title': 'How we use your data',
        'body': body,
      },
      'languages': [
        {'code': 'en', 'name': 'English', 'published': true},
      ],
      'purposes': [
        {'code': 'LOYALTY', 'text': 'Loyalty: points on what you buy', 'tracking': false},
        {'code': 'MARKETING', 'text': 'Marketing: offers by email', 'tracking': true},
      ],
      'settings': {
        'grievanceName': 'Grievance Officer',
        'grievanceEmail': 'privacy@example.in',
        'responseDays': 15,
        'hasGrievanceContact': true,
      },
      'dpdp': true,
      'dpdpFrom': null,
    };

Map<String, dynamic> _mine({bool marketing = false}) => {
      'consents': [
        {'purpose': 'LOYALTY', 'text': 'Loyalty: points on what you buy', 'tracking': false, 'granted': true},
        {'purpose': 'MARKETING', 'text': 'Marketing: offers by email', 'tracking': true, 'granted': marketing},
        {'purpose': 'PERSONALISATION', 'text': 'Personalisation: suggestions', 'tracking': true, 'granted': false},
        {'purpose': 'ANALYTICS', 'text': 'Analytics: how the shop is used', 'tracking': true, 'granted': false},
      ],
      'child': false,
      'canTrack': true,
      'guardian': null,
    };
