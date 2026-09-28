import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/theme.dart';
import 'package:storeql_app/features/storefront/accessibility_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';

// ---------------------------------------------------------------------------
// Who the accessibility statement speaks for, and how wide it reads.
//
// The European Accessibility Act puts the duty on the service provider — the
// business — so the statement opens with the business's name (Corner Stores
// Ltd), never the name of one of its shops (Leeds Road). And on the page that
// promises readable text, a line keeps to about 80 characters (WCAG 1.4.8):
// 552px of 14px body text, whatever the width of the window.
// ---------------------------------------------------------------------------

/// Answers GET /tenant-svc/storefront/stores with [stores], or refuses it.
Dio _stores(List<Map<String, dynamic>>? stores) {
  final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
  dio.interceptors.add(InterceptorsWrapper(onRequest: (o, h) {
    if (stores == null || o.path != '/tenant-svc/storefront/stores') {
      h.reject(DioException(requestOptions: o, type: DioExceptionType.connectionError));
      return;
    }
    h.resolve(Response(requestOptions: o, statusCode: 200, data: {'data': stores}));
  }));
  return dio;
}

Future<void> _pump(
  WidgetTester tester, {
  String? business,
  String storeName = 'Leeds Road',
  Size size = const Size(1280, 900),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final List<Override> overrides = [
    storefrontDioProvider.overrideWithValue(_stores(null)),
    storefrontConfigProvider.overrideWith(
        (ref) async => StorefrontConfig(showPrices: true, storeName: storeName)),
    storefrontBusinessNameProvider.overrideWith((ref) async => business),
  ];
  await tester.pumpWidget(ProviderScope(
    overrides: overrides,
    child: MaterialApp(
      theme: AppTheme.light,
      home: const Scaffold(body: StorefrontAccessibilityScreen()),
    ),
  ));
  await tester.pump();
  await tester.pump();
}

void main() {
  testWidgets('the statement opens with the business, never one of its stores',
      (tester) async {
    await _pump(tester, business: 'Corner Stores Ltd');
    expect(find.textContaining('Corner Stores Ltd wants everyone'), findsOneWidget);
    expect(find.textContaining('Leeds Road'), findsNothing);
  });

  testWidgets(
      'with the business not yet known it says "This shop", even when the store '
      'is known', (tester) async {
    await _pump(tester, business: null);
    expect(find.textContaining('This shop wants everyone'), findsOneWidget);
    expect(find.textContaining('Leeds Road'), findsNothing);
  });

  testWidgets(
      'on a desktop a line keeps to a reading measure — about 80 characters — '
      'and the column sits in the middle', (tester) async {
    await _pump(tester, business: 'Corner Stores Ltd');
    final opening = find.textContaining('wants everyone');
    final paragraph = tester.renderObject<RenderParagraph>(opening);
    expect(paragraph.constraints.maxWidth, lessThanOrEqualTo(552));
    final rect = tester.getRect(opening);
    // Centred: as much room on the start side as the end side leaves, give or take the text's own
    // ragged end.
    expect(rect.left, greaterThan(300));
  });

  testWidgets('on a phone the column takes the whole width, inside the gutters',
      (tester) async {
    await _pump(tester, business: 'Corner Stores Ltd', size: const Size(390, 844));
    final paragraph =
        tester.renderObject<RenderParagraph>(find.textContaining('wants everyone'));
    expect(paragraph.constraints.maxWidth, 390 - 2 * 16);
    expect(tester.takeException(), isNull);
  });

  group('the business name', () {
    Future<String?> read(Dio dio) async {
      final container = ProviderContainer(
          overrides: [storefrontDioProvider.overrideWithValue(dio)]);
      addTearDown(container.dispose);
      final sub = container.listen(storefrontBusinessNameProvider, (_, _) {});
      addTearDown(sub.close);
      // The name comes from the store list the switcher reads; let it answer
      // (or fail) first.
      try {
        await container
            .read(storefrontStoresProvider.future)
            .timeout(const Duration(milliseconds: 500));
      } catch (_) {}
      await Future<void>.delayed(Duration.zero);
      return container.read(storefrontBusinessNameProvider.future);
    }

    test('is read from the store list, with no request of its own', () async {
      var asked = 0;
      final dio = _stores([
        {'storeName': 'Leeds Road', 'businessName': 'Corner Stores Ltd'},
      ]);
      dio.interceptors.insert(0, InterceptorsWrapper(onRequest: (o, h) {
        asked++;
        h.next(o);
      }));
      expect(await read(dio), 'Corner Stores Ltd');
      expect(asked, 1);
    });

    test('is the business the storefront answers for, trimmed', () async {
      expect(
          await read(_stores([
            {'storeName': 'Leeds Road', 'businessName': '  Corner Stores Ltd '},
          ])),
          'Corner Stores Ltd');
    });

    test('is unknown — never a store name or a placeholder — when not sent',
        () async {
      expect(await read(_stores([{'storeName': 'Leeds Road'}])), isNull);
      expect(
          await read(_stores([
            {'storeName': 'Leeds Road', 'businessName': '-'}
          ])),
          isNull);
      expect(await read(_stores(const [])), isNull);
    });

    test('is unknown when the shop cannot be asked', () async {
      expect(await read(_stores(null)), isNull);
    });
  });
}
