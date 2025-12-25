package vn.lochv.fingerprint_reader_example

import android.content.Context
import com.aratek.trustfinger.sdk.DeviceOpenListener
import com.aratek.trustfinger.sdk.FingerPosition
import com.aratek.trustfinger.sdk.TrustFinger
import com.aratek.trustfinger.sdk.TrustFingerDevice

class TrustFingerBackend(
    ctx: Context,
    private val emit: (String, Int?, String?) -> Unit
) : FingerprintBackend {

    val trustFinger = TrustFinger.getInstance(ctx)
    private var device: TrustFingerDevice? = null
    private var engine: TrustFingerCaptureEngine? = null

    init {
        trustFinger.initialize()
    }

    override fun listDevices(): List<Map<String, String>> =
        trustFinger.deviceList.mapIndexed { i, d ->
            mapOf(
                "id" to i.toString(),
                "name" to d,
                "type" to "TrustFinger"
            )
        }

    override fun open(deviceId: String?, onOk: () -> Unit, onErr: (String, String?) -> Unit) {
        try {
            val id = deviceId?.toIntOrNull() ?: 0
            trustFinger.openDevice(id, object : DeviceOpenListener {
                override fun openSuccess(dev: TrustFingerDevice) {
                    device = dev
                    engine = TrustFingerCaptureEngine(dev, emit)
                    emit("idle", null, "opened")
                    onOk()
                }

                override fun openFail(msg: String?) {
                    emit("error", null, msg)
                    onErr("OPEN_FAIL", msg)
                }
            })
        } catch (t: Throwable) {
            onErr("OPEN_EX", t.message)
        }
    }

    override fun close() {
        device?.closeAll()
        trustFinger.closeAllDev()
        device = null
        emit("idle", null, "closed")
    }

    override fun cancel() {
        emit("idle", null, "cancelled")
    }

    override fun capture(
        mode: String,
        finger: String,
        timeoutMs: Int?,
        qualityThreshold: Int,
        onOk: (ByteArray, Int) -> Unit,
        onErr: (String, String?) -> Unit
    ) {
        val pos = FingerPosition.valueOf(finger)
        engine?.capture(mode, pos, qualityThreshold, timeoutMs, onOk, onErr)
            ?: onErr("NO_DEVICE", "Device not opened")
    }
}