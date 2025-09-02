package vn.lochv.fingerprint_reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import com.miaxis.common.MxImage
import com.miaxis.common.MxResult
import com.miaxis.finger.driver.usb.vc.api.CaptureConfig
import com.miaxis.finger.driver.usb.vc.api.FingerApi
import com.miaxis.finger.driver.usb.vc.api.FingerApiFactory
import com.miaxis.justouch.JustouchFingerAPI
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

internal class MiaxisReader(
    private val ctx: Context,
    // emit chỉ là callback lên Plugin; Plugin sẽ phát EventChannel
    private val emit: (state: String, quality: Int?, message: String?) -> Unit
) {
    private val io = Executors.newSingleThreadExecutor()

    // LỚP CHẮN: ép mọi callback đi qua main thread trước khi về Plugin
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
    private fun emitOnMain(state: String, quality: Int? = null, message: String? = null) =
        onMain { emit(state, quality, message) }
    private fun okOnMain(cb: () -> Unit) = onMain(cb)
    private fun errOnMain(cb: () -> Unit) = onMain(cb)

    private var fingerApi: FingerApi? = null
    private var justouch: JustouchFingerAPI? = null

    private var opened = false

    fun bindActivity(binding: ActivityPluginBinding) { /* not used */ }
    fun unbindActivity() { /* not used */ }

    fun open(deviceId: String?, onOk: () -> Unit, onErr: (String, String?) -> Unit) {
        io.execute {
            try {
                if (fingerApi == null) {
                    fingerApi = FingerApiFactory.getInstance(ctx, FingerApiFactory.FPR_220_MSC)
                }
                if (justouch == null) {
                    justouch = JustouchFingerAPI()
                }

                val code = fingerApi?.openDevice() ?: -1
                if (code >= 0) {
                    opened = true
                    emitOnMain("idle", null, "opened")
                    okOnMain(onOk)
                } else {
                    errOnMain { onErr("OPEN_FAIL", "FingerApi.openDevice() = $code") }
                }
            } catch (t: Throwable) {
                errOnMain { onErr("OPEN_EX", t.message) }
            }
        }
    }

    fun close() {
        io.execute {
            try { fingerApi?.closeDevice() } catch (_: Throwable) {}
            opened = false
            emitOnMain("idle", null, "closed")
        }
    }

    fun cancel() {
        // nếu SDK có cancel riêng thì gọi
    }

    /**
     * mode:
     *  "image"      -> trả PNG bytes từ ảnh cảm biến
     *  "iso19794_2" / "ansi378" / "miaxis" -> trả template bytes
     */
    fun capture(
        mode: String,
        timeoutMs: Int?,
        onOk: (ByteArray, Int?) -> Unit,
        onErr: (String, String?) -> Unit
    ) {
        io.execute {
            try {
                val api = fingerApi ?: run {
                    errOnMain { onErr("NO_API", "fingerApi null") }
                    return@execute
                }
                val jt = justouch ?: run {
                    errOnMain { onErr("NO_JUSTOUCH", "Justouch null") }
                    return@execute
                }

                emitOnMain("capturing", null, "start")

                val capCfg = buildCaptureConfig()
                val imageRes: MxResult<MxImage> = api.getImage(capCfg)
                if (!imageRes.isSuccess) {
                    errOnMain { onErr("GET_IMAGE_FAIL", "getImage() not success") }
                    return@execute
                }

                val mx = imageRes.data
                if (mx == null) {
                    errOnMain { onErr("GET_IMAGE_NULL", "MxImage data is null") }
                    return@execute
                }

                var quality: Int? = null
                try {
                    quality = jt.getNFIQ(mx.data, mx.width, mx.height)
                } catch (_: Throwable) { /* optional */ }

                if (mode.equals("image", ignoreCase = true)) {
                    val png = mx.toPngWithJustouch(jt)
                    okOnMain { onOk(png, quality) }
                    emitOnMain("done", quality, null)
                    return@execute
                }

                val (tplType, exFlag) = when (mode.lowercase()) {
                    "iso19794_2" -> 1 to true
                    "ansi378"    -> 3 to true
                    "miaxis"     -> 0 to false
                    else         -> 1 to true
                }

                val outBuf = ByteArray(4096)
                val outLen = IntArray(1)
                val rc = createTemplate(jt, tplType, mx.data, mx.width, mx.height, outBuf, exFlag, outLen)
                if (rc != 0 || outLen[0] <= 0) {
                    errOnMain { onErr("MAKE_TEMPLATE_FAIL", "rc=$rc len=${outLen[0]}") }
                    emitOnMain("error", null, "make template fail")
                    return@execute
                }
                val tpl = outBuf.copyOf(outLen[0])

                okOnMain { onOk(tpl, quality) }
                emitOnMain("done", quality, null)
            } catch (t: Throwable) {
                errOnMain { onErr("CAPTURE_EX", t.message) }
                emitOnMain("error", null, t.message)
            }
        }
    }

    private fun buildCaptureConfig(): CaptureConfig {
        return CaptureConfig.Builder()
            .setNfiqLevel(0)
            .setLfdLevel(0)
            .setTimeout(CaptureConfig.DEFAULT_TIMEOUT.toLong())
            .setAreaScore(45)
            .build()
    }

    private fun createTemplate(
        jt: JustouchFingerAPI,
        featureType: Int,  // 0=MIAIXS, 1=ISO, 2=ISO2011, 3=ANSI, 4=ANSI2009
        data: ByteArray,
        width: Int,
        height: Int,
        newTemplate: ByteArray,
        ex: Boolean,
        length: IntArray
    ): Int {
        val minCount = 18
        return when (featureType) {
            0 -> jt.createTemplateMIAIXS(data, width, height, minCount, newTemplate, length)
            1 -> jt.createTemplateISO(data, width, height, minCount, newTemplate, ex, length)
            2 -> jt.createTemplateISO2011(data, width, height, minCount, newTemplate, ex, length)
            3 -> jt.createTemplateANSI(data, width, height, minCount, newTemplate, ex, length)
            else -> jt.createTemplateANSI2009(data, width, height, minCount, newTemplate, length)
        }
    }
}

/** RAW -> BMP -> PNG dùng Justouch */
private fun MxImage.toPngWithJustouch(jt: JustouchFingerAPI): ByteArray {
    val bmpBytes = ByteArray(this.width * this.height + 1078)
    @Suppress("UNUSED_VARIABLE")
    val rc = jt.convertRawToBMP(this.data, this.width, this.height, bmpBytes)
    val bitmap = BitmapFactory.decodeByteArray(bmpBytes, 0, bmpBytes.size)
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}
