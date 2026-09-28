import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/shared/widgets/status_badge.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/inventory_markdown_tab.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';

// ---------------------------------------------------------------------------
// Reduce to clear (05.4) and the ladder (03.9): the morning's plan for a store,
// a batch stickered at the ladder's step with the code the till will scan, the
// stickers taken off again, and the ladder edited by a manager.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

const _batch = 'b-1111';
const _variant = 'v-yog';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  bool inventoryReachable = true;
  bool stickered = false;
  bool cancelled = false;
  int createStatus = 201;

  @override
  void close({bool force = false}) {}

  String _markdown({required String status}) =>
      '{"id":"md-1","storeId":"st-1","variantId":"$_variant","batchId":"$_batch","batchNo":"B-7","expiryDate":"2026-09-14","qty":6,"redeemedQty":1,"remainingQty":5,'
      '"currency":"GBP","originalPrice":4.00,"markdownPrice":2.40,"percentOff":40.00,"reason":"SHORT_DATED","labelCode":"2100001002402","status":"$status","createdAt":"2026-09-12T07:00:00Z"'
      '${status == 'CANCELLED' ? ',"cancelReason":"wrong shelf"' : ''}}';

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async {
    requests.add(o);
    var body = '{"data":[]}';
    var status = 200;
    if (o.path.endsWith('/markdowns/plan')) {
      body = inventoryReachable
          ? '{"data":{"storeId":"st-1","withinDays":7,"ladderSource":"TENANT","inventoryReachable":true,"suggestions":['
                '{"batchId":"$_batch","variantId":"$_variant","batchNo":"B-7","expiryDate":"2026-09-14","daysToExpiry":2,"remainingQty":6,"currentPrice":4.00,"currency":"GBP","stepDays":2,"percentOff":40.00,"suggestedPrice":2.40${stickered ? ',"existing":${_markdown(status: 'ACTIVE')}' : ''}},'
                '{"batchId":"b-2222","variantId":"v-ham","batchNo":"B-8","expiryDate":"2026-09-18","daysToExpiry":6,"remainingQty":3,"currentPrice":3.00,"currency":"GBP"}'
                ']}}'
          : '{"data":{"storeId":"st-1","withinDays":7,"ladderSource":"DEFAULT","inventoryReachable":false,"suggestions":[]}}';
    } else if (o.path.endsWith('/markdowns/ladder') && o.method == 'GET') {
      body =
          '{"data":{"storeId":null,"source":"TENANT","steps":[{"daysToExpiry":5,"percentOff":20.00},{"daysToExpiry":2,"percentOff":40.00}]}}';
    } else if (o.path.endsWith('/markdowns/ladder') && o.method == 'PUT') {
      body =
          '{"data":{"storeId":"st-1","source":"STORE","steps":[{"daysToExpiry":2,"percentOff":50.00}]}}';
    } else if (o.path.endsWith('/markdowns') && o.method == 'POST') {
      status = createStatus;
      body = status == 201
          ? '{"data":${_markdown(status: 'ACTIVE')}}'
          : '{"error":{"code":"PRICING_MARKDOWN_NOT_A_REDUCTION","message":"5.00 is not below the current price 4.00"}}';
      if (status == 201) stickered = true;
    } else if (o.path.endsWith('/markdowns') && o.method == 'GET') {
      final wanted = o.queryParameters['status'];
      final mine = cancelled ? 'CANCELLED' : 'ACTIVE';
      body = (wanted == null || wanted == mine) && (stickered || cancelled)
          ? '{"data":[${_markdown(status: mine)}]}'
          : '{"data":[]}';
    } else if (o.path.endsWith('/markdowns/md-1/cancel')) {
      cancelled = true;
      body = '{"data":${_markdown(status: 'CANCELLED')}}';
    } else if (o.path.endsWith('/admin/products/variants/resolve')) {
      body =
          '{"data":[{"variantId":"$_variant","productName":"Greek yoghurt 500g","sku":"YOG"},{"variantId":"v-ham","productName":"Honey roast ham","sku":"HAM"}]}';
    }
    return ResponseBody.fromString(
      body,
      status,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }
}

Future<_Server> _open(
  WidgetTester tester, {
  bool reachable = true,
  bool stickered = false,
  Size size = const Size(1200, 1600),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server()
    ..inventoryReachable = reachable
    ..stickered = stickered;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
        storesProvider.overrideWith(
          (ref) async => const [
            StoreInfo(
              id: 'st-1',
              name: 'High Street',
              code: 'HS',
              type: 'STORE',
              status: 'ACTIVE',
              country: 'GB',
            ),
          ],
        ),
      ],
      child: const MaterialApp(home: Scaffold(body: InventoryMarkdownTab())),
    ),
  );
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('markdown-store')));
  await tester.pumpAndSettle();
  await tester.tap(find.text('High Street (HS)').last);
  await tester.pumpAndSettle();
  return server;
}

Map<String, dynamic> _json(RequestOptions o) => o.data is String
    ? jsonDecode(o.data as String) as Map<String, dynamic>
    : o.data as Map<String, dynamic>;

void main() {
  setUpAll(initializeDateFormatting);
  testWidgets('the plan lists what is expiring, priced off the ladder', (
    tester,
  ) async {
    await _open(tester);
    expect(
      find.textContaining('Greek yoghurt 500g · batch B-7'),
      findsOneWidget,
    );
    expect(
      find.textContaining('£4.00 → £2.40 (40 % off, 2-day step)'),
      findsOneWidget,
    );
    expect(find.textContaining('Honey roast ham · batch B-8'), findsOneWidget);
    expect(find.textContaining('no step on the ladder yet'), findsOneWidget);
    expect(find.byKey(const Key('markdown-sticker-$_batch')), findsOneWidget);
    expect(find.text('No stickers to show.'), findsOneWidget);
  });

  testWidgets(
    'when inventory cannot be read the plan says so rather than showing nothing',
    (tester) async {
      await _open(tester, reachable: false);
      expect(find.text('Inventory could not be read'), findsOneWidget);
    },
  );

  testWidgets(
    'stickering a batch at the ladder step issues the code the till scans',
    (tester) async {
      final server = await _open(tester);
      await tester.tap(find.byKey(const Key('markdown-sticker-$_batch')));
      await tester.pumpAndSettle();
      expect(find.text('Sticker at a lower price'), findsOneWidget);
      expect(
        find.textContaining('The ladder says 40 % off → £2.40'),
        findsOneWidget,
      );
      // The ladder's step and the whole batch are offered; the counter keeps them.
      await tester.tap(find.byKey(const Key('markdown-confirm')));
      await tester.pumpAndSettle();

      final post = server.requests.singleWhere(
        (r) => r.path.endsWith('/markdowns') && r.method == 'POST',
      );
      final body = _json(post);
      expect(body['storeId'], 'st-1');
      expect(body['variantId'], _variant);
      expect(body['batchId'], _batch);
      expect(body['batchNo'], 'B-7');
      expect(body['expiryDate'], '2026-09-14');
      expect(body['qty'], 6);
      expect(body['percentOff'], 40);
      expect(body.containsKey('markdownPrice'), isFalse);
      expect(body['reason'], 'SHORT_DATED');

      expect(find.text('Sticker issued'), findsOneWidget);
      expect(find.byKey(const Key('markdown-issued-code')), findsOneWidget);
      expect(find.text('2100001002402'), findsOneWidget);
      await tester.tap(find.byKey(const Key('markdown-done')));
      await tester.pumpAndSettle();

      // The plan now shows the sticker on the batch instead of offering another.
      expect(find.byKey(const Key('markdown-sticker-$_batch')), findsNothing);
      expect(find.textContaining('2100001002402 · £2.40'), findsWidgets);
      expect(find.byKey(const Key('markdown-md-1')), findsOneWidget);
      expect(find.textContaining('5 of 6 left'), findsOneWidget);
      // The sticker says its status in words, as a badge, and its dates as dates.
      expect(find.widgetWithText(StatusBadge, 'Active'), findsOneWidget);
      expect(find.text('ACTIVE'), findsNothing);
      expect(find.textContaining('GBP'), findsNothing);
    },
  );

  testWidgets(
    'a price instead of a percentage, and a refusal shown in the dialog',
    (tester) async {
      final server = await _open(tester)
        ..createStatus = 400;
      await tester.tap(find.byKey(const Key('markdown-sticker-$_batch')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('markdown-percent')), '');
      await tester.enterText(find.byKey(const Key('markdown-price')), '5.00');
      await tester.enterText(find.byKey(const Key('markdown-qty')), '2');
      await tester.tap(find.byKey(const Key('markdown-confirm')));
      await tester.pumpAndSettle();

      final post = server.requests.singleWhere(
        (r) => r.path.endsWith('/markdowns') && r.method == 'POST',
      );
      final body = _json(post);
      expect(body['markdownPrice'], 5.0);
      expect(body.containsKey('percentOff'), isFalse);
      expect(body['qty'], 2);
      expect(
        find.textContaining('not below the current price'),
        findsOneWidget,
      );
      expect(find.text('Sticker issued'), findsNothing);
    },
  );

  testWidgets('both a percentage and a price is not a sticker', (tester) async {
    final server = await _open(tester);
    await tester.tap(find.byKey(const Key('markdown-sticker-$_batch')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('markdown-price')), '2.00');
    await tester.tap(find.byKey(const Key('markdown-confirm')));
    await tester.pumpAndSettle();
    expect(
      find.text('Give a percentage off or a price, not both.'),
      findsOneWidget,
    );
    expect(server.requests.where((r) => r.method == 'POST'), isEmpty);
  });

  testWidgets(
    'taking the stickers off needs a reason and moves the markdown to taken off',
    (tester) async {
      final server = await _open(tester, stickered: true);
      expect(find.byKey(const Key('markdown-md-1')), findsOneWidget);
      await tester.tap(find.byKey(const Key('markdown-cancel-md-1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('markdown-cancel-confirm')));
      await tester.pumpAndSettle();
      expect(find.text('Say why the stickers are coming off.'), findsOneWidget);
      await tester.enterText(
        find.byKey(const Key('markdown-cancel-reason')),
        'wrong shelf',
      );
      await tester.tap(find.byKey(const Key('markdown-cancel-confirm')));
      await tester.pumpAndSettle();

      final post = server.requests.singleWhere(
        (r) => r.path.endsWith('/markdowns/md-1/cancel'),
      );
      expect(_json(post)['reason'], 'wrong shelf');
      // Off the active list; on the taken-off one.
      expect(find.byKey(const Key('markdown-md-1')), findsNothing);
      await tester.tap(find.text('Taken off'));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('markdown-md-1')), findsOneWidget);
      expect(find.textContaining('taken off: wrong shelf'), findsOneWidget);
      expect(find.byKey(const Key('markdown-cancel-md-1')), findsNothing);
    },
  );

  testWidgets(
    'the ladder is read and replaced, for the store or the business',
    (tester) async {
      final server = await _open(tester);
      await tester.tap(find.byKey(const Key('markdown-ladder')));
      await tester.pumpAndSettle();
      expect(find.text('Markdown ladder'), findsOneWidget);
      expect(find.byKey(const Key('ladder-days-0')), findsOneWidget);
      expect(find.byKey(const Key('ladder-days-1')), findsOneWidget);
      await tester.enterText(find.byKey(const Key('ladder-percent-1')), '50');
      await tester.tap(find.byKey(const Key('ladder-for-store')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('ladder-save')));
      await tester.pumpAndSettle();

      final put = server.requests.singleWhere((r) => r.method == 'PUT');
      final body = _json(put);
      expect(body['storeId'], 'st-1');
      expect(body['steps'], [
        {'daysToExpiry': 5, 'percentOff': 20.0},
        {'daysToExpiry': 2, 'percentOff': 50.0},
      ]);
      expect(find.text('Markdown ladder'), findsNothing);
    },
  );

  testWidgets('an empty ladder is refused before it is sent', (tester) async {
    final server = await _open(tester);
    await tester.tap(find.byKey(const Key('markdown-ladder')));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Remove step').first);
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Remove step').first);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('ladder-save')));
    await tester.pumpAndSettle();
    expect(find.text('A ladder needs at least one step.'), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);
  });

  testWidgets('on a phone the stickered heading and filter fit, and a sticker keeps its label',
      (tester) async {
    await _open(tester, stickered: true, size: const Size(390, 2400));
    expect(tester.takeException(), isNull);
    expect(find.text('Stickered'), findsOneWidget);
  });
}
