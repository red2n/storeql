import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/shared/widgets/status_badge.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/features/platform/platform_dashboard_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The platform operator's home page. It reports what it has read — the
// gateway's own health — and nothing it has not: no service is called healthy
// because a string says so, and no developer runbook sits on the page.
// ---------------------------------------------------------------------------

class _Admin extends AuthNotifier {
  @override
  Future<AuthState> build() async => const AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: 'u',
        roles: ['PLATFORM_ADMIN'],
        email: 'operator@storeql.com',
      );
}

/// The gateway's health endpoint: UP, DOWN, a page that is not a health document, or no answer.
class _Gateway implements HttpClientAdapter {
  final String? status;
  final bool answers;
  final String? body;
  final List<RequestOptions> asked = [];

  _Gateway({this.status, this.answers = true, this.body});

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    asked.add(o);
    if (!answers) {
      throw DioException.connectionError(requestOptions: o, reason: 'refused');
    }
    if (body != null) {
      return ResponseBody.fromString(body!, 200, headers: {
        Headers.contentTypeHeader: ['text/html'],
      });
    }
    return jsonResponse(
      jsonEncode({
        'status': status,
        'checks': [
          {'name': 'gateway', 'status': status},
        ],
      }),
      status == 'UP' ? 200 : 503,
    );
  }
}

Future<_Gateway> _pump(
  WidgetTester tester, {
  String? status = 'UP',
  bool answers = true,
  String? body,
  Size size = const Size(1200, 1600),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final gateway = _Gateway(status: status, answers: answers, body: body);
  final dio = Dio()..httpClientAdapter = gateway;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authNotifierProvider.overrideWith(_Admin.new),
        gatewayHealthDioProvider.overrideWithValue(dio),
      ],
      child: const MaterialApp(home: Scaffold(body: PlatformDashboardScreen())),
    ),
  );
  await tester.pumpAndSettle();
  return gateway;
}

String _healthSays(WidgetTester tester) {
  final badge = tester.widget<Text>(find.descendant(
    of: find.byKey(const Key('gateway-health-status')),
    matching: find.byType(Text),
  ));
  return badge.textSpan!.toPlainText();
}

void main() {
  test('the health endpoint is the API base without its /api prefix', () {
    expect(gatewayHealthUri('http://localhost:8090/api').toString(), 'http://localhost:8090/health');
    expect(gatewayHealthUri('https://api.example.com/api/').toString(), 'https://api.example.com/health');
    expect(gatewayHealthUri('https://example.com/shop/api').toString(), 'https://example.com/shop/health');
  });

  testWidgets('the gateway is called healthy only when its health check says UP', (tester) async {
    final gateway = await _pump(tester, status: 'UP');

    expect(_healthSays(tester), 'Healthy');
    expect(gateway.asked.single.uri.toString(), 'http://localhost:8090/health');
    expect(gateway.asked.single.headers.containsKey('Authorization'), isFalse,
        reason: 'the probe is public; a credential is nobody else\'s business');
    // Nothing claims more than was read.
    expect(find.textContaining('All Services Healthy'), findsNothing);
    expect(find.textContaining('all running'), findsNothing);
    expect(find.textContaining('Bootstrap Complete'), findsNothing);
  });

  testWidgets('a gateway that does not answer is unreachable, and can be asked again', (tester) async {
    final gateway = await _pump(tester, answers: false);

    expect(_healthSays(tester), 'Unreachable');
    expect(find.textContaining('did not answer'), findsOneWidget);

    await tester.tap(find.byKey(const Key('gateway-health-recheck')));
    await tester.pumpAndSettle();
    expect(gateway.asked, hasLength(2));
  });

  testWidgets('a gateway reporting DOWN is unhealthy, not healthy', (tester) async {
    await _pump(tester, status: 'DOWN');

    expect(_healthSays(tester), 'Unhealthy');
  });

  testWidgets('an answer that is not a health document is not taken for health', (tester) async {
    // A web host that serves its page for every path answers 200 with HTML.
    await _pump(tester, body: '<!doctype html><title>StoreQL</title>');

    // Not health — and not a false alarm either: the web server answered with the app's own page,
    // which says nothing about the gateway (a relative-API web build whose server does not pass
    // /health on).
    expect(_healthSays(tester), 'Not readable here');
    expect(find.textContaining('cannot be read from this build'), findsOneWidget);
    final badge = tester.widget<StatusBadge>(find.byKey(const Key('gateway-health-status')));
    expect(badge.tone, StatusTone.neutral);
  });

  testWidgets('the home page carries no developer runbook, and its headings are sentence case', (tester) async {
    await _pump(tester);

    expect(find.text('Platform overview'), findsOneWidget);
    expect(find.text('Tenant management'), findsOneWidget);
    expect(find.text('How tenant onboarding works'), findsOneWidget);
    expect(find.text('Key Platform Endpoints'), findsNothing);
    expect(find.textContaining('/api/'), findsNothing);
    expect(find.textContaining('/bootstrap/'), findsNothing);
    for (final verb in ['GET', 'POST']) {
      expect(find.text(verb), findsNothing);
    }
    // A role is said in words, not as a code.
    expect(find.textContaining('CUSTOMER'), findsNothing);
    expect(find.textContaining('OWNER'), findsNothing);
  });

  testWidgets('on a phone the badge goes under the heading, which has the row to itself', (tester) async {
    await _pump(tester, size: const Size(390, 1600));

    final heading = find.text('Platform overview');
    // The whole row beside the avatar: 358 inside the phone's gutters, less the 48 avatar and its
    // 16 gap. Sharing it with the badge is what squeezed the heading onto two lines. (Line count is
    // not measured: the test font draws every glyph a full em wide.)
    final p = tester.renderObject<RenderParagraph>(heading);
    expect(p.constraints.maxWidth, 390 - 2 * 16 - 48 - 16);
    expect(tester.renderObject<RenderParagraph>(find.text('operator@storeql.com')).constraints.maxWidth,
        390 - 2 * 16 - 48 - 16,
        reason: 'the email has the room too');
    expect(tester.getTopLeft(find.byKey(const Key('platform-admin-badge'))).dy,
        greaterThanOrEqualTo(tester.getBottomLeft(heading).dy));
    expect(find.text('operator@storeql.com'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('wide, the badge sits at the end of the heading row', (tester) async {
    await _pump(tester, size: const Size(1200, 1600));

    final heading = find.text('Platform overview');
    final badge = find.byKey(const Key('platform-admin-badge'));
    expect(tester.getTopLeft(badge).dy, lessThan(tester.getBottomLeft(heading).dy));
    expect(tester.getTopLeft(badge).dx, greaterThan(tester.getTopRight(heading).dx));
  });

  testWidgets('a phone at 200% text lays the page out without overflowing', (tester) async {
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await _pump(tester, size: const Size(390, 3200));

    expect(find.text('Platform overview'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
