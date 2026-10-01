// A price list's items come back a page at a time (pricing-svc pages them, 500 by default, with
// meta.nextCursor). The price-list screen and the selling-price map need every item, so both
// follow the cursor to the end — a list longer than one page is never shown cut short.
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/pricing_providers.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';

import '../../support/fake_api.dart';

/// Answers the items of one list in two pages; anything else is an empty list.
class _TwoPages implements HttpClientAdapter {
  final requests = <RequestOptions>[];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    Map<String, dynamic> body;
    if (o.path.endsWith('/price-lists/L1/items')) {
      final second = o.queryParameters['after'] == 'page-2';
      body = {
        'data': [
          for (var i = second ? 3 : 1; i <= (second ? 4 : 2); i++)
            {'id': 'i$i', 'variantId': 'v$i', 'price': i * 1.5, 'minQty': 1},
        ],
        'meta': {'nextCursor': second ? null : 'page-2'},
      };
    } else if (o.path.endsWith('/price-lists')) {
      body = {
        'data': [
          {'id': 'L1', 'name': 'Default', 'isDefault': true, 'currency': 'GBP'},
        ],
        'meta': {'nextCursor': null},
      };
    } else {
      body = {'data': [], 'meta': {}};
    }
    return ResponseBody.fromString(jsonEncode(body), 200, headers: {
      Headers.contentTypeHeader: ['application/json'],
    });
  }
}

void main() {
  test('the price-list screen reads every page of a list\'s items', () async {
    final server = _TwoPages();
    final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
    final container = ProviderContainer(overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
    ]);
    addTearDown(container.dispose);

    // Kept alive while it reads (an autoDispose provider with no listener is dropped mid-read).
    final sub = container.listen(priceListItemsProvider('L1'), (_, _) {});
    addTearDown(sub.close);
    final items = await container.read(priceListItemsProvider('L1').future);

    expect(items.map((i) => i.variantId), ['v1', 'v2', 'v3', 'v4']);
    final itemReads = server.requests.where((r) => r.path.endsWith('/items')).toList();
    expect(itemReads, hasLength(2));
    expect(itemReads.last.queryParameters['after'], 'page-2');
  });

  test('the selling-price map holds every item, not only the first page', () async {
    final server = _TwoPages();
    final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
    final container = ProviderContainer(overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
    ]);
    addTearDown(container.dispose);

    final sub = container.listen(variantPricesProvider, (_, _) {});
    addTearDown(sub.close);
    final prices = await container.read(variantPricesProvider.future);

    expect(prices.keys, containsAll(['v1', 'v2', 'v3', 'v4']));
    expect(prices['v4'], 6.0);
  });
}
