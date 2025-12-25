package vn.lochv.fingerprint_reader_example

import com.aratek.trustfinger.sdk.FingerPosition
import com.aratek.trustfinger.sdk.LfdLevel
import com.aratek.trustfinger.sdk.LfdStatus
import com.aratek.trustfinger.sdk.TrustFingerDevice

internal class TrustFingerCaptureEngine(
    private val device: TrustFingerDevice,
    private val emit: (String, Int?, String?) -> Unit
) {

    fun capture(
        mode: String,
        finger: FingerPosition,
        qualityThreshold: Int,
        timeoutMs: Int?,
        onOk: (ByteArray, Int) -> Unit,
        onErr: (String, String?) -> Unit
    ) {
        Thread {
            try {
                emit("capturing", null, null)

                val raw = device.captureRawData()
                    ?: run {
                        onErr("NO_RAW", "raw null")
                        emit("error", null, "raw null")
                        return@Thread
                    }

                val quality = device.rawDataQuality(raw)
                if (quality < qualityThreshold) {
                    onErr("LOW_QUALITY", "quality=$quality")
                    emit("bad_quality", quality, null)
                    return@Thread
                }

                if (device.lfdLevel != LfdLevel.OFF) {
                    val lfd = device.getFingerLiveness(raw, device.lfdLevel)
                    if (lfd.livenessStatus == LfdStatus.FAKE) {
                        onErr("FAKE_FINGER", "fake")
                        emit("fake", quality, null)
                        return@Thread
                    }
                }

                val bytes = when (mode) {
                    "image" -> device.rawToBmp(
                        raw,
                        device.imageInfo.width,
                        device.imageInfo.height,
                        device.imageInfo.resolution
                    )
                    "iso19794_2" -> device.extractISOFeature(raw, finger)
                    "ansi378" -> device.extractANSIFeature(raw, finger)
                    else -> device.extractFeature(raw, finger)
                }

                emit("done", quality, null)
                onOk(bytes, quality)

            } catch (t: Throwable) {
                emit("error", null, t.message)
                onErr("CAPTURE_EX", t.message)
            }
        }.start()
    }
}
