import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import 'customer_providers.dart';

/// A text message to a customer (13.7), through notification-svc's SMS channel
/// to the E.164 number on their record. Transactional by default; marketing
/// is sent as marketing so the server checks the customer's recorded SMS
/// consent and refuses without it — the dialog never pretends otherwise.
class SendTextDialog extends ConsumerStatefulWidget {
  const SendTextDialog({super.key, required this.customer});
  final Customer customer;

  @override
  ConsumerState<SendTextDialog> createState() => _SendTextDialogState();
}

class _SendTextDialogState extends ConsumerState<SendTextDialog> {
  static const maxBody = 1600;
  final _body = TextEditingController();
  bool _marketing = false;
  bool _sending = false;
  String? _error;

  @override
  void dispose() {
    _body.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    final body = _body.text.trim();
    if (body.isEmpty) {
      setState(() => _error = 'Type the message first.');
      return;
    }
    setState(() {
      _sending = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.notification}/notifications/send',
        data: {
          'channel': 'SMS',
          'recipient': widget.customer.phone!.trim(),
          'subject': _marketing ? 'Offer' : 'Message from the shop',
          'body': body,
          'type': _marketing ? 'MARKETING_SMS' : 'CUSTOMER_SMS',
          'customerId': widget.customer.id,
          'category': _marketing ? 'MARKETING' : 'TRANSACTIONAL',
        },
      );
      if (!mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Text sent to ${widget.customer.phone}.')));
    } catch (e) {
      setState(() {
        _sending = false;
        _error = friendlyError(e, fallback: 'Could not send the text.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text('Text ${widget.customer.fullName.isEmpty ? widget.customer.phone : widget.customer.fullName}'),
      content: SizedBox(
        width: 440,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('To ${widget.customer.phone}'),
            const SizedBox(height: 8),
            TextField(
              key: const Key('send-text-body'),
              controller: _body,
              maxLength: maxBody,
              maxLines: 4,
              autofocus: true,
              decoration: const InputDecoration(labelText: 'Message'),
            ),
            SwitchListTile.adaptive(
              key: const Key('send-text-marketing'),
              contentPadding: EdgeInsets.zero,
              value: _marketing,
              onChanged: (v) => setState(() => _marketing = v),
              title: const Text('This is marketing'),
              subtitle: const Text(
                  'Sent only with the customer\'s recorded consent to texts; refused otherwise.'),
            ),
            if (_error != null) Text(_error!, style: TextStyle(color: cs.error)),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          key: const Key('send-text-send'),
          onPressed: _sending ? null : _send,
          child: const Text('Send'),
        ),
      ],
    );
  }
}
