// 130726 Initial implementation
// 140726 Fix: keep controls out of the system status/navigation bar areas (edge-to-edge insets)
package com.motionamp.app.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.motionamp.app.MainViewModel
import com.motionamp.app.camera.CameraCapabilities
import com.motionamp.app.camera.CameraCaps
import com.motionamp.app.camera.CameraController
import com.motionamp.core.AmplificationPreset
import com.motionamp.core.CaptureConstants
import com.motionamp.core.FRAME_RATE_OPTIONS
import com.motionamp.core.SlowMotionPreset
import kotlinx.coroutines.delay

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

    DisposableEffect(Unit) { onDispose { controller.close() } }

    // Countdown ring: recording auto-stops at MAX_RECORDING_MS.
    LaunchedEffect(isRecording) {
        if (isRecording) {
            val start = System.currentTimeMillis()
            while (isRecording) {
                recordProgress = ((System.currentTimeMillis() - start).toFloat() /
                    CaptureConstants.MAX_RECORDING_MS).coerceAtMost(1f)
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
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PresetRow(
                options = FRAME_RATE_OPTIONS,
                selected = frameRate,
                enabled = { caps.supportedRates.contains(it) && !isRecording },
                label = { "$it fps" },
                onSelect = { viewModel.frameRate.value = it },
            )
            PresetRow(
                options = AmplificationPreset.entries,
                selected = amplification,
                enabled = { !isRecording },
                label = { it.label },
                onSelect = { viewModel.amplification.value = it },
            )
            PresetRow(
                options = SlowMotionPreset.entries,
                selected = slowMotion,
                enabled = { !isRecording },
                label = { it.label },
                onSelect = { viewModel.slowMotion.value = it },
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
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .background(if (isRecording) Color.DarkGray else Color.Red, CircleShape)
                    .clickable {
                        if (isRecording) {
                            controller.stopRecording()
                        } else {
                            isRecording = true
                            controller.startRecording(frameRate, viewModel.rawFile, listener)
                        }
                    },
            )
        }
    }
}

@Composable
private fun <T> PresetRow(
    options: List<T>,
    selected: T,
    enabled: (T) -> Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
