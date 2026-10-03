import 'dart:async';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';

/// Every POS test starts with an empty device store, kept in memory.
///
/// The till keeps a card payment it left at the card machine on the device
/// (held_card_payment.dart), and a press reads it back before it decides
/// anything. In a test the device store is a platform channel nobody answers,
/// so without this a press would wait on it for ever. Emptied before each test,
/// so nothing one test holds is found by the next.
Future<void> testExecutable(FutureOr<void> Function() testMain) async {
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));
  await testMain();
}
