import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/status_badge.dart';
import 'package:storeql_app/core/ids.dart';
import '../../shared/widgets/empty_state.dart';

// ---------------------------------------------------------------------------
// Chargebacks (11.9).
//
// A cardholder asks their bank for a card payment back; the bank takes it from
// the business — usually with a fee — and gives the business until a date to
// answer with evidence. payment-svc keeps each dispute with its history and the
// answer given; this screen is the register: what needs an answer and by when,
// the answer itself, accepting one not worth contesting, and — for a card taken
// on a terminal the platform does not talk to — recording the acquirer's letter
// and, later, how it ended.
// ---------------------------------------------------------------------------

class Dispute {
  final String id;
  final String paymentId;
  final String orderId;
  final String provider;
  final String? reference;
  final num amount;
  final num feeAmount;
  final String currency;
  final String reason;
  final String status;
  final bool fundsWithdrawn;
  final String? evidenceDueBy;
  final bool overdue;
  final String openedAt;

  const Dispute({
    required this.id,
    required this.paymentId,
    required this.orderId,
    required this.provider,
    required this.reference,
    required this.amount,
    required this.feeAmount,
    required this.currency,
    required this.reason,
    required this.status,
    required this.fundsWithdrawn,
    required this.evidenceDueBy,
    required this.overdue,
    required this.openedAt,
  });

  factory Dispute.fromJson(Map<String, dynamic> j) => Dispute(
        id: j['id'] as String,
        paymentId: j['paymentId'] as String,
        orderId: j['orderId'] as String,
        provider: j['provider'] as String? ?? 'MANUAL',
        reference: j['reference'] as String?,
        amount: j['amount'] as num? ?? 0,
        feeAmount: j['feeAmount'] as num? ?? 0,
        currency: j['currency'] as String? ?? '',
        reason: j['reason'] as String? ?? 'GENERAL',
        status: j['status'] as String? ?? '',
        fundsWithdrawn: j['fundsWithdrawn'] == true,
        evidenceDueBy: j['evidenceDueBy'] as String?,
        overdue: j['overdue'] == true,
        openedAt: j['openedAt'] as String? ?? '',
      );

  bool get open => status == 'NEEDS_RESPONSE' || status == 'UNDER_REVIEW';

  /// Whether the acquirer told the business, so the business tells us how it ended.
  bool get manual => provider == 'MANUAL';
}

class DisputeHistoryEntry {
  final String kind;
  final String? detail;
  final String at;
  const DisputeHistoryEntry({required this.kind, required this.detail, required this.at});
}

class DisputeFile {
  final Dispute dispute;
  final List<DisputeHistoryEntry> history;
  final Map<String, dynamic>? evidence;
  const DisputeFile({required this.dispute, required this.history, required this.evidence});

  factory DisputeFile.fromJson(Map<String, dynamic> j) => DisputeFile(
        dispute: Dispute.fromJson(Map<String, dynamic>.from(j['dispute'] as Map)),
        history: [
          for (final h in j['history'] as List<dynamic>? ?? const [])
            DisputeHistoryEntry(
              kind: (h as Map)['kind'] as String? ?? '',
              detail: h['detail'] as String?,
              at: h['at'] as String? ?? '',
            ),
        ],
        evidence: j['evidence'] == null ? null : Map<String, dynamic>.from(j['evidence'] as Map),
      );
}

const _base = '/${ApiConstants.payment}/admin/disputes';

/// What a status, a reason and a history entry are called on screen.
String disputeStatusLabel(String status) => switch (status) {
      'NEEDS_RESPONSE' => 'Needs an answer',
      'UNDER_REVIEW' => 'With the bank',
      'WON' => 'Won',
      'LOST' => 'Lost',
      'ACCEPTED' => 'Accepted',
      _ => humanizeCode(status),
    };

/// The tone a dispute's status is shown in: one waiting on the business needs a
/// look (and is an error once its date has passed), one with the bank waits on
/// someone else, won is good, lost is a failure, accepted is simply closed.
StatusTone disputeStatusTone(String status, {bool overdue = false}) => switch (status) {
      'NEEDS_RESPONSE' => overdue ? StatusTone.error : StatusTone.warning,
      'UNDER_REVIEW' => StatusTone.info,
      'WON' => StatusTone.success,
      'LOST' => StatusTone.error,
      _ => StatusTone.neutral,
    };

String disputeReasonLabel(String reason) => switch (reason) {
      'FRAUDULENT' => 'Says they did not make it',
      'PRODUCT_NOT_RECEIVED' => 'Says they never got it',
      'PRODUCT_UNACCEPTABLE' => 'Says it was not as described',
      'DUPLICATE' => 'Says they were charged twice',
      'CREDIT_NOT_PROCESSED' => 'Says a refund never came',
      'SUBSCRIPTION_CANCELLED' => 'Says they had cancelled',
      'UNRECOGNIZED' => 'Does not recognise it',
      _ => 'Other',
    };

const disputeReasons = [
  'FRAUDULENT',
  'PRODUCT_NOT_RECEIVED',
  'PRODUCT_UNACCEPTABLE',
  'DUPLICATE',
  'CREDIT_NOT_PROCESSED',
  'SUBSCRIPTION_CANCELLED',
  'UNRECOGNIZED',
  'GENERAL',
];

String _historyLabel(String kind) => switch (kind) {
      'OPENED' => 'Opened',
      'FUNDS_WITHDRAWN' => 'The bank took the money',
      'FUNDS_REINSTATED' => 'The money came back',
      'EVIDENCE_SUBMITTED' => 'Answered',
      'ACCEPTED' => 'Accepted, not contested',
      'WON' => 'Won',
      'LOST' => 'Lost',
      _ => humanizeCode(kind),
    };

/// A moment on the shop's own clock (`27 Sept 2026 23:59`), or a dash for none.
String _day(String? iso) => iso == null || iso.isEmpty ? '—' : AppFormat.dateTime(iso);

/// A dispute's sum in its currency: `£42.50`.
String _money(Dispute d, num amount) => AppFormat.money(amount, currencyCode: d.currency);

/// Which status the register shows; null for all of them.
class DisputeStatusFilter extends Notifier<String?> {
  @override
  String? build() => null;

  void show(String? status) => state = status;
}

final disputesStatusFilterProvider = NotifierProvider.autoDispose<DisputeStatusFilter, String?>(DisputeStatusFilter.new);

final disputesProvider = FutureProvider.autoDispose<List<Dispute>>((ref) async {
  final status = ref.watch(disputesStatusFilterProvider);
  final resp = await ref.watch(apiClientProvider).dio.get(
    _base,
    queryParameters: {'limit': 100, 'status': ?status},
  );
  return [
    for (final d in resp.data['data'] as List<dynamic>) Dispute.fromJson(Map<String, dynamic>.from(d as Map)),
  ];
});

class DisputesScreen extends ConsumerWidget {
  const DisputesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final disputes = ref.watch(disputesProvider);
    final filter = ref.watch(disputesStatusFilterProvider);
    void refresh() => ref.invalidate(disputesProvider);

    final actions = [
      OutlinedButton.icon(
        key: const Key('dispute-record'),
        icon: const Icon(Icons.add),
        label: const Text('Record a chargeback'),
        onPressed: () async {
          final recorded = await showDialog<bool>(context: context, builder: (_) => const RecordDisputeDialog());
          if (recorded == true) refresh();
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
                  title: 'Chargebacks',
                  subtitle: 'A customer\'s bank has taken a card payment back. Answer by the date, or it is lost.',
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
                    for (final s in const [null, 'NEEDS_RESPONSE', 'UNDER_REVIEW', 'WON', 'LOST', 'ACCEPTED'])
                      ChoiceChip(
                        label: Text(s == null ? 'All' : disputeStatusLabel(s)),
                        selected: filter == s,
                        onSelected: (_) => ref.read(disputesStatusFilterProvider.notifier).show(s),
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
          sliver: disputes.when(
            loading: () => const SliverFillRemaining(
              hasScrollBody: false,
              child: LoadingView(label: 'Loading chargebacks…'),
            ),
            error: (e, _) => SliverFillRemaining(
              hasScrollBody: false,
              child: ErrorView(message: friendlyError(e, fallback: 'Could not load chargebacks.'), onRetry: refresh),
            ),
            data: (list) => list.isEmpty
                ? const SliverFillRemaining(
                    hasScrollBody: false,
                    child: EmptyState(title: 'No chargebacks. Long may it last.'),
                  )
                : SliverList.separated(
                    itemCount: list.length,
                    separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.sm),
                    itemBuilder: (context, i) => _DisputeTile(
                      dispute: list[i],
                      onOpen: () async {
                        await showDialog<void>(context: context, builder: (_) => DisputeDialog(id: list[i].id));
                        refresh();
                      },
                    ),
                  ),
          ),
        ),
      ],
    );
  }
}

class _DisputeTile extends StatelessWidget {
  final Dispute dispute;
  final VoidCallback onOpen;
  const _DisputeTile({required this.dispute, required this.onOpen});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final d = dispute;
    final urgent = d.status == 'NEEDS_RESPONSE';
    final badge = StatusBadge(disputeStatusLabel(d.status), tone: disputeStatusTone(d.status, overdue: d.overdue));
    final line = Text(
      d.overdue
          ? 'The date to answer has passed (${_day(d.evidenceDueBy)})'
          : urgent
              ? 'Answer by ${_day(d.evidenceDueBy)}'
              : 'Opened ${_day(d.openedAt)}${d.reference == null ? '' : ' · ${d.reference}'}',
      style: TextStyle(color: d.overdue ? cs.error : null),
    );
    return LayoutBuilder(builder: (context, constraints) {
      // On a phone, or with large text, the badge goes under the date rather
      // than taking the width the words need.
      final below = constraints.maxWidth < AppBreakpoints.medium ||
          MediaQuery.textScalerOf(context).scale(16) > 16 * 1.3;
      return Card(
        child: ListTile(
          key: Key('dispute-${d.id}'),
          onTap: onOpen,
          leading: Icon(
            urgent ? Icons.report_gmailerrorred_outlined : Icons.gavel_outlined,
            color: urgent ? cs.error : cs.outline,
          ),
          title: Text('${_money(d, d.amount)} · ${disputeReasonLabel(d.reason)}'),
          subtitle: below
              ? Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [line, const SizedBox(height: AppSpacing.xs), badge],
                )
              : line,
          trailing: below ? null : badge,
        ),
      );
    });
  }
}

/// One dispute: what happened, the answer, and what can still be done about it.
class DisputeDialog extends ConsumerStatefulWidget {
  final String id;
  const DisputeDialog({super.key, required this.id});

  @override
  ConsumerState<DisputeDialog> createState() => _DisputeDialogState();
}

class _DisputeDialogState extends ConsumerState<DisputeDialog> {
  DisputeFile? _file;
  String? _error;
  bool _busy = false;
  bool _answering = false;
  final _fields = {
    for (final k in const [
      'productDescription',
      'customerName',
      'customerEmail',
      'receiptReference',
      'fulfilmentProof',
      'customerCommunication',
      'refundPolicy',
      'notes',
    ])
      k: TextEditingController(),
  };

  Dio get _dio => ref.read(apiClientProvider).dio;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    for (final c in _fields.values) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final resp = await _dio.get('$_base/${widget.id}');
      if (mounted) setState(() => _file = DisputeFile.fromJson(Map<String, dynamic>.from(resp.data['data'] as Map)));
    } catch (e) {
      if (mounted) setState(() => _error = friendlyError(e));
    }
  }

  Future<void> _act(String path, [Map<String, dynamic>? body]) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final resp = await _dio.post('$_base/${widget.id}/$path', data: body ?? const <String, dynamic>{});
      if (!mounted) return;
      setState(() {
        _file = DisputeFile.fromJson(Map<String, dynamic>.from(resp.data['data'] as Map));
        _answering = false;
        _busy = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = switch (apiErrorCode(e)) {
          'DISPUTE_EVIDENCE_LATE' => 'The date for answering has passed.',
          'DISPUTE_EVIDENCE_EMPTY' => 'Say something the bank can weigh: what was sold, the receipt, how it was handed over.',
          'DISPUTE_NOT_AWAITING_RESPONSE' => 'This chargeback has already been answered.',
          'DISPUTE_CLOSED' => 'This chargeback is already closed.',
          _ => friendlyError(e),
        };
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final file = _file;
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return AlertDialog(
      title: Text(file == null ? 'Chargeback' : '${_money(file.dispute, file.dispute.amount)} · ${disputeStatusLabel(file.dispute.status)}'),
      content: SizedBox(
        width: 560,
        child: file == null
            ? (_error == null ? const SizedBox(height: 120, child: Center(child: CircularProgressIndicator())) : Text(_error!))
            : SingleChildScrollView(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(disputeReasonLabel(file.dispute.reason), style: text.titleMedium),
                    const SizedBox(height: 4),
                    Text(
                      [
                        if (file.dispute.reference != null) 'Case ${file.dispute.reference}',
                        file.dispute.manual ? 'told by the acquirer' : 'told by ${file.dispute.provider}',
                        if (file.dispute.feeAmount > 0) 'fee ${_money(file.dispute, file.dispute.feeAmount)}',
                        if (file.dispute.status == 'NEEDS_RESPONSE') 'answer by ${_day(file.dispute.evidenceDueBy)}',
                      ].join(' · '),
                      style: text.bodySmall?.copyWith(color: file.dispute.overdue ? cs.error : cs.outline),
                    ),
                    const Divider(height: 24),
                    for (final h in file.history)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 6),
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            SizedBox(width: 150, child: Text(_day(h.at), style: text.bodySmall)),
                            Expanded(child: Text(h.detail == null ? _historyLabel(h.kind) : '${_historyLabel(h.kind)} — ${h.detail}')),
                          ],
                        ),
                      ),
                    if (file.evidence != null) ...[
                      const Divider(height: 24),
                      Text('The answer given', style: text.titleSmall),
                      const SizedBox(height: 4),
                      for (final e in file.evidence!.entries)
                        if (e.value is String && !e.key.startsWith('submitted')) Text('${_evidenceLabel(e.key)}: ${e.value}'),
                    ],
                    if (_answering) ...[
                      const Divider(height: 24),
                      Text('Your answer — it can be given once', style: text.titleSmall),
                      for (final f in _fields.entries)
                        Padding(
                          padding: const EdgeInsets.only(top: 8),
                          child: TextField(
                            key: Key('evidence-${f.key}'),
                            controller: f.value,
                            maxLines: f.key == 'customerName' || f.key == 'customerEmail' || f.key == 'receiptReference' ? 1 : 2,
                            decoration: InputDecoration(labelText: _evidenceLabel(f.key)),
                          ),
                        ),
                    ],
                    if (_error != null) ...[
                      const SizedBox(height: 12),
                      Text(_error!, key: const Key('dispute-error'), style: TextStyle(color: cs.error)),
                    ],
                  ],
                ),
              ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Close')),
        if (file != null && file.dispute.open && !_answering) ...[
          TextButton(
            key: const Key('dispute-accept'),
            onPressed: _busy ? null : () => _act('accept'),
            child: const Text('Accept it'),
          ),
          if (file.dispute.manual) ...[
            TextButton(
              key: const Key('dispute-lost'),
              onPressed: _busy ? null : () => _act('resolve', {'outcome': 'LOST'}),
              child: const Text('It was lost'),
            ),
            TextButton(
              key: const Key('dispute-won'),
              onPressed: _busy ? null : () => _act('resolve', {'outcome': 'WON'}),
              child: const Text('It was won'),
            ),
          ],
          if (file.dispute.status == 'NEEDS_RESPONSE' && !file.dispute.overdue)
            FilledButton(
              key: const Key('dispute-answer'),
              onPressed: _busy ? null : () => setState(() => _answering = true),
              child: const Text('Answer it'),
            ),
        ],
        if (_answering)
          FilledButton(
            key: const Key('dispute-send'),
            onPressed: _busy
                ? null
                : () => _act('evidence', {
                      for (final f in _fields.entries)
                        if (f.value.text.trim().isNotEmpty) f.key: f.value.text.trim(),
                    }),
            child: Text(_busy ? 'Sending…' : 'Send the answer'),
          ),
      ],
    );
  }

  static String _evidenceLabel(String key) => switch (key) {
        'productDescription' => 'What was sold',
        'customerName' => 'Customer\'s name',
        'customerEmail' => 'Customer\'s email',
        'receiptReference' => 'Receipt or invoice number',
        'fulfilmentProof' => 'How it was handed over (collected, delivered, signed for)',
        'customerCommunication' => 'What was said to the customer',
        'refundPolicy' => 'Your refund policy, as the customer saw it',
        'notes' => 'Anything else',
        _ => key,
      };
}

/// Recording a chargeback the acquirer has written to the business about: the
/// card was taken on a terminal the platform does not talk to.
class RecordDisputeDialog extends ConsumerStatefulWidget {
  const RecordDisputeDialog({super.key});

  @override
  ConsumerState<RecordDisputeDialog> createState() => _RecordDisputeDialogState();
}

class _RecordDisputeDialogState extends ConsumerState<RecordDisputeDialog> {
  final _payment = TextEditingController();
  final _amount = TextEditingController();
  final _fee = TextEditingController();
  final _caseRef = TextEditingController();
  final _networkCode = TextEditingController();
  String _reason = 'GENERAL';
  DateTime? _dueBy;
  String? _error;
  bool _busy = false;

  @override
  void dispose() {
    for (final c in [_payment, _amount, _fee, _caseRef, _networkCode]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    if (_payment.text.trim().isEmpty || _caseRef.text.trim().isEmpty || _dueBy == null) {
      setState(() => _error = 'The payment, the acquirer\'s case number and the date to answer by are needed.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        _base,
        data: {
          'paymentId': _payment.text.trim(),
          'amount': ?num.tryParse(_amount.text.trim()),
          'feeAmount': ?num.tryParse(_fee.text.trim()),
          'reason': _reason,
          'networkReasonCode': ?(_networkCode.text.trim().isEmpty ? null : _networkCode.text.trim()),
          'caseReference': _caseRef.text.trim(),
          'evidenceDueBy': _dueBy!.toUtc().toIso8601String(),
        },
        // The same payment and case entered twice is one dispute, so the key is derived from them.
        options: Options(headers: {'Idempotency-Key': derivedId(_payment.text.trim(), 'dispute:${_caseRef.text.trim()}')}),
      );
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = switch (apiErrorCode(e)) {
          'PAYMENT_NOT_FOUND' => 'No such payment. Copy its id from the order\'s payments.',
          'DISPUTE_NOT_DISPUTABLE' => 'Only a card payment can be charged back.',
          'DISPUTE_ALREADY_OPEN' => 'This payment already has a chargeback open.',
          'DISPUTE_AMOUNT_EXCEEDS_PAYMENT' => 'A chargeback cannot be for more than was paid.',
          'DISPUTE_DUE_DATE_PAST' => 'That date has already passed.',
          _ => friendlyError(e),
        };
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Record a chargeback'),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'For a card taken on your own terminal, when the acquirer writes to say the payment has been '
                'disputed. Payments taken online are told to us by the payment provider.',
              ),
              const SizedBox(height: 12),
              TextField(key: const Key('dispute-payment'), controller: _payment, decoration: const InputDecoration(labelText: 'Payment id *')),
              const SizedBox(height: 8),
              TextField(key: const Key('dispute-case'), controller: _caseRef, decoration: const InputDecoration(labelText: 'Acquirer\'s case number *')),
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      key: const Key('dispute-amount'),
                      controller: _amount,
                      keyboardType: const TextInputType.numberWithOptions(decimal: true),
                      decoration: const InputDecoration(labelText: 'Amount disputed', helperText: 'The whole payment if empty'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextField(
                      key: const Key('dispute-fee'),
                      controller: _fee,
                      keyboardType: const TextInputType.numberWithOptions(decimal: true),
                      decoration: const InputDecoration(labelText: 'Acquirer\'s fee'),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                key: const Key('dispute-reason'),
                isExpanded: true,
                initialValue: _reason,
                decoration: const InputDecoration(labelText: 'What the cardholder says'),
                items: [for (final r in disputeReasons) DropdownMenuItem(value: r, child: Text(disputeReasonLabel(r)))],
                onChanged: (v) => setState(() => _reason = v ?? 'GENERAL'),
              ),
              const SizedBox(height: 8),
              TextField(controller: _networkCode, decoration: const InputDecoration(labelText: 'Card scheme\'s reason code', hintText: 'e.g. 13.1')),
              const SizedBox(height: 8),
              ListTile(
                key: const Key('dispute-due'),
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.event_outlined),
                title: Text(_dueBy == null ? 'Answer by *' : 'Answer by ${AppFormat.date(_dueBy!.toIso8601String())}'),
                onTap: () async {
                  final now = DateTime.now();
                  final picked = await showDatePicker(
                    context: context,
                    firstDate: now,
                    lastDate: now.add(const Duration(days: 180)),
                    initialDate: now.add(const Duration(days: 14)),
                  );
                  // The end of the day named: a letter gives a date, not an hour.
                  if (picked != null) setState(() => _dueBy = DateTime(picked.year, picked.month, picked.day, 23, 59));
                },
              ),
              if (_error != null) ...[
                const SizedBox(height: 8),
                Text(_error!, key: const Key('dispute-record-error'), style: TextStyle(color: cs.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(false), child: const Text('Cancel')),
        FilledButton(key: const Key('dispute-save'), onPressed: _busy ? null : _save, child: Text(_busy ? 'Saving…' : 'Record')),
      ],
    );
  }
}
