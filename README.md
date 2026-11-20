# Nyte Recorder

Local-only Android screen recorder with internal (game) audio. Built for one phone, the nubia Neo 5G
(Unisoc T820, Android 13), with one goal: record gameplay for YouTube without disturbing the game.
Not for the Play Store. No internet permission.

## How it stays light
- MediaProjection -> VirtualDisplay -> **hardware** MediaCodec input Surface: no CPU frame copies, no preview.
- Hardware encoder picked at runtime via `MediaCodecList` (software `c2.android.*` / `OMX.google.*` are skipped).
- H.264 High profile, VBR, 2 s keyframes, realtime priority, low latency; frames above the target fps are dropped
  before the encoder (useful when the game runs at 120 Hz).
- Audio: `AudioPlaybackCapture` (media, game, unknown usages) -> AAC-LC 48 kHz stereo 192 kbps. Mic is optional (off).
- Encoding, muxing and audio each run on their own threads; the UI is closed while recording.

## Defaults
1080p (1920 px wide, native aspect, about 1920x864 here) · 60 fps · 12 Mbps · H.264 · landscape · mic off · bubble off.
Other options: Native 2408x1080, 720p, 30 fps, 8/16 Mbps, H.265 (only if a hardware encoder exists).

## Output
`Movies/NyteRecorder/NyteRec_yyyyMMdd_HHmmss.mp4`. Stop from the notification, the app, or the optional bubble.

## Known limitations
- Apps that opt out of capture (`allowAudioPlaybackCapture=false`) or use `USAGE` types outside media/game/unknown are silent.
- The optional bubble and the notification shade are visible in the video (they are part of the screen).
- The video canvas size is fixed when recording starts. In Landscape mode recording waits until the screen is landscape; if you go back to portrait mid-recording, the portrait screen is pillarboxed (as the system scales it to fit).

## Build
GitHub Actions builds the debug APK on every push (artifact `nyterecorder-debug-apk`, also the `ci-latest` pre-release).
