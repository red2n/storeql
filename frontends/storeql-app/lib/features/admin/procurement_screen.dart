import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/widgets/reference_fields.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/status_badge.dart';
import 'providers/admin_providers.dart';
import 'einvoice_tab.dart';
import 'payment_runs_tab.dart';
import 'procurement_providers.dart';
import 'sourcing_tab.dart';
import 'supplier_scorecards.dart';
import 'resolve_invoice_dialog.dart';
import 'widgets/variant_names.dart';
import 'widgets/variant_picker.dart';
import 'bank_details_validators.dart';
import 'consignment_tab.dart';

class ProcurementScreen extends ConsumerWidget {
  const ProcurementScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final canPay = canRunPayments(ref.watch(authNotifierProvider).value);
    return DefaultTabController(
      length: 7,
      child: Builder(
        // A Builder gives this subtree a context below DefaultTabController,
        // so DefaultTabController.of(context) below can find it.
        builder: (context) {
          final tabController = DefaultTabController.of(context);
          // One inset for the title, the tab labels, the actions and the cards, so their edges
          // line up: 16 on a phone, 24 from tablet width.
          final gutter = context.pageGutter;
          return Scaffold(
            body: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // The tabs follow straight under the title, so no bottom inset.
                PageHeader(
                  title: 'Procurement',
                  padding: EdgeInsetsDirectional.fromSTEB(gutter, gutter, gutter, 0),
                ),
                // The tabs start at the page's edge, not M3's 52px scroll offset, and the first
                // label lines up under the title: the gutter less the tab's own 16 of label padding.
                TabBar(
                  isScrollable: true,
                  tabAlignment: TabAlignment.start,
                  padding: EdgeInsetsDirectional.only(start: gutter - AppSpacing.lg),
                  labelPadding: const EdgeInsetsDirectional.symmetric(horizontal: AppSpacing.lg),
                  tabs: const [
                    Tab(text: 'Purchase Orders'),
                    Tab(text: 'Invoices'),
                    Tab(text: 'E-invoices'),
                    Tab(text: 'Suppliers'),
                    Tab(text: 'Payments'),
                    Tab(text: 'Consignment & dropship'),
                    Tab(text: 'Sourcing'),
                  ],
                ),
                const Expanded(
                  child: TabBarView(
                    children: [
                      _PurchaseOrdersTab(),
                      _SupplierInvoicesTab(),
                      EInvoicesTab(),
                      _SuppliersTab(),
                      PaymentRunsTab(),
                      // Consignment stock: what suppliers are owed as their stock sells.
                      ConsignmentTab(),
                      // RFQs: several suppliers asked, quotes compared at home, the award raising drafts.
                      SourcingTab(),
                    ],
                  ),
                ),
              ],
            ),
            // One primary action, following whichever tab is active, instead
            // of a separate "Create PO" / "Add supplier" button duplicated
            // per tab.
            floatingActionButton: ListenableBuilder(
              listenable: tabController,
              builder: (context, _) => tabController.index == 5
                  // Consignment settles per supplier from the tab itself.
                  ? const SizedBox.shrink()
                  : tabController.index == 4
                  ? (canPay
                        ? FloatingActionButton.extended(
                            onPressed: () => showDialog(
                              context: context,
                              builder: (_) => const ProposePaymentRunDialog(),
                            ),
                            icon: const Icon(Icons.payments_outlined),
                            label: const Text('Propose run'),
                          )
                        : const SizedBox.shrink())
                  : tabController.index == 3
                  ? FloatingActionButton.extended(
                      onPressed: () => showDialog(
                        context: context,
                        builder: (_) => const _SupplierDialog(),
                      ),
                      icon: const Icon(Icons.add),
                      label: const Text('Add supplier'),
                    )
                  : tabController.index == 2
                  ? FloatingActionButton.extended(
                      key: const Key('einvoice-upload'),
                      onPressed: () => uploadEInvoiceFlow(context, ref),
                      icon: const Icon(Icons.upload_file_outlined),
                      label: const Text('Upload e-invoice'),
                    )
                  : FloatingActionButton.extended(
                      onPressed: () => showDialog(
                        context: context,
                        builder: (_) => const _CreatePoDialog(),
                      ),
                      icon: const Icon(Icons.add),
                      label: const Text('Create PO'),
                    ),
            ),
          );
        },
      ),
    );
  }
}

// ── Suppliers ────────────────────────────────────────────────────────────────

class _SuppliersTab extends ConsumerWidget {
  const _SuppliersTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(suppliersProvider);
    final auth = ref.watch(authNotifierProvider).value;
    final isManager = auth is AuthAuthenticated && auth.isManager;
    final cs = Theme.of(context).colorScheme;
    final gutter = context.pageGutter;
    return Column(
      children: [
        const SizedBox(height: AppSpacing.md),
        // Who delivers on time and in full: management's reading of the period.
        if (isManager) const SupplierScorecardsCard(),
        Expanded(
          child: async.when(
            loading: () => const LoadingView(label: 'Loading suppliers…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load suppliers.'),
              onRetry: () => ref.invalidate(suppliersProvider),
            ),
            data: (suppliers) {
              if (suppliers.isEmpty) {
                return const EmptyState(
                  icon: Icons.local_shipping_outlined,
                  title: 'No suppliers yet',
                );
              }
              return ListView.separated(
                // Under the title's inset; the bottom clears the Add supplier button.
                padding: EdgeInsetsDirectional.fromSTEB(
                  gutter,
                  AppSpacing.xs,
                  gutter,
                  AppSpacing.fabClearance,
                ),
                itemCount: suppliers.length,
                separatorBuilder: (_, _) => const SizedBox(height: 4),
                itemBuilder: (_, i) {
                  final s = suppliers[i];
                  return Card(
                    child: ListTile(
                      leading: CircleAvatar(
                        backgroundColor: cs.primaryContainer,
                        child: Icon(
                          Icons.local_shipping_outlined,
                          color: cs.onPrimaryContainer,
                        ),
                      ),
                      title: Text(
                        s.name,
                        style: const TextStyle(fontWeight: FontWeight.bold),
                      ),
                      subtitle: Text(
                        [
                          if (s.currency != null) s.currency,
                          '${s.paymentTermsDays}d terms',
                          if (s.leadTimeDays != null) '${s.leadTimeDays}d lead time',
                          if (s.vatRegistered) 'VAT ${s.vatNumber ?? 'reg'}',
                          if (s.countryCode != null) s.countryCode,
                          if (isManager)
                            s.hasBankDetails
                                ? 'bank details on file'
                                : 'no bank details',
                        ].whereType<String>().join(' · '),
                      ),
                      trailing: isManager
                          ? IconButton(
                              tooltip: 'Edit supplier',
                              icon: const Icon(Icons.edit_outlined),
                              onPressed: () => showDialog<void>(
                                context: context,
                                builder: (_) => _SupplierDialog(existing: s),
                              ),
                            )
                          : null,
                    ),
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }
}

// ── Supplier invoices: the three-way match ───────────────────────────────────

/// Ordered against received against invoiced, per line.
///
/// A status badge alone answers the wrong question. A buyer told an invoice is
/// FLAGGED still has to know *which* line disagreed and by how much before they
/// can ring the supplier — so the three figures sit side by side, and the
/// flagged ones lead.
class _SupplierInvoicesTab extends ConsumerWidget {
  const _SupplierInvoicesTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(supplierInvoicesProvider);
    final gutter = context.pageGutter;
    return async.when(
      loading: () => const LoadingView(label: 'Loading invoices…'),
      error: (e, _) => ErrorView(
        message: friendlyError(
          e,
          fallback: 'Could not load supplier invoices.',
        ),
        onRetry: () => ref.invalidate(supplierInvoicesProvider),
      ),
      data: (invoices) {
        if (invoices.isEmpty) {
          return const EmptyState(
            icon: Icons.receipt_long_outlined,
            title: 'No supplier invoices yet',
            message: 'Capture one from a purchase order to match it',
          );
        }
        // Flagged first: the whole point of the control is the exceptions, and a
        // list ordered by date buries them behind the ones nobody needs to read.
        final sorted = [...invoices]
          ..sort((a, b) {
            if (a.flagged == b.flagged) return 0;
            return a.flagged ? -1 : 1;
          });
        return ListView.separated(
          // Under the title's inset; the bottom clears the page's button.
          padding: EdgeInsetsDirectional.fromSTEB(
            gutter,
            AppSpacing.lg,
            gutter,
            AppSpacing.fabClearance,
          ),
          itemCount: sorted.length,
          separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.sm),
          itemBuilder: (_, i) => _InvoiceCard(sorted[i]),
        );
      },
    );
  }
}

class _InvoiceCard extends ConsumerWidget {
  final SupplierInvoice invoice;
  const _InvoiceCard(this.invoice);

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final flagged = invoice.flagged;
    final cs = Theme.of(context).colorScheme;
    final auth = ref.watch(authNotifierProvider).value;
    // The server refuses anyone else with 403; not offering the buttons spares a
    // storekeeper a pair of controls that can only ever fail.
    final canDecide =
        auth is AuthAuthenticated &&
        auth.isManager &&
        auth.hasPermission('purchasing.invoices.decide');
    final leadingIcon = invoice.rejected
        ? Icons.block_outlined
        : flagged
        ? Icons.warning_amber_rounded
        : Icons.check_circle_outline;
    final leadingColor = invoice.rejected
        ? cs.outline
        : flagged
        ? context.status.warning
        : context.status.success;
    return Card(
      child: ExpansionTile(
        // Flagged invoices open by default. A variance the buyer has to click to
        // discover is a variance that waits until the payment run.
        initiallyExpanded: flagged,
        leading: Icon(leadingIcon, color: leadingColor),
        // A Wrap, so on a phone a long number moves the badge under it rather than off the card.
        title: Wrap(
          spacing: AppSpacing.sm,
          runSpacing: AppSpacing.xs,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Text(
              invoice.invoiceNumber,
              style: const TextStyle(fontWeight: FontWeight.bold),
            ),
            _InvoiceStatusBadge(invoice.status),
          ],
        ),
        subtitle: Text(
          [
            AppFormat.money(
              invoice.grossAmount,
              currencyCode: invoice.currency,
            ),
            if (invoice.invoiceDate != null) AppFormat.date(invoice.invoiceDate),
            // The date accounts payable schedules by, beside the one on the
            // document.
            if (invoice.dueDate != null) 'due ${AppFormat.date(invoice.dueDate)}',
            'PO #${shortRef(invoice.poId)}',
            if (invoice.postedAt != null) 'posted',
          ].join(' · '),
        ),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // The header check comes before the lines: an invoice whose own
                // total does not add up is wrong before any line is compared.
                if (invoice.headerVariances.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: Wrap(
                      spacing: 6,
                      runSpacing: 4,
                      children: [
                        for (final v in invoice.headerVariances)
                          _VarianceChip(
                            v,
                            detail:
                                v == 'TOTAL_MISMATCH' &&
                                    invoice.statedGross != null
                                ? ' (${AppFormat.money(invoice.statedGross!, currencyCode: invoice.currency)} stated, '
                                      '${AppFormat.money(invoice.grossAmount, currencyCode: invoice.currency)} from the lines)'
                                : null,
                          ),
                      ],
                    ),
                  ),
                const _MatchHeaderRow(),
                const Divider(height: 12),
                VariantNames(
                  ids: [for (final l in invoice.lines) l.variantId],
                  builder: (context, labels) => Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      for (final l in invoice.lines)
                        _MatchRow(l, invoice.currency, labels),
                    ],
                  ),
                ),
                if (invoice.resolutionReason != null &&
                    invoice.resolutionReason!.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      '${invoice.approved ? 'Approved' : 'Rejected'}: ${invoice.resolutionReason}',
                      style: TextStyle(fontSize: 12, color: cs.outline),
                    ),
                  ),
                if (flagged && canDecide)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        OutlinedButton.icon(
                          key: Key('reject-invoice-${invoice.id}'),
                          onPressed: () => showDialog<bool>(
                            context: context,
                            builder: (_) => ResolveInvoiceDialog(
                              invoice: invoice,
                              approve: false,
                            ),
                          ),
                          icon: const Icon(Icons.block_outlined, size: 18),
                          label: const Text('Reject'),
                        ),
                        const SizedBox(width: 8),
                        FilledButton.icon(
                          key: Key('approve-invoice-${invoice.id}'),
                          onPressed: () => showDialog<bool>(
                            context: context,
                            builder: (_) => ResolveInvoiceDialog(
                              invoice: invoice,
                              approve: true,
                            ),
                          ),
                          icon: const Icon(Icons.check, size: 18),
                          label: const Text('Approve for payment'),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _MatchHeaderRow extends StatelessWidget {
  const _MatchHeaderRow();

  @override
  Widget build(BuildContext context) {
    const style = TextStyle(fontSize: 11, fontWeight: FontWeight.w600);
    return const Row(
      children: [
        Expanded(flex: 3, child: Text('Variant', style: style)),
        Expanded(
          child: Text('Ordered', style: style, textAlign: TextAlign.right),
        ),
        Expanded(
          child: Text('Received', style: style, textAlign: TextAlign.right),
        ),
        Expanded(
          child: Text('Invoiced', style: style, textAlign: TextAlign.right),
        ),
        Expanded(
          flex: 2,
          child: Text('Price', style: style, textAlign: TextAlign.right),
        ),
      ],
    );
  }
}

class _MatchRow extends StatelessWidget {
  final InvoiceMatchLine line;
  final String currency;

  /// Product names by variant, so the line reads as what was bought.
  final Map<String, VariantLabel> labels;
  const _MatchRow(this.line, this.currency, this.labels);

  @override
  Widget build(BuildContext context) {
    final bad = !line.matched;
    final warn = context.status.warning;
    final num = TextStyle(
      fontSize: 12,
      fontFamily: 'monospace',
      color: bad ? warn : null,
      fontWeight: bad ? FontWeight.bold : null,
    );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                flex: 3,
                child: VariantLine(
                  variantId: line.variantId,
                  labels: labels,
                ),
              ),
              Expanded(
                child: Text(
                  _trim(line.qtyOrdered),
                  style: num,
                  textAlign: TextAlign.right,
                ),
              ),
              Expanded(
                child: Text(
                  _trim(line.qtyReceived),
                  style: num,
                  textAlign: TextAlign.right,
                ),
              ),
              Expanded(
                child: Text(
                  _trim(line.qtyInvoiced),
                  style: num,
                  textAlign: TextAlign.right,
                ),
              ),
              Expanded(
                flex: 2,
                child: Text(
                  // Both prices when they differ, so the buyer can see the gap
                  // rather than being told there is one.
                  line.orderedUnitPrice != null &&
                          line.orderedUnitPrice != line.invoicedUnitPrice
                      ? '${_unitPrice(line.orderedUnitPrice!, currency)} → ${_unitPrice(line.invoicedUnitPrice, currency)}'
                      : _unitPrice(line.invoicedUnitPrice, currency),
                  style: num,
                  textAlign: TextAlign.right,
                ),
              ),
            ],
          ),
          if (bad)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Wrap(
                spacing: 6,
                runSpacing: 4,
                children: [for (final v in line.variances) _VarianceChip(v)],
              ),
            ),
        ],
      ),
    );
  }
}

/// A variance in words. The codes are precise and unreadable; a buyer chasing a
/// supplier needs the sentence, not the constant.
class _VarianceChip extends StatelessWidget {
  final String code;
  final String? detail;
  const _VarianceChip(this.code, {this.detail});

  static const _labels = {
    'INVOICED_ABOVE_RECEIVED': 'Billed for more than arrived',
    'NOT_RECEIVED': 'Nothing received yet',
    'NOT_ON_ORDER': 'Not on the purchase order',
    'PRICE_ABOVE_ORDER': 'Charged above the agreed price',
    'PRICE_BELOW_ORDER': 'Charged below the agreed price',
    'TOTAL_MISMATCH': 'The stated total does not add up',
  };

  @override
  Widget build(BuildContext context) {
    final status = context.status;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: status.warningContainer,
        borderRadius: AppRadius.badge,
      ),
      child: Text(
        '${_labels[code] ?? code}${detail ?? ''}',
        style: TextStyle(
          fontSize: 11,
          color: status.onWarningContainer,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

/// A supplier invoice's status in words, in the tone of what it asks of a buyer.
class _InvoiceStatusBadge extends StatelessWidget {
  final String status;
  const _InvoiceStatusBadge(this.status);

  @override
  Widget build(BuildContext context) {
    final (label, tone) = switch (status.toUpperCase()) {
      // The three documents agree: payable as it stands.
      'MATCHED' => ('Matched', StatusTone.success),
      // They disagree somewhere, and a manager has to approve or reject it before it is paid.
      'FLAGGED' => ('Flagged', StatusTone.warning),
      'APPROVED' => ('Approved', StatusTone.success),
      // Refused for payment: the posting reversed.
      'REJECTED' => ('Rejected', StatusTone.error),
      _ => (humanizeCode(status), StatusTone.neutral),
    };
    return StatusBadge(label.isEmpty ? status : label, tone: tone);
  }
}

/// Add a supplier, or — with [existing] — correct one. Terms, VAT number,
/// country and currency were fixed at creation until SJ-D34; a supplier
/// created in the wrong currency was wrong for every order ever raised
/// against it, and the only fix was a second supplier.
class _SupplierDialog extends ConsumerStatefulWidget {
  const _SupplierDialog({this.existing});
  final Supplier? existing;

  @override
  ConsumerState<_SupplierDialog> createState() => _SupplierDialogState();
}

class _SupplierDialogState extends ConsumerState<_SupplierDialog> {
  final _formKey = GlobalKey<FormState>();
  final _nameCtrl = TextEditingController();
  final _vatCtrl = TextEditingController();
  final _termsCtrl = TextEditingController(text: '30');
  final _leadCtrl = TextEditingController();
  final _emailCtrl = TextEditingController();
  final _bankNameCtrl = TextEditingController();
  final _sortCtrl = TextEditingController();
  final _accountCtrl = TextEditingController();
  final _ibanCtrl = TextEditingController();
  final _bicCtrl = TextEditingController();
  final _einvoiceSchemeCtrl = TextEditingController();
  final _einvoiceIdCtrl = TextEditingController();
  bool _clearBank = false;
  // Empty until chosen; a new supplier starts in the tenant's own country and
  // currency, an existing one in its own (SJ-D53).
  String? _country;
  String? _currency;
  bool _vatRegistered = false;
  bool _loading = false;
  String? _error;

  bool get _editing => widget.existing != null;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    if (e != null) {
      _nameCtrl.text = e.name;
      _vatCtrl.text = e.vatNumber ?? '';
      _termsCtrl.text = e.paymentTermsDays.toString();
      if (e.leadTimeDays != null) _leadCtrl.text = e.leadTimeDays.toString();
      _country = e.countryCode ?? _country;
      _currency = e.currency ?? _currency;
      _vatRegistered = e.vatRegistered;
      _emailCtrl.text = e.remittanceEmail ?? '';
      _einvoiceSchemeCtrl.text = e.einvoiceScheme ?? '';
      _einvoiceIdCtrl.text = e.einvoiceId ?? '';
      // Bank details are never prefilled: the app only holds the last four
      // digits, and a set is replaced whole or left alone.
    }
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _vatCtrl.dispose();
    _termsCtrl.dispose();
    _leadCtrl.dispose();
    _emailCtrl.dispose();
    _bankNameCtrl.dispose();
    _sortCtrl.dispose();
    _accountCtrl.dispose();
    _ibanCtrl.dispose();
    _bicCtrl.dispose();
    _einvoiceSchemeCtrl.dispose();
    _einvoiceIdCtrl.dispose();
    super.dispose();
  }

  static String? _text(TextEditingController c) =>
      c.text.trim().isEmpty ? null : c.text.trim();

  bool get _bankKeyed => [
    _bankNameCtrl,
    _sortCtrl,
    _accountCtrl,
    _ibanCtrl,
    _bicCtrl,
  ].any((c) => c.text.trim().isNotEmpty);

  static String? validEmail(String? v) {
    final t = v?.trim() ?? '';
    if (t.isEmpty) return null;
    return RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$').hasMatch(t)
        ? null
        : 'Not an email address';
  }

  String? _validSortCode(String? v) =>
      validSortCode(v, accountKeyed: _accountCtrl.text.trim().isNotEmpty);

  String? _validAccount(String? v) =>
      validAccountNumber(v, sortCodeKeyed: _sortCtrl.text.trim().isNotEmpty);

  String? _validIban(String? v) =>
      validIban(v, bicKeyed: _bicCtrl.text.trim().isNotEmpty);

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    final canBank = canRunPayments(ref.read(authNotifierProvider).value);
    final bank = canBank && _bankKeyed;
    if (bank && _text(_ibanCtrl) == null && _text(_sortCtrl) == null) {
      setState(
        () => _error = 'Give a sort code and account number, or an IBAN.',
      );
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final dio = ref.read(apiClientProvider).dio;
      final data = {
        'name': _nameCtrl.text.trim(),
        'vatNumber': _vatCtrl.text.trim().isEmpty ? null : _vatCtrl.text.trim(),
        'vatRegistered': _vatRegistered,
        // Omitted, purchase-svc takes the tenant's own (SJ-D53).
        if (_country != null) 'countryCode': _country,
        if (_currency != null) 'currency': _currency,
        'paymentTermsDays': int.tryParse(_termsCtrl.text.trim()) ?? 30,
        // The quoted lead time: unsaid leaves it as it was.
        if (_leadCtrl.text.trim().isNotEmpty)
          'leadTimeDays': int.tryParse(_leadCtrl.text.trim()),
        // On an edit an empty email clears it; on a create it is just absent.
        'remittanceEmail': _editing
            ? _emailCtrl.text.trim()
            : _text(_emailCtrl),
        if (bank) ...{
          'bankAccountName': _text(_bankNameCtrl),
          'bankSortCode': _text(_sortCtrl),
          'bankAccountNumber': _text(_accountCtrl),
          'bankIban': _text(_ibanCtrl),
          'bankBic': _text(_bicCtrl),
        },
        if (canBank && _editing && _clearBank && !bank)
          'clearBankDetails': true,
        // On an edit both empty removes the address; on a create it is absent.
        'einvoiceScheme': _editing
            ? _einvoiceSchemeCtrl.text.trim()
            : _text(_einvoiceSchemeCtrl),
        'einvoiceId': _editing
            ? _einvoiceIdCtrl.text.trim()
            : _text(_einvoiceIdCtrl),
      };
      if (_editing) {
        await dio.put(
          '/${ApiConstants.purchase}/suppliers/${widget.existing!.id}',
          data: data,
        );
      } else {
        await dio.post('/${ApiConstants.purchase}/suppliers', data: data);
      }
      if (!mounted) return;
      ref.invalidate(suppliersProvider);
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(_editing ? 'Supplier updated.' : 'Supplier added.'),
        ),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(
          e,
          fallback: _editing
              ? 'Could not update supplier.'
              : 'Could not add supplier.',
        );
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final canBank = canRunPayments(ref.watch(authNotifierProvider).value);
    final onFile = widget.existing?.hasBankDetails == true;
    return AlertDialog(
      title: Text(_editing ? 'Edit supplier' : 'Add supplier'),
      content: SizedBox(
        width: 400,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (_error != null) ...[
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: cs.errorContainer,
                      borderRadius: AppRadius.chip,
                    ),
                    child: Text(
                      _error!,
                      style: TextStyle(color: cs.onErrorContainer),
                    ),
                  ),
                  const SizedBox(height: 12),
                ],
                TextFormField(
                  controller: _nameCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Supplier name *',
                    prefixIcon: Icon(Icons.business),
                  ),
                  validator: (v) =>
                      v == null || v.trim().isEmpty ? 'Required' : null,
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: CountryField(
                        value:
                            _country ??
                            (_editing
                                ? null
                                : ref.watch(tenantInfoProvider).value?.country),
                        onChanged: (v) => setState(() => _country = v),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: CurrencyField(
                        value:
                            _currency ??
                            (_editing
                                ? null
                                : ref
                                      .watch(tenantInfoProvider)
                                      .value
                                      ?.currency),
                        onChanged: (v) => setState(() => _currency = v),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _termsCtrl,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(
                    labelText: 'Payment terms (days)',
                    prefixIcon: Icon(Icons.calendar_today_outlined),
                  ),
                ),
                const SizedBox(height: 12),
                TextFormField(
                  key: const Key('supplier-lead-time'),
                  controller: _leadCtrl,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(
                    labelText: 'Quoted lead time (days)',
                    helperText: 'What a delivery is measured against when an order names no date',
                    prefixIcon: Icon(Icons.timer_outlined),
                  ),
                ),
                const SizedBox(height: 8),
                SwitchListTile.adaptive(
                  contentPadding: EdgeInsets.zero,
                  value: _vatRegistered,
                  onChanged: (v) => setState(() => _vatRegistered = v),
                  title: const Text('VAT registered'),
                ),
                if (_vatRegistered)
                  TextFormField(
                    controller: _vatCtrl,
                    decoration: const InputDecoration(labelText: 'VAT number'),
                  ),
                const SizedBox(height: 12),
                ElectronicAddressFields(
                  scheme: _einvoiceSchemeCtrl,
                  id: _einvoiceIdCtrl,
                  keyPrefix: 'supplier',
                  label: 'E-invoicing address',
                  helperText: 'Where its e-invoices come from (Peppol)',
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _emailCtrl,
                  keyboardType: TextInputType.emailAddress,
                  decoration: const InputDecoration(
                    labelText: 'Remittance email',
                    helperText: 'Where payment advice is sent',
                    prefixIcon: Icon(Icons.alternate_email),
                  ),
                  validator: validEmail,
                ),
                // Where a supplier's money goes is a finance decision: only a
                // manager holding finance.payments sees these fields.
                if (canBank) ...[
                  const SizedBox(height: 16),
                  Text(
                    'Bank details',
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                  if (onFile)
                    Text(
                      'On file: ${[widget.existing!.bankAccountName, widget.existing!.bankAccountNumberMasked ?? widget.existing!.bankIbanMasked].whereType<String>().join(' · ')}. Leave blank to keep them.',
                      style: TextStyle(
                        color: cs.onSurfaceVariant,
                        fontSize: 12,
                      ),
                    ),
                  TextFormField(
                    controller: _bankNameCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Account holder name',
                    ),
                    validator: (v) => _bankKeyed && (v ?? '').trim().isEmpty
                        ? 'Required with bank details'
                        : null,
                  ),
                  Row(
                    children: [
                      Expanded(
                        child: TextFormField(
                          controller: _sortCtrl,
                          decoration: const InputDecoration(
                            labelText: 'Sort code',
                          ),
                          validator: _validSortCode,
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: TextFormField(
                          controller: _accountCtrl,
                          decoration: const InputDecoration(
                            labelText: 'Account number',
                          ),
                          validator: _validAccount,
                        ),
                      ),
                    ],
                  ),
                  Row(
                    children: [
                      Expanded(
                        flex: 2,
                        child: TextFormField(
                          controller: _ibanCtrl,
                          decoration: const InputDecoration(labelText: 'IBAN'),
                          validator: _validIban,
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: TextFormField(
                          controller: _bicCtrl,
                          decoration: const InputDecoration(labelText: 'BIC'),
                          validator: validBic,
                        ),
                      ),
                    ],
                  ),
                  if (onFile)
                    CheckboxListTile(
                      contentPadding: EdgeInsets.zero,
                      value: _clearBank,
                      onChanged: (v) => setState(() => _clearBank = v ?? false),
                      title: const Text('Remove the bank details'),
                    ),
                ],
              ],
            ),
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
          child: _loading
              ? SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Theme.of(context).colorScheme.onPrimary,
                  ),
                )
              : Text(_editing ? 'Save' : 'Add'),
        ),
      ],
    );
  }
}

// ── Purchase Orders ──────────────────────────────────────────────────────────

class _PurchaseOrdersTab extends ConsumerWidget {
  const _PurchaseOrdersTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(purchaseOrdersProvider);
    // A buyer looks for an order by who it is from, so a row leads with the
    // supplier's name, from the suppliers list this screen already loads. Until
    // that arrives, or for a supplier it does not hold, the row leads with the
    // order's reference instead.
    final supplierNames = {
      for (final s in ref.watch(suppliersProvider).value ?? const <Supplier>[])
        if (s.id.isNotEmpty && s.name.trim().isNotEmpty && s.name != '-')
          s.id: s.name.trim(),
    };
    final cs = Theme.of(context).colorScheme;
    final gutter = context.pageGutter;
    return Column(
      children: [
        Padding(
          padding: EdgeInsetsDirectional.fromSTEB(gutter, AppSpacing.md, gutter, 0),
          // An Align rather than a one-child Row, so at large text the label
          // wraps instead of running off a phone.
          child: Align(
            alignment: AlignmentDirectional.centerStart,
            child: OutlinedButton.icon(
              key: const Key('propose-orders'),
              onPressed: () => showDialog(
                context: context,
                builder: (_) => const _ProposeOrdersDialog(),
              ),
              icon: const Icon(Icons.auto_graph),
              label: const Text('Propose orders'),
            ),
          ),
        ),
        const SizedBox(height: AppSpacing.md),
        Expanded(
          child: async.when(
            loading: () => const LoadingView(label: 'Loading purchase orders…'),
            error: (e, _) => ErrorView(
              message: friendlyError(
                e,
                fallback: 'Could not load purchase orders.',
              ),
              onRetry: () => ref.invalidate(purchaseOrdersProvider),
            ),
            data: (pos) {
              if (pos.isEmpty) {
                return const EmptyState(
                  icon: Icons.receipt_long_outlined,
                  title: 'No purchase orders yet',
                );
              }
              return ListView.separated(
                // Under the title's inset. The bottom clears the Create PO
                // button, which would otherwise cover the last order's status
                // and chevron.
                padding: EdgeInsetsDirectional.fromSTEB(
                  gutter,
                  AppSpacing.xs,
                  gutter,
                  AppSpacing.fabClearance,
                ),
                itemCount: pos.length,
                separatorBuilder: (_, _) => const SizedBox(height: 4),
                itemBuilder: (_, i) {
                  final po = pos[i];
                  // Cut from the end: ids are UUIDv7, whose first characters
                  // are the same for every order raised in the same minute.
                  final poRef = '#${shortRef(po.id)}';
                  final supplier = supplierNames[po.supplierId];
                  final eta = AppFormat.date(po.expectedDelivery);
                  return Card(
                    child: ListTile(
                      onTap: () => showDialog(
                        context: context,
                        builder: (_) => _PoDetailDialog(poId: po.id),
                      ),
                      leading: CircleAvatar(
                        backgroundColor: cs.secondaryContainer,
                        child: Icon(
                          Icons.receipt_long_outlined,
                          color: cs.onSecondaryContainer,
                        ),
                      ),
                      // A Wrap, so on a phone a long name or status moves
                      // the badge under the name rather than into the chevron.
                      title: Wrap(
                        spacing: AppSpacing.sm,
                        runSpacing: AppSpacing.xs,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        children: [
                          supplier == null
                              ? Text(
                                  poRef,
                                  style: const TextStyle(
                                    fontFamily: 'monospace',
                                  ),
                                )
                              : Text(
                                  supplier,
                                  style: const TextStyle(
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                          _PoStatusBadge(po.status),
                          // The Wrap spaces these itself; a spacer here would double the gap.
                          // Where the order came from and whose goods it moves, beside its
                          // status and in the same badge.
                          if (po.source == 'PROPOSAL') _proposedBadge,
                          // The goods stay the supplier's until they sell.
                          if (po.ownership == 'CONSIGNMENT') _consignmentBadge,
                          // The supplier ships straight to the customer: stock never held.
                          if (po.source == 'DROPSHIP') _dropshipBadge,
                          // The goods arrive into bond with the duty suspended.
                          if (po.dutyStatus == 'DUTY_SUSPENDED') _inBondBadge,
                          // Raised by an RFQ award, at the price the supplier quoted.
                          if (po.source == 'RFQ') _rfqBadge,
                        ],
                      ),
                      subtitle: Text(
                        [
                          if (supplier != null) poRef,
                          AppFormat.money(
                            po.totalGross,
                            currencyCode: po.currency,
                          ),
                          if (eta.isNotEmpty) 'ETA $eta',
                        ].join(' · '),
                      ),
                      trailing: const Icon(Icons.chevron_right),
                    ),
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }
}

class _CreatePoDialog extends ConsumerStatefulWidget {
  const _CreatePoDialog();

  @override
  ConsumerState<_CreatePoDialog> createState() => _CreatePoDialogState();
}

class _CreatePoDialogState extends ConsumerState<_CreatePoDialog> {
  String? _supplierId;
  String? _storeId;
  String? _currency;
  DateTime? _eta;
  // Whose the goods will be: ours on arrival, or the supplier's until they sell.
  String _ownership = 'OWNED';
  // Excise goods may arrive into bond with the duty suspended.
  String _dutyStatus = 'DUTY_PAID';
  bool _loading = false;
  String? _error;

  Future<void> _submit() async {
    if (_supplierId == null || _storeId == null) {
      setState(() => _error = 'Pick a supplier and a store.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.purchase}/purchase-orders',
            data: {
              'supplierId': _supplierId,
              'storeId': _storeId,
              if (_currency != null) 'currency': _currency,
              if (_ownership != 'OWNED') 'ownership': _ownership,
              if (_dutyStatus != 'DUTY_PAID') 'dutyStatus': _dutyStatus,
              if (_eta != null)
                'expectedDelivery': _eta!.toIso8601String().split('T').first,
            },
          );
      final po = resp.data['data'] as Map<String, dynamic>;
      if (!mounted) return;
      ref.invalidate(purchaseOrdersProvider);
      Navigator.pop(context);
      // Open the new PO so the user can add lines straight away.
      showDialog(
        context: context,
        builder: (_) => _PoDetailDialog(poId: po['id'] as String),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not create PO.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final suppliersAsync = ref.watch(suppliersProvider);
    final storesAsync = ref.watch(storesProvider);
    return AlertDialog(
      title: const Text('Create purchase order'),
      content: SizedBox(
        width: 400,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (_error != null) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: cs.errorContainer,
                  borderRadius: AppRadius.chip,
                ),
                child: Text(
                  _error!,
                  style: TextStyle(color: cs.onErrorContainer),
                ),
              ),
              const SizedBox(height: 12),
            ],
            suppliersAsync.when(
              loading: () => const LinearProgressIndicator(),
              error: (e, _) => Text(
                friendlyError(e, fallback: 'Could not load suppliers.'),
                style: TextStyle(color: cs.error),
              ),
              data: (suppliers) => DropdownButtonFormField<String>(
                initialValue: _supplierId,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Supplier *'),
                items: [
                  for (final s in suppliers)
                    DropdownMenuItem(value: s.id, child: Text(s.name)),
                ],
                onChanged: (v) => setState(() {
                  _supplierId = v;
                  final s = suppliers.firstWhere((e) => e.id == v);
                  if (s.currency != null) _currency = s.currency!;
                }),
              ),
            ),
            const SizedBox(height: 12),
            storesAsync.when(
              loading: () => const LinearProgressIndicator(),
              error: (e, _) => Text(
                friendlyError(e, fallback: 'Could not load stores.'),
                style: TextStyle(color: cs.error),
              ),
              data: (stores) => DropdownButtonFormField<String>(
                initialValue: _storeId,
                isExpanded: true,
                decoration: const InputDecoration(
                  labelText: 'Deliver to store *',
                ),
                items: [
                  for (final s in stores)
                    DropdownMenuItem(value: s.id, child: Text(s.name)),
                ],
                onChanged: (v) => setState(() => _storeId = v),
              ),
            ),
            const SizedBox(height: 12),
            // The supplier's currency once one is picked, the tenant's before.
            CurrencyField(
              value: _currency ?? ref.watch(tenantInfoProvider).value?.currency,
              onChanged: (v) => setState(() => _currency = v),
            ),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              key: const Key('po-ownership'),
              initialValue: _ownership,
              decoration: const InputDecoration(
                labelText: 'Whose goods',
                helperText: 'Consignment: the supplier owns them until they sell; nothing is owed at the door',
                helperMaxLines: 2,
              ),
              items: const [
                DropdownMenuItem(value: 'OWNED', child: Text('Ours on arrival')),
                DropdownMenuItem(value: 'CONSIGNMENT', child: Text("The supplier's until sold (consignment)")),
              ],
              onChanged: (v) => setState(() => _ownership = v ?? 'OWNED'),
            ),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              key: const Key('po-duty'),
              initialValue: _dutyStatus,
              decoration: const InputDecoration(
                labelText: 'Duty',
                helperText: 'Duty suspended: excise goods into a bonded warehouse; the duty is owed on release',
                helperMaxLines: 2,
              ),
              items: const [
                DropdownMenuItem(value: 'DUTY_PAID', child: Text('Duty paid')),
                DropdownMenuItem(value: 'DUTY_SUSPENDED', child: Text('Duty suspended (into bond)')),
              ],
              onChanged: (v) => setState(() => _dutyStatus = v ?? 'DUTY_PAID'),
            ),
            const SizedBox(height: 12),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.event_outlined),
              title: Text(
                _eta == null
                    ? 'Expected delivery (optional)'
                    : 'ETA ${AppFormat.date(_eta!.toIso8601String())}',
              ),
              trailing: const Icon(Icons.edit_calendar_outlined),
              onTap: () async {
                final now = DateTime.now();
                final picked = await showDatePicker(
                  context: context,
                  initialDate: now,
                  firstDate: now,
                  lastDate: now.add(const Duration(days: 365)),
                );
                if (picked != null) setState(() => _eta = picked);
              },
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _loading ? null : _submit,
          child: _loading
              ? SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Theme.of(context).colorScheme.onPrimary,
                  ),
                )
              : const Text('Create'),
        ),
      ],
    );
  }
}

class _PoDetailDialog extends ConsumerStatefulWidget {
  final String poId;
  const _PoDetailDialog({required this.poId});

  @override
  ConsumerState<_PoDetailDialog> createState() => _PoDetailDialogState();
}

class _PoDetailDialogState extends ConsumerState<_PoDetailDialog> {
  bool _submitting = false;
  String get poId => widget.poId;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final linesAsync = ref.watch(purchaseOrderLinesProvider(poId));
    // Each line by its product's name; the end of its id only while it loads.
    final labels = ref
            .watch(variantLabelsProvider(variantIdsKey([
              for (final l in linesAsync.value ?? const []) l.variantId as String,
            ])))
            .value ??
        const <String, VariantLabel>{};
    final posAsync = ref.watch(purchaseOrdersProvider);
    final po = posAsync.maybeWhen(
      data: (pos) => pos.where((p) => p.id == poId).firstOrNull,
      orElse: () => null,
    );
    final status = po?.status.toUpperCase() ?? 'DRAFT';
    final isDraft = status == 'DRAFT';
    // Receivable is now two states, not "anything that isn't a draft". The button used to offer
    // itself on a CANCELLED or already-RECEIVED order, which could only ever end in a 400.
    final isReceivable =
        status == 'SUBMITTED' || status == 'PARTIALLY_RECEIVED';
    final isPartial = status == 'PARTIALLY_RECEIVED';
    // Above the raiser's own spend authority: nobody entitled to commit this much has agreed yet,
    // and until they do the supplier has not been sent anything.
    final isPendingApproval = status == 'PENDING_APPROVAL';
    // Goods can go back once something arrived: received, partly received, or short-closed.
    final isReturnable =
        status == 'RECEIVED' ||
        status == 'PARTIALLY_RECEIVED' ||
        status == 'CLOSED';
    final progressAsync = isDraft
        ? const AsyncValue<List<PurchaseOrderLineProgress>>.data([])
        : ref.watch(purchaseOrderProgressProvider(poId));
    final progress = progressAsync.asData?.value ?? const [];
    // Cross-docking: an order delivered to a warehouse can send its lines straight on to the shops.
    final stores = ref.watch(storesProvider).value ?? const <StoreInfo>[];
    final names = {for (final s in stores) s.id: s.name};
    final atWarehouse = po != null && stores.any((s) => s.id == po.storeId && s.type == 'WAREHOUSE');
    final allocations = atWarehouse
        ? ref.watch(purchaseOrderAllocationsProvider(poId)).value ?? const <LineAllocation>[]
        : const <LineAllocation>[];

    return AlertDialog(
      title: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(child: Text('PO #${shortRef(poId)}')),
              if (po != null) _PoStatusBadge(po.status),
              if (po != null && po.source == 'DROPSHIP') ...[
                const SizedBox(width: AppSpacing.sm),
                _dropshipBadge,
              ],
            ],
          ),
          // A dropship order ships to the customer, never here: say where.
          if (po?.shipTo != null)
            Padding(
              padding: const EdgeInsets.only(top: AppSpacing.xs),
              child: Text(
                'Ships to ${po!.shipTo}',
                key: const Key('po-ship-to'),
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ),
        ],
      ),
      content: SizedBox(
        width: 480,
        child: linesAsync.when(
          loading: () => const SizedBox(
            height: 140,
            child: LoadingView(label: 'Loading…'),
          ),
          error: (e, _) => SizedBox(
            height: 140,
            child: ErrorView(
              message: friendlyError(e, fallback: 'Could not load lines.'),
              onRetry: () => ref.invalidate(purchaseOrderLinesProvider(poId)),
            ),
          ),
          data: (lines) => Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (lines.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  child: Text(
                    'No lines yet.',
                    style: TextStyle(color: cs.outline),
                  ),
                )
              else
                ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 240),
                  child: ListView(
                    shrinkWrap: true,
                    children: [
                      for (final l in lines)
                        Builder(
                          builder: (context) {
                            final p = progress
                                .where((x) => x.variantId == l.variantId)
                                .firstOrNull;
                            final owed = p?.qtyOutstanding ?? 0;
                            return ListTile(
                              key: Key('po-line-${l.id}'),
                              dense: true,
                              contentPadding: EdgeInsets.zero,
                              leading: atWarehouse && isDraft
                                  ? IconButton(
                                      key: Key('po-line-allocate-${l.id}'),
                                      tooltip: 'Allocate to shops',
                                      icon: const Icon(Icons.call_split),
                                      onPressed: () => showDialog<void>(
                                        context: context,
                                        builder: (_) => _AllocateLineDialog(
                                          poId: poId,
                                          lineId: l.id,
                                          warehouseId: po.storeId,
                                          lineQty: l.qty,
                                          current: allocations.where((a) => a.poLineId == l.id).toList(),
                                        ),
                                      ),
                                    )
                                  : null,
                              title: Text(
                                variantDisplayName(l.variantId, labels),
                              ),
                              subtitle: Text(
                                [
                                  if (variantSku(l.variantId, labels).isNotEmpty)
                                    variantSku(l.variantId, labels),
                                  // The unit price is shown at its own precision, not the
                                  // currency's: a trade price of 0.0125 per screw is ordinary, and
                                  // rounding it to the penny here would misreport the line by 25%.
                                  '${AppFormat.count(l.qty)} × ${_unitPrice(l.unitPrice, po?.currency)}',
                                  if (l.vatCode != null) l.vatCode!,
                                  // What is still owed, which the status alone cannot say.
                                  if (p != null && owed > 0)
                                    '${owed.toStringAsFixed(0)} outstanding',
                                  if (p != null && owed == 0 && !isDraft)
                                    'complete',
                                  if (p != null && p.qtyReturned > 0)
                                    '${p.qtyReturned.toStringAsFixed(0)} returned',
                                ].join(' · ') +
                                    // The proposal's arithmetic, so the buyer can check the line.
                                    (l.proposalReason == null ? '' : '\n${l.proposalReason}') +
                                    // Where it goes on arrival, when it crosses the dock.
                                    (allocations.any((a) => a.poLineId == l.id)
                                        ? '\ncross-docked to ${allocations.where((a) => a.poLineId == l.id).map((a) => '${names[a.storeId] ?? shortRef(a.storeId)} ${a.qty.toStringAsFixed(0)}').join(', ')}'
                                        : ''),
                                style: TextStyle(
                                  color: owed > 0
                                      ? context.status.warning
                                      : null,
                                ),
                              ),
                              trailing: Text(
                                AppFormat.money(
                                  l.qty * l.unitPrice,
                                  currencyCode: po?.currency,
                                ),
                                style: const TextStyle(
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            );
                          },
                        ),
                    ],
                  ),
                ),
              const Divider(),
              if (po != null)
                Row(
                  children: [
                    const Text('Total (gross)'),
                    const Spacer(),
                    Text(
                      AppFormat.money(po.totalGross, currencyCode: po.currency),
                      style: const TextStyle(fontWeight: FontWeight.bold),
                    ),
                  ],
                ),
              const SizedBox(height: 8),
              if (isDraft)
                OutlinedButton.icon(
                  onPressed: () => showDialog(
                    context: context,
                    builder: (_) => _AddPoLineDialog(poId: poId),
                  ),
                  icon: const Icon(Icons.add),
                  label: const Text('Add line'),
                ),
              if (isReturnable)
                _VendorReturnsSection(poId: poId, currency: po?.currency),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Close'),
        ),
        if (isDraft)
          FilledButton.icon(
            onPressed: _submitting ? null : () => _submitPo(context, ref),
            icon: const Icon(Icons.send_outlined, size: 18),
            label: const Text('Submit'),
          )
        else if (isPendingApproval) ...[
          // Rejecting needs no spend authority — refusing to commit money is not a commitment —
          // so it is offered to anyone who can see the order. The server still decides whether
          // this caller may approve, and says so if not.
          TextButton.icon(
            onPressed: () => showDialog(
              context: context,
              builder: (_) => _RejectPoDialog(poId: poId),
            ),
            icon: const Icon(Icons.block_outlined, size: 18),
            label: const Text('Reject'),
          ),
          FilledButton.icon(
            onPressed: _submitting ? null : () => _approvePo(context, ref),
            icon: const Icon(Icons.check_circle_outline, size: 18),
            label: const Text('Approve'),
          ),
        ] else ...[
          // Abandoning the balance is a deliberate act with a reason, so it sits beside the
          // receive action rather than hiding in a menu — but only while there is a balance.
          if (isPartial)
            TextButton.icon(
              onPressed: () => showDialog(
                context: context,
                builder: (_) => _CloseShortDialog(poId: poId),
              ),
              icon: const Icon(Icons.do_not_disturb_on_outlined, size: 18),
              label: const Text('Close short'),
            ),
          if (isReturnable)
            TextButton.icon(
              key: const Key('po-return-to-vendor'),
              onPressed: () => showDialog(
                context: context,
                builder: (_) =>
                    _ReturnToVendorDialog(poId: poId, currency: po?.currency),
              ),
              icon: const Icon(Icons.undo_outlined, size: 18),
              label: const Text('Return to vendor'),
            ),
          if (isReceivable)
            FilledButton.icon(
              onPressed: po == null
                  ? null
                  : () {
                      Navigator.pop(context);
                      showDialog(
                        context: context,
                        builder: (_) => _ReceiveGoodsDialog(
                          poId: poId,
                          storeId: po.storeId,
                        ),
                      );
                    },
              icon: const Icon(Icons.inventory_outlined, size: 18),
              label: Text(isPartial ? 'Receive balance' : 'Receive goods'),
            ),
        ],
      ],
    );
  }

  Future<void> _submitPo(BuildContext context, WidgetRef ref) async {
    if (_submitting) return;
    setState(() => _submitting = true);
    try {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .post('/${ApiConstants.purchase}/purchase-orders/$poId/submit');
      ref.invalidate(purchaseOrdersProvider);
      if (!context.mounted) return;
      Navigator.pop(context);
      // The server decides which of the two happened, so the message reads the status back rather
      // than assuming. Telling a buyer their order went to the supplier when it is actually
      // waiting for a manager is the one thing this screen must not do.
      final held = (resp.data['data'] as Map?)?['status'] == 'PENDING_APPROVAL';
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            held
                ? 'Above your spend authority — sent for approval.'
                : 'Purchase order submitted.',
          ),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _submitting = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(friendlyError(e, fallback: 'Could not submit PO.')),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
    }
  }

  Future<void> _approvePo(BuildContext context, WidgetRef ref) async {
    if (_submitting) return;
    setState(() => _submitting = true);
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post('/${ApiConstants.purchase}/purchase-orders/$poId/approve');
      ref.invalidate(purchaseOrdersProvider);
      if (!context.mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Approved — the order is with the supplier.'),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _submitting = false);
      // The server's own message names both figures and the currency, which is the only useful
      // thing to show someone whose authority fell short — so it is surfaced rather than replaced.
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            friendlyError(e, fallback: 'Could not approve this order.'),
          ),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
    }
  }
}

/// Rejects a purchase order that is waiting for someone's spend authority.
///
/// The reason is required by the server and required here, for the same purpose: a rejection sends
/// the order back to DRAFT for the buyer to correct, and "no" with no explanation leaves them with
/// work to do and no idea what to change.
class _RejectPoDialog extends ConsumerStatefulWidget {
  final String poId;
  const _RejectPoDialog({required this.poId});

  @override
  ConsumerState<_RejectPoDialog> createState() => _RejectPoDialogState();
}

class _RejectPoDialogState extends ConsumerState<_RejectPoDialog> {
  final _reasonCtrl = TextEditingController();
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _reasonCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final reason = _reasonCtrl.text.trim();
    if (reason.isEmpty) {
      setState(() => _error = 'Say why, so the buyer knows what to change.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.purchase}/purchase-orders/${widget.poId}/reject',
            data: {'reason': reason},
          );
      if (!mounted) return;
      ref.invalidate(purchaseOrdersProvider);
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Rejected — the order is back with the buyer.'),
        ),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not reject this order.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Reject purchase order'),
      content: SizedBox(
        width: 400,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'The order goes back to DRAFT so it can be corrected and resubmitted. The '
              'rejection stays in its approval history either way.',
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _reasonCtrl,
              autofocus: true,
              maxLines: 2,
              decoration: const InputDecoration(
                labelText: 'Reason',
                hintText: 'e.g. get a second quote first',
                border: OutlineInputBorder(),
              ),
            ),
            if (_error != null) ...[
              const SizedBox(height: 12),
              Text(
                _error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _loading ? null : _submit,
          child: const Text('Reject'),
        ),
      ],
    );
  }
}

/// Abandons the undelivered balance of a partly received purchase order.
///
/// A reason is required for the same purpose it is on a cancellation: without
/// one, a short-closed order is indistinguishable next quarter from one the
/// supplier fulfilled, and the supplier is the party that has to answer for it.
class _CloseShortDialog extends ConsumerStatefulWidget {
  final String poId;
  const _CloseShortDialog({required this.poId});

  @override
  ConsumerState<_CloseShortDialog> createState() => _CloseShortDialogState();
}

class _CloseShortDialogState extends ConsumerState<_CloseShortDialog> {
  final _reasonCtrl = TextEditingController();
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _reasonCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_reasonCtrl.text.trim().isEmpty) {
      setState(() => _error = 'Say why the balance is being abandoned.');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.purchase}/purchase-orders/${widget.poId}/close',
            data: {'reason': _reasonCtrl.text.trim()},
          );
      ref.invalidate(purchaseOrdersProvider);
      ref.invalidate(purchaseOrderProgressProvider(widget.poId));
      if (!mounted) return;
      Navigator.pop(context);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'Could not close the order.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Close short'),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Text(_error!, style: TextStyle(color: cs.error)),
              ),
            Text(
              'The undelivered balance will be written off and the order marked '
              'CLOSED. What has already arrived stays received — this is not a '
              'cancellation.',
              style: TextStyle(color: cs.outline, fontSize: 13),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _reasonCtrl,
              autofocus: true,
              decoration: const InputDecoration(labelText: 'Reason *'),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _saving ? null : _submit,
          child: Text(_saving ? 'Closing…' : 'Close short'),
        ),
      ],
    );
  }
}

class _AddPoLineDialog extends ConsumerStatefulWidget {
  final String poId;
  const _AddPoLineDialog({required this.poId});

  @override
  ConsumerState<_AddPoLineDialog> createState() => _AddPoLineDialogState();
}

class _AddPoLineDialogState extends ConsumerState<_AddPoLineDialog> {
  String? _productId;
  String? _variantId;
  final _qtyCtrl = TextEditingController(text: '1');
  final _priceCtrl = TextEditingController();
  String _vatCode = 'STANDARD';
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _qtyCtrl.dispose();
    _priceCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final qty = double.tryParse(_qtyCtrl.text.trim());
    final price = double.tryParse(_priceCtrl.text.trim());
    if (_variantId == null ||
        qty == null ||
        qty <= 0 ||
        price == null ||
        price <= 0) {
      setState(() => _error = 'Pick a variant and enter qty + unit price.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.purchase}/purchase-orders/${widget.poId}/lines',
            data: {
              'variantId': _variantId,
              'qty': qty,
              'unitPrice': price,
              'vatCode': _vatCode,
            },
          );
      if (!mounted) return;
      ref.invalidate(purchaseOrderLinesProvider(widget.poId));
      ref.invalidate(purchaseOrdersProvider);
      Navigator.pop(context);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not add line.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Dialog.fullscreen(
      child: Scaffold(
        appBar: AppBar(
          leading: IconButton(
            icon: const Icon(Icons.close),
            tooltip: 'Cancel',
            onPressed: _loading ? null : () => Navigator.pop(context),
          ),
          title: const Text('Add PO line'),
          actions: [
            Padding(
              padding: const EdgeInsets.only(right: 16),
              child: Center(
                child: FilledButton(
                  onPressed: _loading ? null : _submit,
                  child: _loading
                      ? SizedBox(
                          height: 18,
                          width: 18,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: cs.onPrimary,
                          ),
                        )
                      : const Text('Add line'),
                ),
              ),
            ),
          ],
        ),
        body: SingleChildScrollView(
          padding: context.pagePadding,
          child: Center(
            child: ContentBounds.form(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (_error != null) ...[
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: cs.errorContainer,
                        borderRadius: AppRadius.chip,
                      ),
                      child: Text(
                        _error!,
                        style: TextStyle(color: cs.onErrorContainer),
                      ),
                    ),
                    const SizedBox(height: 12),
                  ],
                  VariantPicker(
                    productId: _productId,
                    variantId: _variantId,
                    onProduct: (p) => setState(() {
                      _productId = p;
                      _variantId = null;
                    }),
                    onVariant: (v) => setState(() => _variantId = v),
                  ),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Expanded(
                        child: TextField(
                          controller: _qtyCtrl,
                          keyboardType: TextInputType.number,
                          decoration: const InputDecoration(labelText: 'Qty'),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: TextField(
                          controller: _priceCtrl,
                          keyboardType: const TextInputType.numberWithOptions(
                            decimal: true,
                          ),
                          decoration: const InputDecoration(
                            labelText: 'Unit cost',
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<String>(
                    initialValue: _vatCode,
                    decoration: const InputDecoration(labelText: 'VAT code'),
                    items: const [
                      DropdownMenuItem(
                        value: 'STANDARD',
                        child: Text('Standard'),
                      ),
                      DropdownMenuItem(
                        value: 'REDUCED',
                        child: Text('Reduced'),
                      ),
                      DropdownMenuItem(value: 'ZERO', child: Text('Zero')),
                      DropdownMenuItem(value: 'EXEMPT', child: Text('Exempt')),
                    ],
                    onChanged: (v) => setState(() => _vatCode = v!),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// The returns raised against an order, each with its debit note and, once the
/// supplier has answered, its credit note (07.8). A manager records the credit
/// note here; nothing edits or deletes a return.
class _VendorReturnsSection extends ConsumerWidget {
  const _VendorReturnsSection({required this.poId, this.currency});
  final String poId;
  final String? currency;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final returns = ref.watch(vendorReturnsProvider(poId));
    return returns.when(
      loading: () => const SizedBox.shrink(),
      error: (e, _) => Padding(
        padding: const EdgeInsets.only(top: 8),
        child: Text(
          friendlyError(e, fallback: 'Could not load returns.'),
          style: TextStyle(color: cs.error, fontSize: 12),
        ),
      ),
      data: (rows) => rows.isEmpty
          ? const SizedBox.shrink()
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Divider(),
                Text(
                  'Returned to vendor',
                  style: Theme.of(context).textTheme.labelLarge,
                ),
                for (final r in rows)
                  ListTile(
                    key: Key('vendor-return-${r.id}'),
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    leading: Icon(
                      r.credited
                          ? Icons.check_circle_outline
                          : Icons.undo_outlined,
                      color: r.credited ? cs.primary : cs.tertiary,
                    ),
                    title: Text(
                      '${r.debitNoteNumber} · ${vendorReturnReasons[r.reason] ?? r.reason}',
                    ),
                    subtitle: Text(
                      [
                        for (final l in r.lines)
                          '${_trim(l.qty)} × ${_unitPrice(l.unitPrice, currency)}',
                        r.credited
                            ? 'credit note ${r.creditNoteNumber} · ${AppFormat.date(r.creditNoteDate)}'
                            : "awaiting the supplier's credit note",
                      ].join(' · '),
                    ),
                    trailing: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          AppFormat.money(
                            r.grossAmount,
                            currencyCode: currency,
                          ),
                          style: const TextStyle(fontWeight: FontWeight.bold),
                        ),
                        if (!r.credited)
                          TextButton(
                            key: Key('vendor-return-credit-${r.id}'),
                            onPressed: () => showDialog(
                              context: context,
                              builder: (_) => _RecordCreditNoteDialog(
                                ret: r,
                                currency: currency,
                              ),
                            ),
                            child: const Text('Credit note'),
                          ),
                      ],
                    ),
                  ),
              ],
            ),
    );
  }
}

/// Sends goods back against a received order (07.8): what and how many, and
/// why. The server prices the debit note at the order's own prices and refuses
/// more than was received less what already went back.
class _ReturnToVendorDialog extends ConsumerStatefulWidget {
  final String poId;
  final String? currency;
  const _ReturnToVendorDialog({required this.poId, this.currency});

  @override
  ConsumerState<_ReturnToVendorDialog> createState() =>
      _ReturnToVendorDialogState();
}

class _ReturnToVendorDialogState extends ConsumerState<_ReturnToVendorDialog> {
  final Map<String, double> _qty = {};
  String _reason = 'DAMAGED';
  final _notes = TextEditingController();
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _notes.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final lines = [
      for (final e in _qty.entries)
        if (e.value > 0) {'variantId': e.key, 'qty': e.value},
    ];
    if (lines.isEmpty) {
      setState(() => _error = 'Enter at least one quantity to send back.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.purchase}/vendor-returns',
            data: {
              'poId': widget.poId,
              'reason': _reason,
              if (_notes.text.trim().isNotEmpty) 'notes': _notes.text.trim(),
              'lines': lines,
            },
          );
      if (!mounted) return;
      ref.invalidate(vendorReturnsProvider(widget.poId));
      ref.invalidate(purchaseOrderProgressProvider(widget.poId));
      Navigator.pop(context);
      final number = (resp.data['data'] as Map?)?['debitNoteNumber'];
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Goods returned — debit note $number raised.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not raise the return.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final progressAsync = ref.watch(purchaseOrderProgressProvider(widget.poId));
    final labels = ref
            .watch(variantLabelsProvider(variantIdsKey([
              for (final p in progressAsync.value ?? const []) p.variantId as String,
            ])))
            .value ??
        const <String, VariantLabel>{};
    return AlertDialog(
      title: const Text('Return to vendor'),
      content: SizedBox(
        width: 460,
        child: progressAsync.when(
          loading: () => const SizedBox(
            height: 120,
            child: LoadingView(label: 'Loading…'),
          ),
          error: (e, _) => SizedBox(
            height: 120,
            child: ErrorView(
              message: friendlyError(
                e,
                fallback: 'Could not load what was received.',
              ),
              onRetry: () =>
                  ref.invalidate(purchaseOrderProgressProvider(widget.poId)),
            ),
          ),
          data: (progress) => Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null) ...[
                Container(
                  key: const Key('rtv-error'),
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: cs.errorContainer,
                    borderRadius: AppRadius.chip,
                  ),
                  child: Text(
                    _error!,
                    style: TextStyle(color: cs.onErrorContainer),
                  ),
                ),
                const SizedBox(height: 12),
              ],
              DropdownButtonFormField<String>(
                key: const Key('rtv-reason'),
                initialValue: _reason,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Reason'),
                items: [
                  for (final e in vendorReturnReasons.entries)
                    DropdownMenuItem(value: e.key, child: Text(e.value)),
                ],
                onChanged: (v) => setState(() => _reason = v ?? 'DAMAGED'),
              ),
              const SizedBox(height: 8),
              Text(
                'Quantity to send back',
                style: Theme.of(context).textTheme.labelLarge,
              ),
              const SizedBox(height: 4),
              ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 220),
                child: ListView(
                  shrinkWrap: true,
                  children: [
                    for (final p in progress)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 4),
                        child: Row(
                          children: [
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    variantDisplayName(p.variantId, labels),
                                  ),
                                  Text(
                                    'received ${_trim(p.qtyReceived)} · returned ${_trim(p.qtyReturned)} · '
                                    'up to ${_trim(p.qtyReturnable)} can go back',
                                    style: TextStyle(
                                      fontSize: 11,
                                      color: cs.outline,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            SizedBox(
                              width: 90,
                              child: TextField(
                                key: Key('rtv-qty-${p.variantId}'),
                                enabled: p.qtyReturnable > 0,
                                keyboardType:
                                    const TextInputType.numberWithOptions(
                                      decimal: true,
                                    ),
                                decoration: const InputDecoration(
                                  isDense: true,
                                  hintText: '0',
                                ),
                                onChanged: (v) =>
                                    _qty[p.variantId] = double.tryParse(v) ?? 0,
                              ),
                            ),
                          ],
                        ),
                      ),
                  ],
                ),
              ),
              TextField(
                key: const Key('rtv-notes'),
                controller: _notes,
                decoration: const InputDecoration(
                  labelText: 'Notes',
                  hintText: 'e.g. three cases crushed in transit',
                ),
              ),
              const SizedBox(height: 8),
              Text(
                "The debit note is priced at the order's prices. Stock leaves the store when the return is raised; the purchase order itself is unchanged.",
                style: TextStyle(fontSize: 12, color: cs.outline),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton.icon(
          key: const Key('rtv-submit'),
          onPressed: _loading ? null : _submit,
          icon: const Icon(Icons.undo_outlined, size: 18),
          label: const Text('Send back'),
        ),
      ],
    );
  }
}

/// Records the supplier's credit note against a return, closing it.
class _RecordCreditNoteDialog extends ConsumerStatefulWidget {
  final VendorReturn ret;
  final String? currency;
  const _RecordCreditNoteDialog({required this.ret, this.currency});

  @override
  ConsumerState<_RecordCreditNoteDialog> createState() =>
      _RecordCreditNoteDialogState();
}

class _RecordCreditNoteDialogState
    extends ConsumerState<_RecordCreditNoteDialog> {
  final _number = TextEditingController();
  final _date = TextEditingController(
    text: DateTime.now().toIso8601String().split('T').first,
  );
  late final TextEditingController _amount = TextEditingController(
    text: widget.ret.grossAmount.toStringAsFixed(2),
  );
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _number.dispose();
    _date.dispose();
    _amount.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.purchase}/vendor-returns/${widget.ret.id}/credit',
            data: {
              'creditNoteNumber': _number.text.trim(),
              'creditNoteDate': _date.text.trim(),
              if (_amount.text.trim().isNotEmpty)
                'amount': double.tryParse(_amount.text.trim()),
            },
          );
      if (!mounted) return;
      ref.invalidate(vendorReturnsProvider(widget.ret.poId));
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            'Credit note recorded against ${widget.ret.debitNoteNumber}.',
          ),
        ),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(
          e,
          fallback: 'Could not record the credit note.',
        );
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('Credit note for ${widget.ret.debitNoteNumber}'),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              key: const Key('credit-number'),
              controller: _number,
              decoration: const InputDecoration(
                labelText: 'Credit note number',
              ),
            ),
            TextField(
              key: const Key('credit-date'),
              controller: _date,
              decoration: const InputDecoration(
                labelText: 'Credit note date',
                helperText: 'YYYY-MM-DD',
              ),
            ),
            TextField(
              key: const Key('credit-amount'),
              controller: _amount,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
              decoration: InputDecoration(
                labelText: 'Amount credited',
                helperText:
                    'The debit note asked for ${AppFormat.money(widget.ret.grossAmount, currencyCode: widget.currency)}',
              ),
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: const Key('credit-error'),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('credit-submit'),
          onPressed: _loading ? null : _submit,
          child: const Text('Record'),
        ),
      ],
    );
  }
}

class _ReceiveGoodsDialog extends ConsumerStatefulWidget {
  final String poId;
  final String storeId;
  const _ReceiveGoodsDialog({required this.poId, required this.storeId});

  @override
  ConsumerState<_ReceiveGoodsDialog> createState() =>
      _ReceiveGoodsDialogState();
}

class _ReceiveGoodsDialogState extends ConsumerState<_ReceiveGoodsDialog> {
  final Map<String, double> _received = {};
  bool _loading = false;
  String? _error;

  Future<void> _submit(List<PurchaseOrderLine> lines) async {
    final received = [
      for (final l in lines)
        if ((_received[l.variantId] ?? l.qty) > 0)
          {
            'variantId': l.variantId,
            'qtyReceived': _received[l.variantId] ?? l.qty,
          },
    ];
    if (received.isEmpty) {
      setState(() => _error = 'Enter at least one received quantity.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.purchase}/goods-receipts',
            data: {
              'poId': widget.poId,
              'storeId': widget.storeId,
              'lines': received,
            },
          );
      if (!mounted) return;
      ref.invalidate(purchaseOrdersProvider);
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Goods received — stock updated.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not record receipt.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final linesAsync = ref.watch(purchaseOrderLinesProvider(widget.poId));
    final labels = ref
            .watch(variantLabelsProvider(variantIdsKey([
              for (final l in linesAsync.value ?? const []) l.variantId as String,
            ])))
            .value ??
        const <String, VariantLabel>{};
    return AlertDialog(
      title: const Text('Receive goods'),
      content: SizedBox(
        width: 440,
        child: linesAsync.when(
          loading: () => const SizedBox(
            height: 120,
            child: LoadingView(label: 'Loading…'),
          ),
          error: (e, _) => SizedBox(
            height: 120,
            child: ErrorView(
              message: friendlyError(e, fallback: 'Could not load PO lines.'),
              onRetry: () =>
                  ref.invalidate(purchaseOrderLinesProvider(widget.poId)),
            ),
          ),
          data: (lines) => Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null) ...[
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: cs.errorContainer,
                    borderRadius: AppRadius.chip,
                  ),
                  child: Text(
                    _error!,
                    style: TextStyle(color: cs.onErrorContainer),
                  ),
                ),
                const SizedBox(height: 12),
              ],
              Text(
                'Confirm received quantities',
                style: Theme.of(context).textTheme.labelLarge,
              ),
              const SizedBox(height: 8),
              ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 280),
                child: ListView(
                  shrinkWrap: true,
                  children: [
                    for (final l in lines)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 4),
                        child: Row(
                          children: [
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    variantDisplayName(l.variantId, labels),
                                  ),
                                  Text(
                                    'ordered ${AppFormat.count(l.qty)}',
                                    style: TextStyle(
                                      fontSize: 11,
                                      color: cs.outline,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            SizedBox(
                              width: 90,
                              child: TextFormField(
                                initialValue: l.qty.toStringAsFixed(0),
                                keyboardType: TextInputType.number,
                                decoration: const InputDecoration(
                                  labelText: 'Received',
                                  isDense: true,
                                ),
                                onChanged: (v) => _received[l.variantId] =
                                    double.tryParse(v) ?? 0,
                              ),
                            ),
                          ],
                        ),
                      ),
                  ],
                ),
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
          onPressed: _loading
              ? null
              : () {
                  final lines = ref
                      .read(purchaseOrderLinesProvider(widget.poId))
                      .value;
                  if (lines != null) _submit(lines);
                },
          child: _loading
              ? SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Theme.of(context).colorScheme.onPrimary,
                  ),
                )
              : const Text('Confirm receipt'),
        ),
      ],
    );
  }
}

/// A purchase order's status in words, in the tone of what it asks of a buyer.
class _PoStatusBadge extends StatelessWidget {
  final String status;
  const _PoStatusBadge(this.status);

  @override
  Widget build(BuildContext context) {
    final (label, tone) = purchaseOrderStatus(status);
    return StatusBadge(label.isEmpty ? status : label, tone: tone);
  }
}

// What an order is beyond its status, in the one badge (§7.1): words, a tone for scanning.
const _proposedBadge = StatusBadge('Proposed', tone: StatusTone.info);
const _consignmentBadge = StatusBadge('Consignment', tone: StatusTone.warning);
const _dropshipBadge = StatusBadge('Dropship', tone: StatusTone.info);
const _inBondBadge = StatusBadge('In bond');
const _rfqBadge = StatusBadge('From RFQ', tone: StatusTone.accent);


/// A unit price as money in [currency], at its own precision up to four
/// places: a trade price of 0.0125 a screw reads `£0.0125`, never rounded to
/// the penny, and one at the minor unit reads as plain money (`£1.20`).
String _unitPrice(double v, String? currency) =>
    AppFormat.money(v, currencyCode: currency, maxDecimals: 4);

/// A quantity at its own precision, with trailing zeroes removed.
///
/// Deliberately not [AppFormat.money]: that rounds to the currency's minor unit, which is right for
/// a total and wrong for a unit price. Buying 1,000 screws at 0.0125 each is an ordinary trade
/// price, and showing it as 0.01 misreports the line by 25% — the same defect SJ-D25 removed from
/// the column type.
String _trim(double v) {
  final s = v.toStringAsFixed(4);
  return s.contains('.')
      ? s.replaceFirst(RegExp(r'0+$'), '').replaceFirst(RegExp(r'\.$'), '')
      : s;
}

// ── The automatic order proposal (06.x) ─────────────────────────────────────

/// A store and a cover period; purchase-svc does the rest and says what it
/// raised and what it skipped and why.
class _ProposeOrdersDialog extends ConsumerStatefulWidget {
  const _ProposeOrdersDialog();

  @override
  ConsumerState<_ProposeOrdersDialog> createState() => _ProposeOrdersDialogState();
}

class _ProposeOrdersDialogState extends ConsumerState<_ProposeOrdersDialog> {
  String? _storeId;
  final _coverCtrl = TextEditingController(text: '28');
  bool _running = false;

  @override
  void dispose() {
    _coverCtrl.dispose();
    super.dispose();
  }

  Future<void> _run(String storeId) async {
    setState(() => _running = true);
    final messenger = ScaffoldMessenger.of(context);
    final navigator = Navigator.of(context);
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
            '/${ApiConstants.purchase}/purchase-orders/proposals/run',
            data: {
              'storeId': storeId,
              'coverDays': int.tryParse(_coverCtrl.text.trim()) ?? 28,
            },
          );
      final d = (resp.data['data'] as Map<String, dynamic>?) ?? {};
      final orders = (d['orders'] as List?) ?? [];
      final skipped = (d['skipped'] as List?) ?? [];
      final drafts = orders
          .map((o) => o as Map<String, dynamic>)
          .map((o) =>
              '${o['supplierName'] ?? 'supplier'} (${o['lines']} line${o['lines'] == 1 ? '' : 's'}, '
              '${AppFormat.money((o['totalNet'] as num?)?.toDouble() ?? 0, currencyCode: o['currency'] as String?)})')
          .join(', ');
      final text = orders.isEmpty
          ? (skipped.isEmpty
              ? 'Nothing to order: every item is above its reorder point.'
              : 'Nothing to order; ${skipped.length} item${skipped.length == 1 ? '' : 's'} skipped — open the run to see why.')
          : 'Proposed ${orders.length} draft${orders.length == 1 ? '' : 's'}: $drafts'
              '${skipped.isEmpty ? '' : ' · ${skipped.length} skipped'}';
      ref.invalidate(purchaseOrdersProvider);
      navigator.pop();
      messenger.showSnackBar(SnackBar(content: Text(text)));
    } catch (e) {
      messenger.showSnackBar(
          SnackBar(content: Text(friendlyError(e, fallback: 'The proposal could not run.'))));
    } finally {
      if (mounted) setState(() => _running = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final stores = ref.watch(storesProvider).value ?? const [];
    final storeId = _storeId ?? (stores.isNotEmpty ? stores.first.id : null);
    return AlertDialog(
      title: const Text('Propose orders'),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            DropdownButtonFormField<String>(
              key: const Key('propose-store'),
              initialValue: storeId,
              decoration: const InputDecoration(labelText: 'Store'),
              items: stores
                  .map((s) => DropdownMenuItem(value: s.id, child: Text(s.name)))
                  .toList(),
              onChanged: (v) => setState(() => _storeId = v),
            ),
            const SizedBox(height: 12),
            TextField(
              key: const Key('propose-cover'),
              controller: _coverCtrl,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                labelText: 'Cover (days)',
                helperText:
                    'An item with no EOQ is ordered back to its reorder point plus this many days of forecast.',
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Every item at or below its reorder point becomes a line on a draft order for the '
              'supplier you last bought it from. You submit the drafts.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton.icon(
          key: const Key('propose-run'),
          onPressed: storeId == null || _running ? null : () => _run(storeId),
          icon: const Icon(Icons.auto_graph),
          label: const Text('Run'),
        ),
      ],
    );
  }
}

/// Cross-docking: a warehouse draft's line allocated to the shops the warehouse serves — typed, or
/// filled from what they need now — so the delivery goes straight across the dock to them.
class _AllocateLineDialog extends ConsumerStatefulWidget {
  final String poId;
  final String lineId;
  final String warehouseId;
  final double lineQty;
  final List<LineAllocation> current;
  const _AllocateLineDialog({
    required this.poId,
    required this.lineId,
    required this.warehouseId,
    required this.lineQty,
    required this.current,
  });

  @override
  ConsumerState<_AllocateLineDialog> createState() => _AllocateLineDialogState();
}

class _AllocateLineDialogState extends ConsumerState<_AllocateLineDialog> {
  final Map<String, TextEditingController> _qty = {};
  bool _busy = false;

  TextEditingController _ctrl(String storeId) => _qty.putIfAbsent(storeId, () {
        final had = widget.current.where((a) => a.storeId == storeId).firstOrNull;
        return TextEditingController(text: had == null ? '' : had.qty.toStringAsFixed(0));
      });

  @override
  void dispose() {
    for (final c in _qty.values) {
      c.dispose();
    }
    super.dispose();
  }

  String get _path => '/${ApiConstants.purchase}/purchase-orders/${widget.poId}/lines/${widget.lineId}/allocations';

  Future<void> _done(String message) async {
    ref.invalidate(purchaseOrderAllocationsProvider(widget.poId));
    if (!mounted) return;
    Navigator.of(context).pop();
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _send(Future<void> Function() call, String done, String fallback) async {
    setState(() => _busy = true);
    try {
      await call();
      await _done(done);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(friendlyError(e, fallback: fallback))));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final stores = ref.watch(storesProvider).value ?? const <StoreInfo>[];
    final names = {for (final s in stores) s.id: s.name};
    final network = ref.watch(servedShopsProvider(widget.warehouseId));
    return AlertDialog(
      title: const Text('Allocate to shops'),
      content: SizedBox(
        width: 420,
        child: network.when(
          loading: () => const SizedBox(height: 120, child: LoadingView(label: 'Loading the shops…')),
          error: (e, _) => ErrorView(message: friendlyError(e, fallback: 'Could not load the shops.')),
          data: (shops) => shops.isEmpty
              ? const Text('This warehouse serves no shop yet: set that on the Inventory screen, Depot & shops.')
              : Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text('Of ${widget.lineQty.toStringAsFixed(0)} on the line. What is allocated crosses the dock on arrival; the rest is put away.'),
                    for (final shop in shops)
                      TextField(
                        key: Key('allocate-$shop'),
                        controller: _ctrl(shop),
                        keyboardType: const TextInputType.numberWithOptions(decimal: true),
                        decoration: InputDecoration(labelText: names[shop] ?? shortRef(shop)),
                      ),
                  ],
                ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
        TextButton(
          key: const Key('allocate-fill'),
          onPressed: _busy
              ? null
              : () => _send(
                    () => ref.read(apiClientProvider).dio.post('$_path/fill'),
                    'Allocated by what the shops need now.',
                    'Could not fill from the shops\' needs.',
                  ),
          child: const Text('Fill from the shops\' needs'),
        ),
        FilledButton(
          key: const Key('allocate-save'),
          onPressed: _busy
              ? null
              : () {
                  final rows = [
                    for (final e in _qty.entries)
                      if ((double.tryParse(e.value.text.trim()) ?? 0) > 0)
                        {'storeId': e.key, 'qty': double.parse(e.value.text.trim())},
                  ];
                  _send(
                    () => ref.read(apiClientProvider).dio.put(_path, data: {'allocations': rows}),
                    rows.isEmpty ? 'Allocations cleared.' : 'Allocated.',
                    'Could not allocate the line.',
                  );
                },
          child: const Text('Save'),
        ),
      ],
    );
  }
}

/// The shops a warehouse serves, from inventory's network.
final servedShopsProvider = FutureProvider.autoDispose.family<List<String>, String>((ref, warehouseId) async {
  final resp = await ref.read(apiClientProvider).dio.get('/${ApiConstants.inventory}/admin/inventory/network/serving');
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => e as Map<String, dynamic>)
      .where((e) => e['warehouseId'] == warehouseId)
      .map((e) => e['storeId'] as String)
      .toList();
});
