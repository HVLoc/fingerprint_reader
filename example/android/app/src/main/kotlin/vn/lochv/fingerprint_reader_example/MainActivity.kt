package vn.lochv.fingerprint_reader_example

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel


class MainActivity : FlutterActivity(), EventChannel.StreamHandler {

    // ---- Channels ----
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel

    // ---- App/Activity context ----
    private val ctx: Context get() = this
    private val act: Activity get() = this

    // ---- EventSink (được proxy ép về main) ----
    @Volatile
    private var eventSink: EventChannel.EventSink? = null

    // ---- Reader của bạn (thay theo SDK) ----
    private var reader: MiaxisReader? = null

    // ---- Main handler & helpers ép về main thread ----
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private inner class MainThreadEventSink(
        private val delegate: EventChannel.EventSink
    ) : EventChannel.EventSink {
        override fun success(event: Any?) = onMain { delegate.success(event) }
        override fun error(code: String, message: String?, details: Any?) =
            onMain { delegate.error(code, message, details) }
        override fun endOfStream() = onMain { delegate.endOfStream() }
    }

    private inner class MainThreadResult(
        private val delegate: MethodChannel.Result
    ) : MethodChannel.Result {
        override fun success(result: Any?) = onMain { delegate.success(result) }
        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) =
            onMain { delegate.error(errorCode, errorMessage, errorDetails) }
        override fun notImplemented() = onMain { delegate.notImplemented() }
    }

    // ---- USB permission flow ----
    private val ACTION_USB_PERMISSION = "vn.lochv.fingerprint_reader.USB_PERMISSION"
    private var usbPermissionReceiver: BroadcastReceiver? = null

    private fun requestUsbPermission(
        onGranted: () -> Unit,
        onDenied: (String) -> Unit
    ) {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usb.deviceList.values.toList()
        if (devices.isEmpty()) { onDenied("NO_USB_DEVICE"); return }

        val device = devices.first() // TODO: lọc theo VID/PID nếu cần
        if (usb.hasPermission(device)) { onGranted(); return }

        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ACTION_USB_PERMISSION), flags
        )

        usbPermissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == ACTION_USB_PERMISSION) {
                    try { unregisterReceiver(this) } catch (_: Throwable) {}
                    usbPermissionReceiver = null
                    val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) onGranted() else onDenied("USB_PERMISSION_DENIED")
                }
            }
        }
        registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
        usb.requestPermission(device, pi)
    }

    // ---- Emit trạng thái qua EventChannel ----
    @MainThread
    private fun emitStatus(state: String, quality: Int? = null, message: String? = null) {
        val payload = mapOf(
            "state" to state,
            "quality" to quality,
            "message" to message
        )
        eventSink?.success(payload) // đã qua proxy main-thread
    }

    // ---- Lifecycle ----
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Tạo channels từ binaryMessenger của engine
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "fingerprint_reader/methods")
        eventChannel = EventChannel(flutterEngine.dartExecutor.binaryMessenger, "fingerprint_reader/events")

        // StreamHandler cho EventChannel (this)
        eventChannel.setStreamHandler(this)

        // Khởi tạo reader & callback state → emitStatus
        reader = MiaxisReader(ctx) { state, quality, message ->
            emitStatus(state, quality, message)
        }.apply {
            bindActivity(this@MainActivity)   // truyền Activity thay vì ActivityPluginBinding
        }

        // Đăng ký xử lý MethodChannel
        methodChannel.setMethodCallHandler { call: MethodCall, raw: MethodChannel.Result ->
            val result = MainThreadResult(raw)
            when (call.method) {
                "getPlatformVersion" -> result.success("Android ${Build.VERSION.RELEASE}")

                "listDevices" -> result.success(
                    listOf(mapOf("id" to "Miaxis-USB-0", "name" to "Miaxis FPR", "type" to "USB"))
                )

                "open" -> {
                    requestUsbPermission(
                        onGranted = {
                            reader?.open(
                                call.argument<String>("deviceId"),
                                onOk = {
                                    emitStatus("idle", null, "opened")
                                    result.success(true)
                                },
                                onErr = { code, msg ->
                                    emitStatus("error", null, msg)
                                    result.error(code, msg, null)
                                }
                            ) ?: result.error("NO_READER", "Reader is null", null)
                        },
                        onDenied = { why ->
                            emitStatus("error", null, why)
                            result.error("USB_NO_PERMISSION", why, null)
                        }
                    )
                }

                "close" -> {
                    reader?.close()
                    emitStatus("idle", null, "closed")
                    result.success(null)
                }

                "capture" -> {
                    val mode = call.argument<String>("mode") ?: "iso19794_2"
                    val timeoutMs = call.argument<Int>("timeoutMs")
                    emitStatus("capturing", null, "start")
                    reader?.capture(
                        mode, timeoutMs,
                        onOk = { bytes, quality ->
                            emitStatus("done", quality, null)
                            result.success(
                                mapOf(
                                    "mode" to mode,
                                    "bytes" to bytes.toList(),
                                    "quality" to (quality ?: -1)
                                )
                            )
                        },
                        onErr = { code, msg ->
                            emitStatus("error", null, msg)
                            result.error(code, msg, null)
                        }
                    ) ?: result.error("NO_READER", "Reader is null", null)
                }

                "cancel" -> {
                    reader?.cancel()
                    emitStatus("idle", null, "cancelled")
                    result.success(null)
                }

                else -> result.notImplemented()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            methodChannel.setMethodCallHandler(null)
            eventChannel.setStreamHandler(null)
        } catch (_: Throwable) {}

        try { usbPermissionReceiver?.let { unregisterReceiver(it) } } catch (_: Throwable) {}
        usbPermissionReceiver = null

        reader?.unbindActivity()
        reader = null
        eventSink = null
    }

    // ==== EventChannel.StreamHandler ====
    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        // bọc proxy ép về main ngay tại nguồn
        eventSink = MainThreadEventSink(events)
    }
    override fun onCancel(arguments: Any?) {
        eventSink = null
    }
}
