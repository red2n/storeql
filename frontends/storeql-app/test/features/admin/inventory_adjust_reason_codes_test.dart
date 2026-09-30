import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/inventory_screen.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Catalogue: inventory/inv-stock-adjustments-writeoffs gap 2. The Adjust Stock
// dialog offers the business's active reason codes, sends the picked code as
// `reasonCode` with the free text as `reason` (the note), and keeps the same
// Idempotency-Key for a retry of the same adjustment.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> posts = [];
  final String codes;
  final List<int> statuses;
  _Server({required this.codes, this.statuses = const [200]});

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    if (o.method == 'POST') {
      posts.add(o);
      final st = statuses[(posts.length - 1).clamp(0, statuses.length - 1)];
      return jsonResponse(
          st < 300 ? '{"data":{}}' : '{"error":{"message":"Try again."}}', st);
    }
    if (o.path.endsWith('/admin/inventory/reason-codes')) {
      if (codes == 'FAIL') return jsonResponse('{}', 500);
      return jsonResponse(codes);
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _open(WidgetTester tester, _Server server) async {
  tester.view.physicalSize = const Size(800, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<void>(
              context: context,
              builder: (_) => AdjustStockDialog(
                level: const InventoryLevel(
                    variantId: 'v-1',
                    storeId: 's-1',
                    onHand: 10,
                    reserved: 0,
                    available: 10),
                onDone: () {},
              ),
            ),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  return server;
}

const _catalogue = '{"data":['
    '{"id":"1","code":"THEFT","description":"Theft","active":true},'
    '{"id":"2","code":"DAMAGED","description":"","active":true},'
    '{"id":"3","code":"OLD","description":"Retired reason","active":false}]}';

Future<void> _submit(WidgetTester tester) async {
  await tester.tap(find.text('Adjust'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('offers the active reason codes, in words, and not a retired one',
      (tester) async {
    await _open(tester, _Server(codes: _catalogue));
    await tester.tap(find.byKey(const Key('adjust-reason-code')));
    await tester.pumpAndSettle();
    expect(find.text('Theft'), findsOneWidget);
    expect(find.text('Damaged'), findsOneWidget, reason: 'no description: the code in words');
    expect(find.text('Retired reason'), findsNothing);
    expect(find.text('THEFT'), findsNothing);
  });

  testWidgets('sends the picked code as reasonCode and the free text as the reason',
      (tester) async {
    final server = await _open(tester, _Server(codes: _catalogue));
    await tester.enterText(find.widgetWithText(TextFormField, 'Delta *'), '-2');
    await tester.tap(find.byKey(const Key('adjust-reason-code')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Theft').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextFormField, 'Note'), 'left at the door');
    await _submit(tester);
    expect(server.posts, hasLength(1));
    expect(server.posts.single.data, {
      'storeId': 's-1',
      'variantId': 'v-1',
      'delta': -2.0,
      'reasonCode': 'THEFT',
      'reason': 'left at the door',
    });
  });

  testWidgets('a list that cannot be read leaves the free-text note as it was',
      (tester) async {
    final server = await _open(tester, _Server(codes: 'FAIL'));
    expect(find.byKey(const Key('adjust-reason-code')), findsNothing);
    await tester.enterText(find.widgetWithText(TextFormField, 'Delta *'), '3');
    await tester.enterText(find.widgetWithText(TextFormField, 'Note'), 'found stock');
    await _submit(tester);
    expect(server.posts.single.data, {
      'storeId': 's-1',
      'variantId': 'v-1',
      'delta': 3.0,
      'reason': 'found stock',
    });
  });

  testWidgets('a retry of the same adjustment keeps its key; a changed one gets a new key',
      (tester) async {
    final server =
        await _open(tester, _Server(codes: _catalogue, statuses: const [500, 500, 200]));
    await tester.enterText(find.widgetWithText(TextFormField, 'Delta *'), '-1');
    await _submit(tester);
    await _submit(tester);
    expect(server.posts, hasLength(2));
    expect(server.posts[1].headers['Idempotency-Key'],
        server.posts[0].headers['Idempotency-Key']);
    await tester.enterText(find.widgetWithText(TextFormField, 'Delta *'), '-3');
    await _submit(tester);
    expect(server.posts, hasLength(3));
    expect(server.posts[2].headers['Idempotency-Key'],
        isNot(server.posts[0].headers['Idempotency-Key']));
  });
}
