import 'package:dio/dio.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/status_badge.dart';
import 'providers/admin_providers.dart';

// The legal receipt register: the series a store runs, the documents in it in
// order, and the inspector's question — is the sequence unbroken? — answered by
// the server from the table rather than by anyone's assertion. A manager sets
// what a series prints in front of its numbers; nothing here can renumber,
// delete or edit a receipt.

class ReceiptSeries {
  final String storeId;
  final String seriesCode;
  final String period;
  final int nextNumber;
  final String? prefix;
  const ReceiptSeries({
    required this.storeId,
    required this.seriesCode,
    required this.period,
    required this.nextNumber,
    this.prefix,
  });
  factory ReceiptSeries.fromJson(Map<String, dynamic> j) => ReceiptSeries(
    storeId: j['storeId'] as String? ?? '',
    seriesCode: j['seriesCode'] as String? ?? 'MAIN',
    period: j['period'] as String? ?? '',
    nextNumber: (j['nextNumber'] as num?)?.toInt() ?? 1,
    prefix: j['prefix'] as String?,
  );
}

class ReceiptAudit {
  final int firstNumber;
  final int lastNumber;
  final int issued;
  final int expected;
  final bool intact;
  final List<(int, int)> gaps;

  /// The tamper-evidence chain (18.4): each document carries a hash of its
  /// own figures and of the document before it. Null when the server predates
  /// the chain; [chainFrom] is the first number in the chain, [chainBrokenAt]
  /// the first number whose stored figures no longer match their hash.
  final bool? chainIntact;
  final int? chainFrom;
  final int? chainBrokenAt;
  const ReceiptAudit({
    required this.firstNumber,
    required this.lastNumber,
    required this.issued,
    required this.expected,
    required this.intact,
    required this.gaps,
    this.chainIntact,
    this.chainFrom,
    this.chainBrokenAt,
  });
  factory ReceiptAudit.fromJson(Map<String, dynamic> j) => ReceiptAudit(
    firstNumber: (j['firstNumber'] as num?)?.toInt() ?? 0,
    lastNumber: (j['lastNumber'] as num?)?.toInt() ?? 0,
    issued: (j['issued'] as num?)?.toInt() ?? 0,
    expected: (j['expected'] as num?)?.toInt() ?? 0,
    intact: j['intact'] as bool? ?? false,
    gaps: [
      for (final g in (j['gaps'] as List?) ?? const [])
        ((g['from'] as num).toInt(), (g['to'] as num).toInt()),
    ],
    chainIntact: j['chainIntact'] as bool?,
    chainFrom: (j['chainFrom'] as num?)?.toInt(),
    chainBrokenAt: (j['chainBrokenAt'] as num?)?.toInt(),
  );
}

class ReceiptRow {
  final String fullNumber;
  final int number;
  final String orderId;
  final String issuedAt;
  final double grossTotal;
  final String currency;
  final bool voided;

  /// The regime the store was under when the document was issued (18.5).
  final String regime;

  /// The German module's signature counter, or null; [tseError] when it failed.
  final int? tseSignatureCounter;
  final String? tseError;

  /// The four characters of the Portuguese signature a receipt prints, or null.
  final String? ptExcerpt;

  const ReceiptRow({
    required this.fullNumber,
    required this.number,
    required this.orderId,
    required this.issuedAt,
    required this.grossTotal,
    required this.currency,
    required this.voided,
    this.regime = 'NONE',
    this.tseSignatureCounter,
    this.tseError,
    this.ptExcerpt,
  });
  factory ReceiptRow.fromJson(Map<String, dynamic> j) {
    final tse = j['tse'] as Map<String, dynamic>?;
    final pt = j['pt'] as Map<String, dynamic>?;
    return ReceiptRow(
      fullNumber: j['fullNumber'] as String? ?? '',
      number: (j['number'] as num?)?.toInt() ?? 0,
      orderId: j['orderId'] as String? ?? '',
      issuedAt: j['issuedAt'] as String? ?? '',
      grossTotal: (j['grossTotal'] as num?)?.toDouble() ?? 0,
      currency: j['currency'] as String? ?? '',
      voided: j['voidedAt'] != null,
      regime: j['regime'] as String? ?? 'NONE',
      tseSignatureCounter: (tse?['signatureCounter'] as num?)?.toInt(),
      tseError: tse?['error'] as String?,
      ptExcerpt: pt?['printedExcerpt'] as String?,
    );
  }

  /// What the row shows beside the amount: the stamp, in the regime's own terms.
  String? get stampLabel {
    if (tseError != null) return 'TSE ausgefallen';
    if (tseSignatureCounter != null) return 'TSE #$tseSignatureCounter';
    if (ptExcerpt != null) return 'AT $ptExcerpt';
    return null;
  }
}

/// The fiscal regime a store trades under (18.5), and what the deployment
/// offers beside it. NONE is the register alone.
class FiscalSettings {
  final String storeId;
  final String regime;
  final String? taxRegistrationNumber;
  final String? certificateNumber;
  final String? seriesValidationCode;
  final String? tseProvider;
  final String? tseSerial;
  final String? tseClientId;
  final int? tseSignatureCounter;
  final List<String> regimes;
  final List<String> tseProviders;
  final bool ptKeyConfigured;

  const FiscalSettings({
    required this.storeId,
    required this.regime,
    this.taxRegistrationNumber,
    this.certificateNumber,
    this.seriesValidationCode,
    this.tseProvider,
    this.tseSerial,
    this.tseClientId,
    this.tseSignatureCounter,
    this.regimes = const ['NONE'],
    this.tseProviders = const [],
    this.ptKeyConfigured = false,
  });

  factory FiscalSettings.fromJson(Map<String, dynamic> j) {
    final tse = j['tse'] as Map<String, dynamic>?;
    return FiscalSettings(
      storeId: j['storeId'] as String? ?? '',
      regime: j['regime'] as String? ?? 'NONE',
      taxRegistrationNumber: j['taxRegistrationNumber'] as String?,
      certificateNumber: j['certificateNumber'] as String?,
      seriesValidationCode: j['seriesValidationCode'] as String?,
      tseProvider: tse?['provider'] as String?,
      tseSerial: tse?['serialNumber'] as String?,
      tseClientId: tse?['clientId'] as String?,
      tseSignatureCounter: (tse?['signatureCounter'] as num?)?.toInt(),
      regimes: [
        for (final r in (j['regimes'] as List?) ?? const ['NONE']) r as String,
      ],
      tseProviders: [
        for (final p in (j['tseProviders'] as List?) ?? const []) p as String,
      ],
      ptKeyConfigured: j['ptKeyConfigured'] as bool? ?? false,
    );
  }

  static String regimeLabel(String regime) => switch (regime) {
    'DE_KASSENSICHV' => 'Germany — KassenSichV (TSE, DSFinV-K)',
    'PT_SAFT' => 'Portugal — certified software (SAF-T)',
    _ => 'None — the register alone',
  };
}

final fiscalSettingsProvider = FutureProvider.autoDispose
    .family<FiscalSettings, String>((ref, storeId) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get(
            '/${ApiConstants.order}/admin/fiscal-receipts/settings',
            queryParameters: {'storeId': storeId},
          );
      return FiscalSettings.fromJson(resp.data['data'] as Map<String, dynamic>);
    });

class ReceiptFilter {
  final String? storeId;
  final String series;
  final String period;
  const ReceiptFilter({
    this.storeId,
    this.series = 'MAIN',
    required this.period,
  });
  ReceiptFilter copyWith({String? storeId, String? series, String? period}) =>
      ReceiptFilter(
        storeId: storeId ?? this.storeId,
        series: series ?? this.series,
        period: period ?? this.period,
      );
}

final receiptFilterProvider = StateProvider<ReceiptFilter>(
  (ref) => ReceiptFilter(period: DateTime.now().year.toString()),
);

final receiptSeriesProvider = FutureProvider.autoDispose
    .family<List<ReceiptSeries>, String>((ref, storeId) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get(
            '/${ApiConstants.order}/admin/fiscal-receipts/series',
            queryParameters: {'storeId': storeId},
          );
      return ((resp.data['data'] as List?) ?? const [])
          .map((e) => ReceiptSeries.fromJson(e as Map<String, dynamic>))
          .toList();
    });

final receiptAuditProvider = FutureProvider.autoDispose<ReceiptAudit?>((
  ref,
) async {
  final f = ref.watch(receiptFilterProvider);
  if (f.storeId == null) return null;
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get(
        '/${ApiConstants.order}/admin/fiscal-receipts/audit',
        queryParameters: {
          'storeId': f.storeId,
          'series': f.series,
          'period': f.period,
        },
      );
  return ReceiptAudit.fromJson(resp.data['data'] as Map<String, dynamic>);
});

final receiptListProvider = FutureProvider.autoDispose<List<ReceiptRow>>((
  ref,
) async {
  final f = ref.watch(receiptFilterProvider);
  if (f.storeId == null) return const [];
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get(
        '/${ApiConstants.order}/admin/fiscal-receipts',
        queryParameters: {
          'storeId': f.storeId,
          'series': f.series,
          'period': f.period,
          'limit': 200,
        },
      );
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => ReceiptRow.fromJson(e as Map<String, dynamic>))
      .toList();
});

class ReceiptsTab extends ConsumerWidget {
  const ReceiptsTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stores = ref.watch(storesProvider);
    final filter = ref.watch(receiptFilterProvider);
    final auth = ref.watch(authNotifierProvider).value;
    final isManager = auth is AuthAuthenticated && auth.isManager;
    final theme = Theme.of(context);

    // Default to the first store once they load.
    final storeList = stores.value ?? const <StoreInfo>[];
    final storeId =
        filter.storeId ?? (storeList.isNotEmpty ? storeList.first.id : null);
    if (filter.storeId == null && storeId != null) {
      Future.microtask(
        () => ref.read(receiptFilterProvider.notifier).state = filter.copyWith(
          storeId: storeId,
        ),
      );
    }

    // The page gutter (16 on a phone, 24 from tablet up), so the heading
    // lines up under the page title as the other tabs do.
    return ListView(
      padding: context.pagePadding,
      children: [
        Text('Legal receipts', style: theme.textTheme.titleLarge),
        const SizedBox(height: 4),
        Text(
          'Every completed sale is numbered consecutively per store, series and fiscal year. '
          'A voided sale keeps its number; nothing here can renumber or remove one. The audit '
          'answers the inspector\'s question from the table itself.',
          style: theme.textTheme.bodyMedium?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: AppSpacing.lg),
        Wrap(
          spacing: 12,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            SizedBox(
              width: 240,
              child: DropdownButtonFormField<String>(
                key: const Key('receipts-store'),
                initialValue: storeId,
                decoration: const InputDecoration(labelText: 'Store'),
                items: [
                  for (final s in storeList)
                    DropdownMenuItem(value: s.id, child: Text(s.name)),
                ],
                onChanged: (v) =>
                    ref.read(receiptFilterProvider.notifier).state = filter
                        .copyWith(storeId: v),
              ),
            ),
            SizedBox(
              width: 120,
              child: TextFormField(
                key: const Key('receipts-series'),
                initialValue: filter.series,
                decoration: const InputDecoration(labelText: 'Series'),
                onFieldSubmitted: (v) =>
                    ref.read(receiptFilterProvider.notifier).state = filter
                        .copyWith(series: v.trim().toUpperCase()),
              ),
            ),
            SizedBox(
              width: 120,
              child: TextFormField(
                key: const Key('receipts-period'),
                initialValue: filter.period,
                decoration: const InputDecoration(labelText: 'Fiscal year'),
                onFieldSubmitted: (v) =>
                    ref.read(receiptFilterProvider.notifier).state = filter
                        .copyWith(period: v.trim()),
              ),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.lg),
        if (storeId != null) ...[
          _SeriesCard(storeId: storeId, isManager: isManager),
          const SizedBox(height: AppSpacing.lg),
          _RegimeCard(storeId: storeId, isManager: isManager),
          const SizedBox(height: AppSpacing.lg),
          ref
              .watch(receiptAuditProvider)
              .when(
                loading: () => const LoadingView(label: 'Auditing…'),
                error: (e, _) => ErrorView(
                  message: friendlyError(
                    e,
                    fallback: 'Could not audit the series.',
                  ),
                  onRetry: () => ref.invalidate(receiptAuditProvider),
                ),
                data: (a) =>
                    a == null ? const SizedBox.shrink() : _AuditCard(audit: a),
              ),
          const SizedBox(height: AppSpacing.lg),
          Row(
            children: [
              Expanded(
                child: Text(
                  'Receipts, in order',
                  style: theme.textTheme.titleMedium,
                ),
              ),
              TextButton.icon(
                onPressed: () => showDialog<void>(
                  context: context,
                  builder: (_) => ReceiptExportDialog(
                    filter: filter.copyWith(storeId: storeId),
                    regime:
                        ref
                            .read(fiscalSettingsProvider(storeId))
                            .value
                            ?.regime ??
                        'NONE',
                  ),
                ),
                icon: const Icon(Icons.download_outlined, size: 18),
                label: const Text('Export'),
              ),
            ],
          ),
          const SizedBox(height: 8),
          ref
              .watch(receiptListProvider)
              .when(
                loading: () => const LoadingView(label: 'Loading receipts…'),
                error: (e, _) => ErrorView(
                  message: friendlyError(
                    e,
                    fallback: 'Could not load the receipts.',
                  ),
                  onRetry: () => ref.invalidate(receiptListProvider),
                ),
                data: (rows) => rows.isEmpty
                    ? Text(
                        'No receipts in this series yet.',
                        style: TextStyle(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      )
                    : Card(
                        child: Column(
                          children: [
                            for (final r in rows)
                              ListTile(
                                dense: true,
                                leading: Text(
                                  '${r.number}',
                                  style: const TextStyle(
                                    fontFeatures: [
                                      FontFeature.tabularFigures(),
                                    ],
                                  ),
                                ),
                                title: Text(r.fullNumber),
                                // The stamp and the void as badges under the
                                // number, so the trailing slot keeps only the
                                // amount and the number keeps its width.
                                subtitle: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      '${AppFormat.dateTime(r.issuedAt)} · order …${shortRef(r.orderId)}',
                                    ),
                                    if (r.voided || r.stampLabel != null)
                                      Padding(
                                        padding: const EdgeInsetsDirectional.only(
                                          top: AppSpacing.xs,
                                        ),
                                        child: Wrap(
                                          spacing: AppSpacing.sm,
                                          runSpacing: AppSpacing.xs,
                                          children: [
                                            if (r.voided)
                                              const StatusBadge(
                                                'Void',
                                                tone: StatusTone.error,
                                              ),
                                            if (r.stampLabel != null)
                                              StatusBadge(
                                                r.stampLabel!,
                                                tone: r.tseError != null
                                                    ? StatusTone.error
                                                    : StatusTone.neutral,
                                              ),
                                          ],
                                        ),
                                      ),
                                  ],
                                ),
                                trailing: Text(
                                  AppFormat.money(
                                    r.grossTotal,
                                    currencyCode: r.currency,
                                  ),
                                  style: r.voided
                                      ? TextStyle(
                                          color: theme.colorScheme.error,
                                          decoration:
                                              TextDecoration.lineThrough,
                                        )
                                      : null,
                                ),
                              ),
                          ],
                        ),
                      ),
              ),
        ],
      ],
    );
  }
}

class _AuditCard extends StatelessWidget {
  const _AuditCard({required this.audit});
  final ReceiptAudit audit;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      color: audit.intact ? null : cs.errorContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  audit.intact
                      ? Icons.verified_outlined
                      : Icons.report_problem_outlined,
                  color: audit.intact ? cs.primary : cs.error,
                ),
                const SizedBox(width: 8),
                Text(
                  audit.intact ? 'Sequence intact' : 'Sequence has gaps',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              'First ${audit.firstNumber} · last ${audit.lastNumber} · issued ${audit.issued} '
              '· expected ${audit.expected}',
            ),
            if (audit.gaps.isNotEmpty) ...[
              const SizedBox(height: 6),
              for (final g in audit.gaps)
                Text(
                  g.$1 == g.$2 ? 'Missing ${g.$1}' : 'Missing ${g.$1}–${g.$2}',
                ),
              const SizedBox(height: 6),
              const Text(
                'A gap is not necessarily fraud; it is the thing that has to be explained.',
              ),
            ],
            if (audit.chainIntact != null) ...[
              const SizedBox(height: 8),
              Row(
                children: [
                  Icon(
                    audit.chainIntact! ? Icons.link : Icons.link_off,
                    size: 18,
                    color: audit.chainIntact! ? cs.primary : cs.error,
                  ),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      audit.chainIntact!
                          ? 'Hash chain intact${audit.chainFrom != null ? ' from no. ${audit.chainFrom}' : ''}: '
                                'no document has been altered since it was issued.'
                          : 'Hash chain broken at no. ${audit.chainBrokenAt}: a document\'s stored figures '
                                'no longer match the hash written when it was issued.',
                      style: TextStyle(
                        color: audit.chainIntact! ? null : cs.error,
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// The register as a file for the accountant or the inspector (18.4, 18.5): the
/// hash-chained CSV and the Portuguese SAF-T are text, fetched and shown here to
/// copy; the German DSFinV-K is a zip, handed to the browser to save.
class ReceiptExportDialog extends ConsumerStatefulWidget {
  const ReceiptExportDialog({
    super.key,
    required this.filter,
    this.regime = 'NONE',
  });
  final ReceiptFilter filter;
  final String regime;

  @override
  ConsumerState<ReceiptExportDialog> createState() =>
      _ReceiptExportDialogState();
}

class _ReceiptExportDialogState extends ConsumerState<ReceiptExportDialog> {
  late String _format;
  bool _saving = false;
  String? _saveError;

  static const _formats = <String, String>{
    'csv': 'Register (CSV with hash chain)',
    'saft-pt': 'SAF-T (PT) — Portugal',
    'dsfinvk': 'DSFinV-K — Germany (zip)',
  };

  @override
  void initState() {
    super.initState();
    _format = switch (widget.regime) {
      'DE_KASSENSICHV' => 'dsfinvk',
      'PT_SAFT' => 'saft-pt',
      _ => 'csv',
    };
  }

  Future<void> _saveZip() async {
    setState(() {
      _saving = true;
      _saveError = null;
    });
    try {
      final f = widget.filter;
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get<List<int>>(
            '/${ApiConstants.order}/admin/fiscal-receipts/export',
            queryParameters: {
              'storeId': f.storeId,
              'series': f.series,
              'period': f.period,
              'format': 'dsfinvk',
            },
            options: Options(responseType: ResponseType.bytes),
          );
      final bytes = Uint8List.fromList(resp.data ?? const []);
      await FilePicker.saveFile(
        dialogTitle: 'Save DSFinV-K export',
        fileName: 'dsfinvk-${f.series}-${f.period}.zip',
        bytes: bytes,
      );
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('DSFinV-K export saved.')));
      }
    } catch (e) {
      setState(
        () => _saveError = friendlyError(
          e,
          fallback: 'Could not export the file.',
        ),
      );
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final filter = widget.filter;
    final isZip = _format == 'dsfinvk';
    final text = isZip
        ? null
        : ref.watch(receiptExportProvider((filter, _format)));
    return AlertDialog(
      title: const Text('Export register'),
      content: SizedBox(
        width: 640,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            DropdownButtonFormField<String>(
              key: const Key('receipt-export-format'),
              isExpanded: true,
              initialValue: _format,
              decoration: const InputDecoration(labelText: 'Format'),
              items: [
                for (final e in _formats.entries)
                  DropdownMenuItem(value: e.key, child: Text(e.value)),
              ],
              onChanged: (v) => setState(() => _format = v ?? 'csv'),
            ),
            const SizedBox(height: 12),
            if (isZip) ...[
              const Text(
                'The German inspector\'s file: cash-point closings by day, every transaction with '
                'its TSE stamp, lines, VAT and payments, with index.xml. Saved as a zip.',
              ),
              if (_saveError != null) ...[
                const SizedBox(height: 8),
                Text(
                  _saveError!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ],
            ] else
              text!.when(
                loading: () => const LoadingView(label: 'Exporting…'),
                error: (e, _) => ErrorView(
                  message: friendlyError(
                    e,
                    fallback: 'Could not export the register.',
                  ),
                  onRetry: () =>
                      ref.invalidate(receiptExportProvider((filter, _format))),
                ),
                data: (body) => Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      _format == 'csv'
                          ? '${body.split('\n').where((l) => l.trim().isNotEmpty).length - 1} documents, '
                                'one row each, with the hash chain. Copy and save as .csv.'
                          : 'SAF-T (PT) 1.04_01: every document with its signature and ATCUD. '
                                'Copy and save as .xml.',
                    ),
                    const SizedBox(height: 8),
                    ConstrainedBox(
                      constraints: const BoxConstraints(maxHeight: 320),
                      child: SingleChildScrollView(
                        child: SelectableText(
                          body,
                          key: const Key('receipt-export-csv'),
                          style: const TextStyle(
                            fontFamily: 'monospace',
                            fontSize: 12,
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Close'),
        ),
        if (isZip)
          FilledButton.icon(
            key: const Key('receipt-export-save'),
            onPressed: _saving ? null : _saveZip,
            icon: const Icon(Icons.download_outlined, size: 18),
            label: const Text('Save zip'),
          )
        else
          FilledButton.icon(
            onPressed: text!.hasValue
                ? () {
                    Clipboard.setData(ClipboardData(text: text.value!));
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(
                        content: Text(
                          _format == 'csv'
                              ? 'Register copied.'
                              : 'SAF-T file copied.',
                        ),
                      ),
                    );
                  }
                : null,
            icon: const Icon(Icons.copy_outlined, size: 18),
            label: Text(_format == 'csv' ? 'Copy CSV' : 'Copy XML'),
          ),
      ],
    );
  }
}

final receiptExportProvider = FutureProvider.autoDispose
    .family<String, (ReceiptFilter, String)>((ref, arg) async {
      final (f, format) = arg;
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get(
            '/${ApiConstants.order}/admin/fiscal-receipts/export',
            queryParameters: {
              'storeId': f.storeId,
              'series': f.series,
              'period': f.period,
              'format': format,
            },
            options: Options(responseType: ResponseType.plain),
          );
      return resp.data.toString();
    });

/// The fiscal regime the store trades under (18.5): what stamps its receipts
/// and which file its inspector gets. A manager places the store under one;
/// documents already issued keep their stamps.
class _RegimeCard extends ConsumerWidget {
  const _RegimeCard({required this.storeId, required this.isManager});
  final String storeId;
  final bool isManager;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settings = ref.watch(fiscalSettingsProvider(storeId));
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    'Fiscal regime',
                    style: theme.textTheme.titleMedium,
                  ),
                ),
                if (isManager && settings.hasValue)
                  TextButton.icon(
                    key: const Key('regime-change'),
                    onPressed: () => showDialog<void>(
                      context: context,
                      builder: (_) => _RegimeDialog(
                        storeId: storeId,
                        current: settings.value!,
                      ),
                    ),
                    icon: const Icon(Icons.gavel_outlined, size: 18),
                    label: const Text('Change'),
                  ),
              ],
            ),
            settings.when(
              loading: () => const LoadingView(label: 'Loading regime…'),
              error: (e, _) => ErrorView(
                message: friendlyError(
                  e,
                  fallback: 'Could not load the fiscal regime.',
                ),
                onRetry: () => ref.invalidate(fiscalSettingsProvider(storeId)),
              ),
              data: (s) => Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    FiscalSettings.regimeLabel(s.regime),
                    key: const Key('regime-label'),
                  ),
                  if (s.taxRegistrationNumber != null)
                    Text(
                      'Tax number ${s.taxRegistrationNumber}',
                      style: theme.textTheme.bodySmall,
                    ),
                  if (s.regime == 'DE_KASSENSICHV')
                    Text(
                      s.tseSerial == null
                          ? 'No security module registered — every sale will record the outage.'
                          : 'Security module ${s.tseProvider} · serial ${s.tseSerial!.substring(0, s.tseSerial!.length.clamp(0, 16))}… · '
                                'client ${s.tseClientId} · signatures so far ${s.tseSignatureCounter ?? 0}',
                      style: theme.textTheme.bodySmall,
                    ),
                  if (s.regime == 'PT_SAFT')
                    Text(
                      'Certificate ${s.certificateNumber ?? '— (not yet certified)'} · '
                      'series validation code ${s.seriesValidationCode ?? '0 (not yet registered with the AT)'}',
                      style: theme.textTheme.bodySmall,
                    ),
                  if (s.regime == 'NONE')
                    Text(
                      'Receipts are numbered and hash-chained; nothing else is stamped on them.',
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _RegimeDialog extends ConsumerStatefulWidget {
  const _RegimeDialog({required this.storeId, required this.current});
  final String storeId;
  final FiscalSettings current;
  @override
  ConsumerState<_RegimeDialog> createState() => _RegimeDialogState();
}

class _RegimeDialogState extends ConsumerState<_RegimeDialog> {
  late String _regime;
  late final TextEditingController _taxNo;
  late final TextEditingController _certificate;
  late final TextEditingController _seriesCode;
  late final TextEditingController _clientId;
  late final TextEditingController _tssId;
  String? _tseProvider;
  String? _error;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    final c = widget.current;
    _regime = c.regime;
    _taxNo = TextEditingController(text: c.taxRegistrationNumber ?? '');
    _certificate = TextEditingController(text: c.certificateNumber ?? '');
    _seriesCode = TextEditingController(text: c.seriesValidationCode ?? '');
    _clientId = TextEditingController(text: c.tseClientId ?? '');
    _tssId = TextEditingController();
    _tseProvider =
        c.tseProvider ??
        (c.tseProviders.contains('SIMULATED')
            ? 'SIMULATED'
            : c.tseProviders.firstOrNull);
  }

  @override
  void dispose() {
    _taxNo.dispose();
    _certificate.dispose();
    _seriesCode.dispose();
    _clientId.dispose();
    _tssId.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final body = <String, dynamic>{
        'storeId': widget.storeId,
        'regime': _regime,
        'taxRegistrationNumber': _taxNo.text.trim(),
      };
      if (_regime == 'PT_SAFT') {
        body['certificateNumber'] = _certificate.text.trim();
        body['seriesValidationCode'] = _seriesCode.text.trim();
      }
      if (_regime == 'DE_KASSENSICHV') {
        // Register a device only when the store has none, or the manager chose another.
        if (widget.current.tseProvider == null ||
            _tseProvider != widget.current.tseProvider) {
          body['tseProvider'] = _tseProvider;
          if (_tseProvider == 'CLOUD') {
            body['tseTssId'] = _tssId.text.trim();
          }
          if (_clientId.text.trim().isNotEmpty) {
            body['tseClientId'] = _clientId.text.trim();
          }
        }
      }
      await ref
          .read(apiClientProvider)
          .dio
          .put(
            '/${ApiConstants.order}/admin/fiscal-receipts/settings',
            data: body,
          );
      ref.invalidate(fiscalSettingsProvider(widget.storeId));
      ref.invalidate(receiptListProvider);
      if (mounted) Navigator.pop(context);
    } catch (e) {
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'Could not change the regime.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.current;
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Fiscal regime'),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              DropdownButtonFormField<String>(
                key: const Key('regime-select'),
                isExpanded: true,
                initialValue: _regime,
                decoration: const InputDecoration(labelText: 'Regime'),
                items: [
                  for (final r in c.regimes)
                    DropdownMenuItem(
                      value: r,
                      child: Text(FiscalSettings.regimeLabel(r)),
                    ),
                ],
                onChanged: (v) => setState(() => _regime = v ?? 'NONE'),
              ),
              if (_regime != 'NONE')
                TextField(
                  key: const Key('regime-tax-number'),
                  controller: _taxNo,
                  decoration: InputDecoration(
                    labelText: _regime == 'PT_SAFT'
                        ? 'NIF'
                        : 'Steuernummer / USt-IdNr',
                  ),
                ),
              if (_regime == 'DE_KASSENSICHV') ...[
                DropdownButtonFormField<String>(
                  key: const Key('regime-tse-provider'),
                  isExpanded: true,
                  initialValue: _tseProvider,
                  decoration: const InputDecoration(
                    labelText: 'Security module (TSE)',
                  ),
                  items: [
                    for (final p in c.tseProviders)
                      DropdownMenuItem(
                        value: p,
                        child: Text(
                          p == 'SIMULATED'
                              ? 'Simulated — not certified; for rigs without a device'
                              : 'Cloud — a certified module at the configured provider',
                        ),
                      ),
                  ],
                  onChanged: (v) => setState(() => _tseProvider = v),
                ),
                if (_tseProvider == 'CLOUD')
                  TextField(
                    controller: _tssId,
                    decoration: const InputDecoration(
                      labelText: 'Provider\'s device id (TSS id)',
                    ),
                  ),
                TextField(
                  controller: _clientId,
                  decoration: const InputDecoration(
                    labelText: 'Client id',
                    helperText:
                        'What the device knows this register by; the store id when blank',
                  ),
                ),
              ],
              if (_regime == 'PT_SAFT') ...[
                if (!c.ptKeyConfigured)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      'No signing key is installed on the server. The software must be certified by the AT and its key configured before a store can trade under this regime.',
                      key: const Key('regime-pt-no-key'),
                      style: TextStyle(color: cs.error, fontSize: 12),
                    ),
                  ),
                TextField(
                  controller: _certificate,
                  decoration: const InputDecoration(
                    labelText: 'AT certificate number',
                    helperText: 'Printed on every document as n.º NNNN/AT',
                  ),
                ),
                TextField(
                  controller: _seriesCode,
                  decoration: const InputDecoration(
                    labelText: 'Series validation code',
                    helperText: 'From the AT; the ATCUD is <code>-<number>',
                  ),
                ),
              ],
              const SizedBox(height: 8),
              const Text(
                'Applies from the next sale. Every document already issued keeps the stamp it was issued with.',
                style: TextStyle(fontSize: 12),
              ),
              if (_error != null) ...[
                const SizedBox(height: 8),
                Text(
                  _error!,
                  key: const Key('regime-error'),
                  style: TextStyle(color: cs.error),
                ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('regime-save'),
          onPressed: _saving ? null : _save,
          child: const Text('Save'),
        ),
      ],
    );
  }
}

class _SeriesCard extends ConsumerWidget {
  const _SeriesCard({required this.storeId, required this.isManager});
  final String storeId;
  final bool isManager;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final series = ref.watch(receiptSeriesProvider(storeId));
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    'Series',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                if (isManager)
                  TextButton.icon(
                    onPressed: () => showDialog<void>(
                      context: context,
                      builder: (_) => _SeriesPrefixDialog(storeId: storeId),
                    ),
                    icon: const Icon(Icons.edit_outlined, size: 18),
                    label: const Text('Set prefix'),
                  ),
              ],
            ),
            series.when(
              loading: () => const LoadingView(label: 'Loading series…'),
              error: (e, _) => ErrorView(
                message: friendlyError(
                  e,
                  fallback: 'Could not load the series.',
                ),
                onRetry: () => ref.invalidate(receiptSeriesProvider(storeId)),
              ),
              data: (rows) => rows.isEmpty
                  ? const Text(
                      'No series opened yet — the first completed sale opens MAIN.',
                    )
                  : Column(
                      children: [
                        for (final s in rows)
                          ListTile(
                            dense: true,
                            title: Text('${s.seriesCode} · ${s.period}'),
                            subtitle: Text(
                              'prefix ${s.prefix ?? '—'} · next number ${s.nextNumber}',
                            ),
                          ),
                      ],
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SeriesPrefixDialog extends ConsumerStatefulWidget {
  const _SeriesPrefixDialog({required this.storeId});
  final String storeId;
  @override
  ConsumerState<_SeriesPrefixDialog> createState() =>
      _SeriesPrefixDialogState();
}

class _SeriesPrefixDialogState extends ConsumerState<_SeriesPrefixDialog> {
  final _series = TextEditingController(text: 'MAIN');
  final _period = TextEditingController(text: DateTime.now().year.toString());
  final _prefix = TextEditingController();
  String? _error;
  bool _saving = false;

  @override
  void dispose() {
    _series.dispose();
    _period.dispose();
    _prefix.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .put(
            '/${ApiConstants.order}/admin/fiscal-receipts/series',
            data: {
              'storeId': widget.storeId,
              'seriesCode': _series.text.trim(),
              'period': _period.text.trim(),
              'prefix': _prefix.text.trim(),
            },
          );
      ref.invalidate(receiptSeriesProvider(widget.storeId));
      if (mounted) Navigator.pop(context);
    } catch (e) {
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'Could not set the prefix.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Series prefix'),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _series,
              decoration: const InputDecoration(labelText: 'Series'),
            ),
            TextField(
              controller: _period,
              decoration: const InputDecoration(labelText: 'Fiscal year'),
            ),
            TextField(
              controller: _prefix,
              decoration: const InputDecoration(
                labelText: 'Prefix',
                helperText: 'Printed in front of the number, e.g. GB-LDN-01',
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              'Changes the documents issued from now on. Every receipt already issued keeps the number it was printed with.',
              style: TextStyle(fontSize: 12),
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
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
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _saving ? null : _save,
          child: const Text('Save'),
        ),
      ],
    );
  }
}
