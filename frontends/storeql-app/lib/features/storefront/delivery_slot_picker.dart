import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/format.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import 'storefront_providers.dart';

/// The design system's *DeliverySlotPicker*: the next seven days of a store's
/// delivery or collection windows, in the store's own time, with what each has
/// left.
///
/// Reads `GET /order-svc/storefront/fulfilment-slots?store=&type=` through
/// [fulfilmentSlotsProvider]. Renders nothing while that answers `offered:
/// false` — a store with no windows checks out exactly as before — so a
/// caller can always mount this once it knows a store, without first asking
/// whether the store offers windows.
///
/// Every date and time comes from the server already in the store's own zone
/// (`SlotOption.timeRange`, `SlotDay.date`) and is never converted on the
/// device: a shopper in one time zone sees a store in another its own hours.
///
/// [selected] is the chosen occurrence, held by the caller (it also decides
/// whether *Place order* may be pressed); [onSelected] reports a tap, or a
/// clear when the caller re-reads after the window filled meanwhile.
class DeliverySlotPicker extends ConsumerWidget {
  final String storeId;

  /// DELIVERY | PICKUP.
  final String fulfilmentType;

  final SlotOption? selected;
  final ValueChanged<SlotOption?> onSelected;

  const DeliverySlotPicker({
    super.key,
    required this.storeId,
    required this.fulfilmentType,
    required this.selected,
    required this.onSelected,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final query = (store: storeId, type: fulfilmentType);
    final async = ref.watch(fulfilmentSlotsProvider(query));
    final cs = Theme.of(context).colorScheme;
    final windowWord = fulfilmentType == 'PICKUP' ? 'collection' : 'delivery';

    return async.when(
      loading: () => Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
        child: Row(
          children: [
            const SizedBox(
              height: 16,
              width: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            const SizedBox(width: AppSpacing.sm),
            Text(
              'Checking available $windowWord windows…',
              style: TextStyle(color: cs.onSurfaceVariant),
            ),
          ],
        ),
      ),
      // A soft failure: the store's window offer could not be read. Checkout
      // still works — the server is the one place a slot is truly required —
      // so this offers Retry rather than blocking anything.
      error: (e, _) => Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
        child: Row(
          children: [
            Icon(Icons.error_outline, size: 16, color: cs.error),
            const SizedBox(width: AppSpacing.sm),
            Expanded(
              child: Text(
                'Could not check $windowWord windows.',
                style: TextStyle(color: cs.onSurfaceVariant),
              ),
            ),
            TextButton(
              onPressed: () => ref.invalidate(fulfilmentSlotsProvider(query)),
              child: const Text('Retry'),
            ),
          ],
        ),
      ),
      data: (slots) {
        // offered = false ⇒ no picker, checkout exactly as today.
        if (!slots.offered) return const SizedBox.shrink();
        return _DaysPicker(
          slots: slots,
          selected: selected,
          onSelected: onSelected,
          windowWord: windowWord,
        );
      },
    );
  }
}

/// The days as a row of choices, and — under it — only the tapped day's
/// windows: never all seven laid out at once, which used to push everything
/// under the picker (Payment, the summary, *Review order*) off the bottom of
/// the web cart's narrow checkout column.
///
/// The day row is its own choice, independent of [selected]: tapping a day
/// only changes which day's windows show below; only tapping a window itself
/// reports through [onSelected]. Starts on the day [selected] belongs to
/// (so a window chosen earlier is where the shopper left it), else the first.
class _DaysPicker extends StatefulWidget {
  final FulfilmentSlots slots;
  final SlotOption? selected;
  final ValueChanged<SlotOption?> onSelected;
  final String windowWord;

  const _DaysPicker({
    required this.slots,
    required this.selected,
    required this.onSelected,
    required this.windowWord,
  });

  @override
  State<_DaysPicker> createState() => _DaysPickerState();
}

class _DaysPickerState extends State<_DaysPicker> {
  late int _dayIndex;

  @override
  void initState() {
    super.initState();
    _dayIndex = _dayIndexOf(widget.slots, widget.selected) ?? 0;
  }

  @override
  void didUpdateWidget(_DaysPicker old) {
    super.didUpdateWidget(old);
    // The shopper stays on the day they were looking at — when they un-pick a
    // window, and when the chosen one filled meanwhile and the picker re-read
    // (ORDER_SLOT_FULL/CLOSED), so they pick another the same day. Only a
    // re-read with fewer days moves them, back to the first.
    if (_dayIndex >= widget.slots.days.length) {
      setState(() => _dayIndex = 0);
    }
  }

  /// Which day [o] falls on: the day it carries, else the day whose windows
  /// hold the same occurrence. Null when it isn't in [slots] at all (a stale
  /// selection from before a re-read).
  static int? _dayIndexOf(FulfilmentSlots slots, SlotOption? o) {
    if (o == null) return null;
    final days = slots.days;
    for (var i = 0; i < days.length; i++) {
      if (o.date.isNotEmpty
          ? days[i].date == o.date
          : days[i]
              .slots
              .any((s) => s.windowId == o.windowId && s.startsAt == o.startsAt)) {
        return i;
      }
    }
    return null;
  }

  bool _isSelected(SlotOption o) {
    final s = widget.selected;
    return s != null && s.windowId == o.windowId && s.startsAt == o.startsAt;
  }

  void _select(SlotOption o) => widget.onSelected(_isSelected(o) ? null : o);

  /// "Today" / "Tomorrow" by position — the server's first two days are
  /// always today and tomorrow in the store's own zone — then the weekday and
  /// date the way the review and the order say them (*Fri 25 Sept*), in the
  /// reader's own order.
  String _dayLabel(int index, SlotDay day) {
    if (index == 0) return 'Today';
    if (index == 1) return 'Tomorrow';
    final d = day.localDate;
    return d == null ? day.date : AppFormat.weekdayDate(d);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final days = widget.slots.days;
    final dayIndex = days.isEmpty ? 0 : _dayIndex.clamp(0, days.length - 1);

    // Which day's windows show below — a navigational choice, coloured
    // `primary` when picked, never the window's own `tertiaryContainer` (that
    // reads "the window you chose", not "the day you're looking at").
    Widget dayChip(int i) {
      final chosen = i == dayIndex;
      return ChoiceChip(
        key: Key('slot-day-$i'),
        label: Text(_dayLabel(i, days[i])),
        selected: chosen,
        showCheckmark: false,
        selectedColor: cs.primary,
        labelStyle: TextStyle(color: chosen ? cs.onPrimary : cs.onSurface),
        onSelected: (_) => setState(() => _dayIndex = i),
      );
    }

    // A window chosen (or choosable): the informational `tertiaryContainer`
    // with a 2px `tertiary` border — never the theme's default selected
    // filter chip (`secondaryContainer` + a check), which is the frequent,
    // positive-action colour the payment chips under it use.
    Widget windowChoiceChip(SlotOption o) {
      final chosen = _isSelected(o);
      return ChoiceChip(
        key: Key('slot-${o.windowId}-${o.startsAt.toIso8601String()}'),
        label: Text(o.timeRange),
        selected: chosen,
        showCheckmark: false,
        selectedColor: cs.tertiaryContainer,
        labelStyle:
            TextStyle(color: chosen ? cs.onTertiaryContainer : cs.onSurface),
        shape: chosen
            ? RoundedRectangleBorder(
                borderRadius: AppRadius.chip,
                side: BorderSide(color: cs.tertiary, width: 2),
              )
            : null,
        onSelected: (_) => _select(o),
      );
    }

    // A full window: shown, never tappable — but still read at full contrast.
    // A plain (non-selectable) Chip, not a ChoiceChip with onSelected: null,
    // which Flutter paints at the Material disabled 38% opacity: right for an
    // unpickable window, wrong for one that must still say *Full* clearly.
    Widget fullWindowChip(SlotOption o) => Chip(
          key: Key('slot-${o.windowId}-${o.startsAt.toIso8601String()}'),
          label: Text('${o.timeRange} · Full'),
          labelStyle: TextStyle(color: cs.onSurface),
        );

    final dayWindows = days.isEmpty ? const <SlotOption>[] : days[dayIndex].slots;
    final windowsBody = dayWindows.isEmpty
        ? Text('No windows this day', style: TextStyle(color: cs.onSurfaceVariant))
        : Wrap(
            spacing: AppSpacing.sm,
            runSpacing: AppSpacing.xs,
            children: [
              for (final o in dayWindows)
                o.full ? fullWindowChip(o) : windowChoiceChip(o),
            ],
          );

    final warning = widget.selected == null
        ? Padding(
            padding: const EdgeInsets.only(top: AppSpacing.xs),
            child: Text(
              'Pick a ${widget.windowWord} window before placing your order',
              style: TextStyle(color: cs.error, fontSize: 12),
            ),
          )
        : const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Padding(
          padding: const EdgeInsets.only(bottom: AppSpacing.sm),
          child: Text(
            'Pick a ${widget.windowWord} window',
            style: theme.textTheme.titleSmall,
          ),
        ),
        // A row, not a wrap: seven days (some written out as a date, in a
        // language that may run long) must never grow the picker downward —
        // that is exactly how the old one-block-per-day layout pushed
        // Payment, the summary and *Review order* off the bottom of the web
        // cart's narrow checkout column. It scrolls sideways instead.
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(
            children: [
              for (var i = 0; i < days.length; i++) ...[
                if (i > 0) const SizedBox(width: AppSpacing.sm),
                dayChip(i),
              ],
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.only(top: AppSpacing.sm),
          child: windowsBody,
        ),
        warning,
      ],
    );
  }
}
