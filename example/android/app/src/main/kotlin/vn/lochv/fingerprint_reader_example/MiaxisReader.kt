package vn.lochv.fingerprint_reader_example

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.miaxis.common.MxImage
import com.miaxis.common.MxResult
import com.miaxis.finger.driver.usb.vc.api.CaptureConfig
import com.miaxis.finger.driver.usb.vc.api.FingerApi
import com.miaxis.finger.driver.usb.vc.api.FingerApiFactory
import com.miaxis.justouch.JustouchFingerAPI
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import java.io.ByteArrayOutputStream
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class MiaxisReader(
    private val ctx: Context,
    // emit chỉ là callback lên Plugin; Plugin sẽ phát EventChannel
    private val emit: (state: String, quality: Int?, message: String?) -> Unit
) {
    // Thread pools
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    // Ép về main
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
    private fun emitOnMain(state: String, quality: Int? = null, message: String? = null) =
        onMain { emit(state, quality, message) }
    private fun okOnMain(cb: () -> Unit)  = onMain(cb)
    private fun errOnMain(cb: () -> Unit) = onMain(cb)

    private var fingerApi: FingerApi? = null
    private var justouch: JustouchFingerAPI? = null
    private var opened = false

    // Quản lý 1 tác vụ capture đang chạy
    private val currentCapture = AtomicReference<Future<*>?>()


    // Sau (đề xuất):
    private var activity: Activity? = null

    fun bindActivity(activity: Activity) {
        this.activity = activity
        // TODO: nếu trước đây bạn dùng binding.addActivityResultListener(...)
        // thì giờ dùng registerForActivityResult hoặc override onActivityResult trong Activity
    }

    fun unbindActivity() {
        this.activity = null
    }

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
                    emitOnMain("idle", null, "opened")   // ✅ main
                    okOnMain(onOk)                        // ✅ main
                } else {
                    errOnMain { onErr("OPEN_FAIL", "FingerApi.openDevice() = $code") } // ✅ main
                    emitOnMain("error", null, "open fail")
                }
            } catch (t: Throwable) {
                errOnMain { onErr("OPEN_EX", t.message) }
                emitOnMain("error", null, t.message)
            }
        }
    }

    fun close() {
        // Hủy capture đang chạy trước khi đóng
        cancel()
        io.execute {
            try { fingerApi?.closeDevice() } catch (_: Throwable) {}
            opened = false
            emitOnMain("idle", null, "closed")
        }
    }

    fun cancel() {
        currentCapture.getAndSet(null)?.cancel(true)
        emitOnMain("idle", null, "cancelled")
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
        // Đảm bảo chỉ 1 capture tại một thời điểm
        currentCapture.getAndSet(null)?.cancel(true)

        val done = AtomicBoolean(false)

        // Lập lịch timeout (nếu có)
        val timeoutTask: ScheduledFuture<*>? = timeoutMs
            ?.takeIf { it > 0 }
            ?.let {
                scheduler.schedule({
                    if (done.compareAndSet(false, true)) {
                        errOnMain { onErr("TIMEOUT", "Capture timed out after ${it}ms") }
                        emitOnMain("error", null, "timeout")
                        // hủy luồng đang chạy (nếu còn)
                        currentCapture.getAndSet(null)?.cancel(true)
                    }
                }, it.toLong(), TimeUnit.MILLISECONDS)
            }

        val future = io.submit {
            try {
                if (!opened) {
                    if (done.compareAndSet(false, true)) {
                        errOnMain { onErr("NOT_OPENED", "Device not opened") }
                        emitOnMain("error", null, "not opened")
                    }
                    return@submit
                }

                val api = fingerApi ?: run {
                    if (done.compareAndSet(false, true)) {
                        errOnMain { onErr("NO_API", "fingerApi null") }
                        emitOnMain("error", null, "no api")
                    }
                    return@submit
                }
                val jt = justouch ?: run {
                    if (done.compareAndSet(false, true)) {
                        errOnMain { onErr("NO_JUSTOUCH", "Justouch null") }
                        emitOnMain("error", null, "no justouch")
                    }
                    return@submit
                }

                emitOnMain("capturing", null, "start")

                val capCfg = buildCaptureConfig(timeoutMs)
                val imageRes: MxResult<MxImage> = api.getImage(capCfg)

                if (done.get()) return@submit // đã timeout/hủy

                if (!imageRes.isSuccess) {
                    if (done.compareAndSet(false, true)) {
                        errOnMain { onErr("GET_IMAGE_FAIL", "getImage() not success") }
                        emitOnMain("error", null, "getImage not success")
                    }
                    return@submit
                }

                val mx = imageRes.data
                if (mx == null) {
                    if (done.compareAndSet(false, true)) {
                        errOnMain { onErr("GET_IMAGE_NULL", "MxImage data is null") }
                        emitOnMain("error", null, "MxImage null")
                    }
                    return@submit
                }

                var quality: Int? = null
                try {
                    quality = jt.getNFIQ(mx.data, mx.width, mx.height)
                } catch (_: Throwable) { /* ignore */ }

                if (mode.equals("image", ignoreCase = true)) {
                    val png = mx.toPngWithJustouch(jt)
                    if (done.compareAndSet(false, true)) {
                        okOnMain { onOk(png, quality) }
                        emitOnMain("done", quality, null)
                    }
                    return@submit
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

                if (done.get()) return@submit // đã timeout/hủy

                if (rc != 0 || outLen[0] <= 0) {
                    if (done.compareAndSet(false, true)) {
                        errOnMain { onErr("MAKE_TEMPLATE_FAIL", "rc=$rc len=${outLen[0]}") }
                        emitOnMain("error", null, "make template fail")
                    }
                    return@submit
                }

                val tpl = outBuf.copyOf(outLen[0])

                if (done.compareAndSet(false, true)) {
                    okOnMain { onOk(tpl, quality) }
                    emitOnMain("done", quality, null)
                }
            } catch (t: Throwable) {
                if (done.compareAndSet(false, true)) {
                    errOnMain { onErr("CAPTURE_EX", t.message) }
                    emitOnMain("error", null, t.message)
                }
            } finally {
                timeoutTask?.cancel(false)
                currentCapture.set(null)
            }
        }

        currentCapture.set(future)
    }

    private fun buildCaptureConfig(timeoutMs: Int?): CaptureConfig {
        val t = (timeoutMs ?: CaptureConfig.DEFAULT_TIMEOUT).toLong()
        return CaptureConfig.Builder()
            .setNfiqLevel(0)
            .setLfdLevel(0)
            .setTimeout(t)
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
