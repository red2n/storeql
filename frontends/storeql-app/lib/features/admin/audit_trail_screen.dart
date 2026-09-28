import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/util/file_download.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/util/status_labels.dart';
import '../../shared/widgets/adaptive_filters.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import 'providers/admin_providers.dart';
import 'providers/staff_names.dart';

// The business audit trail (20.11): who discounted, voided, opened the drawer,
// cancelled, took goods back or wrote stock off — one timeline across the two
// services that record those actions, newest first, naming the member of
// staff. Read-only by design: every row comes from an append-only log and
// nothing on this screen can change one.

/// The event types the trail shows, with the words used for them.
const auditTypeLabels = <String, String>{
  'DISCOUNT': 'Discount',
  'VOID': 'Void',
  'NO_SALE': 'No sale',
  'CANCEL': 'Cancel',
  'RETURN': 'Return',
  'STOCK_ADJUSTMENT': 'Stock adjustment',
};

/// How a return's money went back, finishing "£4.20 refunded …".
String _refundedHow(String method) => switch (method.toUpperCase()) {
      'ORIGINAL' => 'to the original payment',
      'STORE_CREDIT' => 'as store credit',
      'GIFT_CARD' => 'to a gift card',
      'CARD' => 'to the card',
      'CASH' => 'in cash',
      _ => 'by ${_midSentence(humanizeCode(method))}',
    };

/// The stockroom's reason codes (inventory-svc's `transaction_reason_codes`),
/// in words. A business's own code reads as its words too.
String _stockReason(String code) => switch (code.toUpperCase()) {
      'DAMAGED' => 'Damaged',
      'FOUND' => 'Found during a count',
      'THEFT' => 'Theft or shrinkage',
      'EXPIRY' || 'EXPIRED' => 'Expired',
      'VENDOR_RETURN' => 'Returned to the supplier',
      'CORRECTION' => 'Correction',
      'SAMPLING' => 'Quality sampling',
      _ => humanizeCode(code),
    };

/// Words from [humanizeCode] for the middle of a sentence: `Manager` →
/// `manager`, while an acronym such as `POS lead` keeps its capitals.
String _midSentence(String words) {
  if (words.length < 2 || words[1].toUpperCase() == words[1]) return words;
  return words[0].toLowerCase() + words.substring(1);
}

/// One event on the trail, from either source.
class AuditEvent {
  final String id;
  final String type;
  final DateTime? occurredAt;
  final String? actorId;
  final String storeId;
  final String? orderId;
  final double? amount;
  final String? reason;
  final String? detail;

  /// Stock adjustments only: the signed quantity and what was adjusted.
  final double? qty;
  final String? variantId;

  const AuditEvent({
    required this.id,
    required this.type,
    this.occurredAt,
    this.actorId,
    required this.storeId,
    this.orderId,
    this.amount,
    this.reason,
    this.detail,
    this.qty,
    this.variantId,
  });

  bool get fromStock => type == 'STOCK_ADJUSTMENT';

  /// An event from order-svc's trail.
  factory AuditEvent.fromSales(Map<String, dynamic> j) => AuditEvent(
        id: j['id'] as String? ?? '',
        type: j['type'] as String? ?? '',
        occurredAt: DateTime.tryParse(j['occurredAt'] as String? ?? ''),
        actorId: j['actorId'] as String?,
        storeId: j['storeId'] as String? ?? '',
        orderId: j['orderId'] as String?,
        amount: (j['amount'] as num?)?.toDouble(),
        reason: j['reason'] as String?,
        detail: j['detail'] as String?,
      );

  /// An inventory movement of type ADJUST, read as an event on the trail.
  factory AuditEvent.fromMovement(Map<String, dynamic> j) => AuditEvent(
        id: j['id'] as String? ?? '',
        type: 'STOCK_ADJUSTMENT',
        occurredAt: DateTime.tryParse(j['createdAt'] as String? ?? ''),
        actorId: j['actorId'] as String?,
        storeId: j['storeId'] as String? ?? '',
        reason: j['reasonCode'] as String?,
        detail: j['refType'] as String?,
        qty: (j['qty'] as num?)?.toDouble(),
        variantId: j['variantId'] as String?,
      );
}

/// The trail's filters: one store or all, a period, one kind of action or
/// all, one member of staff or anyone. The whole filter is the provider
/// family's key, so changing any of it starts a fresh first page.
class AuditFilter {
  final String? storeId;
  final DateTime from;
  final DateTime to;
  final String? type;
  final String? actorId;

  const AuditFilter({
    this.storeId,
    required this.from,
    required this.to,
    this.type,
    this.actorId,
  });

  static const _unset = Object();

  /// The trail's opening view: every store, every action, anyone, over the
  /// last thirty days including today.
  factory AuditFilter.lastThirtyDays() {
    final today = DateTime.now();
    final day = DateTime(today.year, today.month, today.day);
    return AuditFilter(from: day.subtract(const Duration(days: 29)), to: day);
  }

  /// How many filters differ from [lastThirtyDays] — the number the phone's
  /// *Filters* button shows.
  int get activeCount {
    final initial = AuditFilter.lastThirtyDays();
    return [
      storeId != null,
      from != initial.from || to != initial.to,
      type != null,
      actorId != null,
    ].where((on) => on).length;
  }

  AuditFilter copyWith({
    Object? storeId = _unset,
    DateTime? from,
    DateTime? to,
    Object? type = _unset,
    Object? actorId = _unset,
  }) =>
      AuditFilter(
        storeId: storeId == _unset ? this.storeId : storeId as String?,
        from: from ?? this.from,
        to: to ?? this.to,
        type: type == _unset ? this.type : type as String?,
        actorId: actorId == _unset ? this.actorId : actorId as String?,
      );

  /// Whether order-svc's trail is wanted at all under this filter.
  bool get wantsSales => type != 'STOCK_ADJUSTMENT';

  /// Whether inventory's adjustments are wanted under this filter.
  bool get wantsStock => type == null || type == 'STOCK_ADJUSTMENT';

  /// The API's upper bound is exclusive; the picker's is a day, inclusive.
  DateTime get toExclusive => to.add(const Duration(days: 1));

  Map<String, dynamic> get salesQuery => {
        if (storeId != null) 'store': storeId,
        'from': from.toUtc().toIso8601String(),
        'to': toExclusive.toUtc().toIso8601String(),
        if (type != null && type != 'STOCK_ADJUSTMENT') 'type': type,
        if (actorId != null) 'actor': actorId,
      };

  @override
  bool operator ==(Object other) =>
      other is AuditFilter &&
      other.storeId == storeId &&
      other.from == from &&
      other.to == to &&
      other.type == type &&
      other.actorId == actorId;

  @override
  int get hashCode => Object.hash(storeId, from, to, type, actorId);
}

final auditFilterProvider =
    StateProvider<AuditFilter>((ref) => AuditFilter.lastThirtyDays());

/// What has been loaded so far: the merged timeline, and whether order-svc has
/// an older page to fetch.
class AuditTrailState {
  final List<AuditEvent> events;
  final String? nextCursor;
  final bool loading;
  final bool loadingMore;
  final Object? error;

  const AuditTrailState({
    this.events = const [],
    this.nextCursor,
    this.loading = false,
    this.loadingMore = false,
    this.error,
  });

  bool get hasMore => nextCursor != null;
}

/// Loads the trail from its two sources and keeps them as one timeline.
///
/// order-svc's events are cursor-paged and carry every filter server-side.
/// Inventory's adjustments come from the movements ledger, which filters by
/// store and type but not by period or actor, so those two are applied here
/// to the page it returns. "Load older" pages order-svc only: adjustments are
/// a single bounded read.
class AuditTrailNotifier extends StateNotifier<AuditTrailState> {
  AuditTrailNotifier(this._ref, this._filter)
      : super(const AuditTrailState(loading: true)) {
    _load();
  }

  final Ref _ref;
  final AuditFilter _filter;
  static const _pageSize = 50;

  Future<void> _load() async {
    state = const AuditTrailState(loading: true);
    try {
      final sales = _filter.wantsSales ? await _fetchSales(null) : (const <AuditEvent>[], null);
      final stock = _filter.wantsStock ? await _fetchStock() : const <AuditEvent>[];
      if (!mounted) return;
      state = AuditTrailState(events: _merge([...sales.$1, ...stock]), nextCursor: sales.$2);
    } catch (e) {
      if (!mounted) return;
      state = AuditTrailState(error: e);
    }
  }

  Future<void> refresh() => _load();

  Future<void> loadOlder() async {
    if (state.loading || state.loadingMore || !state.hasMore) return;
    state = AuditTrailState(
        events: state.events, nextCursor: state.nextCursor, loadingMore: true);
    try {
      final (more, next) = await _fetchSales(state.nextCursor);
      if (!mounted) return;
      state = AuditTrailState(events: _merge([...state.events, ...more]), nextCursor: next);
    } catch (e) {
      if (!mounted) return;
      state = AuditTrailState(events: state.events, nextCursor: state.nextCursor, error: e);
    }
  }

  Future<(List<AuditEvent>, String?)> _fetchSales(String? after) async {
    final resp = await _ref.read(apiClientProvider).dio.get(
          '/${ApiConstants.order}/admin/audit/events',
          queryParameters: {
            ..._filter.salesQuery,
            'limit': _pageSize,
            'after': ?after,
          },
        );
    final rows = (resp.data['data'] as List?) ?? const [];
    final next = (resp.data['meta'] as Map<String, dynamic>?)?['nextCursor'] as String?;
    return (
      rows.map((e) => AuditEvent.fromSales(e as Map<String, dynamic>)).toList(),
      next,
    );
  }

  Future<List<AuditEvent>> _fetchStock() async {
    final resp = await _ref.read(apiClientProvider).dio.get(
          '/${ApiConstants.inventory}/admin/inventory/movements',
          queryParameters: {
            if (_filter.storeId != null) 'store': _filter.storeId,
            // The ledger's type for a manual adjustment; its refType is ADJUSTMENT.
            'type': 'ADJUST',
            'limit': 100,
          },
        );
    final rows = (resp.data['data'] as List?) ?? const [];
    final from = _filter.from.toUtc();
    final to = _filter.toExclusive.toUtc();
    return rows
        .map((e) => AuditEvent.fromMovement(e as Map<String, dynamic>))
        .where((e) {
          final at = e.occurredAt?.toUtc();
          if (at == null || at.isBefore(from) || !at.isBefore(to)) return false;
          return _filter.actorId == null || e.actorId == _filter.actorId;
        })
        .toList();
  }

  static List<AuditEvent> _merge(List<AuditEvent> all) {
    final out = [...all];
    out.sort((a, b) {
      final x = a.occurredAt, y = b.occurredAt;
      if (x == null) return y == null ? 0 : 1;
      if (y == null) return -1;
      return y.compareTo(x);
    });
    return out;
  }
}

final auditTrailProvider = StateNotifierProvider.autoDispose
    .family<AuditTrailNotifier, AuditTrailState, AuditFilter>(
  (ref, filter) => AuditTrailNotifier(ref, filter),
);

class AuditTrailScreen extends ConsumerWidget {
  const AuditTrailScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stores = ref.watch(storesProvider);
    final staff = ref.watch(staffProvider).value ?? const <StaffMember>[];
    final filter = ref.watch(auditFilterProvider);
    final trail = ref.watch(auditTrailProvider(filter));
    // The trail's amounts carry no currency: a discount or a refund is in the
    // business's home currency, the one its tills charge in.
    final currency = ref.watch(tenantInfoProvider).value?.currency;
    final theme = Theme.of(context);
    String day(DateTime d) => AppFormat.date(d.toIso8601String());
    void setFilter(AuditFilter f) => ref.read(auditFilterProvider.notifier).state = f;

    // People by their login, not their id: everyone on the staff list (the Who
    // menu), everyone the trail names (someone who has since left included)
    // and the supervisor who authorised a no-sale. The end of an id stands in
    // only while iam-svc has not named someone.
    // Names already read stay while the trail reloads (a filter, Load
    // older), so a row never drops back to an id for a round trip; only ids
    // not yet asked about are sent.
    final logins = ref.watch(staffNameCacheProvider);
    final names = ref.read(staffNameCacheProvider.notifier);
    Future.microtask(() => names.resolve([
          for (final s in staff) s.userId,
          for (final e in trail.events) ...[
            ?e.actorId,
            if (e.type == 'NO_SALE') ?e.detail,
          ],
        ]));
    // Products by name, for the stock adjustments.
    final products = ref
            .watch(variantLabelsProvider(variantIdsKey([
              for (final e in trail.events) ?e.variantId,
            ])))
            .value ??
        const <String, VariantLabel>{};

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

    // One entry per person, whatever stores they are assigned at. A login
    // iam-svc has not named keeps its role, so two unnamed people differ.
    final actors = <String, String>{};
    for (final s in staff) {
      actors.putIfAbsent(
        s.userId,
        () => logins[s.userId] ?? '${humanizeCode(s.role)} · ${shortRef(s.userId)}',
      );
    }

    return ListView(
      // 16 on a phone, 24 from tablet width up.
      padding: context.pagePadding,
      children: [
        PageHeader(
          title: 'Audit trail',
          subtitle: 'Who discounted, voided, opened the drawer, cancelled, took goods '
              'back or wrote stock off — one record across the till and the '
              'stockroom, newest first. Every row is an append-only log; nothing '
              'here can change one.',
          padding: const EdgeInsetsDirectional.only(bottom: AppSpacing.lg),
          actions: [
            OutlinedButton.icon(
              key: const Key('audit-export'),
              onPressed: trail.events.isEmpty ? null : () => _exportCsv(trail.events),
              icon: const Icon(Icons.download_outlined),
              label: const Text('Export CSV'),
            ),
          ],
        ),
        // Inline from tablet width; on a phone, folded behind one Filters
        // button so the trail itself starts on the first screen.
        AdaptiveFilters(
          activeCount: filter.activeCount,
          onClear: () => setFilter(AuditFilter.lastThirtyDays()),
          children: [
            SizedBox(
              width: 240,
              child: DropdownButtonFormField<String?>(
                key: const Key('audit-store'),
                isExpanded: true,
                initialValue: filter.storeId,
                decoration: const InputDecoration(labelText: 'Store'),
                items: [
                  const DropdownMenuItem<String?>(value: null, child: Text('All stores')),
                  for (final s in stores.value ?? const [])
                    DropdownMenuItem<String?>(value: s.id, child: Text(s.name)),
                ],
                onChanged: (v) => setFilter(filter.copyWith(storeId: v)),
              ),
            ),
            OutlinedButton.icon(
              key: const Key('audit-period'),
              onPressed: pickRange,
              icon: const Icon(Icons.date_range),
              label: Text('${day(filter.from)} – ${day(filter.to)}'),
            ),
            SizedBox(
              width: 220,
              child: DropdownButtonFormField<String?>(
                key: const Key('audit-type'),
                isExpanded: true,
                initialValue: filter.type,
                decoration: const InputDecoration(labelText: 'Action'),
                items: [
                  const DropdownMenuItem<String?>(value: null, child: Text('Everything')),
                  for (final e in auditTypeLabels.entries)
                    DropdownMenuItem<String?>(value: e.key, child: Text('${e.value}s')),
                ],
                onChanged: (v) => setFilter(filter.copyWith(type: v)),
              ),
            ),
            SizedBox(
              width: 240,
              child: DropdownButtonFormField<String?>(
                key: const Key('audit-actor'),
                isExpanded: true,
                initialValue: actors.containsKey(filter.actorId) ? filter.actorId : null,
                decoration: const InputDecoration(labelText: 'Who'),
                items: [
                  const DropdownMenuItem<String?>(value: null, child: Text('Anyone')),
                  for (final e in actors.entries)
                    DropdownMenuItem<String?>(
                      value: e.key,
                      child: Text(e.value, overflow: TextOverflow.ellipsis),
                    ),
                ],
                onChanged: (v) => setFilter(filter.copyWith(actorId: v)),
              ),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.lg),
        if (trail.loading)
          const LoadingView(label: 'Loading the trail…')
        else if (trail.error != null && trail.events.isEmpty)
          ErrorView(
            message: friendlyError(trail.error!, fallback: 'Could not load the audit trail.'),
            onRetry: () => ref.read(auditTrailProvider(filter).notifier).refresh(),
          )
        else if (trail.events.isEmpty)
          const EmptyState(
            icon: Icons.fact_check_outlined,
            title: 'Nothing recorded in this period',
            message: 'A busy shop with an empty trail has either had a quiet month '
                'or is not using the till for its exceptions.',
          )
        else ...[
          Card(
            child: Column(
              children: [
                for (final e in trail.events)
                  _AuditRow(
                    event: e,
                    currency: currency,
                    nameOf: (id) => staffDisplayName(id, logins),
                    productOf: (id) {
                      final name = products[id]?.productName ?? '';
                      return name.isNotEmpty ? name : shortRef(id);
                    },
                  ),
              ],
            ),
          ),
          if (trail.error != null)
            Padding(
              padding: const EdgeInsetsDirectional.only(top: AppSpacing.sm),
              child: Text(
                friendlyError(trail.error!, fallback: 'Could not load older events.'),
                style: TextStyle(color: theme.colorScheme.error),
              ),
            ),
          if (trail.hasMore)
            Padding(
              padding: const EdgeInsetsDirectional.only(top: AppSpacing.md),
              child: Center(
                child: trail.loadingMore
                    ? const CircularProgressIndicator()
                    : TextButton.icon(
                        key: const Key('audit-load-older'),
                        onPressed: () =>
                            ref.read(auditTrailProvider(filter).notifier).loadOlder(),
                        icon: const Icon(Icons.expand_more),
                        label: const Text('Load older'),
                      ),
              ),
            ),
        ],
      ],
    );
  }

  static void _exportCsv(List<AuditEvent> events) {
    final buf = StringBuffer('occurredAt,type,actorId,storeId,orderId,amount,qty,variantId,reason,detail\n');
    for (final e in events) {
      buf.writeln([
        e.occurredAt?.toUtc().toIso8601String() ?? '',
        e.type,
        e.actorId ?? '',
        e.storeId,
        e.orderId ?? '',
        e.amount?.toStringAsFixed(2) ?? '',
        e.qty?.toString() ?? '',
        e.variantId ?? '',
        e.reason ?? '',
        e.detail ?? '',
      ].map(_csv).join(','));
    }
    downloadTextFile('audit-trail.csv', buf.toString(), mimeType: 'text/csv;charset=utf-8');
  }

  static String _csv(String s) =>
      s.contains(',') || s.contains('"') || s.contains('\n')
          ? '"${s.replaceAll('"', '""')}"'
          : s;
}

class _AuditRow extends StatelessWidget {
  const _AuditRow({
    required this.event,
    required this.currency,
    required this.nameOf,
    required this.productOf,
  });

  final AuditEvent event;

  /// The business's home currency, or null while it is unknown (the amount
  /// then shows without a symbol rather than in the wrong one).
  final String? currency;

  /// A member of staff's name from their user id.
  final String Function(String userId) nameOf;

  /// A product's name from its variant id.
  final String Function(String variantId) productOf;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final e = event;
    final (icon, colour) = switch (e.type) {
      'DISCOUNT' => (Icons.sell_outlined, cs.tertiary),
      'VOID' => (Icons.block_outlined, cs.error),
      'NO_SALE' => (Icons.point_of_sale_outlined, cs.secondary),
      'CANCEL' => (Icons.cancel_outlined, cs.error),
      'RETURN' => (Icons.assignment_return_outlined, cs.primary),
      _ => (Icons.inventory_outlined, cs.secondary),
    };
    final label = auditTypeLabels[e.type] ?? humanizeCode(e.type);
    final money = e.amount == null ? '' : AppFormat.money(e.amount!, currencyCode: currency);
    final detail = (e.detail ?? '').isEmpty ? null : e.detail!;
    final title = switch (e.type) {
      // The role the discount was granted under.
      'DISCOUNT' => [
          label,
          if (money.isNotEmpty) money,
          if (detail != null) 'authorised as ${_midSentence(humanizeCode(detail))}',
        ].join(' · '),
      // The status the order was in when it was cancelled.
      'CANCEL' => detail == null
          ? label
          : '$label · the order was ${_midSentence(orderStatusLabel(detail))}',
      'RETURN' => [
          label,
          [
            if (money.isNotEmpty) money,
            'refunded',
            if (detail != null) _refundedHow(detail),
          ].join(' '),
        ].join(' · '),
      // The supervisor who authorised opening the drawer.
      'NO_SALE' => detail == null ? label : '$label · authorised by ${nameOf(detail)}',
      'STOCK_ADJUSTMENT' =>
        '$label · ${_signed(e.qty)}${e.variantId != null ? ' × ${productOf(e.variantId!)}' : ''}',
      _ => label,
    };
    final when = e.occurredAt == null ? '' : AppFormat.dateTime(e.occurredAt!.toIso8601String());
    final who = e.actorId == null ? 'Unattributed' : 'by ${nameOf(e.actorId!)}';
    // A till reason is the cashier's own words; the stockroom's is a code.
    final reason = (e.reason ?? '').isEmpty
        ? null
        : e.fromStock
            ? _stockReason(e.reason!)
            : e.reason!;
    final subtitle = [
      if (when.isNotEmpty) when,
      who,
      if (e.orderId != null) 'order ${shortRef(e.orderId!)}',
      ?reason,
    ].join(' · ');
    return ListTile(
      leading: Icon(icon, color: colour),
      title: Text(title),
      subtitle: Text(subtitle),
      trailing: Chip(
        label: Text(e.fromStock ? 'Stock' : 'Till'),
        visualDensity: VisualDensity.compact,
      ),
    );
  }

  static String _signed(double? q) {
    if (q == null) return '';
    final s = q == q.roundToDouble() ? q.toInt().toString() : q.toString();
    return q > 0 ? '+$s' : s;
  }
}
