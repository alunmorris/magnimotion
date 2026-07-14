# Motion Amplifier — Design Spec

Date: 2026-07-13 (amended 2026-07-14: EVM replaced by optical-flow warping after on-device testing)
Status: Approved approach; spec for implementation planning

## Overview

An Android app that records a short video clip and exaggerates motion in it by
optical-flow warping (Lagrangian motion magnification): a part displaced by d from
its rest position appears displaced by α·d — moving things visibly move further.
(v1 originally used linear Eulerian Video Magnification; on-device it read as edge
flicker rather than displacement, so it was replaced.) Flow is
**record → process → playback**, with a save-to-gallery option. No live amplified
preview in v1.

## Goals

- Record a clip (max 10 s) at a user-selected frame rate.
- Exaggerate displacement by a user-selected factor via optical-flow warping.
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
| Amplification | Low ×5 / Medium ×15 / High ×30 | Displacement gain α: a part moved d from rest appears moved α·d. |
| Slow motion | 1× / ½× / ¼× / ⅛× | Stacks on frame-rate normalisation: capture is first slowed to 30 fps effective playback (120 fps clip → 4× slower), then the preset multiplies that (½× on 120 fps → 8× slower). Baked into the processed file by stretching encode timestamps; playback and saved MP4 always match. |

Flow is computed against the first frame (rest pose) at a fixed 640-wide analysis
resolution (Farneback dense optical flow); the mean flow is subtracted so hand
shake and panning are not exaggerated. Not user-exposed in v1.

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
MediaCodec decode ──► frame (YUV luma, 720p)
        │   (analysis happens at pyramid resolutions ≤360p — no separate downscale pass)
Gaussian pyramid (4 levels, OpenCV) ──► Laplacian bands Lᵢ = Gᵢ − up(Gᵢ₊₁)
        │   (bands avoid double-amplifying overlapping spatial frequencies)
IIR temporal band-pass per band (two running low-pass images; band = 0.4–8 Hz)
        │
amplify band-passed luma (α = 5/15/30; chroma passes through untouched)
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
