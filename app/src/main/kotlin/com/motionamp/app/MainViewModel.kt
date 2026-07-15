// 130726 Initial implementation
// 140726 Fix: volatile processingJob (written from camera thread, read from main)
// 140726 Slow motion stacks on frame-rate normalisation via totalFactorFor
// 150726 Added startDelay preset state
package com.motionamp.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motionamp.app.video.AmplifyVideoUseCase
import com.motionamp.core.AmplificationPreset
import com.motionamp.core.SlowMotionPreset
import com.motionamp.core.StartDelayPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface UiState {
    data object Capture : UiState
    data class Processing(val progress: Float) : UiState
    data class Playback(val videoPath: String) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow<UiState>(UiState.Capture)
    val uiState: StateFlow<UiState> = _uiState

    val frameRate = MutableStateFlow(30)
    val amplification = MutableStateFlow(AmplificationPreset.MEDIUM)
    val slowMotion = MutableStateFlow(SlowMotionPreset.X1)
    val startDelay = MutableStateFlow(StartDelayPreset.S0)
    val errorMessage = MutableStateFlow<String?>(null)

    val rawFile: File get() = File(getApplication<Application>().cacheDir, "raw.mp4")
    private val outFile: File get() = File(getApplication<Application>().cacheDir, "amplified.mp4")

    @Volatile private var processingJob: Job? = null

    /** Called (from any thread) when the recorder has written rawFile. */
    fun onRecordingFinished(path: String) {
        _uiState.value = UiState.Processing(0f)
        processingJob = viewModelScope.launch {
            try {
                AmplifyVideoUseCase().run(
                    AmplifyVideoUseCase.Params(
                        inputPath = path,
                        outputPath = outFile.absolutePath,
                        captureFps = frameRate.value,
                        alpha = amplification.value.alpha,
                        // Total stretch: high-fps capture is normalised to 30 fps playback,
                        // then the preset slows it further (120 fps + half = 8x slower).
                        slowMotionFactor = slowMotion.value.totalFactorFor(frameRate.value),
                    ),
                ) { p -> _uiState.value = UiState.Processing(p) }
                _uiState.value = UiState.Playback(outFile.absolutePath)
            } catch (e: CancellationException) {
                _uiState.value = UiState.Capture
                throw e
            } catch (t: Throwable) {
                Log.e("MotionAmp", "processing failed", t)
                // raw.mp4 is kept: the user can hit record settings again and retry
                postError("Processing failed: ${t.message ?: t.javaClass.simpleName}")
                _uiState.value = UiState.Capture
            }
        }
    }

    fun cancelProcessing() {
        processingJob?.cancel()
    }

    fun retake() {
        _uiState.value = UiState.Capture
    }

    fun postError(msg: String) {
        errorMessage.value = msg
    }

    fun clearError() {
        errorMessage.value = null
    }
}
