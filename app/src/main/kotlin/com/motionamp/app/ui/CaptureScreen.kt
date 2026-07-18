// 130726 Initial implementation
// 140726 Fix: keep controls out of the system status/navigation bar areas (edge-to-edge insets)
// 150726 Fix: preview letterboxed at the buffer's aspect ratio instead of stretching to screen
// 150726 Fix: re-acquire the camera on resume after another app took it
// 150726 Added on-screen title "Video Motion Amplification"
// 150726 Row labels: Amplification, Playback Speed
// 150726 App icon added beside the title
// 150726 Start Delay preset row with cancellable on-screen countdown
// 160726 Info button beside the title opens a usage guide dialog
// 180726 Recording Time preset row (2/5/10/60 s); ring and auto-stop follow the selection
// 180726 App renamed MagniMotion; old title kept as subtitle
package com.motionamp.app.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.motionamp.app.MainViewModel
import com.motionamp.app.R
import com.motionamp.app.camera.CameraCapabilities
import com.motionamp.app.camera.CameraCaps
import com.motionamp.app.camera.CameraController
import com.motionamp.core.AmplificationPreset
import com.motionamp.core.FRAME_RATE_OPTIONS
import com.motionamp.core.RecordingTimePreset
import com.motionamp.core.SlowMotionPreset
import com.motionamp.core.StartDelayPreset
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CaptureScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val caps = remember {
        runCatching { CameraCapabilities.query(context) }
            .getOrElse { CameraCaps("0", listOf(30), emptyMap()) }
    }
    val controller = remember { CameraController(context, caps) }
    var isRecording by remember { mutableStateOf(false) }
    var recordProgress by remember { mutableFloatStateOf(0f) }
    val frameRate by viewModel.frameRate.collectAsState()
    val amplification by viewModel.amplification.collectAsState()
    val slowMotion by viewModel.slowMotion.collectAsState()
    val startDelay by viewModel.startDelay.collectAsState()
    val recordingTime by viewModel.recordingTime.collectAsState()
    var countdown by remember { mutableStateOf<Int?>(null) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) { onDispose { controller.close() } }

    // Another app can steal the camera (onDisconnected); re-acquire it on resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.reopenIfNeeded()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Countdown ring: recording auto-stops at the selected Recording Time.
    LaunchedEffect(isRecording) {
        if (isRecording) {
            val start = System.currentTimeMillis()
            val durationMs = recordingTime.millis // locked while recording
            while (isRecording) {
                recordProgress = ((System.currentTimeMillis() - start).toFloat() /
                    durationMs).coerceAtMost(1f)
                delay(100)
            }
        } else {
            recordProgress = 0f
        }
    }

    val listener = remember {
        object : CameraController.Listener {
            override fun onRecordingFinished(path: String) {
                isRecording = false
                viewModel.onRecordingFinished(path)
            }
            override fun onError(message: String) {
                isRecording = false
                viewModel.postError(message)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            st: SurfaceTexture, w: Int, h: Int,
                        ) {
                            st.setDefaultBufferSize(
                                controller.previewSize.width, controller.previewSize.height,
                            )
                            controller.open(Surface(st)) { viewModel.postError(it) }
                        }
                        override fun onSurfaceTextureSizeChanged(
                            st: SurfaceTexture, w: Int, h: Int,
                        ) = Unit
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            // The buffer is landscape (e.g. 1280x720) shown rotated in portrait: constrain
            // the view to the rotated aspect ratio and letterbox, otherwise it stretches.
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(
                    controller.previewSize.height.toFloat() / controller.previewSize.width,
                ),
        )

        Column(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "MagniMotion",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Video motion amplification",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Image(
                    painter = painterResource(R.drawable.ic_app_icon),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                IconButton(onClick = { showInfo = true }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Usage information",
                        tint = Color.White,
                    )
                }
            }
            PresetRow(
                options = FRAME_RATE_OPTIONS,
                selected = frameRate,
                enabled = { caps.supportedRates.contains(it) && !isRecording },
                label = { "$it fps" },
                onSelect = { viewModel.frameRate.value = it },
            )
            PresetRow(
                title = "Amplification",
                options = AmplificationPreset.entries,
                selected = amplification,
                enabled = { !isRecording },
                label = { it.label },
                onSelect = { viewModel.amplification.value = it },
            )
            PresetRow(
                title = "Playback\nSpeed",
                options = SlowMotionPreset.entries,
                selected = slowMotion,
                enabled = { !isRecording },
                label = { it.label },
                onSelect = { viewModel.slowMotion.value = it },
            )
            PresetRow(
                title = "Recording\nTime",
                options = RecordingTimePreset.entries,
                selected = recordingTime,
                enabled = { !isRecording },
                label = { it.label },
                onSelect = { viewModel.recordingTime.value = it },
            )
            PresetRow(
                title = "Start Delay",
                options = StartDelayPreset.entries,
                selected = startDelay,
                enabled = { !isRecording && countdown == null },
                label = { it.label },
                onSelect = { viewModel.startDelay.value = it },
            )
        }

        if (showInfo) {
            AlertDialog(
                onDismissRequest = { showInfo = false },
                confirmButton = {
                    TextButton(onClick = { showInfo = false }) { Text("Got it") }
                },
                title = { Text("How to use") },
                text = {
                    Text(
                        text = USAGE_TEXT,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                },
            )
        }

        // Start-delay countdown, big and central.
        countdown?.let { remaining ->
            Text(
                text = "$remaining",
                color = Color.White,
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Record button with countdown ring.
        Box(
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(32.dp).size(84.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isRecording) {
                CircularProgressIndicator(
                    progress = { recordProgress },
                    modifier = Modifier.size(84.dp),
                    color = Color.Red,
                )
            }
            fun beginRecording() {
                isRecording = true
                controller.startRecording(frameRate, recordingTime.millis, viewModel.rawFile, listener)
            }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .background(
                        when {
                            isRecording -> Color.DarkGray
                            countdown != null -> Color.Yellow
                            else -> Color.Red
                        },
                        CircleShape,
                    )
                    .clickable {
                        when {
                            isRecording -> controller.stopRecording()
                            countdown != null -> { // tap during countdown cancels it
                                countdownJob?.cancel()
                                countdownJob = null
                                countdown = null
                            }
                            startDelay.seconds == 0 -> beginRecording()
                            else -> countdownJob = scope.launch {
                                for (s in startDelay.seconds downTo 1) {
                                    countdown = s
                                    delay(1000)
                                }
                                countdown = null
                                beginRecording()
                            }
                        }
                    },
            )
        }
    }
}

private val USAGE_TEXT = """
    Record a short clip; MagniMotion then exaggerates any movement in it.

    Frame rate — higher rates capture fast motion better and play back slower (120 fps plays 4× slower than real time). Greyed-out rates aren't supported by this phone.

    Amplification — how much movement is exaggerated. Start with ×5; ×15 and ×30 suit very small motions (vibration, breathing, sway).

    Playback Speed — extra slow motion on top of the frame-rate slowdown (½× plays 2× slower again).

    Recording Time — how long the clip runs before stopping automatically.

    Start Delay — countdown before recording begins. Tap the yellow button to cancel it.

    Recording: tap the red button; it stops automatically after the selected Recording Time, or tap again to stop early. Focus locks the moment recording starts, so point at your subject first. Keep the phone as still as possible — prop it up or use a tripod. Small repetitive movements amplify most cleanly.

    Afterwards the result loops on screen. Tap the video for pause and seek controls, then Retake or Save to gallery.
""".trimIndent()

@Composable
private fun <T> PresetRow(
    options: List<T>,
    selected: T,
    enabled: (T) -> Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    title: String? = null,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (title != null) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                enabled = enabled(option),
            )
        }
    }
}
