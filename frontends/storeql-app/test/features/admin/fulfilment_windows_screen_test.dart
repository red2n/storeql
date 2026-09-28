import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/fulfilment_windows_screen.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Delivery and collection slots, set per store: the windows a store offers
// for delivery and for collection, listed by weekday, added and edited with
// 24-hour time pickers, a refused window worded by its own problem — and,
// since setting them is an owner or manager's decision, never offered to a
// storekeeper.
// ---------------------------------------------------------------------------

const _store = StoreInfo(
  id: 's1',
  name: 'Leeds',
  code: 'LDS',
  type: 'STORE',
  status: 'ACTIVE',
  timezone: 'Europe/Warsaw',
);

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  List<Map<String, dynamic>> windows;
  Map<String, dynamic>? refusal;

  _Server({List<Map<String, dynamic>>? windows}) : windows = windows ?? [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async {
    requests.add(o);
    if (o.method == 'GET' && o.path.endsWith('/admin/fulfilment-windows')) {
      return jsonResponse(jsonEncode({'data': windows}));
    }
    if (o.method == 'POST' && o.path.endsWith('/admin/fulfilment-windows')) {
      if (refusal != null) {
        return jsonResponse(jsonEncode({'error': refusal}), 400);
      }
      final body =
          (o.data is String ? jsonDecode(o.data as String) : o.data)
              as Map<String, dynamic>;
      final window = {'id': 'w-new', ...body};
      windows = [...windows, window];
      return jsonResponse(jsonEncode({'data': window}), 201);
    }
    if (o.method == 'PUT' && o.path.contains('/admin/fulfilment-windows/')) {
      if (refusal != null) {
        return jsonResponse(jsonEncode({'error': refusal}), 400);
      }
      final id = o.path.split('/').last;
      final body =
          (o.data is String ? jsonDecode(o.data as String) : o.data)
              as Map<String, dynamic>;
      final existing = windows.firstWhere((w) => w['id'] == id);
      final updated = {...existing, ...body};
      windows = [
        for (final w in windows)
          if (w['id'] == id) updated else w,
      ];
      return jsonResponse(jsonEncode({'data': updated}));
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _pump(
  WidgetTester tester, {
  List<Map<String, dynamic>>? windows,
  String role = 'OWNER',
}) async {
  tester.view.physicalSize = const Size(900, 1400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(windows: windows);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(FakeApiClient(dio)),
        authNotifierProvider.overrideWith(() => RoleAuth(role)),
      ],
      child: const MaterialApp(
        home: Scaffold(body: FulfilmentWindowsScreen(store: _store)),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return server;
}

Map<String, dynamic> _bodyOf(RequestOptions r) =>
    (r.data is String ? jsonDecode(r.data as String) : r.data)
        as Map<String, dynamic>;

Map<String, dynamic> _window({
  String id = 'w1',
  String type = 'DELIVERY',
  int weekday = 1,
  String start = '17:00',
  String end = '19:00',
  int capacity = 10,
  int cutoff = 60,
  bool active = true,
}) => {
  'id': id,
  'storeId': 's1',
  'fulfilmentType': type,
  'weekday': weekday,
  'startTime': start,
  'endTime': end,
  'capacity': capacity,
  'cutoffMinutes': cutoff,
  'active': active,
};

void main() {
  testWidgets(
    'lists the store\'s windows by weekday, and says whose time it is',
    (tester) async {
      await _pump(tester, windows: [_window()]);

      expect(find.textContaining('Monday'), findsOneWidget);
      expect(find.textContaining('17:00'), findsOneWidget);
      expect(find.textContaining('19:00'), findsOneWidget);
      expect(find.textContaining('Europe/Warsaw'), findsOneWidget);
    },
  );

  testWidgets('collection windows are listed apart from delivery', (
    tester,
  ) async {
    await _pump(
      tester,
      windows: [
        _window(id: 'w1', type: 'DELIVERY', weekday: 1),
        _window(
          id: 'w2',
          type: 'PICKUP',
          weekday: 2,
          start: '09:00',
          end: '12:00',
        ),
      ],
    );

    expect(find.textContaining('Monday'), findsOneWidget);
    expect(find.textContaining('Tuesday'), findsNothing);

    await tester.tap(find.text('Collection'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Tuesday'), findsOneWidget);
    expect(find.textContaining('Monday'), findsNothing);
  });

  testWidgets('adds a window with weekday, capacity, cut-off and active', (
    tester,
  ) async {
    final server = await _pump(tester, windows: []);
    expect(find.text('No delivery windows yet.'), findsOneWidget);

    await tester.tap(find.byKey(const Key('add-window')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('window-capacity')), '15');
    await tester.enterText(find.byKey(const Key('window-cutoff')), '45');
    await tester.tap(find.byKey(const Key('window-save')));
    await tester.pumpAndSettle();

    final post = server.requests.singleWhere(
      (r) => r.method == 'POST' && r.path.endsWith('/admin/fulfilment-windows'),
    );
    final body = _bodyOf(post);
    expect(body['storeId'], 's1');
    expect(body['fulfilmentType'], 'DELIVERY');
    expect(body['capacity'], 15);
    expect(body['cutoffMinutes'], 45);
    expect(body['active'], isTrue);
    expect(find.textContaining('Capacity 15'), findsOneWidget);
  });

  testWidgets('editing a window opens it filled in, and PUTs the change', (
    tester,
  ) async {
    final server = await _pump(tester, windows: [_window()]);

    await tester.tap(find.byKey(const Key('window-w1')));
    await tester.pumpAndSettle();
    expect(find.text('From 17:00'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('window-capacity')), '20');
    await tester.tap(find.byKey(const Key('window-save')));
    await tester.pumpAndSettle();

    final put = server.requests.singleWhere((r) => r.method == 'PUT');
    expect(put.path, endsWith('/admin/fulfilment-windows/w1'));
    final body = _bodyOf(put);
    expect(body['capacity'], 20);
    expect(find.textContaining('Capacity 20'), findsOneWidget);
  });

  testWidgets('a refused window is worded by its own problem', (tester) async {
    final server = await _pump(tester, windows: []);
    server.refusal = {
      'code': 'ORDER_SLOT_WINDOW_INVALID',
      'message': 'This window overlaps another active Monday delivery window.',
    };
    await tester.tap(find.byKey(const Key('add-window')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('window-save')));
    await tester.pumpAndSettle();

    expect(
      find.text('This window overlaps another active Monday delivery window.'),
      findsOneWidget,
    );
  });

  testWidgets('is not offered to a storekeeper', (tester) async {
    await _pump(tester, windows: [_window()], role: 'STOREKEEPER');

    expect(find.byKey(const Key('slots-not-offered')), findsOneWidget);
    expect(find.textContaining('Monday'), findsNothing);
    expect(find.byKey(const Key('add-window')), findsNothing);
  });

  testWidgets('a manager (not just an owner) may set windows', (tester) async {
    await _pump(tester, windows: [_window()], role: 'MANAGER');

    expect(find.byKey(const Key('slots-not-offered')), findsNothing);
    expect(find.textContaining('Monday'), findsOneWidget);
  });
}
