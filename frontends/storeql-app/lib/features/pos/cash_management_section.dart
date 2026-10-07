import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../admin/providers/staff_names.dart';
import '../admin/providers/admin_providers.dart' show tenantInfoProvider;
import 'cash_providers.dart';
import 'pos_providers.dart';

/// What a manager does with the tills of the store this terminal is at, beside
/// the till itself: end a cashier's open session (with a reason), and settle
/// or correct the store's day. Shown to an owner or a manager only; a cashier
/// sees none of it, because the server would refuse each.
class TillManagementSection extends ConsumerWidget {
  const TillManagementSection({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    if (auth is! AuthAuthenticated || !auth.isManager) {
      return const SizedBox.shrink();
    }
    final storeId = ref.watch(posStoreProvider);
    if (storeId == null) return const SizedBox.shrink();
    return Column(
      key: const Key('till-management'),
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: AppSpacing.xl),
        OpenSessionsCard(storeId: storeId),
        if (auth.hasPermission('till.manage')) ...[
          const SizedBox(height: AppSpacing.lg),
          DayReportCard(storeId: storeId),
        ],
      ],
    );
  }
}

// ── Open sessions ────────────────────────────────────────────────────────────

class OpenSessionsCard extends ConsumerWidget {
  final String storeId;
  const OpenSessionsCard({super.key, required this.storeId});

  Future<void> _end(
      BuildContext context, WidgetRef ref, OpenPosSession s, String who) async {
    final reason = await showDialog<String>(
      context: context,
      builder: (_) => _ReasonDialog(
        title: 'End session',
        message: '$who will be signed out of the till at once.',
        confirm: 'End session',
      ),
    );
    if (reason == null || !context.mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    final errorColor = Theme.of(context).colorScheme.error;
    try {
      await ref.read(apiClientProvider).dio.delete(
        '/${ApiConstants.iam}/auth/pos/sessions/${s.id}',
        queryParameters: {'reason': reason},
      );
      ref.invalidate(openPosSessionsProvider(storeId));
      messenger.showSnackBar(const SnackBar(content: Text('Session ended.')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(
        content: Text(friendlyError(e, fallback: 'Could not end the session.')),
        backgroundColor: errorColor,
      ));
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final async = ref.watch(openPosSessionsProvider(storeId));
    final names = async.value == null
        ? const <String, String>{}
        : ref
                .watch(staffLoginsProvider(
                    staffIdsKey(async.value!.map((s) => s.userId))))
                .value ??
            const <String, String>{};
    return Card(
      key: const Key('open-sessions-card'),
      child: Padding(
        padding: AppSpacing.cardPadding,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Signed in at this store', style: theme.textTheme.titleMedium),
            const SizedBox(height: AppSpacing.xs),
            Text(
              'Staff clocked in to a till here. Ending a session signs that person out of the till.',
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
            const SizedBox(height: AppSpacing.md),
            async.when(
              loading: () => const LoadingView(label: 'Loading sessions…'),
              error: (e, _) => ErrorView(
                message: friendlyError(e,
                    fallback: 'The open sessions could not be loaded.'),
                onRetry: () => ref.invalidate(openPosSessionsProvider(storeId)),
              ),
              data: (sessions) => sessions.isEmpty
                  ? const EmptyState(
                      icon: Icons.person_off_outlined,
                      title: 'Nobody is signed in at a till here')
                  : Column(children: [
                      for (final s in sessions)
                        _row(context, ref, s,
                            staffDisplayName(s.userId, names)),
                    ]),
            ),
          ],
        ),
      ),
    );
  }

  Widget _row(BuildContext context, WidgetRef ref, OpenPosSession s, String who) {
    final last = s.lastActivityAt;
    return ListTile(
      key: Key('open-session-${s.id}'),
      contentPadding: EdgeInsets.zero,
      title: Text(who),
      subtitle: Text([
        'Since ${AppFormat.dateTime(s.startedAt)}',
        if (last != null && last.isNotEmpty)
          'last active ${AppFormat.time(last)}',
      ].join(' · ')),
      trailing: OutlinedButton(
        onPressed: () => _end(context, ref, s, who),
        child: const Text('End session'),
      ),
    );
  }
}

/// A required reason (up to 200 characters), asked before something a
/// manager does to another person's session. Returns the trimmed reason.
class _ReasonDialog extends StatefulWidget {
  final String title;
  final String message;
  final String confirm;
  const _ReasonDialog(
      {required this.title, required this.message, required this.confirm});

  @override
  State<_ReasonDialog> createState() => _ReasonDialogState();
}

class _ReasonDialogState extends State<_ReasonDialog> {
  final _reason = TextEditingController();

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final reason = _reason.text.trim();
    return AlertDialog(
      title: Text(widget.title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(widget.message),
          const SizedBox(height: AppSpacing.md),
          TextField(
            key: const Key('reason-field'),
            controller: _reason,
            autofocus: true,
            maxLength: 200,
            decoration: const InputDecoration(labelText: 'Reason *'),
            onChanged: (_) => setState(() {}),
          ),
        ],
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel')),
        FilledButton(
          onPressed: reason.isEmpty ? null : () => Navigator.pop(context, reason),
          child: Text(widget.confirm),
        ),
      ],
    );
  }
}

// ── The store's day ──────────────────────────────────────────────────────────

class DayReportCard extends ConsumerStatefulWidget {
  final String storeId;
  const DayReportCard({super.key, required this.storeId});

  @override
  ConsumerState<DayReportCard> createState() => _DayReportCardState();
}

class _DayReportCardState extends ConsumerState<DayReportCard> {
  /// The day asked for; null is today where the store is, which only the
  /// server knows (this device may sit in another time zone).
  DateTime? _day;
  final _counted = TextEditingController();
  DayReport? _report;

  /// What asking said: "written" for a day just settled, "stored" for one that
  /// already was.
  String? _outcome;
  String? _error;
  bool _busy = false;

  @override
  void dispose() {
    _counted.dispose();
    super.dispose();
  }

  /// The day picked, as the server reads it; null while none is picked.
  String? get _isoDay {
    final d = _day;
    if (d == null) return null;
    return '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
  }

  Future<void> _pickDay() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _day ?? DateTime.now(),
      firstDate: DateTime(2020),
      lastDate: DateTime.now().add(const Duration(days: 1)),
    );
    if (picked != null) {
      setState(() {
        _day = picked;
        _report = null;
        _outcome = null;
      });
    }
  }

  Future<void> _run(Future<void> Function() call) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await call();
    } catch (e) {
      if (mounted) {
        setState(() => _error =
            friendlyError(e, fallback: 'The day report could not be produced.'));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Settles the day, or answers the report already stored for it.
  Future<void> _settle() async {
    final counted = double.tryParse(_counted.text.trim());
    if (counted == null || counted < 0) {
      setState(() => _error = 'Enter the cash counted for the day.');
      return;
    }
    await _run(() async {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.payment}/admin/cash/z-report',
        data: {
          'storeId': widget.storeId,
          if (_isoDay != null) 'businessDate': _isoDay,
          'countedCash': counted,
        },
      );
      final r = DayReport.fromJson(resp.data['data'] as Map<String, dynamic>);
      if (!mounted) return;
      setState(() {
        _report = r;
        _outcome = r.regenerated ? 'written' : 'stored';
      });
    });
  }

  /// The report already stored for the day, with no count to give.
  Future<void> _showStored() => _run(() async {
        try {
          final resp = await ref.read(apiClientProvider).dio.get(
            '/${ApiConstants.payment}/admin/cash/z-report',
            queryParameters: {
              'storeId': widget.storeId,
              if (_isoDay != null) 'businessDate': _isoDay,
            },
          );
          final r = DayReport.fromJson(resp.data['data'] as Map<String, dynamic>);
          if (!mounted) return;
          setState(() {
            _report = r;
            _outcome = 'stored';
          });
        } catch (e) {
          if (apiErrorCode(e) == 'Z_REPORT_NOT_FOUND') {
            if (!mounted) return;
            setState(() {
              _report = null;
              _outcome = null;
              _error = 'This day has not been settled yet.';
            });
            return;
          }
          rethrow;
        }
      });

  Future<void> _correct(DayReport current) async {
    final result = await showDialog<({double counted, String reason})>(
      context: context,
      builder: (_) => _CorrectionDialog(current: current),
    );
    if (result == null) return;
    await _run(() async {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.payment}/admin/cash/z-report',
        data: {
          'storeId': widget.storeId,
          'businessDate': current.businessDate,
          'countedCash': result.counted,
          'correctionOf': current.id,
          'reason': result.reason,
        },
      );
      final r = DayReport.fromJson(resp.data['data'] as Map<String, dynamic>);
      if (!mounted) return;
      setState(() {
        _report = r;
        _outcome = 'written';
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final symbol =
        AppFormat.currencySymbol(ref.watch(tenantInfoProvider).value?.currency);
    final r = _report;
    return Card(
      key: const Key('day-report-card'),
      child: Padding(
        padding: AppSpacing.cardPadding,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Day report', style: theme.textTheme.titleMedium),
            const SizedBox(height: AppSpacing.xs),
            Text(
              "Settles the store's own day, midnight to midnight in the store's time zone. "
              'A settled day is kept: asking again shows it, and a correction is a new version with its reason.',
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: cs.onSurfaceVariant),
            ),
            const SizedBox(height: AppSpacing.md),
            Wrap(
              spacing: AppSpacing.md,
              runSpacing: AppSpacing.md,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                OutlinedButton.icon(
                  key: const Key('day-report-date'),
                  onPressed: _busy ? null : _pickDay,
                  icon: const Icon(Icons.calendar_today_outlined, size: 18),
                  label: Text(_day == null
                      ? "Today at the store"
                      : AppFormat.dateOf(_day!)),
                ),
                SizedBox(
                  width: 200,
                  child: TextField(
                    key: const Key('day-report-counted'),
                    controller: _counted,
                    keyboardType:
                        const TextInputType.numberWithOptions(decimal: true),
                    decoration: InputDecoration(
                      labelText: 'Cash counted for the day',
                      prefixText: symbol.isEmpty ? null : '$symbol ',
                    ),
                  ),
                ),
                FilledButton(
                  key: const Key('day-report-settle'),
                  onPressed: _busy ? null : _settle,
                  child: const Text('Settle the day'),
                ),
                TextButton(
                  key: const Key('day-report-stored'),
                  onPressed: _busy ? null : _showStored,
                  child: const Text('Show the stored report'),
                ),
              ],
            ),
            if (_error != null) ...[
              const SizedBox(height: AppSpacing.md),
              Text(_error!,
                  key: const Key('day-report-error'),
                  style: TextStyle(color: cs.error)),
            ],
            if (r != null) ...[
              const Divider(height: AppSpacing.xxl),
              _DayReportView(
                report: r,
                outcome: _outcome,
                onCorrect: _busy ? null : () => _correct(r),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _DayReportView extends StatelessWidget {
  final DayReport report;
  final String? outcome;
  final VoidCallback? onCorrect;
  const _DayReportView(
      {required this.report, required this.outcome, required this.onCorrect});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final r = report;
    String money(double v) => AppFormat.money(v, currencyCode: r.currency);
    Widget row(String label, double v, {bool bold = false}) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 3),
          child: Row(children: [
            Expanded(
                child: Text(label,
                    style: TextStyle(
                        fontWeight: bold ? FontWeight.bold : FontWeight.normal))),
            Text(money(v),
                style: TextStyle(
                    fontWeight: bold ? FontWeight.bold : FontWeight.normal)),
          ]),
        );
    final over = r.overShort;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          'The store\'s day of ${AppFormat.date(r.businessDate)}'
          '${r.timeZone != null && !r.zoneAssumed ? ' (${r.timeZone})' : ''}',
          key: const Key('day-report-title'),
          style: theme.textTheme.titleSmall,
        ),
        if (r.zoneAssumed)
          Padding(
            padding: const EdgeInsets.only(top: AppSpacing.xs),
            child: Text(
              "This store's time zone could not be read, so the day was counted "
              'midnight to midnight UTC (Coordinated Universal Time). Set the store\'s time zone to count its own day.',
              key: const Key('day-report-zone-assumed'),
              style: TextStyle(color: cs.error),
            ),
          ),
        if (outcome == 'stored')
          Padding(
            padding: const EdgeInsets.only(top: AppSpacing.xs),
            child: Text('This day was already settled: this is the stored report.',
                key: const Key('day-report-stored-note'),
                style: theme.textTheme.bodySmall
                    ?.copyWith(color: cs.onSurfaceVariant)),
          ),
        if (outcome == 'written')
          Padding(
            padding: const EdgeInsets.only(top: AppSpacing.xs),
            child: Text('The day is settled.',
                key: const Key('day-report-written-note'),
                style: theme.textTheme.bodySmall
                    ?.copyWith(color: cs.onSurfaceVariant)),
          ),
        if (r.version > 1)
          Padding(
            padding: const EdgeInsets.only(top: AppSpacing.xs),
            child: Text(
              'Corrected: version ${r.version}'
              '${r.correctionReason == null ? '' : ' — ${r.correctionReason}'}',
              key: const Key('day-report-correction'),
              style: theme.textTheme.bodySmall,
            ),
          ),
        const SizedBox(height: AppSpacing.md),
        row('Sales', r.totalSales),
        row('Refunds', r.totalRefunds == 0 ? 0 : -r.totalRefunds),
        row('Net sales', r.netSales, bold: true),
        const Divider(),
        row('Opening float', r.openingFloat),
        row('Cash sales', r.cashSales),
        row('Cash refunds', r.cashRefunds == 0 ? 0 : -r.cashRefunds),
        row('Paid in', r.payIns),
        row('Paid out', r.payOuts == 0 ? 0 : -r.payOuts),
        row('Cash drops', r.cashDrops == 0 ? 0 : -r.cashDrops),
        row('Expected cash', r.expectedCash, bold: true),
        row('Cash counted', r.countedCash),
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 3),
          child: Text(
            over == 0
                ? 'Balanced: no discrepancy.'
                : '${over > 0 ? 'Over' : 'Short'} by ${money(over.abs())}',
            key: const Key('day-report-over-short'),
            style: const TextStyle(fontWeight: FontWeight.bold),
          ),
        ),
        Text('${r.transactionCount} sales',
            style: theme.textTheme.bodySmall
                ?.copyWith(color: cs.onSurfaceVariant)),
        const SizedBox(height: AppSpacing.md),
        Align(
          alignment: AlignmentDirectional.centerStart,
          child: OutlinedButton(
            key: const Key('day-report-correct'),
            onPressed: onCorrect,
            child: const Text('Correct this report'),
          ),
        ),
      ],
    );
  }
}

/// A correction to a settled day: what was really counted, and why the report
/// is being changed (required).
class _CorrectionDialog extends StatefulWidget {
  final DayReport current;
  const _CorrectionDialog({required this.current});

  @override
  State<_CorrectionDialog> createState() => _CorrectionDialogState();
}

class _CorrectionDialogState extends State<_CorrectionDialog> {
  late final TextEditingController _counted = TextEditingController(
      text: widget.current.countedCash.toStringAsFixed(2));
  final _reason = TextEditingController();

  @override
  void dispose() {
    _counted.dispose();
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final counted = double.tryParse(_counted.text.trim());
    final reason = _reason.text.trim();
    return AlertDialog(
      title: const Text('Correct the day report'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
              'The settled report is kept. Your correction is saved as a new version beside it.'),
          const SizedBox(height: AppSpacing.md),
          TextField(
            key: const Key('correction-counted'),
            controller: _counted,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(labelText: 'Cash counted'),
            onChanged: (_) => setState(() {}),
          ),
          const SizedBox(height: AppSpacing.md),
          TextField(
            key: const Key('correction-reason'),
            controller: _reason,
            maxLength: 200,
            decoration: const InputDecoration(labelText: 'Reason *'),
            onChanged: (_) => setState(() {}),
          ),
        ],
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel')),
        FilledButton(
          onPressed: counted == null || counted < 0 || reason.isEmpty
              ? null
              : () => Navigator.pop(context, (counted: counted, reason: reason)),
          child: const Text('Save correction'),
        ),
      ],
    );
  }
}
