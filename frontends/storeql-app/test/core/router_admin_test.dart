import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/features/admin/admin_shell.dart';
import 'package:storeql_app/features/admin/messages_screen.dart';
import 'package:storeql_app/features/admin/pricing_screen.dart';
import 'package:storeql_app/features/admin/sales_screen.dart';
import 'package:storeql_app/features/platform/security_incidents_screen.dart';

import '../support/app_router_harness.dart';

// ---------------------------------------------------------------------------
// The app's router, admin shell: one message's editor has an address of its
// own inside the shell (`/admin/messages/:type`), so a reload, the browser's
// back button and a shared link all land where the person was — under the
// shell's one app bar, not a second one of its own.
// ---------------------------------------------------------------------------

const _messages = '/notification-svc/admin/notifications';

final _replies = {
  'GET $_messages/templates': jsonEncode({
    'data': [
      {
        'type': 'ORDER_CONFIRMED',
        'title': 'Order confirmed',
        'audience': 'CUSTOMER',
        'why': 'Sent to a shopper when their order is confirmed.',
        'variables': [],
        'forms': [
          {'form': 'EMAIL', 'subjectMax': 200, 'bodyMax': 20000, 'required': [], 'written': []},
        ],
      },
    ],
  }),
  'GET $_messages/template-settings': jsonEncode({
    'data': {'defaultLanguage': 'en', 'signedAs': 'Hollins Grocers'},
  }),
  'GET $_messages/templates/ORDER_CONFIRMED/EMAIL/en': jsonEncode({
    'data': {'source': 'DEFAULT', 'subject': 'Your order is confirmed', 'body': 'Thanks!'},
  }),
};

void main() {
  // Screens date what they show with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);
  testWidgets("a link to a message opens its editor inside the admin shell, under the shell's one app bar",
      (tester) async {
    final router = await followLink(tester, '/admin/messages/ORDER_CONFIRMED', role: 'OWNER', replies: _replies);

    expect(router.state.uri.path, '/admin/messages/ORDER_CONFIRMED');
    expect(find.byType(AdminShell), findsOneWidget);
    expect(find.byType(MessageEditorScreen), findsOneWidget);
    expect(find.text('Order confirmed'), findsOneWidget);
    expect(find.byType(AppBar), findsOneWidget, reason: "the editor adds no app bar under the shell's");

    // Back is the list, as the browser's back button would be.
    await tester.tap(find.byKey(const Key('message-editor-back')));
    await tester.pumpAndSettle();
    expect(router.state.uri.path, '/admin/messages');
    expect(find.byType(MessagesScreen), findsOneWidget);
    expect(find.byType(MessageEditorScreen), findsNothing);

    // And forward again from the list, to the same address.
    await tester.tap(find.byKey(const Key('messages-open-ORDER_CONFIRMED')));
    await tester.pumpAndSettle();
    expect(router.state.uri.path, '/admin/messages/ORDER_CONFIRMED');
    expect(find.byType(AppBar), findsOneWidget);
  });

  testWidgets('a storekeeper following a link to a message is sent home, as for the list', (tester) async {
    final router = await followLink(tester, '/admin/messages/ORDER_CONFIRMED', role: 'STOREKEEPER', replies: _replies);
    expect(router.state.uri.path, '/admin/inventory');
    expect(find.byType(MessageEditorScreen), findsNothing);
  });

  testWidgets("a business's staff cannot follow a link into the platform's incident register", (tester) async {
    final router = await followLink(tester, '/platform/security/i3', role: 'OWNER', replies: _replies);
    expect(router.state.uri.path, '/admin/dashboard');
    expect(find.byType(SecurityIncidentDetailScreen), findsNothing);
  });

  testWidgets('a link to one of Pricing\'s tabs opens Pricing on that tab', (tester) async {
    final router = await followLink(tester, '/admin/pricing?tab=vat-return', role: 'OWNER', replies: _replies);
    expect(router.state.uri.path, '/admin/pricing');
    expect(find.byType(PricingScreen), findsOneWidget);
    final tabs = DefaultTabController.of(tester.element(find.byType(TabBar)));
    expect(tabs.index, 3, reason: 'VAT Return');
  });

  testWidgets('a link to Sales tools\' receipts opens that tab', (tester) async {
    final router = await followLink(tester, '/admin/sales?tab=receipts', role: 'OWNER', replies: _replies);
    expect(router.state.uri.path, '/admin/sales');
    expect(find.byType(SalesScreen), findsOneWidget);
    final tabs = DefaultTabController.of(tester.element(find.byType(TabBar)));
    expect(tabs.index, 3, reason: 'Receipts');
  });

  testWidgets('Pricing with no tab, or one it does not have, opens on its first', (tester) async {
    await followLink(tester, '/admin/pricing?tab=nonsense', role: 'OWNER', replies: _replies);
    expect(DefaultTabController.of(tester.element(find.byType(TabBar))).index, 0);
  });
}
