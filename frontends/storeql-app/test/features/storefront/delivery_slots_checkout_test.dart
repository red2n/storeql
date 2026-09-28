import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/format.dart';
import 'package:storeql_app/core/theme.dart';
import 'package:storeql_app/features/storefront/cart_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';

// ---------------------------------------------------------------------------
// Delivery and collection slots at checkout: seven days shown, a chosen
// window required before Place order where the store offers one, sent as
// slotWindowId + slotStartsAt, and re-read (choice cleared) when the window
// fills or closes meanwhile. A store with no windows (offered=false) checks
// out exactly as before.
//
// Never taps the checkout button without overriding storefrontDioProvider —
// doing so leaves a pending Dio timer. Where only the form/gating logic is at
// stake, the button is inspected rather than tapped through to the server.
// ---------------------------------------------------------------------------

const _store = '01a0e100-0000-7000-8000-00000000e001';

Map<String, dynamic> _slotsBody({
  required bool offered,
  bool full = false,
  String type = 'PICKUP',
}) => {
  'storeId': _store,
  'fulfilmentType': type,
  'timeZone': 'Europe/London',
  'offered': offered,
  'days': [
    {
      'date': '2026-09-26',
      'slots': [
        {
          'windowId': 'w1',
          'startsAt': '2026-09-26T17:00:00Z',
          'endsAt': '2026-09-26T19:00:00Z',
          'startTime': '17:00',
          'endTime': '19:00',
          'left': full ? 0 : 3,
          'full': full,
        },
      ],
    },
    for (var i = 1; i < 7; i++)
      {'date': '2026-09-${26 + i}', 'slots': const []},
  ],
};

class _SignedIn extends StorefrontAuthNotifier {
  _SignedIn() {
    state = const StorefrontAuthState(
      accessToken: 'tok',
      refreshToken: 'ref',
      email: 'sam@example.com',
    );
  }
}

/// order-svc and tenant-svc as checkout meets them. [slots] answers every
/// slots read; [orderResponses] is consumed one at a time per POST /orders
/// (a 409 first, then success, proves the picker re-reads and the retry
/// succeeds).
class _Server extends Interceptor {
  final List<RequestOptions> requests = [];
  Map<String, dynamic> Function() slots;
  final List<Response Function(RequestOptions)> orderResponses;
  int _orderCall = 0;

  _Server({required this.slots, required this.orderResponses});

  @override
  void onRequest(RequestOptions o, RequestInterceptorHandler h) {
    requests.add(o);
    if (o.method == 'GET' && o.path.endsWith('/storefront/fulfilment-slots')) {
      h.resolve(
        Response(requestOptions: o, statusCode: 200, data: {'data': slots()}),
      );
      return;
    }
    if (o.method == 'GET' && o.path.endsWith('/fulfilment/resolve')) {
      h.resolve(
        Response(
          requestOptions: o,
          statusCode: 200,
          data: {
            'data': {
              'storeId': _store,
              'storeName': 'Leeds',
              'storeCode': 'LDS',
              'pincode': 'LS1 5AB',
              'priority': 100,
            },
          },
        ),
      );
      return;
    }
    if (o.method == 'POST' && o.path.endsWith('/orders')) {
      final response =
          orderResponses[_orderCall.clamp(0, orderResponses.length - 1)](o);
      _orderCall++;
      if (response.statusCode != null && response.statusCode! >= 400) {
        h.reject(
          DioException(
            requestOptions: o,
            response: response,
            type: DioExceptionType.badResponse,
          ),
        );
      } else {
        h.resolve(response);
      }
      return;
    }
    h.reject(
      DioException(requestOptions: o, type: DioExceptionType.connectionError),
    );
  }
}

Response _ok201(RequestOptions o) => Response(
  requestOptions: o,
  statusCode: 201,
  data: {
    'data': {
      'id': 'ord-1',
      'status': 'PENDING',
      'storeId': _store,
      'total': 0,
      'currency': '',
    },
  },
);

/// Echoes the window the order was placed for, as the server's own answer
/// would (every order answer carries "slot").
Response _ok201WithSlot(RequestOptions o) => Response(
  requestOptions: o,
  statusCode: 201,
  data: {
    'data': {
      'id': 'ord-1',
      'status': 'PENDING',
      'storeId': _store,
      'total': 0,
      'currency': '',
      'slot': {
        'startsAt': '2026-09-26T17:00:00Z',
        'endsAt': '2026-09-26T19:00:00Z',
        'timeZone': 'Europe/London',
        'date': '2026-09-26',
        'startTime': '17:00',
        'endTime': '19:00',
      },
    },
  },
);

Response _slotFull409(RequestOptions o) => Response(
  requestOptions: o,
  statusCode: 409,
  data: {'code': 'ORDER_SLOT_FULL', 'detail': 'That window has just filled'},
);

Future<_Server> _pump(
  WidgetTester tester, {
  required Map<String, dynamic> Function() slots,
  List<Response Function(RequestOptions)>? orderResponses,
  String fulfilment = 'PICKUP',
  Size viewport = const Size(800, 1200),
  ThemeData? theme,
}) async {
  tester.view.physicalSize = viewport;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final server = _Server(
    slots: slots,
    orderResponses: orderResponses ?? [_ok201],
  );
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        storefrontConfigProvider.overrideWith(
          (ref) async =>
              const StorefrontConfig(showPrices: false, storeName: 'Leeds'),
        ),
        storefrontDioProvider.overrideWith((ref) {
          final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
          dio.interceptors.add(server);
          return dio;
        }),
        storefrontStoreProvider.overrideWith((ref) => _store),
        storefrontAuthProvider.overrideWith((ref) => _SignedIn()),
        serverOrdersProvider.overrideWith((ref) async => const []),
      ],
      child: MaterialApp(
          theme: theme, home: const Scaffold(body: StorefrontCartScreen())),
    ),
  );
  final el = tester.element(find.byType(StorefrontCartScreen).first);
  final cart = ProviderScope.containerOf(el).read(cartProvider.notifier);
  cart.add(
    CartLine(
      variantId: 'v1',
      productName: 'Bread',
      sku: 'BRD',
      unitPrice: 0,
      currency: '',
    ),
  );
  await tester.pumpAndSettle();
  if (fulfilment == 'DELIVERY') {
    await tester.tap(find.text('Deliver to home'));
    await tester.pumpAndSettle();
  } else {
    await tester.enterText(
      find.widgetWithText(TextField, 'Contact phone *'),
      '07700900000',
    );
    await tester.pump();
  }
  return server;
}

Map<String, dynamic> _lastOrderBody(_Server server) {
  final r = server.requests.lastWhere(
    (r) => r.method == 'POST' && r.path.endsWith('/orders'),
  );
  return (r.data is String ? jsonDecode(r.data as String) : r.data)
      as Map<String, dynamic>;
}

void main() {
  setUpAll(initializeDateFormatting);
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));

  testWidgets('offered=false leaves checkout exactly as before', (
    tester,
  ) async {
    await _pump(tester, slots: () => _slotsBody(offered: false));

    expect(find.textContaining('Pick a'), findsNothing);
    final button = tester.widget<FilledButton>(
      find.byKey(const Key('review-order-button')),
    );
    expect(
      button.onPressed,
      isNotNull,
      reason: 'no window is offered, so nothing is required',
    );
  });

  testWidgets('Place order stays disabled until a window is chosen', (
    tester,
  ) async {
    await _pump(tester, slots: () => _slotsBody(offered: true));

    expect(find.text('Today'), findsOneWidget);
    var button = tester.widget<FilledButton>(
      find.byKey(const Key('review-order-button')),
    );
    expect(
      button.onPressed,
      isNull,
      reason: 'the store offers windows and none is chosen yet',
    );
    expect(
      find.text('Pick a collection window before placing your order'),
      findsOneWidget,
    );

    await tester.ensureVisible(find.text('17:00–19:00'));
    await tester.tap(find.text('17:00–19:00'));
    await tester.pumpAndSettle();

    button = tester.widget<FilledButton>(
      find.byKey(const Key('review-order-button')),
    );
    expect(button.onPressed, isNotNull, reason: 'a window is now chosen');
  });

  testWidgets('placing the order sends slotWindowId and slotStartsAt', (
    tester,
  ) async {
    final server = await _pump(tester, slots: () => _slotsBody(offered: true));
    await tester.ensureVisible(find.text('17:00–19:00'));
    await tester.tap(find.text('17:00–19:00'));
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const Key('review-order-button')));
    await tester.tap(find.byKey(const Key('review-order-button')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Place order'));
    await tester.pumpAndSettle();

    final body = _lastOrderBody(server);
    expect(body['slotWindowId'], 'w1');
    expect(body['slotStartsAt'], '2026-09-26T17:00:00.000Z');
  });

  testWidgets(
    'the placed-order confirmation shows the window the order holds',
    (tester) async {
      await _pump(
        tester,
        slots: () => _slotsBody(offered: true),
        orderResponses: [_ok201WithSlot],
      );
      await tester.ensureVisible(find.text('17:00–19:00'));
      await tester.tap(find.text('17:00–19:00'));
      await tester.pumpAndSettle();

      await tester.ensureVisible(find.byKey(const Key('review-order-button')));
      await tester.tap(find.byKey(const Key('review-order-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Place order'));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('order-slot-label')), findsOneWidget);
      expect(find.textContaining('17:00–19:00'), findsWidgets);
    },
  );

  testWidgets('a window that filled meanwhile is worded, cleared and re-read', (
    tester,
  ) async {
    var full = false;
    await _pump(
      tester,
      slots: () => _slotsBody(offered: true, full: full),
      orderResponses: [_slotFull409, _ok201],
    );
    await tester.ensureVisible(find.text('17:00–19:00'));
    await tester.tap(find.text('17:00–19:00'));
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const Key('review-order-button')));
    await tester.tap(find.byKey(const Key('review-order-button')));
    await tester.pumpAndSettle();
    // The server will refuse this attempt: the window has just filled.
    full = true;
    await tester.tap(find.text('Place order'));
    await tester.pumpAndSettle();

    expect(
      find.text('That window has just filled — pick another.'),
      findsOneWidget,
    );
    // The choice is cleared and the picker re-read: the same occurrence now
    // shows Full, and Place order is disabled again until another is chosen.
    expect(find.text('17:00–19:00 · Full'), findsOneWidget);
    final button = tester.widget<FilledButton>(
      find.byKey(const Key('review-order-button')),
    );
    expect(button.onPressed, isNull);
  });

  testWidgets(
    'for delivery, the window offered is the postcode\'s resolved store\'s',
    (tester) async {
      final server = await _pump(
        tester,
        slots: () => _slotsBody(offered: true, type: 'DELIVERY'),
        fulfilment: 'DELIVERY',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Address line 1'),
        '1 Park Row',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'City'),
        'Leeds',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Postal code'),
        'LS1 5AB',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Recipient name'),
        'Sam Shopper',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Recipient phone'),
        '07700900123',
      );
      // The postcode resolve is debounced.
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pumpAndSettle();

      expect(
        server.requests.any(
          (r) =>
              r.method == 'GET' &&
              r.path.endsWith('/fulfilment/resolve') &&
              r.queryParameters['pincode'] == 'LS1 5AB',
        ),
        isTrue,
      );
      await tester.ensureVisible(find.text('17:00–19:00'));
      expect(find.text('17:00–19:00'), findsOneWidget);
    },
  );

  testWidgets('Review your order shows the chosen window', (tester) async {
    await _pump(tester, slots: () => _slotsBody(offered: true));
    await tester.ensureVisible(find.text('17:00–19:00'));
    await tester.tap(find.text('17:00–19:00'));
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const Key('review-order-button')));
    await tester.tap(find.byKey(const Key('review-order-button')));
    await tester.pumpAndSettle();

    expect(find.text('Collection window'), findsOneWidget);
    // The day as well as the time: the last look before paying names both,
    // written the reader's own way.
    final when = AppFormat.weekdayDate(DateTime(2026, 9, 26));
    expect(find.text('$when, 17:00–19:00'), findsOneWidget);

    await tester.tap(find.text('Back to cart'));
    await tester.pumpAndSettle();
  });

  testWidgets(
    'at 1200×800 the review button stays visible with the picker showing its windows',
    (tester) async {
      // The web cart's two-column layout: a 420px checkout column that used
      // to scroll a seven-day picker past the fold before Payment, the
      // summary and Review order — delivery-and-collection-slots' own bug.
      await _pump(
        tester,
        slots: () => _slotsBody(offered: true),
        viewport: const Size(1200, 800),
        theme: AppTheme.light,
      );

      final rect =
          tester.getRect(find.byKey(const Key('review-order-button')));
      expect(
        rect.bottom,
        lessThanOrEqualTo(800),
        reason: "the day-and-window picker must not push Review order "
            'below the fold of the checkout column',
      );
    },
  );
}
