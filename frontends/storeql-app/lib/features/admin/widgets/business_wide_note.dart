import 'package:flutter/material.dart';

import '../../../core/auth/auth_state.dart';
import '../../../core/spacing.dart';

/// Whether [auth] is held to particular stores, so the business-wide settings
/// (business profile, new stores, roles, exchange rates, retention, notices …)
/// are refused to it with `BUSINESS_WIDE_ONLY`. An owner and a head-office
/// manager are never held to stores; while the sign-in is unknown nothing is
/// hidden (the server judges).
bool heldToStores(AuthState? auth) => auth is AuthAuthenticated && auth.heldToStores;

/// The line shown in place of a business-wide setting's write controls to a
/// manager held to stores. [message] names the thing where "this" would not be
/// clear (a screen that keeps other controls, a section that is not read).
class BusinessWideNote extends StatelessWidget {
  const BusinessWideNote({super.key = const Key('business-wide-note'), this.message = text});

  static const text = 'Only an owner or a head-office manager changes this.';

  final String message;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(Icons.lock_outline, size: 16, color: cs.onSurfaceVariant),
        const SizedBox(width: AppSpacing.xs),
        Flexible(
          child: Text(message,
              style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12)),
        ),
      ],
    );
  }
}
