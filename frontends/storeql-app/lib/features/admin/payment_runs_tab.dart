import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/util/file_download.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/status_badge.dart';
import 'bank_details_validators.dart';
import 'procurement_providers.dart';
import '../../core/theme.dart';
import '../../core/spacing.dart';
import 'providers/admin_providers.dart' show tenantInfoProvider;

/// Whether the signed-in user may run supplier payments (17.10): a manager
/// holding finance.payments. The server refuses anyone else with 403, so the
/// tab does not offer controls that can only fail.
bool canRunPayments(AuthState? auth) =>
    auth is AuthAuthenticated &&
    auth.isManager &&
    auth.hasPermission('finance.payments');

/// What a warning on a supplier in a run means, in words.
String paymentWarningText(String code) => switch (code) {
  'BANK_DETAILS_CHANGED_RECENTLY' =>
    'Bank details changed in the last 14 days: confirm them with the supplier by phone',
  _ => code,
};

/// Why a supplier with something due is not paid by a run.
String paymentExcludedText(String code) => switch (code) {
  'NO_BANK_DETAILS' => 'No bank details on file',
  'NET_NOT_POSITIVE' => 'Credit notes cover the invoices',
  _ => code,
};

/// What the bank's status report said about a supplier's payment, in words.
String payeeCheckText(PayeeCheck c) {
  if (c.status == 'RJCT') {
    return 'The bank rejected this payment'
        '${c.reasonCode != null ? ' (${c.reasonCode})' : ''}';
  }
  return switch (c.payeeMatch) {
    'MTCH' => 'The bank matched the name on the account',
    'CMTC' =>
      c.matchedName != null
          ? 'Close match: the bank holds the account as "${c.matchedName}"'
          : 'Close match: the name on the account differs',
    'NMTC' =>
      'The bank could not match the name to the account: check the details with the supplier',
    'NOAP' => 'The bank could not check the name on this account',
    _ => 'The bank reports ${c.status}',
  };
}

/// Picks the bank's status report and reads it as text; null when dismissed.
/// A provider so widget tests hand a file in.
final statusReportPickerProvider = Provider<Future<String?> Function()>(
  (ref) => () async {
    final r = await FilePicker.pickFiles(
      type: FileType.custom,
      allowedExtensions: const ['xml'],
      withData: true,
    );
    final bytes = r == null || r.files.isEmpty ? null : r.files.first.bytes;
    return bytes == null ? null : utf8.decode(bytes);
  },
);

/// The largest status report sent; purchase-svc refuses anything bigger.
const maxStatusReportChars = 2000000;

/// A real calendar date as YYYY-MM-DD: 2026-02-30 is refused, not rolled into
/// March.
String? validIsoDate(String? v) {
  final t = v?.trim() ?? '';
  if (!RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(t)) return 'Use YYYY-MM-DD';
  final parsed = DateTime.tryParse(t);
  if (parsed == null || _isoDate(parsed) != t) return 'Not a date';
  return null;
}

String _isoDate(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// Supplier payment runs: propose, approve, download the bank file, mark paid.
class PaymentRunsTab extends ConsumerWidget {
  const PaymentRunsTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    final cs = Theme.of(context).colorScheme;
    if (!canRunPayments(auth)) {
      return Center(
        child: Padding(
          padding: context.pagePadding,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.lock_outline, size: 48, color: cs.outlineVariant),
              const SizedBox(height: 12),
              const Text(
                'Supplier payments need the finance.payments permission.',
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      );
    }
    final me = auth as AuthAuthenticated;
    return ref
        .watch(paymentRunsProvider)
        .when(
          loading: () => const LoadingView(label: 'Loading payment runs…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load payment runs.'),
            onRetry: () => ref.invalidate(paymentRunsProvider),
          ),
          data: (runs) => Column(
            children: [
              Padding(
                padding: EdgeInsetsDirectional.fromSTEB(
                    context.pageGutter, AppSpacing.sm, context.pageGutter, 0),
                child: Align(
                  alignment: AlignmentDirectional.centerEnd,
                  child: TextButton.icon(
                    onPressed: () => showDialog<void>(
                      context: context,
                      builder: (_) => const PayingAccountsDialog(),
                    ),
                    icon: const Icon(Icons.account_balance_outlined),
                    label: const Text('Paying accounts'),
                  ),
                ),
              ),
              Expanded(
                child: runs.isEmpty
                    ? Center(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                              Icons.payments_outlined,
                              size: 64,
                              color: cs.outlineVariant,
                            ),
                            const SizedBox(height: 12),
                            const Text('No payment runs yet'),
                            const SizedBox(height: 4),
                            Text(
                              'Propose one to pay the invoices falling due.',
                              style: TextStyle(color: cs.onSurfaceVariant),
                            ),
                          ],
                        ),
                      )
                    : ListView.separated(
                        padding: context.pagePadding,
                        itemCount: runs.length,
                        separatorBuilder: (_, _) => const SizedBox(height: 8),
                        itemBuilder: (_, i) =>
                            PaymentRunCard(run: runs[i], me: me),
                      ),
              ),
            ],
          ),
        );
  }
}

/// One run: what it pays each supplier, and the next step it is waiting for.
class PaymentRunCard extends ConsumerStatefulWidget {
  const PaymentRunCard({super.key, required this.run, required this.me});
  final PaymentRun run;
  final AuthAuthenticated me;

  @override
  ConsumerState<PaymentRunCard> createState() => _PaymentRunCardState();
}

class _PaymentRunCardState extends ConsumerState<PaymentRunCard> {
  bool _busy = false;

  PaymentRun get run => widget.run;

  /// The proposer cannot approve their own run unless they own the business.
  bool get _ownRun =>
      run.proposedBy == widget.me.userId && !widget.me.roles.contains('OWNER');

  static void _tell(ScaffoldMessengerState messenger, String message) {
    messenger
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _act(
    String action, {
    Map<String, dynamic>? data,
    required String done,
    String? failed,
  }) async {
    setState(() => _busy = true);
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.purchase}/payment-runs/${run.id}/$action',
            data: data ?? const <String, dynamic>{},
          );
      ref.invalidate(paymentRunsProvider);
      if (action == 'pay') ref.invalidate(supplierInvoicesProvider);
      _tell(messenger, done);
    } catch (e) {
      _tell(
        messenger,
        friendlyError(
          e,
          fallback: failed ?? 'Could not $action the payment run.',
        ),
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _pay() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Mark ${run.reference} paid?'),
        content: Text(
          'Pays ${AppFormat.money(run.total, currencyCode: run.currency)} to '
          '${run.suppliers.length} supplier(s) on ${AppFormat.date(run.paymentDate)}. The '
          'invoices are settled, the ledger is posted and each supplier is '
          'sent a remittance advice. Upload the bank file to the bank first.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Not yet'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Mark paid'),
          ),
        ],
      ),
    );
    if (ok == true) {
      await _act('pay', done: 'Payment run ${run.reference} paid.');
    }
  }

  Future<void> _cancel() async {
    final reason = await showDialog<String>(
      context: context,
      builder: (_) => const PaymentRunReasonDialog(
        title: 'Cancel payment run',
        confirm: 'Cancel run',
        keep: 'Keep it',
      ),
    );
    if (reason != null) {
      await _act(
        'cancel',
        data: {'reason': reason},
        done: 'Payment run ${run.reference} cancelled.',
      );
    }
  }

  /// Releases a close match once the supplier has confirmed the account.
  Future<void> _release(PaymentRunSupplier s) async {
    final held = s.bankCheck?.matchedName;
    final reason = await showDialog<String>(
      context: context,
      builder: (_) => PaymentRunReasonDialog(
        title: 'Release the payment to ${s.name}?',
        intro:
            '${held != null ? 'The bank holds this account as "$held". ' : ''}'
            'Release it only once the supplier has confirmed the account is '
            'theirs, and say how.',
        label: 'What you checked *',
        confirm: 'Release',
        keep: 'Keep held',
      ),
    );
    if (reason != null) {
      await _act(
        'payments/${s.supplierId}/release',
        data: {'reason': reason},
        done: 'Payment to ${s.name} released.',
        failed: 'Could not release the payment.',
      );
    }
  }

  Future<void> _bankFile(BankFileFormat format) async {
    setState(() => _busy = true);
    final messenger = ScaffoldMessenger.of(context);
    try {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get<String>(
            '/${ApiConstants.purchase}/payment-runs/${run.id}/bank-file',
            queryParameters: {'format': format.code},
            options: Options(responseType: ResponseType.plain),
          );
      final named = RegExp(
        r'filename="([^"/\\]+)"',
      ).firstMatch(resp.headers.value('content-disposition') ?? '')?.group(1);
      downloadTextFile(
        named ?? '${run.reference.toLowerCase()}.${format.extension}',
        resp.data ?? '',
        mimeType: format.mimeType,
      );
      _tell(messenger, 'Bank file for ${run.reference} downloaded.');
    } catch (e) {
      _tell(
        messenger,
        friendlyError(e, fallback: 'Could not download the bank file.'),
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Reads the bank's pain.002 status report on the run's file.
  Future<void> _statusReport() async {
    final messenger = ScaffoldMessenger.of(context);
    String? xml;
    try {
      xml = await ref.read(statusReportPickerProvider)();
    } on FormatException {
      _tell(
        messenger,
        'That file is not text: choose the XML status report from the bank.',
      );
      return;
    }
    if (xml == null || !mounted) return;
    if (xml.length > maxStatusReportChars) {
      _tell(messenger, 'That file is too large to be a status report.');
      return;
    }
    setState(() => _busy = true);
    try {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.purchase}/payment-runs/${run.id}/status-report',
            data: xml,
            options: Options(contentType: 'application/xml'),
          );
      ref.invalidate(paymentRunsProvider);
      final held = PaymentRun.fromJson(
        (resp.data['data'] as Map).cast<String, dynamic>(),
      ).heldPayments;
      _tell(
        messenger,
        held == 0
            ? 'Status report read: the bank holds nothing in ${run.reference}.'
            : 'Status report read: the bank holds $held payment(s) in ${run.reference}.',
      );
    } catch (e) {
      _tell(
        messenger,
        friendlyError(e, fallback: 'Could not read the status report.'),
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final toCheck = run.suppliers.where((s) => s.warnings.isNotEmpty).length;
    final approve = FilledButton(
      onPressed: _busy || _ownRun
          ? null
          : () =>
                _act('approve', done: 'Payment run ${run.reference} approved.'),
      child: const Text('Approve'),
    );
    final markPaid = FilledButton.icon(
      onPressed: _busy || run.heldPayments > 0 ? null : _pay,
      icon: const Icon(Icons.payments_outlined),
      label: const Text('Mark paid'),
    );
    return Card(
      child: ExpansionTile(
        initiallyExpanded: run.proposed || run.approved,
        leading: Icon(
          run.paid
              ? Icons.check_circle_outline
              : run.cancelled
              ? Icons.block_outlined
              : Icons.payments_outlined,
          color: run.cancelled ? cs.outline : cs.primary,
        ),
        title: Text(
          run.reference,
          style: const TextStyle(fontWeight: FontWeight.bold),
        ),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              [
                AppFormat.money(run.total, currencyCode: run.currency),
                'pay on ${AppFormat.date(run.paymentDate)}',
                'due by ${AppFormat.date(run.payUpTo)}',
                '${run.suppliers.length} supplier(s)',
                if (toCheck > 0) '$toCheck to check',
                if (run.heldPayments > 0) '${run.heldPayments} held by the bank',
              ].join(' · '),
            ),
            const SizedBox(height: AppSpacing.xs),
            // The run's status as the shared badge, in words.
            StatusBadge(
              paymentRunStatusLabel(run.status),
              key: Key('run-status-${run.status}'),
              tone: paymentRunStatusTone(run.status),
            ),
          ],
        ),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        children: [
          for (final s in run.suppliers)
            _SupplierBlock(
              supplier: s,
              currency: run.currency,
              onRelease: run.approved && !_busy ? () => _release(s) : null,
            ),
          if (run.excluded.isNotEmpty) ...[
            const Divider(),
            Align(
              alignment: Alignment.centerLeft,
              child: Text(
                'Not paid',
                style: Theme.of(context).textTheme.titleSmall,
              ),
            ),
            for (final e in run.excluded)
              ListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                leading: Icon(Icons.block, color: cs.error),
                title: Text(e.name),
                subtitle: Text(paymentExcludedText(e.reason)),
                trailing: Text(
                  AppFormat.money(e.net, currencyCode: run.currency),
                ),
              ),
          ],
          if (run.cancelled && run.cancelReason != null)
            Align(
              alignment: Alignment.centerLeft,
              child: Text('Cancelled: ${run.cancelReason}'),
            ),
          const SizedBox(height: 8),
          Wrap(
            alignment: WrapAlignment.end,
            spacing: 8,
            runSpacing: 8,
            children: [
              if (run.proposed || run.approved)
                TextButton(
                  onPressed: _busy ? null : _cancel,
                  child: const Text('Cancel run'),
                ),
              if (run.approved || run.paid)
                MenuAnchor(
                  menuChildren: [
                    for (final f in BankFileFormat.forCurrency(run.currency))
                      MenuItemButton(
                        onPressed: () => _bankFile(f),
                        child: Text(f.label),
                      ),
                  ],
                  builder: (context, menu, _) => OutlinedButton.icon(
                    onPressed: _busy
                        ? null
                        : () => menu.isOpen ? menu.close() : menu.open(),
                    icon: const Icon(Icons.download_outlined),
                    label: const Text('Bank file'),
                  ),
                ),
              if (run.approved)
                OutlinedButton.icon(
                  onPressed: _busy ? null : _statusReport,
                  icon: const Icon(Icons.upload_file_outlined),
                  label: const Text("Bank's answer"),
                ),
              if (run.proposed)
                _ownRun
                    ? Tooltip(
                        message: 'Another manager approves a run you proposed',
                        child: approve,
                      )
                    : approve,
              if (run.approved)
                run.heldPayments > 0
                    ? Tooltip(
                        message:
                            'The bank holds ${run.heldPayments} payment(s): '
                            'release a close match, or cancel the run',
                        child: markPaid,
                      )
                    : markPaid,
            ],
          ),
        ],
      ),
    );
  }
}

class _SupplierBlock extends StatelessWidget {
  const _SupplierBlock({
    required this.supplier,
    required this.currency,
    this.onRelease,
  });
  final PaymentRunSupplier supplier;
  final String currency;
  final VoidCallback? onRelease;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    const bold = TextStyle(fontWeight: FontWeight.bold);
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(child: Text(supplier.name, style: bold)),
              Text(
                AppFormat.money(supplier.net, currencyCode: currency),
                style: bold,
              ),
            ],
          ),
          if (!supplier.remittanceEmailOnFile)
            Text(
              'No remittance email: the advice will not be sent',
              style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12),
            ),
          for (final w in supplier.warnings)
            _Flag(
              icon: Icons.warning_amber_rounded,
              color: cs.error,
              text: paymentWarningText(w),
            ),
          if (supplier.bankCheck case final check?)
            _Flag(
              icon: check.blocking
                  ? Icons.pan_tool_outlined
                  : check.released
                  ? Icons.lock_open_outlined
                  : Icons.verified_outlined,
              color: check.blocking ? cs.error : cs.onSurfaceVariant,
              text: check.released
                  ? '${payeeCheckText(check)}. Released: ${check.releaseReason ?? ''}'
                  : payeeCheckText(check),
              action: check.blocking && check.releasable && onRelease != null
                  ? TextButton(
                      onPressed: onRelease,
                      child: const Text('Release'),
                    )
                  : null,
            ),
          for (final d in supplier.documents)
            Row(
              children: [
                Expanded(
                  child: Text(
                    '${d.isCredit ? 'Credit note' : 'Invoice'} ${d.reference}'
                    '${d.dueDate != null ? ' · due ${AppFormat.date(d.dueDate)}' : ''}',
                  ),
                ),
                Text(
                  '${d.isCredit ? '-' : ''}${AppFormat.money(d.amount, currencyCode: currency)}',
                ),
              ],
            ),
        ],
      ),
    );
  }
}

/// A line under a supplier that needs the reviewer's eye.
class _Flag extends StatelessWidget {
  const _Flag({
    required this.icon,
    required this.color,
    required this.text,
    this.action,
  });
  final IconData icon;
  final Color color;
  final String text;
  final Widget? action;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 4),
    child: Row(
      children: [
        Icon(icon, color: color, size: 18),
        const SizedBox(width: 6),
        Expanded(
          child: Text(text, style: TextStyle(color: color)),
        ),
        ?action,
      ],
    ),
  );
}

/// A form's refusal, in words, above its fields.
class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner(this.message);
  final String message;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: cs.errorContainer,
        borderRadius: AppRadius.chip,
      ),
      child: Text(message, style: TextStyle(color: cs.onErrorContainer)),
    );
  }
}

/// Proposes a run from the invoices due by a date.
class ProposePaymentRunDialog extends ConsumerStatefulWidget {
  const ProposePaymentRunDialog({super.key});

  @override
  ConsumerState<ProposePaymentRunDialog> createState() =>
      _ProposePaymentRunDialogState();
}

class _ProposePaymentRunDialogState
    extends ConsumerState<ProposePaymentRunDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _payUpTo;
  late final TextEditingController _paymentDate;
  bool _loading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    _payUpTo = TextEditingController(
      text: _isoDate(now.add(const Duration(days: 7))),
    );
    _paymentDate = TextEditingController(text: _isoDate(now));
  }

  @override
  void dispose() {
    _payUpTo.dispose();
    _paymentDate.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.purchase}/payment-runs',
            data: {
              'payUpTo': _payUpTo.text.trim(),
              'paymentDate': _paymentDate.text.trim(),
            },
          );
      final run = PaymentRun.fromJson(
        (resp.data['data'] as Map).cast<String, dynamic>(),
      );
      if (!mounted) return;
      ref.invalidate(paymentRunsProvider);
      Navigator.pop(context);
      final left = run.excluded.length;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            'Proposed ${run.reference}: '
            '${AppFormat.money(run.total, currencyCode: run.currency)} to '
            '${run.suppliers.length} supplier(s)'
            '${left > 0 ? ', $left left out' : ''}.',
          ),
        ),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not propose a payment run.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Propose payment run'),
      content: SizedBox(
        width: 400,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null) ...[
                _ErrorBanner(_error!),
                const SizedBox(height: 12),
              ],
              const Text(
                'Every matched or approved invoice due by the date, less each '
                "supplier's credit notes. A second manager approves it.",
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _payUpTo,
                decoration: const InputDecoration(
                  labelText: 'Pay invoices due by',
                  helperText: 'YYYY-MM-DD',
                  prefixIcon: Icon(Icons.event_outlined),
                ),
                validator: validIsoDate,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _paymentDate,
                decoration: const InputDecoration(
                  labelText: 'Payment date',
                  helperText: 'Today or later',
                  prefixIcon: Icon(Icons.today_outlined),
                ),
                validator: validIsoDate,
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _loading ? null : _submit,
          child: const Text('Propose'),
        ),
      ],
    );
  }
}

/// Asks for a reason — why a run is abandoned, what was checked before a held
/// payment is released — and returns it, or null when dismissed.
class PaymentRunReasonDialog extends StatefulWidget {
  const PaymentRunReasonDialog({
    super.key,
    required this.title,
    required this.confirm,
    required this.keep,
    this.intro,
    this.label = 'Reason *',
  });
  final String title;
  final String confirm;
  final String keep;
  final String? intro;
  final String label;

  @override
  State<PaymentRunReasonDialog> createState() => _PaymentRunReasonDialogState();
}

class _PaymentRunReasonDialogState extends State<PaymentRunReasonDialog> {
  final _formKey = GlobalKey<FormState>();
  final _reason = TextEditingController();

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title),
      content: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (widget.intro != null) ...[
              Text(widget.intro!),
              const SizedBox(height: 12),
            ],
            TextFormField(
              controller: _reason,
              maxLength: 500,
              decoration: InputDecoration(labelText: widget.label),
              validator: (v) =>
                  v == null || v.trim().isEmpty ? 'Required' : null,
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: Text(widget.keep),
        ),
        FilledButton(
          onPressed: () {
            if (_formKey.currentState!.validate()) {
              Navigator.pop(context, _reason.text.trim());
            }
          },
          child: Text(widget.confirm),
        ),
      ],
    );
  }
}

/// The accounts supplier payments are made from, one per currency, and a form
/// to set one (17.12). A Bacs or SEPA file needs one; a change made after a run
/// was approved stops that run's file.
class PayingAccountsDialog extends ConsumerStatefulWidget {
  const PayingAccountsDialog({super.key});

  @override
  ConsumerState<PayingAccountsDialog> createState() =>
      _PayingAccountsDialogState();
}

class _PayingAccountsDialogState extends ConsumerState<PayingAccountsDialog> {
  final _formKey = GlobalKey<FormState>();
  final _currency = TextEditingController();
  final _name = TextEditingController();
  final _sortCode = TextEditingController();
  final _account = TextEditingController();
  final _serviceUser = TextEditingController();
  final _iban = TextEditingController();
  final _bic = TextEditingController();
  bool _loading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    // Starts from the tenant's own currency (SJ-D53).
    ref.listenManual(tenantInfoProvider, (_, next) {
      final currency = next.value?.currency;
      if (currency != null && _currency.text.isEmpty) {
        _currency.text = currency;
      }
    }, fireImmediately: true);
  }

  @override
  void dispose() {
    for (final c in [
      _currency,
      _name,
      _sortCode,
      _account,
      _serviceUser,
      _iban,
      _bic,
    ]) {
      c.dispose();
    }
    super.dispose();
  }

  static String? _text(TextEditingController c) =>
      c.text.trim().isEmpty ? null : c.text.trim();

  String? _validServiceUser(String? v) {
    final t = v?.trim() ?? '';
    if (t.isEmpty) return null;
    if (!RegExp(r'^\d{6}$').hasMatch(t)) return 'Six digits';
    return _text(_sortCode) == null ? 'Goes with a UK sort code' : null;
  }

  static String _describe(PayingAccount a) => [
    if (a.sortCode != null) 'sort code ${a.sortCode}',
    if (a.accountNumberMasked != null) a.accountNumberMasked!,
    if (a.ibanMasked != null) a.ibanMasked!,
    if (a.serviceUserNumber != null) 'service user ${a.serviceUserNumber}',
    if (a.sendsBacs) 'Bacs',
    if (a.sendsSepa) 'SEPA',
  ].join(' · ');

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    if (_text(_iban) == null && _text(_sortCode) == null) {
      setState(
        () => _error = 'Give a sort code and account number, or an IBAN.',
      );
      return;
    }
    final currency = _currency.text.trim().toUpperCase();
    final name = _name.text.trim();
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .put(
            '/${ApiConstants.purchase}/payment-runs/paying-accounts/$currency',
            data: {
              'accountName': name,
              'sortCode': _text(_sortCode),
              'accountNumber': _text(_account),
              'serviceUserNumber': _text(_serviceUser),
              'iban': _text(_iban),
              'bic': _text(_bic),
            },
          );
      if (!mounted) return;
      ref.invalidate(payingAccountsProvider);
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('$currency payments are now made from $name.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(
          e,
          fallback: 'Could not set the paying account.',
        );
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final muted = TextStyle(color: cs.onSurfaceVariant);
    return AlertDialog(
      title: const Text('Paying accounts'),
      content: SizedBox(
        width: 440,
        child: SingleChildScrollView(
          child: Form(
            key: _formKey,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                ref
                    .watch(payingAccountsProvider)
                    .when(
                      loading: () => const LinearProgressIndicator(),
                      error: (e, _) => Text(
                        friendlyError(
                          e,
                          fallback: 'Could not load the paying accounts.',
                        ),
                      ),
                      data: (accounts) => accounts.isEmpty
                          ? Text(
                              'No paying account set: a Bacs or SEPA file needs one.',
                              style: muted,
                            )
                          : Column(
                              children: [
                                for (final a in accounts)
                                  ListTile(
                                    dense: true,
                                    contentPadding: EdgeInsets.zero,
                                    leading: const Icon(
                                      Icons.account_balance_outlined,
                                    ),
                                    title: Text(
                                      '${a.currency} · ${a.accountName}',
                                    ),
                                    subtitle: Text(_describe(a)),
                                  ),
                              ],
                            ),
                    ),
                const Divider(height: 24),
                Text(
                  'Set the account for a currency',
                  style: Theme.of(context).textTheme.titleSmall,
                ),
                const SizedBox(height: 4),
                Text(
                  'It replaces that currency\'s account. A run approved before '
                  'the change gets no bank file: cancel it and propose again.',
                  style: muted,
                ),
                const SizedBox(height: 12),
                if (_error != null) ...[
                  _ErrorBanner(_error!),
                  const SizedBox(height: 12),
                ],
                TextFormField(
                  controller: _currency,
                  textCapitalization: TextCapitalization.characters,
                  decoration: const InputDecoration(
                    labelText: 'Currency *',
                    helperText: 'ISO 4217 code',
                  ),
                  validator: (v) =>
                      RegExp(r'^[A-Za-z]{3}$').hasMatch(v?.trim() ?? '')
                      ? null
                      : 'Three letters',
                ),
                TextFormField(
                  controller: _name,
                  maxLength: 140,
                  decoration: const InputDecoration(
                    labelText: 'Account holder name *',
                  ),
                  validator: (v) =>
                      v == null || v.trim().isEmpty ? 'Required' : null,
                ),
                TextFormField(
                  controller: _sortCode,
                  decoration: const InputDecoration(labelText: 'Sort code'),
                  validator: (v) =>
                      validSortCode(v, accountKeyed: _text(_account) != null),
                ),
                TextFormField(
                  controller: _account,
                  decoration: const InputDecoration(
                    labelText: 'Account number',
                  ),
                  validator: (v) => validAccountNumber(
                    v,
                    sortCodeKeyed: _text(_sortCode) != null,
                  ),
                ),
                TextFormField(
                  controller: _serviceUser,
                  decoration: const InputDecoration(
                    labelText: 'Bacs service user number',
                    helperText: 'Six digits, for a Bacs file',
                  ),
                  validator: _validServiceUser,
                ),
                TextFormField(
                  controller: _iban,
                  decoration: const InputDecoration(
                    labelText: 'IBAN',
                    helperText: 'Needed for a SEPA file',
                  ),
                  validator: (v) => validIban(v, bicKeyed: _text(_bic) != null),
                ),
                TextFormField(
                  controller: _bic,
                  decoration: const InputDecoration(labelText: 'BIC'),
                  validator: validBic,
                ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : () => Navigator.pop(context),
          child: const Text('Close'),
        ),
        FilledButton(
          onPressed: _loading ? null : _save,
          child: const Text('Save account'),
        ),
      ],
    );
  }
}

/// A payment run's status in words.
String paymentRunStatusLabel(String status) => switch (status.toUpperCase()) {
      'PROPOSED' => 'Proposed',
      'APPROVED' => 'Approved',
      'PAID' => 'Paid',
      'CANCELLED' => 'Cancelled',
      _ => humanizeCode(status),
    };

StatusTone paymentRunStatusTone(String status) => switch (status.toUpperCase()) {
      'PROPOSED' => StatusTone.info,
      'APPROVED' => StatusTone.warning,
      'PAID' => StatusTone.success,
      _ => StatusTone.neutral,
    };
