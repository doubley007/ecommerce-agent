package com.example.shopguide

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * 端侧图像采集预处理：
 *  1) EXIF 旋转矫正：相机 JPEG 通常带 Orientation tag，不矫正会让竖拍图躺平
 *  2) 缩放到 ≤512px 长边（VLM 不需要大图，省带宽 + 加速 base64 体积）
 *  3) JPEG 80% 编码 + base64
 *  4) 拉普拉斯方差判模糊（变体：3x3 二阶差分核），用于"软提醒"
 *
 * 这些预处理放在端侧而不是服务端的理由：
 *  - 上传前缩到 512px，base64 体积从 ~2MB 降到 ~70KB，弱网/移动数据下显著省时
 *  - EXIF/模糊检测靠近"采集源头"做才有意义，到服务端再做就丢失了"提示用户重拍"的窗口
 */
object ImageUtils {
    private const val MAX_DIM = 512
    private const val JPEG_QUALITY = 80

    /** 拉普拉斯方差阈值。低于此值认为画面模糊；阈值由真机实测标定（华为 P40 Pro，2026-05-26）：
     *   - 稳拍清晰商品图：~1400+
     *   - 快速拖动手机抖糊：~280~500
     *   - 严重失焦：<200
     * 现代手机摄像头的运动稳像会把"轻微拖糊"图救回到 ~500，所以阈值取 500 卡在
     * 清晰 vs 抖糊的中间带；不同机型实际值不同，必要时把日志里 sharpness= 的值再校准一次。 */
    const val BLUR_VARIANCE_THRESHOLD = 500.0

    /** 端侧采集结果。base64 是 JPEG 80% 编码后的纯 base64（无 data: 前缀），可直接送 VLM。 */
    data class CapturedImage(
        val base64: String,
        val widthPx: Int,
        val heightPx: Int,
        val sizeKb: Int,
        /** 拉普拉斯方差，越大越清晰 */
        val sharpness: Double,
        val isBlurry: Boolean,
    )

    /** 相册选图：从 Uri 读取 + EXIF 旋转 + 缩放 + 编码。失败返回 null。 */
    fun captureFromUri(context: Context, uri: Uri): CapturedImage? = runCatching {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@runCatching null
        captureFromBytes(rawBytes)
    }.onFailure {
        android.util.Log.e(TAG, "captureFromUri failed", it)
    }.getOrNull()

    /** 相机回调：直接拿到 JPEG bytes，做 EXIF 旋转 + 缩放 + 编码 + 模糊检测。 */
    fun captureFromBytes(jpegBytes: ByteArray): CapturedImage? = runCatching {
        val src = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return@runCatching null

        val rotation = readExifRotation(jpegBytes)
        val rotated = if (rotation != 0) rotate(src, rotation) else src
        if (rotated !== src) src.recycle()

        val resized = resizeIfNeeded(rotated)
        if (resized !== rotated) rotated.recycle()

        val sharpness = estimateLaplacianVariance(resized)

        val out = ByteArrayOutputStream().use { os ->
            resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os)
            os.toByteArray()
        }
        val b64 = Base64.encodeToString(out, Base64.NO_WRAP)
        val result = CapturedImage(
            base64 = b64,
            widthPx = resized.width,
            heightPx = resized.height,
            sizeKb = out.size / 1024,
            sharpness = sharpness,
            isBlurry = sharpness < BLUR_VARIANCE_THRESHOLD,
        )
        resized.recycle()
        result
    }.onFailure {
        android.util.Log.e(TAG, "captureFromBytes failed", it)
    }.getOrNull()

    /** 把 base64 字符串解回 Bitmap（用户消息气泡里显示缩略图用）。 */
    fun decodeThumbnail(base64: String): Bitmap? = runCatching {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun readExifRotation(jpegBytes: ByteArray): Int {
        val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
        return when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun resizeIfNeeded(src: Bitmap): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= MAX_DIM) return src
        val scale = MAX_DIM.toFloat() / longest
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /**
     * 拉普拉斯方差（Variance of Laplacian），衡量图像锐度的常用指标。
     * 算法：把图缩到 256px 长边、转灰度、用 3x3 拉普拉斯核做二阶差分、取方差。
     * 方差越大说明高频成分越多 → 越锐利；越小说明边缘信息少 → 越模糊。
     *
     * 性能：256x256 大约扫 65k 像素，单线程 ~10ms 完成，对 UI 不卡。
     */
    private fun estimateLaplacianVariance(src: Bitmap): Double {
        // 预缩放到 256px 长边以加速；不影响"模糊/清晰"判定的相对值
        val target = 256
        val longest = max(src.width, src.height)
        val small = if (longest > target) {
            val s = target.toFloat() / longest
            Bitmap.createScaledBitmap(
                src,
                (src.width * s).toInt().coerceAtLeast(1),
                (src.height * s).toInt().coerceAtLeast(1),
                true,
            )
        } else src

        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)

        // 转灰度（Rec.601）
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }

        // 3x3 拉普拉斯核：
        //  0 -1  0
        // -1  4 -1
        //  0 -1  0
        // 仅扫内部像素（边界 1px 不算）
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val v = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w]
                sum += v
                sumSq += v.toDouble() * v
                count++
            }
        }
        if (small !== src) small.recycle()
        if (count == 0) return 0.0
        val mean = sum / count
        return sumSq / count - mean * mean
    }

    private const val TAG = "ImageUtils"
}
