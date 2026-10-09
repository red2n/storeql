import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/shared/util/pick_file.dart';

/// A picked file whose read is whatever the test says.
base class _FakeFile extends PlatformFile {
  _FakeFile(this.name, {this.read});

  @override
  final String name;

  /// What reading the file does; null reads an empty file.
  final Future<Uint8List> Function()? read;

  @override
  Future<Uint8List> readAsBytes() =>
      read == null ? Future.value(Uint8List(0)) : read!();

  @override
  Uri get uri => Uri.parse('file:///picked/$name');

  @override
  Never get xFile => throw UnimplementedError();

  @override
  int? lengthSync() => null;

  @override
  Future<int?> length() async => null;

  @override
  Stream<Uint8List> readAsByteStream() => throw UnimplementedError();
}

/// The platform end of file_picker, answering `pickFile` as the test sets it
/// and remembering what it was asked.
class _FakePicker extends FilePickerPlatform {
  Future<PlatformFile?> Function()? answer;
  int asked = 0;
  FileType? type;
  List<String>? allowedExtensions;

  @override
  Future<PlatformFile?> pickFile({
    String? dialogTitle,
    String? initialDirectory,
    FileType type = FileType.any,
    List<String>? allowedExtensions,
    Function(FilePickerStatus)? onFileLoading,
    int compressionQuality = 0,
    AndroidOptions androidOptions = const AndroidOptions(),
    DarwinOptions darwinOptions = const DarwinOptions(),
    WindowsOptions windowsOptions = const WindowsOptions(),
    LinuxOptions linuxOptions = const LinuxOptions(),
    WebOptions webOptions = const WebOptions(),
  }) {
    asked++;
    this.type = type;
    this.allowedExtensions = allowedExtensions;
    return answer!();
  }
}

void main() {
  late FilePickerPlatform original;
  late _FakePicker picker;

  setUp(() {
    original = FilePickerPlatform.instance;
    picker = _FakePicker();
    FilePickerPlatform.instance = picker;
  });

  tearDown(() => FilePickerPlatform.instance = original);

  group('pickFileWithBytes', () {
    test('gives the name and everything in the file', () async {
      final content = Uint8List.fromList([60, 63, 120, 109, 108]);
      picker.answer = () async =>
          _FakeFile('report.xml', read: () async => content);

      final picked = await pickFileWithBytes(const ['xml']);

      expect(picked, isNotNull);
      expect(picked!.name, 'report.xml');
      expect(picked.bytes, content);
    });

    test('asks for a file of the given extensions only', () async {
      picker.answer = () async => _FakeFile('a.jpg');

      await pickFileWithBytes(const ['jpg', 'jpeg', 'png', 'webp']);

      expect(picker.asked, 1);
      expect(picker.type, FileType.custom);
      expect(picker.allowedExtensions, ['jpg', 'jpeg', 'png', 'webp']);
    });

    test('is null when the dialog is dismissed', () async {
      picker.answer = () async => null;

      expect(await pickFileWithBytes(const ['csv']), isNull);
      expect(picker.asked, 1);
    });

    test(
      'is null when the file cannot be read, as bytes that never came were',
      () async {
        picker.answer = () async => _FakeFile(
          'gone.csv',
          read: () async => throw StateError('the file went away'),
        );

        expect(await pickFileWithBytes(const ['csv']), isNull);
      },
    );

    test('is null when the read fails after the dialog has closed', () async {
      picker.answer = () async => _FakeFile(
        'slow.csv',
        read: () => Future.delayed(
          Duration.zero,
          () => throw Exception('blob revoked'),
        ),
      );

      expect(await pickFileWithBytes(const ['csv']), isNull);
    });

    test(
      'an empty file is still a file: the caller judges what is in it',
      () async {
        picker.answer = () async => _FakeFile('empty.csv');

        final picked = await pickFileWithBytes(const ['csv']);

        expect(picked, isNotNull);
        expect(picked!.bytes, isEmpty);
      },
    );

    test('a failure of the dialog itself reaches the caller', () async {
      picker.answer = () async => throw UnsupportedError('no picker here');

      await expectLater(
        pickFileWithBytes(const ['csv']),
        throwsA(isA<UnsupportedError>()),
      );
    });
  });

  group('PickedFileData.extension', () {
    PickedFileData named(String name) => PickedFileData(name, Uint8List(0));

    test('is the part after the last dot', () {
      expect(named('photo.JPG').extension, 'JPG');
      expect(named('archive.tar.gz').extension, 'gz');
    });

    test('is null when there is none', () {
      expect(named('photo').extension, isNull);
      expect(named('photo.').extension, isNull);
      expect(named('.hidden').extension, isNull);
    });
  });
}
