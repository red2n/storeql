import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/ids.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/orders_screen.dart';

// ---------------------------------------------------------------------------
// Return-controls: the admin Return / Refund dialog. Every returned line says
// what condition it is in (nothing preselected) before anything is sent; the
// refund goes where the person chose; the Idempotency-Key of a submit is kept
// for a retry of the same thing and renewed for a different one; store credit
// is off for a sale with no customer; a return that needs a manager says why in
// words; a gift card's code is shown once.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

/// One scripted answer to the POST: a status and body, or a network failure.
class _Answer {
  final int status;
  final String body;
  final bool network;
  const _Answer(this.status, this.body) : network = false;
  const _Answer.network()
      : status = 0,
        body = '',
        network = true;
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  final bool hasCustomer;
  final List<_Answer> answers;
  _Server({required this.hasCustomer, required this.answers});

  List<RequestOptions> get posts => requests.where((r) => r.method == 'POST').toList();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.method == 'POST') {
      final a = answers.length > 1 ? answers.removeAt(0) : answers.first;
      if (a.network) {
        throw DioException(requestOptions: o, type: DioExceptionType.connectionError);
      }
      return _json(a.body, a.status);
    }
    if (o.path.endsWith('/orders/o-1')) {
      return _json(
          '{"data":{"id":"o-1","status":"FULFILLED","currency":"GBP","total":20.0,'
          '${hasCustomer ? '"customerId":"c-1",' : ''}'
          '"items":[{"variantId":"v-1","qty":2,"unitPrice":10.0,"lineTotal":20.0}]}}',
          200);
    }
    return _json('{"data":[]}', 200);
  }

  static ResponseBody _json(String body, int status) => ResponseBody.fromString(body, status,
      headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
}

const _created = _Answer(201,
    '{"data":{"id":"r-1","refundAmount":10.0,"refundMethod":"ORIGINAL","status":"COMPLETED","outsidePolicy":[]}}');

Future<({_Server server, List<int> done})> _open(
  WidgetTester tester, {
  bool hasCustomer = true,
  List<_Answer> answers = const [_created],
}) async {
  tester.view.physicalSize = const Size(800, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(hasCustomer: hasCustomer, answers: List.of(answers));
  final done = <int>[];
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<void>(
              context: context,
              builder: (_) => ReturnDialog(orderId: 'o-1', onDone: () => done.add(1)),
            ),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  return (server: server, done: done);
}

Future<void> _tap(WidgetTester tester, Finder f) async {
  await tester.ensureVisible(f);
  await tester.pumpAndSettle();
  await tester.tap(f);
  await tester.pumpAndSettle();
}

/// Return one of the line's two units.
Future<void> _addOne(WidgetTester tester) => _tap(tester, find.byTooltip('Increase quantity'));

Future<void> _condition(WidgetTester tester, String c) =>
    _tap(tester, find.byKey(Key('return-condition-v-1-$c')));

Future<void> _submit(WidgetTester tester) =>
    _tap(tester, find.widgetWithText(FilledButton, 'Process return'));

Map<String, dynamic> _body(RequestOptions r) =>
    (r.data is String ? jsonDecode(r.data as String) : r.data) as Map<String, dynamic>;

void main() {
  testWidgets('a condition is required per returned line before anything is sent', (tester) async {
    final o = await _open(tester);
    // No condition offered until a quantity is chosen, and none preselected after.
    expect(find.byKey(const Key('return-condition-v-1-SEALED')), findsNothing);
    await _addOne(tester);
    for (final w in ['Sealed', 'Opened', 'Damaged', 'Faulty']) {
      expect(find.widgetWithText(ChoiceChip, w), findsOneWidget);
    }
    expect(
        tester
            .widgetList<ChoiceChip>(find.byType(ChoiceChip))
            .where((c) => c.selected && c.key.toString().contains('return-condition')),
        isEmpty);

    await _submit(tester);
    expect(find.text('Say what condition each returned item is in.'), findsOneWidget);
    expect(o.server.posts, isEmpty);
    expect(o.done, isEmpty);
  });

  testWidgets('sends the chosen condition, the method and an Idempotency-Key', (tester) async {
    final o = await _open(tester);
    await _addOne(tester);
    await _condition(tester, 'OPENED');
    await _submit(tester);

    final post = o.server.posts.single;
    expect(post.path, endsWith('/order-svc/orders/o-1/returns'));
    final body = _body(post);
    expect(body['refundMethod'], 'ORIGINAL');
    expect(body.containsKey('giftCardCode'), isFalse);
    expect(body['items'], [
      {'variantId': 'v-1', 'qty': 1, 'condition': 'OPENED'}
    ]);
    expect(isV7(post.headers['Idempotency-Key'] as String), isTrue);
    expect(o.done, hasLength(1));
    expect(find.text('Return / Refund'), findsNothing);
  });

  testWidgets('a retry of the same submit reuses the key, a different submit gets a new one',
      (tester) async {
    final o = await _open(tester, answers: const [
      _Answer.network(),
      _Answer(500, '{"error":{"code":"INTERNAL","message":"try again"}}'),
      _created,
    ]);
    await _addOne(tester);
    await _condition(tester, 'SEALED');
    await _submit(tester); // the network fails
    expect(find.text('Return / Refund'), findsOneWidget);
    await _submit(tester); // the same submit again
    await _condition(tester, 'FAULTY'); // now a different return
    await _submit(tester);

    final keys = [for (final p in o.server.posts) p.headers['Idempotency-Key'] as String];
    expect(keys, hasLength(3));
    expect(keys[1], keys[0]);
    expect(keys[2], isNot(keys[0]));
    expect(_body(o.server.posts.last)['items'], [
      {'variantId': 'v-1', 'qty': 1, 'condition': 'FAULTY'}
    ]);
  });

  testWidgets('store credit is off, with the reason, for a sale that names no customer',
      (tester) async {
    await _open(tester, hasCustomer: false);
    final chip = tester.widget<ChoiceChip>(find.byKey(const Key('return-method-STORE_CREDIT')));
    expect(chip.onSelected, isNull);
    expect(find.text('Store credit needs a sale that names a customer.'), findsOneWidget);
  });

  testWidgets('store credit is offered, and sent, when the sale names a customer', (tester) async {
    final o = await _open(tester);
    expect(find.byKey(const Key('return-store-credit-why')), findsNothing);
    await _addOne(tester);
    await _condition(tester, 'SEALED');
    await _tap(tester, find.byKey(const Key('return-method-STORE_CREDIT')));
    await _submit(tester);
    expect(_body(o.server.posts.single)['refundMethod'], 'STORE_CREDIT');
  });

  testWidgets('a return that needs a manager says why in words and stays open', (tester) async {
    final o = await _open(tester, answers: const [
      _Answer(
          403,
          '{"code":"ORDER_RETURN_NEEDS_MANAGER","status":403,"details":["CEILING","WINDOW"],'
          '"error":{"code":"ORDER_RETURN_NEEDS_MANAGER","message":"needs a manager"}}'),
    ]);
    await _addOne(tester);
    await _condition(tester, 'SEALED');
    await _submit(tester);

    expect(find.text('A manager must take this return'), findsOneWidget);
    expect(find.text("· Over the cashier's refund limit"), findsOneWidget);
    expect(find.text("· Past the business's return window"), findsOneWidget);
    expect(find.text('CEILING'), findsNothing);
    expect(find.text('Return / Refund'), findsOneWidget);
    expect(o.done, isEmpty);
  });

  testWidgets('faulty goods past the window is worded, not shown as a code', (tester) async {
    await _open(tester, answers: const [
      _Answer(
          403,
          '{"error":{"code":"ORDER_RETURN_NEEDS_MANAGER","message":"m",'
          '"details":["FAULTY_PAST_WINDOW"]},"details":["FAULTY_PAST_WINDOW"]}'),
    ]);
    await _addOne(tester);
    await _condition(tester, 'FAULTY');
    await _submit(tester);
    expect(find.text('· Faulty goods past the window'), findsOneWidget);
  });

  testWidgets('another refusal is shown in words', (tester) async {
    await _open(tester, answers: const [
      _Answer(404, '{"error":{"code":"GIFT_CARD_NOT_FOUND","message":"gift card not found"}}'),
    ]);
    await _addOne(tester);
    await _condition(tester, 'SEALED');
    await _tap(tester, find.byKey(const Key('return-method-GIFT_CARD')));
    await _tap(tester, find.byKey(const Key('return-gift-topup')));
    await tester.enterText(find.byKey(const Key('return-gift-code')), 'NOPE');
    await _submit(tester);
    expect(find.text('No gift card has that code. Check it, or issue a new card.'),
        findsOneWidget);
  });

  testWidgets('a gift card refund shows the card code and balance once', (tester) async {
    final o = await _open(tester, answers: const [
      _Answer(
          201,
          '{"data":{"id":"r-1","refundAmount":10.0,"refundMethod":"GIFT_CARD","status":"COMPLETED",'
          '"giftCard":{"id":"g-1","code":"GC-ABCD-1234","balance":10.0}}}'),
    ]);
    await _addOne(tester);
    await _condition(tester, 'SEALED');
    await _tap(tester, find.byKey(const Key('return-method-GIFT_CARD')));
    await _submit(tester);

    final body = _body(o.server.posts.single);
    expect(body['refundMethod'], 'GIFT_CARD');
    expect(body.containsKey('giftCardCode'), isFalse); // a new card
    expect(find.text('GC-ABCD-1234'), findsOneWidget);
    expect(find.textContaining('Balance'), findsOneWidget);
    expect(o.done, hasLength(1));

    await _tap(tester, find.widgetWithText(FilledButton, 'Done'));
    expect(find.text('GC-ABCD-1234'), findsNothing);
  });

  testWidgets('topping up a card sends its code', (tester) async {
    final o = await _open(tester);
    await _addOne(tester);
    await _condition(tester, 'SEALED');
    await _tap(tester, find.byKey(const Key('return-method-GIFT_CARD')));
    await _tap(tester, find.byKey(const Key('return-gift-topup')));
    await tester.enterText(find.byKey(const Key('return-gift-code')), ' GC-1 ');
    await _submit(tester);
    expect(_body(o.server.posts.single)['giftCardCode'], 'GC-1');
  });
}
