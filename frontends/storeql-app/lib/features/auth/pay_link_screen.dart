import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/spacing.dart';

// ---------------------------------------------------------------------------
// The page a dunning notice's pay link opens (21.12).
//
// The business it reaches may already be suspended, and a suspended business
// cannot sign in — so this page has no session, asks for none, and can do one
// thing: pay the one invoice the token in the link names. A token for an
// invoice since paid, or from an older notice than the newest, opens nothing,
// and the page says so rather than pretending.
// ---------------------------------------------------------------------------

/// A tokenless Dio: the page has no session to attach.
final payLinkDioProvider = Provider<Dio>((ref) {
  return Dio(BaseOptions(
    baseUrl: ApiConstants.baseUrl,
    connectTimeout: const Duration(seconds: 8),
    receiveTimeout: const Duration(seconds: 15),
    headers: const {'Content-Type': 'application/json'},
  ));
});

class PayLinkScreen extends ConsumerStatefulWidget {
  final String token;
  const PayLinkScreen({super.key, required this.token});

  @override
  ConsumerState<PayLinkScreen> createState() => _PayLinkScreenState();
}

class _PayLinkScreenState extends ConsumerState<PayLinkScreen> {
  bool _paying = false;
  Map<String, dynamic>? _paid;
  String? _refusal;

  Future<void> _pay() async {
    setState(() {
      _paying = true;
      _refusal = null;
    });
    try {
      final resp = await ref
          .read(payLinkDioProvider)
          .post('/${ApiConstants.tenant}/billing/pay/${Uri.encodeComponent(widget.token)}');
      final data = (resp.data as Map)['data'];
      setState(() => _paid = Map<String, dynamic>.from(data as Map));
    } on DioException catch (e) {
      final body = e.response?.data;
      final code = body is Map ? body['code'] : null;
      setState(() {
        _refusal = code == 'PAY_LINK_INVALID'
            ? 'This link does not open an invoice that can be paid. It may have been paid '
                'already, or a newer notice has replaced it — use the link in the latest one.'
            : 'The payment could not be taken just now. Nothing was charged; try again in a moment.';
      });
    } finally {
      if (mounted) setState(() => _paying = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final paid = _paid;
    // As the sign-in card sits: the page's gutter, a form's width, and a
    // scroll when a phone or large text leaves it too little height.
    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: context.pagePadding,
          child: ContentBounds.form(
          child: Card(
            margin: EdgeInsets.zero,
            child: Padding(
              padding: context.pagePadding,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text('Pay your invoice', style: text.headlineSmall),
                  const SizedBox(height: 8),
                  if (paid == null) ...[
                    Text(
                      'This link came from a payment notice. It pays the one invoice the notice '
                      'is about, in full, and needs no sign-in. If your service was interrupted '
                      'for non-payment, paying brings it back.',
                      style: text.bodyMedium,
                    ),
                    const SizedBox(height: 20),
                    if (_refusal != null) ...[
                      Text(
                        _refusal!,
                        key: const Key('pay-refusal'),
                        style: text.bodyMedium?.copyWith(color: cs.error),
                      ),
                      const SizedBox(height: 12),
                    ],
                    FilledButton(
                      key: const Key('pay-now'),
                      onPressed: _paying || widget.token.isEmpty ? null : _pay,
                      child: Text(_paying ? 'Paying…' : 'Pay now'),
                    ),
                  ] else ...[
                    Text(
                      'Paid. Thank you.',
                      key: const Key('pay-done'),
                      style: text.titleMedium?.copyWith(color: cs.primary),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Invoice ${paid['number'] ?? ''} is settled'
                      '${paid['totalAmount'] is num ? ' — ${AppFormat.money(paid['totalAmount'] as num, currencyCode: paid['currency'] as String?)}' : ''}. '
                      'If your service was interrupted, it is back: sign in as usual.',
                      style: text.bodyMedium,
                    ),
                  ],
                ],
              ),
            ),
          ),
          ),
        ),
      ),
    );
  }
}
