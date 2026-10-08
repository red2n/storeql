import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/format.dart';
import '../../core/input_mode.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/util/status_labels.dart';
import '../../shared/widgets/adaptive_sheet.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/skeleton.dart';
import '../../shared/widgets/status_badge.dart';
import 'providers/staff_names.dart';
import 'system_health_providers.dart';

// ---------------------------------------------------------------------------
// System health (intent/system-health-dashboard.md). One screen, three
// sections, for whoever the owner has trusted with it: how the requests are
// going (windows, a failure rate, a verdict in words, bar strips, the busiest
// areas), what failed in the last day (each failure opening the request id to
// quote), and what is waiting for a person (a tile per queue, opening the
// screen that settles it). It reads and nothing here changes anything.
//
// It asks on its own every few seconds while it is showing (the controller in
// system_health_providers.dart) and keeps the last good figures when an ask
// fails. A login without the permission, or held to stores, is told so and
// nothing is asked.
// ---------------------------------------------------------------------------

class SystemHealthScreen extends ConsumerWidget {
  const SystemHealthScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    // Until the sign-in is known nothing is asked or said.
    if (auth is! AuthAuthenticated) return const LoadingView(label: 'Loading…');
    if (!auth.hasPermission(systemHealthPermission)) {
      return const _Refused(SystemHealthRefusal.notPermitted);
    }
    // The gateway cannot tell which store a request was for, so the page is
    // the whole business's and the server refuses a login held to stores.
    if (auth.heldToStores) return const _Refused(SystemHealthRefusal.businessWideOnly);
    return const _LivePage();
  }
}

class _Refused extends StatelessWidget {
  const _Refused(this.why);

  final SystemHealthRefusal why;

  @override
  Widget build(BuildContext context) {
    final permission = why == SystemHealthRefusal.notPermitted;
    return ListView(
      padding: context.pagePadding,
      children: [
        const PageHeader(
          title: 'System health',
          padding: EdgeInsetsDirectional.only(bottom: AppSpacing.lg),
        ),
        EmptyState(
          key: Key(permission ? 'health-not-permitted' : 'health-business-wide-only'),
          icon: Icons.lock_outline,
          title: 'Not open to you',
          message: permission
              ? 'Seeing how healthy the system is needs the system.health permission, '
                  'which your role does not hold. Ask an owner to allow it.'
              : "The system's health covers the whole business, so it is only shown to "
                  'people who are not held to particular stores.',
        ),
      ],
    );
  }
}

class _LivePage extends ConsumerStatefulWidget {
  const _LivePage();

  @override
  ConsumerState<_LivePage> createState() => _LivePageState();
}

class _LivePageState extends ConsumerState<_LivePage> with WidgetsBindingObserver {
  bool _foreground = true;
  bool _showing = true;

  /// An app in the background asks nothing; one merely out of focus (a desktop
  /// window behind another) is still on someone's screen.
  static bool _inForeground(AppLifecycleState? s) =>
      s == null || s == AppLifecycleState.resumed || s == AppLifecycleState.inactive;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _foreground = _inForeground(WidgetsBinding.instance.lifecycleState);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  // A page covered by another (TickerMode off) is not being looked at either.
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _showing = TickerMode.valuesOf(context).enabled;
    _sync();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = _inForeground(state);
    _sync();
  }

  void _sync() => ref.read(systemHealthProvider.notifier).setVisible(_foreground && _showing);

  @override
  Widget build(BuildContext context) {
    final s = ref.watch(systemHealthProvider);
    final controller = ref.read(systemHealthProvider.notifier);
    // People by login: the failures name them by id, iam-svc names them once.
    ref.listen(systemHealthProvider.select((s) => staffIdsKey(s.failureUserIds)), (_, key) {
      if (key.isNotEmpty) ref.read(staffNameCacheProvider.notifier).resolve(key.split(','));
    });
    final logins = ref.watch(staffNameCacheProvider);
    if (s.refusal != null) return _Refused(s.refusal!);

    final gutter = context.pageGutter;
    return LayoutBuilder(builder: (context, box) {
      final wc = AppBreakpoints.classOf(box.maxWidth);
      final failure = s.summaryError ?? s.failuresError ?? s.waitingError;
      return ListView(
        padding: EdgeInsetsDirectional.only(bottom: gutter),
        children: [
          ContentBounds(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                PageHeader(
                  title: 'System health',
                  subtitle: 'How the system is doing right now: requests, failures and work waiting '
                      'for a person. It updates by itself every few seconds.',
                  actions: [
                    // It refreshes itself; a phone's header would give this a row
                    // of its own, so only a mouse or a wider window is offered it.
                    if (pointerFirst || wc != WindowClass.compact)
                      IconButton(
                        key: const Key('health-refresh'),
                        icon: const Icon(Icons.refresh),
                        tooltip: 'Refresh now',
                        onPressed: controller.refresh,
                      ),
                  ],
                ),
                Padding(
                  padding: EdgeInsetsDirectional.symmetric(horizontal: gutter),
                  child: !s.settled
                      ? const _Loading()
                      : s.nothingLoaded
                          ? ErrorView(
                              key: const Key('health-load-failed'),
                              message: friendlyError(failure!, fallback: "Couldn't load the system's health."),
                              onRetry: controller.refresh,
                            )
                          : Column(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [
                                _UpdatedNote(s),
                                const SizedBox(height: AppSpacing.lg),
                                _CountersSection(state: s, wc: wc, onRetry: controller.refresh),
                                const SizedBox(height: AppSpacing.xl),
                                _FailuresSection(state: s, wc: wc, logins: logins),
                                const SizedBox(height: AppSpacing.xl),
                                _WaitingSection(state: s, wc: wc, onRetry: controller.refresh),
                              ],
                            ),
                ),
              ],
            ),
          ),
        ],
      );
    });
  }
}

// ── pieces shared by the sections ───────────────────────────────────────────

class _Loading extends StatelessWidget {
  const _Loading();

  @override
  Widget build(BuildContext context) {
    return const Skeleton(
      label: 'Loading system health',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SkeletonBlock(height: 112, borderRadius: AppRadius.card),
          SizedBox(height: AppSpacing.md),
          SkeletonBlock(height: 112, borderRadius: AppRadius.card),
          SizedBox(height: AppSpacing.md),
          SkeletonBlock(height: 112, borderRadius: AppRadius.card),
          SizedBox(height: AppSpacing.xl),
          SkeletonLine(),
          SizedBox(height: AppSpacing.sm),
          SkeletonLine(widthFactor: 0.8),
          SizedBox(height: AppSpacing.sm),
          SkeletonLine(widthFactor: 0.9),
        ],
      ),
    );
  }
}

/// When the figures are from, said quietly; and, when the last round failed
/// in any part, that what is shown is what was last read.
class _UpdatedNote extends StatelessWidget {
  const _UpdatedNote(this.state);

  final SystemHealthState state;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final at = state.updatedAt;
    final time = at == null ? '' : AppFormat.timeSeconds(at.toIso8601String());
    final failed = state.refreshFailed;
    final text = failed
        ? (at == null
            ? "Couldn't refresh everything."
            : "Couldn't refresh. Showing the figures from $time.")
        : (at == null ? '' : 'Updated $time');
    if (text.isEmpty) return const SizedBox.shrink();
    final note = Row(
      children: [
        Icon(
          failed ? Icons.sync_problem_outlined : Icons.check_circle_outline,
          size: 16,
          color: theme.colorScheme.onSurfaceVariant,
        ),
        const SizedBox(width: AppSpacing.xs),
        Flexible(
          child: Text(
            text,
            style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ),
      ],
    );
    // A refresh that failed is worth a screen reader's word; one that went
    // well every few seconds is not.
    return failed
        ? Semantics(liveRegion: true, child: KeyedSubtree(key: const Key('health-refresh-failed'), child: note))
        : KeyedSubtree(key: const Key('health-updated'), child: note);
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, this.subtitle, this.trailing, required this.child});

  final String title;
  final String? subtitle;
  final Widget? trailing;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: AppSpacing.md,
          runSpacing: AppSpacing.xs,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Semantics(header: true, child: Text(title, style: theme.textTheme.titleLarge)),
            ?trailing,
          ],
        ),
        if (subtitle != null) ...[
          const SizedBox(height: AppSpacing.xs),
          Text(
            subtitle!,
            style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ],
        const SizedBox(height: AppSpacing.md),
        child,
      ],
    );
  }
}

/// Children side by side in [columns] equal columns, wrapping to new rows.
class _Grid extends StatelessWidget {
  const _Grid({required this.columns, required this.children});

  final int columns;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, box) {
      const gap = AppSpacing.md;
      final width = ((box.maxWidth - gap * (columns - 1)) / columns).floorToDouble();
      return Wrap(
        spacing: gap,
        runSpacing: gap,
        children: [for (final c in children) SizedBox(width: width, child: c)],
      );
    });
  }
}

class _InlineError extends StatelessWidget {
  const _InlineError({super.key, required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: AppSpacing.cardPadding,
        child: Row(
          children: [
            Icon(Icons.error_outline, color: cs.error),
            const SizedBox(width: AppSpacing.md),
            Expanded(child: Text(message)),
            const SizedBox(width: AppSpacing.sm),
            TextButton(onPressed: onRetry, child: const Text('Try again')),
          ],
        ),
      ),
    );
  }
}

class _Unavailable extends StatelessWidget {
  const _Unavailable({super.key});

  @override
  Widget build(BuildContext context) {
    return const Card(
      child: EmptyState(
        icon: Icons.cloud_off_outlined,
        title: 'Live figures are unavailable right now',
        message: 'The request counters could not be read. Requests are not affected, '
            'and the figures come back on their own.',
      ),
    );
  }
}

class _Figure extends StatelessWidget {
  const _Figure(this.label, this.value, {this.strong = false, this.color});

  final String label;
  final String value;
  final bool strong;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsetsDirectional.only(top: AppSpacing.xs),
      child: Row(
        children: [
          Expanded(
            child: Text(
              label,
              style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
          const SizedBox(width: AppSpacing.sm),
          Text(
            value,
            style: theme.textTheme.bodyMedium?.copyWith(
              fontWeight: strong ? FontWeight.w700 : FontWeight.w600,
              color: color,
            ),
          ),
        ],
      ),
    );
  }
}

// ── live counters ───────────────────────────────────────────────────────────

class _CountersSection extends StatelessWidget {
  const _CountersSection({required this.state, required this.wc, required this.onRetry});

  final SystemHealthState state;
  final WindowClass wc;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final summary = state.summary;
    if (summary == null) {
      return _Section(
        title: 'Requests',
        child: _InlineError(
          key: const Key('health-counters-error'),
          message: friendlyError(state.summaryError ?? '', fallback: "Couldn't load the request counts."),
          onRetry: onRetry,
        ),
      );
    }
    if (!summary.available) {
      return const _Section(title: 'Requests', child: _Unavailable(key: Key('health-unavailable')));
    }
    final theme = Theme.of(context);
    final verdict = healthVerdict(summary.last5Minutes);
    final rate = summary.last5Minutes.rate;
    final judged = verdict == HealthVerdict.noTraffic
        ? 'No requests in the last 5 minutes.'
        : 'Judged on the last 5 minutes: ${AppFormat.percent(rate ?? 0)} of requests failed.';
    return _Section(
      title: 'Requests',
      trailing: StatusBadge(verdict.label, key: const Key('health-verdict'), tone: verdict.tone),
      subtitle: judged,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _Grid(
            columns: wc == WindowClass.compact ? 1 : 3,
            children: [
              _WindowTile(
                key: const Key('health-window-last5Minutes'),
                title: 'Last 5 minutes',
                window: summary.last5Minutes,
              ),
              _WindowTile(
                key: const Key('health-window-lastHour'),
                title: 'Last hour',
                window: summary.lastHour,
              ),
              _WindowTile(
                key: const Key('health-window-last24Hours'),
                title: 'Last 24 hours',
                window: summary.last24Hours,
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.lg),
          _Trends(summary: summary, wc: wc),
          const SizedBox(height: AppSpacing.lg),
          _Areas(groups: summary.byGroup),
          if (summary.droppedSinceStart > 0)
            Padding(
              padding: const EdgeInsetsDirectional.only(top: AppSpacing.sm),
              child: Text(
                '${AppFormat.count(summary.droppedSinceStart)} successful requests were not counted '
                'since the gateway started, to keep it quick. Failures are always counted.',
                style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
              ),
            ),
        ],
      ),
    );
  }
}

class _WindowTile extends StatelessWidget {
  const _WindowTile({super.key, required this.title, required this.window});

  final String title;
  final HealthWindow window;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final rate = window.rate;
    final rateText = rate == null ? '—' : AppFormat.percent(rate);
    return Semantics(
      container: true,
      excludeSemantics: true,
      label: '$title: ${AppFormat.count(window.total)} requests, ${AppFormat.count(window.failed)} failed, '
          '${rate == null ? 'no failure rate yet' : 'failure rate $rateText'}',
      child: Card(
        child: Padding(
          padding: AppSpacing.cardPadding,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: theme.textTheme.titleSmall?.copyWith(color: cs.onSurfaceVariant)),
              const SizedBox(height: AppSpacing.xs),
              Wrap(
                spacing: AppSpacing.xs,
                crossAxisAlignment: WrapCrossAlignment.end,
                children: [
                  Text(AppFormat.count(window.total), style: theme.textTheme.headlineSmall),
                  Text(
                    'requests',
                    style: theme.textTheme.bodyMedium?.copyWith(color: cs.onSurfaceVariant),
                  ),
                ],
              ),
              const SizedBox(height: AppSpacing.sm),
              _Figure('Failure rate', rateText, strong: true),
              _Figure('Succeeded', AppFormat.count(window.succeeded)),
              _Figure(
                'Failed',
                AppFormat.count(window.failed),
                color: window.failed > 0 ? cs.error : null,
              ),
              _Figure('Not accepted', AppFormat.count(window.clientErrors)),
            ],
          ),
        ),
      ),
    );
  }
}

/// The per-minute and per-hour bar strips, side by side where there is room.
class _Trends extends StatelessWidget {
  const _Trends({required this.summary, required this.wc});

  final HealthSummary summary;
  final WindowClass wc;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final minute = _BarStrip(
      key: const Key('health-sparkline-minute'),
      title: 'Requests each minute',
      says: 'Requests each minute over the last hour',
      unit: 'minute',
      startLabel: 'An hour ago',
      points: summary.perMinute,
    );
    final hour = _BarStrip(
      key: const Key('health-sparkline-hour'),
      title: 'Requests each hour',
      says: 'Requests each hour over the last 24 hours',
      unit: 'hour',
      startLabel: 'A day ago',
      points: summary.perHour,
    );
    return Card(
      child: Padding(
        padding: AppSpacing.cardPadding,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (wc >= WindowClass.expanded)
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(child: minute),
                  const SizedBox(width: AppSpacing.xl),
                  Expanded(child: hour),
                ],
              )
            else ...[
              minute,
              const SizedBox(height: AppSpacing.lg),
              hour,
            ],
            const SizedBox(height: AppSpacing.md),
            Wrap(
              spacing: AppSpacing.lg,
              runSpacing: AppSpacing.xs,
              children: [
                _Legend(color: context.status.success, label: 'Succeeded'),
                _Legend(color: cs.error, label: 'Failed'),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _Legend extends StatelessWidget {
  const _Legend({required this.color, required this.label});

  final Color color;
  final String label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        ExcludeSemantics(child: Container(width: 10, height: 10, color: color)),
        const SizedBox(width: AppSpacing.xs),
        Text(label, style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
      ],
    );
  }
}

class _BarStrip extends StatelessWidget {
  const _BarStrip({
    super.key,
    required this.title,
    required this.says,
    required this.unit,
    required this.startLabel,
    required this.points,
  });

  final String title;
  final String says;
  final String unit;
  final String startLabel;
  final List<HealthPoint> points;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final quiet = theme.textTheme.bodySmall?.copyWith(color: cs.onSurfaceVariant);
    final total = points.fold(0, (a, p) => a + p.total);
    final failed = points.fold(0, (a, p) => a + p.failed);
    final peak = points.fold(0, (a, p) => math.max(a, p.total));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Wrap(
          alignment: WrapAlignment.spaceBetween,
          spacing: AppSpacing.md,
          runSpacing: AppSpacing.xs,
          children: [
            Text(title, style: theme.textTheme.titleSmall),
            Text('Busiest $unit: ${AppFormat.count(peak)}', style: quiet),
          ],
        ),
        const SizedBox(height: AppSpacing.sm),
        Semantics(
          image: true,
          label: '$says: ${AppFormat.count(total)} in all, ${AppFormat.count(failed)} failed. '
              'Busiest $unit: ${AppFormat.count(peak)}.',
          excludeSemantics: true,
          child: SizedBox(
            height: 56,
            child: CustomPaint(
              size: Size.infinite,
              painter: _BarsPainter(
                points: points,
                ok: context.status.success,
                failed: cs.error,
                base: cs.outlineVariant,
              ),
            ),
          ),
        ),
        const SizedBox(height: AppSpacing.xs),
        Row(
          children: [
            Expanded(child: Text(startLabel, style: quiet)),
            Text('Now', style: quiet),
          ],
        ),
      ],
    );
  }
}

/// Bars, oldest at the left, each as tall as its requests against the busiest
/// slot, the failed part in its own colour at the foot. A time axis reads left
/// to right whatever the language.
class _BarsPainter extends CustomPainter {
  _BarsPainter({required this.points, required this.ok, required this.failed, required this.base});

  final List<HealthPoint> points;
  final Color ok;
  final Color failed;
  final Color base;

  @override
  void paint(Canvas canvas, Size size) {
    if (points.isEmpty) return;
    final floor = size.height - 1;
    canvas.drawLine(Offset(0, floor + 0.5), Offset(size.width, floor + 0.5), Paint()..color = base);
    final peak = points.fold(0, (a, p) => math.max(a, p.total));
    if (peak == 0) return;
    final slot = size.width / points.length;
    final bar = math.max(1.0, slot * 0.7);
    final okPaint = Paint()..color = ok;
    final failedPaint = Paint()..color = failed;
    for (var i = 0; i < points.length; i++) {
      final p = points[i];
      if (p.total == 0) continue;
      final x = i * slot + (slot - bar) / 2;
      final height = math.max(2.0, p.total / peak * floor);
      final failedHeight = p.failed == 0 ? 0.0 : math.min(height, math.max(2.0, p.failed / peak * floor));
      canvas.drawRect(Rect.fromLTWH(x, floor - height, bar, height - failedHeight), okPaint);
      if (failedHeight > 0) {
        canvas.drawRect(Rect.fromLTWH(x, floor - failedHeight, bar, failedHeight), failedPaint);
      }
    }
  }

  @override
  bool shouldRepaint(_BarsPainter old) =>
      old.points != points || old.ok != ok || old.failed != failed || old.base != base;
}

class _Areas extends StatelessWidget {
  const _Areas({required this.groups});

  final List<HealthGroup> groups;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    return Card(
      child: Padding(
        padding: AppSpacing.cardPadding,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Busiest areas, last hour', style: theme.textTheme.titleSmall),
            const SizedBox(height: AppSpacing.sm),
            if (groups.isEmpty)
              Text(
                'No requests in the last hour.',
                style: theme.textTheme.bodyMedium?.copyWith(color: cs.onSurfaceVariant),
              )
            else
              for (var i = 0; i < groups.length; i++) ...[
                if (i > 0) const Divider(height: AppSpacing.lg),
                Wrap(
                  key: Key('health-group-${groups[i].group}'),
                  alignment: WrapAlignment.spaceBetween,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  spacing: AppSpacing.md,
                  runSpacing: AppSpacing.xs,
                  children: [
                    Text(routeGroupLabel(groups[i].group)),
                    Text.rich(
                      TextSpan(
                        children: [
                          TextSpan(
                            text: '${AppFormat.count(groups[i].total)} requests',
                            style: const TextStyle(fontWeight: FontWeight.w600),
                          ),
                          const TextSpan(text: ' · '),
                          TextSpan(
                            text: '${AppFormat.count(groups[i].failed)} failed',
                            style: TextStyle(color: groups[i].failed > 0 ? cs.error : cs.onSurfaceVariant),
                          ),
                        ],
                      ),
                      style: theme.textTheme.bodyMedium,
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

// ── failures ────────────────────────────────────────────────────────────────

/// Who sent a request, by login; a short reference only while the name is not
/// known; nobody when no one was signed in.
String _who(FailureEntry f, Map<String, String> logins) {
  final id = f.userId;
  if (id == null || id.isEmpty) return 'No one signed in';
  return staffDisplayName(id, logins);
}

String _when(FailureEntry f) => f.at == null ? 'Time unknown' : AppFormat.dateTime(f.at!.toIso8601String());

/// The status and code as they are, small, for the person quoting them.
String _raw(FailureEntry f) => [
      if (f.status != null) '${f.status}',
      if ((f.code ?? '').isNotEmpty) f.code!,
    ].join(' · ');

class _FailuresSection extends ConsumerWidget {
  const _FailuresSection({required this.state, required this.wc, required this.logins});

  final SystemHealthState state;
  final WindowClass wc;
  final Map<String, String> logins;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final controller = ref.read(systemHealthProvider.notifier);
    final theme = Theme.of(context);
    final summary = state.summary;
    final Widget body;
    if (summary != null && !summary.available) {
      body = const _Unavailable(key: Key('failures-unavailable'));
    } else if (!state.failuresRead) {
      body = _InlineError(
        key: const Key('failures-error'),
        message: friendlyError(state.failuresError ?? '', fallback: "Couldn't load the failures."),
        onRetry: controller.refresh,
      );
    } else if (state.failures.isEmpty) {
      body = const Card(
        child: EmptyState(
          icon: Icons.check_circle_outline,
          title: 'No failures in the last 24 hours',
          message: 'Nothing failed or was turned away in the last day.',
        ),
      );
    } else {
      body = Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Card(
            child: Column(
              children: [
                if (wc >= WindowClass.expanded) const _FailuresHeading(),
                for (var i = 0; i < state.failures.length; i++) ...[
                  if (i > 0 || wc >= WindowClass.expanded) const Divider(height: 1),
                  _FailureRow(
                    key: Key('failure-${state.failures[i].requestId}'),
                    entry: state.failures[i],
                    who: _who(state.failures[i], logins),
                    wide: wc >= WindowClass.expanded,
                    onTap: () => _showFailure(context, state.failures[i], _who(state.failures[i], logins)),
                  ),
                ],
              ],
            ),
          ),
          if (state.loadMoreError != null)
            Padding(
              padding: const EdgeInsetsDirectional.only(top: AppSpacing.sm),
              child: Text(
                friendlyError(state.loadMoreError!, fallback: "Couldn't load older failures."),
                style: TextStyle(color: theme.colorScheme.error),
              ),
            ),
          if (state.moreCursor != null)
            Padding(
              padding: const EdgeInsetsDirectional.only(top: AppSpacing.md),
              child: Center(
                child: state.loadingMore
                    ? const CircularProgressIndicator()
                    : TextButton.icon(
                        key: const Key('failures-load-more'),
                        onPressed: controller.loadMoreFailures,
                        icon: const Icon(Icons.expand_more),
                        label: const Text('Load more'),
                      ),
              ),
            ),
        ],
      );
    }
    return _Section(
      title: 'Failures',
      subtitle: 'Requests that failed or were turned away in the last 24 hours, newest first. '
          'Tap one for the request id to quote.',
      child: body,
    );
  }
}

// The wide list's columns, shared by its heading and its rows so they line up.
const double _whenColumn = 148;
const double _areaColumn = 120;
const double _badgeColumn = 80;

class _FailuresHeading extends StatelessWidget {
  const _FailuresHeading();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final style = theme.textTheme.labelMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    return Padding(
      padding: const EdgeInsets.fromLTRB(AppSpacing.lg, AppSpacing.md, AppSpacing.lg, AppSpacing.sm),
      child: Row(
        children: [
          SizedBox(width: _whenColumn, child: Text('When', style: style)),
          const SizedBox(width: AppSpacing.md),
          Expanded(flex: 3, child: Text('What happened', style: style)),
          const SizedBox(width: AppSpacing.md),
          SizedBox(width: _areaColumn, child: Text('Area', style: style)),
          const SizedBox(width: AppSpacing.md),
          Expanded(flex: 2, child: Text('Who', style: style)),
          const SizedBox(width: AppSpacing.md),
          const SizedBox(width: _badgeColumn),
        ],
      ),
    );
  }
}

class _FailureRow extends StatelessWidget {
  const _FailureRow({
    super.key,
    required this.entry,
    required this.who,
    required this.wide,
    required this.onTap,
  });

  final FailureEntry entry;
  final String who;
  final bool wide;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final quiet = theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    final reason = Text(failureReason(entry.status, entry.code), style: theme.textTheme.titleSmall);
    final raw = Text(_raw(entry), style: quiet);
    final badge = StatusBadge(failureKindLabel(entry.status), tone: failureKindTone(entry.status));
    final area = routeGroupLabel(entry.group);
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg, vertical: AppSpacing.md),
        child: wide
            ? Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(width: _whenColumn, child: Text(_when(entry), style: theme.textTheme.bodyMedium)),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    flex: 3,
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [reason, raw]),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  SizedBox(width: _areaColumn, child: Text(area, style: theme.textTheme.bodyMedium)),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(flex: 2, child: Text(who, style: theme.textTheme.bodyMedium)),
                  const SizedBox(width: AppSpacing.md),
                  SizedBox(
                    width: _badgeColumn,
                    child: Align(alignment: AlignmentDirectional.centerEnd, child: badge),
                  ),
                ],
              )
            : Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(child: reason),
                      const SizedBox(width: AppSpacing.sm),
                      badge,
                    ],
                  ),
                  raw,
                  const SizedBox(height: AppSpacing.xs),
                  Text('$area · $who · ${_when(entry)}', style: quiet),
                ],
              ),
      ),
    );
  }
}

Future<void> _showFailure(BuildContext context, FailureEntry f, String who) => showAdaptiveSheet<void>(
      context: context,
      title: '${failureKindLabel(f.status)} request',
      builder: (_) => _FailureDetail(entry: f, who: who),
    );

class _FailureDetail extends StatelessWidget {
  const _FailureDetail({required this.entry, required this.who});

  final FailureEntry entry;
  final String who;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final code = entry.code ?? '';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(failureReason(entry.status, entry.code), style: theme.textTheme.titleMedium),
        const SizedBox(height: AppSpacing.md),
        _Detail('When', Text(_when(entry))),
        _Detail(
          'Request id',
          Wrap(
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: AppSpacing.sm,
            children: [
              SelectableText(entry.requestId),
              _CopyId(entry.requestId),
            ],
          ),
        ),
        _Detail('Who', Text(who)),
        _Detail('Area', Text(routeGroupLabel(entry.group))),
        _Detail('Method', Text(entry.method)),
        _Detail('Route', SelectableText(entry.routePattern)),
        if (entry.status != null) _Detail('Status', Text('${entry.status}')),
        if (code.isNotEmpty) _Detail('Code', SelectableText(code)),
        if (entry.ms != null) _Detail('Time taken', Text('${AppFormat.count(entry.ms!)} ms')),
      ],
    );
  }
}

class _Detail extends StatelessWidget {
  const _Detail(this.label, this.value);

  final String label;
  final Widget value;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsetsDirectional.only(bottom: AppSpacing.md),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: theme.textTheme.labelMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          const SizedBox(height: 2),
          DefaultTextStyle.merge(style: theme.textTheme.bodyMedium, child: value),
        ],
      ),
    );
  }
}

class _CopyId extends StatefulWidget {
  const _CopyId(this.id);

  final String id;

  @override
  State<_CopyId> createState() => _CopyIdState();
}

class _CopyIdState extends State<_CopyId> {
  bool _copied = false;

  @override
  Widget build(BuildContext context) {
    return TextButton.icon(
      key: const Key('failure-copy-request-id'),
      onPressed: () async {
        await Clipboard.setData(ClipboardData(text: widget.id));
        if (mounted) setState(() => _copied = true);
      },
      icon: Icon(_copied ? Icons.check : Icons.copy_outlined, size: 18),
      label: Text(_copied ? 'Request id copied' : 'Copy request id'),
    );
  }
}

// ── waiting for a person ────────────────────────────────────────────────────

class _WaitingSection extends StatelessWidget {
  const _WaitingSection({required this.state, required this.wc, required this.onRetry});

  final SystemHealthState state;
  final WindowClass wc;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final waiting = state.waiting;
    return _Section(
      title: 'Waiting for a person',
      subtitle: 'Work that stays where it is until someone decides it. A tile opens the page that settles it.',
      child: waiting == null
          ? _InlineError(
              key: const Key('waiting-error'),
              message: friendlyError(state.waitingError ?? '', fallback: "Couldn't load the waiting work."),
              onRetry: onRetry,
            )
          : waiting.items.isEmpty
              ? const Card(
                  child: EmptyState(
                    icon: Icons.task_alt_outlined,
                    title: 'Nothing is waiting',
                    message: 'No queue has anything for a person to decide.',
                  ),
                )
              : _Grid(
                  columns: switch (wc) {
                    WindowClass.compact => 1,
                    WindowClass.medium => 2,
                    _ => 3,
                  },
                  children: [
                    for (final item in waiting.items) _WaitingTile(key: Key('waiting-${item.kind}'), item: item),
                  ],
                ),
    );
  }
}

/// A queue's count as it is written on its tile. One the server counted only up
/// to its cap reads as the count it sent and a plus (`1,000+`): it is "this
/// many or more", never an exact figure.
String _waitingCountText(WaitingItem item, int count) =>
    item.capped ? '${AppFormat.count(count)}+' : AppFormat.count(count);

/// The same count as a screen reader says it: words, not a sign.
String _waitingCountSpoken(WaitingItem item, int count) =>
    item.capped ? '${AppFormat.count(count)} or more waiting' : '${AppFormat.count(count)} waiting';

class _WaitingTile extends StatelessWidget {
  const _WaitingTile({super.key, required this.item});

  final WaitingItem item;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final route = waitingWorkRoute(item.kind, item.opens);
    final count = item.count;
    void open() => context.go(route!);
    return Semantics(
      container: true,
      excludeSemantics: true,
      button: route != null,
      onTap: route == null ? null : open,
      label: '${item.label}: ${count == null ? "couldn't check" : _waitingCountSpoken(item, count)}'
          '${item.note == null ? '' : '. ${item.note}'}',
      child: Card(
        child: InkWell(
          onTap: route == null ? null : open,
          child: Padding(
            padding: AppSpacing.cardPadding,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(item.label, style: theme.textTheme.titleSmall),
                      const SizedBox(height: AppSpacing.xs),
                      if (count != null)
                        Text(_waitingCountText(item, count), style: theme.textTheme.headlineSmall)
                      else
                        Row(
                          children: [
                            Icon(Icons.help_outline, size: 18, color: cs.onSurfaceVariant),
                            const SizedBox(width: AppSpacing.xs),
                            Flexible(
                              child: Text(
                                "Couldn't check",
                                style: theme.textTheme.titleMedium?.copyWith(color: cs.onSurfaceVariant),
                              ),
                            ),
                          ],
                        ),
                      if (item.note != null) ...[
                        const SizedBox(height: AppSpacing.xs),
                        Text(
                          item.note!,
                          style: theme.textTheme.bodySmall?.copyWith(color: cs.onSurfaceVariant),
                        ),
                      ],
                    ],
                  ),
                ),
                if (route != null) ...[
                  const SizedBox(width: AppSpacing.sm),
                  Icon(Icons.chevron_right, color: cs.onSurfaceVariant),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
