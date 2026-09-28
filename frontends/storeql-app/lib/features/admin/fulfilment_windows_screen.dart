import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/status_badge.dart';
import 'providers/admin_providers.dart' show StoreInfo;

// ---------------------------------------------------------------------------
// Delivery and collection slots (delivery-and-collection-slots): a store's
// windows, for delivery and collection separately — the weekday, from and to
// in the store's own time, how many orders a window takes, and how long
// before it starts orders stop. Set by an owner or manager, per store; the
// storefront reads them through the picker, and every order shows the one it
// holds.
// ---------------------------------------------------------------------------

const _windows = '/${ApiConstants.order}/admin/fulfilment-windows';

const _weekdayNames = [
  'Monday',
  'Tuesday',
  'Wednesday',
  'Thursday',
  'Friday',
  'Saturday',
  'Sunday',
];

/// The ISO weekday (1 = Monday .. 7 = Sunday) in words.
String weekdayName(int isoWeekday) => (isoWeekday >= 1 && isoWeekday <= 7)
    ? _weekdayNames[isoWeekday - 1]
    : 'Day $isoWeekday';

class FulfilmentWindow {
  final String id;
  final String storeId;
  final String fulfilmentType;
  final int weekday;
  final String startTime;
  final String endTime;
  final int capacity;
  final int cutoffMinutes;
  final bool active;

  const FulfilmentWindow({
    required this.id,
    required this.storeId,
    required this.fulfilmentType,
    required this.weekday,
    required this.startTime,
    required this.endTime,
    required this.capacity,
    required this.cutoffMinutes,
    required this.active,
  });

  factory FulfilmentWindow.fromJson(Map<String, dynamic> j) => FulfilmentWindow(
    id: j['id'] as String? ?? '',
    storeId: j['storeId'] as String? ?? '',
    fulfilmentType: j['fulfilmentType'] as String? ?? 'DELIVERY',
    weekday: (j['weekday'] as num?)?.toInt() ?? 1,
    startTime: j['startTime'] as String? ?? '',
    endTime: j['endTime'] as String? ?? '',
    capacity: (j['capacity'] as num?)?.toInt() ?? 1,
    cutoffMinutes: (j['cutoffMinutes'] as num?)?.toInt() ?? 0,
    active: j['active'] as bool? ?? true,
  );
}

final fulfilmentWindowsProvider = FutureProvider.autoDispose
    .family<List<FulfilmentWindow>, String>((ref, storeId) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get(_windows, queryParameters: {'storeId': storeId});
      return ((resp.data['data'] as List?) ?? const [])
          .map((e) => FulfilmentWindow.fromJson(e as Map<String, dynamic>))
          .toList();
    });

/// Which type's windows the tab shows: DELIVERY | PICKUP. autoDispose — this
/// is scoped to the sheet it opens in.
final fulfilmentWindowsTypeProvider = StateProvider.autoDispose<String>(
  (ref) => 'DELIVERY',
);

/// One store's delivery and collection windows: listed, added, edited, or
/// switched off. Opened from the Stores screen's *Delivery & collection
/// slots* action (a sheet or dialog, adaptive to the window).
class FulfilmentWindowsScreen extends ConsumerWidget {
  final StoreInfo store;
  const FulfilmentWindowsScreen({super.key, required this.store});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    final isManager = auth is AuthAuthenticated && auth.isManager;
    if (!isManager) {
      return const EmptyState(
        key: Key('slots-not-offered'),
        icon: Icons.lock_outline,
        title:
            'Only an owner or manager can set delivery and collection windows.',
      );
    }

    final type = ref.watch(fulfilmentWindowsTypeProvider);
    final async = ref.watch(fulfilmentWindowsProvider(store.id));
    final tz = store.timezone;
    final cs = Theme.of(context).colorScheme;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          (tz == null || tz.isEmpty)
              ? 'Times are the store\'s own.'
              : 'Times are the store\'s own: $tz.',
          style: TextStyle(color: cs.onSurfaceVariant),
        ),
        const SizedBox(height: AppSpacing.md),
        SizedBox(
          width: double.infinity,
          child: SegmentedButton<String>(
            segments: const [
              ButtonSegment(
                value: 'DELIVERY',
                label: Text('Delivery'),
                icon: Icon(Icons.local_shipping_outlined),
              ),
              ButtonSegment(
                value: 'PICKUP',
                label: Text('Collection'),
                icon: Icon(Icons.storefront_outlined),
              ),
            ],
            selected: {type},
            onSelectionChanged: (s) =>
                ref.read(fulfilmentWindowsTypeProvider.notifier).state =
                    s.first,
          ),
        ),
        const SizedBox(height: AppSpacing.md),
        async.when(
          loading: () => const LoadingView(label: 'Loading windows…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the windows.'),
            onRetry: () => ref.invalidate(fulfilmentWindowsProvider(store.id)),
          ),
          data: (all) {
            final rows = all.where((w) => w.fulfilmentType == type).toList()
              ..sort(
                (a, b) => a.weekday != b.weekday
                    ? a.weekday.compareTo(b.weekday)
                    : a.startTime.compareTo(b.startTime),
              );
            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              mainAxisSize: MainAxisSize.min,
              children: [
                if (rows.isEmpty)
                  Padding(
                    key: const Key('windows-empty'),
                    padding: const EdgeInsets.symmetric(
                      vertical: AppSpacing.lg,
                    ),
                    child: Text(
                      type == 'DELIVERY'
                          ? 'No delivery windows yet.'
                          : 'No collection windows yet.',
                      style: TextStyle(color: cs.onSurfaceVariant),
                    ),
                  )
                else
                  for (final w in rows) ...[
                    _WindowTile(
                      window: w,
                      onTap: () =>
                          _addOrEdit(context, ref, type: type, existing: w),
                    ),
                    const SizedBox(height: AppSpacing.sm),
                  ],
                Align(
                  alignment: AlignmentDirectional.centerStart,
                  child: OutlinedButton.icon(
                    key: const Key('add-window'),
                    onPressed: () => _addOrEdit(context, ref, type: type),
                    icon: const Icon(Icons.add),
                    label: const Text('Add window'),
                  ),
                ),
              ],
            );
          },
        ),
      ],
    );
  }

  Future<void> _addOrEdit(
    BuildContext context,
    WidgetRef ref, {
    required String type,
    FulfilmentWindow? existing,
  }) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (_) => _WindowFormDialog(
        storeId: store.id,
        fulfilmentType: type,
        existing: existing,
      ),
    );
    if (saved == true) ref.invalidate(fulfilmentWindowsProvider(store.id));
  }
}

class _WindowTile extends StatelessWidget {
  final FulfilmentWindow window;
  final VoidCallback onTap;
  const _WindowTile({required this.window, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final w = window;
    return Card(
      key: Key('window-${w.id}'),
      child: ListTile(
        onTap: onTap,
        leading: Icon(
          w.active ? Icons.event_available_outlined : Icons.event_busy_outlined,
        ),
        title: Text('${weekdayName(w.weekday)} · ${w.startTime}–${w.endTime}'),
        subtitle: Text(
          'Capacity ${w.capacity} · cut-off ${w.cutoffMinutes} min before',
        ),
        trailing: StatusBadge(
          w.active ? 'Active' : 'Off',
          tone: w.active ? StatusTone.success : StatusTone.neutral,
        ),
      ),
    );
  }
}

/// Adds or edits one window: weekday, from/to (24-hour time pickers),
/// capacity, cut-off and active — POSTs a new one or PUTs the one being
/// edited (store and type are fixed once a window exists).
class _WindowFormDialog extends ConsumerStatefulWidget {
  final String storeId;
  final String fulfilmentType;
  final FulfilmentWindow? existing;

  const _WindowFormDialog({
    required this.storeId,
    required this.fulfilmentType,
    this.existing,
  });

  @override
  ConsumerState<_WindowFormDialog> createState() => _WindowFormDialogState();
}

class _WindowFormDialogState extends ConsumerState<_WindowFormDialog> {
  late int _weekday;
  late TimeOfDay _start;
  late TimeOfDay _end;
  late final TextEditingController _capacity;
  late final TextEditingController _cutoff;
  late bool _active;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    _weekday = e?.weekday ?? DateTime.monday;
    _start = _parseTime(e?.startTime) ?? const TimeOfDay(hour: 9, minute: 0);
    _end = _parseTime(e?.endTime) ?? const TimeOfDay(hour: 17, minute: 0);
    _capacity = TextEditingController(text: '${e?.capacity ?? 10}');
    _cutoff = TextEditingController(text: '${e?.cutoffMinutes ?? 60}');
    _active = e?.active ?? true;
  }

  static TimeOfDay? _parseTime(String? hhmm) {
    if (hhmm == null || !hhmm.contains(':')) return null;
    final parts = hhmm.split(':');
    if (parts.length != 2) return null;
    final h = int.tryParse(parts[0]);
    final m = int.tryParse(parts[1]);
    if (h == null || m == null) return null;
    return TimeOfDay(hour: h, minute: m);
  }

  /// "HH:mm", 24-hour, whatever the device's own clock convention — the wire
  /// shape order-svc expects, never a locale-formatted string.
  static String _fmt(TimeOfDay t) =>
      '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';

  @override
  void dispose() {
    _capacity.dispose();
    _cutoff.dispose();
    super.dispose();
  }

  Future<void> _pickTime(bool start) async {
    final picked = await showTimePicker(
      context: context,
      initialTime: start ? _start : _end,
      // A 24-hour dial: a manager sets store hours other staff and shoppers
      // everywhere read, so the clock is never AM/PM-ambiguous.
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(alwaysUse24HourFormat: true),
        child: child!,
      ),
    );
    if (picked == null) return;
    setState(() {
      if (start) {
        _start = picked;
      } else {
        _end = picked;
      }
    });
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final body = {
        'weekday': _weekday,
        'startTime': _fmt(_start),
        'endTime': _fmt(_end),
        'capacity': int.tryParse(_capacity.text.trim()) ?? 0,
        'cutoffMinutes': int.tryParse(_cutoff.text.trim()) ?? 0,
        'active': _active,
      };
      final dio = ref.read(apiClientProvider).dio;
      final existing = widget.existing;
      if (existing == null) {
        await dio.post(
          _windows,
          data: {
            'storeId': widget.storeId,
            'fulfilmentType': widget.fulfilmentType,
            ...body,
          },
        );
      } else {
        await dio.put('$_windows/${existing.id}', data: body);
      }
      if (!mounted) return;
      Navigator.pop(context, true);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        // ORDER_SLOT_WINDOW_INVALID names the problem (overlap, times, capacity,
        // cut-off, weekday) in its own message; friendlyError shows it.
        _error = friendlyError(
          e,
          fallback:
              'That window overlaps another, or its times or capacity are not valid.',
        );
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text(widget.existing == null ? 'Add window' : 'Edit window'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            DropdownButtonFormField<int>(
              key: const Key('window-weekday'),
              initialValue: _weekday,
              isExpanded: true,
              decoration: const InputDecoration(labelText: 'Weekday'),
              items: [
                for (var d = 1; d <= 7; d++)
                  DropdownMenuItem(value: d, child: Text(weekdayName(d))),
              ],
              onChanged: (v) => setState(() => _weekday = v ?? _weekday),
            ),
            const SizedBox(height: AppSpacing.sm),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    key: const Key('window-start'),
                    onPressed: () => _pickTime(true),
                    child: Text('From ${_fmt(_start)}'),
                  ),
                ),
                const SizedBox(width: AppSpacing.sm),
                Expanded(
                  child: OutlinedButton(
                    key: const Key('window-end'),
                    onPressed: () => _pickTime(false),
                    child: Text('To ${_fmt(_end)}'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: AppSpacing.sm),
            TextField(
              key: const Key('window-capacity'),
              controller: _capacity,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Capacity (orders)'),
            ),
            const SizedBox(height: AppSpacing.sm),
            TextField(
              key: const Key('window-cutoff'),
              controller: _cutoff,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                labelText: 'Cut-off (minutes before it starts)',
              ),
            ),
            SwitchListTile.adaptive(
              key: const Key('window-active'),
              contentPadding: EdgeInsets.zero,
              title: const Text('Active'),
              value: _active,
              onChanged: (v) => setState(() => _active = v),
            ),
            if (_error != null) ...[
              const SizedBox(height: AppSpacing.sm),
              Text(
                _error!,
                key: const Key('window-error'),
                style: TextStyle(color: cs.error),
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
          key: const Key('window-save'),
          onPressed: _saving ? null : _save,
          child: _saving
              ? const SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Text('Save'),
        ),
      ],
    );
  }
}
