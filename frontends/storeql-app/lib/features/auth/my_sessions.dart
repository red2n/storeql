import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/status_badge.dart';

/// One place a login is signed in (iam-svc `GET /auth/sessions`): the device,
/// the network it came from, when it began and was last renewed.
class MySession {
  final String id;
  final String? deviceLabel;
  final String? network;
  final String? startedAt;
  final String? lastUsedAt;
  final String? authMethod;

  /// The session making this request, marked by the server from the
  /// `X-Session-Id` header the app sends.
  final bool current;

  const MySession({
    required this.id,
    this.deviceLabel,
    this.network,
    this.startedAt,
    this.lastUsedAt,
    this.authMethod,
    this.current = false,
  });

  factory MySession.fromJson(Map<String, dynamic> j) => MySession(
        id: j['id'] as String? ?? '',
        deviceLabel: j['deviceLabel'] as String?,
        network: j['network'] as String?,
        startedAt: j['startedAt'] as String?,
        lastUsedAt: j['lastUsedAt'] as String?,
        authMethod: j['authMethod'] as String?,
        current: j['current'] as bool? ?? false,
      );
}

/// The `sid` claim of an access token: which session of the login it belongs
/// to. Read without verifying (the server judges the token); null when the
/// token carries none or cannot be read.
String? sessionIdOfToken(String? token) {
  if (token == null) return null;
  try {
    final parts = token.split('.');
    if (parts.length != 3) return null;
    final claims = jsonDecode(
        utf8.decode(base64Url.decode(base64Url.normalize(parts[1])))) as Map;
    final sid = claims['sid'];
    return sid is String && sid.isNotEmpty ? sid : null;
  } catch (_) {
    return null;
  }
}

/// How a session was signed in, in words. Null for an old session that says
/// nothing, or a method this app has no words for yet.
String? signInMethodLabel(String? method) => switch (method?.toLowerCase()) {
      'pwd' => 'Password',
      'pwd+otp' => 'Password and a code',
      'sso' => 'Single sign-on',
      'passkey' => 'Passkey',
      _ => null,
    };

/// Where this login is signed in, with *Sign out* on every other one, beside
/// *Sign out everywhere*. Shared by the staff security screen and the shopper's
/// account: each passes the Dio that carries its own credential and its access
/// token, whose `sid` is sent as `X-Session-Id` so the server can say which
/// session is this device.
class MySessionsCard extends StatefulWidget {
  final Dio dio;
  final String? accessToken;

  const MySessionsCard({super.key, required this.dio, this.accessToken});

  @override
  State<MySessionsCard> createState() => _MySessionsCardState();
}

class _MySessionsCardState extends State<MySessionsCard> {
  List<MySession>? _sessions;
  String? _error;
  bool _loading = true;
  String? _ending;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final sid = sessionIdOfToken(widget.accessToken);
      final resp = await widget.dio.get(
        '/${ApiConstants.iam}/auth/sessions',
        options: sid == null ? null : Options(headers: {'X-Session-Id': sid}),
      );
      final raw = resp.data['data'];
      final rows = [
        for (final e in raw is List ? raw : const [])
          if (e is Map<String, dynamic>) MySession.fromJson(e),
      ];
      if (!mounted) return;
      setState(() {
        _sessions = rows;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: 'Your sessions could not be loaded.');
        _loading = false;
      });
    }
  }

  Future<void> _end(MySession s) async {
    setState(() => _ending = s.id);
    final messenger = ScaffoldMessenger.of(context);
    try {
      await widget.dio.delete('/${ApiConstants.iam}/auth/sessions/${s.id}');
      messenger.showSnackBar(
          SnackBar(content: Text('Signed out of ${s.deviceLabel ?? 'that device'}.')));
    } catch (e) {
      // Already ended elsewhere is what it was asked to be: say so, and refresh.
      messenger.showSnackBar(SnackBar(
        content: Text(apiErrorCode(e) == 'SESSION_NOT_FOUND'
            ? 'That session had already ended.'
            : friendlyError(e, fallback: 'Could not sign that session out.')),
      ));
    }
    if (!mounted) return;
    setState(() => _ending = null);
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final sessions = _sessions;
    return Card(
      key: const Key('my-sessions'),
      child: Padding(
        padding: AppSpacing.cardPadding,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Where you are signed in', style: theme.textTheme.titleMedium),
            const SizedBox(height: AppSpacing.xs),
            Text(
              'Sign out of any device you do not recognise. A device you sign out may keep working for a few minutes.',
              style: theme.textTheme.bodySmall?.copyWith(color: cs.onSurfaceVariant),
            ),
            const SizedBox(height: AppSpacing.md),
            if (_loading && sessions == null)
              const LinearProgressIndicator()
            else if (_error != null && sessions == null)
              Row(children: [
                Expanded(child: Text(_error!, style: TextStyle(color: cs.error))),
                TextButton(
                  key: const Key('my-sessions-retry'),
                  onPressed: _load,
                  child: const Text('Try again'),
                ),
              ])
            else if (sessions != null && sessions.isEmpty)
              Text('No other sessions.',
                  style: theme.textTheme.bodyMedium?.copyWith(color: cs.onSurfaceVariant))
            else if (sessions != null)
              for (final s in sessions) _row(context, s),
          ],
        ),
      ),
    );
  }

  Widget _row(BuildContext context, MySession s) {
    final method = signInMethodLabel(s.authMethod);
    final last = s.lastUsedAt;
    final started = s.startedAt;
    return ListTile(
      key: Key('my-session-${s.id}'),
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.devices_outlined),
      title: Wrap(
        spacing: AppSpacing.sm,
        runSpacing: AppSpacing.xs,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          Text(s.deviceLabel == null || s.deviceLabel!.isEmpty
              ? 'Unknown device'
              : s.deviceLabel!),
          if (s.current) const StatusBadge('This device', tone: StatusTone.info),
        ],
      ),
      subtitle: Text([
        if (s.network != null && s.network!.isNotEmpty) 'Network ${s.network}',
        if (started != null && started.isNotEmpty)
          'signed in ${AppFormat.dateTime(started)}',
        if (last != null && last.isNotEmpty) 'last used ${AppFormat.dateTime(last)}',
        ?method,
      ].join(' · ')),
      trailing: s.current
          ? null
          : OutlinedButton(
              key: Key('my-session-end-${s.id}'),
              onPressed: _ending != null ? null : () => _end(s),
              child: const Text('Sign out'),
            ),
    );
  }
}
