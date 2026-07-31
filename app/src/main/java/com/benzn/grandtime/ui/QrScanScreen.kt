package com.benzn.grandtime.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.benzn.grandtime.BuildConfig
import com.benzn.grandtime.GrandTimeApp
import com.benzn.grandtime.auth.QrLoginParser
import com.benzn.grandtime.auth.SignInResult
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.launch

/**
 * Passwordless sign-in: scans the login QR shown by the web app, parses it (`QrLoginParser`),
 * checks it's for this build's environment, then signs in via Cognito custom-auth
 * (`AuthManager.signInWithQrCode`). On success `onSignedIn` is invoked and the caller dismisses
 * this screen; on failure the status line explains why and scanning re-arms so a freshly
 * generated code can be retried without leaving the screen.
 */
@Composable
fun QrScanScreen(onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val auth = remember { (context.applicationContext as GrandTimeApp).authManager }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Point the camera at the login QR") }
    // `busy` serializes sign-in attempts (ignore decodes while one is in flight); `lastAttempted`
    // remembers the last raw string we already acted on so an identical code still sitting in
    // frame isn't re-parsed/re-submitted every frame — only a genuinely new/changed code (a fresh
    // QR) is attempted again. Together these replace the old permanent found=true latch, which let
    // onDecoded fire exactly once ever and would have left the user stuck after a failed sign-in.
    var busy by remember { mutableStateOf(false) }
    var lastAttempted by remember { mutableStateOf<String?>(null) }

    val scanner = remember {
        QrScanner(
            context = context,
            onStatus = { status = it },
            onFrame = {},
            onDecoded = { raw ->
                if (busy || raw == lastAttempted) return@QrScanner
                lastAttempted = raw
                val payload = QrLoginParser.parse(raw)
                when {
                    payload == null -> status = "Not a FieldSight login code — try again"
                    payload.env != BuildConfig.QR_ENV && BuildConfig.QR_ENV == "prod" ->
                        status = "This code is for a different environment"
                    else -> {
                        busy = true
                        status = "Signing in…"
                        scope.launch {
                            when (val r = auth.signInWithQrCode(payload.code)) {
                                SignInResult.Success -> onSignedIn()
                                is SignInResult.Failure -> { status = r.message; busy = false }
                                SignInResult.NewPasswordRequired -> {
                                    status = "Set your password in the web app first"; busy = false
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    // Lock to the current orientation while scanning. MainActivity is intentionally not
    // orientation-locked, so a rotation would recreate the Activity mid-scan — tearing down the
    // camera and resetting the composable back to the login form. Locking prevents that recreation;
    // the previous setting is restored on exit.
    DisposableEffect(Unit) {
        var c: Context = context
        while (c !is Activity && c is android.content.ContextWrapper) c = c.baseContext
        val activity = c as? Activity
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        onDispose {
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            scanner.stop()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) { scanner.start(h) }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) { scanner.stop() }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.padding(16.dp)) {
            Text(status, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Minimal Camera2 + ZXing controller. Opens the back camera, feeds Y-plane luminance to ZXing. */
private class QrScanner(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onFrame: () -> Unit,
    private val onDecoded: (String) -> Unit,
) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var stopped = false

    private val zxing = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    @SuppressLint("MissingPermission") // CAMERA is requested at app launch (MainActivity) and required to record.
    fun start(holder: SurfaceHolder) {
        if (thread != null) return
        stopped = false
        thread = HandlerThread("qr-scan").also { it.start() }
        handler = Handler(thread!!.looper)

        val cameraId = pickBackCamera() ?: run { onStatus("No back camera found"); return }
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val analysisSize = chooseSize(map?.getOutputSizes(ImageFormat.YUV_420_888))
        val previewSize = chooseSize(map?.getOutputSizes(SurfaceHolder::class.java))
        holder.setFixedSize(previewSize.width, previewSize.height)

        reader = ImageReader.newInstance(analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ r -> analyse(r) }, handler)
        }

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    if (stopped) { device.close(); return }
                    val previewSurface = holder.surface
                    val analysisSurface = reader!!.surface
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(
                        listOf(previewSurface, analysisSurface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (stopped) { s.close(); return }
                                session = s
                                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(previewSurface)
                                    addTarget(analysisSurface)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                }.build()
                                s.setRepeatingRequest(req, null, handler)
                                onStatus("Scanning…")
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                onStatus("Camera session failed")
                            }
                        },
                        handler,
                    )
                }

                override fun onDisconnected(device: CameraDevice) { device.close(); camera = null }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); camera = null
                    onStatus("Camera error $error (in use by recording?)")
                }
            }, handler)
        } catch (e: Exception) {
            onStatus("Cannot open camera: ${e.message}")
        }
    }

    private fun analyse(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val data = ByteArray(buf.remaining())
            buf.get(data)
            val rowStride = plane.rowStride
            val w = image.width
            val h = image.height
            image.close()
            onFrame()
            val source = PlanarYUVLuminanceSource(data, rowStride, h, 0, 0, w, h, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = try {
                zxing.decodeWithState(bitmap)
            } catch (e: NotFoundException) {
                // Retry once on the 90°-rotated frame — the terminal is wall-mounted landscape, so a
                // QR held "upright" to the operator lands sideways in sensor coordinates.
                try { zxing.decodeWithState(BinaryBitmap(HybridBinarizer(source.rotateCounterClockwise()))) }
                catch (e2: NotFoundException) { null }
            } finally {
                zxing.reset()
            }
            // No permanent latch here — every successfully-decoded frame is reported. The composable
            // (onDecoded above) is responsible for de-duplicating repeat decodes of the same code and
            // re-arming after a failed sign-in attempt.
            if (result != null) {
                onDecoded(result.text)
            }
        } catch (e: Exception) {
            runCatching { image.close() }
        }
    }

    fun stop() {
        stopped = true
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { reader?.close() }
        session = null; camera = null; reader = null
        thread?.quitSafely()
        thread = null; handler = null
    }

    private fun pickBackCamera(): String? {
        for (id in manager.cameraIdList) {
            val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return manager.cameraIdList.firstOrNull()
    }

    /** Prefer a mid-range size (~1280×720) — big enough to resolve a QR, small enough to decode fast. */
    private fun chooseSize(sizes: Array<Size>?): Size {
        if (sizes.isNullOrEmpty()) return Size(1280, 720)
        return sizes.filter { it.width in 960..1600 }.minByOrNull { kotlin.math.abs(it.width - 1280) }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height }!!
    }
}
