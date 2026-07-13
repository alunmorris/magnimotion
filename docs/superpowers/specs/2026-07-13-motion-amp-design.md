# Motion Amplifier — Design Spec

Date: 2026-07-13
Status: Approved approach; spec for implementation planning

## Overview

An Android app that records a short video clip and exaggerates motion in it using
linear Eulerian Video Magnification (EVM), the MIT technique. Aimed at general
curiosity/experimentation: point it at anything (a wobbling table, someone breathing,
a running motor) and see invisible or subtle motion amplified. Flow is
**record → process → playback**, with a save-to-gallery option. No live amplified
preview in v1.

## Goals

- Record a clip (max 10 s) at a user-selected frame rate.
- Amplify motion by a user-selected factor via linear EVM.
- Play the result in-app, looped, with slow motion baked in.
- Save the processed clip to the device gallery on demand.
- Run on a range of devices: capabilities (frame rates) detected at runtime.

## Non-goals (v1)

- Live real-time amplified preview.
- Phase-based magnification (possible v2 quality upgrade).
- User-adjustable temporal frequency band (fixed internally).
- Editing, trimming, or multi-clip management.

## Presets

| Preset | Options | Notes |
|---|---|---|
| Frame rate | 30 / 60 / 120 / 240 fps | Queried from camera at runtime; unsupported chips greyed out. 120/240 use Camera2 constrained high-speed sessions. |
| Amplification | Low ×5 / Medium ×15 / High ×30 | EVM gain α applied to band-passed signal. |
| Slow motion | 1× / ½× / ¼× / ⅛× | Baked into the processed file by stretching encode timestamps; playback and saved MP4 always match. |

The temporal band-pass is fixed at a broad general-purpose band, 0.4–8 Hz,
implemented relative to the capture frame rate. Not user-exposed in v1.

## Architecture

Single-module Kotlin Android app, Jetpack Compose UI, minSdk 26, targetSdk 35.
Three screens managed as simple navigation states in one activity:

1. **Capture screen** — full-screen viewfinder (Camera2 via `SurfaceView`/`TextureView`
   interop in Compose), preset chips overlaid (frame rate, amplification, slow motion),
   record button. Recording auto-stops at 10 s; a countdown ring shows progress.
2. **Processing screen** — determinate progress bar (frames processed / total frames),
   cancel button.
3. **Playback screen** — looped playback of the processed clip (Media3 ExoPlayer),
   **Save to gallery** button (MediaStore `Movies/MotionAmp/` export), **Retake**
   button returning to capture.

### Components

- `CameraController` — wraps Camera2: capability query (normal fps ranges via
  `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`, high-speed via
  `ConstrainedHighSpeedCaptureSession` / `getHighSpeedVideoFpsRanges`), preview,
  and recording through `MediaRecorder` to an app-private MP4 at 720p.
  Single code path for all frame rates (CameraX is not used because it does not
  support constrained high-speed capture).
- `MotionAmplifier` — the EVM engine (pure processing, no Android UI deps beyond
  MediaCodec). Streaming design (see pipeline below). Reports per-frame progress
  via callback; cancellable.
- `VideoIo` — MediaCodec decoder (raw MP4 → frames) and MediaCodec/MediaMuxer
  encoder (frames → H.264 MP4 with timestamps stretched by the slow-motion factor).
- `MainViewModel` — owns app state (selected presets, recording state, processing
  progress, file paths), survives rotation.

### Data flow

```
Camera2 + MediaRecorder ──► raw.mp4 (app cache, 720p, chosen fps)
        │
MediaCodec decode ──► frame (YUV) ──► downscale to ≤480p analysis size
        │
Gaussian pyramid (≈4 levels, OpenCV)
        │
IIR temporal band-pass per level (two running low-pass images; band = 0.4–8 Hz)
        │
amplify band-passed luma (α = 5/15/30; chroma attenuated to avoid colour blotching)
        │
upscale amplified delta ──► add to original 720p frame ──► clamp
        │
MediaCodec encode + MediaMuxer (timestamps × slow-mo factor) ──► amplified.mp4
```

The IIR filter makes processing single-pass with constant memory: per pyramid level
only two low-pass state images are held, never the whole clip. OpenCV is used via
its Java/Kotlin bindings (OpenCV Android SDK dependency); no NDK/C++ code.

Files live in app cache: `raw.mp4` and `amplified.mp4`, overwritten each session.
The raw clip is kept until the next recording so a failed processing run can be
retried without re-recording.

## Error handling

- **Permissions**: camera only (clips are video-only, so no microphone permission),
  requested with a rationale screen; a permanent denial shows a "grant in settings" screen.
- **Capability query failure**: fall back to offering 30 fps only.
- **Recording failure** (MediaRecorder/Camera2 error): toast + return to idle
  capture state.
- **Processing failure** (codec error, OOM): error message + return to capture;
  raw clip retained for retry.
- **Cancel during processing**: partial output deleted; return to capture.
- **Save failure** (MediaStore): error snackbar; processed file still playable in-app.
- Input validation at component boundaries; errors logged with context (Logcat).

## Testing

- **JVM unit tests** for the numerically fragile core, using a desktop OpenCV
  build (openpnp `opencv` Maven artifact) so tests run without a device:
  - IIR band-pass: impulse/step response matches expected filter behaviour;
    a synthetic 2 Hz oscillation passes the band, DC and 15 Hz (at 60 fps) are rejected.
  - Pyramid build/collapse round-trip error below threshold.
  - End-to-end on synthetic frames: a sub-pixel oscillating square's motion
    amplitude increases ≈α after amplification.
- **On-device verification** via adb for camera, high-speed capture, codecs, and
  MediaStore export — these cannot be meaningfully emulated.

## Toolchain & build

- WSL2 CLI: Temurin JDK 17, Android SDK command-line tools + platform-tools,
  Gradle via wrapper (AGP 8.x).
- Debug APKs built here; installed to the phone with adb over wireless debugging
  (or USB via usbipd).
- Dependencies: Jetpack Compose (BOM), Media3 ExoPlayer, OpenCV Android SDK
  (Maven artifact), kotlinx-coroutines. Test-only: JUnit, openpnp OpenCV.

## Risks / open points

- High-speed (120/240 fps) sessions restrict preview surface combinations and
  sizes per device; the capability query must validate the full configuration,
  not just the fps list, before enabling a chip.
- MediaRecorder audio is disabled during high-speed capture (platform limitation);
  clips are recorded without audio at all rates for consistency — output is
  video-only, which also simplifies muxing with stretched timestamps.
- Processing speed target (≈1–4× clip duration at 480p analysis) to be confirmed
  on a real device early; analysis resolution is the tuning knob.
