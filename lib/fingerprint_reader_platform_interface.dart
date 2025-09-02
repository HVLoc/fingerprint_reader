import 'dart:async';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'fingerprint_reader_method_channel.dart';

abstract class FingerprintReaderPlatform extends PlatformInterface {
  /// Constructs a FingerprintReaderPlatform.
  FingerprintReaderPlatform() : super(token: _token);

  static final Object _token = Object();

  static FingerprintReaderPlatform _instance = MethodChannelFingerprintReader();

  /// The default instance of [FingerprintReaderPlatform] to use.
  static FingerprintReaderPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [FingerprintReaderPlatform] when
  /// they register themselves.
  static set instance(FingerprintReaderPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  // ========= API =========

  /// Giữ lại demo API
  Future<String?> getPlatformVersion() {
    throw UnimplementedError('getPlatformVersion() has not been implemented.');
  }

  /// Liệt kê thiết bị có sẵn
  Future<List<Map<String, dynamic>>> listDevices() {
    throw UnimplementedError('listDevices() has not been implemented.');
  }

  /// Mở kết nối (truyền deviceId nếu muốn chọn cụ thể)
  Future<bool> open({String? deviceId}) {
    throw UnimplementedError('open() has not been implemented.');
  }

  /// Đóng kết nối
  Future<void> close() {
    throw UnimplementedError('close() has not been implemented.');
  }

  /// Bắt đầu capture, trả về Map dữ liệu:
  /// { mode: String, bytes: List<int>, quality: int? }
  Future<Map<String, dynamic>> capture({
    String mode = 'iso19794_2',
    int? timeoutMs,
  }) {
    throw UnimplementedError('capture() has not been implemented.');
  }

  /// Hủy thao tác đang chạy
  Future<void> cancel() {
    throw UnimplementedError('cancel() has not been implemented.');
  }

  // /// Stream trạng thái từ native
  // Stream<Map<String, dynamic>> get statusStream {
  //   throw UnimplementedError('statusStream has not been implemented.');
  // }
}
