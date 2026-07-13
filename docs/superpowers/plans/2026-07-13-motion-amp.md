# Motion Amplifier Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android app that records a ≤10 s clip at a chosen frame rate, amplifies motion via linear Eulerian Video Magnification, plays the result in-app with slow motion baked in, and can save it to the gallery.

**Architecture:** Two Gradle modules. `:core` is a pure-JVM Kotlin library holding the EVM maths (OpenCV Mat-based, unit-tested on desktop). `:app` is the Android app: Camera2 + MediaRecorder capture (single code path, constrained high-speed sessions for 120/240 fps), MediaCodec decode → amplify luma → MediaCodec/MediaMuxer encode with stretched timestamps, Compose UI with three screens driven by a ViewModel state machine.

**Tech Stack:** Kotlin 2.0.20, AGP 8.7.3, Gradle 8.9, JDK 17, Jetpack Compose (BOM 2024.09.03), Camera2, MediaCodec/MediaMuxer, Media3 ExoPlayer 1.4.1, OpenCV (`org.opencv:opencv:4.10.0` AAR on device; `org.openpnp:opencv:4.9.0-0` for JVM tests), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-07-13-motion-amp-design.md`

## Global Constraints

- minSdk 26, targetSdk 35, compileSdk 35.
- Recording: 1280×720, H.264, video-only (no audio), max duration 10 000 ms, app cache files `raw.mp4` / `amplified.mp4`.
- Presets: frame rate 30/60/120/240 fps (runtime-detected, unsupported greyed out); amplification α = 5/15/30; slow motion factor 1/2/4/8.
- EVM band-pass fixed at 0.4–8 Hz relative to capture fps; 4 Gaussian pyramid levels (3 Laplacian bands are temporally filtered — Laplacian bands derived from the Gaussian pyramid avoid double-amplifying overlapping spatial frequencies).
- Amplification applies to luma only; chroma passes through untouched.
- Slow motion is baked into the output file by multiplying encode timestamps by the factor.
- No NDK/C++; OpenCV via Java bindings only.
- All builds run from the repo root with this environment (every build/test command below assumes it):

```bash
export JAVA_HOME=$HOME/tools/jdk-17
export ANDROID_HOME=$HOME/tools/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

- Commit after every task. Changelog convention (per user CLAUDE.md): each source file starts with a `// DDMMYY <description>` changelog comment, UK date order (first entry `130726 Initial implementation`).

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle.properties, local.properties (gitignored), .gitignore
gradle/wrapper/…                     (generated)
core/build.gradle.kts
core/src/main/kotlin/com/motionamp/core/
    Presets.kt            — preset enums + EVM constants (no OpenCV dep)
    TemporalBandpass.kt   — streaming IIR band-pass over Mat frames
    GaussianPyramid.kt    — pyrDown decomposition + exact-size upsampling
    MotionAmplifier.kt    — per-frame EVM: pyramid → filter → amplify → reconstruct
core/src/test/kotlin/com/motionamp/core/   — JUnit tests for all of the above
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/values/themes.xml
app/src/main/kotlin/com/motionamp/app/
    MotionAmpApplication.kt  — loads OpenCV native lib
    MainActivity.kt          — permission gate + screen switching
    MainViewModel.kt         — UiState machine, processing job, presets state
    video/YuvUtils.kt        — Image(YUV_420_888) ↔ Mat helpers
    video/VideoDecoder.kt    — MP4 → YUV Image frames (MediaCodec)
    video/VideoEncoder.kt    — YUV frames → H.264 MP4 (MediaCodec + MediaMuxer)
    video/AmplifyVideoUseCase.kt — wires decoder → MotionAmplifier → encoder
    camera/CameraCapabilities.kt — supported frame-rate query
    camera/CameraController.kt   — Camera2 preview + MediaRecorder recording
    gallery/GalleryExporter.kt   — MediaStore export
    ui/CaptureScreen.kt, ui/ProcessingScreen.kt, ui/PlaybackScreen.kt
```

Notes for implementers with no Android background:

- **Why two OpenCV artifacts:** the Java API classes (`org.opencv.core.Mat` etc.) are identical in both; `:core` compiles against the openpnp desktop build (`compileOnly`) and its tests load desktop natives via `nu.pattern.OpenCV.loadLocally()`. On the phone, `:app` supplies the official Android AAR whose natives are loaded once in `MotionAmpApplication`.
- **Unit tests only exist in `:core`.** MediaCodec/Camera2/Compose classes are Android-framework stubs on the JVM and cannot run in plain unit tests; those tasks are verified by compilation (`assembleDebug`) and the final on-device checklist (Task 11). This is the planned deviation from strict TDD for framework-bound code.
- **Mat memory:** OpenCV Mats are native memory; every function below documents who releases what. Follow it exactly or the app leaks native heap.

---

### Task 1: Toolchain + project scaffold

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `local.properties`, `.gitignore`
- Create: `core/build.gradle.kts`, `core/src/main/kotlin/com/motionamp/core/Presets.kt` (placeholder object only — real content in Task 2)
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/kotlin/com/motionamp/app/MainActivity.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: a building two-module project. Later tasks add files and run `./gradlew :core:test` / `./gradlew assembleDebug`.

- [ ] **Step 1: Install JDK 17 (no sudo needed)**

```bash
mkdir -p ~/tools && cd ~/tools
curl -L -o jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
tar xzf jdk17.tar.gz && rm jdk17.tar.gz && mv jdk-17* jdk-17
~/tools/jdk-17/bin/java -version
```

Expected: `openjdk version "17.0.x"`.

- [ ] **Step 2: Install Android SDK command-line tools + packages**

```bash
mkdir -p ~/tools/android-sdk/cmdline-tools && cd ~/tools/android-sdk/cmdline-tools
curl -L -o tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
unzip -q tools.zip && rm tools.zip && mv cmdline-tools latest
export JAVA_HOME=$HOME/tools/jdk-17 ANDROID_HOME=$HOME/tools/android-sdk
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Expected: ends with `100% Computing updates...` and no error. (If `unzip` is missing: `python3 -m zipfile -e tools.zip .` then `chmod +x cmdline-tools/bin/*`.)

- [ ] **Step 3: Install Gradle 8.9 distribution (used once to generate the wrapper)**

```bash
cd ~/tools && curl -L -O https://services.gradle.org/distributions/gradle-8.9-bin.zip
unzip -q gradle-8.9-bin.zip && rm gradle-8.9-bin.zip
```

- [ ] **Step 4: Write root project files**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "motion-amp"
include(":app", ":core")
```

`build.gradle.kts` (root):

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx3g
android.useAndroidX=true
kotlin.code.style=official
```

`local.properties` (gitignored — machine-specific):

```properties
sdk.dir=/home/alun/tools/android-sdk
```

`.gitignore`:

```
.gradle/
build/
local.properties
*.apk
.superpowers/
```

- [ ] **Step 5: Write `:core` module stub**

`core/build.gradle.kts`:

```kotlin
plugins { id("org.jetbrains.kotlin.jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    compileOnly("org.openpnp:opencv:4.9.0-0")
    testImplementation("org.openpnp:opencv:4.9.0-0")
    testImplementation("junit:junit:4.13.2")
}
```

`core/src/main/kotlin/com/motionamp/core/Presets.kt` (placeholder so the module compiles; replaced in Task 2):

```kotlin
// 130726 Initial implementation
package com.motionamp.core

object Presets
```

- [ ] **Step 6: Write `:app` module**

`app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.motionamp.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.motionamp.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core"))
    implementation("org.opencv:opencv:4.10.0")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
    <uses-feature android:name="android.hardware.camera" android:required="true" />

    <application
        android:label="Motion Amp"
        android:theme="@style/Theme.MotionAmp">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait"
            android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

(The `android:name=".MotionAmpApplication"` attribute is added in Task 6 when that class exists. No launcher icon in v1 — the system default is used.)

`app/src/main/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.MotionAmp" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

`app/src/main/kotlin/com/motionamp/app/MainActivity.kt` (hello-world placeholder; replaced in Task 10):

```kotlin
// 130726 Initial implementation
package com.motionamp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Text("Motion Amp") } }
    }
}
```

- [ ] **Step 7: Generate the Gradle wrapper and build**

```bash
cd /home/alun/appdev/motion-amp
~/tools/gradle-8.9/bin/gradle wrapper --gradle-version 8.9
./gradlew assembleDebug
ls app/build/outputs/apk/debug/app-debug.apk
```

Expected: `BUILD SUCCESSFUL`, APK file listed. First run downloads dependencies (minutes).

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat: two-module Android project scaffold, builds debug APK"
```

---

### Task 2: Presets model (`:core`)

**Files:**
- Modify: `core/src/main/kotlin/com/motionamp/core/Presets.kt` (replace placeholder)
- Test: `core/src/test/kotlin/com/motionamp/core/PresetsTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces (used by `:app` UI and pipeline):
  - `enum class AmplificationPreset(val alpha: Double, val label: String)` — `LOW(5.0,"×5")`, `MEDIUM(15.0,"×15")`, `HIGH(30.0,"×30")`
  - `enum class SlowMotionPreset(val factor: Int, val label: String)` — `X1(1,"1×")`, `X2(2,"½×")`, `X4(4,"¼×")`, `X8(8,"⅛×")`
  - `object EvmConstants { LOW_CUTOFF_HZ=0.4; HIGH_CUTOFF_HZ=8.0; PYRAMID_LEVELS=4; MAX_RECORDING_MS=10_000 }`
  - `val FRAME_RATE_OPTIONS: List<Int>` — `[30, 60, 120, 240]`

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/com/motionamp/core/PresetsTest.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PresetsTest {
    @Test
    fun amplificationAlphasMatchSpec() {
        assertEquals(5.0, AmplificationPreset.LOW.alpha, 0.0)
        assertEquals(15.0, AmplificationPreset.MEDIUM.alpha, 0.0)
        assertEquals(30.0, AmplificationPreset.HIGH.alpha, 0.0)
    }

    @Test
    fun slowMotionFactorsMatchSpec() {
        assertEquals(listOf(1, 2, 4, 8), SlowMotionPreset.entries.map { it.factor })
    }

    @Test
    fun frameRateOptionsMatchSpec() {
        assertEquals(listOf(30, 60, 120, 240), FRAME_RATE_OPTIONS)
    }

    @Test
    fun evmConstantsMatchSpec() {
        assertEquals(0.4, EvmConstants.LOW_CUTOFF_HZ, 0.0)
        assertEquals(8.0, EvmConstants.HIGH_CUTOFF_HZ, 0.0)
        assertEquals(4, EvmConstants.PYRAMID_LEVELS)
        assertEquals(10_000, EvmConstants.MAX_RECORDING_MS)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests 'com.motionamp.core.PresetsTest'`
Expected: FAIL — unresolved references `AmplificationPreset` etc.

- [ ] **Step 3: Write the implementation**

Replace `core/src/main/kotlin/com/motionamp/core/Presets.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.core

/** Amplification gain applied to the band-passed motion signal. */
enum class AmplificationPreset(val alpha: Double, val label: String) {
    LOW(5.0, "×5"),
    MEDIUM(15.0, "×15"),
    HIGH(30.0, "×30"),
}

/** Playback slow-down; encode timestamps are multiplied by [factor]. */
enum class SlowMotionPreset(val factor: Int, val label: String) {
    X1(1, "1×"),
    X2(2, "½×"),
    X4(4, "¼×"),
    X8(8, "⅛×"),
}

/** Capture frame rates offered in the UI; availability is device-dependent. */
val FRAME_RATE_OPTIONS: List<Int> = listOf(30, 60, 120, 240)

object EvmConstants {
    /** Temporal band amplified, in Hz — broad general-purpose band per spec. */
    const val LOW_CUTOFF_HZ = 0.4
    const val HIGH_CUTOFF_HZ = 8.0

    /** Gaussian pyramid depth; the finest full-res level is never amplified. */
    const val PYRAMID_LEVELS = 4

    const val MAX_RECORDING_MS = 10_000
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests 'com.motionamp.core.PresetsTest'`
Expected: PASS (`BUILD SUCCESSFUL`).

- [ ] **Step 5: Commit**

```bash
git add core/ && git commit -m "feat(core): preset enums and EVM constants"
```

---

### Task 3: TemporalBandpass (`:core`)

**Files:**
- Create: `core/src/main/kotlin/com/motionamp/core/TemporalBandpass.kt`
- Test: `core/src/test/kotlin/com/motionamp/core/TemporalBandpassTest.kt`

**Interfaces:**
- Consumes: OpenCV `Mat` (`org.opencv.core`).
- Produces:
  - `class TemporalBandpass(lowCutoffHz: Double, highCutoffHz: Double, fps: Double)`
  - `fun filter(frame: Mat): Mat` — input CV_32FC1; all calls must use the same frame size. Returns a **new** CV_32FC1 Mat (caller releases). First call initialises state and returns zeros.
  - `fun release()` — frees internal state Mats.

Filter design (document in KDoc): two first-order IIR low-passes, `lp[n] = (1-r)·lp[n-1] + r·x[n]` with `r = 1 − exp(−2π·fc / fps)`; band = fast lp − slow lp.

- [ ] **Step 1: Write the failing tests**

`core/src/test/kotlin/com/motionamp/core/TemporalBandpassTest.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.math.PI
import kotlin.math.sin

class TemporalBandpassTest {
    companion object {
        @BeforeClass @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    /** Push a scalar sequence through the filter via 1x1 Mats; return the scalar outputs. */
    private fun run(filter: TemporalBandpass, samples: DoubleArray): DoubleArray {
        val out = DoubleArray(samples.size)
        val frame = Mat(1, 1, CvType.CV_32FC1)
        for (i in samples.indices) {
            frame.put(0, 0, floatArrayOf(samples[i].toFloat()))
            val band = filter.filter(frame)
            out[i] = band.get(0, 0)[0]
            band.release()
        }
        frame.release()
        return out
    }

    /** Peak amplitude of the last [tail] samples. */
    private fun tailAmplitude(x: DoubleArray, tail: Int): Double {
        val t = x.takeLast(tail)
        return (t.max() - t.min()) / 2.0
    }

    private fun sine(freqHz: Double, fps: Double, n: Int, amplitude: Double) =
        DoubleArray(n) { amplitude * sin(2.0 * PI * freqHz * it / fps) }

    @Test
    fun firstFrameReturnsZeros() {
        val f = TemporalBandpass(0.4, 8.0, 30.0)
        val out = run(f, doubleArrayOf(123.0))
        assertEquals(0.0, out[0], 1e-6)
        f.release()
    }

    @Test
    fun dcIsFullyRejected() {
        val f = TemporalBandpass(0.4, 8.0, 30.0)
        val out = run(f, DoubleArray(300) { 100.0 })
        assertTrue("DC leak: ${tailAmplitude(out, 200)}", tailAmplitude(out, 200) < 0.01)
        f.release()
    }

    @Test
    fun passbandSinePassesAt240Fps() {
        // 2 Hz is well inside 0.4-8 Hz; expect gain > 0.5 after settling.
        val f = TemporalBandpass(0.4, 8.0, 240.0)
        val out = run(f, sine(2.0, 240.0, 2400, 10.0))
        assertTrue("passband gain too low: ${tailAmplitude(out, 480)}", tailAmplitude(out, 480) > 5.0)
        f.release()
    }

    @Test
    fun highFrequencyIsAttenuatedAt240Fps() {
        // 100 Hz is far above the 8 Hz cutoff; first-order rolloff gives gain < 0.3.
        val f = TemporalBandpass(0.4, 8.0, 240.0)
        val out = run(f, sine(100.0, 240.0, 2400, 10.0))
        assertTrue("stopband gain too high: ${tailAmplitude(out, 480)}", tailAmplitude(out, 480) < 3.0)
        f.release()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests 'com.motionamp.core.TemporalBandpassTest'`
Expected: FAIL — `TemporalBandpass` unresolved.

- [ ] **Step 3: Write the implementation**

`core/src/main/kotlin/com/motionamp/core/TemporalBandpass.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import kotlin.math.PI
import kotlin.math.exp

/**
 * Streaming first-order IIR temporal band-pass over equally-sized CV_32FC1 frames.
 *
 * band[n] = lpFast[n] - lpSlow[n], where lp[n] = (1-r)*lp[n-1] + r*x[n]
 * and r = 1 - exp(-2*PI*fc / fps). Constant memory: only the two low-pass
 * state images are held, so a clip of any length streams through.
 *
 * Not thread-safe. Call [release] when done.
 */
class TemporalBandpass(lowCutoffHz: Double, highCutoffHz: Double, fps: Double) {
    init {
        require(lowCutoffHz < highCutoffHz) { "low cutoff must be below high cutoff" }
        require(fps > 0) { "fps must be positive" }
    }

    private val rSlow = 1.0 - exp(-2.0 * PI * lowCutoffHz / fps)
    private val rFast = 1.0 - exp(-2.0 * PI * highCutoffHz / fps)
    private var lpSlow: Mat? = null
    private var lpFast: Mat? = null

    /** Returns a new band-passed Mat (caller releases). First call returns zeros. */
    fun filter(frame: Mat): Mat {
        val slow = lpSlow
        val fast = lpFast
        val band = Mat()
        if (slow == null || fast == null) {
            lpSlow = frame.clone()
            lpFast = frame.clone()
            band.create(frame.rows(), frame.cols(), frame.type())
            band.setTo(Scalar(0.0))
            return band
        }
        Core.addWeighted(slow, 1.0 - rSlow, frame, rSlow, 0.0, slow)
        Core.addWeighted(fast, 1.0 - rFast, frame, rFast, 0.0, fast)
        Core.subtract(fast, slow, band)
        return band
    }

    fun release() {
        lpSlow?.release(); lpSlow = null
        lpFast?.release(); lpFast = null
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.motionamp.core.TemporalBandpassTest'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add core/ && git commit -m "feat(core): streaming IIR temporal band-pass filter"
```

---

### Task 4: GaussianPyramid (`:core`)

**Files:**
- Create: `core/src/main/kotlin/com/motionamp/core/GaussianPyramid.kt`
- Test: `core/src/test/kotlin/com/motionamp/core/GaussianPyramidTest.kt`

**Interfaces:**
- Consumes: OpenCV `Mat`, `Imgproc.pyrDown/pyrUp/resize`.
- Produces:
  - `object GaussianPyramid`
  - `fun decompose(src: Mat, levels: Int): List<Mat>` — `levels >= 1` new Mats, `[0]` is half resolution, `[i]` is `src / 2^(i+1)`. Caller releases each. `src` untouched.
  - `fun upsampleTo(src: Mat, targetWidth: Int, targetHeight: Int): Mat` — new Mat at exactly the target size (repeated `pyrUp`, then a final `resize` if sizes don't align). `src` untouched, caller releases result.

- [ ] **Step 1: Write the failing tests**

`core/src/test/kotlin/com/motionamp/core/GaussianPyramidTest.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat

class GaussianPyramidTest {
    companion object {
        @BeforeClass @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    /** 64x64 horizontal linear gradient 0..252 — smooth, so pyramid ops nearly preserve it. */
    private fun gradient(): Mat {
        val m = Mat(64, 64, CvType.CV_32FC1)
        for (y in 0 until 64) for (x in 0 until 64) {
            m.put(y, x, floatArrayOf(4f * x))
        }
        return m
    }

    @Test
    fun decomposeHalvesSizesPerLevel() {
        val src = gradient()
        val levels = GaussianPyramid.decompose(src, 4)
        assertEquals(listOf(32, 16, 8, 4), levels.map { it.cols() })
        assertEquals(listOf(32, 16, 8, 4), levels.map { it.rows() })
        levels.forEach { it.release() }; src.release()
    }

    @Test
    fun upsampleToHitsExactTargetSize() {
        val src = gradient()
        val levels = GaussianPyramid.decompose(src, 3) // coarsest is 8x8
        val up = GaussianPyramid.upsampleTo(levels[2], 64, 64)
        assertEquals(64, up.cols()); assertEquals(64, up.rows())
        up.release(); levels.forEach { it.release() }; src.release()
    }

    @Test
    fun downUpRoundTripPreservesSmoothImage() {
        val src = gradient()
        val levels = GaussianPyramid.decompose(src, 1)
        val up = GaussianPyramid.upsampleTo(levels[0], 64, 64)
        val diff = Mat()
        Core.absdiff(src, up, diff)
        val meanErr = Core.mean(diff).`val`[0]
        assertTrue("round-trip mean error $meanErr", meanErr < 3.0)
        diff.release(); up.release(); levels.forEach { it.release() }; src.release()
    }

    @Test
    fun oddSizesUpsampleCleanly() {
        // 45-row case occurs in the real pipeline (720 -> 360 -> 180 -> 90 -> 45).
        val src = Mat(45, 80, CvType.CV_32FC1)
        src.setTo(org.opencv.core.Scalar(7.0))
        val up = GaussianPyramid.upsampleTo(src, 160, 90)
        assertEquals(160, up.cols()); assertEquals(90, up.rows())
        up.release(); src.release()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests 'com.motionamp.core.GaussianPyramidTest'`
Expected: FAIL — `GaussianPyramid` unresolved.

- [ ] **Step 3: Write the implementation**

`core/src/main/kotlin/com/motionamp/core/GaussianPyramid.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.core

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Gaussian pyramid helpers. All returned Mats are owned by the caller. */
object GaussianPyramid {

    /** [levels] successively pyrDown-ed copies of [src]; result[0] is half resolution. */
    fun decompose(src: Mat, levels: Int): List<Mat> {
        require(levels >= 1) { "levels must be >= 1" }
        val out = ArrayList<Mat>(levels)
        var cur = src
        repeat(levels) {
            val down = Mat()
            Imgproc.pyrDown(cur, down)
            out.add(down)
            cur = down
        }
        return out
    }

    /**
     * Upsample [src] to exactly targetWidth x targetHeight: pyrUp doublings while
     * they fit, then one bilinear resize to absorb odd-size rounding.
     */
    fun upsampleTo(src: Mat, targetWidth: Int, targetHeight: Int): Mat {
        var cur = src.clone()
        while (cur.cols() * 2 <= targetWidth && cur.rows() * 2 <= targetHeight) {
            val up = Mat()
            Imgproc.pyrUp(cur, up)
            cur.release()
            cur = up
        }
        if (cur.cols() != targetWidth || cur.rows() != targetHeight) {
            val resized = Mat()
            Imgproc.resize(
                cur, resized,
                Size(targetWidth.toDouble(), targetHeight.toDouble()),
                0.0, 0.0, Imgproc.INTER_LINEAR,
            )
            cur.release()
            cur = resized
        }
        return cur
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.motionamp.core.GaussianPyramidTest'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add core/ && git commit -m "feat(core): Gaussian pyramid decompose and exact-size upsample"
```

---

### Task 5: MotionAmplifier (`:core`)

**Files:**
- Create: `core/src/main/kotlin/com/motionamp/core/MotionAmplifier.kt`
- Test: `core/src/test/kotlin/com/motionamp/core/MotionAmplifierTest.kt`

**Interfaces:**
- Consumes: `GaussianPyramid`, `TemporalBandpass`, `EvmConstants`.
- Produces (used by `AmplifyVideoUseCase` in Task 7):
  - `class MotionAmplifier(fps: Double, alpha: Double)`
  - `fun amplify(luma: Mat): Mat` — input CV_32FC1 luma (0..255 range), constant size across calls. Returns a **new** clamped CV_32FC1 Mat, same size (caller releases). Streaming/stateful: call once per frame in order.
  - `fun release()`

Algorithm per frame (KDoc this): 4-level Gaussian pyramid → 3 Laplacian bands `L[i] = G[i] − up(G[i+1])` (at 1/2, 1/4, 1/8 resolution; the 1/16 residual is not amplified, which avoids global brightness flicker) → each band through its own `TemporalBandpass` → upsample to full res and add `gain·band` to the frame. Finest band gets `0.5·alpha` (fine detail is mostly sensor noise), the other two get `alpha`.

- [ ] **Step 1: Write the failing tests**

`core/src/test/kotlin/com/motionamp/core/MotionAmplifierTest.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class MotionAmplifierTest {
    companion object {
        @BeforeClass @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    /**
     * 160x120 frame: background 50, a 200-valued square of half-width 20 centred at
     * (cx, 60), with 2-px soft (linear) edges so sub-pixel motion changes intensities.
     */
    private fun renderFrame(cx: Double): Mat {
        fun edge(d: Double) = (d / 2.0 + 0.5).coerceIn(0.0, 1.0)
        val m = Mat(120, 160, CvType.CV_32FC1)
        val row = FloatArray(160)
        for (y in 0 until 120) {
            for (x in 0 until 160) {
                val cov = edge(x - (cx - 20.0)) * edge((cx + 20.0) - x) *
                    edge(y - 40.0) * edge(80.0 - y)
                row[x] = (50.0 + 150.0 * cov).toFloat()
            }
            m.put(y, 0, row)
        }
        return m
    }

    /** Temporal standard deviation of pixel (row,col) over the given frames. */
    private fun pixelStd(frames: List<Mat>, row: Int, col: Int): Double {
        val v = frames.map { it.get(row, col)[0] }
        val mean = v.average()
        return sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
    }

    @Test
    fun staticSceneIsUnchanged() {
        val amp = MotionAmplifier(30.0, 15.0)
        val input = renderFrame(50.0)
        var last: Mat? = null
        repeat(30) {
            last?.release()
            last = amp.amplify(input)
        }
        val diff = Mat()
        Core.absdiff(input, last!!, diff)
        val maxDiff = Core.minMaxLoc(diff).maxVal
        assertTrue("static frame changed by $maxDiff", maxDiff < 0.01)
        diff.release(); input.release(); last!!.release(); amp.release()
    }

    @Test
    fun subPixelOscillationIsAmplified() {
        // 2 Hz, +/-0.5 px horizontal oscillation at 30 fps, alpha 15.
        val fps = 30.0
        val amp = MotionAmplifier(fps, 15.0)
        val inputs = ArrayList<Mat>()
        val outputs = ArrayList<Mat>()
        for (n in 0 until 90) {
            val cx = 50.0 + 0.5 * sin(2.0 * PI * 2.0 * n / fps)
            val frame = renderFrame(cx)
            inputs.add(frame)
            outputs.add(amp.amplify(frame))
        }
        // Pixel on the square's left edge (x = 30) where intensity slope is steepest.
        val inStd = pixelStd(inputs.subList(45, 90), 60, 30)
        val outStd = pixelStd(outputs.subList(45, 90), 60, 30)
        assertTrue("edge pixel input std $inStd should be > 5", inStd > 5.0)
        assertTrue("amplification ratio ${outStd / inStd} should exceed 2", outStd > inStd * 2.0)
        (inputs + outputs).forEach { it.release() }
        amp.release()
    }

    @Test
    fun outputStaysInValidRangeAndSize() {
        val amp = MotionAmplifier(30.0, 30.0)
        var out: Mat? = null
        for (n in 0 until 60) {
            out?.release()
            val f = renderFrame(50.0 + 2.0 * sin(2.0 * PI * 2.0 * n / 30.0))
            out = amp.amplify(f)
            f.release()
        }
        assertEquals(160, out!!.cols()); assertEquals(120, out!!.rows())
        val mm = Core.minMaxLoc(out)
        assertTrue("min ${mm.minVal}", mm.minVal >= 0.0)
        assertTrue("max ${mm.maxVal}", mm.maxVal <= 255.0)
        out!!.release(); amp.release()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests 'com.motionamp.core.MotionAmplifierTest'`
Expected: FAIL — `MotionAmplifier` unresolved.

- [ ] **Step 3: Write the implementation**

`core/src/main/kotlin/com/motionamp/core/MotionAmplifier.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * Streaming linear Eulerian Video Magnification on luma frames.
 *
 * Per frame: 4-level Gaussian pyramid; Laplacian bands L[i] = G[i] - up(G[i+1])
 * at 1/2, 1/4 and 1/8 resolution each go through their own temporal band-pass
 * (0.4-8 Hz); the band-passed signals, scaled by the per-level gain, are
 * upsampled to full resolution and added back. The 1/16 residual is not
 * amplified (avoids whole-frame brightness flicker); the finest band gets
 * half gain because fine detail is dominated by sensor noise.
 *
 * Stateful (IIR filters): feed frames in order, one call per frame, constant
 * frame size. Not thread-safe.
 */
class MotionAmplifier(fps: Double, alpha: Double) {
    private val levels = EvmConstants.PYRAMID_LEVELS
    private val bands = levels - 1
    private val filters = List(bands) {
        TemporalBandpass(EvmConstants.LOW_CUTOFF_HZ, EvmConstants.HIGH_CUTOFF_HZ, fps)
    }
    private val levelGains = DoubleArray(bands) { i -> if (i == 0) alpha * 0.5 else alpha }

    /** Input CV_32FC1 luma (0..255). Returns a new clamped Mat; caller releases. */
    fun amplify(luma: Mat): Mat {
        require(luma.type() == CvType.CV_32FC1) { "expected CV_32FC1 luma" }
        val gauss = GaussianPyramid.decompose(luma, levels)
        val delta = Mat.zeros(luma.rows(), luma.cols(), CvType.CV_32FC1)
        for (i in 0 until bands) {
            val up = GaussianPyramid.upsampleTo(gauss[i + 1], gauss[i].cols(), gauss[i].rows())
            val lap = Mat()
            Core.subtract(gauss[i], up, lap)
            up.release()
            val band = filters[i].filter(lap)
            lap.release()
            val full = GaussianPyramid.upsampleTo(band, luma.cols(), luma.rows())
            band.release()
            Core.addWeighted(delta, 1.0, full, levelGains[i], 0.0, delta)
            full.release()
        }
        gauss.forEach { it.release() }
        val out = Mat()
        Core.add(luma, delta, out)
        delta.release()
        Core.max(out, Scalar(0.0), out)
        Core.min(out, Scalar(255.0), out)
        return out
    }

    fun release() = filters.forEach { it.release() }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.motionamp.core.MotionAmplifierTest'`
Expected: PASS, 3 tests. If `subPixelOscillationIsAmplified` fails on the ratio assertion (algorithm behaviour, not a code error), print both std values, verify the maths by hand, and only then adjust the threshold — do not weaken it below 1.5.

- [ ] **Step 5: Run the full core suite and commit**

Run: `./gradlew :core:test`
Expected: PASS — all Presets, TemporalBandpass, GaussianPyramid and MotionAmplifier tests.

```bash
git add core/ && git commit -m "feat(core): streaming EVM motion amplifier with synthetic-motion test"
```

---

### Task 6: YuvUtils + VideoDecoder + VideoEncoder (`:app`)

Android-framework code: no JVM tests possible (MediaCodec is a stub off-device). Verified by compilation here and on-device in Task 11.

**Files:**
- Create: `app/src/main/kotlin/com/motionamp/app/MotionAmpApplication.kt`
- Create: `app/src/main/kotlin/com/motionamp/app/video/YuvUtils.kt`
- Create: `app/src/main/kotlin/com/motionamp/app/video/VideoDecoder.kt`
- Create: `app/src/main/kotlin/com/motionamp/app/video/VideoEncoder.kt`
- Modify: `app/src/main/AndroidManifest.xml` (add `android:name=".MotionAmpApplication"` to `<application>`)

**Interfaces:**
- Consumes: `org.opencv.core.Mat` (Android AAR), `android.media.*`.
- Produces (used by Task 7):
  - `object YuvUtils`
    - `fun lumaToMat(image: Image): Mat` — Y plane of a YUV_420_888 Image → new CV_32FC1 Mat (0..255).
    - `fun writeLuma(luma: Mat, dst: Image)` — CV_32FC1 Mat → Y plane of a writable YUV_420_888 Image.
    - `fun copyChroma(src: Image, dst: Image)` — copies U and V planes honouring row/pixel strides.
  - `class VideoDecoder(inputPath: String)`
    - `data class VideoInfo(val width: Int, val height: Int, val durationUs: Long, val rotationDegrees: Int)`
    - `fun readInfo(): VideoInfo`
    - `fun decode(onFrame: (image: Image, ptsUs: Long) -> Boolean)` — synchronous; Image valid only during callback; return `false` to abort.
  - `class VideoEncoder(width: Int, height: Int, frameRate: Int, bitRate: Int, outputPath: String, orientationDegrees: Int)`
    - `fun encodeFrame(ptsUs: Long, fill: (Image) -> Unit)`
    - `fun finish()` — EOS, drain, close muxer.
  - `class MotionAmpApplication : Application` — calls `OpenCVLoader.initLocal()` in `onCreate`.

- [ ] **Step 1: Write MotionAmpApplication and register it**

`app/src/main/kotlin/com/motionamp/app/MotionAmpApplication.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.app

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class MotionAmpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) {
            // Processing cannot work without the native lib; surface loudly in logs.
            Log.e("MotionAmp", "OpenCV native library failed to load")
        }
    }
}
```

In `AndroidManifest.xml`, change the `<application>` open tag to:

```xml
    <application
        android:name=".MotionAmpApplication"
        android:label="Motion Amp"
        android:theme="@style/Theme.MotionAmp">
```

- [ ] **Step 2: Write YuvUtils**

`app/src/main/kotlin/com/motionamp/app/video/YuvUtils.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.app.video

import android.media.Image
import org.opencv.core.CvType
import org.opencv.core.Mat

/** Conversions between YUV_420_888 Images and OpenCV Mats. Luma only is processed. */
object YuvUtils {

    /** Y plane -> new CV_32FC1 Mat (values 0..255). Caller releases. */
    fun lumaToMat(image: Image): Mat {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val rowStride = plane.rowStride
        val buf = plane.buffer
        val bytes = ByteArray(w)
        val mat8 = Mat(h, w, CvType.CV_8UC1)
        for (y in 0 until h) {
            buf.position(y * rowStride)
            buf.get(bytes, 0, w)
            mat8.put(y, 0, bytes)
        }
        val mat32 = Mat()
        mat8.convertTo(mat32, CvType.CV_32FC1)
        mat8.release()
        return mat32
    }

    /** Clamped CV_32FC1 Mat -> Y plane of writable [dst]. Sizes must match. */
    fun writeLuma(luma: Mat, dst: Image) {
        val plane = dst.planes[0]
        val w = dst.width
        val h = dst.height
        require(luma.cols() == w && luma.rows() == h) { "luma size mismatch" }
        val rowStride = plane.rowStride
        val buf = plane.buffer
        val mat8 = Mat()
        luma.convertTo(mat8, CvType.CV_8UC1)
        val bytes = ByteArray(w)
        for (y in 0 until h) {
            mat8.get(y, 0, bytes)
            buf.position(y * rowStride)
            buf.put(bytes, 0, w)
        }
        mat8.release()
    }

    /** Copy U and V planes from [src] to [dst], honouring each side's strides. */
    fun copyChroma(src: Image, dst: Image) {
        val cw = src.width / 2
        val ch = src.height / 2
        for (p in 1..2) {
            val sp = src.planes[p]
            val dp = dst.planes[p]
            val sBuf = sp.buffer
            val dBuf = dp.buffer
            for (y in 0 until ch) {
                for (x in 0 until cw) {
                    dBuf.put(y * dp.rowStride + x * dp.pixelStride,
                        sBuf.get(y * sp.rowStride + x * sp.pixelStride))
                }
            }
        }
    }
}
```

- [ ] **Step 3: Write VideoDecoder**

`app/src/main/kotlin/com/motionamp/app/video/VideoDecoder.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.app.video

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat

/** Synchronous MP4 -> YUV_420_888 frame decoder (video track only). */
class VideoDecoder(private val inputPath: String) {

    data class VideoInfo(
        val width: Int,
        val height: Int,
        val durationUs: Long,
        val rotationDegrees: Int,
    )

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        error("no video track in $inputPath")
    }

    fun readInfo(): VideoInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputPath)
            val format = extractor.getTrackFormat(selectVideoTrack(extractor))
            return VideoInfo(
                width = format.getInteger(MediaFormat.KEY_WIDTH),
                height = format.getInteger(MediaFormat.KEY_HEIGHT),
                durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                    format.getLong(MediaFormat.KEY_DURATION) else 0L,
                rotationDegrees = if (format.containsKey(MediaFormat.KEY_ROTATION))
                    format.getInteger(MediaFormat.KEY_ROTATION) else 0,
            )
        } finally {
            extractor.release()
        }
    }

    /**
     * Decode every frame in order. [onFrame]'s Image is only valid during the
     * call. Return false from [onFrame] to abort early. Throws on codec errors.
     */
    fun decode(onFrame: (image: Image, ptsUs: Long) -> Boolean) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(inputPath)
            val track = selectVideoTrack(extractor)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var aborted = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0 && !aborted) {
                        val image = codec.getOutputImage(outIdx)
                        if (image != null) {
                            val keepGoing = onFrame(image, info.presentationTimeUs)
                            image.close()
                            if (!keepGoing) aborted = true
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || aborted) {
                        outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }
}
```

- [ ] **Step 4: Write VideoEncoder**

`app/src/main/kotlin/com/motionamp/app/video/VideoEncoder.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.app.video

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer

/**
 * Synchronous YUV -> H.264 MP4 encoder. Slow motion is baked in by the caller
 * passing pre-stretched [encodeFrame] timestamps; the muxer just writes them.
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    frameRate: Int,
    bitRate: Int,
    outputPath: String,
    orientationDegrees: Int,
) {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()

    init {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height,
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (orientationDegrees != 0) muxer.setOrientationHint(orientationDegrees)
        codec.start()
    }

    /** Blocks for a free input buffer, lets [fill] write its YUV Image, queues it. */
    fun encodeFrame(ptsUs: Long, fill: (Image) -> Unit) {
        var inIdx = -1
        while (inIdx < 0) {
            inIdx = codec.dequeueInputBuffer(10_000)
            drainOutput(untilEos = false)
        }
        val image = codec.getInputImage(inIdx) ?: error("encoder input image unavailable")
        fill(image)
        codec.queueInputBuffer(inIdx, 0, width * height * 3 / 2, ptsUs, 0)
        drainOutput(untilEos = false)
    }

    /** Send EOS, drain everything, close codec and muxer. Call exactly once. */
    fun finish() {
        var inIdx = -1
        while (inIdx < 0) {
            inIdx = codec.dequeueInputBuffer(10_000)
            drainOutput(untilEos = false)
        }
        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drainOutput(untilEos = true)
        codec.stop()
        codec.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
    }

    private fun drainOutput(untilEos: Boolean) {
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, if (untilEos) 10_000 else 0)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIdx >= 0 -> {
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (bufferInfo.size > 0 && !isConfig) {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        muxer.writeSampleData(trackIndex, buf, bufferInfo)
                    }
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outIdx, false)
                    if (eos) return
                }
                else -> if (!untilEos) return // INFO_TRY_AGAIN_LATER; keep looping if draining to EOS
            }
        }
    }
}
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/ && git commit -m "feat(app): MediaCodec video decoder/encoder and YUV helpers"
```

---

### Task 7: AmplifyVideoUseCase (`:app`)

**Files:**
- Create: `app/src/main/kotlin/com/motionamp/app/video/AmplifyVideoUseCase.kt`

**Interfaces:**
- Consumes: `VideoDecoder`, `VideoEncoder`, `YuvUtils` (Task 6), `MotionAmplifier` (Task 5).
- Produces (used by `MainViewModel` in Task 10):
  - `class AmplifyVideoUseCase`
  - `data class Params(val inputPath: String, val outputPath: String, val captureFps: Int, val alpha: Double, val slowMotionFactor: Int)`
  - `suspend fun run(params: Params, onProgress: (Float) -> Unit)` — runs on `Dispatchers.Default`; honours coroutine cancellation (deletes partial output); throws on failure (partial output deleted). `onProgress` called with 0..1 from the worker thread.

- [ ] **Step 1: Write the implementation**

`app/src/main/kotlin/com/motionamp/app/video/AmplifyVideoUseCase.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.app.video

import com.motionamp.core.MotionAmplifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Decode raw.mp4 -> amplify luma per frame -> encode amplified.mp4 with stretched timestamps. */
class AmplifyVideoUseCase {

    data class Params(
        val inputPath: String,
        val outputPath: String,
        val captureFps: Int,
        val alpha: Double,
        val slowMotionFactor: Int,
    )

    suspend fun run(params: Params, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.Default) {
            require(params.captureFps > 0 && params.slowMotionFactor >= 1) { "bad params" }
            val decoder = VideoDecoder(params.inputPath)
            val info = decoder.readInfo()
            val totalFrames =
                max(1L, info.durationUs * params.captureFps / 1_000_000L).toInt()
            val amplifier = MotionAmplifier(params.captureFps.toDouble(), params.alpha)
            // High-speed clips carry far more frames per second of footage.
            val bitRate = if (params.captureFps >= 120) 20_000_000 else 12_000_000
            var encoder: VideoEncoder? = null
            var frames = 0
            try {
                decoder.decode { image, ptsUs ->
                    if (!isActive) return@decode false
                    val enc = encoder ?: VideoEncoder(
                        width = image.width,
                        height = image.height,
                        frameRate = max(1, params.captureFps / params.slowMotionFactor),
                        bitRate = bitRate,
                        outputPath = params.outputPath,
                        orientationDegrees = info.rotationDegrees,
                    ).also { encoder = it }
                    val luma = YuvUtils.lumaToMat(image)
                    val amplified = amplifier.amplify(luma)
                    enc.encodeFrame(ptsUs * params.slowMotionFactor) { dst ->
                        YuvUtils.writeLuma(amplified, dst)
                        YuvUtils.copyChroma(image, dst)
                    }
                    luma.release()
                    amplified.release()
                    frames++
                    onProgress(min(0.99f, frames.toFloat() / totalFrames))
                    true
                }
                ensureActive() // cancelled mid-decode: fall through to catch, not success
                encoder?.finish() ?: error("no frames decoded from ${params.inputPath}")
                encoder = null
                onProgress(1f)
            } catch (t: Throwable) {
                runCatching { encoder?.finish() }
                File(params.outputPath).delete()
                throw t
            } finally {
                amplifier.release()
            }
        }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/ && git commit -m "feat(app): amplify-video use case wiring decode -> EVM -> encode"
```

---

### Task 8: CameraCapabilities + CameraController (`:app`)

**Files:**
- Create: `app/src/main/kotlin/com/motionamp/app/camera/CameraCapabilities.kt`
- Create: `app/src/main/kotlin/com/motionamp/app/camera/CameraController.kt`

**Interfaces:**
- Consumes: Camera2 (`android.hardware.camera2.*`), `MediaRecorder`, `EvmConstants.MAX_RECORDING_MS`.
- Produces (used by `CaptureScreen` in Task 10):
  - `data class CameraCaps(val cameraId: String, val normalRates: List<Int>, val highSpeedRates: Map<Int, Size>)` with `val supportedRates: List<Int>` (sorted union).
  - `object CameraCapabilities { fun query(context: Context): CameraCaps }` — back camera; 30/60 from fixed AE fps ranges; 120/240 from constrained high-speed ranges with a chosen recording size (largest ≤ 1280 wide).
  - `class CameraController(context: Context, caps: CameraCaps)`
    - `interface Listener { fun onRecordingFinished(path: String); fun onError(message: String) }`
    - `val previewSize: Size`
    - `fun open(surface: Surface, onError: (String) -> Unit)`
    - `fun startRecording(frameRate: Int, outputFile: File, listener: Listener)`
    - `fun stopRecording()` — also invoked automatically at the 10 s cap.
    - `fun close()`

- [ ] **Step 1: Write CameraCapabilities**

`app/src/main/kotlin/com/motionamp/app/camera/CameraCapabilities.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.app.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size

data class CameraCaps(
    val cameraId: String,
    val normalRates: List<Int>,          // subset of {30, 60}
    val highSpeedRates: Map<Int, Size>,  // 120/240 -> recording size
) {
    val supportedRates: List<Int> get() = (normalRates + highSpeedRates.keys).sorted()
}

object CameraCapabilities {

    /** Query the back camera's frame-rate support. Throws if no camera; caller
     *  falls back to CameraCaps(id, [30], empty) on any failure. */
    fun query(context: Context): CameraCaps {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: mgr.cameraIdList.first()
        val chars = mgr.getCameraCharacteristics(id)

        val aeRanges =
            chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?: emptyArray()
        // Fixed-rate ranges only: a (30,30) range guarantees steady sampling for EVM.
        val normal = listOf(30, 60).filter { r ->
            aeRanges.any { it.lower == r && it.upper == r }
        }.ifEmpty { listOf(30) }

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val high = mutableMapOf<Int, Size>()
        map?.highSpeedVideoFpsRanges?.forEach { range ->
            if (range.lower == range.upper && (range.upper == 120 || range.upper == 240)) {
                val sizes = map.getHighSpeedVideoSizesFor(range)
                val best = sizes.filter { it.width <= 1280 }.maxByOrNull { it.width * it.height }
                    ?: sizes.minByOrNull { it.width * it.height }
                if (best != null) high[range.upper] = best
            }
        }
        return CameraCaps(id, normal, high)
    }
}
```

- [ ] **Step 2: Write CameraController**

`app/src/main/kotlin/com/motionamp/app/camera/CameraController.kt`:

```kotlin
// 130726 Initial implementation
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
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var previewSurface: Surface? = null
    private var listener: Listener? = null
    private var recordingPath: String? = null
    @Volatile var isRecording = false
        private set

    /** High-speed capture requires preview and recorder surfaces at the same size. */
    val previewSize: Size = caps.highSpeedRates.values.firstOrNull() ?: Size(1280, 720)

    private val sensorOrientation: Int =
        cameraManager.getCameraCharacteristics(caps.cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    @SuppressLint("MissingPermission") // caller gates on CAMERA permission
    fun open(surface: Surface, onError: (String) -> Unit) {
        previewSurface = surface
        cameraManager.openCamera(caps.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
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
                listener?.onError("Preview configuration failed")
            }
        }, handler)
    }

    fun startRecording(frameRate: Int, outputFile: File, listener: Listener) {
        val cam = device ?: return listener.onError("Camera not ready")
        val surface = previewSurface ?: return listener.onError("No preview surface")
        if (isRecording) return
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
                } catch (e: Exception) {
                    failRecording("Recording start failed: ${e.message}")
                }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
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
        if (!isRecording) return
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
        recorder?.release(); recorder = null
        isRecording = false
        listener?.onError(msg)
        startPreviewSession()
    }

    fun close() {
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
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/ && git commit -m "feat(app): Camera2 capture with high-speed session support"
```

---

### Task 9: GalleryExporter (`:app`)

**Files:**
- Create: `app/src/main/kotlin/com/motionamp/app/gallery/GalleryExporter.kt`

**Interfaces:**
- Consumes: `MediaStore`, app cache file from Task 7.
- Produces (used by `PlaybackScreen` in Task 10):
  - `object GalleryExporter { fun export(context: Context, file: File): Boolean }` — copies the MP4 into `Movies/MotionAmp/` (MediaStore on API 29+, public dir + media scan on 26–28). Returns success. Caller handles the WRITE_EXTERNAL_STORAGE permission on API < 29.

- [ ] **Step 1: Write the implementation**

`app/src/main/kotlin/com/motionamp/app/gallery/GalleryExporter.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.app.gallery

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Copies a processed clip into the device gallery under Movies/MotionAmp. */
object GalleryExporter {

    fun export(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        val name = "motionamp_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        return try {
            if (Build.VERSION.SDK_INT >= 29) exportViaMediaStore(context, file, name)
            else exportLegacy(context, file, name)
        } catch (e: Exception) {
            Log.e("MotionAmp", "gallery export failed", e)
            false
        }
    }

    private fun exportViaMediaStore(context: Context, file: File, name: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MotionAmp")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: return false
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return true
    }

    /** API 26-28: direct copy to the public Movies dir + media scan. */
    private fun exportLegacy(context: Context, file: File, name: String): Boolean {
        val dir = Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            .resolve("MotionAmp")
        if (!dir.exists() && !dir.mkdirs()) return false
        val dst = dir.resolve(name)
        file.copyTo(dst, overwrite = true)
        MediaScannerConnection.scanFile(
            context, arrayOf(dst.absolutePath), arrayOf("video/mp4"), null,
        )
        return true
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/ && git commit -m "feat(app): gallery export via MediaStore with pre-Q fallback"
```

---

### Task 10: ViewModel, screens, permission flow (`:app`)

**Files:**
- Create: `app/src/main/kotlin/com/motionamp/app/MainViewModel.kt`
- Create: `app/src/main/kotlin/com/motionamp/app/ui/CaptureScreen.kt`
- Create: `app/src/main/kotlin/com/motionamp/app/ui/ProcessingScreen.kt`
- Create: `app/src/main/kotlin/com/motionamp/app/ui/PlaybackScreen.kt`
- Modify: `app/src/main/kotlin/com/motionamp/app/MainActivity.kt` (replace placeholder)

**Interfaces:**
- Consumes: everything from Tasks 2, 7, 8, 9.
- Produces: the complete app. Key types:
  - `sealed interface UiState { data object Capture; data class Processing(val progress: Float); data class Playback(val videoPath: String) }`
  - `class MainViewModel(app: Application) : AndroidViewModel` — `uiState: StateFlow<UiState>`, `frameRate: MutableStateFlow<Int>`, `amplification: MutableStateFlow<AmplificationPreset>`, `slowMotion: MutableStateFlow<SlowMotionPreset>`, `errorMessage: MutableStateFlow<String?>`, `val rawFile: File`, `fun onRecordingFinished(path: String)`, `fun cancelProcessing()`, `fun retake()`, `fun postError(msg: String)`
  - `@Composable fun CaptureScreen(viewModel: MainViewModel)`
  - `@Composable fun ProcessingScreen(progress: Float, onCancel: () -> Unit)`
  - `@Composable fun PlaybackScreen(videoPath: String, onRetake: () -> Unit, onSaved: (Boolean) -> Unit)`

- [ ] **Step 1: Write MainViewModel**

`app/src/main/kotlin/com/motionamp/app/MainViewModel.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motionamp.app.video.AmplifyVideoUseCase
import com.motionamp.core.AmplificationPreset
import com.motionamp.core.SlowMotionPreset
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
    val errorMessage = MutableStateFlow<String?>(null)

    val rawFile: File get() = File(getApplication<Application>().cacheDir, "raw.mp4")
    private val outFile: File get() = File(getApplication<Application>().cacheDir, "amplified.mp4")

    private var processingJob: Job? = null

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
                        slowMotionFactor = slowMotion.value.factor,
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
```

- [ ] **Step 2: Write ProcessingScreen**

`app/src/main/kotlin/com/motionamp/app/ui/ProcessingScreen.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ProcessingScreen(progress: Float, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Amplifying motion…")
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        )
        Text("${(progress * 100).toInt()}%")
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}
```

- [ ] **Step 3: Write PlaybackScreen**

`app/src/main/kotlin/com/motionamp/app/ui/PlaybackScreen.kt`:

```kotlin
// 130726 Initial implementation
package com.motionamp.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
import java.io.File

@Composable
fun PlaybackScreen(videoPath: String, onRetake: () -> Unit, onSaved: (Boolean) -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(videoPath))))
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    fun save() = onSaved(GalleryExporter.export(context, File(videoPath)))

    // API 26-28 need WRITE_EXTERNAL_STORAGE granted before writing to public Movies.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) save() else onSaved(false) }

    Column(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
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
```

- [ ] **Step 4: Write CaptureScreen**

`app/src/main/kotlin/com/motionamp/app/ui/CaptureScreen.kt`:

```kotlin
// 130726 Initial implementation
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.motionamp.core.EvmConstants
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
                    EvmConstants.MAX_RECORDING_MS).coerceAtMost(1f)
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
            modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
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
            modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp).size(84.dp),
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
```

- [ ] **Step 5: Replace MainActivity**

`app/src/main/kotlin/com/motionamp/app/MainActivity.kt`:

```kotlin
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
```

- [ ] **Step 6: Build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/ && git commit -m "feat(app): three-screen Compose UI with permission flow"
```

---

### Task 11: On-device verification

No code. Requires the phone: enable Developer options → Wireless debugging (or USB via usbipd on Windows).

**Files:** none (checklist results recorded in the commit message / conversation).

- [ ] **Step 1: Connect the device**

```bash
adb pair <ip>:<pairing-port>   # code shown on the phone (wireless debugging)
adb connect <ip>:<port>
adb devices                     # expect: one device, state "device"
```

- [ ] **Step 2: Install and launch**

```bash
./gradlew installDebug
adb shell am start -n com.motionamp.app/.MainActivity
adb logcat -s MotionAmp AndroidRuntime *:E &
```

Expected: app opens to the camera permission prompt; after granting, live viewfinder with three preset chip rows. No `OpenCV native library failed to load` in logcat.

- [ ] **Step 3: Verify the capture → process → playback loop (30 fps)**

Record ~5 s of a scene with subtle motion (e.g. a hand resting on a table, breathing). Expected: countdown ring runs; processing screen shows advancing progress; playback loops with visibly exaggerated motion at ×15.

- [ ] **Step 4: Verify each supported frame rate chip**

For each enabled chip (30/60/120/240): record a short clip and confirm processing completes and plays. Unsupported rates must appear greyed out, not crash. Note which rates the device supports.

- [ ] **Step 5: Verify slow motion and amplification presets**

Record with ¼× slow motion: playback should be 4× slower and the saved duration 4× the recorded one. Record the same scene at ×5 and ×30: the ×30 clip must show clearly stronger motion (and likely more noise).

- [ ] **Step 6: Verify save, cancel, and error paths**

- Save to gallery → toast "Saved to gallery"; clip visible in the Photos/Gallery app under Movies/MotionAmp and plays there.
- Cancel during processing → returns to capture, no crash; `amplified.mp4` removed (`adb shell ls /data/data/com.motionamp.app/cache/` via `run-as com.motionamp.app` if needed).
- 10 s auto-stop: record without pressing stop; recording must end itself at 10 s and proceed to processing.

- [ ] **Step 7: Record results and commit any fixes**

Fix any failures found (each fix follows red-green where a JVM test can capture it; device-only fixes get verified by re-running the relevant checklist step). Then:

```bash
git add -A && git commit -m "chore: on-device verification pass on <device model>"
```

---

## Post-plan notes for the executor

- Processing speed target from the spec: 1–4× clip duration at these resolutions. If a 10 s / 30 fps clip takes much longer than ~40 s on the test device, the knob is analysis size — add one extra `pyrDown` of the luma before `MotionAmplifier.amplify` and upsample the delta accordingly (do not change the plan's interfaces; note the change in the spec).
- 120/240 fps behaviour varies per device. If `createConstrainedHighSpeedCaptureSession` fails on the test phone despite advertised support, the chip should still fail gracefully via `failRecording` — capture the logcat and treat deeper high-speed work as a follow-up, not a blocker for v1.
- If Maven coordinates `org.opencv:opencv:4.10.0` fail to resolve, use the latest 4.x that exists on Maven Central (search "org.opencv opencv") and keep `org.openpnp:opencv` at the closest matching 4.x for tests.

