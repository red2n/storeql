import 'dart:convert';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/util/file_download.dart';
import '../../shared/util/status_labels.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import 'payment_runs_tab.dart' show PaymentRunReasonDialog, validIsoDate;
import 'tenant_data_providers.dart';

// Data export and leaving (21.14).
//
// The EU Data Act gives a business the right to take all its data from a
// platform, to have it in a structured, machine-readable form, and to leave
// on notice of at most two months, with its data erased once it has had the
// chance to take it. The owner downloads everything here, brings a bundle in,
// and gives, extends or withdraws notice.
// ---------------------------------------------------------------------------

/// Picks a bundle to import and reads it as text; a provider so tests hand
/// one in.
final tenantBundlePickerProvider = Provider<Future<String?> Function()>(
  (ref) => () async {
    final r = await FilePicker.pickFiles(
      type: FileType.custom,
      allowedExtensions: const ['jsonl'],
      withData: true,
    );
    final bytes = r == null || r.files.isEmpty ? null : r.files.first.bytes;
    return bytes == null ? null : utf8.decode(bytes);
  },
);

/// Where a notice stands, in words.
String switchingStageText(String stage) => switch (stage) {
  'NOTICE' => 'Notice is running: the service carries on as normal',
  'TRANSITION' =>
    'Transitional period: the service carries on while the data moves',
  'RETRIEVAL' => 'Retrieval period: download the data before it is erased',
  'ERASURE_DUE' =>
    'Erasure is due: the platform erases the data at its next sweep',
  'ERASING' => 'Erasing: waiting for every service to confirm',
  'ERASED' => 'Erased: every service has confirmed',
  'CANCELLED' => 'Notice withdrawn',
  _ => stage,
};

/// What a service holds, in words an owner knows: `iam-svc` is where the
/// staff sign in, `cart-svc` the shoppers' carts. A service this list does not
/// know yet reads as its name without the suffix, never as `something-svc`.
String tenantDataServiceName(String service) => switch (service) {
  ApiConstants.tenant => 'Business and stores',
  ApiConstants.iam => 'Staff sign-ins',
  ApiConstants.product => 'Products',
  ApiConstants.pricing => 'Prices',
  ApiConstants.inventory => 'Stock',
  ApiConstants.purchase => 'Purchasing and accounts',
  ApiConstants.order => 'Orders',
  ApiConstants.payment => 'Payments',
  ApiConstants.customer => 'Customers',
  ApiConstants.notification => 'Messages',
  ApiConstants.reporting => 'Reports',
  ApiConstants.cart => 'Shopping carts',
  _ => humanizeCode(service.replaceFirst(RegExp(r'-svc$'), '')),
};

/// A count grouped as every other number is (*138,469*).
String _count(int n) => AppFormat.count(n);

/// *1 row*, *138,469 rows*.
String _rows(int n) => '${_count(n)} ${n == 1 ? 'row' : 'rows'}';

/// A `service/table` key as an owner reads it: `Business and stores: stores`,
/// never the platform's `tenant-svc/stores`.
String _tableInWords(String? key) {
  if (key == null) return 'a table';
  final slash = key.indexOf('/');
  if (slash < 0) return tenantDataServiceName(key);
  return '${tenantDataServiceName(key.substring(0, slash))}: '
      '${key.substring(slash + 1)}';
}

void _tell(ScaffoldMessengerState messenger, String message) {
  messenger
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(message)));
}

class TenantDataScreen extends ConsumerWidget {
  const TenantDataScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    final owner =
        auth is AuthAuthenticated && auth.roles.contains(UserRoles.owner);
    final gutter = context.pageGutter;
    // Cards of running text and a date field: kept to a readable measure on a
    // desktop, the heading over them on the same edge.
    return ListView(
      padding: EdgeInsetsDirectional.only(bottom: gutter),
      children: [
        ContentBounds(
          maxWidth: AppBreakpoints.formMaxWidth + 2 * gutter,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const PageHeader(
                title: 'Data export and leaving',
                subtitle:
                    'Everything this business holds on the platform, service by '
                    'service, to download or bring in; and notice to leave, with the '
                    'data erased once it ends. The EU Data Act gives a business these '
                    'rights.',
              ),
              // The page is the owner's; the navigation lists it for nobody else,
              // and anyone who reaches it by its address is told whose it is.
              if (!owner)
                const EmptyState(
                  icon: Icons.lock_outline,
                  title:
                      "Only the owner can take the business's data out, bring it in, "
                      'or give notice.',
                  message: 'Ask the owner if the data needs to move.',
                )
              else
                Padding(
                  padding: EdgeInsetsDirectional.symmetric(horizontal: gutter),
                  child: const Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _DataCard(),
                      SizedBox(height: AppSpacing.lg),
                      _LeavingCard(),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ],
    );
  }
}

class _DataCard extends ConsumerStatefulWidget {
  const _DataCard();

  @override
  ConsumerState<_DataCard> createState() => _DataCardState();
}

class _DataCardState extends ConsumerState<_DataCard> {
  String? _progress;

  Future<void> _download(List<TenantDataManifest> manifests) async {
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _progress = 'Reading…');
    try {
      final bundle = await buildTenantDataBundle(
        ref.read(apiClientProvider).dio,
        manifests,
        onProgress: (n) {
          if (mounted) setState(() => _progress = 'Read ${_rows(n)}…');
        },
      );
      final day = DateTime.now().toIso8601String().substring(0, 10);
      downloadTextFile(
        'storeql-data-$day.jsonl',
        bundle,
        mimeType: 'application/x-ndjson',
      );
      final rows = manifests.fold(0, (n, m) => n + m.rows);
      _tell(
        messenger,
        'Downloaded ${_rows(rows)} from ${manifests.length} services.',
      );
    } catch (e) {
      _tell(
        messenger,
        friendlyError(e, fallback: 'The data could not be read.'),
      );
    } finally {
      if (mounted) setState(() => _progress = null);
    }
  }

  Future<void> _import(List<TenantDataManifest> manifests) async {
    final messenger = ScaffoldMessenger.of(context);
    String? bundle;
    try {
      bundle = await ref.read(tenantBundlePickerProvider)();
    } on FormatException {
      _tell(messenger, 'That file is not text: choose a .jsonl bundle.');
      return;
    }
    if (bundle == null || !mounted) return;
    final go = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Import into this business?'),
        content: const Text(
          'Every row in the bundle is added to this business, table by table. A '
          'row already here is refused, and the import stops at the first '
          'refusal, naming the table.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Not now'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Import'),
          ),
        ],
      ),
    );
    if (go != true || !mounted) return;
    setState(() => _progress = 'Importing…');
    try {
      final skip = {
        for (final m in manifests)
          for (final t in m.tables)
            if (t.importSkippedReason != null) '${m.service}/${t.name}',
      };
      final result = await importTenantDataBundle(
        ref.read(apiClientProvider).dio,
        bundle,
        skip: skip,
        onProgress: (n) {
          if (mounted) setState(() => _progress = 'Imported ${_rows(n)}…');
        },
      );
      ref.invalidate(tenantDataManifestsProvider);
      _tell(
        messenger,
        result.complete
            ? 'Imported ${_rows(result.rows)}.'
            : 'Imported ${_rows(result.rows)}, then '
                  '${_tableInWords(result.refusedAt)} was refused: '
                  '${result.refusal}',
      );
    } on FormatException catch (e) {
      _tell(messenger, e.message);
    } catch (e) {
      _tell(
        messenger,
        friendlyError(e, fallback: 'The bundle could not be imported.'),
      );
    } finally {
      if (mounted) setState(() => _progress = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: AppSpacing.cardPadding,
        child: ref
            .watch(tenantDataManifestsProvider)
            .when(
              loading: () =>
                  const LoadingView(label: 'Reading what each service holds…'),
              error: (e, _) => ErrorView(
                message: friendlyError(
                  e,
                  fallback: 'What this business holds could not be read.',
                ),
                onRetry: () => ref.invalidate(tenantDataManifestsProvider),
              ),
              data: (list) {
                final rows = list.fold(0, (n, m) => n + m.rows);
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'What this business holds',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: AppSpacing.xs),
                    Text(
                      '${_rows(rows)} in ${list.length} services, as JSON Lines: '
                      'one object per row, keyed by column.',
                      style: TextStyle(color: cs.outline),
                    ),
                    for (final m in list)
                      ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        leading: const Icon(Icons.dns_outlined),
                        title: Text(tenantDataServiceName(m.service)),
                        trailing: Text(
                          '${_rows(m.rows)} · ${_count(m.tables.length)} '
                          '${m.tables.length == 1 ? 'table' : 'tables'}',
                        ),
                      ),
                    ExpansionTile(
                      tilePadding: EdgeInsets.zero,
                      title: const Text('What is left out, and why'),
                      children: [
                        _Reason(
                          'Every service: ${tenantDataMachinery.join(', ')}',
                          'how events and migrations moved: delivery machinery, not data',
                        ),
                        for (final m in list) ...[
                          for (final e in m.excludedTables.entries)
                            if (!tenantDataMachinery.contains(e.key))
                              _Reason(
                                '${tenantDataServiceName(m.service)}: ${e.key}',
                                e.value,
                              ),
                          for (final e in m.excludedColumns.entries)
                            _Reason(
                              '${tenantDataServiceName(m.service)}: ${e.key}',
                              e.value,
                            ),
                          for (final e in m.keptAtErasure.entries)
                            _Reason(
                              '${tenantDataServiceName(m.service)}: ${e.key}, '
                              'kept at erasure',
                              e.value,
                            ),
                        ],
                      ],
                    ),
                    if (_progress != null)
                      Padding(
                        padding: const EdgeInsets.symmetric(
                          vertical: AppSpacing.sm,
                        ),
                        child: Text(_progress!),
                      ),
                    Wrap(
                      spacing: AppSpacing.sm,
                      runSpacing: AppSpacing.sm,
                      children: [
                        FilledButton.icon(
                          onPressed: _progress != null
                              ? null
                              : () => _download(list),
                          icon: const Icon(Icons.download_outlined),
                          label: const Text('Download everything'),
                        ),
                        OutlinedButton.icon(
                          onPressed: _progress != null
                              ? null
                              : () => _import(list),
                          icon: const Icon(Icons.upload_file_outlined),
                          label: const Text('Import a bundle'),
                        ),
                      ],
                    ),
                  ],
                );
              },
            ),
      ),
    );
  }
}

class _Reason extends StatelessWidget {
  const _Reason(this.what, this.why);
  final String what;
  final String why;

  @override
  Widget build(BuildContext context) => ListTile(
    dense: true,
    contentPadding: EdgeInsets.zero,
    title: Text(what),
    subtitle: Text(why),
  );
}

class _LeavingCard extends ConsumerStatefulWidget {
  const _LeavingCard();

  @override
  ConsumerState<_LeavingCard> createState() => _LeavingCardState();
}

class _LeavingCardState extends ConsumerState<_LeavingCard> {
  bool _busy = false;

  Future<void> _post(
    String path,
    Map<String, dynamic> body,
    String done,
  ) async {
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.tenant}/admin/tenant/switching$path',
            data: body,
          );
      ref.invalidate(switchingStatusProvider);
      _tell(messenger, done);
    } catch (e) {
      _tell(
        messenger,
        friendlyError(e, fallback: 'The platform refused that.'),
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _give() async {
    final notice = await showDialog<({String intent, String endsOn})>(
      context: context,
      builder: (_) => const GiveNoticeDialog(),
    );
    if (notice == null) return;
    await _post(
      '',
      {'intent': notice.intent, 'noticeEndsOn': notice.endsOn},
      notice.intent == 'ERASE'
          ? 'Notice given: the data is erased when it ends.'
          : 'Notice given.',
    );
  }

  Future<void> _extend() async {
    final ends = await showDialog<String>(
      context: context,
      builder: (_) => const ExtendTransitionDialog(),
    );
    if (ends != null) {
      await _post('/extend', {
        'transitionEndsOn': ends,
      }, 'Transitional period extended.');
    }
  }

  Future<void> _withdraw() async {
    final reason = await showDialog<String>(
      context: context,
      builder: (_) => const PaymentRunReasonDialog(
        title: 'Withdraw the notice',
        confirm: 'Withdraw',
        keep: 'Keep notice',
      ),
    );
    if (reason != null) {
      await _post('/cancel', {'reason': reason}, 'Notice withdrawn.');
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: AppSpacing.cardPadding,
        child: ref
            .watch(switchingStatusProvider)
            .when(
              loading: () => const LoadingView(label: 'Reading the notice…'),
              error: (e, _) => ErrorView(
                message: friendlyError(
                  e,
                  fallback: 'The notice could not be read.',
                ),
                onRetry: () => ref.invalidate(switchingStatusProvider),
              ),
              data: (s) {
                final n = s?.notice;
                final standing = s != null && s.stage != 'CANCELLED';
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Leaving the platform',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: AppSpacing.xs),
                    if (!standing) ...[
                      Text(
                        s == null
                            ? 'No notice given.'
                            : 'The last notice was withdrawn: ${n?.cancelReason ?? ''}',
                        style: TextStyle(color: cs.outline),
                      ),
                      const SizedBox(height: AppSpacing.sm),
                      FilledButton.icon(
                        onPressed: _busy ? null : _give,
                        icon: const Icon(Icons.logout),
                        label: const Text('Give notice'),
                      ),
                    ] else ...[
                      Text(
                        switchingStageText(s.stage),
                        style: const TextStyle(fontWeight: FontWeight.bold),
                      ),
                      const SizedBox(height: AppSpacing.sm),
                      _Date('Notice ends', n!.noticeEndsOn),
                      if (n.intent == 'SWITCH') ...[
                        _Date('Transitional period ends', n.transitionEndsOn),
                        _Date('Retrieval period ends', n.retrievalEndsOn),
                      ],
                      _Date('Erased from', n.erasureDueOn),
                      if (s.awaiting.isNotEmpty)
                        Text(
                          'Waiting for: '
                          '${s.awaiting.map(tenantDataServiceName).join(', ')}',
                        ),
                      for (final e in s.evidence)
                        Text(
                          '${tenantDataServiceName(e.service)}: '
                          '${_rows(e.rowsErased)} erased',
                        ),
                      const SizedBox(height: AppSpacing.sm),
                      Wrap(
                        spacing: AppSpacing.sm,
                        runSpacing: AppSpacing.sm,
                        children: [
                          if (n.intent == 'SWITCH' &&
                              n.extendedAt == null &&
                              (s.stage == 'NOTICE' || s.stage == 'TRANSITION'))
                            OutlinedButton(
                              onPressed: _busy ? null : _extend,
                              child: const Text('Extend transition'),
                            ),
                          if (s.stage == 'NOTICE')
                            TextButton(
                              onPressed: _busy ? null : _withdraw,
                              child: const Text('Withdraw notice'),
                            ),
                        ],
                      ),
                    ],
                  ],
                );
              },
            ),
      ),
    );
  }
}

class _Date extends StatelessWidget {
  const _Date(this.label, this.value);
  final String label;
  final String? value;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 2),
    child: Row(
      children: [
        Expanded(child: Text(label)),
        const SizedBox(width: AppSpacing.sm),
        Text(value == null || value!.isEmpty ? '—' : AppFormat.date(value)),
      ],
    ),
  );
}

/// A day keyed as YYYY-MM-DD, checked to be a real date.
Widget _dateField(
  TextEditingController controller,
  String label,
  String helper,
) => TextFormField(
  controller: controller,
  decoration: InputDecoration(labelText: label, helperText: helper),
  validator: validIsoDate,
);

/// Asks what the business wants and when notice ends; returns both, or null.
class GiveNoticeDialog extends StatefulWidget {
  const GiveNoticeDialog({super.key});

  @override
  State<GiveNoticeDialog> createState() => _GiveNoticeDialogState();
}

class _GiveNoticeDialogState extends State<GiveNoticeDialog> {
  final _formKey = GlobalKey<FormState>();
  final _ends = TextEditingController();
  String _intent = 'SWITCH';

  @override
  void dispose() {
    _ends.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Give notice to leave'),
      content: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(
                  value: 'SWITCH',
                  label: Text('Take the data elsewhere'),
                ),
                ButtonSegment(value: 'ERASE', label: Text('Erase the data')),
              ],
              selected: {_intent},
              onSelectionChanged: (v) => setState(() => _intent = v.first),
            ),
            const SizedBox(height: 12),
            Text(
              _intent == 'SWITCH'
                  ? 'Notice runs up to two months. Then 30 days while the data moves, '
                        'which can be extended once, and at least 30 more to download it. '
                        'Then it is erased.'
                  : 'The data is erased when notice ends. Download it first to keep a copy.',
            ),
            const SizedBox(height: 12),
            _dateField(
              _ends,
              'Notice ends on *',
              'YYYY-MM-DD, today to two months ahead',
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () {
            if (_formKey.currentState!.validate()) {
              Navigator.pop(context, (
                intent: _intent,
                endsOn: _ends.text.trim(),
              ));
            }
          },
          child: const Text('Give notice'),
        ),
      ],
    );
  }
}

/// Asks for the transitional period's new last day; returns it, or null.
class ExtendTransitionDialog extends StatefulWidget {
  const ExtendTransitionDialog({super.key});

  @override
  State<ExtendTransitionDialog> createState() => _ExtendTransitionDialogState();
}

class _ExtendTransitionDialogState extends State<ExtendTransitionDialog> {
  final _formKey = GlobalKey<FormState>();
  final _ends = TextEditingController();

  @override
  void dispose() {
    _ends.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Extend the transitional period'),
      content: Form(
        key: _formKey,
        child: _dateField(
          _ends,
          'New last day *',
          'Once, to at most seven months after notice ends',
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () {
            if (_formKey.currentState!.validate()) {
              Navigator.pop(context, _ends.text.trim());
            }
          },
          child: const Text('Extend'),
        ),
      ],
    );
  }
}
