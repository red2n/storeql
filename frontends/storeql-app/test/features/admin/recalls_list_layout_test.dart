import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/recalls_screen.dart';
import 'package:storeql_app/shared/widgets/status_badge.dart';

// ---------------------------------------------------------------------------
// The recalls list, read on a phone and at a glance: the status filter fits a
// 390px screen with every word whole, the kind badge is a badge rather than a
// second title, and a recall a store still has to act on says so in words
// beside its kind while every row keeps the same chevron.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Adapter implements HttpClientAdapter {
  final List<RequestOptions> gets = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    if (o.method == 'GET') gets.add(o);
    final body = o.path.endsWith('/admin/inventory/recalls') ? _list : '{"data":[]}';
    return ResponseBody.fromString(body, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

class _Auth extends AuthNotifier {
  @override
  Future<AuthState> build() async => const AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: 'user-1',
        tenantId: 'tenant-1',
        roles: ['MANAGER'],
      );
}

const _list = '''
{"data":[
 {"id":"r-1","reference":"FSA-PRIN-42","kind":"RECALL","hazard":"ALLERGEN","status":"OPEN",
  "openedAt":"2026-09-11T08:00:00Z","scopeLines":1,"storesAffected":2,"storesOutstanding":2,"qtyHeld":13},
 {"id":"r-2","reference":"WD-7","kind":"WITHDRAWAL","hazard":"QUALITY","status":"OPEN",
  "openedAt":"2026-09-12T08:00:00Z","scopeLines":1,"storesAffected":1,"storesOutstanding":0,"qtyHeld":0}]}''';

Future<_Adapter> _pump(WidgetTester tester, Size size) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final adapter = _Adapter();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = adapter;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      authNotifierProvider.overrideWith(_Auth.new),
    ],
    child: const MaterialApp(home: Scaffold(body: RecallsScreen())),
  ));
  await tester.pumpAndSettle();
  return adapter;
}

/// The size a piece of text is actually drawn at, whatever it inherited.
double? _fontSize(WidgetTester tester, Finder text) => tester
    .renderObject<RenderParagraph>(
        find.descendant(of: text, matching: find.byType(RichText)).first)
    .text
    .style
    ?.fontSize;

void main() {
  // Dates are written through AppFormat in the app's locale (en_GB here); the
  // app loads intl's date data through flutter_localizations, a test loads it here.
  setUpAll(initializeDateFormatting);
  testWidgets('on a phone the status filter is a row of chips, every word on one line',
      (tester) async {
    final adapter = await _pump(tester, const Size(390, 844));

    expect(find.byType(SegmentedButton<String?>), findsNothing,
        reason: 'four equal segments break "Open" and "Cancelled" mid-word at 390px');
    for (final label in ['Open', 'Closed', 'Cancelled', 'All']) {
      final chip = find.widgetWithText(ChoiceChip, label);
      expect(chip, findsOneWidget, reason: label);
      final text = find.descendant(of: chip, matching: find.text(label));
      final line = tester.getSize(text).height;
      expect(line, lessThan(_fontSize(tester, text)! * 2),
          reason: '"$label" wraps onto a second line');
    }
    expect(tester.takeException(), isNull);

    await tester.tap(find.widgetWithText(ChoiceChip, 'Closed'));
    await tester.pumpAndSettle();
    expect(adapter.gets.last.queryParameters['status'], 'CLOSED');
  });

  testWidgets('on a phone at 200% text the header, filter and rows still fit', (tester) async {
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await _pump(tester, const Size(390, 844));
    expect(tester.takeException(), isNull);
    expect(find.widgetWithText(ChoiceChip, 'Cancelled'), findsOneWidget);
  });

  testWidgets('from tablet width the filter stays a segmented button', (tester) async {
    await _pump(tester, const Size(1400, 1000));
    expect(find.byType(SegmentedButton<String?>), findsOneWidget);
  });

  testWidgets('the kind badge is set in the badge size, not the title size', (tester) async {
    await _pump(tester, const Size(1400, 1000));
    final reference = _fontSize(tester, find.text('FSA-PRIN-42'))!;
    final recall = _fontSize(tester, find.text('Recall'))!;
    final withdrawal = _fontSize(tester, find.text('Withdrawal'))!;
    expect(recall, lessThan(reference));
    expect(recall, 12);
    expect(withdrawal, 12);
  });

  testWidgets('a store still to act is a status badge beside the kind; every row keeps its chevron',
      (tester) async {
    await _pump(tester, const Size(1400, 1000));
    expect(find.byIcon(Icons.chevron_right), findsNWidgets(2));

    final toAct = find.ancestor(
        of: find.textContaining('Stores to act'), matching: find.byType(StatusBadge));
    expect(toAct, findsOneWidget);
    expect(tester.widget<StatusBadge>(toAct).tone, StatusTone.error);
    // Beside the kind, on the same row as the reference.
    final row = find.ancestor(of: find.text('FSA-PRIN-42'), matching: find.byType(ListTile));
    expect(find.descendant(of: row, matching: toAct), findsOneWidget);

    // A recall every store has acted on is still open, and says that instead.
    final other = find.ancestor(of: find.text('WD-7'), matching: find.byType(ListTile));
    expect(
        find.descendant(
            of: other,
            matching: find.ancestor(of: find.text('Open'), matching: find.byType(StatusBadge))),
        findsOneWidget);
  });

  testWidgets('at 200% text from tablet to desktop the status filter is chips too, words whole',
      (tester) async {
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await _pump(tester, const Size(1000, 1000));
    expect(tester.takeException(), isNull);
    expect(find.byType(SegmentedButton<String?>), findsNothing,
        reason: 'equal segments break Open and Cancelled mid-word at this size');
    for (final label in ['Open', 'Closed', 'Cancelled']) {
      final chip = find.widgetWithText(ChoiceChip, label);
      expect(chip, findsOneWidget, reason: label);
    }
  });
}
