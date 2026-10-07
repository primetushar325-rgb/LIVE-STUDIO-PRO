# LIVE VIP

Professional mobile live broadcasting for Android — **direct RTMP / RTMPS ingest**
(YouTube compatible). No relay server, no simulated LIVE state, no fake metrics.

## Pipeline

```
STREAM PROFILE -> VIDEO PLAYLIST -> HW VIDEO DECODER -> GPU COMPOSITOR (OpenGL ES)
      -> PREVIEW SURFACE + ENCODER INPUT SURFACE -> HARDWARE H.264 -> FLV
      -> DIRECT RTMP/RTMPS -> DESTINATION (e.g. YouTube)
AAC AUDIO (video audio + optional mic) -> MASTER CLOCK -> FLV -> same transport
```

Lifecycle owner: `LiveStreamingForegroundService` -> `LiveStreamingEngine` ->
state machine + network controller + reconnect controller. The Activity is UI only.

## Key design rules

- One `CompositionState` drives **both** preview and encoder → what you see is what you send.
- The playlist is one continuous source: switching videos or looping **never** restarts the
  encoder, the audio pipeline or the RTMP socket, and output PTS never resets (`MasterClock`).
- Explicit state machine: `IDLE → PREPARING → VIDEO_READY → AUDIO_READY → ENCODER_READY →
  CONNECTING → CONNECTED → SENDING → STREAMING` with `NETWORK_LOST → RECONNECTING` recovery.
  `STREAMING` is only reported when real packets are accepted by the transport.
- Reconnect backoff 1s, 2s, 4s, 8s, 15s then `ERROR` — never an infinite loop, stale sockets
  are always closed first (no duplicate sockets / encoders / services).
- Stream keys are stored in keystore-backed encrypted preferences and are never logged
  or shown in full.
- Diagnostics screen exposes real decoder/encoder/audio/transport counters only.

## Modules (app/src/main/java/com/livevip/app)

| Package | Responsibility |
|---|---|
| `core` | state machine, error codes, `CompositionState`, `MasterClock` |
| `data` | stream profiles, persistence, encrypted key storage |
| `video` | `VideoSourceController` (MediaExtractor + MediaCodec, playlist, loop) |
| `gl` | `EglCore`, OES shader program, `LiveCompositor` |
| `encoder` | `VideoEncoderController` (hardware H.264, CBR, surface input) |
| `audio` | `AudioPipeline` (AAC, video audio + mic mixing) |
| `rtmp` | AMF0, `RtmpClient` (handshake/publish), `FlvPackager`, `RtmpTransport` |
| `engine` | `LiveStreamingEngine`, network/reconnect controllers, stats |
| `service` | `LiveStreamingForegroundService` |
| `ui` | Compose UI: home, profile editor, dashboard, diagnostics |

## Build

CI builds a **debug APK** on every push: `.github/workflows/android-build.yml`
(unit tests → lint → `assembleDebug` → APK artifact `live-vip-debug-apk`).

Locally: `./gradlew assembleDebug` (requires Android SDK 34, JDK 17).

## Device verification checklist (must be run on real hardware)

Preview without internet, 16:9 and 9:16 output, drag/pinch/fit/fill/reset,
H.264 + AAC output, direct RTMP/RTMPS to YouTube Live Control Room, playlist
transition and 3-loop test without encoder/transport restart, reconnect after
airplane-mode toggle, background + screen-off streaming, automatic stop at the
configured maximum duration, 1 hour continuous stability run.

<!-- build: v1.4.0-mediafix rebuild 2026-10-06T21:42Z -->
