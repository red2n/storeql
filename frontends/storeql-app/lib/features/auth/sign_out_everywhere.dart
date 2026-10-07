import 'package:flutter/material.dart';

/// Asks before ending every session of the caller's login
/// (`POST /auth/sessions/revoke-all`), this device's included. True when the
/// person confirmed. Shared by the staff security screen and the shopper's
/// account menu so both say the same thing.
Future<bool> confirmSignOutEverywhere(BuildContext context) async {
  final yes = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('Sign out everywhere?'),
      content: const Text(
        'This ends every session of this login, on every device, this one '
        'included, and you will sign in again here. A device that was already '
        'open may keep working for a few minutes.',
      ),
      actions: [
        TextButton(
          key: const Key('sign-out-everywhere-cancel'),
          onPressed: () => Navigator.pop(ctx, false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('sign-out-everywhere-confirm'),
          style: FilledButton.styleFrom(
            backgroundColor: Theme.of(ctx).colorScheme.error,
            foregroundColor: Theme.of(ctx).colorScheme.onError,
          ),
          onPressed: () => Navigator.pop(ctx, true),
          child: const Text('Sign out everywhere'),
        ),
      ],
    ),
  );
  return yes == true;
}
