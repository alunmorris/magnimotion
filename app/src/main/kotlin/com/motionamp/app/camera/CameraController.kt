// 130726 Initial implementation
// 130726 Fix: pre-recording error reporting; stop during configure; clear startInFlight in failRecording
// 140726 Fix: closed flag stops onOpened resurrecting a closed controller; volatile camera fields
package com.motionamp.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import com.motionamp.core.EvmConstants
import java.io.File

/**
 * Camera2 wrapper: idle preview session at 30 fps; on record, reconfigures to a
 * recording session (constrained high-speed for 120/240 fps) feeding MediaRecorder,
 * then restores preview. Single code path for every frame rate. All camera
 * callbacks run on a dedicated handler thread.
 */
class CameraController(context: Context, private val caps: CameraCaps) {

    interface Listener {
        fun onRecordingFinished(path: String)
        fun onError(message: String)
    }

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val appContext = context.applicationContext
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var previewSurface: Surface? = null
    @Volatile private var listener: Listener? = null
    @Volatile private var recordingPath: String? = null
    @Volatile private var errorCallback: ((String) -> Unit)? = null
    @Volatile var isRecording = false
        private set
    @Volatile private var startInFlight = false
    @Volatile private var stopRequested = false
    @Volatile private var closed = false

    /** High-speed capture requires preview and recorder surfaces at the same size. */
    val previewSize: Size = caps.highSpeedRates.values.firstOrNull() ?: Size(1280, 720)

    private val sensorOrientation: Int =
        cameraManager.getCameraCharacteristics(caps.cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    @SuppressLint("MissingPermission") // caller gates on CAMERA permission
    fun open(surface: Surface, onError: (String) -> Unit) {
        errorCallback = onError
        previewSurface = surface
        cameraManager.openCamera(caps.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                if (closed) { // close() already ran; don't resurrect a torn-down controller
                    cam.close()
                    return
                }
                device = cam
                startPreviewSession()
            }
            override fun onDisconnected(cam: CameraDevice) {
                cam.close(); device = null
            }
            override fun onError(cam: CameraDevice, error: Int) {
                cam.close(); device = null
                onError("Camera error $error")
            }
        }, handler)
    }

    /** Errors before the first recording have no Listener yet; fall back to open()'s callback. */
    private fun reportError(message: String) {
        (listener?.let { { m: String -> it.onError(m) } } ?: errorCallback)?.invoke(message)
    }

    private fun startPreviewSession() {
        val cam = device ?: return
        val surface = previewSurface ?: return
        @Suppress("DEPRECATION") // SessionConfiguration path not needed at minSdk 26
        cam.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                }
                s.setRepeatingRequest(req.build(), null, handler)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                reportError("Preview configuration failed")
            }
        }, handler)
    }

    fun startRecording(frameRate: Int, outputFile: File, listener: Listener) {
        val cam = device ?: return listener.onError("Camera not ready")
        val surface = previewSurface ?: return listener.onError("No preview surface")
        if (isRecording || startInFlight) return
        startInFlight = true
        stopRequested = false
        this.listener = listener
        recordingPath = outputFile.absolutePath
        session?.close(); session = null
        val size = caps.highSpeedRates[frameRate] ?: Size(1280, 720)
        val rec = try {
            buildRecorder(frameRate, size, outputFile)
        } catch (e: Exception) {
            return failRecording("Recorder setup failed: ${e.message}")
        }
        recorder = rec
        val surfaces = listOf(surface, rec.surface)

        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                try {
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        addTarget(rec.surface)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(frameRate, frameRate))
                    }.build()
                    if (s is CameraConstrainedHighSpeedCaptureSession) {
                        s.setRepeatingBurst(s.createHighSpeedRequestList(req), null, handler)
                    } else {
                        s.setRepeatingRequest(req, null, handler)
                    }
                    rec.start()
                    isRecording = true
                    startInFlight = false
                    // A stop tapped while the session was configuring must still stop us.
                    if (stopRequested) handler.post { stopRecording() }
                } catch (e: Exception) {
                    startInFlight = false
                    failRecording("Recording start failed: ${e.message}")
                }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                startInFlight = false
                failRecording("Recording session configuration failed")
            }
        }
        @Suppress("DEPRECATION")
        if (frameRate >= 120) {
            cam.createConstrainedHighSpeedCaptureSession(surfaces, stateCallback, handler)
        } else {
            cam.createCaptureSession(surfaces, stateCallback, handler)
        }
    }

    fun stopRecording() {
        if (!isRecording) {
            if (startInFlight) stopRequested = true
            return
        }
        isRecording = false
        val path = recordingPath
        val rec = recorder
        recorder = null
        session?.close(); session = null
        try {
            rec?.stop()
        } catch (e: RuntimeException) {
            // stop() throws if nothing was captured (e.g. immediate stop)
            rec?.release()
            listener?.onError("Recording failed: ${e.message}")
            startPreviewSession()
            return
        }
        rec?.release()
        startPreviewSession()
        if (path != null) listener?.onRecordingFinished(path)
    }

    private fun failRecording(msg: String) {
        startInFlight = false
        recorder?.release(); recorder = null
        isRecording = false
        reportError(msg)
        startPreviewSession()
    }

    fun close() {
        closed = true
        runCatching { recorder?.stop() }
        recorder?.release(); recorder = null
        session?.close(); session = null
        device?.close(); device = null
        thread.quitSafely()
    }

    private fun buildRecorder(frameRate: Int, size: Size, outputFile: File): MediaRecorder {
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(appContext) else MediaRecorder()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(size.width, size.height)
        r.setVideoFrameRate(frameRate)
        r.setCaptureRate(frameRate.toDouble())
        r.setVideoEncodingBitRate(if (frameRate >= 120) 30_000_000 else 12_000_000)
        r.setOrientationHint(sensorOrientation)
        r.setMaxDuration(EvmConstants.MAX_RECORDING_MS)
        r.setOutputFile(outputFile.absolutePath)
        r.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                handler.post { stopRecording() }
            }
        }
        r.prepare()
        return r
    }
}
