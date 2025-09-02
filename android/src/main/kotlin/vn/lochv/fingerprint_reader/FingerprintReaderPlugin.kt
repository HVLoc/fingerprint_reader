package vn.lochv.fingerprint_reader

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class FingerprintReaderPlugin : FlutterPlugin,
    MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler,
    ActivityAware {

    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel

    private var context: Context? = null
    private var activity: Activity? = null

    // luôn truy cập eventSink qua proxy này
    @Volatile
    private var eventSink: EventChannel.EventSink? = null

    private var reader: MiaxisReader? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** Proxy: ép mọi lệnh EventSink chạy trên main */
    private inner class MainThreadEventSink(
        private val delegate: EventChannel.EventSink
    ) : EventChannel.EventSink {
        override fun success(event: Any?) = onMain { delegate.success(event) }
        override fun error(code: String, message: String?, details: Any?) =
            onMain { delegate.error(code, message, details) }
        override fun endOfStream() = onMain { delegate.endOfStream() }
    }

    /** Proxy: ép mọi lệnh Result chạy trên main */
    private inner class MainThreadResult(
        private val delegate: MethodChannel.Result
    ) : MethodChannel.Result {
        override fun success(result: Any?) = onMain { delegate.success(result) }
        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) =
            onMain { delegate.error(errorCode, errorMessage, errorDetails) }
        override fun notImplemented() = onMain { delegate.notImplemented() }
    }
    private val ACTION_USB_PERMISSION = "vn.lochv.fingerprint_reader.USB_PERMISSION"

    private fun requestUsbPermission(ctx: Context, onGranted: () -> Unit, onDenied: (String) -> Unit) {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val devices = usb.deviceList.values.toList()
        if (devices.isEmpty()) { onDenied("NO_USB_DEVICE"); return }

        val device = devices.first() // hoặc lọc theo VID/PID
        if (usb.hasPermission(device)) { onGranted(); return }

        val flags = if (android.os.Build.VERSION.SDK_INT >= 31)
            android.app.PendingIntent.FLAG_MUTABLE else 0
        val pi = android.app.PendingIntent.getBroadcast(ctx, 0, android.content.Intent(ACTION_USB_PERMISSION), flags)

        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: android.content.Intent?) {
                if (i?.action == ACTION_USB_PERMISSION) {
                    c?.unregisterReceiver(this)
                    val granted = i.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) onGranted() else onDenied("USB_PERMISSION_DENIED")
                }
            }
        }
        ctx.registerReceiver(receiver, android.content.IntentFilter(ACTION_USB_PERMISSION))
        usb.requestPermission(device, pi)
    }

    private fun sendError(result: MethodChannel.Result, code: String, msg: String?) =
        onMain { result.error(code, msg, null) }


    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        methodChannel = MethodChannel(binding.binaryMessenger, "fingerprint_reader/methods")
        eventChannel = EventChannel(binding.binaryMessenger, "fingerprint_reader/events")
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(this)

        reader = MiaxisReader(context!!) { state, quality, message ->
            emitStatus(state, quality, message)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        reader = null
        context = null
        activity = null
        eventSink = null
    }

    // ===== ActivityAware =====
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        reader?.bindActivity(binding)
    }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        reader?.bindActivity(binding)
    }
    override fun onDetachedFromActivityForConfigChanges() {
        activity = null; reader?.unbindActivity()
    }
    override fun onDetachedFromActivity() {
        activity = null; reader?.unbindActivity()
    }

    // ===== EventChannel =====
    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        // bọc proxy ngay tại nguồn
        eventSink = MainThreadEventSink(events)
    }
    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    @MainThread
    private fun emitStatus(state: String, quality: Int? = null, message: String? = null) {
        val payload = mapOf(
            "state" to state,
            "quality" to quality,
            "message" to message
        )
        eventSink?.success(payload) // đã được proxy ép về main
    }

    // ===== MethodChannel =====
    override fun onMethodCall(call: MethodCall, rawResult: MethodChannel.Result) {
        val result = MainThreadResult(rawResult) // luôn dùng proxy
        when (call.method) {
            "getPlatformVersion" -> result.success("Android ${Build.VERSION.RELEASE}")

            "listDevices" -> result.success(
                listOf(mapOf("id" to "Miaxis-USB-0", "name" to "Miaxis FPR", "type" to "USB"))
            )

            "open" -> {
                val deviceId = call.argument<String>("deviceId")
                val ctx = context ?: return sendError(result, "NO_CTX", "no context")
                requestUsbPermission(
                    ctx,
                    onGranted = {
                        reader?.open(deviceId,
                            onOk = {
                                emitStatus("idle", null, "opened")
                                result.success(true)
                            },
                            onErr = { code, msg ->
                                emitStatus("error", null, msg)
                                result.error(code, msg, null)
                            }
                        ) ?: result.error("NO_READER","Reader is null",null)
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
