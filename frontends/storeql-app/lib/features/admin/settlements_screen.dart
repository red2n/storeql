import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/status_badge.dart';
import 'package:storeql_app/core/ids.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/util/short_ref.dart';
import 'providers/admin_providers.dart';

// ---------------------------------------------------------------------------
// Card settlements (11.10).
//
// A card payment is a promise until the acquirer pays it out — days later, in
// a batch, less its fees, refunds and chargebacks. The acquirer's settlement
// file says which payments a payout covers. payment-svc matches every line to
// what it holds; this screen is where a payout's file is imported, where what
// did not match is decided (point it at the payment it is about, accept that
// the sums differ, or send its money to unallocated receipts), where a payout
// with nothing left open is signed off into the books, and where the card
// payments no payout has covered yet are listed.
// ---------------------------------------------------------------------------

class SettlementBatch {
  final String id;
  final String provider;
  final String reference;
  final String currency;
  final String payoutDate;
  final num sales;
  final num refunds;
  final num chargebacks;
  final num fees;
  final num net;
  final int lineCount;
  final int openExceptions;
  final String status;

  const SettlementBatch({
    required this.id,
    required this.provider,
    required this.reference,
    required this.currency,
    required this.payoutDate,
    required this.sales,
    required this.refunds,
    required this.chargebacks,
    required this.fees,
    required this.net,
    required this.lineCount,
    required this.openExceptions,
    required this.status,
  });

  factory SettlementBatch.fromJson(Map<String, dynamic> j) => SettlementBatch(
        id: j['id'] as String,
        provider: j['provider'] as String? ?? '',
        reference: j['reference'] as String? ?? '',
        currency: j['currency'] as String? ?? '',
        payoutDate: j['payoutDate'] as String? ?? '',
        sales: j['salesAmount'] as num? ?? 0,
        refunds: j['refundAmount'] as num? ?? 0,
        chargebacks: j['chargebackAmount'] as num? ?? 0,
        fees: j['feeAmount'] as num? ?? 0,
        net: j['netAmount'] as num? ?? 0,
        lineCount: j['lineCount'] as int? ?? 0,
        openExceptions: j['openExceptions'] as int? ?? 0,
        status: j['status'] as String? ?? '',
      );

  bool get reconciled => status == 'RECONCILED';
}

class SettlementLine {
  final String id;
  final int lineNo;
  final String type;
  final String? reference;
  final String? originalReference;
  final num gross;
  final num fee;
  final num net;
  final String matchStatus;
  final bool open;
  final num? expectedAmount;
  final String? resolution;
  final String? note;

  const SettlementLine({
    required this.id,
    required this.lineNo,
    required this.type,
    required this.reference,
    required this.originalReference,
    required this.gross,
    required this.fee,
    required this.net,
    required this.matchStatus,
    required this.open,
    required this.expectedAmount,
    required this.resolution,
    required this.note,
  });

  factory SettlementLine.fromJson(Map<String, dynamic> j) => SettlementLine(
        id: j['id'] as String,
        lineNo: j['lineNo'] as int? ?? 0,
        type: j['type'] as String? ?? '',
        reference: j['reference'] as String?,
        originalReference: j['originalReference'] as String?,
        gross: j['gross'] as num? ?? 0,
        fee: j['fee'] as num? ?? 0,
        net: j['net'] as num? ?? 0,
        matchStatus: j['matchStatus'] as String? ?? '',
        open: j['open'] == true,
        expectedAmount: j['expectedAmount'] as num?,
        resolution: j['resolution'] as String?,
        note: j['note'] as String?,
      );

  /// What the line is about, in the word its target goes by.
  String get targetWord => switch (type) {
        'SALE' => 'payment',
        'REFUND' => 'refund',
        _ => 'chargeback',
      };

  /// A fee or an adjustment is about no one payment: it can only go to unallocated.
  bool get pointable => type != 'FEE' && type != 'ADJUSTMENT';
}

class UnsettledPayment {
  final String paymentId;

  /// The sale the payment was taken for, when payment-svc says.
  final String? orderId;
  final String? reference;
  final String method;
  final num amount;
  final String capturedAt;
  final int daysOutstanding;

  const UnsettledPayment({
    required this.paymentId,
    this.orderId,
    required this.reference,
    required this.method,
    required this.amount,
    required this.capturedAt,
    required this.daysOutstanding,
  });

  factory UnsettledPayment.fromJson(Map<String, dynamic> j) => UnsettledPayment(
        paymentId: j['paymentId'] as String,
        orderId: j['orderId'] as String?,
        reference: j['reference'] as String?,
        method: j['method'] as String? ?? '',
        amount: j['amount'] as num? ?? 0,
        capturedAt: j['capturedAt'] as String? ?? '',
        daysOutstanding: j['daysOutstanding'] as int? ?? 0,
      );
}

const _base = '/${ApiConstants.payment}/admin/settlements';

String settlementStatusLabel(String status) => switch (status) {
      'EXCEPTIONS' => 'Needs decisions',
      'READY' => 'Ready to sign off',
      'RECONCILED' => 'Reconciled',
      _ => humanizeCode(status),
    };

/// The tone a payout's status is shown in: open decisions need a look, a payout
/// ready to sign off only waits for someone, and one in the books is done.
StatusTone settlementStatusTone(String status) => switch (status) {
      'EXCEPTIONS' => StatusTone.warning,
      'READY' => StatusTone.info,
      'RECONCILED' => StatusTone.success,
      _ => StatusTone.neutral,
    };

String settlementLineLabel(String type) => switch (type) {
      'SALE' => 'Sale',
      'REFUND' => 'Refund',
      'CHARGEBACK' => 'Chargeback',
      'CHARGEBACK_REVERSAL' => 'Chargeback won back',
      'FEE' => 'Fee',
      'ADJUSTMENT' => 'Adjustment',
      _ => type,
    };

/// What became of a payout line, in words; the sum we hold for it in the
/// payout's [currency].
String settlementMatchLabel(SettlementLine l, {String? currency}) {
  final held = l.expectedAmount == null
      ? null
      : AppFormat.money(l.expectedAmount!, currencyCode: currency);
  return switch (l.resolution ?? l.matchStatus) {
    'MATCHED' => 'Matched',
    'NOT_APPLICABLE' => 'A fee',
    'UNMATCHED' => 'Nothing here answers to it',
    'AMOUNT_MISMATCH' =>
      held == null ? 'We hold a different sum' : 'We hold $held',
    'DUPLICATE' => 'Already settled by another line',
    'MATCHED_BY_HAND' => 'Matched by hand',
    'DIFFERENCE_ACCEPTED' => held == null
        ? 'Difference accepted'
        : 'Difference accepted (we hold $held)',
    'UNALLOCATED' => 'Sent to unallocated receipts',
    final other => humanizeCode(other),
  };
}

const settlementFormatLabels = {
  'STOREQL': 'Our own spreadsheet layout',
  'STRIPE': 'Stripe — itemised payout reconciliation',
  'ADYEN': 'Adyen — settlement details',
};

/// Which of the screen's two lists is showing.
class SettlementView extends Notifier<bool> {
  @override
  bool build() => false;

  void showUnsettled(bool unsettled) => state = unsettled;
}

final settlementViewProvider = NotifierProvider.autoDispose<SettlementView, bool>(SettlementView.new);

final settlementsProvider = FutureProvider.autoDispose<List<SettlementBatch>>((ref) async {
  final resp = await ref.watch(apiClientProvider).dio.get(_base, queryParameters: {'limit': 100});
  return [
    for (final b in resp.data['data'] as List<dynamic>) SettlementBatch.fromJson(Map<String, dynamic>.from(b as Map)),
  ];
});

final unsettledPaymentsProvider = FutureProvider.autoDispose<List<UnsettledPayment>>((ref) async {
  final resp = await ref.watch(apiClientProvider).dio.get('$_base/unsettled', queryParameters: {'limit': 100});
  return [
    for (final u in resp.data['data'] as List<dynamic>) UnsettledPayment.fromJson(Map<String, dynamic>.from(u as Map)),
  ];
});

class SettlementsScreen extends ConsumerWidget {
  const SettlementsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final unsettled = ref.watch(settlementViewProvider);
    void refresh() {
      ref.invalidate(settlementsProvider);
      ref.invalidate(unsettledPaymentsProvider);
    }

    final actions = [
      OutlinedButton.icon(
        key: const Key('settlement-import'),
        icon: const Icon(Icons.upload_file_outlined),
        label: const Text('Import a payout'),
        onPressed: () async {
          final id = await showDialog<String>(context: context, builder: (_) => const ImportSettlementDialog());
          refresh();
          if (id != null && context.mounted) {
            await showDialog<void>(context: context, builder: (_) => SettlementDialog(id: id));
            refresh();
          }
        },
      ),
      IconButton(icon: const Icon(Icons.refresh), tooltip: 'Refresh', onPressed: refresh),
    ];
    // The page is inset by the gutter — 16 on a phone, 24 from tablet width —
    // and scrolls as one, so a tall header (large text on a phone) scrolls
    // away instead of squeezing the list.
    final gutter = context.pageGutter;
    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: EdgeInsetsDirectional.fromSTEB(gutter, gutter, gutter, 0),
          sliver: SliverToBoxAdapter(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                PageHeader(
                  title: 'Card settlements',
                  subtitle: 'What the acquirer paid into the bank, against the card payments taken. '
                      'A payout is in the books once everything in it is accounted for.',
                  padding: const EdgeInsetsDirectional.only(bottom: AppSpacing.md),
                  actions: actions,
                  // Below 600 the actions go under the title, so the long
                  // subtitle keeps its line rather than breaking mid-word beside them.
                  stackBelow: AppBreakpoints.medium,
                ),
                Wrap(
                  spacing: AppSpacing.sm,
                  runSpacing: AppSpacing.sm,
                  children: [
                    ChoiceChip(
                      label: const Text('Payouts'),
                      selected: !unsettled,
                      onSelected: (_) => ref.read(settlementViewProvider.notifier).showUnsettled(false),
                    ),
                    ChoiceChip(
                      key: const Key('settlement-unsettled'),
                      label: const Text('Not yet paid out'),
                      selected: unsettled,
                      onSelected: (_) => ref.read(settlementViewProvider.notifier).showUnsettled(true),
                    ),
                  ],
                ),
                const SizedBox(height: AppSpacing.md),
              ],
            ),
          ),
        ),
        SliverPadding(
          padding: EdgeInsetsDirectional.fromSTEB(gutter, 0, gutter, gutter),
          sliver: unsettled ? const _UnsettledList() : _BatchList(onChanged: refresh),
        ),
      ],
    );
  }
}

/// A list's loading, failure or empty state, filling what the page leaves.
class _Fill extends StatelessWidget {
  final Widget child;
  const _Fill(this.child);

  @override
  Widget build(BuildContext context) => SliverFillRemaining(hasScrollBody: false, child: child);
}

class _BatchList extends ConsumerWidget {
  final VoidCallback onChanged;
  const _BatchList({required this.onChanged});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return ref.watch(settlementsProvider).when(
          loading: () => const _Fill(LoadingView(label: 'Loading payouts…')),
          error: (e, _) => _Fill(ErrorView(message: friendlyError(e, fallback: 'Could not load payouts.'), onRetry: onChanged)),
          data: (list) => list.isEmpty
              ? const _Fill(EmptyState(title: 'No payouts imported yet. Import the acquirer\'s settlement file to begin.'))
              : SliverList.separated(
                  itemCount: list.length,
                  separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.sm),
                  itemBuilder: (context, i) => _BatchTile(batch: list[i], onChanged: onChanged),
                ),
        );
  }
}

class _BatchTile extends StatelessWidget {
  final SettlementBatch batch;
  final VoidCallback onChanged;
  const _BatchTile({required this.batch, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final b = batch;
    final paid = AppFormat.date(b.payoutDate);
    // Red is for a payout with decisions still open; one ready to sign off has
    // nothing wrong with it, it only waits.
    final (icon, colour) = switch (b.status) {
      'RECONCILED' => (Icons.task_alt, context.status.success),
      'READY' => (Icons.fact_check_outlined, context.status.info),
      _ when b.openExceptions > 0 || b.status == 'EXCEPTIONS' => (Icons.rule_outlined, cs.error),
      _ => (Icons.rule_outlined, cs.onSurfaceVariant),
    };
    final badge = StatusBadge(settlementStatusLabel(b.status), tone: settlementStatusTone(b.status));
    final line = Text(
      b.openExceptions > 0
          ? 'Paid $paid · ${b.openExceptions} of ${b.lineCount} lines need a decision'
          : 'Paid $paid · ${b.lineCount} lines · fees ${AppFormat.money(b.fees, currencyCode: b.currency)}',
    );
    return LayoutBuilder(builder: (context, constraints) {
      // On a phone, or with large text, the badge goes under the line rather
      // than taking the width the words need.
      final below = constraints.maxWidth < AppBreakpoints.medium ||
          MediaQuery.textScalerOf(context).scale(16) > 16 * 1.3;
      return Card(
        child: ListTile(
          key: Key('settlement-${b.id}'),
          leading: Icon(icon, color: colour),
          title: Text('${AppFormat.money(b.net, currencyCode: b.currency)} · ${b.provider} ${b.reference}'),
          subtitle: below
              ? Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [line, const SizedBox(height: AppSpacing.xs), badge],
                )
              : line,
          trailing: below ? null : badge,
          onTap: () async {
            await showDialog<void>(context: context, builder: (_) => SettlementDialog(id: b.id));
            onChanged();
          },
        ),
      );
    });
  }
}

class _UnsettledList extends ConsumerWidget {
  const _UnsettledList();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // payment-svc sends the amount alone; a business takes payments in its
    // home currency (a foreign price is shown, never charged).
    final currency = ref.watch(tenantInfoProvider).value?.currency;
    return ref.watch(unsettledPaymentsProvider).when(
          loading: () => const _Fill(LoadingView(label: 'Loading payments…')),
          error: (e, _) => _Fill(ErrorView(
            message: friendlyError(e, fallback: 'Could not load payments.'),
            onRetry: () => ref.invalidate(unsettledPaymentsProvider),
          )),
          data: (list) => list.isEmpty
              ? const _Fill(EmptyState(title: 'Every card payment older than three days has been paid out.'))
              : SliverList.separated(
                  itemCount: list.length,
                  separatorBuilder: (_, _) => const Divider(height: 1),
                  itemBuilder: (context, i) {
                    final u = list[i];
                    return ListTile(
                      key: Key('unsettled-${u.paymentId}'),
                      leading: const Icon(Icons.hourglass_bottom_outlined),
                      title: Text([
                        AppFormat.money(u.amount, currencyCode: currency),
                        humanizeCode(u.method),
                        ?u.reference,
                      ].join(' · ')),
                      subtitle: Text([
                        'Taken ${AppFormat.date(u.capturedAt)}',
                        if (u.orderId != null) 'order …${shortRef(u.orderId!)}',
                      ].join(' · ')),
                      trailing: Text('${u.daysOutstanding} days'),
                    );
                  },
                ),
        );
  }
}

/// One payout: what it adds up to, the lines that need a decision, and the sign-off.
class SettlementDialog extends ConsumerStatefulWidget {
  final String id;
  const SettlementDialog({super.key, required this.id});

  @override
  ConsumerState<SettlementDialog> createState() => _SettlementDialogState();
}

class _SettlementDialogState extends ConsumerState<SettlementDialog> {
  SettlementBatch? _batch;
  List<SettlementLine> _lines = const [];
  bool _all = false;
  String? _error;
  bool _busy = false;

  Dio get _dio => ref.read(apiClientProvider).dio;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _take(dynamic data) {
    final map = Map<String, dynamic>.from(data as Map);
    _batch = SettlementBatch.fromJson(Map<String, dynamic>.from(map['batch'] as Map));
    _lines = [
      for (final l in map['lines'] as List<dynamic>? ?? const []) SettlementLine.fromJson(Map<String, dynamic>.from(l as Map)),
    ];
  }

  Future<void> _load() async {
    try {
      final resp = await _dio.get('$_base/${widget.id}', queryParameters: {'open': !_all, 'limit': 200});
      if (mounted) setState(() => _take(resp.data['data']));
    } catch (e) {
      if (mounted) setState(() => _error = friendlyError(e));
    }
  }

  String _refusal(Object e) => switch (apiErrorCode(e)) {
        'SETTLEMENT_TARGET_NOT_FOUND' => 'Nothing of that kind has that id. Copy it from the order\'s payments.',
        'SETTLEMENT_ALREADY_SETTLED' => 'Another settlement line already covers that one.',
        'SETTLEMENT_AMOUNT_DIFFERS' => 'The sums differ. If it is the right one all the same, accept the difference and say why.',
        'SETTLEMENT_NOTE_REQUIRED' => 'Say why: the books will be read by somebody else.',
        'SETTLEMENT_NOTHING_TO_ACCEPT' => 'This line is linked to nothing yet: say which one it is about.',
        'SETTLEMENT_HAS_EXCEPTIONS' => 'Some lines still need a decision.',
        'SETTLEMENT_RECONCILED' => 'This payout is already in the books.',
        _ => friendlyError(e),
      };

  Future<void> _decide(SettlementLine line) async {
    final decision = await showDialog<Map<String, dynamic>>(context: context, builder: (_) => _DecideDialog(line: line, currency: _batch?.currency));
    if (decision == null || !mounted) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await _dio.post('$_base/${widget.id}/lines/${line.id}/resolve', data: decision);
      await _load();
      if (mounted) setState(() => _busy = false);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = _refusal(e);
      });
    }
  }

  Future<void> _signOff() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await _dio.post('$_base/${widget.id}/reconcile', data: const <String, dynamic>{});
      await _load();
      if (mounted) setState(() => _busy = false);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = _refusal(e);
      });
    }
  }

  String _money(num amount) => AppFormat.money(amount, currencyCode: _batch?.currency);

  @override
  Widget build(BuildContext context) {
    final b = _batch;
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return AlertDialog(
      title: Text(b == null ? 'Payout' : '${b.provider} ${b.reference} · ${settlementStatusLabel(b.status)}'),
      content: SizedBox(
        width: 640,
        child: b == null
            ? (_error == null ? const SizedBox(height: 120, child: Center(child: CircularProgressIndicator())) : Text(_error!))
            : SingleChildScrollView(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text('${_money(b.net)} into the bank on ${AppFormat.date(b.payoutDate)}', style: text.titleMedium),
                    const SizedBox(height: AppSpacing.xs),
                    Text(
                      'Sales ${_money(b.sales)} · refunds ${_money(b.refunds)} · chargebacks ${_money(b.chargebacks)} · '
                      'fees ${_money(b.fees)} · ${b.lineCount} lines',
                      style: text.bodySmall?.copyWith(color: cs.outline),
                    ),
                    const Divider(height: 24),
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            _all
                                ? 'Every line'
                                : _lines.isEmpty
                                    ? 'Nothing needs a decision'
                                    : 'Lines that need a decision',
                            style: text.titleSmall,
                          ),
                        ),
                        TextButton(
                          key: const Key('settlement-toggle-lines'),
                          onPressed: () {
                            setState(() => _all = !_all);
                            _load();
                          },
                          child: Text(_all ? 'Show what needs a decision' : 'Show every line'),
                        ),
                      ],
                    ),
                    for (final l in _lines)
                      ListTile(
                        key: Key('settlement-line-${l.lineNo}'),
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        title: Text('${l.lineNo}. ${settlementLineLabel(l.type)} ${_money(l.gross)} · ${l.reference ?? l.originalReference ?? 'no reference'}'),
                        subtitle: Text(
                          [
                            settlementMatchLabel(l, currency: b.currency),
                            if (l.fee != 0) 'fee ${_money(l.fee)}',
                            if (l.note != null) l.note!,
                          ].join(' · '),
                          style: TextStyle(color: l.open ? cs.error : null),
                        ),
                        trailing: b.reconciled || (!l.open && l.resolution == null)
                            ? null
                            : TextButton(
                                key: Key('settlement-decide-${l.lineNo}'),
                                onPressed: _busy ? null : () => _decide(l),
                                child: Text(l.open ? 'Decide' : 'Change'),
                              ),
                      ),
                    if (_error != null) ...[
                      const SizedBox(height: 8),
                      Text(_error!, key: const Key('settlement-error'), style: TextStyle(color: cs.error)),
                    ],
                  ],
                ),
              ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Close')),
        if (b != null && b.status == 'READY')
          FilledButton(
            key: const Key('settlement-sign-off'),
            onPressed: _busy ? null : _signOff,
            child: Text(_busy ? 'Signing off…' : 'Sign off into the books'),
          ),
      ],
    );
  }
}

/// What to do with a line that did not match.
class _DecideDialog extends StatefulWidget {
  final SettlementLine line;
  const _DecideDialog({required this.line, this.currency});

  /// The payout's currency, which its lines are in.
  final String? currency;

  @override
  State<_DecideDialog> createState() => _DecideDialogState();
}

class _DecideDialogState extends State<_DecideDialog> {
  late String _resolution = widget.line.matchStatus == 'AMOUNT_MISMATCH'
      ? 'DIFFERENCE_ACCEPTED'
      : widget.line.pointable
          ? 'MATCHED_BY_HAND'
          : 'UNALLOCATED';
  final _target = TextEditingController();
  final _note = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _target.dispose();
    _note.dispose();
    super.dispose();
  }

  void _save() {
    final target = _target.text.trim();
    final note = _note.text.trim();
    if (_resolution == 'MATCHED_BY_HAND' && target.isEmpty) {
      setState(() => _error = 'Say which ${widget.line.targetWord} it is about.');
      return;
    }
    if (_resolution != 'MATCHED_BY_HAND' && note.isEmpty) {
      setState(() => _error = 'Say why: the books will be read by somebody else.');
      return;
    }
    Navigator.of(context).pop(<String, dynamic>{
      'resolution': _resolution,
      if (target.isNotEmpty && _resolution != 'UNALLOCATED') 'targetId': target,
      if (note.isNotEmpty) 'note': note,
    });
  }

  @override
  Widget build(BuildContext context) {
    final l = widget.line;
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text('${settlementLineLabel(l.type)} ${AppFormat.money(l.gross, currencyCode: widget.currency)} · ${l.reference ?? l.originalReference ?? ''}'),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(settlementMatchLabel(l, currency: widget.currency)),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                key: const Key('decide-resolution'),
                isExpanded: true,
                initialValue: _resolution,
                decoration: const InputDecoration(labelText: 'What to do'),
                items: [
                  if (l.pointable) DropdownMenuItem(value: 'MATCHED_BY_HAND', child: Text('It is this ${l.targetWord}')),
                  if (l.pointable) const DropdownMenuItem(value: 'DIFFERENCE_ACCEPTED', child: Text('Right one, different sum: accept the difference')),
                  const DropdownMenuItem(value: 'UNALLOCATED', child: Text('Not ours to match: send to unallocated receipts')),
                ],
                onChanged: (v) => setState(() => _resolution = v ?? _resolution),
              ),
              if (_resolution != 'UNALLOCATED') ...[
                const SizedBox(height: 8),
                TextField(
                  key: const Key('decide-target'),
                  controller: _target,
                  decoration: InputDecoration(
                    labelText: 'The ${l.targetWord}\'s id${_resolution == 'MATCHED_BY_HAND' ? ' *' : ''}',
                    helperText: _resolution == 'DIFFERENCE_ACCEPTED' ? 'Leave empty to keep the one it is linked to' : null,
                  ),
                ),
              ],
              const SizedBox(height: 8),
              TextField(
                key: const Key('decide-note'),
                controller: _note,
                maxLines: 2,
                decoration: InputDecoration(labelText: 'Why${_resolution == 'MATCHED_BY_HAND' ? '' : ' *'}'),
              ),
              if (_error != null) ...[
                const SizedBox(height: 8),
                Text(_error!, key: const Key('decide-error'), style: TextStyle(color: cs.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(key: const Key('decide-save'), onPressed: _save, child: const Text('Decide')),
      ],
    );
  }
}

/// Importing one payout's settlement file. Pops the new batch's id.
class ImportSettlementDialog extends ConsumerStatefulWidget {
  /// A file already read, for a test or a caller that has one; the person picks one otherwise.
  final String? content;
  const ImportSettlementDialog({super.key, this.content});

  @override
  ConsumerState<ImportSettlementDialog> createState() => _ImportSettlementDialogState();
}

class _ImportSettlementDialogState extends ConsumerState<ImportSettlementDialog> {
  final _provider = TextEditingController();
  final _reference = TextEditingController();
  final _declared = TextEditingController();
  String _format = 'STOREQL';
  DateTime? _paidOn;
  String? _fileName;
  late String? _content = widget.content;
  String? _error;
  bool _busy = false;

  @override
  void dispose() {
    _provider.dispose();
    _reference.dispose();
    _declared.dispose();
    super.dispose();
  }

  Future<void> _pick() async {
    final r = await FilePicker.pickFiles(type: FileType.custom, allowedExtensions: ['csv', 'txt'], withData: true);
    if (r == null || r.files.isEmpty || r.files.first.bytes == null) return;
    setState(() {
      _fileName = r.files.first.name;
      _content = utf8.decode(r.files.first.bytes!, allowMalformed: true);
      _error = null;
    });
  }

  Future<void> _save() async {
    if (_provider.text.trim().isEmpty) {
      setState(() => _error = 'Say who paid: the acquirer or provider\'s name.');
      return;
    }
    if (_content == null || _content!.trim().isEmpty) {
      setState(() => _error = 'Choose the settlement file.');
      return;
    }
    final declared = _declared.text.trim();
    if (declared.isNotEmpty && num.tryParse(declared) == null) {
      setState(() => _error = 'The sum paid is a number, like 1234.56.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        _base,
        data: {
          'provider': _provider.text.trim(),
          'format': _format,
          'reference': ?(_reference.text.trim().isEmpty ? null : _reference.text.trim()),
          'payoutDate': ?_paidOn?.toIso8601String().substring(0, 10),
          'declaredNet': ?num.tryParse(declared),
          'content': _content,
        },
        // The same payout file imported twice is one batch, so the key is derived from it.
        options: Options(headers: {'Idempotency-Key': derivedId('settlement', '${_provider.text.trim()}:${_reference.text.trim()}:${_content!.length}')}),
      );
      if (mounted) Navigator.of(context).pop((resp.data['data'] as Map)['id'] as String);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = switch (apiErrorCode(e)) {
          'SETTLEMENT_ALREADY_IMPORTED' => 'This payout has been imported already.',
          'SETTLEMENT_OUT_OF_BALANCE' => 'The file does not add up to the sum paid. Is it the whole payout, and this one?',
          'SETTLEMENT_REFERENCE_MISSING' => 'This layout does not carry the payout\'s number: type it.',
          'SETTLEMENT_PAYOUT_DATE_MISSING' => 'This layout does not say when it was paid: choose the date.',
          'SETTLEMENT_FILE_WRONG_LAYOUT' => 'The file is not in the layout chosen.',
          'SETTLEMENT_FILE_MANY_PAYOUTS' => 'The file covers more than one payout: import one at a time.',
          'SETTLEMENT_CURRENCY_MIXED' => 'The payout is not in the currency this business takes payments in.',
          'PAYLOAD_TOO_LARGE' => 'The file is too large to import in one go.',
          'CARD_DATA_NOT_ACCEPTED' => 'The file carries what looks like a full card number. Ask the acquirer for a report with masked numbers.',
          // A file that cannot be read says which line and why; that is worth showing as it is.
          'SETTLEMENT_FILE_INVALID' => friendlyError(e),
          _ => friendlyError(e),
        };
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Import a payout'),
      content: SizedBox(
        width: 480,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('One file per payout, as the acquirer exports it. Every line is matched to the card payments taken.'),
              const SizedBox(height: 12),
              TextField(key: const Key('import-provider'), controller: _provider, decoration: const InputDecoration(labelText: 'Who paid *', hintText: 'e.g. Worldpay')),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                key: const Key('import-format'),
                isExpanded: true,
                initialValue: _format,
                decoration: const InputDecoration(labelText: 'The file\'s layout'),
                items: [for (final f in settlementFormatLabels.entries) DropdownMenuItem(value: f.key, child: Text(f.value))],
                onChanged: (v) => setState(() => _format = v ?? 'STOREQL'),
              ),
              const SizedBox(height: 8),
              TextField(
                key: const Key('import-reference'),
                controller: _reference,
                decoration: InputDecoration(
                  labelText: 'Payout number${_format == 'STOREQL' ? ' *' : ''}',
                  helperText: _format == 'STOREQL' ? null : 'The file says; type it only if it does not',
                ),
              ),
              const SizedBox(height: 8),
              TextField(
                key: const Key('import-declared'),
                controller: _declared,
                keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
                decoration: const InputDecoration(labelText: 'Sum on the bank statement', helperText: 'The file must add up to it'),
              ),
              const SizedBox(height: 8),
              ListTile(
                key: const Key('import-date'),
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.event_outlined),
                title: Text(_paidOn == null ? 'Paid on${_format == 'STOREQL' ? ' *' : ''}' : 'Paid on ${AppFormat.date(_paidOn!.toIso8601String())}'),
                onTap: () async {
                  final now = DateTime.now();
                  final picked = await showDatePicker(context: context, firstDate: now.subtract(const Duration(days: 730)), lastDate: now, initialDate: now);
                  if (picked != null) setState(() => _paidOn = picked);
                },
              ),
              ListTile(
                key: const Key('import-file'),
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.attach_file),
                title: Text(_fileName ?? (_content == null ? 'Choose the settlement file *' : 'File ready (${_content!.length} characters)')),
                onTap: _busy ? null : _pick,
              ),
              if (_error != null) ...[
                const SizedBox(height: 8),
                Text(_error!, key: const Key('import-error'), style: TextStyle(color: cs.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(key: const Key('import-save'), onPressed: _busy ? null : _save, child: Text(_busy ? 'Importing…' : 'Import')),
      ],
    );
  }
}
