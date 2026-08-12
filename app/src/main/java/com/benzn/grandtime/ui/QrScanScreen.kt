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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
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
import com.benzn.grandtime.capture.GroupExit
import com.benzn.grandtime.capture.SessionGroup
import com.benzn.grandtime.core.AppState
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.launch

/**
 * Passwordless sign-in: scans the login QR shown by the web app, parses it (`QrLoginParser`),
 * checks it's for this build's environment, then signs in by redeeming the one-time code for a
 * refresh token and running REFRESH_TOKEN_AUTH (`AuthManager.signInWithQrCode`). On success
 * `onSignedIn` is invoked and the caller dismisses this screen; on failure the status line
 * explains why and scanning re-arms so a freshly generated code can be retried without leaving
 * the screen.
 */
@Composable
fun QrScanScreen(onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val auth = remember { (context.applicationContext as GrandTimeApp).authManager }
    QrScanScaffold(prompt = "Point the camera at the login QR") { raw, setStatus ->
        val payload = QrLoginParser.parse(raw)
        when {
            payload == null -> setStatus("Not a FieldSight login code — try again")
            payload.env != BuildConfig.QR_ENV && BuildConfig.QR_ENV == "prod" ->
                setStatus("This code is for a different environment")
            else -> {
                setStatus("Signing in…")
                when (val r = auth.signInWithQrCode(payload.code)) {
                    SignInResult.Success -> onSignedIn()
                    is SignInResult.Failure -> setStatus(r.message)
                    SignInResult.NewPasswordRequired ->
                        setStatus("Set your password in the web app first")
                }
            }
        }
    }
}

/**
 * Join a multi-device meeting by scanning the lead device's code.
 *
 * Shares [QrScanScaffold] with sign-in rather than owning a second camera: the
 * two flows differ only in how the decoded string is interpreted, and a copied
 * scanner would drift on the parts that are easy to get wrong (frame dedup,
 * orientation lock, teardown).
 *
 * Joining only records the intent — the group is attached to a recording when
 * one starts, and the server independently refuses a lead that is stale or
 * belongs to another company.
 */
@Composable
fun QrJoinMeetingScreen(onJoined: () -> Unit) {
    QrScanScaffold(prompt = "Point the camera at the meeting code") { raw, setStatus ->
        when (val scan = SessionGroup.parse(raw, env = BuildConfig.QR_ENV)) {
            // Wrong code, not a broken one: a login QR reaches here whenever
            // someone opens the wrong scanner. Say which kind is expected.
            SessionGroup.Scan.NotAMeetingCode -> setStatus("Not a meeting code — try again")
            // Re-scanning cannot fix this one, so do not invite it.
            SessionGroup.Scan.WrongEnvironment ->
                setStatus("That device is on a different build — both must be dev or both prod")
            is SessionGroup.Scan.Ok -> {
                AppState.pendingGroup.value = GroupExit.PendingGroup(
                    scan.groupId, heldSinceMillis = System.currentTimeMillis())
                onJoined()
            }
        }
    }
}

/**
 * Camera surface + decode plumbing shared by every QR flow.
 *
 * [onCode] runs at most once per distinct code and never concurrently with
 * itself. Both properties live here rather than in each caller because both
 * were bugs once: without dedup, a code sitting in frame is re-submitted every
 * frame; without serialization, a slow sign-in gets a second attempt stacked
 * behind it. A caller cannot opt out of either by forgetting.
 */
@Composable
private fun QrScanScaffold(
    prompt: String,
    onCode: suspend (raw: String, setStatus: (String) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Three independent sources, deliberately not one variable: the camera's own state, the
    // per-frame advice, and the verdict of a sign-in attempt. Precedence is fixed here.
    var scanning by remember { mutableStateOf(prompt) }
    var hint by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf<String?>(null) }
    val status = attempt ?: hint ?: scanning
    var busy by remember { mutableStateOf(false) }
    var lastAttempted by remember { mutableStateOf<String?>(null) }
    val hints = remember { ScanHints() }

    val scanner = remember {
        QrScanner(
            context = context,
            onStatus = { scanning = it },
            onFrame = {},
            // Frame hints and attempt messages are separate state, displayed as
            // `attempt ?: hint ?: scanning`. A per-frame hint therefore CANNOT overwrite
            // "Invalid or expired QR code" -- the one message that names a cause the operator
            // has no other way to discover. Ordering luck is not what keeps that true.
            onFrameOutcome = { outcome ->
                hints.onFrame(outcome, elapsedMs = System.currentTimeMillis())
                hint = hints.hint
            },
            // A screen blank tears the surface down and rebuilds it, which reruns start() and
            // rewrites "Scanning...". lastAttempted lives in the composable and survived, so the
            // same code could never be retried again: decoded, failed, and stuck on "Scanning..."
            // with no way out. Clearing it here is what makes the retry real.
            onRestart = {
                lastAttempted = null
                hints.reset()
                hint = null
            },
            onDecoded = { raw ->
                if (busy || raw == lastAttempted) return@QrScanner
                lastAttempted = raw
                attempt = null      // a new code: the previous attempt's verdict is stale
                busy = true
                scope.launch {
                    try {
                        onCode(raw) { attempt = it }
                    } finally {
                        // Re-arm even if the handler threw: leaving the screen
                        // stuck on a dead scanner is worse than a retry, and a
                        // failed attempt must not end the session.
                        busy = false
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
            // Overlaid on the preview, not placed under it.
            //
            // The F2SP screen is 480x640. A 4:3 preview is 360 tall, and with
            // the app bar above and the nav bar below there is nothing left:
            // a status line placed after the Box falls off the screen entirely.
            // Every message this screen produces — "Not a meeting code",
            // "different build", a sign-in failure — was therefore invisible on
            // the only hardware that runs it, and a failed scan looked like a
            // scanner that simply does nothing.
            //
            // Bottom of the preview is also where the user is already looking.
            Text(
                status,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/** Minimal Camera2 + ZXing controller. Opens the back camera, feeds Y-plane luminance to ZXing. */
private class QrScanner(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onFrame: () -> Unit,
    private val onFrameOutcome: (ScanFrame) -> Unit = {},
    /** The surface was rebuilt (screen blank, app resume) and scanning starts over. */
    private val onRestart: () -> Unit = {},
    private val onDecoded: (String) -> Unit,
) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var stopped = false
    private var lastLoggedOutcome: ScanFrame? = null
    private var lastLoggedAtMs = 0L

    // QRCodeReader, NOT MultiFormatReader: the latter declares `throws NotFoundException` only,
    // catching every other ReaderException internally, so "a code is there but I cannot read it"
    // was already being computed every frame and thrown away. QRCodeReader distinguishes
    // not-found from checksum/format, which is exactly the advice the operator was missing.
    private val zxing = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    @SuppressLint("MissingPermission") // CAMERA is requested at app launch (MainActivity) and required to record.
    fun start(holder: SurfaceHolder) {
        if (thread != null) return
        onRestart()
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
            // The array is buf.remaining(), which on many HALs is rowStride*(h-1)+w -- SMALLER
            // than the rowStride*h this source declares. Where rowStride > w that throws every
            // frame into the blanket catch below: a permanent "Scanning..." with no logs, which
            // is the exact shape of the incident this screen is being changed for.
            val needed = rowStride * h
            val padded = if (data.size >= needed) data else data.copyOf(needed)
            val source = PlanarYUVLuminanceSource(padded, rowStride, h, 0, 0, w, h, false)
            var located = false
            // Upright first, then the 90-rotated frame -- the terminal is wall-mounted landscape,
            // so a QR held "upright" to the operator lands sideways in sensor coordinates.
            var result = decodeOrClassify(BinaryBitmap(HybridBinarizer(source))) { located = true }
            if (result == null) {
                result = decodeOrClassify(
                    BinaryBitmap(HybridBinarizer(source.rotateCounterClockwise()))
                ) { located = true }
            }
            // No permanent latch here — every successfully-decoded frame is reported. The composable
            // (onDecoded above) is responsible for de-duplicating repeat decodes of the same code and
            // re-arming after a failed sign-in attempt.
            val outcome = when {
                result != null -> ScanFrame.DECODED
                located -> ScanFrame.LOCATED_UNREADABLE
                else -> ScanFrame.NOTHING
            }
            // One line per second, not per frame: enough to answer "what does ZXing actually see
            // at the distance where this fails" without flooding a log the operator has to read.
            val now = System.currentTimeMillis()
            if (outcome != lastLoggedOutcome || now - lastLoggedAtMs > 1000) {
                lastLoggedOutcome = outcome
                lastLoggedAtMs = now
                android.util.Log.i("GrandTime", "qr frame: $outcome (${w}x$h)")
            }
            onFrameOutcome(outcome)
            if (result != null) {
                onDecoded(result.text)
            }
        } catch (e: Exception) {
            runCatching { image.close() }
        }
    }

    /**
     * Decode one bitmap, and tell the caller whether a code was at least *located*.
     *
     * ChecksumException / FormatException mean the finder patterns were found and the code was
     * sampled, but the payload could not be recovered -- too small, too blurry, or damaged. That
     * is the one state with a concrete remedy ("move closer"), and it is invisible through
     * MultiFormatReader, which collapses it into not-found.
     */
    private fun decodeOrClassify(bitmap: BinaryBitmap, onLocated: () -> Unit): com.google.zxing.Result? =
        try {
            zxing.decode(bitmap, hints)
        } catch (e: NotFoundException) {
            null
        } catch (e: ChecksumException) {
            onLocated(); null
        } catch (e: FormatException) {
            onLocated(); null
        } finally {
            zxing.reset()
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
