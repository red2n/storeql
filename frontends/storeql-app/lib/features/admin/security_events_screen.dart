import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/util/status_labels.dart';
import '../../shared/widgets/adaptive_filters.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import 'paged_log.dart';
import 'providers/admin_providers.dart';
import 'providers/staff_names.dart';
import 'widgets/business_wide_note.dart';

// The security events of the business's own logins (iam-svc, 30 Sep 2026): who
// signed in or failed to, whose second step locked or was reset, whose password
// changed, which API key or single-sign-on connection was made or removed —
// newest first, in words. Read by an owner or a manager held to no store; the
// server refuses anyone else (a manager held to stores is told who reads it).
// Read-only: the trail is append-only and nothing here can change a row.

/// What each recorded security event says, in words. A type not listed reads as
/// [securityEventLabel]'s generic line, never as its code.
const securityEventLabels = <String, String>{
  'LOGIN_OK': 'Signed in',
  'LOGIN_FAILED': 'Sign-in failed',
  'LOGIN_BLOCKED_TENANT_INACTIVE': 'Sign-in blocked: the business is switched off',
  'LOGIN_REFUSED_SSO_REQUIRED': 'Password sign-in refused: this business signs in through single sign-on',
  'MFA_LOGIN_OK': 'Second step passed',
  'MFA_LOGIN_FAILED': 'Second step failed',
  'MFA_LOCKED': 'Second step locked after too many wrong answers',
  'MFA_STEP_UP_FAILED': 'Second step failed on a sensitive action',
  'MFA_ENROLMENT_REQUIRED': 'Asked to set up a second step',
  'MFA_TOTP_PROVISIONED': 'Authenticator app set-up started',
  'MFA_TOTP_ENROLLED': 'Authenticator app added',
  'MFA_PASSKEY_ADDED': 'Passkey added',
  'MFA_FACTOR_REMOVED': 'Second-step method removed',
  'MFA_RECOVERY_CODES_REGENERATED': 'Recovery codes replaced',
  'MFA_RECOVERY_CODE_USED': 'Recovery code used',
  'MFA_RESET_BY_ADMIN': 'Second step reset by an administrator',
  'MFA_RESET_SELF': 'Second step reset by the person themselves',
  'MFA_POLICY_CHANGED': 'Second-step policy changed',
  'PASSWORD_CHANGED': 'Password changed',
  'PASSWORD_RESET_REQUESTED': 'Password reset requested',
  'PASSWORD_RESET': 'Password reset',
  'SESSIONS_REVOKED_ALL': 'Signed out everywhere',
  'REFRESH_REUSE_DETECTED': 'A used sign-in was presented again',
  'API_KEY_CREATED': 'API key created',
  'API_KEY_REVOKED': 'API key revoked',
  'SSO_CONNECTION_CHANGED': 'Single sign-on connection changed',
  'SSO_CONNECTION_REMOVED': 'Single sign-on connection removed',
  'SSO_LINKED': 'Login linked to single sign-on',
  'SSO_UNLINKED': 'Login unlinked from single sign-on',
  'SSO_LOGIN_OK': 'Signed in through single sign-on',
  'SSO_LOGIN_PROVED': 'Identity proved through single sign-on',
  'SSO_LOGIN_REFUSED': 'Single sign-on sign-in refused',
  'SSO_SESSION_EXPIRED': 'Single sign-on session ended',
  'SSO_TICKET_REFUSED': 'Single sign-on hand-over refused',
  'SANDBOX_ENTERED': 'Entered the sandbox',
  'STAFF_PROVISIONED': 'Staff login created',
  'STAFF_PROVISIONED_REUSE': 'Existing login given staff access',
  'STAFF_BOUND': 'Staff assignment added',
  'STAFF_UNBOUND': 'Staff assignment removed',
  'OWNER_BOUND': 'Owner login set up',
  'BUSINESS_SIGNED_UP': 'Business signed up',
  'USER_REGISTERED': 'Account registered',
  'ACCOUNT_DELETED': 'Account deleted',
  'ACCOUNT_DELETE_REFUSED': 'Account deletion refused',
};

/// Events a person should look at twice: something refused, failed or locked.
const _alarming = {
  'LOGIN_FAILED',
  'LOGIN_BLOCKED_TENANT_INACTIVE',
  'LOGIN_REFUSED_SSO_REQUIRED',
  'MFA_LOGIN_FAILED',
  'MFA_LOCKED',
  'MFA_STEP_UP_FAILED',
  'REFRESH_REUSE_DETECTED',
  'SSO_LOGIN_REFUSED',
  'SSO_TICKET_REFUSED',
  'ACCOUNT_DELETE_REFUSED',
};

/// A recorded event type in words; a type this app has no words for reads as a
/// generic line carrying its own words, never the raw code.
String securityEventLabel(String type) =>
    securityEventLabels[type] ?? 'Security event: ${humanizeCode(type).toLowerCase()}';

/// The safe detail the server shows for some events (a method, a key's name),
/// as words: a code such as `TOTP` reads `Totp`… never the raw constant.
String _detailWords(String detail) =>
    RegExp(r'^[A-Z0-9_]+$').hasMatch(detail) ? humanizeCode(detail) : detail;

class SecurityEvent {
  final String id;
  final String type;
  final String? userId;
  final String? email;
  final String? detail;
  final DateTime? at;

  const SecurityEvent({
    required this.id,
    required this.type,
    this.userId,
    this.email,
    this.detail,
    this.at,
  });

  factory SecurityEvent.fromJson(Map<String, dynamic> j) => SecurityEvent(
        id: j['id'] as String? ?? '',
        type: j['type'] as String? ?? '',
        userId: j['userId'] as String?,
        email: j['email'] as String?,
        detail: j['detail'] as String?,
        at: DateTime.tryParse(j['at'] as String? ?? ''),
      );
}

/// The screen's filters: one kind of event or all, one login or anyone, a
/// period (days, inclusive). The whole filter is the provider family's key.
class SecurityEventFilter {
  final DateTime from;
  final DateTime to;
  final String? type;
  final String? userId;

  const SecurityEventFilter({required this.from, required this.to, this.type, this.userId});

  factory SecurityEventFilter.lastThirtyDays() {
    final today = DateTime.now();
    final day = DateTime(today.year, today.month, today.day);
    return SecurityEventFilter(from: day.subtract(const Duration(days: 29)), to: day);
  }

  static const _unset = Object();

  SecurityEventFilter copyWith({DateTime? from, DateTime? to, Object? type = _unset, Object? userId = _unset}) =>
      SecurityEventFilter(
        from: from ?? this.from,
        to: to ?? this.to,
        type: type == _unset ? this.type : type as String?,
        userId: userId == _unset ? this.userId : userId as String?,
      );

  int get activeCount {
    final initial = SecurityEventFilter.lastThirtyDays();
    return [from != initial.from || to != initial.to, type != null, userId != null].where((on) => on).length;
  }

  Map<String, dynamic> get query => {
        'from': from.toUtc().toIso8601String(),
        // The API's upper bound is exclusive; the picker's is a day, inclusive.
        'to': to.add(const Duration(days: 1)).toUtc().toIso8601String(),
        if (type != null) 'type': type,
        if (userId != null) 'userId': userId,
      };

  @override
  bool operator ==(Object other) =>
      other is SecurityEventFilter &&
      other.from == from &&
      other.to == to &&
      other.type == type &&
      other.userId == userId;

  @override
  int get hashCode => Object.hash(from, to, type, userId);
}

final securityEventFilterProvider =
    StateProvider.autoDispose<SecurityEventFilter>((ref) => SecurityEventFilter.lastThirtyDays());

final securityEventsProvider = StateNotifierProvider.autoDispose
    .family<PagedLogNotifier<SecurityEvent>, PagedLog<SecurityEvent>, SecurityEventFilter>(
  (ref, filter) => PagedLogNotifier<SecurityEvent>((after) async {
    final resp = await ref.read(apiClientProvider).dio.get(
      '/${ApiConstants.iam}/auth/admin/security-events',
      queryParameters: {...filter.query, 'limit': 50, 'after': ?after},
    );
    final data = resp.data['data'];
    final rows = (data is Map ? data['items'] : data) as List? ?? const [];
    final next = (data is Map ? data['nextCursor'] : null) as String? ??
        (resp.data['meta'] as Map?)?['nextCursor'] as String?;
    return (
      [for (final e in rows) SecurityEvent.fromJson((e as Map).cast<String, dynamic>())],
      next,
    );
  }),
);

/// Security events (owner and head-office manager).
class SecurityEventsScreen extends ConsumerWidget {
  const SecurityEventsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    // Until the sign-in is known nothing is asked: a manager held to stores is
    // not sent to a page the server would refuse.
    if (auth is! AuthAuthenticated) return const LoadingView(label: 'Loading…');
    if (heldToStores(auth)) {
      // The trail is the whole business's; the server would refuse it anyway.
      return ListView(padding: context.pagePadding, children: const [
        PageHeader(title: 'Security events', padding: EdgeInsetsDirectional.only(bottom: AppSpacing.lg)),
        EmptyState(
          icon: Icons.lock_outline,
          title: 'Not open to you',
          message: 'Only an owner or a head-office manager reads the security events.',
        ),
      ]);
    }
    final filter = ref.watch(securityEventFilterProvider);
    final log = ref.watch(securityEventsProvider(filter));
    final staff = ref.watch(staffProvider).value ?? const <StaffMember>[];
    void setFilter(SecurityEventFilter f) => ref.read(securityEventFilterProvider.notifier).state = f;
    String day(DateTime d) => AppFormat.date(d.toIso8601String());

    // People by their login, not their id: the server names each event's login
    // (its address); the Who menu names the staff list the same way.
    final logins = ref.watch(staffNameCacheProvider);
    Future.microtask(() => ref.read(staffNameCacheProvider.notifier).resolve([for (final s in staff) s.userId]));
    final people = <String, String>{};
    for (final s in staff) {
      people.putIfAbsent(s.userId, () => staffDisplayName(s.userId, logins));
    }

    Future<void> pickRange() async {
      final picked = await showDateRangePicker(
        context: context,
        firstDate: DateTime(2020),
        lastDate: DateTime.now().add(const Duration(days: 1)),
        initialDateRange: DateTimeRange(start: filter.from, end: filter.to),
      );
      if (picked != null) {
        setFilter(filter.copyWith(
          from: DateTime(picked.start.year, picked.start.month, picked.start.day),
          to: DateTime(picked.end.year, picked.end.month, picked.end.day),
        ));
      }
    }

    return ListView(
      padding: context.pagePadding,
      children: [
        const PageHeader(
          title: 'Security events',
          subtitle: 'Sign-ins and failures, second-step lock-outs and resets, password changes, '
              'API keys and single sign-on for this business\'s logins, newest first. '
              'Every row is an append-only record; nothing here can change one.',
          padding: EdgeInsetsDirectional.only(bottom: AppSpacing.lg),
        ),
        AdaptiveFilters(
          activeCount: filter.activeCount,
          onClear: () => setFilter(SecurityEventFilter.lastThirtyDays()),
          children: [
            SizedBox(
              width: 280,
              child: DropdownButtonFormField<String?>(
                key: const Key('security-type'),
                isExpanded: true,
                initialValue: filter.type,
                decoration: const InputDecoration(labelText: 'What happened'),
                items: [
                  const DropdownMenuItem<String?>(value: null, child: Text('Everything')),
                  for (final e in securityEventLabels.entries)
                    DropdownMenuItem<String?>(
                        value: e.key, child: Text(e.value, overflow: TextOverflow.ellipsis)),
                ],
                onChanged: (v) => setFilter(filter.copyWith(type: v)),
              ),
            ),
            SizedBox(
              width: 240,
              child: DropdownButtonFormField<String?>(
                key: const Key('security-user'),
                isExpanded: true,
                initialValue: people.containsKey(filter.userId) ? filter.userId : null,
                decoration: const InputDecoration(labelText: 'Who'),
                items: [
                  const DropdownMenuItem<String?>(value: null, child: Text('Anyone')),
                  for (final e in people.entries)
                    DropdownMenuItem<String?>(value: e.key, child: Text(e.value, overflow: TextOverflow.ellipsis)),
                ],
                onChanged: (v) => setFilter(filter.copyWith(userId: v)),
              ),
            ),
            OutlinedButton.icon(
              key: const Key('security-period'),
              onPressed: pickRange,
              icon: const Icon(Icons.date_range),
              label: Text('${day(filter.from)} – ${day(filter.to)}'),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.lg),
        if (log.loading)
          const LoadingView(label: 'Loading the security events…')
        else if (log.error != null && log.items.isEmpty)
          ErrorView(
            message: friendlyError(log.error!, fallback: 'Could not load the security events.'),
            onRetry: () => ref.read(securityEventsProvider(filter).notifier).refresh(),
          )
        else if (log.items.isEmpty)
          const EmptyState(
            icon: Icons.shield_outlined,
            title: 'Nothing recorded in this period',
            message: 'No security event was recorded for this business\'s logins in these days.',
          )
        else ...[
          Card(
            child: Column(children: [
              for (final e in log.items) _EventRow(event: e, name: _who(e, logins)),
            ]),
          ),
          if (log.error != null)
            Padding(
              padding: const EdgeInsetsDirectional.only(top: AppSpacing.sm),
              child: Text(friendlyError(log.error!, fallback: 'Could not load older events.'),
                  style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ),
          if (log.hasMore)
            Padding(
              padding: const EdgeInsetsDirectional.only(top: AppSpacing.md),
              child: Center(
                child: log.loadingMore
                    ? const CircularProgressIndicator()
                    : TextButton.icon(
                        key: const Key('security-load-older'),
                        onPressed: () => ref.read(securityEventsProvider(filter).notifier).loadOlder(),
                        icon: const Icon(Icons.expand_more),
                        label: const Text('Load older'),
                      ),
              ),
            ),
        ],
      ],
    );
  }

  /// The login an event concerns: its address, else the name already read for
  /// its id, else the end of the id, else that it names no login (a sign-in
  /// with an address nobody holds).
  static String _who(SecurityEvent e, Map<String, String> logins) {
    if ((e.email ?? '').isNotEmpty) return e.email!;
    if (e.userId != null) return logins[e.userId] ?? 'a login that no longer exists (${shortRef(e.userId!)})';
    return 'no known login';
  }
}

class _EventRow extends StatelessWidget {
  const _EventRow({required this.event, required this.name});

  final SecurityEvent event;
  final String name;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final alarm = _alarming.contains(event.type);
    final when = event.at == null ? '' : AppFormat.dateTime(event.at!.toIso8601String());
    final detail = (event.detail ?? '').trim();
    return ListTile(
      key: Key('security-event-${event.id}'),
      leading: Icon(alarm ? Icons.gpp_maybe_outlined : Icons.shield_outlined,
          color: alarm ? cs.error : cs.onSurfaceVariant),
      title: Text(securityEventLabel(event.type)),
      subtitle: Text([
        if (when.isNotEmpty) when,
        name,
        if (detail.isNotEmpty) _detailWords(detail),
      ].join(' · ')),
    );
  }
}
