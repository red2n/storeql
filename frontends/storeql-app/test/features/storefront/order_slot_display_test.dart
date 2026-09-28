import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/features/storefront/orders_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';

// ---------------------------------------------------------------------------
// The shopper's order history shows the window an order holds
// (delivery-and-collection-slots): the store's own local date and clock,
// exactly as the server sent them — two stores in two zones each show their
// own, with nothing converted in common.
// ---------------------------------------------------------------------------

const _london = 'order-ldn';
const _warsaw = 'order-waw';

class _SignedIn extends StorefrontAuthNotifier {
  _SignedIn() {
    state = const StorefrontAuthState(
      accessToken: 'tok',
      refreshToken: 'ref',
      email: 'sam@example.com',
    );
  }
}

class _Server extends Interceptor {
  @override
  void onRequest(RequestOptions o, RequestInterceptorHandler h) {
    if (o.path.endsWith('/orders/mine')) {
      h.resolve(
        Response(
          requestOptions: o,
          statusCode: 200,
          data: {
            'data': [
              {
                'id': _london,
                'storeId': 'store-ldn',
                'fulfilmentType': 'DELIVERY',
                'status': 'CONFIRMED',
                'total': 12.5,
                'currency': 'GBP',
                'createdAt': '2026-09-25T09:00:00Z',
                'slot': {
                  'date': '2026-09-27',
                  'startTime': '17:00',
                  'endTime': '19:00',
                  'timeZone': 'Europe/London',
                },
              },
              {
                'id': _warsaw,
                'storeId': 'store-waw',
                'fulfilmentType': 'PICKUP',
                'status': 'CONFIRMED',
                'total': 8.0,
                'currency': 'PLN',
                'createdAt': '2026-09-25T09:05:00Z',
                'slot': {
                  'date': '2026-09-27',
                  'startTime': '18:00',
                  'endTime': '20:00',
                  'timeZone': 'Europe/Warsaw',
                },
              },
            ],
          },
        ),
      );
      return;
    }
    if (o.path.endsWith('/orders/recall-notices/mine')) {
      h.resolve(
        Response(requestOptions: o, statusCode: 200, data: {'data': []}),
      );
      return;
    }
    h.reject(
      DioException(requestOptions: o, type: DioExceptionType.connectionError),
    );
  }
}

Future<void> _pump(WidgetTester tester) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        storefrontDioProvider.overrideWith((ref) {
          final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
          dio.interceptors.add(_Server());
          return dio;
        }),
        storefrontAuthProvider.overrideWith((ref) => _SignedIn()),
        storefrontStoresProvider.overrideWith(
          (ref) async => const [
            StoreSummary(id: 'store-ldn', name: 'London', showPrices: true),
            StoreSummary(id: 'store-waw', name: 'Warsaw', showPrices: true),
          ],
        ),
      ],
      child: const MaterialApp(home: Scaffold(body: StorefrontOrdersScreen())),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  setUpAll(initializeDateFormatting);
  // The windows below are read the British way (Sun 27 Sept): pinned, since the
  // app itself assumes no country for English.
  setUp(() => Intl.defaultLocale = 'en_GB');

  testWidgets(
    'each order shows its own store\'s window in its own local clock',
    (tester) async {
      await _pump(tester);

      // Worded with which kind of window it is, and the weekday — never bare
      // date and time alone.
      expect(find.textContaining('Delivery · Sun 27 Sept, 17:00–19:00'),
          findsOneWidget);
      expect(find.textContaining('Collection · Sun 27 Sept, 18:00–20:00'),
          findsOneWidget);
      // Never the raw ISO date or a device-side conversion.
      expect(find.textContaining('2026-09-27'), findsNothing);
    },
  );
}
