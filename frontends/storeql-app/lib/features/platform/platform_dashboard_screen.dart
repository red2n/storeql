import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/status_badge.dart';

// ---------------------------------------------------------------------------
// The platform operator's home page.
//
// It says only what it has read. The gateway's health is asked for, not
// assumed: a console that calls every service running whatever their state is
// worse than one that says nothing. The endpoints a developer calls live in
// docs/API-GUIDE.md, not on an operator's page.
// ---------------------------------------------------------------------------

/// What the gateway's own health check said.
enum GatewayHealth {
  /// It answered with a health document saying UP.
  healthy,

  /// It answered, and said DOWN.
  unhealthy,

  /// Nothing answered, or the answer was a failure (a 502 from a proxy in front of it).
  unreachable,

  /// Something answered, successfully, but not with a health document: the web host's own page.
  /// A web build that reaches the API through its own origin asks that origin for `/health`, and a
  /// web server that does not pass the path on to the gateway answers it with the app. That says
  /// nothing either way about the gateway, so it is not reported as a fault.
  unreadable,
}

/// The gateway's public health endpoint: the API base without its `/api` prefix, then `/health`
/// (`http://localhost:8090/api` → `http://localhost:8090/health`). A relative base (`/api`, a web
/// build behind one origin) resolves against the page's own address.
Uri gatewayHealthUri([String apiBase = ApiConstants.baseUrl]) {
  final base = Uri.base.resolve(apiBase.trim());
  final segments = [...base.pathSegments.where((s) => s.isNotEmpty)];
  if (segments.isNotEmpty && segments.last == 'api') segments.removeLast();
  return base.replace(pathSegments: [...segments, 'health']);
}

/// The Dio the overview asks the gateway's health with. Not the API client's: the probe is public,
/// so it carries no credential, and with no content type a browser sends it as a simple request
/// with no preflight.
final gatewayHealthDioProvider = Provider<Dio>(
  (ref) => Dio(BaseOptions(
    connectTimeout: const Duration(seconds: 5),
    receiveTimeout: const Duration(seconds: 5),
  )),
);

final gatewayHealthProvider = FutureProvider.autoDispose<GatewayHealth>((ref) async {
  final dio = ref.watch(gatewayHealthDioProvider);
  try {
    // Every status is read, whatever the Dio's defaults: a DOWN gateway answers 503 with its document.
    final resp = await dio.getUri<Object?>(
      gatewayHealthUri(),
      options: Options(validateStatus: (_) => true),
    );
    final body = resp.data;
    // Only a MicroProfile health document counts. A web host that answers every path with its
    // page says 200, and that is not the gateway saying it is well.
    final status = body is Map ? body['status'] : null;
    final code = resp.statusCode ?? 0;
    return switch (status) {
      'UP' => GatewayHealth.healthy,
      'DOWN' => GatewayHealth.unhealthy,
      _ when code >= 200 && code < 300 => GatewayHealth.unreadable,
      _ => GatewayHealth.unreachable,
    };
  } on Object {
    return GatewayHealth.unreachable;
  }
});

class PlatformDashboardScreen extends ConsumerWidget {
  const PlatformDashboardScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    final email = auth is AuthAuthenticated
        ? (auth.email ?? 'Platform administrator')
        : 'Platform administrator';
    final cs = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;

    return SingleChildScrollView(
      padding: context.pagePadding,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _Header(email: email),
          const SizedBox(height: AppSpacing.xl),

          LayoutBuilder(builder: (context, constraints) {
            final wide = constraints.maxWidth >= AppBreakpoints.medium;
            final width =
                wide ? (constraints.maxWidth - AppSpacing.lg) / 2 : constraints.maxWidth;
            return Wrap(
              spacing: AppSpacing.lg,
              runSpacing: AppSpacing.lg,
              children: [
                _GatewayHealthCard(width: width),
                _InfoCard(
                  width: width,
                  icon: Icons.business,
                  color: cs.primaryContainer,
                  iconColor: cs.onPrimaryContainer,
                  title: 'Tenant management',
                  subtitle:
                      'Businesses sign themselves up through the onboarding wizard. Follow them in Tenants.',
                ),
              ],
            );
          }),
          const SizedBox(height: AppSpacing.xl),

          Card(
            child: Padding(
              padding: const EdgeInsets.all(AppSpacing.lg),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(Icons.rocket_launch_outlined, color: cs.primary),
                      const SizedBox(width: AppSpacing.sm),
                      Expanded(
                        child: Text(
                          'How tenant onboarding works',
                          style: tt.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: AppSpacing.lg),
                  const _Step(n: 1, text: 'A business owner opens the app and chooses “New here? Create an account.”'),
                  const _Step(n: 2, text: 'They register with their email and password, and start out as a customer.'),
                  const _Step(n: 3, text: 'The onboarding wizard appears straight away: they enter their business name, country and currency.'),
                  const _Step(n: 4, text: 'Next they add their first store (name, address, time zone), and become the business’s owner.'),
                  const _Step(n: 5, text: 'They land on the admin dashboard, where they manage inventory, orders and staff.'),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Who is signed in. Wide, the role badge ends the row; on a phone it goes under the heading, or
/// the avatar, the heading and the badge share 358px and the heading breaks onto two lines.
class _Header extends StatelessWidget {
  final String email;
  const _Header({required this.email});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    return LayoutBuilder(builder: (context, constraints) {
      final compact = AppBreakpoints.classOf(constraints.maxWidth) == WindowClass.compact;
      const badge = StatusBadge(
        'Platform admin',
        key: Key('platform-admin-badge'),
        tone: StatusTone.accent,
        icon: Icons.verified_user,
      );
      return Row(
        crossAxisAlignment: compact ? CrossAxisAlignment.start : CrossAxisAlignment.center,
        children: [
          CircleAvatar(
            backgroundColor: cs.primaryContainer,
            radius: 24,
            child: Icon(Icons.admin_panel_settings, color: cs.onPrimaryContainer),
          ),
          const SizedBox(width: AppSpacing.lg),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Platform overview',
                  // The type scale follows the width, as the page header's does.
                  style: (compact ? tt.headlineSmall : tt.headlineMedium)
                      ?.copyWith(fontWeight: FontWeight.bold),
                ),
                Text(
                  email,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: tt.bodyMedium?.copyWith(color: cs.onSurfaceVariant),
                ),
                if (compact) ...[
                  const SizedBox(height: AppSpacing.sm),
                  badge,
                ],
              ],
            ),
          ),
          if (!compact) ...[
            const SizedBox(width: AppSpacing.lg),
            badge,
          ],
        ],
      );
    });
  }
}

/// The gateway's health as it answered just now — healthy, unhealthy or unreachable — and a way to
/// ask again. It speaks for the gateway only: each service behind it has its own probes.
class _GatewayHealthCard extends ConsumerWidget {
  final double width;
  const _GatewayHealthCard({required this.width});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tt = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final health = ref.watch(gatewayHealthProvider);
    final (label, tone, says) = switch (health) {
      AsyncData(value: GatewayHealth.healthy) => (
          'Healthy',
          StatusTone.success,
          'The gateway answered its health check. Each service behind it reports its own health.',
        ),
      AsyncData(value: GatewayHealth.unhealthy) => (
          'Unhealthy',
          StatusTone.error,
          'The gateway answered, and says one of its checks is failing.',
        ),
      AsyncData(value: GatewayHealth.unreachable) || AsyncError() => (
          'Unreachable',
          StatusTone.error,
          'The gateway’s health check did not answer from here.',
        ),
      AsyncData(value: GatewayHealth.unreadable) => (
          'Not readable here',
          StatusTone.neutral,
          'The web server answered the health check with this app’s own page, so the gateway’s '
              'health cannot be read from this build. Its web server does not pass /health on to '
              'the gateway.',
        ),
      _ => ('Checking…', StatusTone.info, 'Asking the gateway how it is.'),
    };
    return SizedBox(
      width: width,
      child: Card(
        key: const Key('gateway-health'),
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(Icons.health_and_safety_outlined, color: cs.onSurfaceVariant, size: 32),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: Align(
                      alignment: AlignmentDirectional.centerEnd,
                      child: StatusBadge(
                        label,
                        key: const Key('gateway-health-status'),
                        tone: tone,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: AppSpacing.md),
              Text('Gateway', style: tt.titleSmall?.copyWith(fontWeight: FontWeight.bold)),
              const SizedBox(height: AppSpacing.xs),
              Text(says, style: tt.bodySmall),
              const SizedBox(height: AppSpacing.xs),
              Align(
                alignment: AlignmentDirectional.centerEnd,
                child: TextButton.icon(
                  key: const Key('gateway-health-recheck'),
                  onPressed: health.isLoading ? null : () => ref.invalidate(gatewayHealthProvider),
                  icon: const Icon(Icons.refresh),
                  label: const Text('Check again'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _InfoCard extends StatelessWidget {
  final double width;
  final IconData icon;
  final Color color;
  final Color iconColor;
  final String title;
  final String subtitle;

  const _InfoCard({
    required this.width,
    required this.icon,
    required this.color,
    required this.iconColor,
    required this.title,
    required this.subtitle,
  });

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    return SizedBox(
      width: width,
      child: Card(
        color: color,
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(icon, color: iconColor, size: 32),
              const SizedBox(height: AppSpacing.md),
              Text(
                title,
                style: tt.titleSmall?.copyWith(fontWeight: FontWeight.bold, color: iconColor),
              ),
              const SizedBox(height: AppSpacing.xs),
              Text(subtitle, style: tt.bodySmall?.copyWith(color: iconColor)),
            ],
          ),
        ),
      ),
    );
  }
}

class _Step extends StatelessWidget {
  final int n;
  final String text;

  const _Step({required this.n, required this.text});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.xs),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // A number in a disc; it grows with large text rather than clipping it.
          Container(
            constraints: const BoxConstraints(minWidth: 24, minHeight: 24),
            alignment: Alignment.center,
            decoration: BoxDecoration(color: cs.primary, shape: BoxShape.circle),
            child: Text(
              '$n',
              style: tt.labelMedium?.copyWith(color: cs.onPrimary, fontWeight: FontWeight.bold),
            ),
          ),
          const SizedBox(width: AppSpacing.md),
          Expanded(child: Text(text, style: tt.bodyMedium)),
        ],
      ),
    );
  }
}
