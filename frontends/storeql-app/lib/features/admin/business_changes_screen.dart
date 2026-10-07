import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

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
import 'stores_screen.dart' show storeStatusLabel, storeTypeLabel;

// Changes to stores and staff (tenant-svc, 30 Sep 2026): who opened a store,
// moved its status or its till-phone setting, gave a person a role or took it
// away, defined, changed or deleted a role of the business's own — newest first,
// with what it was and what it became, at which store (or the whole business).
// Management only; a manager held to stores is sent only their stores' entries
// and the business-wide ones. Read-only: the log is written on the transaction
// of each change and never altered.

/// The change types, in words (also the Kind filter's options).
const businessChangeLabels = <String, String>{
  'STORE_CREATED': 'Store opened',
  'STORE_STATUS_CHANGED': 'Store status changed',
  'STORE_TILL_PHONE_CHANGED': 'Till phone setting changed',
  'STAFF_ASSIGNED': 'Staff assigned',
  'STAFF_UNASSIGNED': 'Staff removed',
  'ROLE_DEFINED': 'Role defined',
  'ROLE_CHANGED': 'Role changed',
  'ROLE_DELETED': 'Role deleted',
};

class BusinessChange {
  final String id;
  final String type;
  final String? actorId;
  final String? storeId;
  final String? subjectId;
  final String? subjectCode;
  final String? from;
  final String? to;
  final DateTime? at;

  const BusinessChange({
    required this.id,
    required this.type,
    this.actorId,
    this.storeId,
    this.subjectId,
    this.subjectCode,
    this.from,
    this.to,
    this.at,
  });

  factory BusinessChange.fromJson(Map<String, dynamic> j) => BusinessChange(
        id: j['id'] as String? ?? '',
        type: j['type'] as String? ?? '',
        actorId: j['actorId'] as String?,
        storeId: j['storeId'] as String?,
        subjectId: j['subjectId'] as String?,
        subjectCode: j['subjectCode'] as String?,
        from: j['from'] as String?,
        to: j['to'] as String?,
        at: DateTime.tryParse(j['occurredAt'] as String? ?? ''),
      );
}

/// The screen's filters: one kind of change or all, one person or anyone, one
/// store or all, a period (days, inclusive).
class BusinessChangeFilter {
  final DateTime from;
  final DateTime to;
  final String? type;
  final String? actorId;
  final String? storeId;

  const BusinessChangeFilter({required this.from, required this.to, this.type, this.actorId, this.storeId});

  factory BusinessChangeFilter.lastThirtyDays() {
    final today = DateTime.now();
    final day = DateTime(today.year, today.month, today.day);
    return BusinessChangeFilter(from: day.subtract(const Duration(days: 29)), to: day);
  }

  static const _unset = Object();

  BusinessChangeFilter copyWith(
          {DateTime? from, DateTime? to, Object? type = _unset, Object? actorId = _unset, Object? storeId = _unset}) =>
      BusinessChangeFilter(
        from: from ?? this.from,
        to: to ?? this.to,
        type: type == _unset ? this.type : type as String?,
        actorId: actorId == _unset ? this.actorId : actorId as String?,
        storeId: storeId == _unset ? this.storeId : storeId as String?,
      );

  int get activeCount {
    final initial = BusinessChangeFilter.lastThirtyDays();
    return [from != initial.from || to != initial.to, type != null, actorId != null, storeId != null]
        .where((on) => on)
        .length;
  }

  Map<String, dynamic> get query => {
        'from': from.toUtc().toIso8601String(),
        'to': to.add(const Duration(days: 1)).toUtc().toIso8601String(),
        if (type != null) 'type': type,
        if (actorId != null) 'actor': actorId,
        if (storeId != null) 'store': storeId,
      };

  @override
  bool operator ==(Object other) =>
      other is BusinessChangeFilter &&
      other.from == from &&
      other.to == to &&
      other.type == type &&
      other.actorId == actorId &&
      other.storeId == storeId;

  @override
  int get hashCode => Object.hash(from, to, type, actorId, storeId);
}

final businessChangeFilterProvider =
    StateProvider.autoDispose<BusinessChangeFilter>((ref) => BusinessChangeFilter.lastThirtyDays());

final businessChangesProvider = StateNotifierProvider.autoDispose
    .family<PagedLogNotifier<BusinessChange>, PagedLog<BusinessChange>, BusinessChangeFilter>(
  (ref, filter) => PagedLogNotifier<BusinessChange>((after) async {
    final resp = await ref.read(apiClientProvider).dio.get(
      '/${ApiConstants.tenant}/admin/tenant/audit',
      queryParameters: {...filter.query, 'limit': 50, 'after': ?after},
    );
    final rows = (resp.data['data'] as List?) ?? const [];
    final next = (resp.data['meta'] as Map?)?['nextCursor'] as String?;
    return (
      [for (final e in rows) BusinessChange.fromJson((e as Map).cast<String, dynamic>())],
      next,
    );
  }),
);

/// A role entry's `name (TIER): perm,perm` in words: *Shift lead, on Manager,
/// holding Sales void and Staff manage*. Text in another shape is shown as it is.
String roleChangeWords(String? text) {
  if (text == null || text.isEmpty) return '';
  final m = RegExp(r'^(.*) \(([A-Z_]+)\): ?(.*)$').firstMatch(text);
  if (m == null) return text;
  final perms = [
    for (final p in m.group(3)!.split(','))
      if (p.trim().isNotEmpty) humanizeCode(p.trim().replaceAll('.', '_')),
  ];
  return '${m.group(1)}, on ${humanizeCode(m.group(2))}, '
      '${perms.isEmpty ? 'holding nothing' : 'holding ${perms.join(', ')}'}';
}

/// Changes to stores and staff.
class BusinessChangesScreen extends ConsumerWidget {
  const BusinessChangesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final filter = ref.watch(businessChangeFilterProvider);
    final log = ref.watch(businessChangesProvider(filter));
    final stores = ref.watch(storesProvider).value ?? const <StoreInfo>[];
    final staff = ref.watch(staffProvider).value ?? const <StaffMember>[];
    final roles = ref.watch(rolesProvider).value ?? const <TenantRole>[];
    void setFilter(BusinessChangeFilter f) => ref.read(businessChangeFilterProvider.notifier).state = f;
    String day(DateTime d) => AppFormat.date(d.toIso8601String());

    // People by their login; names already read stay while the log reloads.
    final logins = ref.watch(staffNameCacheProvider);
    Future.microtask(() => ref.read(staffNameCacheProvider.notifier).resolve([
          for (final s in staff) s.userId,
          for (final e in log.items) ...[?e.actorId, ?e.subjectId],
        ]));
    final storeNames = {for (final s in stores) s.id: s.name};
    final roleNames = {for (final r in roles) r.code: r.name};
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
          title: 'Changes to stores and staff',
          subtitle: 'Who opened a store, changed its status or till setting, gave someone a role '
              'or took it away, or changed a role of the business\'s own — newest first. '
              'Every row is written with the change itself and never altered.',
          padding: EdgeInsetsDirectional.only(bottom: AppSpacing.lg),
        ),
        AdaptiveFilters(
          activeCount: filter.activeCount,
          onClear: () => setFilter(BusinessChangeFilter.lastThirtyDays()),
          children: [
            SizedBox(
              width: 220,
              child: DropdownButtonFormField<String?>(
                key: const Key('changes-type'),
                isExpanded: true,
                initialValue: filter.type,
                decoration: const InputDecoration(labelText: 'What changed'),
                items: [
                  const DropdownMenuItem<String?>(value: null, child: Text('Everything')),
                  for (final e in businessChangeLabels.entries)
                    DropdownMenuItem<String?>(value: e.key, child: Text(e.value, overflow: TextOverflow.ellipsis)),
                ],
                onChanged: (v) => setFilter(filter.copyWith(type: v)),
              ),
            ),
            SizedBox(
              width: 240,
              child: DropdownButtonFormField<String?>(
                key: const Key('changes-store'),
                isExpanded: true,
                initialValue: storeNames.containsKey(filter.storeId) ? filter.storeId : null,
                decoration: const InputDecoration(labelText: 'Store'),
                items: [
                  const DropdownMenuItem<String?>(value: null, child: Text('All stores')),
                  for (final s in stores) DropdownMenuItem<String?>(value: s.id, child: Text(s.name)),
                ],
                onChanged: (v) => setFilter(filter.copyWith(storeId: v)),
              ),
            ),
            SizedBox(
              width: 240,
              child: DropdownButtonFormField<String?>(
                key: const Key('changes-actor'),
                isExpanded: true,
                initialValue: people.containsKey(filter.actorId) ? filter.actorId : null,
                decoration: const InputDecoration(labelText: 'Who'),
                items: [
                  const DropdownMenuItem<String?>(value: null, child: Text('Anyone')),
                  for (final e in people.entries)
                    DropdownMenuItem<String?>(value: e.key, child: Text(e.value, overflow: TextOverflow.ellipsis)),
                ],
                onChanged: (v) => setFilter(filter.copyWith(actorId: v)),
              ),
            ),
            OutlinedButton.icon(
              key: const Key('changes-period'),
              onPressed: pickRange,
              icon: const Icon(Icons.date_range),
              label: Text('${day(filter.from)} – ${day(filter.to)}'),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.lg),
        if (log.loading)
          const LoadingView(label: 'Loading the changes…')
        else if (log.error != null && log.items.isEmpty)
          ErrorView(
            message: friendlyError(log.error!, fallback: 'Could not load the changes.'),
            onRetry: () => ref.read(businessChangesProvider(filter).notifier).refresh(),
          )
        else if (log.items.isEmpty)
          const EmptyState(
            icon: Icons.history_outlined,
            title: 'No changes in this period',
            message: 'No store or staff change was recorded in these days.',
          )
        else ...[
          Card(
            child: Column(children: [
              for (final e in log.items)
                _ChangeRow(
                  change: e,
                  person: (id) => staffDisplayName(id, logins),
                  storeName: (id) => storeNames[id] ?? 'a store (${shortRef(id)})',
                  roleName: (code) => roleNames[code] ?? humanizeCode(code),
                ),
            ]),
          ),
          if (log.error != null)
            Padding(
              padding: const EdgeInsetsDirectional.only(top: AppSpacing.sm),
              child: Text(friendlyError(log.error!, fallback: 'Could not load older changes.'),
                  style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ),
          if (log.hasMore)
            Padding(
              padding: const EdgeInsetsDirectional.only(top: AppSpacing.md),
              child: Center(
                child: log.loadingMore
                    ? const CircularProgressIndicator()
                    : TextButton.icon(
                        key: const Key('changes-load-older'),
                        onPressed: () => ref.read(businessChangesProvider(filter).notifier).loadOlder(),
                        icon: const Icon(Icons.expand_more),
                        label: const Text('Load older'),
                      ),
              ),
            ),
        ],
      ],
    );
  }
}

class _ChangeRow extends StatelessWidget {
  const _ChangeRow({required this.change, required this.person, required this.storeName, required this.roleName});

  final BusinessChange change;
  final String Function(String userId) person;
  final String Function(String storeId) storeName;
  final String Function(String roleCode) roleName;

  /// A staff entry's role: `MANAGER`, or `MANAGER (business-wide)`.
  (String role, bool wholeBusiness) _staffRole(String? v) {
    final t = (v ?? '').trim();
    const mark = ' (business-wide)';
    final whole = t.endsWith(mark);
    final code = whole ? t.substring(0, t.length - mark.length) : t;
    return (code.isEmpty ? '' : roleName(code), whole);
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final e = change;
    final subject = e.subjectId == null ? 'A person' : person(e.subjectId!);
    final store = e.storeId == null ? null : storeName(e.storeId!);

    late final String title;
    String? detail;
    var icon = Icons.history_outlined;
    switch (e.type) {
      case 'STORE_CREATED':
        icon = Icons.add_business_outlined;
        title = 'Store opened${store == null ? '' : ': $store'}';
        detail = e.to == null ? null : 'A ${storeTypeLabel(e.to!).toLowerCase()}';
      case 'STORE_STATUS_CHANGED':
        icon = Icons.toggle_on_outlined;
        title = 'Store status changed${store == null ? '' : ': $store'}';
        detail = _arrow(e.from == null ? null : storeStatusLabel(e.from!), e.to == null ? null : storeStatusLabel(e.to!));
      case 'STORE_TILL_PHONE_CHANGED':
        icon = Icons.phone_outlined;
        title = 'Till phone setting changed${store == null ? '' : ': $store'}';
        detail = _arrow(e.from == null ? null : tillPhoneLabel(e.from), e.to == null ? null : tillPhoneLabel(e.to));
      case 'STAFF_ASSIGNED':
        icon = Icons.person_add_alt_outlined;
        final (role, whole) = _staffRole(e.to ?? e.subjectCode);
        title = '$subject given $role';
        detail = whole ? 'Across the whole business' : (store == null ? null : 'At $store');
      case 'STAFF_UNASSIGNED':
        icon = Icons.person_remove_outlined;
        final (role, whole) = _staffRole(e.from ?? e.subjectCode);
        title = '$subject taken off $role';
        detail = whole ? 'Across the whole business' : (store == null ? null : 'At $store');
      case 'ROLE_DEFINED':
        icon = Icons.shield_outlined;
        title = 'Role defined: ${roleName(e.subjectCode ?? '')}';
        detail = roleChangeWords(e.to);
      case 'ROLE_CHANGED':
        icon = Icons.edit_outlined;
        title = 'Role changed: ${roleName(e.subjectCode ?? '')}';
        detail = _arrow(roleChangeWords(e.from), roleChangeWords(e.to));
      case 'ROLE_DELETED':
        icon = Icons.delete_outline;
        title = 'Role deleted: ${roleName(e.subjectCode ?? '')}';
        detail = roleChangeWords(e.from);
      default:
        title = 'A change to the business: ${humanizeCode(e.type).toLowerCase()}';
        detail = _arrow(e.from, e.to);
    }
    final when = e.at == null ? '' : AppFormat.dateTime(e.at!.toIso8601String());
    final who = e.actorId == null ? 'by nobody recorded' : 'by ${person(e.actorId!)}';
    return ListTile(
      key: Key('change-${e.id}'),
      leading: Icon(icon, color: cs.onSurfaceVariant),
      title: Text(title),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (detail != null && detail.isNotEmpty) Text(detail),
          Text([if (when.isNotEmpty) when, who].join(' · ')),
        ],
      ),
    );
  }

  /// `Was → is`, or whichever side there is.
  static String? _arrow(String? from, String? to) {
    final f = (from ?? '').isEmpty ? null : from;
    final t = (to ?? '').isEmpty ? null : to;
    if (f != null && t != null) return '$f → $t';
    return t ?? f;
  }
}
