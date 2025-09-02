// import 'package:flutter_test/flutter_test.dart';
// import 'package:fingerprint_reader/fingerprint_reader.dart';
// import 'package:fingerprint_reader/fingerprint_reader_platform_interface.dart';
// import 'package:fingerprint_reader/fingerprint_reader_method_channel.dart';
// import 'package:plugin_platform_interface/plugin_platform_interface.dart';

// class MockFingerprintReaderPlatform
//     with MockPlatformInterfaceMixin
//     implements FingerprintReaderPlatform {

//   @override
//   Future<String?> getPlatformVersion() => Future.value('42');
// }

// void main() {
//   final FingerprintReaderPlatform initialPlatform = FingerprintReaderPlatform.instance;

//   test('$MethodChannelFingerprintReader is the default instance', () {
//     expect(initialPlatform, isInstanceOf<MethodChannelFingerprintReader>());
//   });

//   test('getPlatformVersion', () async {
//     FingerprintReader fingerprintReaderPlugin = FingerprintReader();
//     MockFingerprintReaderPlatform fakePlatform = MockFingerprintReaderPlatform();
//     FingerprintReaderPlatform.instance = fakePlatform;

//     expect(await fingerprintReaderPlugin.getPlatformVersion(), '42');
//   });
// }
