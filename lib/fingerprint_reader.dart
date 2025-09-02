import 'fingerprint_reader_platform_interface.dart';

/// API Flutter mà app sẽ gọi
class FingerprintReader {
  /// Giữ lại API cũ để tương thích ví dụ mặc định
  Future<String?> getPlatformVersion() {
    return FingerprintReaderPlatform.instance.getPlatformVersion();
  }

  /// Liệt kê thiết bị có thể dùng (USB/BLE/SDK…)
  /// Mỗi phần tử là Map: {id, name, type}
  Future<List<Map<String, dynamic>>> listDevices() {
    return FingerprintReaderPlatform.instance.listDevices();
  }

  /// Mở kết nối tới thiết bị (nếu null sẽ chọn thiết bị đầu tiên nhà nền tảng)
  Future<bool> open({String? deviceId}) {
    return FingerprintReaderPlatform.instance.open(deviceId: deviceId);
  }

  /// Đóng kết nối
  Future<void> close() {
    return FingerprintReaderPlatform.instance.close();
  }

  /// Bắt đầu quét vân tay.
  /// mode: "image" | "iso19794_2" | "ansi378" | "wsq"
  /// timeout: milliseconds (null = không timeout, do native xử lý)
  Future<Map<String, dynamic>> capture({
    String mode = 'iso19794_2',
    int? timeoutMs,
  }) {
    return FingerprintReaderPlatform.instance.capture(
      mode: mode,
      timeoutMs: timeoutMs,
    );
  }

  /// Hủy thao tác quét hiện tại nếu đang chạy
  Future<void> cancel() {
    return FingerprintReaderPlatform.instance.cancel();
  }

  /// Stream trạng thái từ native:
  /// ví dụ event: {state:"capturing", quality:73, message:null}
  // Stream<Map<String, dynamic>> get statusStream =>
  //     FingerprintReaderPlatform.instance.statusStream;
}
