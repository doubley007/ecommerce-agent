package com.example.shopguide

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

/**
 * 拍照搜商品的相机界面。设计要点：
 *  - 全屏 PreviewView，取景框居中虚线方框引导用户对准商品（端侧采集体验）
 *  - 快门按钮在底部中央，点一次拍一张
 *  - 拍完出来 JPEG bytes 走 ImageUtils.captureFromBytes：EXIF 旋转 + 缩放 + 模糊检测
 *  - 模糊则弹软提醒（重拍 / 照样使用），清晰则直接 onConfirm 关闭相机回主界面
 */
@Composable
fun CameraScreen(
    onCancel: () -> Unit,
    onConfirm: (ImageUtils.CapturedImage) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var pendingBlurry by remember { mutableStateOf<ImageUtils.CapturedImage?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 取景预览
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            },
            update = { previewView ->
                bindCamera(
                    context = context,
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    onReady = { ic -> imageCapture = ic },
                )
            },
        )

        // 居中取景框 + 提示文案
        ViewfinderOverlay(modifier = Modifier.fillMaxSize())

        // 顶部：返回按钮
        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .padding(12.dp)
                .background(Color(0x66000000), RoundedCornerShape(20.dp)),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
        }

        // 底部：快门按钮
        Column(
            modifier = Modifier.fillMaxSize().padding(bottom = 48.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "把商品放在框内，光线均匀更易识别",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            ShutterButton(
                enabled = imageCapture != null && !capturing,
                busy = capturing,
                onClick = {
                    val ic = imageCapture ?: return@ShutterButton
                    capturing = true
                    takePhoto(ic, ContextCompat.getMainExecutor(context)) { jpegBytes, err ->
                        if (err != null || jpegBytes == null) {
                            android.util.Log.e("CameraScreen", "takePhoto error", err)
                            android.widget.Toast.makeText(context, "拍照失败：${err?.message ?: "未知"}", android.widget.Toast.LENGTH_SHORT).show()
                            capturing = false
                            return@takePhoto
                        }
                        // 端侧预处理放 IO 线程，避免阻塞 main
                        scope.launch {
                            val captured = withContext(Dispatchers.IO) { ImageUtils.captureFromBytes(jpegBytes) }
                            capturing = false
                            if (captured == null) {
                                android.widget.Toast.makeText(context, "图片处理失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            android.util.Log.d(
                                "CameraScreen",
                                "captured ${captured.widthPx}x${captured.heightPx} ${captured.sizeKb}KB sharpness=${"%.1f".format(captured.sharpness)} blurry=${captured.isBlurry}",
                            )
                            if (captured.isBlurry) pendingBlurry = captured
                            else onConfirm(captured)
                        }
                    }
                },
            )
        }
    }

    // 模糊软提醒：重拍 / 照样用
    pendingBlurry?.let { captured ->
        AlertDialog(
            onDismissRequest = { pendingBlurry = null },
            title = { Text("图片有点模糊") },
            text = {
                Text(
                    "锐度估值 ${"%.0f".format(captured.sharpness)}（参考阈值 ${ImageUtils.BLUR_VARIANCE_THRESHOLD.toInt()}）。\n" +
                        "重拍效果会更好，要继续用这张吗？",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingBlurry = null
                    onConfirm(captured)
                }) { Text("照样使用") }
            },
            dismissButton = {
                TextButton(onClick = { pendingBlurry = null }) { Text("重拍") }
            },
        )
    }
}

@Composable
private fun ViewfinderOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val frameSize = (size.minDimension * 0.72f)
        val left = (size.width - frameSize) / 2f
        val top = (size.height - frameSize) / 2f
        // 虚线描边方框
        drawRoundedDashedRect(
            left = left,
            top = top,
            width = frameSize,
            height = frameSize,
            color = Color.White.copy(alpha = 0.85f),
            strokeWidth = 4f,
            dashOn = 18f,
            dashOff = 12f,
            cornerRadius = 16f,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundedDashedRect(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    color: Color,
    strokeWidth: Float,
    dashOn: Float,
    dashOff: Float,
    cornerRadius: Float,
) {
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(left, top),
        size = ComposeSize(width, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
        style = Stroke(
            width = strokeWidth,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashOn, dashOff)),
        ),
    )
}

@Composable
private fun ShutterButton(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    // 经典快门：外圈白边 + 内圈实心；busy 时换 spinner
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(Color.White.copy(alpha = if (enabled) 1f else 0.5f))
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(30.dp))
                    .background(Color.White),
            )
        }
    }
}

private fun bindCamera(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    onReady: (ImageCapture) -> Unit,
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        val provider = future.get()
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            onReady(capture)
        } catch (e: Exception) {
            android.util.Log.e("CameraScreen", "bindCamera failed", e)
            android.widget.Toast.makeText(context, "相机绑定失败：${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }, ContextCompat.getMainExecutor(context))
}

/** 拍照：从 ImageProxy 读出 JPEG bytes。CAPTURE_MODE_MINIMIZE_LATENCY 模式下 plane[0] 直接是 JPEG。 */
private fun takePhoto(
    capture: ImageCapture,
    executor: Executor,
    callback: (ByteArray?, Throwable?) -> Unit,
) {
    capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                callback(bytes, null)
            } finally {
                image.close()
            }
        }

        override fun onError(exception: ImageCaptureException) {
            callback(null, exception)
        }
    })
}
