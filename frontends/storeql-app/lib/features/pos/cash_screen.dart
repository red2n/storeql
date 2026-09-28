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
import '../admin/providers/admin_providers.dart' show tenantInfoProvider;
import 'cash_providers.dart';
import 'pos_providers.dart';
import '../../shared/util/short_ref.dart';

/// The currency the till counts cash in: the business's own. Stores carry no
/// currency of their own, and payment-svc counts cash in the tenant's when none
/// is given. Empty until it is known, and the figures are then plain numbers —
/// never a guessed currency.
String _tillCurrency(WidgetRef ref) =>
    ref.watch(tenantInfoProvider).value?.currency ?? '';

/// The till: open one with a float, or work the one already open — which is
/// asked of payment-svc each time the screen opens, so a reload or another
/// device at the counter carries on with it rather than offering a second.
class CashScreen extends ConsumerWidget {
  const CashScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return ref.watch(activeTillProvider).when(
          loading: () => const LoadingView(label: 'Checking the till…'),
          // The server could not say: today's form, with the reason and a
          // way to ask again.
          error: (e, _) => _OpenTillView(
            lookupError: friendlyError(e, fallback: 'Please try again.'),
            onRetry: () => ref.invalidate(activeTillProvider),
          ),
          data: (session) => session == null
              ? const _OpenTillView()
              : _OpenSessionView(sessionId: session),
        );
  }
}

// ── Open a till ──────────────────────────────────────────────────────────────

class _OpenTillView extends ConsumerStatefulWidget {
  const _OpenTillView({this.lookupError, this.onRetry});

  /// Why the server could not say whether a till is open here, if it could not.
  final String? lookupError;
  final VoidCallback? onRetry;

  @override
  ConsumerState<_OpenTillView> createState() => _OpenTillViewState();
}

class _OpenTillViewState extends ConsumerState<_OpenTillView> {
  final _floatCtrl = TextEditingController(text: '0');
  bool _opening = false;
  String? _error;

  @override
  void dispose() {
    _floatCtrl.dispose();
    super.dispose();
  }

  Future<void> _open() async {
    final storeId = ref.read(posStoreProvider);
    if (storeId == null) {
      setState(() => _error = 'Pick a store on the Sale tab first.');
      return;
    }
    setState(() {
      _opening = true;
      _error = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.payment}/admin/cash/till-sessions',
        data: {
          'storeId': storeId,
          'floatAmount': double.tryParse(_floatCtrl.text.trim()) ?? 0,
        },
      );
      final session = resp.data['data'] as Map<String, dynamic>;
      final id = session['id'] as String?;
      if (id != null) ref.read(activeTillProvider.notifier).opened(id);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _opening = false;
        _error = friendlyError(e, fallback: 'Could not open till.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(posStoresProvider);
    final storeId = ref.watch(posStoreProvider);
    final storeName = storesAsync.maybeWhen(
      data: (stores) =>
          stores.where((s) => s.id == storeId).map((s) => s.name).firstOrNull,
      orElse: () => null,
    );
    final symbol = AppFormat.currencySymbol(_tillCurrency(ref));
    return Center(
      // Scrolls when the keyboard or large text leaves it too little height.
      child: SingleChildScrollView(
        padding: EdgeInsets.all(context.pageGutter),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 360),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Icon(Icons.point_of_sale, size: 56, color: cs.primary),
              const SizedBox(height: AppSpacing.md),
              Text('Open till', style: Theme.of(context).textTheme.headlineSmall,
                  textAlign: TextAlign.center),
              const SizedBox(height: AppSpacing.xs),
              Text(
                storeName == null
                    ? 'Select a store on the Sale tab first'
                    : 'Store: $storeName',
                textAlign: TextAlign.center,
                style: TextStyle(color: cs.onSurfaceVariant),
              ),
              const SizedBox(height: AppSpacing.xl),
              if (widget.lookupError != null) ...[
                _LookupFailed(
                    message: widget.lookupError!, onRetry: widget.onRetry),
                const SizedBox(height: AppSpacing.lg),
              ],
              if (_error != null) ...[
                Text(_error!, style: TextStyle(color: cs.error)),
                const SizedBox(height: AppSpacing.md),
              ],
              TextField(
                controller: _floatCtrl,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(
                  labelText: 'Opening float',
                  // Neutral: a dollar sign has no place on a pound or rupee till.
                  prefixIcon: const Icon(Icons.payments_outlined),
                  prefixText: symbol.isEmpty ? null : '$symbol ',
                ),
              ),
              const SizedBox(height: AppSpacing.lg),
              FilledButton.icon(
                onPressed: _opening ? null : _open,
                icon: _opening
                    ? SizedBox(
                        height: 18,
                        width: 18,
                        child: CircularProgressIndicator(
                            strokeWidth: 2, color: cs.onPrimary))
                    : const Icon(Icons.lock_open),
                label: Text(_opening ? 'Opening…' : 'Open till'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// The server could not say whether a till is already open here. The form
/// stays — the till may still be opened — with the reason and *Try again*.
class _LookupFailed extends StatelessWidget {
  const _LookupFailed({required this.message, this.onRetry});

  final String message;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return Card(
      margin: EdgeInsets.zero,
      color: cs.errorContainer,
      child: Padding(
        padding: const EdgeInsetsDirectional.fromSTEB(
            AppSpacing.lg, AppSpacing.md, AppSpacing.sm, AppSpacing.xs),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.error_outline, size: 20, color: cs.onErrorContainer),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: Text(
                    "Couldn't check whether a till is already open here. "
                    '$message',
                    style: text.bodyMedium
                        ?.copyWith(color: cs.onErrorContainer),
                  ),
                ),
              ],
            ),
            if (onRetry != null)
              Align(
                alignment: AlignmentDirectional.centerEnd,
                child: TextButton(
                  style: TextButton.styleFrom(
                      foregroundColor: cs.onErrorContainer),
                  onPressed: onRetry,
                  child: const Text('Try again'),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

// ── Operate an open till ─────────────────────────────────────────────────────

class _OpenSessionView extends ConsumerWidget {
  final String sessionId;
  const _OpenSessionView({required this.sessionId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final reportAsync = ref.watch(xReportProvider(sessionId));
    final currency = _tillCurrency(ref);
    return reportAsync.when(
      loading: () => const LoadingView(label: 'Loading till…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load till report.'),
        onRetry: () => ref.invalidate(xReportProvider(sessionId)),
      ),
      data: (r) {
        final gutter = context.pageGutter;
        // Pull to refresh on touch; the refresh button stays for a mouse.
        return RefreshIndicator.adaptive(
          // Wait for the fresh report, so the spinner stays until it's in.
          onRefresh: () async {
            try {
              ref.invalidate(xReportProvider(sessionId));
              await ref.read(xReportProvider(sessionId).future);
            } on Object {
              // A failed reload shows as the ErrorView; the pull just ends.
            }
          },
          child: SingleChildScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: EdgeInsets.only(bottom: gutter),
            // A reading column: the figures stay by their labels on a desktop.
            child: ContentBounds.form(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  PageHeader(
                    title: 'Till session',
                    subtitle: '#${shortRef(sessionId)}',
                    actions: [
                      IconButton(
                        icon: const Icon(Icons.refresh),
                        tooltip: 'Refresh till session',
                        onPressed: () =>
                            ref.invalidate(xReportProvider(sessionId)),
                      ),
                    ],
                  ),
                  Padding(
                    padding: EdgeInsets.symmetric(horizontal: gutter),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Card(
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: Column(
                              children: [
                                _row('Opening float', r.floatAmount, currency),
                                _row('Gross sales', r.grossSales, currency),
                                // Refunds come off the sales. None reads 0.00:
                                // negating zero printed −0.00.
                                _row(
                                  'Refunds',
                                  r.totalRefunds == 0 ? 0.0 : -r.totalRefunds,
                                  currency,
                                ),
                                _row('Net sales', r.netSales, currency,
                                    bold: true),
                                const Divider(),
                                _row('Cash drops', r.cashDropsTotal, currency),
                                _row('Expected cash in till',
                                    r.expectedCashInTill, currency,
                                    bold: true),
                              ],
                            ),
                          ),
                        ),
                        const SizedBox(height: 16),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            OutlinedButton.icon(
                              onPressed: () => _cashDrop(context, ref, currency),
                              icon: const Icon(Icons.move_down),
                              label: const Text('Cash drop'),
                            ),
                            OutlinedButton.icon(
                              onPressed: () =>
                                  _movement(context, ref, 'PAY_IN', currency),
                              icon: const Icon(Icons.add),
                              label: const Text('Pay in'),
                            ),
                            OutlinedButton.icon(
                              onPressed: () =>
                                  _movement(context, ref, 'PAY_OUT', currency),
                              icon: const Icon(Icons.remove),
                              label: const Text('Pay out'),
                            ),
                          ],
                        ),
                        const SizedBox(height: 24),
                        FilledButton.icon(
                          // The error pair: its label in onError, not onPrimary.
                          style: FilledButton.styleFrom(
                            backgroundColor: cs.error,
                            foregroundColor: cs.onError,
                          ),
                          onPressed: () => _closeTill(
                              context, ref, r.expectedCashInTill, currency),
                          icon: const Icon(Icons.lock_outline),
                          label: const Text('Close till (Z-report)'),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _row(String label, double value, String currency, {bool bold = false}) {
    final style = TextStyle(fontWeight: bold ? FontWeight.bold : FontWeight.normal);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Expanded(child: Text(label, style: style)),
          Text(AppFormat.money(value, currencyCode: currency), style: style),
        ],
      ),
    );
  }

  Future<void> _cashDrop(
      BuildContext context, WidgetRef ref, String currency) async {
    final result = await _amountReasonDialog(context, 'Cash drop',
        reasonLabel: 'Notes', currency: currency);
    if (result == null) return;
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.payment}/admin/cash/till-sessions/$sessionId/drops',
        data: {'amount': result.amount, 'notes': result.reason},
      );
      ref.invalidate(xReportProvider(sessionId));
      if (!context.mounted) return;
      _toast(context, 'Cash drop recorded.');
    } catch (e) {
      if (!context.mounted) return;
      _toast(context, friendlyError(e, fallback: 'Could not record cash drop.'),
          error: true);
    }
  }

  Future<void> _movement(BuildContext context, WidgetRef ref, String direction,
      String currency) async {
    final storeId = ref.read(posStoreProvider);
    final label = direction == 'PAY_IN' ? 'Pay in' : 'Pay out';
    final result = await _amountReasonDialog(context, label,
        reasonLabel: 'Reason', currency: currency);
    if (result == null) return;
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.payment}/admin/cash/movements',
        data: {
          'tillSessionId': sessionId,
          'storeId': storeId,
          'direction': direction,
          'amount': result.amount,
          'reason': result.reason.isEmpty ? label : result.reason,
        },
      );
      ref.invalidate(xReportProvider(sessionId));
      if (!context.mounted) return;
      _toast(context, '$label recorded.');
    } catch (e) {
      if (!context.mounted) return;
      _toast(
          context,
          friendlyError(e,
              fallback: 'Could not record ${label.toLowerCase()}.'),
          error: true);
    }
  }

  Future<void> _closeTill(BuildContext context, WidgetRef ref, double expected,
      String currency) async {
    final symbol = AppFormat.currencySymbol(currency);
    final countedCtrl = TextEditingController(text: expected.toStringAsFixed(2));
    final counted = await showDialog<double>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Close till'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
                'Expected cash: ${AppFormat.money(expected, currencyCode: currency)}'),
            const SizedBox(height: 12),
            TextField(
              controller: countedCtrl,
              autofocus: true,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: InputDecoration(
                labelText: 'Counted cash',
                prefixText: symbol.isEmpty ? null : '$symbol ',
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () =>
                Navigator.pop(ctx, double.tryParse(countedCtrl.text.trim()) ?? 0),
            child: const Text('Close till'),
          ),
        ],
      ),
    );
    if (counted == null) return;
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.payment}/admin/cash/till-sessions/$sessionId/close',
        data: {'countedCash': counted},
      );
      final z = resp.data['data'] as Map<String, dynamic>;
      final overShort = (z['overShort'] as num?)?.toDouble() ?? 0;
      ref.read(activeTillProvider.notifier).closed();
      if (!context.mounted) return;
      showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Till closed'),
          content: Text(overShort == 0
              ? 'Balanced — no discrepancy.'
              : '${overShort > 0 ? 'Over' : 'Short'} by '
                  '${AppFormat.money(overShort.abs(), currencyCode: currency)}'),
          actions: [
            FilledButton(
                onPressed: () => Navigator.pop(ctx), child: const Text('Done')),
          ],
        ),
      );
    } catch (e) {
      if (!context.mounted) return;
      _toast(context, friendlyError(e, fallback: 'Could not close till.'),
          error: true);
    }
  }

  void _toast(BuildContext context, String msg, {bool error = false}) {
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg),
      backgroundColor: error ? Theme.of(context).colorScheme.error : null,
    ));
  }
}

class _AmountReason {
  final double amount;
  final String reason;
  const _AmountReason(this.amount, this.reason);
}

Future<_AmountReason?> _amountReasonDialog(BuildContext context, String title,
    {required String reasonLabel, required String currency}) {
  final amountCtrl = TextEditingController();
  final reasonCtrl = TextEditingController();
  final symbol = AppFormat.currencySymbol(currency);
  return showDialog<_AmountReason>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: amountCtrl,
            autofocus: true,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(
              labelText: 'Amount',
              prefixText: symbol.isEmpty ? null : '$symbol ',
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: reasonCtrl,
            decoration: InputDecoration(labelText: reasonLabel),
          ),
        ],
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
        FilledButton(
          onPressed: () {
            final amt = double.tryParse(amountCtrl.text.trim());
            if (amt == null || amt <= 0) return;
            Navigator.pop(ctx, _AmountReason(amt, reasonCtrl.text.trim()));
          },
          child: const Text('Record'),
        ),
      ],
    ),
  );
}
