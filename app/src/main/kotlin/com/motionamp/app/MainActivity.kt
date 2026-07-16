// 130726 Initial implementation
package com.motionamp.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.motionamp.app.ui.CaptureScreen
import com.motionamp.app.ui.PlaybackScreen
import com.motionamp.app.ui.ProcessingScreen

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AppRoot(viewModel) } }
    }
}

@Composable
fun AppRoot(viewModel: MainViewModel) {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCamera = granted
        denied = !granted
    }
    LaunchedEffect(Unit) { if (!hasCamera) launcher.launch(Manifest.permission.CAMERA) }

    // One-shot error toasts from any layer.
    val error by viewModel.errorMessage.collectAsState()
    LaunchedEffect(error) {
        error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    when {
        hasCamera -> {
            val state by viewModel.uiState.collectAsState()
            when (val s = state) {
                is UiState.Capture -> CaptureScreen(viewModel)
                is UiState.Processing -> ProcessingScreen(s.progress) {
                    viewModel.cancelProcessing()
                }
                is UiState.Playback -> PlaybackScreen(
                    videoPath = s.videoPath,
                    nameTag = viewModel.saveTag,
                    onRetake = { viewModel.retake() },
                    onSaved = { ok ->
                        viewModel.postError(
                            if (ok) "Saved to gallery (Movies/MotionAmp)" else "Save failed",
                        )
                    },
                )
            }
        }
        denied -> PermissionScreen { launcher.launch(Manifest.permission.CAMERA) }
        else -> Unit // waiting for the permission dialog
    }
}

@Composable
private fun PermissionScreen(onRequestAgain: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Motion Amp needs the camera to record clips for motion amplification.")
        Button(onClick = onRequestAgain, modifier = Modifier.padding(top = 16.dp)) {
            Text("Grant camera access")
        }
        Button(
            onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Open app settings")
        }
    }
}
