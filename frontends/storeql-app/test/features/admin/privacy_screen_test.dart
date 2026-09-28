import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/spacing.dart';
import 'package:storeql_app/features/admin/privacy_screen.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// The business's side of its customers' privacy (13.12): the grievance contact
// and period, the notice per language, the queue of requests, and a breach told
// to customers.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  final answers = <String, String>{};
  final statuses = <String, int>{};
  _Server() {
    answers['GET /customer-svc/customers/privacy/settings'] =
        '{"data":{"grievanceName":null,"grievanceEmail":null,"responseDays":30,"hasGrievanceContact":false}}';
    answers['GET /customer-svc/customers/privacy/notices'] = '{"data":[]}';
    answers['GET /customer-svc/customers/privacy/requests'] = '{"data":[]}';
    answers['GET /customer-svc/customers/privacy/breach-intimations'] = '{"data":[]}';
  }
  @override
  void close({bool force = false}) {}
  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final key = '${o.method} ${o.path}';
    final status = statuses[key] ?? 200;
    final body = status >= 400
        ? '{"error":{"code":"PRIVACY_RESPONSE_DAYS_INVALID","message":"a request is answered within 1 to 90 days"}}'
        : answers[key] ?? '{"data":{}}';
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Future<_Server> _pump(WidgetTester tester, void Function(_Server) setUp) async {
  tester.view.physicalSize = const Size(1100, 2400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final server = _Server();
  setUp(server);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: const MaterialApp(home: Scaffold(body: PrivacyScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

const _asha = '0199a0b0-0000-7000-8000-00000000a5a1';
const _unknown = '0199a0b0-0000-7000-8000-0000000b0b01';

String _request(String id, {String status = 'OPEN', bool overdue = false, String customerId = 'c-1'}) =>
    '{"id":"$id","customerId":"$customerId","kind":"GRIEVANCE","detail":"You kept emailing.","openedAt":"2026-09-10T10:00:00Z","dueOn":"2026-09-25","status":"$status","overdue":$overdue,"resolution":${status == 'OPEN' ? 'null' : '"Stopped."'}}';

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  setUpAll(initializeDateFormatting);

  testWidgets('the settings load into the form and save as one PUT', (tester) async {
    final server = await _pump(tester, (s) {
      s.answers['GET /customer-svc/customers/privacy/settings'] =
          '{"data":{"grievanceName":"Grievance Officer","grievanceEmail":"privacy@example.in","responseDays":15,"hasGrievanceContact":true}}';
    });
    expect(find.text('Grievance Officer'), findsOneWidget);
    expect(find.text('15'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('privacy-response-days')), '20');
    await tester.tap(find.byKey(const Key('privacy-save-settings')));
    await tester.pumpAndSettle();
    final put = server.requests.singleWhere((r) => r.method == 'PUT');
    expect(put.path, '/customer-svc/customers/privacy/settings');
    expect((put.data as Map)['responseDays'], 20);
    expect((put.data as Map)['grievanceEmail'], 'privacy@example.in');
    expect(find.text('Saved.'), findsOneWidget);
  });

  testWidgets('a refused period is shown in the server\'s words', (tester) async {
    await _pump(tester, (s) => s.statuses['PUT /customer-svc/customers/privacy/settings'] = 400);
    await tester.enterText(find.byKey(const Key('privacy-response-days')), '91');
    await tester.tap(find.byKey(const Key('privacy-save-settings')));
    await tester.pumpAndSettle();
    expect(find.textContaining('1 to 90 days'), findsOneWidget);
  });

  testWidgets('the notice offers English and the twenty-two, marks what is published, and publishes',
      (tester) async {
    final server = await _pump(tester, (s) {
      s.answers['GET /customer-svc/customers/privacy/notices'] =
          '{"data":[{"language":"en","languageName":"English","version":2,"title":"How we use your data","publishedAt":"2026-09-15T10:00:00Z"}]}';
    });
    expect(find.byKey(const Key('notice-en')), findsOneWidget);
    expect(find.text('English v2'), findsOneWidget);
    await tester.tap(find.byKey(const Key('privacy-notice-language')));
    await tester.pumpAndSettle();
    expect(find.text('Hindi — not yet published'), findsWidgets);
    expect(find.text('English — version 2'), findsWidgets);
    await tester.tap(find.text('Hindi — not yet published').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('privacy-notice-title')), 'सूचना');
    await tester.enterText(find.byKey(const Key('privacy-notice-body')), 'हम आपके ऑर्डर रखते हैं।');
    await tester.tap(find.byKey(const Key('privacy-publish')));
    await tester.pumpAndSettle();
    final post = server.requests.singleWhere((r) => r.method == 'POST');
    expect(post.path, '/customer-svc/customers/privacy/notices');
    expect((post.data as Map)['language'], 'hi');
    expect((post.data as Map)['title'], 'सूचना');
    expect(find.text('Published in Hindi.'), findsOneWidget);
  });

  testWidgets('the queue shows what is due, flags the overdue, and answers with one POST',
      (tester) async {
    final server = await _pump(tester, (s) {
      s.answers['GET /customer-svc/customers/privacy/requests'] =
          '{"data":[${_request('r-1', overdue: true)},${_request('r-2')}]}';
    });
    expect(find.byKey(const Key('privacy-request-r-1')), findsOneWidget);
    // Due dates as dates, not ISO strings.
    expect(find.textContaining('Overdue: due 25 Sept 2026'), findsOneWidget);
    expect(find.textContaining('Due 25 Sept 2026'), findsOneWidget);
    expect(find.textContaining('2026-09-25'), findsNothing);
    final list = server.requests.firstWhere((r) => r.path.endsWith('/privacy/requests'));
    expect(list.queryParameters['status'], 'OPEN', reason: 'the queue is the open ones, soonest due first');
    await tester.tap(find.byKey(const Key('resolve-r-1')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('resolve-text')), 'Stopped, and the log corrected.');
    await tester.tap(find.byKey(const Key('resolve-send')));
    await tester.pumpAndSettle();
    final post = server.requests.singleWhere((r) => r.method == 'POST');
    expect(post.path, '/customer-svc/customers/privacy/requests/r-1/resolve');
    expect((post.data as Map)['status'], 'RESOLVED');
    expect((post.data as Map)['resolution'], 'Stopped, and the log corrected.');
  });

  testWidgets('telling customers of a breach sends the subject and body, and lists what was sent',
      (tester) async {
    final server = await _pump(tester, (s) {
      s.answers['GET /customer-svc/customers/privacy/breach-intimations'] =
          '{"data":[{"id":"i-1","subject":"About your data","sentAt":"2026-09-15T10:00:00Z","recipients":40,"failures":2}]}';
      s.answers['POST /customer-svc/customers/privacy/breach-intimations'] =
          '{"data":{"id":"i-2","recipients":41,"failures":0}}';
    });
    expect(find.textContaining('to 40, 2 not reached'), findsOneWidget);
    await tester.tap(find.byKey(const Key('privacy-tell-customers')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('intimation-body')), 'Names and emails were read. Change reused passwords.');
    await tester.tap(find.byKey(const Key('intimation-send')));
    await tester.pumpAndSettle();
    final post = server.requests.singleWhere((r) => r.method == 'POST');
    expect(post.path, '/customer-svc/customers/privacy/breach-intimations');
    expect((post.data as Map)['subject'], 'About your data');
    expect((post.data as Map).containsKey('noticeId'), isFalse);
  });

  testWidgets('the grievance fields and the notice fields stand 12px apart', (tester) async {
    await _pump(tester, (_) {});
    double gap(String upper, String lower) =>
        tester.getTopLeft(find.byKey(Key(lower))).dy - tester.getBottomLeft(find.byKey(Key(upper))).dy;
    // Edge to edge, their outlines touched.
    expect(gap('privacy-grievance-name', 'privacy-grievance-email'), AppSpacing.md);
    expect(gap('privacy-grievance-email', 'privacy-grievance-phone'), AppSpacing.md);
    expect(gap('privacy-grievance-phone', 'privacy-grievance-address'), AppSpacing.md);
    expect(gap('privacy-grievance-address', 'privacy-response-days'), AppSpacing.md);
    expect(gap('privacy-notice-language', 'privacy-notice-title'), AppSpacing.md);
    expect(gap('privacy-notice-title', 'privacy-notice-body'), AppSpacing.md);
  });

  testWidgets('the notice opens on the published version of the chosen language, to be corrected',
      (tester) async {
    final server = await _pump(tester, (s) {
      s.answers['GET /customer-svc/customers/privacy/notices'] =
          '{"data":[{"language":"en","languageName":"English","version":2,"title":"How we use your data",'
          '"body":"We keep your orders for six years.","publishedAt":"2026-09-15T10:00:00Z"}]}';
    });
    TextField field(String key) => tester.widget<TextField>(find.byKey(Key(key)));
    expect(field('privacy-notice-title').controller!.text, 'How we use your data');
    expect(field('privacy-notice-body').controller!.text, 'We keep your orders for six years.');

    // A language with nothing published starts empty; back to English, the published text again.
    await tester.tap(find.byKey(const Key('privacy-notice-language')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Hindi — not yet published').last);
    await tester.pumpAndSettle();
    expect(field('privacy-notice-title').controller!.text, '');
    expect(field('privacy-notice-body').controller!.text, '');
    await tester.tap(find.byKey(const Key('privacy-notice-language')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('English — version 2').last);
    await tester.pumpAndSettle();
    expect(field('privacy-notice-title').controller!.text, 'How we use your data');

    // A typo fixed in place, not the whole notice typed again.
    await tester.enterText(find.byKey(const Key('privacy-notice-body')), 'We keep your orders for six years.\n');
    await tester.tap(find.byKey(const Key('privacy-publish')));
    await tester.pumpAndSettle();
    final post = server.requests.singleWhere((r) => r.method == 'POST');
    expect((post.data as Map)['language'], 'en');
    expect((post.data as Map)['title'], 'How we use your data');
    expect((post.data as Map)['body'], 'We keep your orders for six years.');
  });

  testWidgets('what is typed is not thrown away by choosing a language', (tester) async {
    await _pump(tester, (s) {
      s.answers['GET /customer-svc/customers/privacy/notices'] =
          '{"data":[{"language":"hi","languageName":"Hindi","version":1,"title":"सूचना",'
          '"body":"पुराना","publishedAt":"2026-09-15T10:00:00Z"}]}';
    });
    await tester.enterText(find.byKey(const Key('privacy-notice-title')), 'Draft title');
    await tester.tap(find.byKey(const Key('privacy-notice-language')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Hindi — version 1').last);
    await tester.pumpAndSettle();
    expect(tester.widget<TextField>(find.byKey(const Key('privacy-notice-title'))).controller!.text,
        'Draft title');
  });

  testWidgets('a request names the customer who made it', (tester) async {
    await _pump(tester, (s) {
      s.answers['GET /customer-svc/customers/privacy/requests'] =
          '{"data":[${_request('r-1', customerId: _asha)},${_request('r-2', customerId: _unknown)}]}';
      s.answers['GET /customer-svc/customers/$_asha'] =
          '{"data":{"id":"$_asha","firstName":"Asha","lastName":"Rao","email":"asha@example.in","status":"ACTIVE"}}';
      s.statuses['GET /customer-svc/customers/$_unknown'] = 404;
    });
    expect(find.text('Grievance from Asha Rao'), findsOneWidget);
    expect(find.textContaining(_asha), findsNothing, reason: 'a name, not an id');
    // One whose record cannot be read is named by the end of its id, never the whole of it.
    expect(find.text('Grievance from customer #${_unknown.substring(_unknown.length - 8)}'), findsOneWidget);
  });

  testWidgets('with no breach told, the card says so', (tester) async {
    await _pump(tester, (_) {});
    expect(find.text('No breach has been told to customers.'), findsOneWidget);
  });
}
