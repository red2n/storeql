import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/features/platform/platform_shell.dart';
import 'package:storeql_app/features/platform/security_incidents_screen.dart';

import '../support/app_router_harness.dart';

// ---------------------------------------------------------------------------
// The app's router, platform shell: one security incident has an address of
// its own inside the shell (`/platform/security/:id`), so it can be linked to
// and reloaded, and back — the page's or the browser's — is the register.
// ---------------------------------------------------------------------------

const _incidents = '/tenant-svc/platform/security-incidents';

final _replies = {
  'GET $_incidents': jsonEncode({
    'data': [
      {'id': 'i3', 'kind': 'SEVERE_INCIDENT', 'title': 'Token leak', 'awareAt': '2026-09-14T06:00:00Z',
        'status': 'OPEN', 'overdue': false},
    ],
  }),
  'GET $_incidents/i3': jsonEncode({
    'data': {
      'id': 'i3', 'kind': 'SEVERE_INCIDENT', 'title': 'Token leak', 'summary': 'Tokens leaked',
      'awareAt': '2026-09-14T06:00:00Z', 'affectsAllTenants': true, 'tenantIds': [], 'status': 'OPEN',
      'stages': [], 'events': [], 'noticesIssued': 0, 'noticesAcknowledged': 0,
    },
  }),
};

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('a link to an incident opens it inside the platform shell, and back is the register',
      (tester) async {
    final router = await followLink(tester, '/platform/security/i3', role: 'PLATFORM_ADMIN', replies: _replies);

    expect(router.state.uri.path, '/platform/security/i3');
    expect(find.byType(PlatformShell), findsOneWidget);
    expect(find.byType(SecurityIncidentDetailScreen), findsOneWidget);
    expect(find.text('Tokens leaked'), findsOneWidget);
    expect(tester.widget<NavigationRail>(find.byType(NavigationRail)).selectedIndex, 4,
        reason: 'Security incidents stays the selected destination');

    await tester.tap(find.byKey(const Key('incident-back')));
    await tester.pumpAndSettle();
    expect(router.state.uri.path, '/platform/security');
    expect(find.byType(SecurityIncidentsScreen), findsOneWidget);
    expect(find.text('Token leak'), findsOneWidget);

    // And forward again from the register, to the same address.
    await tester.tap(find.byKey(const Key('incident-i3')));
    await tester.pumpAndSettle();
    expect(router.state.uri.path, '/platform/security/i3');
    expect(find.byType(SecurityIncidentDetailScreen), findsOneWidget);
  });
}
