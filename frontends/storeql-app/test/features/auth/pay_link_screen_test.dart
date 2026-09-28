import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/auth/pay_link_screen.dart';

// ---------------------------------------------------------------------------
// The page a dunning notice's pay link opens (21.12): no sign-in, one button,
// which pays the one invoice the token names — and says plainly when the link
// opens nothing, rather than pretending a payment happened.
// ---------------------------------------------------------------------------

class _Api implements HttpClientAdapter {
  int status = 200;
  String body = '{"data":{"number":"INV-2026-000041","status":"PAID","totalAmount":29.00,"currency":"EUR"}}';
  RequestOptions? last;
  int calls = 0;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    last = o;
    calls++;
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Future<_Api> _pump(WidgetTester tester,
    {String token = '9m2xKq1vT8sHc4bYw7Lp3Q',
    void Function(_Api)? setUp,
    Size? size,
    double textScale = 1}) async {
  if (size != null) {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
  }
  final api = _Api();
  setUp?.call(api);
  final dio = Dio(BaseOptions(baseUrl: 'http://test/api'))..httpClientAdapter = api;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[payLinkDioProvider.overrideWithValue(dio)],
    child: MaterialApp(
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(textScale)),
        child: child!,
      ),
      home: PayLinkScreen(token: token),
    ),
  ));
  await tester.pumpAndSettle();
  return api;
}

void main() {
  testWidgets('one press pays the invoice the token names, with no session attached', (tester) async {
    final api = await _pump(tester);
    expect(find.text('Pay your invoice'), findsOneWidget);
    expect(find.byKey(const Key('pay-done')), findsNothing);

    await tester.tap(find.byKey(const Key('pay-now')));
    await tester.pumpAndSettle();

    expect(api.calls, 1);
    expect(api.last!.method, 'POST');
    expect(api.last!.path, '/tenant-svc/billing/pay/9m2xKq1vT8sHc4bYw7Lp3Q');
    expect(api.last!.headers.containsKey('Authorization'), isFalse);
    expect(find.byKey(const Key('pay-done')), findsOneWidget);
    expect(find.textContaining('Invoice INV-2026-000041 is settled — €29.00'), findsOneWidget);
    expect(find.textContaining('EUR'), findsNothing);
    expect(find.byKey(const Key('pay-now')), findsNothing);
  });

  testWidgets('a link that opens nothing says so, and offers no second try at pretending', (tester) async {
    await _pump(tester, setUp: (api) {
      api.status = 404;
      api.body = '{"code":"PAY_LINK_INVALID","status":404,"title":"Pay link invalid"}';
    });

    await tester.tap(find.byKey(const Key('pay-now')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('pay-done')), findsNothing);
    expect(find.textContaining('a newer notice has replaced it'), findsOneWidget);
  });

  testWidgets('any other failure says nothing was charged', (tester) async {
    await _pump(tester, setUp: (api) {
      api.status = 503;
      api.body = '{"code":"SERVICE_UNAVAILABLE"}';
    });

    await tester.tap(find.byKey(const Key('pay-now')));
    await tester.pumpAndSettle();

    expect(find.textContaining('Nothing was charged'), findsOneWidget);
  });

  testWidgets('with no token there is nothing to pay, and the button says so by staying off', (tester) async {
    await _pump(tester, token: '');
    final button = tester.widget<FilledButton>(find.byKey(const Key('pay-now')));
    expect(button.onPressed, isNull);
  });

  testWidgets('on a phone at 200% text the card scrolls rather than overflowing, inset by the gutter',
      (tester) async {
    await _pump(tester, size: const Size(390, 700), textScale: 2);
    expect(tester.takeException(), isNull);
    expect(tester.getTopLeft(find.byType(Card)).dx, 16);
    await tester.scrollUntilVisible(find.byKey(const Key('pay-now')), 100);
    expect(find.byKey(const Key('pay-now')).hitTestable(), findsOneWidget);
  });
}
