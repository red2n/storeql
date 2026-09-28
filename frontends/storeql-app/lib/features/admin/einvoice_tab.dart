import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/theme.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'einvoice_providers.dart';
import 'einvoice_transport_dialog.dart';
import 'procurement_providers.dart';
import 'providers/admin_providers.dart'
    show TenantInfo, tenantInfoProvider, VariantLabel, variantLabelsProvider, variantIdsKey, variantDisplayName;
import '../../shared/util/short_ref.dart';

// ── E-invoices received (07.13) ──────────────────────────────────────────────
//
// A supplier's e-invoice arrives as a file — UBL, CII, or a Factur-X PDF — and
// is captured on its own when its supplier, order and lines are found. This tab
// is for the rest: each waiting document says in a sentence what is missing, and
// the person who knows picks it, once, with the choice remembered for next time.

void _tell(ScaffoldMessengerState messenger, String message) => messenger
  ..hideCurrentSnackBar()
  ..showSnackBar(SnackBar(content: Text(message)));

/// Whoever may refuse an e-invoice; the server refuses anyone else with 403.
bool _canDecide(AuthState? auth) =>
    auth is AuthAuthenticated &&
    auth.isManager &&
    auth.hasPermission('purchasing.invoices.decide');

/// Picks an e-invoice, sends it, and opens it when a person has something to
/// decide.
Future<void> uploadEInvoiceFlow(BuildContext context, WidgetRef ref) async {
  final messenger = ScaffoldMessenger.of(context);
  PickedDocument? doc;
  try {
    doc = await ref.read(eInvoicePickerProvider)();
  } catch (e) {
    _tell(messenger, friendlyError(e, fallback: 'Could not open the file.'));
    return;
  }
  if (doc == null) return;
  // Checked here as well as by the server: a 40 MB file is not worth sending
  // only to be told it is too large.
  if (doc.bytes.isEmpty) {
    _tell(messenger, '${doc.name} is empty.');
    return;
  }
  if (doc.bytes.length > maxEInvoiceBytes) {
    _tell(messenger, '${doc.name} is larger than an e-invoice may be (20 MB).');
    return;
  }
  try {
    final inv = await uploadEInvoice(ref.read(apiClientProvider).dio, doc);
    ref.invalidate(supplierEInvoicesProvider);
    _tell(
      messenger,
      inv.alreadyReceived
          ? '${doc.name} was received before: ${eInvoiceStatusLabel(inv.status)}.'
          : '${inv.title}: ${eInvoiceStatusLabel(inv.status)}.',
    );
    if (inv.open && context.mounted) {
      await showDialog<void>(
        context: context,
        builder: (_) => EInvoiceDialog(id: inv.id),
      );
    }
  } catch (e) {
    _tell(messenger, friendlyError(e, fallback: 'Could not send ${doc.name}.'));
  }
}

class EInvoicesTab extends ConsumerWidget {
  const EInvoicesTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(supplierEInvoicesProvider);
    final cs = Theme.of(context).colorScheme;
    return Column(
      children: [
        const _ReceivingAddress(),
        Expanded(
          child: async.when(
            loading: () => const LoadingView(label: 'Loading e-invoices…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load e-invoices.'),
              onRetry: () => ref.invalidate(supplierEInvoicesProvider),
            ),
            data: (list) {
              if (list.isEmpty) {
                return Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        Icons.mark_email_unread_outlined,
                        size: 64,
                        color: cs.outlineVariant,
                      ),
                      const SizedBox(height: 12),
                      const Text('No e-invoices received yet'),
                      const SizedBox(height: 4),
                      Text(
                        'Upload a supplier\'s UBL, CII or Factur-X invoice to '
                        'capture it against its order',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: cs.outline, fontSize: 12),
                      ),
                    ],
                  ),
                );
              }
              // What waits comes first, newest first within each group: the
              // captured ones need nobody.
              final sorted = [
                ...list.where((e) => e.open),
                ...list.where((e) => !e.open),
              ];
              return ListView.separated(
                padding: EdgeInsetsDirectional.fromSTEB(context.pageGutter, AppSpacing.sm,
                    context.pageGutter, AppSpacing.fabClearance),
                itemCount: sorted.length,
                separatorBuilder: (_, _) => const SizedBox(height: 8),
                itemBuilder: (_, i) => _EInvoiceCard(sorted[i]),
              );
            },
          ),
        ),
      ],
    );
  }
}

/// Where suppliers send, and the VAT number an e-invoice has to name: a
/// document addressed to another business is set aside rather than captured.
class _ReceivingAddress extends ConsumerWidget {
  const _ReceivingAddress();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenant = ref.watch(tenantInfoProvider).value;
    if (tenant == null) return const SizedBox(height: 8);
    final cs = Theme.of(context).colorScheme;
    final canEdit = switch (ref.watch(authNotifierProvider).value) {
      final AuthAuthenticated a => a.isManager,
      _ => false,
    };
    final vat = tenant.vatNumber ?? '';
    return Padding(
      padding: EdgeInsetsDirectional.fromSTEB(
          context.pageGutter, AppSpacing.md, context.pageGutter, 0),
      child: Card(
        margin: EdgeInsets.zero,
        child: Column(children: [
          ListTile(
          leading: Icon(Icons.hub_outlined, color: cs.primary),
          title: Text(
            tenant.hasElectronicAddress
                ? 'Suppliers send e-invoices to '
                    '${tenant.einvoiceScheme}:${tenant.einvoiceId}'
                : 'No e-invoicing address yet',
          ),
          subtitle: Text(
            tenant.hasElectronicAddress || vat.isNotEmpty
                ? [
                    vat.isEmpty ? 'no VAT number' : 'VAT $vat',
                    'an e-invoice addressed to another business is set aside',
                  ].join(' · ')
                : 'Add the VAT number and address suppliers send to, so an '
                    'e-invoice meant for another business is set aside',
          ),
          trailing: canEdit
              ? IconButton(
                  key: const Key('einvoice-identity-edit'),
                  tooltip: 'Edit how e-invoices name the business',
                  icon: const Icon(Icons.edit_outlined),
                  onPressed: () => showDialog<void>(
                    context: context,
                    builder: (_) => BusinessIdentityDialog(tenant: tenant),
                  ),
                )
              : null,
        ),
          const Divider(height: 1),
          // Where the business's own invoices leave (the transport seam).
          TransportSettingsTile(canEdit: canEdit),
        ]),
      ),
    );
  }
}

/// A Peppol electronic address: a scheme and the identifier within it, each
/// required once the other is given.
class ElectronicAddressFields extends StatelessWidget {
  final TextEditingController scheme;
  final TextEditingController id;
  final String keyPrefix;
  final String label;
  final String helperText;

  const ElectronicAddressFields({
    super.key,
    required this.scheme,
    required this.id,
    required this.keyPrefix,
    required this.label,
    required this.helperText,
  });

  @override
  Widget build(BuildContext context) => Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 110,
            child: TextFormField(
              key: Key('$keyPrefix-einvoice-scheme'),
              controller: scheme,
              decoration: const InputDecoration(
                labelText: 'Scheme',
                hintText: '0088',
              ),
              validator: (v) =>
                  validEinvoiceScheme(v, idKeyed: id.text.trim().isNotEmpty),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: TextFormField(
              key: Key('$keyPrefix-einvoice-id'),
              controller: id,
              decoration: InputDecoration(
                labelText: label,
                helperText: helperText,
                helperMaxLines: 2,
                prefixIcon: const Icon(Icons.hub_outlined),
              ),
              validator: (v) => validEinvoiceId(v,
                  schemeKeyed: scheme.text.trim().isNotEmpty),
            ),
          ),
        ],
      );
}

/// The business's VAT number and e-invoicing address, as its e-invoices name
/// it. Empty removes one.
class BusinessIdentityDialog extends ConsumerStatefulWidget {
  final TenantInfo tenant;
  const BusinessIdentityDialog({super.key, required this.tenant});

  @override
  ConsumerState<BusinessIdentityDialog> createState() =>
      _BusinessIdentityDialogState();
}

class _BusinessIdentityDialogState
    extends ConsumerState<BusinessIdentityDialog> {
  final _form = GlobalKey<FormState>();
  late final _vat = TextEditingController(text: widget.tenant.vatNumber ?? '');
  late final _scheme = TextEditingController(
    text: widget.tenant.einvoiceScheme ?? '',
  );
  late final _id = TextEditingController(text: widget.tenant.einvoiceId ?? '');
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _vat.dispose();
    _scheme.dispose();
    _id.dispose();
    super.dispose();
  }

  static String? _validVat(String? v) {
    final t = (v ?? '').replaceAll(RegExp(r'[\s.\-]'), '');
    if (t.isEmpty) return null;
    return RegExp(r'^[A-Za-z]{2}[A-Za-z0-9+*]{2,15}$').hasMatch(t)
        ? null
        : 'Country prefix and number, e.g. GB123456789';
  }

  Future<void> _save() async {
    if (_busy || !(_form.currentState?.validate() ?? false)) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.put(
        '/${ApiConstants.tenant}/admin/tenant',
        data: {
          // The name travels unchanged: this dialog only edits identity.
          'businessName': widget.tenant.name,
          if (widget.tenant.legalName != null)
            'legalName': widget.tenant.legalName,
          'vatNumber': _vat.text.trim(),
          'einvoiceScheme': _scheme.text.trim(),
          'einvoiceId': _id.text.trim(),
        },
      );
      if (!mounted) return;
      ref.invalidate(tenantInfoProvider);
      Navigator.of(context).pop();
    } catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = friendlyError(e, fallback: 'Could not save.');
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('How e-invoices name the business'),
      content: SizedBox(
        width: 480,
        child: Form(
          key: _form,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'An e-invoice has to name this business as its buyer. One '
                'naming another VAT number or address is kept aside, not '
                'captured.',
              ),
              const SizedBox(height: 16),
              TextFormField(
                key: const Key('identity-vat'),
                controller: _vat,
                decoration: const InputDecoration(labelText: 'VAT number'),
                validator: _validVat,
              ),
              const SizedBox(height: 12),
              ElectronicAddressFields(
                scheme: _scheme,
                id: _id,
                keyPrefix: 'identity',
                label: 'E-invoicing address',
                helperText: 'Where suppliers send e-invoices (Peppol)',
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text(
                    _error!,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('identity-save'),
          onPressed: _busy ? null : _save,
          child: const Text('Save'),
        ),
      ],
    );
  }
}

(IconData, Color) _statusLook(BuildContext context, String status) {
  final cs = Theme.of(context).colorScheme;
  return switch (status) {
    'CAPTURED' || 'CREDITED' => (
        Icons.check_circle_outline,
        context.status.success,
      ),
    'REFUSED' || 'DUPLICATE' => (Icons.block_outlined, cs.outline),
    'NOT_COMPLIANT' || 'MISDIRECTED' => (Icons.report_outlined, cs.error),
    _ => (Icons.pending_actions_outlined, context.status.warning),
  };
}

class _StatusChip extends StatelessWidget {
  final String status;
  const _StatusChip(this.status);

  @override
  Widget build(BuildContext context) {
    final color = _statusLook(context, status).$2;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withAlpha(30),
        borderRadius: AppRadius.badge,
      ),
      child: Text(
        eInvoiceStatusLabel(status),
        style: TextStyle(
          color: color,
          fontSize: 11,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _EInvoiceCard extends StatelessWidget {
  final SupplierEInvoice inv;
  const _EInvoiceCard(this.inv);

  @override
  Widget build(BuildContext context) {
    final look = _statusLook(context, inv.status);
    final facts = [
      inv.sellerName ?? 'Unnamed seller',
      if (inv.payableAmount != null)
        AppFormat.money(inv.payableAmount!, currencyCode: inv.currency),
      if (inv.issueDate != null) AppFormat.date(inv.issueDate),
      inv.container == 'PDF' ? '${inv.syntax} in a PDF' : inv.syntax,
      if (inv.arrivedBy != null) inv.arrivedBy!,
    ].join(' · ');
    return Card(
      child: ListTile(
        key: Key('einvoice-${inv.id}'),
        leading: Icon(look.$1, color: look.$2),
        title: Row(
          children: [
            Flexible(
              child: Text(
                inv.title,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
            ),
            const SizedBox(width: 8),
            _StatusChip(inv.status),
          ],
        ),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(facts),
            if (inv.open && inv.problem != null)
              Text(
                inv.problem!,
                style: TextStyle(color: context.status.warning),
              ),
          ],
        ),
        trailing: const Icon(Icons.chevron_right),
        onTap: () => showDialog<void>(
          context: context,
          builder: (_) => EInvoiceDialog(id: inv.id),
        ),
      ),
    );
  }
}

String _qty(double q) =>
    q == q.roundToDouble() ? q.toInt().toString() : q.toString();


/// A name to save the original under: the supplier wrote the invoice number,
/// so nothing in it may reach the file system as a path.
String eInvoiceFileName(SupplierEInvoice inv) {
  final stem = (inv.invoiceNumber ?? '')
      .replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_')
      .replaceAll(RegExp(r'^\.+'), '');
  final safe = stem.isEmpty
      ? 'e-invoice'
      : stem.substring(0, stem.length > 80 ? 80 : stem.length);
  return '$safe.${inv.container == 'PDF' ? 'pdf' : 'xml'}';
}

/// One received e-invoice: what it says, what it broke, and — while it waits —
/// the choices that let it be captured.
class EInvoiceDialog extends ConsumerStatefulWidget {
  final String id;
  const EInvoiceDialog({super.key, required this.id});

  @override
  ConsumerState<EInvoiceDialog> createState() => _EInvoiceDialogState();
}

class _EInvoiceDialogState extends ConsumerState<EInvoiceDialog> {
  final _reason = TextEditingController();
  final Map<int, String> _lines = {};
  String? _supplierId;
  String? _poId;
  String? _returnId;
  bool _remember = true;
  bool _seeded = false;
  bool _busy = false;
  bool _refusing = false;
  String? _error;

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  /// The choices start as what was found on arrival.
  void _seed(SupplierEInvoice inv) {
    if (_seeded) return;
    _seeded = true;
    _supplierId = inv.supplierId;
    _poId = inv.poId;
    _returnId = null;
    _lines
      ..clear()
      ..addEntries([
        for (final l in inv.lines)
          if (l.poLineId != null) MapEntry(l.position, l.poLineId!),
      ]);
  }

  void _failed(Object e, String fallback) {
    if (!mounted) return;
    setState(() {
      _busy = false;
      _error = friendlyError(e, fallback: fallback);
    });
  }

  /// A settled document closes the dialog; one still waiting shows its new
  /// reason.
  void _after(SupplierEInvoice after) {
    if (!mounted) return;
    ref.invalidate(supplierEInvoicesProvider);
    ref.invalidate(supplierInvoicesProvider);
    if (after.open) {
      _seeded = false;
      ref.invalidate(supplierEInvoiceProvider(widget.id));
      setState(() {
        _busy = false;
        _refusing = false;
      });
      return;
    }
    _tell(
      ScaffoldMessenger.of(context),
      '${after.title}: ${eInvoiceStatusLabel(after.status)}.',
    );
    Navigator.of(context).pop();
  }

  Future<void> _match(SupplierEInvoice inv) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    final found = {
      for (final l in inv.lines)
        if (l.poLineId != null) l.position: l.poLineId!,
    };
    try {
      _after(
        await matchEInvoice(
          ref.read(apiClientProvider).dio,
          inv.id,
          // Only what the person changed: the rest is found again as on arrival.
          supplierId: _supplierId == inv.supplierId ? null : _supplierId,
          poId: _poId == inv.poId ? null : _poId,
          returnId: _returnId,
          lines: {
            for (final e in _lines.entries)
              if (found[e.key] != e.value) e.key: e.value,
          },
          remember: _remember,
        ),
      );
    } catch (e) {
      _failed(e, 'Could not match the e-invoice.');
    }
  }

  Future<void> _refuse(SupplierEInvoice inv) async {
    if (_busy) return;
    final reason = _reason.text.trim();
    if (reason.isEmpty) {
      setState(() => _error = 'Say why it is refused.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      _after(
        await refuseEInvoice(ref.read(apiClientProvider).dio, inv.id, reason),
      );
    } catch (e) {
      _failed(e, 'Could not refuse the e-invoice.');
    }
  }

  Future<void> _download(SupplierEInvoice inv) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final bytes = await originalEInvoice(
        ref.read(apiClientProvider).dio,
        inv.id,
      );
      await ref.read(eInvoiceSaverProvider)(eInvoiceFileName(inv), bytes);
      if (mounted) setState(() => _busy = false);
    } catch (e) {
      _failed(e, 'Could not download the original.');
    }
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(supplierEInvoiceProvider(widget.id));
    final inv = async.value;
    final canDecide = _canDecide(ref.watch(authNotifierProvider).value);
    return AlertDialog(
      title: Text(inv?.title ?? 'E-invoice'),
      content: SizedBox(
        width: 640,
        child: async.when(
          loading: () => const SizedBox(
            height: 160,
            child: LoadingView(label: 'Loading the e-invoice…'),
          ),
          error: (e, _) => Text(
            friendlyError(e, fallback: 'Could not load the e-invoice.'),
          ),
          data: (inv) {
            _seed(inv);
            return SingleChildScrollView(child: _body(inv));
          },
        ),
      ),
      actions: [
        if (inv != null)
          TextButton.icon(
            key: const Key('einvoice-download'),
            onPressed: _busy ? null : () => _download(inv),
            icon: const Icon(Icons.download_outlined),
            label: const Text('Original'),
          ),
        if (inv != null && inv.open && canDecide)
          _refusing
              ? FilledButton.tonal(
                  key: const Key('einvoice-refuse-confirm'),
                  onPressed: _busy ? null : () => _refuse(inv),
                  child: const Text('Refuse e-invoice'),
                )
              : TextButton(
                  key: const Key('einvoice-refuse'),
                  onPressed: _busy
                      ? null
                      : () => setState(() {
                            _refusing = true;
                            _error = null;
                          }),
                  child: const Text('Refuse'),
                ),
        if (inv != null && inv.matchable && !_refusing)
          FilledButton(
            key: const Key('einvoice-match'),
            onPressed: _busy ? null : () => _match(inv),
            child: Text(inv.creditNote ? 'Match to the return' : 'Match'),
          ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Close'),
        ),
      ],
    );
  }

  Widget _body(SupplierEInvoice inv) {
    final cs = Theme.of(context).colorScheme;
    final outline = TextStyle(color: cs.outline);
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 4,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            _StatusChip(inv.status),
            Text(
              [
                inv.container == 'PDF' ? '${inv.syntax} in a PDF' : inv.syntax,
                if (inv.issueDate != null) 'issued ${AppFormat.date(inv.issueDate)}',
                if (inv.arrivedBy != null) inv.arrivedBy!,
                if (inv.deliveryRef != null) 'ref ${inv.deliveryRef}',
              ].join(' · '),
              style: outline,
            ),
          ],
        ),
        if (inv.open && inv.problem != null)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(
              inv.problem!,
              key: const Key('einvoice-problem'),
              style: TextStyle(
                color: context.status.warning,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        const SizedBox(height: 12),
        _fact(
          'From',
          [
            inv.sellerName ?? 'Unnamed seller',
            if (inv.sellerVatId != null) 'VAT ${inv.sellerVatId}',
            if (inv.sellerEndpoint != null) inv.sellerEndpoint!,
          ].join(' · '),
        ),
        _fact('Order reference', inv.orderReference ?? 'none given'),
        if (inv.payableAmount != null)
          _fact(
            'To pay',
            AppFormat.money(inv.payableAmount!, currencyCode: inv.currency),
          ),
        if (inv.decisionReason != null)
          _fact('Refused because', inv.decisionReason!),
        if (_settledNote(inv) case final note?)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(note),
          ),
        if (inv.violations.isNotEmpty) ...[
          const SizedBox(height: 12),
          Text('Rules it breaks',
              style: Theme.of(context).textTheme.titleSmall),
          for (final v in inv.violations)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(
                    v.fatal ? Icons.error_outline : Icons.info_outline,
                    size: 16,
                    color: v.fatal ? cs.error : context.status.warning,
                  ),
                  const SizedBox(width: 8),
                  Expanded(child: Text('${v.rule}: ${v.message}')),
                ],
              ),
            ),
        ],
        if (inv.matchable) _choices(inv),
        if (!inv.matchable && inv.lines.isNotEmpty) ...[
          const SizedBox(height: 12),
          for (final l in inv.lines)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                [
                  _lineText(l, inv.currency),
                  if (l.matched) 'matched ${matchedByLabel(l.matchedBy)}',
                ].join(' — '),
              ),
            ),
        ],
        if (_refusing)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: TextField(
              key: const Key('einvoice-refuse-reason'),
              controller: _reason,
              maxLength: 500,
              decoration: const InputDecoration(
                labelText: 'Why it is refused',
                helperText: 'Kept with the document, for the supplier',
              ),
            ),
          ),
        if (_error != null)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(_error!, style: TextStyle(color: cs.error)),
          ),
      ],
    );
  }

  Widget _fact(String label, String value) => Padding(
        padding: const EdgeInsets.only(top: 4),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 130,
              child: Text(
                label,
                style: TextStyle(color: Theme.of(context).colorScheme.outline),
              ),
            ),
            Expanded(child: Text(value)),
          ],
        ),
      );

  static String? _settledNote(SupplierEInvoice inv) => switch (inv.status) {
        'CAPTURED' => 'Captured as a supplier invoice: it is under Invoices.',
        'CREDITED' => 'Its credit note closed the return it credits.',
        'DUPLICATE' =>
          'This supplier\'s invoice with this number is already captured.',
        _ => null,
      };

  static String _lineText(EInvoiceLine l, String? currency) => [
        '${l.position}. ${l.itemName ?? 'Unnamed item'}',
        if (l.sellersItemId != null) '(${l.sellersItemId})',
        if (l.quantity != null) '× ${_qty(l.quantity!)}',
        if (l.netAmount != null)
          AppFormat.money(l.netAmount!, currencyCode: currency),
      ].join(' ');

  Widget _choices(SupplierEInvoice inv) {
    final suppliers = ref.watch(suppliersProvider).value ?? const <Supplier>[];
    final orders = [
      for (final o in ref.watch(purchaseOrdersProvider).value ?? const [])
        if (_supplierId == null || o.supplierId == _supplierId) o,
    ];
    final poId = _poId;
    final poLines = poId == null || inv.creditNote
        ? const <PurchaseOrderLine>[]
        : ref.watch(purchaseOrderLinesProvider(poId)).value ?? const [];
    // The order's lines by their product's name; the end of an id only while
    // the names load.
    final lineNames = ref
            .watch(variantLabelsProvider(variantIdsKey(poLines.map((p) => p.variantId))))
            .value ??
        const <String, VariantLabel>{};
    final returns = poId == null || !inv.creditNote
        ? const <VendorReturn>[]
        : [
            for (final r
                in ref.watch(vendorReturnsProvider(poId)).value ?? const [])
              if (!r.credited) r,
          ];
    // A form field keeps the value it started with; each one is rebuilt when
    // what it depends on changes, or when its choices arrive.
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 16),
        KeyedSubtree(
          key: ValueKey('s${suppliers.length}'),
          child: DropdownButtonFormField<String>(
            key: const Key('einvoice-supplier'),
            initialValue:
                suppliers.any((s) => s.id == _supplierId) ? _supplierId : null,
            isExpanded: true,
            decoration: const InputDecoration(labelText: 'Supplier'),
            items: [
              for (final s in suppliers)
                DropdownMenuItem(value: s.id, child: Text(s.name)),
            ],
            onChanged: _busy
                ? null
                : (v) => setState(() {
                      _supplierId = v;
                      _poId = null;
                      _returnId = null;
                      _lines.clear();
                    }),
          ),
        ),
        const SizedBox(height: 12),
        KeyedSubtree(
          key: ValueKey('o$_supplierId${orders.length}'),
          child: DropdownButtonFormField<String>(
            key: const Key('einvoice-order'),
            initialValue: orders.any((o) => o.id == _poId) ? _poId : null,
            isExpanded: true,
            decoration: const InputDecoration(labelText: 'Purchase order'),
            items: [
              for (final o in orders)
                DropdownMenuItem(
                  value: o.id,
                  child: Text(
                    'PO …${shortRef(o.id)} · ${purchaseOrderStatus(o.status).$1} · '
                    '${AppFormat.money(o.totalGross, currencyCode: o.currency)}',
                  ),
                ),
            ],
            onChanged: _busy
                ? null
                : (v) => setState(() {
                      _poId = v;
                      _returnId = null;
                      _lines.clear();
                    }),
          ),
        ),
        if (inv.creditNote) ...[
          const SizedBox(height: 12),
          KeyedSubtree(
            key: ValueKey('r$_poId${returns.length}'),
            child: DropdownButtonFormField<String>(
              key: const Key('einvoice-return'),
              initialValue:
                  returns.any((r) => r.id == _returnId) ? _returnId : null,
              isExpanded: true,
              decoration: const InputDecoration(
                labelText: 'The return it credits',
              ),
              items: [
                for (final r in returns)
                  DropdownMenuItem(
                    value: r.id,
                    child: Text(
                      '${r.debitNoteNumber} · '
                      '${AppFormat.money(r.grossAmount, currencyCode: r.currency)}',
                    ),
                  ),
              ],
              onChanged: _busy ? null : (v) => setState(() => _returnId = v),
            ),
          ),
        ],
        const SizedBox(height: 12),
        for (final l in inv.lines)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(_lineText(l, inv.currency)),
                if (!inv.creditNote)
                  KeyedSubtree(
                    key: ValueKey('l$_poId${poLines.length}'),
                    child: DropdownButtonFormField<String>(
                      key: Key('einvoice-line-${l.position}'),
                      initialValue:
                          poLines.any((p) => p.id == _lines[l.position])
                              ? _lines[l.position]
                              : null,
                      isExpanded: true,
                      decoration: InputDecoration(
                        labelText: 'Order line',
                        helperText:
                            l.matched && _lines[l.position] == l.poLineId
                                ? 'Matched ${matchedByLabel(l.matchedBy)}'
                                : null,
                      ),
                      items: [
                        for (final (i, p) in poLines.indexed)
                          DropdownMenuItem(
                            value: p.id,
                            child: Text(
                              '${i + 1}. ${variantDisplayName(p.variantId, lineNames)} · '
                              '${_qty(p.qty)} × '
                              '${AppFormat.money(p.unitPrice, currencyCode: inv.currency)}',
                            ),
                          ),
                      ],
                      onChanged: _busy
                          ? null
                          : (v) => setState(() {
                                if (v != null) _lines[l.position] = v;
                              }),
                    ),
                  ),
              ],
            ),
          ),
        CheckboxListTile(
          key: const Key('einvoice-remember'),
          contentPadding: EdgeInsets.zero,
          value: _remember,
          onChanged:
              _busy ? null : (v) => setState(() => _remember = v ?? false),
          title: const Text('Remember for this supplier\'s next e-invoice'),
          subtitle: const Text(
            'Its address, and which of its item codes are which order line',
          ),
        ),
      ],
    );
  }
}
