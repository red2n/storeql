import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/auth/passkeys.dart';
import 'package:storeql_app/core/format.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/spacing.dart';
import 'package:storeql_app/core/theme.dart';
import 'package:storeql_app/features/auth/mfa_api.dart';
import 'package:storeql_app/features/auth/mfa_widgets.dart';
import 'package:storeql_app/features/auth/second_factor_screen.dart';
import 'package:storeql_app/features/auth/second_factor_setup_screen.dart';
import 'package:storeql_app/features/auth/security_screen.dart';

import '../../support/fake_api.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// How the second-factor screens look (the design system's SignIn cards): the
// security page's cards stand apart and its dates read as dates; the required
// note is information, not success; the second step has one filled button; the
// set-up's way back is on screen in a laptop's window; a copy says it copied.
// Pumped in the app's own theme, where a card has no margin of its own.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final Map<String, ResponseBody Function(RequestOptions)> routes;

  _Server(this.routes);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    final route = routes['${o.method} ${o.path}'];
    return route == null ? jsonResponse('{"error":{"code":"NOT_FOUND","message":"no route"}}', 404) : route(o);
  }
}

class _Passkeys implements Passkeys {
  @override
  bool get supported => true;

  @override
  Future<Map<String, String>> create(Map<String, dynamic> options) async => const {};

  @override
  Future<Map<String, String>> get(Map<String, dynamic> options) async => const {};
}

/// A sign-in standing at [state], with nothing stored.
class _Owed extends AuthNotifier {
  final AuthState state0;

  _Owed(this.state0);

  @override
  Future<AuthState> build() async => state0;
}

const _iam = '/iam-svc/auth';
const _secret = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ';

final _totp = _Server({
  'POST $_iam/mfa/totp': (_) => jsonResponse(
      '{"data":{"secret":"$_secret","otpauthUri":"otpauth://totp/StoreQL:ana?secret=$_secret&issuer=StoreQL"}}'),
});

Future<void> _pump(
  WidgetTester tester,
  Widget screen, {
  Size size = const Size(1200, 2400),
  List<Override> overrides = const [],
  _Server? server,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server ?? _Server({});
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio)), ...overrides],
    child: MaterialApp(theme: AppTheme.light, home: screen),
  ));
  await tester.pumpAndSettle();
}

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  final realPasskeys = passkeys;
  setUpAll(initializeDateFormatting);
  tearDown(() => passkeys = realPasskeys);

  // ── sign-in security ────────────────────────────────────────────────────────

  const lastUsed = '2026-09-23T08:00:00Z';
  const status = MfaStatus(
    totp: true,
    passkeys: [PasskeyView(id: 'p-1', name: 'Office laptop', createdAt: '2026-09-01T10:00:00Z', lastUsedAt: lastUsed)],
    recoveryCodesLeft: 7,
    required: true,
  );

  testWidgets('the security cards stand apart, one gap between each, so their hairlines never double', (tester) async {
    await _pump(tester, const SecurityScreen(), overrides: [mfaStatusProvider.overrideWith((ref) async => status)]);

    final cards = find.byType(Card);
    expect(cards, findsNWidgets(3), reason: 'authenticator app, passkeys, recovery codes');
    for (var i = 1; i < 3; i++) {
      final gap = tester.getRect(cards.at(i)).top - tester.getRect(cards.at(i - 1)).bottom;
      expect(gap, AppSpacing.md, reason: 'card $i sits ${AppSpacing.md} below the one above it');
    }
  });

  testWidgets('a passkey\'s last use reads as a date, not an ISO string', (tester) async {
    await _pump(tester, const SecurityScreen(), overrides: [mfaStatusProvider.overrideWith((ref) async => status)]);

    expect(find.text('Last used ${AppFormat.date(lastUsed)}'), findsOneWidget);
    expect(find.textContaining('23 Sep'), findsOneWidget);
    expect(find.textContaining('2026-09-23'), findsNothing);
  });

  testWidgets('the required note is information: the info container, with an info icon', (tester) async {
    await _pump(tester, const SecurityScreen(), overrides: [mfaStatusProvider.overrideWith((ref) async => status)]);

    final note = find.byKey(const Key('mfa-required'));
    final box = tester.widget<Container>(note).decoration! as BoxDecoration;
    expect(box.color, StatusColors.light.infoContainer);
    expect(box.color, isNot(AppTheme.lightScheme.secondaryContainer));
    final icon = find.descendant(of: note, matching: find.byIcon(Icons.info_outline));
    expect(icon, findsOneWidget);
    expect(tester.widget<Icon>(icon).color, StatusColors.light.onInfoContainer);
  });

  // ── the second step ─────────────────────────────────────────────────────────

  testWidgets('the second step has one filled button: the passkey is tonal beside Sign in', (tester) async {
    passkeys = _Passkeys();
    await _pump(
      tester,
      const SecondFactorScreen(),
      overrides: [
        authNotifierProvider.overrideWith(
          () => _Owed(const AuthSecondFactorOwed(
            mfaToken: 'wait-1',
            methods: ['PASSKEY', 'TOTP', 'RECOVERY_CODE'],
            platform: false,
          )),
        ),
      ],
    );

    expect(find.byKey(const Key('mfa-passkey')), findsOneWidget);
    Color? fill(Key key) => tester
        .widget<Material>(find.descendant(of: find.byKey(key), matching: find.byType(Material)).first)
        .color;
    expect(fill(const Key('mfa-submit')), AppTheme.lightScheme.primary);
    expect(fill(const Key('mfa-passkey')), isNot(AppTheme.lightScheme.primary));

    final filled = find.byWidgetPredicate((w) => w is ButtonStyleButton).evaluate().where((e) {
      final materials = find.descendant(of: find.byWidget(e.widget), matching: find.byType(Material));
      return materials.evaluate().isNotEmpty &&
          tester.widget<Material>(materials.first).color == AppTheme.lightScheme.primary;
    });
    expect(filled, hasLength(1), reason: 'one filled button per card');
  });

  // ── setting one up ──────────────────────────────────────────────────────────

  testWidgets('in a 1200 × 800 window the set-up\'s way back is on screen without scrolling', (tester) async {
    passkeys = _Passkeys();
    await _pump(
      tester,
      const SecondFactorSetupScreen(),
      size: const Size(1200, 800),
      server: _totp,
      overrides: [
        authNotifierProvider.overrideWith(() => _Owed(const AuthEnrolmentOwed(enrolmentToken: 'enrol', platform: false))),
      ],
    );

    expect(find.text('1. Scan this with an authenticator app'), findsOneWidget, reason: 'the steps are showing');
    final back = find.text('Back to sign in');
    expect(back, findsOneWidget);
    expect(back.hitTestable(), findsOneWidget, reason: 'reachable where the window ends, not below it');
    expect(tester.getRect(back).bottom, lessThanOrEqualTo(800));
  });

  // ── copying ─────────────────────────────────────────────────────────────────

  testWidgets('copying the key says it was copied, and copies the key', (tester) async {
    String? copied;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') copied = (call.arguments as Map)['text'] as String?;
        return null;
      },
    );
    addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null));
    final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = _totp;
    await _pump(
      tester,
      Scaffold(body: SingleChildScrollView(child: TotpSetup(api: MfaApi(dio), onEnrolled: (_) {}))),
    );

    expect(find.text('Copied'), findsNothing);
    await tester.tap(find.byTooltip('Copy the key'));
    await tester.pump();
    expect(copied, _secret);
    expect(find.text('Copied'), findsOneWidget);
  });

  testWidgets('copying the recovery codes says it was copied, and copies them all', (tester) async {
    String? copied;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') copied = (call.arguments as Map)['text'] as String?;
        return null;
      },
    );
    addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null));
    await _pump(
      tester,
      Scaffold(
        body: SingleChildScrollView(
          child: RecoveryCodesPanel(codes: const ['AAAA-BBBB-CCC1', 'AAAA-BBBB-CCC2'], onDone: () {}),
        ),
      ),
    );

    await tester.tap(find.text('Copy all'));
    await tester.pump();
    expect(copied, 'AAAA-BBBB-CCC1\nAAAA-BBBB-CCC2');
    expect(find.text('Copied'), findsOneWidget);
  });

  testWidgets('on the security screen, copying from the set-up dialog is confirmed above its scrim',
      (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async => null);
    addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null));
    await _pump(
      tester,
      const SecurityScreen(),
      overrides: [
        mfaStatusProvider.overrideWith((ref) async =>
            const MfaStatus(totp: false, passkeys: [], recoveryCodesLeft: 0, required: false)),
      ],
      server: _totp,
    );
    await tester.tap(find.byKey(const Key('totp-setup')));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Copy the key'));
    await tester.pump();
    expect(find.text('Copied').hitTestable(), findsOneWidget,
        reason: 'said where it can be seen, not under the dialog');
    // It goes back to a copy button after a moment.
    await tester.pump(const Duration(seconds: 3));
    expect(find.text('Copied'), findsNothing);
    expect(find.byTooltip('Copy the key'), findsOneWidget);
  });
}
