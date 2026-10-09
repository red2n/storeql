import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/ids.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/pos/cash_providers.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/returns_screen.dart';

// ---------------------------------------------------------------------------
// The till's Returns screen (return-controls, slice 2): find a sale by its
// receipt number, take items back with a condition each, refund or exchange,
// and (managers only) take items back with no receipt. The server decides the
// policy; these tests pin what the screen sends, once and under a key, and
// that every refusal reads as words.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _CashierAuth extends AuthNotifier {
  @override
  Future<AuthState> build() async => const AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: 'u-cashier',
        roles: ['CASHIER'],
      );
}

class _ManagerAuth extends AuthNotifier {
  @override
  Future<AuthState> build() async => const AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: 'u-manager',
        roles: ['MANAGER'],
      );
}

/// One scripted answer.
class _Reply {
  final int status;
  final String body;
  const _Reply(this.status, this.body);
}

String _problem(int status, String code, String detail, {List<String> details = const []}) =>
    jsonEncode({
      'type': 'urn:storeql:problem:$code',
      'status': status,
      'detail': detail,
      'code': code,
      if (details.isNotEmpty) 'details': details,
      'error': {'code': code, 'message': detail},
    });

class _Server implements HttpClientAdapter {
  _Server({this.customerId, this.lookup});

  final String? customerId;

  /// Overrides the receipt lookup's answer.
  final _Reply? lookup;

  /// What "which till is open" answers, in turn (the last repeats): an id,
  /// `null` for none (404) or 'down' for a 503.
  List<String?> openTill = [null];
  int _asked = 0;

  /// Answers, in order, to POST /returns, /exchange and /returns/no-receipt.
  final List<_Reply> returnReplies = [];
  final List<_Reply> exchangeReplies = [];
  final List<_Reply> noReceiptReplies = [];
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  ResponseBody _json(_Reply r) => ResponseBody.fromString(r.body, r.status,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType]
      });

  ResponseBody _ok(Object data, [int status = 200]) =>
      _json(_Reply(status, jsonEncode({'data': data})));

  _Reply _next(List<_Reply> replies, Object fallback) =>
      replies.isEmpty
          ? _Reply(201, jsonEncode({'data': fallback}))
          : (replies.length == 1 ? replies.first : replies.removeAt(0));

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (o.method == 'GET' && path.endsWith('/till-sessions/current')) {
      final answer =
          openTill[_asked < openTill.length ? _asked : openTill.length - 1];
      _asked++;
      if (answer == 'down') {
        return _json(_Reply(503, _problem(503, 'SERVICE_UNAVAILABLE', 'Down.')));
      }
      if (answer == null) {
        return _json(_Reply(404, _problem(404, 'TILL_SESSION_NOT_OPEN', 'None.')));
      }
      return _ok({'id': answer, 'status': 'OPEN', 'storeId': 'store-1'});
    }
    if (o.method == 'GET' && path.endsWith('/orders/by-receipt')) {
      if (lookup != null) return _json(lookup!);
      return _ok({
        'order': {'id': 'o-1', 'currency': 'GBP', 'customerId': customerId},
        'receiptNumber': '2026-000042',
        'lines': [
          {
            'variantId': 'v-1',
            'soldQty': 2,
            'returnedQty': 0,
            'returnableQty': 2,
            'unitPrice': 6.0,
          },
        ],
      });
    }
    if (o.method == 'GET' && path.endsWith('/variants/resolve')) {
      return _ok([
        {'variantId': 'v-1', 'productName': 'Strawberry jam', 'sku': 'JAM-1'},
      ]);
    }
    if (o.method == 'GET' && path.endsWith('/catalog/scan')) {
      final code = o.queryParameters['code'];
      return _ok({
        'item': {
          'variantId': code == 'JAM-1' ? 'v-1' : 'v-2',
          'sku': code,
          'productName': code == 'JAM-1' ? 'Strawberry jam' : 'Marmalade',
        },
      });
    }
    if (o.method == 'POST' && path.endsWith('/prices/resolve')) {
      return _ok({'unitPrice': 6.0, 'currency': 'GBP'});
    }
    if (o.method == 'POST' && path.endsWith('/returns/no-receipt')) {
      return _json(_next(noReceiptReplies, {'refundAmount': 6.0}));
    }
    if (o.method == 'POST' && path.endsWith('/exchange')) {
      return _json(_next(exchangeReplies, {}));
    }
    if (o.method == 'POST' && path.endsWith('/returns')) {
      return _json(_next(returnReplies, {'refundAmount': 6.0}));
    }
    return _ok({});
  }

  List<RequestOptions> posts(String suffix) => requests
      .where((r) => r.method == 'POST' && r.path.endsWith(suffix))
      .toList();
}

/// The drawer the terminal has read as open (payment-svc's till session id).
class _Drawer extends SaleTillNotifier {
  _Drawer(this.id);

  final String? id;

  @override
  Future<String?> build() async => id;
}

Future<ProviderContainer> _pump(
  WidgetTester tester,
  _Server server, {
  AuthNotifier Function()? auth,
  List<Override> overrides = const [],
}) async {
  tester.view.physicalSize = const Size(800, 1400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  final router = GoRouter(routes: [
    GoRoute(
        path: '/',
        builder: (_, _) => const Scaffold(body: PosReturnsScreen())),
    GoRoute(
        path: '/pos/tender',
        builder: (_, _) => const Scaffold(body: Text('TENDER-SCREEN'))),
  ]);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      authNotifierProvider.overrideWith(auth ?? _CashierAuth.new),
      posStoreProvider.overrideWith((ref) => 'store-1'),
      ...overrides,
    ],
    child: MaterialApp.router(routerConfig: router),
  ));
  final container =
      ProviderScope.containerOf(tester.element(find.byType(PosReturnsScreen)));
  container.read(authNotifierProvider);
  await tester.pumpAndSettle();
  return container;
}

Future<void> _tap(WidgetTester tester, Finder f) async {
  await tester.ensureVisible(f);
  await tester.pumpAndSettle();
  await tester.tap(f);
  await tester.pumpAndSettle();
}

Future<void> _type(WidgetTester tester, Finder f, String text) async {
  await tester.ensureVisible(f);
  await tester.pumpAndSettle();
  await tester.enterText(f, text);
  await tester.pumpAndSettle();
}

Future<void> _findSale(WidgetTester tester, [String number = '2026-000042']) async {
  await _type(tester, find.byKey(const Key('returns-receipt-field')), number);
  await _tap(tester, find.byKey(const Key('returns-find')));
}

/// One jam back, in the given condition.
Future<void> _oneJamBack(WidgetTester tester, {String condition = 'SEALED'}) async {
  await _tap(tester, find.byKey(const Key('returns-inc-v-1')));
  await _tap(tester, find.byKey(Key('returns-condition-v-1-$condition')));
}

Finder _actionSegment(String label) => find.descendant(
    of: find.byKey(const Key('returns-action')), matching: find.text(label));

Future<void> _addNewItem(WidgetTester tester, String sku) async {
  await _type(tester, find.byKey(const Key('returns-item-field')), sku);
  await _tap(tester, find.byKey(const Key('returns-item-add')));
}

void main() {
  testWidgets('finds the sale by its receipt number and names its lines',
      (tester) async {
    final server = _Server();
    await _pump(tester, server);
    await _findSale(tester);

    final get = server.requests.firstWhere((r) => r.path.endsWith('/orders/by-receipt'));
    expect(get.queryParameters['number'], '2026-000042');
    expect(find.text('Sale 2026-000042'), findsOneWidget);
    expect(find.text('Strawberry jam'), findsOneWidget,
        reason: 'a name, not a variant id');
    expect(find.textContaining('sold 2'), findsOneWidget);
  });

  testWidgets('a receipt that is not found is said in words', (tester) async {
    final server = _Server(
        lookup: _Reply(404,
            _problem(404, 'ORDER_RECEIPT_NOT_FOUND', 'raw server text')));
    await _pump(tester, server);
    await _findSale(tester, 'NOPE');

    expect(find.byKey(const Key('returns-lookup-error')), findsOneWidget);
    expect(find.textContaining('No sale has that receipt number'), findsOneWidget);
    expect(find.text('raw server text'), findsNothing);
    expect(find.text('Sale 2026-000042'), findsNothing);
  });

  testWidgets('a returned line needs a condition before anything is sent',
      (tester) async {
    final server = _Server();
    await _pump(tester, server);
    await _findSale(tester);

    await _tap(tester, find.byKey(const Key('returns-inc-v-1')));
    // Nothing is preselected: the person looks.
    for (final c in ['SEALED', 'OPENED', 'DAMAGED', 'FAULTY']) {
      final chip = tester.widget<ChoiceChip>(find.byKey(Key('returns-condition-v-1-$c')));
      expect(chip.selected, isFalse);
    }
    await _tap(tester, find.byKey(const Key('returns-submit')));

    expect(find.text('Say what condition each returned item is in.'), findsOneWidget);
    expect(server.posts('/returns'), isEmpty);
  });

  testWidgets('sends the refund with its condition, under a key', (tester) async {
    final server = _Server();
    await _pump(tester, server);
    await _findSale(tester);
    await _oneJamBack(tester, condition: 'OPENED');
    await _tap(tester, find.byKey(const Key('returns-submit')));

    final post = server.posts('/orders/o-1/returns').single;
    final body = post.data as Map<String, dynamic>;
    expect(body['refundMethod'], 'ORIGINAL');
    expect(body['items'], [
      {'variantId': 'v-1', 'qty': 1, 'condition': 'OPENED'}
    ]);
    expect(isV7(post.headers['Idempotency-Key'] as String), isTrue);
    expect(find.byKey(const Key('returns-done')), findsOneWidget);
    expect(find.text('Return recorded'), findsOneWidget);
  });

  testWidgets('a retry of the same submit sends the same key', (tester) async {
    final server = _Server();
    server.returnReplies.addAll([
      _Reply(503, _problem(503, 'SERVICE_UNAVAILABLE', 'Try again shortly.')),
      const _Reply(201, '{"data":{"refundAmount":6.0}}'),
    ]);
    await _pump(tester, server);
    await _findSale(tester);
    await _oneJamBack(tester);
    await _tap(tester, find.byKey(const Key('returns-submit')));
    expect(find.byKey(const Key('returns-error')), findsOneWidget);

    await _tap(tester, find.byKey(const Key('returns-submit')));
    final posts = server.posts('/orders/o-1/returns');
    expect(posts, hasLength(2));
    expect(posts[1].headers['Idempotency-Key'], posts[0].headers['Idempotency-Key']);
    expect(find.byKey(const Key('returns-done')), findsOneWidget);
  });

  testWidgets('a changed submit gets a new key', (tester) async {
    final server = _Server();
    server.returnReplies.addAll([
      _Reply(422, _problem(422, 'ORDER_RETURN_INVALID', 'Nope.')),
      const _Reply(201, '{"data":{"refundAmount":12.0}}'),
    ]);
    await _pump(tester, server);
    await _findSale(tester);
    await _oneJamBack(tester);
    await _tap(tester, find.byKey(const Key('returns-submit')));
    await _tap(tester, find.byKey(const Key('returns-inc-v-1')));
    await _tap(tester, find.byKey(const Key('returns-submit')));

    final posts = server.posts('/orders/o-1/returns');
    expect(posts[1].headers['Idempotency-Key'], isNot(posts[0].headers['Idempotency-Key']));
  });

  testWidgets('store credit is offered only when the sale names a customer',
      (tester) async {
    final without = _Server();
    await _pump(tester, without);
    await _findSale(tester);
    await _oneJamBack(tester);
    expect(
        tester
            .widget<ChoiceChip>(find.byKey(const Key('returns-method-STORE_CREDIT')))
            .onSelected,
        isNull);
    expect(find.byKey(const Key('returns-store-credit-why')), findsOneWidget);
  });

  testWidgets('a sale that names a customer can be refunded to store credit',
      (tester) async {
    final server = _Server(customerId: 'c-1');
    await _pump(tester, server);
    await _findSale(tester);
    await _oneJamBack(tester);
    await _tap(tester, find.byKey(const Key('returns-method-STORE_CREDIT')));
    await _tap(tester, find.byKey(const Key('returns-submit')));

    final body = server.posts('/orders/o-1/returns').single.data as Map<String, dynamic>;
    expect(body['refundMethod'], 'STORE_CREDIT');
  });

  testWidgets('a return that needs a manager says why, in words', (tester) async {
    final server = _Server();
    server.returnReplies.add(_Reply(
        403,
        _problem(403, 'ORDER_RETURN_NEEDS_MANAGER', 'A manager must take this.',
            details: ['reason=WINDOW', 'reason=CEILING'])));
    await _pump(tester, server);
    await _findSale(tester);
    await _oneJamBack(tester);
    await _tap(tester, find.byKey(const Key('returns-submit')));

    expect(find.byKey(const Key('returns-needs-manager')), findsOneWidget);
    expect(find.text("· Past the business's return window"), findsOneWidget);
    expect(find.text("· Over the cashier's refund limit"), findsOneWidget);
    expect(find.byKey(const Key('returns-done')), findsNothing);
  });

  group('exchange', () {
    testWidgets(
        'sends the return and the new items, then goes to the tender screen '
        'for what the customer owes', (tester) async {
      final server = _Server(customerId: 'c-1');
      server.exchangeReplies.add(const _Reply(
          201,
          '{"data":{"return":{"id":"r-1"},"order":{"id":"o-new","currency":"GBP"},'
          '"exchangeAmount":6.0,"dueFromCustomer":4.5,"refundToCustomer":0}}'));
      final container = await _pump(tester, server);
      await _findSale(tester);
      await _oneJamBack(tester);
      await _tap(tester, _actionSegment('Exchange'));
      await _addNewItem(tester, 'MARM-1');
      expect(find.byKey(const Key('returns-new-v-2')), findsOneWidget);

      await _tap(tester, find.byKey(const Key('returns-submit')));

      final post = server.posts('/orders/o-1/exchange').single;
      expect(post.data, {
        'reason': 'Customer return',
        'returnItems': [
          {'variantId': 'v-1', 'qty': 1, 'condition': 'SEALED'}
        ],
        'newItems': [
          {'variantId': 'v-2', 'qty': 1}
        ],
        'customerId': 'c-1',
      });
      expect(isV7(post.headers['Idempotency-Key'] as String), isTrue);
      expect(server.posts('/returns'), isEmpty,
          reason: 'an exchange is one request, not a refund plus a sale');

      expect(find.text('TENDER-SCREEN'), findsOneWidget);
      final settlement = container.read(posExchangeSettlementProvider)!;
      expect(settlement.orderId, 'o-new');
      expect(settlement.due, 4.5);
      expect(settlement.lines.single.name, 'Marmalade');
    });

    testWidgets('a cheaper exchange tells the cashier the rest goes back by itself',
        (tester) async {
      final server = _Server();
      server.exchangeReplies.add(const _Reply(
          201,
          '{"data":{"return":{"id":"r-1"},"order":{"id":"o-new","currency":"GBP"},'
          '"exchangeAmount":6.0,"dueFromCustomer":0,"refundToCustomer":2.5}}'));
      final container = await _pump(tester, server);
      await _findSale(tester);
      await _oneJamBack(tester);
      await _tap(tester, _actionSegment('Exchange'));
      await _addNewItem(tester, 'MARM-1');
      await _tap(tester, find.byKey(const Key('returns-submit')));

      expect(find.text('TENDER-SCREEN'), findsNothing);
      expect(find.byKey(const Key('returns-done')), findsOneWidget);
      expect(find.textContaining('goes back to how the customer paid'), findsOneWidget);
      expect(container.read(posExchangeSettlementProvider), isNull);
    });

    testWidgets('needs something to take instead', (tester) async {
      final server = _Server();
      await _pump(tester, server);
      await _findSale(tester);
      await _oneJamBack(tester);
      await _tap(tester, _actionSegment('Exchange'));
      await _tap(tester, find.byKey(const Key('returns-submit')));

      expect(find.text('Choose what the customer takes instead.'), findsOneWidget);
      expect(server.posts('/exchange'), isEmpty);
    });
  });

  group('no receipt', () {
    testWidgets('is not offered to a cashier', (tester) async {
      await _pump(tester, _Server());
      expect(find.byKey(const Key('returns-receipt-field')), findsOneWidget);
      expect(find.byKey(const Key('returns-mode')), findsNothing);
      expect(find.text('No receipt'), findsNothing);
    });

    testWidgets('is offered to a manager', (tester) async {
      await _pump(tester, _Server(), auth: _ManagerAuth.new);
      expect(find.byKey(const Key('returns-mode')), findsOneWidget);
    });

    testWidgets('a manager takes items back to a gift card, and the code is shown once',
        (tester) async {
      final server = _Server();
      server.noReceiptReplies.add(const _Reply(
          201,
          '{"data":{"refundAmount":6.0,"giftCard":{"id":"g-1","code":"GC-NEW-77","balance":6.0}}}'));
      await _pump(tester, server, auth: _ManagerAuth.new);
      await _tap(tester, find.text('No receipt'));
      await _addNewItem(tester, 'JAM-1');
      await _tap(tester, find.byKey(const Key('returns-condition-v-1-FAULTY')));
      await _tap(tester, find.byKey(const Key('returns-method-GIFT_CARD')));
      await _type(tester, find.byKey(const Key('returns-contact')), '07700900000');
      await _tap(tester, find.byKey(const Key('returns-submit')));

      final post = server.posts('/returns/no-receipt').single;
      expect(post.data, {
        'storeId': 'store-1',
        'reason': 'Customer return',
        'refundMethod': 'GIFT_CARD',
        'customerContact': '07700900000',
        'items': [
          {'variantId': 'v-1', 'qty': 1, 'condition': 'FAULTY'}
        ],
      });
      expect(isV7(post.headers['Idempotency-Key'] as String), isTrue);
      expect(find.text('GC-NEW-77'), findsOneWidget);
    });

    testWidgets('store credit needs a customer, and a contact is required',
        (tester) async {
      final server = _Server();
      await _pump(tester, server, auth: _ManagerAuth.new);
      await _tap(tester, find.text('No receipt'));
      await _addNewItem(tester, 'JAM-1');
      await _tap(tester, find.byKey(const Key('returns-condition-v-1-SEALED')));
      await _tap(tester, find.byKey(const Key('returns-submit')));
      expect(find.text('Choose the customer who gets the store credit.'), findsOneWidget);

      await _tap(tester, find.byKey(const Key('returns-method-GIFT_CARD')));
      await _tap(tester, find.byKey(const Key('returns-submit')));
      expect(find.textContaining('Enter the customer'), findsOneWidget);
      expect(server.posts('/returns/no-receipt'), isEmpty);
    });

    for (final c in const [
      ('ORDER_NO_RECEIPT_RETURNS_OFF', 409, 'does not take returns without a receipt'),
      ('ORDER_NO_RECEIPT_OVER_CEILING', 422, 'over the most this business gives back'),
      ('ORDER_NO_RECEIPT_METHOD_INVALID', 400, 'store credit or a gift card only'),
      ('ORDER_RETURN_STORE_CREDIT_NEEDS_CUSTOMER', 409, 'needs a customer'),
    ]) {
      testWidgets('${c.$1} reads as words', (tester) async {
        final server = _Server();
        server.noReceiptReplies.add(_Reply(c.$2, _problem(c.$2, c.$1, 'raw server text')));
        await _pump(tester, server, auth: _ManagerAuth.new);
        await _tap(tester, find.text('No receipt'));
        await _addNewItem(tester, 'JAM-1');
        await _tap(tester, find.byKey(const Key('returns-condition-v-1-SEALED')));
        await _tap(tester, find.byKey(const Key('returns-method-GIFT_CARD')));
        await _type(tester, find.byKey(const Key('returns-contact')), 'a@b.co');
        await _tap(tester, find.byKey(const Key('returns-submit')));

        expect(find.byKey(const Key('returns-error')), findsOneWidget);
        expect(find.textContaining(c.$3), findsOneWidget);
        expect(find.text('raw server text'), findsNothing);
      });
    }

    testWidgets('a no-receipt return that needs a manager names why', (tester) async {
      final server = _Server();
      server.noReceiptReplies.add(_Reply(
          403,
          _problem(403, 'ORDER_RETURN_NEEDS_MANAGER', 'A manager must take this.',
              details: ['reason=NO_RECEIPT'])));
      await _pump(tester, server, auth: _ManagerAuth.new);
      await _tap(tester, find.text('No receipt'));
      await _addNewItem(tester, 'JAM-1');
      await _tap(tester, find.byKey(const Key('returns-condition-v-1-SEALED')));
      await _tap(tester, find.byKey(const Key('returns-method-GIFT_CARD')));
      await _type(tester, find.byKey(const Key('returns-contact')), 'a@b.co');
      await _tap(tester, find.byKey(const Key('returns-submit')));

      expect(find.byKey(const Key('returns-needs-manager')), findsOneWidget);
      expect(find.text('· No receipt to find the sale by'), findsOneWidget);
    });
  });
  group('the drawer a refund comes out of', () {
    testWidgets('a refund names the open drawer, so its report counts the cash',
        (tester) async {
      final server = _Server();
      final container = await _pump(tester, server,
          overrides: [saleTillProvider.overrideWith(() => _Drawer('till-1'))]);
      // As the POS shell does from the moment the terminal is on.
      container.read(saleTillProvider);
      await tester.pumpAndSettle();
      await _findSale(tester);
      await _oneJamBack(tester);
      await _tap(tester, find.byKey(const Key('returns-submit')));

      final body =
          server.posts('/orders/o-1/returns').single.data as Map<String, dynamic>;
      expect(body['tillSessionId'], 'till-1');
      expect(body['refundMethod'], 'ORIGINAL');
    });

    testWidgets('with no drawer open it names none', (tester) async {
      final server = _Server();
      final container = await _pump(tester, server,
          overrides: [saleTillProvider.overrideWith(() => _Drawer(null))]);
      // As the POS shell does from the moment the terminal is on.
      container.read(saleTillProvider);
      await tester.pumpAndSettle();
      await _findSale(tester);
      await _oneJamBack(tester);
      await _tap(tester, find.byKey(const Key('returns-submit')));

      final body =
          server.posts('/orders/o-1/returns').single.data as Map<String, dynamic>;
      expect(body.containsKey('tillSessionId'), isFalse);
    });

    testWidgets(
        'a drawer the terminal could not read a moment ago is read again, '
        'so the refund still names it', (tester) async {
      final server = _Server()..openTill = ['down', 'till-7'];
      final container = await _pump(tester, server);
      container.read(saleTillProvider);
      await tester.pumpAndSettle();
      await _findSale(tester);
      await _oneJamBack(tester);
      await _tap(tester, find.byKey(const Key('returns-submit')));

      final body =
          server.posts('/orders/o-1/returns').single.data as Map<String, dynamic>;
      expect(body['tillSessionId'], 'till-7');
    });
  });
}
