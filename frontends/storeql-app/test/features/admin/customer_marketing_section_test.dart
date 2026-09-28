import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/customer_marketing_section.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// A customer's marketing channels, taken over the counter:
// withdrawing Marketing switches off every channel server-side; a channel may
// not be switched back on until it is granted again, worded the same way
// whether the switch is merely disabled or the server itself refuses one
// (409 MARKETING_PURPOSE_NOT_GRANTED).
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  bool marketingGranted;
  final Map<String, bool> channels;
  int? refuseChannelPutWith;

  _Server({required this.marketingGranted, this.channels = const {}});

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async {
    requests.add(o);
    if (o.method == 'GET' && o.path.endsWith('/privacy')) {
      return jsonResponse(
        jsonEncode({
          'data': {
            'child': false,
            'canTrack': true,
            'guardian': null,
            'consents': [
              {'purpose': 'MARKETING', 'granted': marketingGranted},
            ],
          },
        }),
      );
    }
    if (o.method == 'GET' && o.path.endsWith('/marketing')) {
      return jsonResponse(
        jsonEncode({
          'data': [
            for (final e in channels.entries)
              {'channel': e.key, 'granted': e.value},
          ],
        }),
      );
    }
    if (o.method == 'PUT' && o.path.endsWith('/privacy/consents')) {
      final choice = ((o.data as Map)['choices'] as List).single as Map;
      marketingGranted = choice['granted'] as bool;
      if (!marketingGranted) {
        channels.updateAll((_, _) => false);
      }
      return jsonResponse(jsonEncode({'data': <String, dynamic>{}}));
    }
    if (o.method == 'PUT' && o.path.endsWith('/marketing')) {
      if (refuseChannelPutWith != null) {
        return jsonResponse(
          jsonEncode({
            'error': {
              'code': 'MARKETING_PURPOSE_NOT_GRANTED',
              'message': "the person's MARKETING purpose stands withdrawn",
            },
          }),
          refuseChannelPutWith!,
        );
      }
      final change = ((o.data as Map)['channels'] as List).single as Map;
      channels[change['channel'] as String] = change['granted'] as bool;
      return jsonResponse(jsonEncode({'data': <String, dynamic>{}}));
    }
    return jsonResponse(
      jsonEncode({
        'error': {'code': 'NOT_FOUND', 'message': 'no route'},
      }),
      404,
    );
  }
}

Future<_Server> _pump(
  WidgetTester tester, {
  required bool marketingGranted,
  Map<String, bool> channels = const {},
}) async {
  final server = _Server(
    marketingGranted: marketingGranted,
    channels: Map.of(channels),
  );
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
      child: const MaterialApp(
        home: Scaffold(body: CustomerMarketingSection(customerId: 'cust-1')),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return server;
}

void main() {
  testWidgets(
    'Marketing off: every channel is disabled, with "Switch on Marketing first"',
    (tester) async {
      await _pump(tester, marketingGranted: false);

      for (final channel in const ['EMAIL', 'SMS', 'PHONE', 'POST']) {
        final tile = tester.widget<SwitchListTile>(
          find.byKey(Key('customer-marketing-$channel')),
        );
        expect(
          tile.onChanged,
          isNull,
          reason: '$channel cannot be switched on',
        );
        expect(
          find.descendant(
            of: find.byKey(Key('customer-marketing-$channel')),
            matching: find.text('Switch on Marketing first'),
          ),
          findsOneWidget,
        );
      }
    },
  );

  testWidgets(
    "the hint stays at full contrast, never the tile's own disabled dimming",
    (tester) async {
      await _pump(tester, marketingGranted: false);
      final theme = Theme.of(
        tester.element(find.byKey(const Key('customer-marketing-EMAIL'))),
      );
      final hint = tester.widget<Text>(
        find.byKey(const Key('customer-marketing-hint')).first,
      );
      expect(hint.style?.color, theme.colorScheme.onSurfaceVariant);
      expect(hint.style?.color, isNot(theme.disabledColor));
    },
  );

  testWidgets(
    'Marketing on: channels are free to switch, and a switch sends the channel and grant',
    (tester) async {
      final server = await _pump(tester, marketingGranted: true);
      final tile = tester.widget<SwitchListTile>(
        find.byKey(const Key('customer-marketing-EMAIL')),
      );
      expect(tile.onChanged, isNotNull);

      await tester.tap(find.byKey(const Key('customer-marketing-EMAIL')));
      await tester.pumpAndSettle();
      final put = server.requests.lastWhere((r) => r.method == 'PUT');
      expect(put.path, endsWith('/marketing'));
      final change = ((put.data as Map)['channels'] as List).single as Map;
      expect(change, {'channel': 'EMAIL', 'granted': true});
    },
  );

  testWidgets(
    'withdrawing Marketing re-reads the channels, shown off and disabled',
    (tester) async {
      await _pump(tester, marketingGranted: true, channels: {'EMAIL': true});
      expect(
        tester
            .widget<SwitchListTile>(
              find.byKey(const Key('customer-marketing-EMAIL')),
            )
            .value,
        isTrue,
      );

      await tester.tap(find.byKey(const Key('customer-marketing-purpose')));
      await tester.pumpAndSettle();

      final purpose = tester.widget<SwitchListTile>(
        find.byKey(const Key('customer-marketing-purpose')),
      );
      expect(purpose.value, isFalse);
      final email = tester.widget<SwitchListTile>(
        find.byKey(const Key('customer-marketing-EMAIL')),
      );
      expect(
        email.value,
        isFalse,
        reason: 'the server switched it off on the same transaction',
      );
      expect(email.onChanged, isNull);
      expect(find.byKey(const Key('customer-marketing-hint')), findsWidgets);
    },
  );

  testWidgets(
    'a 409 MARKETING_PURPOSE_NOT_GRANTED is worded the same, never the raw code',
    (tester) async {
      final server = await _pump(tester, marketingGranted: true);
      server.refuseChannelPutWith = 409;

      await tester.tap(find.byKey(const Key('customer-marketing-EMAIL')));
      await tester.pumpAndSettle();

      expect(find.text('Switch on Marketing first'), findsWidgets);
      expect(
        find.textContaining('MARKETING_PURPOSE_NOT_GRANTED'),
        findsNothing,
      );
      expect(find.textContaining('purpose stands withdrawn'), findsNothing);
    },
  );
}
