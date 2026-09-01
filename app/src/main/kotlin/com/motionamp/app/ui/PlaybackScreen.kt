// 130726 Initial implementation
// 130726 Fix: run gallery export on Dispatchers.IO instead of the main thread
// 140726 Fix: keep buttons out of the system navigation bar area (edge-to-edge insets)
// 150726 Player controls enabled: time bar, seek, play/pause
// 160726 Saved file name carries the capture settings tag (e.g. f120m15)
// 010926 Landscape-recorded clips play back in landscape instead of the portrait lock
package com.motionamp.app.ui

import android.Manifest
import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.motionamp.app.gallery.GalleryExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** True if the clip's stored rotation means it plays back wider than it is tall. */
private fun isLandscapeVideo(path: String): Boolean {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        val (displayWidth, displayHeight) =
            if (rotation == 90 || rotation == 270) height to width else width to height
        displayWidth > displayHeight
    } catch (e: Exception) {
        false
    } finally {
        retriever.release()
    }
}

@Composable
fun PlaybackScreen(
    videoPath: String,
    nameTag: String,
    onRetake: () -> Unit,
    onSaved: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(videoPath))))
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }

    // The activity is portrait-locked for capture; a landscape-recorded clip
    // needs the screen rotated to landscape to display at full size.
    LaunchedEffect(videoPath) {
        val landscape = withContext(Dispatchers.IO) { isLandscapeVideo(videoPath) }
        activity?.requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            player.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    fun save() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                GalleryExporter.export(context, File(videoPath), nameTag)
            }
            onSaved(ok)
        }
    }

    // API 26-28 need WRITE_EXTERNAL_STORAGE granted before writing to public Movies.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) save() else onSaved(false) }

    Column(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    // Built-in controls: play/pause, time bar with seek. Tap video to show.
                    useController = true
                    controllerShowTimeoutMs = 2500
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            OutlinedButton(onClick = onRetake) { Text("Retake") }
            Button(onClick = {
                val needsLegacyPermission = Build.VERSION.SDK_INT < 29 &&
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) != PackageManager.PERMISSION_GRANTED
                if (needsLegacyPermission) {
                    permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    save()
                }
            }) { Text("Save to gallery") }
        }
    }
}
