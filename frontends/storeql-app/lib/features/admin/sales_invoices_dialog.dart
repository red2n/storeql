import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/loading_view.dart';
import 'einvoice_providers.dart' show eInvoiceSaverProvider;
import 'einvoice_tab.dart' show ElectronicAddressFields;
import 'providers/admin_providers.dart' show tenantInfoProvider;
import 'sales_invoice_providers.dart';

// ── A sale's invoice and credit notes (18.9) ────────────────────────────────

/// The documents a sale to a business was given: the invoice issued when it
/// completed, and a credit note for each return. Each downloads as issued,
/// or as CII, Factur-X or India's IRP document; an Indian document the portal
/// would refuse says why here rather than offering that download. The invoice
/// can be issued by hand when the automatic one was refused — the customer's
/// registration recorded since, say — and asking again never takes a second
/// number.
class OrderInvoicesDialog extends ConsumerStatefulWidget {
  final String orderId;
  const OrderInvoicesDialog({super.key, required this.orderId});

  @override
  ConsumerState<OrderInvoicesDialog> createState() =>
      _OrderInvoicesDialogState();
}

class _OrderInvoicesDialogState extends ConsumerState<OrderInvoicesDialog> {
  bool _busy = false;
  String? _error;

  Future<void> _issue() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await issueInvoice(ref.read(apiClientProvider).dio, widget.orderId);
      ref.invalidate(orderInvoicesProvider(widget.orderId));
      if (mounted) setState(() => _busy = false);
    } catch (e) {
      _failed(e, 'Could not issue the invoice.');
    }
  }

  Future<void> _send(SalesInvoice inv) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final t = await sendInvoice(ref.read(apiClientProvider).dio, inv.id);
      ref.invalidate(orderInvoicesProvider(widget.orderId));
      if (mounted) {
        setState(() {
          _busy = false;
          _error = t.status == 'ACCEPTED' || t.status == 'PENDING'
              ? null
              : t.summary;
        });
      }
    } catch (e) {
      _failed(e, 'Could not send the document.');
    }
  }

  Future<void> _download(SalesInvoice inv, String format) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final bytes = await salesInvoiceDocument(
        ref.read(apiClientProvider).dio,
        inv.id,
        format,
      );
      await ref.read(eInvoiceSaverProvider)(
          salesInvoiceFileName(inv, format), bytes);
      if (mounted) setState(() => _busy = false);
    } catch (e) {
      _failed(e, 'Could not download the document.');
    }
  }

  void _failed(Object e, String fallback) {
    if (!mounted) return;
    setState(() {
      _busy = false;
      _error = friendlyError(e, fallback: fallback);
    });
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(orderInvoicesProvider(widget.orderId));
    final docs = async.value ?? const <SalesInvoice>[];
    final sending =
        ref.watch(transportSettingsProvider).value?.sending ?? false;
    final invoiced = docs.any((d) => !d.creditNote);
    return AlertDialog(
      title: const Text('Invoices and credit notes'),
      content: SizedBox(
        width: 560,
        child: async.when(
          loading: () => const SizedBox(
            height: 120,
            child: LoadingView(label: 'Loading the documents…'),
          ),
          error: (e, _) => Text(
            friendlyError(e, fallback: 'Could not load the documents.'),
          ),
          data: (docs) => SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              mainAxisSize: MainAxisSize.min,
              children: [
                if (docs.isEmpty)
                  Text(
                    'No invoice has been issued for this sale. A sale to a customer '
                    'recorded as a VAT-registered business is invoiced when it '
                    'completes; if the registration was recorded since, issue it now.',
                    style: TextStyle(color: cs.outline),
                  ),
                for (final d in docs)
                  _DocumentTile(
                    doc: d,
                    busy: _busy,
                    canSend: sending &&
                        (d.transmission == null ||
                            d.transmission!.sendableAgain),
                    onDownload: _download,
                    onSend: _send,
                  ),
                if (_error != null) ...[
                  const SizedBox(height: 12),
                  Text(_error!,
                      key: const Key('sales-invoice-error'),
                      style: TextStyle(color: cs.error)),
                ],
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Close'),
        ),
        if (async.hasValue && !invoiced)
          FilledButton.icon(
            key: const Key('sales-invoice-issue'),
            onPressed: _busy ? null : _issue,
            icon: const Icon(Icons.receipt_long_outlined, size: 18),
            label: const Text('Issue invoice'),
          ),
      ],
    );
  }
}

class _DocumentTile extends StatelessWidget {
  final SalesInvoice doc;
  final bool busy;
  final bool canSend;
  final void Function(SalesInvoice doc, String format) onDownload;
  final void Function(SalesInvoice doc) onSend;
  const _DocumentTile({
    required this.doc,
    required this.busy,
    required this.canSend,
    required this.onDownload,
    required this.onSend,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    String money(double v) => AppFormat.money(v, currencyCode: doc.currency);
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(
                  doc.creditNote
                      ? Icons.undo_outlined
                      : Icons.receipt_long_outlined,
                  size: 20,
                  color: doc.creditNote ? cs.tertiary : cs.primary,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '${doc.kindLabel} ${doc.fullNumber}',
                    key: Key('sales-invoice-${doc.id}'),
                    style: text.titleSmall,
                  ),
                ),
                if (doc.peppol) ...[
                  Tooltip(
                    message:
                        'Peppol BIS Billing 3.0: both parties have a network address',
                    child: Chip(
                      label: const Text('Peppol'),
                      visualDensity: VisualDensity.compact,
                      backgroundColor: cs.secondaryContainer,
                      labelStyle: TextStyle(
                          fontSize: 11, color: cs.onSecondaryContainer),
                    ),
                  ),
                  const SizedBox(width: 4),
                ],
                PopupMenuButton<String>(
                  key: Key('sales-invoice-download-${doc.id}'),
                  tooltip: 'Download',
                  enabled: !busy,
                  icon: const Icon(Icons.download_outlined),
                  itemBuilder: (_) => [
                    for (final f in doc.formats)
                      PopupMenuItem(
                          value: f, child: Text(salesInvoiceFormatLabel(f))),
                  ],
                  onSelected: (f) => onDownload(doc, f),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              '${doc.buyerName}'
              '${doc.buyerVatId == null ? '' : ' · ${doc.buyerVatId}'}'
              ' · ${AppFormat.date(doc.issueDate)}',
              style: TextStyle(color: cs.outline, fontSize: 12),
            ),
            const SizedBox(height: 2),
            Text(
              'Net ${money(doc.netAmount)} · VAT ${money(doc.vatAmount)} · '
              '${doc.creditNote ? 'Credited' : 'Payable'} ${money(doc.payableAmount)}',
              style: text.bodySmall,
            ),
            if (doc.transmission != null || canSend) ...[
              const SizedBox(height: 6),
              Row(
                children: [
                  if (doc.transmission != null)
                    Expanded(
                      child: Text(
                        doc.transmission!.summary,
                        key: Key('sales-invoice-transmission-${doc.id}'),
                        style: TextStyle(
                          fontSize: 12,
                          color: switch (doc.transmission!.status) {
                            'ACCEPTED' => cs.primary,
                            'REJECTED' || 'FAILED' => cs.error,
                            _ => cs.outline,
                          },
                        ),
                      ),
                    )
                  else
                    const Spacer(),
                  if (canSend)
                    TextButton.icon(
                      key: Key('sales-invoice-send-${doc.id}'),
                      onPressed: busy ? null : () => onSend(doc),
                      icon: const Icon(Icons.send_outlined, size: 16),
                      label: Text(
                          doc.transmission == null ? 'Send' : 'Send again'),
                    ),
                ],
              ),
            ],
            if (doc.irpProblems.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(
                'India\'s Invoice Registration Portal would refuse this document; '
                'put these right and issue the next one:',
                style: TextStyle(color: cs.error, fontSize: 12),
              ),
              for (final p in doc.irpProblems)
                Text('• $p', style: TextStyle(color: cs.error, fontSize: 12)),
            ],
          ],
        ),
      ),
    );
  }
}

// ── A customer's VAT registration ───────────────────────────────────────────

/// Whether a customer is a VAT-registered business, on the customer's record:
/// what decides whether a sale to them is invoiced, and what the invoice
/// names them as.
class VatRegistrationSection extends ConsumerWidget {
  final String customerId;
  const VatRegistrationSection({super.key, required this.customerId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(customerVatStatusProvider(customerId));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Text('VAT registration',
                style: Theme.of(context).textTheme.labelLarge),
            const Spacer(),
            TextButton.icon(
              key: const Key('customer-vat-edit'),
              icon: const Icon(Icons.edit_outlined, size: 18),
              label: const Text('Edit'),
              onPressed: async.hasValue
                  ? () => showDialog<void>(
                        context: context,
                        builder: (_) => VatRegistrationDialog(
                          customerId: customerId,
                          current: async.value,
                        ),
                      )
                  : null,
            ),
          ],
        ),
        async.when(
          loading: () => const Padding(
            padding: EdgeInsets.all(12),
            child: Center(child: CircularProgressIndicator()),
          ),
          error: (e, _) => Text(
            friendlyError(e, fallback: 'Could not load the VAT registration.'),
            style: TextStyle(color: cs.error),
          ),
          data: (s) => s == null || !s.vatRegistered
              ? Text(
                  'Not recorded as a VAT-registered business: sales to this customer get '
                  'a receipt, not an invoice.',
                  key: const Key('customer-vat-none'),
                  style: TextStyle(color: cs.outline),
                )
              : Text(
                  [
                    if ((s.legalName ?? '').isNotEmpty) s.legalName,
                    s.vatNumber,
                    if ((s.countryCode ?? '').isNotEmpty) s.countryCode,
                    if (s.reverseChargeEligible) 'reverse charge',
                    if (s.hasElectronicAddress)
                      'Peppol ${s.einvoiceScheme}:${s.einvoiceId}',
                  ].whereType<String>().join(' · '),
                  key: const Key('customer-vat-summary'),
                ),
        ),
      ],
    );
  }
}

/// Records what a customer's invoices name them as. The VAT number is parsed
/// for its country by the service (a GSTIN in India, a VAT identifier
/// elsewhere), so a refusal comes back in words rather than the form guessing
/// every country's shape.
class VatRegistrationDialog extends ConsumerStatefulWidget {
  final String customerId;
  final CustomerVatStatus? current;
  const VatRegistrationDialog(
      {super.key, required this.customerId, this.current});

  @override
  ConsumerState<VatRegistrationDialog> createState() =>
      _VatRegistrationDialogState();
}

class _VatRegistrationDialogState extends ConsumerState<VatRegistrationDialog> {
  final _form = GlobalKey<FormState>();
  late bool _registered = widget.current?.vatRegistered ?? true;
  late bool _reverseCharge = widget.current?.reverseChargeEligible ?? false;
  late final _legalName =
      TextEditingController(text: widget.current?.legalName ?? '');
  late final _vat =
      TextEditingController(text: widget.current?.vatNumber ?? '');
  late final _country =
      TextEditingController(text: widget.current?.countryCode ?? '');
  late final _scheme =
      TextEditingController(text: widget.current?.einvoiceScheme ?? '');
  late final _id =
      TextEditingController(text: widget.current?.einvoiceId ?? '');
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _legalName.dispose();
    _vat.dispose();
    _country.dispose();
    _scheme.dispose();
    _id.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_busy || !_form.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await saveCustomerVatStatus(
        ref.read(apiClientProvider).dio,
        customerId: widget.customerId,
        vatRegistered: _registered,
        reverseChargeEligible: _registered && _reverseCharge,
        vatNumber: _vat.text,
        legalName: _legalName.text,
        countryCode: _country.text,
        einvoiceScheme: _scheme.text,
        einvoiceId: _id.text,
      );
      ref.invalidate(customerVatStatusProvider(widget.customerId));
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = friendlyError(e, fallback: 'Could not save the registration.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    // A hint, not a default: the business's own country when it is known,
    // never a country picked for it (multi-location, multi-tenant — SJ-D67).
    final tenantCountry = ref.watch(tenantInfoProvider).value?.country;
    final countryHint = (tenantCountry == null || tenantCountry.isEmpty) ? null : tenantCountry;
    return AlertDialog(
      title: const Text('VAT registration'),
      content: SizedBox(
        width: 480,
        child: Form(
          key: _form,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SwitchListTile.adaptive(
                  key: const Key('customer-vat-registered'),
                  contentPadding: EdgeInsets.zero,
                  title: const Text('VAT-registered business'),
                  subtitle: const Text(
                      'A completed sale to them is invoiced, not just receipted.'),
                  value: _registered,
                  onChanged:
                      _busy ? null : (v) => setState(() => _registered = v),
                ),
                TextFormField(
                  key: const Key('customer-vat-legal-name'),
                  controller: _legalName,
                  enabled: _registered,
                  maxLength: 200,
                  decoration: const InputDecoration(
                    labelText: 'Registered name',
                    helperText:
                        'The name the invoice addresses; their name on the record otherwise.',
                    helperMaxLines: 2,
                    counterText: '',
                  ),
                ),
                const SizedBox(height: 8),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: TextFormField(
                        key: const Key('customer-vat-number'),
                        controller: _vat,
                        enabled: _registered,
                        decoration: const InputDecoration(
                          labelText: 'VAT number',
                          hintText: 'GB123456789 · 29AAGCB7383J1Z4',
                        ),
                        validator: (v) =>
                            _registered && (v ?? '').trim().isEmpty
                                ? 'A registered business has a VAT number'
                                : null,
                      ),
                    ),
                    const SizedBox(width: 12),
                    SizedBox(
                      width: 96,
                      child: TextFormField(
                        key: const Key('customer-vat-country'),
                        controller: _country,
                        enabled: _registered,
                        maxLength: 2,
                        textCapitalization: TextCapitalization.characters,
                        decoration: InputDecoration(
                          labelText: 'Country',
                          hintText: countryHint,
                          counterText: '',
                        ),
                        validator: (v) {
                          final t = (v ?? '').trim();
                          return t.isEmpty ||
                                  RegExp(r'^[A-Za-z]{2}$').hasMatch(t)
                              ? null
                              : 'Two letters';
                        },
                      ),
                    ),
                  ],
                ),
                SwitchListTile.adaptive(
                  key: const Key('customer-vat-reverse-charge'),
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Reverse charge'),
                  subtitle: const Text(
                      'They account for the VAT themselves; lines are zero-rated as AE.'),
                  value: _registered && _reverseCharge,
                  onChanged: _busy || !_registered
                      ? null
                      : (v) => setState(() => _reverseCharge = v),
                ),
                const SizedBox(height: 8),
                ElectronicAddressFields(
                  scheme: _scheme,
                  id: _id,
                  keyPrefix: 'customer',
                  label: 'E-invoicing address',
                  helperText:
                      'Where they receive e-invoices on the Peppol network, or blank.',
                ),
                if (_error != null) ...[
                  const SizedBox(height: 12),
                  Text(_error!,
                      key: const Key('customer-vat-error'),
                      style: TextStyle(color: cs.error)),
                ],
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('customer-vat-save'),
          onPressed: _busy ? null : _save,
          child: const Text('Save'),
        ),
      ],
    );
  }
}
