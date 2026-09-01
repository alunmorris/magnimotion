# MagniMotion

An Android app that records a short video clip and exaggerates whatever motion is
in it — vibration, breathing, sway, small mechanical movement — turning it into
something clearly visible.

<img width="270" height="524" alt="MagniMotion_screenshot" src="https://github.com/user-attachments/assets/fbc11a1b-5228-49c3-a77d-b0be6369657d" />

https://youtube.com/video/wjAJO5KNZI4

It does this with optical-flow warping (Lagrangian motion magnification): dense
Farneback flow is computed between each frame and the clip's middle frame, the
mean (whole-frame) flow is subtracted so shake isn't so amplified,
and each pixel is resampled along the remaining flow scaled by the chosen gain. Results will be poor without a stable phone mount however.
Written by Alun Morris and Claude Code.
## Features

- **Frame rate** — 30/60/120/240 fps (device-dependent; unsupported rates are
  greyed out). Higher capture rates play back slower: a 120 fps clip plays 4×
  slower than real time before any additional slow-motion is applied.
- **Amplification** — ×5 / ×15 / ×30 gain on the detected motion.
- **Playback speed** — an additional ½× / ¼× / ⅛× slow-down stacked on top of the
  frame-rate slowdown.
- **Recording time** — 2 / 5 / 10 / 60 second clips, stopping automatically.
- **Start delay** — an optional 0 / 1 / 3 / 10 second countdown before recording,
  cancellable mid-countdown.
- Focus locks the moment recording starts (focus hunting mid-clip reads as motion
  to the amplifier).
- Saves processed clips to `Movies/MagniMotion` in the device gallery.

## Project structure

Two Gradle modules:

- **`:core`** — pure-JVM motion amplification algorithm (`FlowAmplifier`) and
  capture presets, with no Android dependency. Tested with headless OpenCV
  ([openpnp/opencv](https://github.com/openpnp/opencv)) via JUnit.
- **`:app`** — the Android application: Camera2 capture (including
  constrained-high-speed sessions for 120/240 fps), a decode → amplify → encode
  pipeline built on `MediaCodec`/`MediaMuxer`, and the Jetpack Compose UI.

## Building

Requires JDK 17 and the Android SDK (`compileSdk`/`targetSdk` 35, `minSdk` 26).

```
./gradlew :app:assembleDebug    # debug APK, all ABIs
./gradlew :app:assembleRelease  # release APK, arm64 only — needs a signing
                                 # config in keystore.properties (gitignored;
                                 # unsigned without it)
./gradlew :core:test            # algorithm unit tests
```
## Install APK without building

A ready-made APK is at [github.com/alunmorris/magnimotion/releases/download/v0.1/magnimotion-v0.1.apk](https://github.com/alunmorris/magnimotion/releases/download/v0.1/magnimotion-v0.1.apk)

Runs on Android 8.0 or later.

How to? https://anexplorer.io/install/apk-on-android

## Permissions

- `CAMERA` — required, to record clips.
- `WRITE_EXTERNAL_STORAGE` — only requested on Android 9 (API 28) and below,
  needed to write into the public `Movies` directory before scoped storage.

## License

[PolyForm Noncommercial 1.0.0](LICENSE) — free for noncommercial use
(personal, hobby, research, education, nonprofits); commercial use requires
the licensor's permission.
