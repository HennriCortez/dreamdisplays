# DreamDisplays Android Playback Handoff

Date: 2026-09-04

## Repositories

### DreamDisplays mod

- Local path: `D:\Zetroc\dreamdisplay\dreamdisplays`
- Remote: `https://github.com/HennriCortez/dreamdisplays.git`
- Working branch: `Plugin-APK`
- Purpose: Minecraft Fabric mod and shared media/player code
- Target build: Fabric 26.2 only

### FFmpeg plugin APK

- Local path: `D:\Zetroc\FFmpegPlugin\FFmpegPlugin`
- Remote: `https://github.com/HennriCortez/FFmpegPlugin.git`
- Working branch: `oldffmpeg`
- Purpose: Android APK containing the arm64 FFmpeg and audio native binaries

Do not mix files or commits between these repositories. APK changes belong in `FFmpegPlugin`; mod changes belong in `dreamdisplays`.

## Device and launcher

Observed test environment:

- Zalith Launcher 2.4.9_hotfix1
- Android API 34, Android 14 in the current test
- ARM64 device: Samsung SM-X810
- Minecraft 26.2
- Fabric Loader 0.19.3
- MobileGlues OpenGL 4.0 / OpenGL ES 3.2
- Internal Java 25 guest JVM

Important environment values:

- `POJAV_NATIVEDIR` is present
- `POJAV_FFMPEG_PATH` points to the installed plugin `lib/arm64/libffmpeg.so`
- `LIBGL_EGL=libmobileglues.so`
- `POJAV_RENDERER=opengles3`
- `os.name=Linux`
- `os.version=Android-14`

Android versions must be detected generically. Never check specifically for Android 14; use an `android` substring check plus Zalith `POJAV_*` variables.

## Current playback architecture

### FFmpeg video

The mod launches FFmpeg as a subprocess and reads raw frames from stdout. The video path is software-only on Android by design:

```text
FFmpeg subprocess -> raw RGB/NV12 stdout -> native/JVM frame pipe -> prebuffer -> Minecraft texture
```

The mod currently selects `HwAccelBackend.NONE` on Android. This is intentional because no usable Android Surface bridge exists yet.

### FFmpeg audio

The mod launches a separate FFmpeg process producing stereo signed 16-bit PCM:

```text
FFmpeg -f s16le -ar 44100 -ac 2 -> AudioSink -> Android AAudio
```

Android audio uses `AudioTrackWrapper`, which loads the APK's plain C API through JNA. Desktop keeps Java Sound as a fallback.

### A/V sync

`AudioMasterClock` and `FramePacing` use the audio line's frame position as the master clock. The video prebuffer consumes frames against that clock, drops late frames, and records A/V health warnings.

Preserve the audio clock and prebuffer contract when fixing performance. Do not replace it with wall-clock pacing without a measured reason.

## FFmpeg plugin APK contents

The working APK contains arm64 files such as:

- `libffmpeg.so` - standalone FFmpeg executable renamed with `.so`
- `libaaudio_sink.so` - custom Android AAudio wrapper
- `libytdlp.so` - yt-dlp executable renamed with `.so`
- `libjnidispatch.so` - JNA native dispatch library

`libffmpeg.so` is not a normal FFmpeg shared library API. It is executed as a process. The APK plugin exports the AAudio C functions separately.

The APK build was changed to use:

```text
--enable-jni
--enable-mediacodec
```

in `build-ffmpeg-android.sh`.

## AAudio native ABI

The APK's AAudio source is generated in `.github/workflows/build-plugin.yml`. Its exported functions are plain C symbols:

```c
void* aaudio_open(int32_t sampleRate, int32_t channelCount);
void aaudio_close(void* handle);
int32_t aaudio_write(void* handle, const void* data, int32_t frames, int64_t timeoutNs);
int32_t aaudio_get_timestamp(void* handle, int64_t* outPos, int64_t* outNs);
int64_t aaudio_frames_written(void* handle);
int32_t aaudio_pause(void* handle);
int32_t aaudio_resume(void* handle);
int32_t aaudio_flush(void* handle);
int32_t aaudio_buffer_size(void* handle);
```

The mod must use JNA to load those exact C symbols. Do not declare Java `external` methods unless the APK implements matching JNI exports.

The AAudio open call must pass channel count `2`, not the byte buffer size.

## Important known-good commits

### `1ca176d2e678f827d70b46cc6bacbc6b5f951649`

This was the smoothest observed playback state:

- Audio and video were smooth and synchronized.
- It could rarely crash during video changes.
- It used `aaudio_frames_written()` in the hot clock path.
- It did not have the later timestamp-query overhead.

Use this commit as the performance reference, not as a complete rollback target.

### `9ad381d6`

Added AAudio teardown protection and disabled Android warm audio processes:

- idempotent close;
- serialized native calls;
- fewer extra FFmpeg processes on Android.

The serialized implementation used one ordinary mutex and caused a serious regression because video clock reads waited behind blocking AAudio writes. It was later replaced with a read/write lock.

### `33ce483f`

Restored the low-overhead `aaudio_frames_written()` clock after an attempted timestamp-based clock caused severe video lag.

### `cca597df`

Fixed `AudioSink` property calls from `available()` to `available` after introducing `PcmLine.available` as a property.

### `1ca176d2` branch ancestry

This commit changed the wrapper to JNA and corrected the C ABI mismatch. It is not itself the final crash-safe version.

## Current uncommitted fix

The current worktree has an uncommitted change in:

`media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AudioTrackWrapper.kt`

The change replaces the ordinary mutex around AAudio calls with `ReentrantReadWriteLock`:

- AAudio writes use the read lock.
- Clock reads use the read lock.
- `release()` uses the write lock.
- Multiple reads/writes can proceed concurrently.
- Release waits until an active native call completes.
- AAudio cannot be closed while a write is in progress.

This is intended to preserve the crash fix without blocking the video clock behind `aaudio_write()`.

Before committing, verify the exact diff and push to `Plugin-APK`.

## Latest reported symptom

The user reported stop-motion video and these A/V warnings:

```text
A / V: 295 of 316 frames dropped and 21 shown behind the audio clock
worst lateness 29700 ms

A / V: 367 of 378 frames dropped and 11 shown behind the audio clock
worst lateness 27783 ms
```

This indicates a clock or pacing runaway, not merely normal software decode load. Tens of seconds of lateness means the prebuffer is dropping almost every frame.

## Diagnosis history

1. The original Android audio path used Java Sound and failed with:

   ```text
   Couldn't load library jsound
   PCM line not supported
   ```

2. The mod was changed to use `AudioTrackWrapper` and AAudio.

3. The first wrapper used JNI declarations that did not match the APK's plain C symbols. JNA was added to fix this.

4. The mod initially selected VAAPI because Zalith reports `os.name=Linux`. Android detection was corrected using `os.version` and `POJAV_*` variables.

5. MediaCodec is compiled into the APK but is not active in the mod. The subprocess FFmpeg path has no Android `Surface` to pass to MediaCodec.

6. An AAudio timestamp query was tried for the video clock. It allocated JNA arrays on every clock read and caused severe video lag. It was reverted to `aaudio_frames_written()`.

7. A normal mutex was added to prevent AAudio close/write races. This fixed crashes in testing but serialized blocking writes with clock reads and caused stop-motion. The current read/write lock is intended to resolve that tradeoff.

## Surface and MediaCodec status

MediaCodec support is compiled into the APK, but the current system cannot use it through the existing mod architecture.

Required pieces still missing:

- Android `SurfaceTexture` or `ANativeWindow` owner;
- `android.view.Surface` or native equivalent;
- `av_mediacodec_default_init()` integration;
- MediaCodec output surface lifecycle;
- external OES or AHardwareBuffer frame handoff into Minecraft's current GL context;
- Zalith/plugin IPC or JNI boundary if the Surface is created outside the guest JVM.

The FFmpeg APK currently has `android:hasCode="false"` and packages native files only. It is not an active Android media service. Do not enable `MEDIACODEC` in `HwAccelBackend` until a real Surface provider and frame handoff exist.

## Build workflows

### Mod

Repository: `HennriCortez/dreamdisplays`

Branch: `Plugin-APK`

Workflow: `Manual Build`

The workflow is visible because `.github/workflows/manual-build.yml` exists on `main`. It invokes `.github/workflows/_build.yml` from the selected branch.

The manual workflow is configured for:

- Minecraft version `26.2`;
- Fabric only;
- Linux native target for the current reduced build.

Expected useful artifact:

```text
build-jars-26.2
```

Install the Fabric 26.2 jar from that artifact. The `native-linux-x64` artifact is not Android-native and is not needed by Zalith.

### FFmpeg APK

Repository: `HennriCortez/FFmpegPlugin`

Branch: `oldffmpeg`

Workflow: `Build FFmpeg Plugin APK` (`build-plugin.yml`)

The older `Android CI` workflow uses a separate ffmpeg-kit path and is not the preferred workflow.

## Validation checklist for next session

1. Build the mod jar from the latest `Plugin-APK` commit.
2. Install only the new Fabric 26.2 jar in Zalith.
3. Keep the working FFmpeg APK installed.
4. Start one video and inspect these lines:

   ```text
   Loaded AAudio C API from FFmpeg plugin
   aaudio_open ok
   first PCM chunk received
   ```

5. Confirm the software mode line appears once, not once per process:

   ```text
   Initializing FFmpeg with software decoding (NONE)
   ```

6. Change video/quality repeatedly and watch for:

   - `A / V` warnings;
   - `Producer blocked` warnings;
   - `Audio clock stuck` warnings;
   - `Audio device was lost`;
   - `ErrorClosed`;
   - `aaudio` errors;
   - Java crashes or native aborts.

7. For a useful comparison, test the exact same stream with the `1ca176d2` jar and the current jar.

## Performance principles

- Keep AAudio writes blocking for back-pressure.
- Do not hold an exclusive lock that blocks clock reads behind writes.
- Do not allocate arrays or byte buffers in the per-frame clock hot path.
- Keep frame-buffer pooling enabled.
- Disable Android audio warm-pool shadows unless measurements show they help.
- Do not change A/V pacing thresholds without comparing the A/V health metrics.
- Treat a 20+ second audio/video difference as a clock contract failure.
- Keep software video decoding on Android until a Surface bridge is actually integrated.
- Prefer a bounded fallback over repeatedly retrying a known-unavailable hardware backend.

## Open technical questions

- Does `aaudio_frames_written()` report submitted frames or rendered frames on the target device/API? The smooth reference suggests it worked best for this pipeline, but confirm with logs or a native timestamp experiment outside the hot path.
- Does `aaudio_write()` ever block for the full 100 ms timeout on the target device? If yes, a read/write lock is needed; if no, a simpler guard may be sufficient.
- Does the AAudio stream remain valid after Android audio-device loss? The wrapper currently treats native close as the terminal lifecycle operation.
- Are crashes caused by AAudio teardown, FFmpeg process teardown, native frame-pool cleanup, or an Android audio-device reset? Obtain a native crash stack trace if the crash returns.

## Files to inspect first

- `media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AudioTrackWrapper.kt`
- `media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AudioSink.kt`
- `media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AudioMasterClock.kt`
- `media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/FramePrebuffer.kt`
- `media/player/src/main/kotlin/com/dreamdisplays/media/player/managers/PlaybackSessionManager.kt`
- `media/player/src/main/kotlin/com/dreamdisplays/media/player/process/HwAccelBackend.kt`
- `media/player/src/main/kotlin/com/dreamdisplays/media/player/process/MediaProcess.kt`
- `.github/workflows/manual-build.yml`
- `.github/workflows/_build.yml`
