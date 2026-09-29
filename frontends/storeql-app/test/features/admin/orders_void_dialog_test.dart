import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/orders_screen.dart';

// ---------------------------------------------------------------------------
// 09.13: a completed till sale is voided from the back office. The menu offers
// it only on a till sale that is still standing, and only to a manager; the
// dialog will not send without a reason, posts the reason to /void, and shows
// the server's refusal in words. The receipt keeps its number — the dialog
// says so before the button is pressed.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int postStatus = 200;

  /// When set, a request is held until the gate completes — for the double-tap case.
  Completer<void>? gate;
  String refusal =
      '{"error":{"code":"ORDER_VOID_ONLY_POS","message":"void is only allowed on POS orders"}}';

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (gate != null) await gate!.future;
    final body = postStatus == 200
        ? '{"data":{"orderId":"o-1","reason":"Rang up twice","voidedAt":"2026-09-13T10:00:00Z"}}'
        : refusal;
    return ResponseBody.fromString(body, postStatus,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

class _Opened {
  final _Server server;
  int done = 0;
  _Opened(this.server);
}

Future<_Opened> _pumpDialog(WidgetTester tester) async {
  final opened = _Opened(_Server());
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = opened.server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<void>(
              context: context,
              builder: (_) => VoidSaleDialog(orderId: 'o-1', onDone: () => opened.done++),
            ),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  return opened;
}

Map<String, dynamic> _postBody(_Server s) {
  final post = s.requests.singleWhere((r) => r.method == 'POST');
  return (post.data is String ? jsonDecode(post.data as String) : post.data)
      as Map<String, dynamic>;
}

Future<List<String>> _pumpMenu(
  WidgetTester tester, {
  required String status,
  required String channel,
  bool canVoid = true,
}) async {
  final picked = <String>[];
  await tester.pumpWidget(MaterialApp(
    home: Scaffold(
      body: OrderActionsMenu(
        status: status,
        channel: channel,
        canVoid: canVoid,
        onAction: picked.add,
      ),
    ),
  ));
  await tester.tap(find.byIcon(Icons.more_vert));
  await tester.pumpAndSettle();
  return picked;
}

void main() {
  group('the actions menu', () {
    testWidgets('offers Void sale on a standing till sale to a manager, and it fires the void',
        (tester) async {
      final picked = await _pumpMenu(tester, status: 'FULFILLED', channel: 'POS');
      expect(find.text('Void sale'), findsOneWidget);
      await tester.tap(find.text('Void sale'));
      await tester.pumpAndSettle();
      expect(picked, ['void']);
    });

    testWidgets('a till sale not yet paid for can still be voided, beside its cancel',
        (tester) async {
      await _pumpMenu(tester, status: 'PENDING', channel: 'POS');
      expect(find.text('Void sale'), findsOneWidget);
      expect(find.text('Cancel'), findsOneWidget);
    });

    testWidgets('never on an online order — that is cancelled or returned instead',
        (tester) async {
      await _pumpMenu(tester, status: 'FULFILLED', channel: 'ONLINE');
      expect(find.text('Void sale'), findsNothing);
      expect(find.text('Return / Refund'), findsOneWidget);
    });

    testWidgets('not twice: a voided sale offers neither a void nor a return', (tester) async {
      await _pumpMenu(tester, status: 'VOIDED', channel: 'POS');
      expect(find.text('Void sale'), findsNothing);
      expect(find.text('Return / Refund'), findsNothing);
      expect(find.text('Print receipt'), findsOneWidget);
    });

    testWidgets('not to anyone below manager — the server would refuse them anyway',
        (tester) async {
      await _pumpMenu(tester, status: 'FULFILLED', channel: 'POS', canVoid: false);
      expect(find.text('Void sale'), findsNothing);
      expect(find.text('Return / Refund'), findsOneWidget);
    });

    testWidgets('a paid order is cancelled only by someone who may void — the cancel refunds it',
        (tester) async {
      await _pumpMenu(tester, status: 'CONFIRMED', channel: 'ONLINE', canVoid: false);
      expect(find.text('Cancel'), findsNothing);
    });

    testWidgets('a paid order offers Cancel to a manager who may void', (tester) async {
      await _pumpMenu(tester, status: 'CONFIRMED', channel: 'ONLINE');
      expect(find.text('Cancel'), findsOneWidget);
    });

    testWidgets('an unpaid order is still anyone\'s to cancel', (tester) async {
      await _pumpMenu(tester, status: 'PENDING', channel: 'ONLINE', canVoid: false);
      expect(find.text('Cancel'), findsOneWidget);
    });
  });

  group('the dialog', () {
    testWidgets('says the receipt keeps its number, takes a reason and posts it to /void',
        (tester) async {
      final opened = await _pumpDialog(tester);
      expect(find.textContaining('the receipt keeps its number'), findsOneWidget);

      await tester.enterText(find.byKey(const Key('void-reason')), '  Rang up twice ');
      await tester.tap(find.widgetWithText(FilledButton, 'Void sale'));
      await tester.pumpAndSettle();

      final post = opened.server.requests.singleWhere((r) => r.method == 'POST');
      expect(post.path, endsWith('/order-svc/orders/o-1/void'));
      expect(_postBody(opened.server), {'reason': 'Rang up twice'});
      expect(opened.done, 1);
      expect(find.text('Void sale?'), findsNothing);
      expect(find.text('Sale voided. The stock goes back and the receipt keeps its number.'),
          findsOneWidget);
    });

    testWidgets('a blank reason is stopped here and nothing is sent', (tester) async {
      final opened = await _pumpDialog(tester);
      await tester.enterText(find.byKey(const Key('void-reason')), '   ');
      await tester.tap(find.widgetWithText(FilledButton, 'Void sale'));
      await tester.pumpAndSettle();
      expect(find.text('A reason is required.'), findsOneWidget);
      expect(opened.server.requests, isEmpty);
      expect(opened.done, 0);
      expect(find.text('Void sale?'), findsOneWidget);
    });

    testWidgets("the server's refusal is shown in words and the dialog stays open",
        (tester) async {
      final opened = await _pumpDialog(tester);
      opened.server.postStatus = 409;
      await tester.enterText(find.byKey(const Key('void-reason')), 'Wrong order');
      await tester.tap(find.widgetWithText(FilledButton, 'Void sale'));
      await tester.pumpAndSettle();
      expect(find.text('void is only allowed on POS orders'), findsOneWidget);
      expect(find.text('Void sale?'), findsOneWidget);
      expect(opened.server.requests.where((r) => r.method == 'POST'), hasLength(1));
      expect(opened.done, 0);
    });

    testWidgets('a storekeeper who reached it is refused by the server, not let through',
        (tester) async {
      final opened = await _pumpDialog(tester);
      opened.server
        ..postStatus = 403
        ..refusal = '{"error":{"code":"FORBIDDEN","message":"management role required"}}';
      await tester.enterText(find.byKey(const Key('void-reason')), 'Trying it');
      await tester.tap(find.widgetWithText(FilledButton, 'Void sale'));
      await tester.pumpAndSettle();
      expect(find.text('management role required'), findsOneWidget);
      expect(opened.done, 0);
    });

    testWidgets('a double tap while the void is in flight sends it once', (tester) async {
      final opened = await _pumpDialog(tester);
      opened.server.gate = Completer<void>();
      await tester.enterText(find.byKey(const Key('void-reason')), 'Rang up twice');
      await tester.tap(find.widgetWithText(FilledButton, 'Void sale'));
      await tester.pump();
      // The button is disabled the moment the request goes out, so a second tap is nothing.
      final button = tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Void sale'));
      expect(button.onPressed, isNull);
      await tester.tap(find.widgetWithText(FilledButton, 'Void sale'), warnIfMissed: false);
      for (var i = 0; i < 5; i++) {
        await tester.pump(const Duration(milliseconds: 20));
      }
      expect(opened.server.requests.where((r) => r.method == 'POST'), hasLength(1));
      opened.server.gate!.complete();
      await tester.pumpAndSettle();
      expect(opened.server.requests.where((r) => r.method == 'POST'), hasLength(1));
      expect(opened.done, 1);
    });

    testWidgets('the reason is capped at 500 characters before it is sent', (tester) async {
      final opened = await _pumpDialog(tester);
      await tester.enterText(find.byKey(const Key('void-reason')), 'x' * 600);
      await tester.tap(find.widgetWithText(FilledButton, 'Void sale'));
      await tester.pumpAndSettle();
      expect((_postBody(opened.server)['reason'] as String).length, 500);
    });

    testWidgets('Keep sale sends nothing', (tester) async {
      final opened = await _pumpDialog(tester);
      await tester.enterText(find.byKey(const Key('void-reason')), 'Changed my mind');
      await tester.tap(find.text('Keep sale'));
      await tester.pumpAndSettle();
      expect(opened.server.requests, isEmpty);
      expect(opened.done, 0);
      expect(find.text('Void sale?'), findsNothing);
    });
  });
}
