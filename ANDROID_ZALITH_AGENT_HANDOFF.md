# Android Zalith Fabric 26.2 Agent Handoff

## Ready-to-Paste Prompt

You are continuing work in the Dream Displays repository.

The target is Minecraft Java Fabric 26.2 running through Zalith Launcher on Android, primarily ARM64 devices. The output must be a Fabric mod jar for the Java Minecraft client running inside Zalith. This is not an Android APK project.

Continue the implementation end to end. Do not stop at a proposal. Inspect the current working tree first because files may have changed since this document was written. Preserve unrelated user changes and do not reset or revert the repository.

The final result must support:

- Fabric Minecraft 26.2.
- Java 25.
- Zalith Launcher Android sandbox.
- Android ARM64 (`arm64-v8a`) native libraries.
- An Android FFmpeg executable built with `ffmpeg-android-maker`.
- FFmpeg MediaCodec hardware decoding as a hard requirement.
- No software-decoding fallback on Android.
- Android audio output.
- Audio/video synchronization using the existing audio master clock.
- A distributable Fabric jar containing the required Android resources.

## Current Repository State

The repository root is the current workspace.

Important modules:

- `media/player`
- `media/runtime`
- `util`
- `native`
- `platform/client/fabric`
- `platform/resources`
- `gradle`

The active Minecraft target must be checked in both:

- `versions/active.txt`
- `versions.json` top-level `active` field

Both should be `26.2`. If they disagree, fix the authoritative configuration before building.

The 26.2 configuration already exists in `versions.json` and uses:

- Minecraft: `26.2`
- Java: `25`
- Fabric Loader: `0.19.3`
- Fabric API: `0.157.0+26.2`
- Loom: `1.17.13`

Use Java 25 explicitly on Windows:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.2'
```

## Existing Changes

Previous work changed these areas:

- `util/src/main/kotlin/com/dreamdisplays/util/OsInfo.kt`
  - Detects Android from runtime properties and `android.os.Build`.
- `media/player/src/main/kotlin/com/dreamdisplays/media/player/process/HwAccelBackend.kt`
  - Adds `MEDIACODEC`.
  - Selects MediaCodec by default on Android.
- `media/player/src/main/kotlin/com/dreamdisplays/media/player/process/FFmpegBinary.kt`
  - Android accepts an app-provided executable path.
  - Android probes `ffmpeg -hwaccels` and rejects the executable unless `mediacodec` is reported.
- `media/player/src/main/kotlin/com/dreamdisplays/media/player/MediaPlayer.kt`
  - Android does not honor the software fallback path.
  - Android keeps hardware decoding enabled.
- `media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/PcmLine.kt`
  - Adds a PCM output abstraction.
  - Desktop wraps Java Sound.
  - Android uses reflective `android.media.AudioTrack`.
  - AudioTrack playback position is used for queue and clock accounting.
- `media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AudioSink.kt`
  - Routes audio through the PCM abstraction.
- `media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/NativeVideoFramePipe.kt`
  - Maps `mediacodec` to the native hardware backend code.

These changes compile, but the Android native payload has not yet been added to the jar.

## Critical Architecture Constraint

Zalith runs a Java Fabric mod on Android. It does not turn the Fabric mod into an Android application. Therefore:

- Android SDK classes must not be direct compile-time dependencies of the JVM modules.
- Reflection is acceptable for Java Android APIs such as `AudioTrack`.
- Native Android libraries must be built for Android NDK ABI compatibility.
- GNU/Linux ARM64 libraries are not interchangeable with Android ARM64 libraries.
- A normal desktop FFmpeg build is not acceptable on Android.
- The final jar must extract Android-native resources to writable storage before loading or executing them.

## Primary Task: Android Native Packaging

Implement the missing Android native packaging completely.

### 1. FFmpeg Android Build

Use `Javernaut/ffmpeg-android-maker` as the FFmpeg source/build system.

The repository produces Android executables and shared libraries for:

- `arm64-v8a`
- `armeabi-v7a`
- `x86`
- `x86_64`

The immediate target is `arm64-v8a`.

Do not assume a prebuilt GitHub release exists. The project is primarily a build script. Add a reproducible build script or CI job that:

1. Checks out a pinned commit or release.
2. Uses the Android SDK and NDK.
3. Builds only `arm64-v8a` unless the repository's matrix requires more ABIs.
4. Enables the FFmpeg JNI/MediaCodec support required by the Android build.
5. Produces the `ffmpeg` executable.
6. Runs the executable with `-hwaccels`.
7. Fails if the output does not contain an exact `mediacodec` backend.
8. Preserves the appropriate FFmpeg license files.

Never download the source repository at runtime and attempt to compile it on the user's device. Build it in CI or as a developer build step, then package the resulting artifact.

Prefer a pinned commit rather than a moving `master` branch.

### 2. Android FFmpeg Runtime Extraction

Update `FFmpegBinary` so Android can resolve a packaged resource or a configured external path.

Recommended resolution order:

1. Explicit system property for development/testing.
2. Packaged Android ARM64 resource.
3. A clear fatal failure.

The Android executable should be copied to a writable directory such as the Dream Displays cache directory before execution. Ensure:

- Parent directories are created.
- The file is marked executable.
- Existing partial downloads/files are rejected.
- Concurrent extraction is safe.
- The extracted executable is probed before use.
- `mediacodec` is mandatory.
- No Linux desktop tarball is selected when running under Android/Zalith.

Use the existing `DreamHttpClient` and cache patterns where appropriate. Do not weaken the hardware validation.

### 3. Android Rust Native Libraries

The current native build supports desktop targets only. Inspect:

- `native/Cargo.toml`
- `native/build.gradle.kts`
- `native/src/lib.rs`
- `native/src/session.rs`
- `native/lav/Cargo.toml`
- `native/lav/src/lib.rs`
- `native/lav/src/session.rs`
- `gradle/src/main/kotlin/support/natives/DreamDisplaysNativeResources.kt`
- `gradle/src/main/kotlin/conventions/dreamdisplays.native-resources.gradle.kts`
- `.github/workflows/_build.yml`

Add Android ARM64 support using the Android NDK target:

```text
aarch64-linux-android
```

The build must use the NDK linker and an appropriate Android API level. Do not label GNU/Linux ARM64 libraries as Android libraries.

Add a distinct resource key, for example:

```text
android-arm64
```

or use an explicitly documented Android ABI key such as:

```text
android-arm64-v8a
```

Use one naming convention consistently across:

- Native build output.
- Jar resource directories.
- Runtime lookup.
- Validation.
- CI matrices.

If the current Rust code or Java FFM bridge cannot operate on Android, make that failure explicit and use a supported Android loading path. Do not package a library that compiles but cannot load in Zalith.

### 4. FFmpeg Shared Libraries and LAV

`LavFfmpeg.kt` currently downloads BtbN desktop shared libraries. That is not valid for Android.

For Android, choose one of these explicit approaches:

- Package the Android FFmpeg shared libraries produced by `ffmpeg-android-maker` and update the loader to use them.
- Disable the in-process LAV path on Android and use the validated Android FFmpeg executable path for video decoding.
- Statically link FFmpeg into an Android-specific native library if the native build supports this cleanly.

The simplest correct first implementation is acceptable, but it must not attempt to load BtbN GNU/Linux libraries on Android.

If the LAV path is disabled on Android, document and test that the external FFmpeg path remains hardware-only and functional.

### 5. Audio Output

Oboe is available from Google as a C++ Android library and is technically suitable, but it requires a native JNI/NDK bridge. Do not add Oboe as a fake Kotlin dependency.

The repository already has a reflective `AudioTrack` implementation in `PcmLine.kt`. Evaluate it carefully before replacing it with Oboe.

Use Oboe only if all of the following are implemented:

- Android C++ build integration.
- Oboe dependency integration, preferably pinned to a stable release.
- JNI or another supported bridge from JVM to native code.
- A PCM ring buffer.
- Blocking or callback writes with correct back-pressure.
- Start, stop, pause, resume, flush, and release.
- Track switching.
- Playback frame position exposed to the JVM.
- Correct handling of underruns and device route changes.
- Android ARM64 packaging inside the Fabric jar.

For the first working Zalith implementation, reflective `AudioTrack` is acceptable and simpler. It must be robust enough for:

- 44.1 kHz.
- Stereo.
- Signed 16-bit little-endian PCM.
- Streaming writes.
- Pause/resume without resetting the position unexpectedly.
- Flush on seek/restart.
- Correct available queue estimation.

Do not use `javax.sound.sampled.SourceDataLine` on Android.

### 6. Audio/Video Synchronization

Do not replace or bypass the existing synchronization model.

The synchronization authority is:

- `AudioSink.sampleClock()`
- `AudioMasterClock`
- `PlaybackClock`
- The video frame pacing path

Audio playback position must be derived from actual played frames, not bytes merely written to a buffer.

Preserve:

- Session epochs.
- Content start timestamps.
- Catch-up skipping.
- Resync requests.
- Stall takeover behavior.
- Audio track switching.
- Cached audio bridge behavior.
- Video pacing against the audio master clock.

Add focused tests for:

- AudioTrack/backend clock monotonicity.
- Queue accounting.
- Pause/resume behavior.
- Resync behavior.
- Track switch anchoring.
- Android hardware failure behavior.
- MediaCodec absence being fatal on Android.

A decoder failure must not silently call `HwAccelBackend.NONE` on Android.

## Dependency Rules

Do not add Android SDK classes as regular JVM compile dependencies unless the build is deliberately separated and the Fabric jar remains valid.

Do not add an Android Gradle application module merely to make the dependency graph look complete. This output is a Fabric mod jar.

Do not use:

- BtbN GNU/Linux binaries on Android.
- FFmpegKit AAR as if it were an executable.
- An unpinned GitHub source archive at runtime.
- A software decoder fallback on Android.
- Oboe without its native bridge.
- Java Sound on Android.

## Required Validation Commands

Run focused validation after each substantive edit.

### Check active target

```powershell
Get-Content versions/active.txt
Select-String -Path versions.json -Pattern '"active"|"26.2"'
```

Expected active target: `26.2`.

### Compile media player

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.2'
./gradlew.bat :media:player:compileKotlin --no-configuration-cache --no-daemon
```

### Resolve Fabric 26.2 dependencies

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.2'
./gradlew.bat :platform:client:fabric:dependencies --configuration compileClasspath --no-configuration-cache --no-daemon
```

Confirm the report contains:

```text
net.minecraft ... :26.2
net.fabricmc.fabric-api:fabric-api:0.157.0+26.2
```

### Build final Fabric jar

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.2'
./gradlew.bat :platform:client:fabric:publishJar --no-configuration-cache --no-daemon
```

### Inspect jar

```powershell
$jar = Get-ChildItem 'build\libs\dreamdisplays-fabric-26.2-*.jar' | Select-Object -First 1 -ExpandProperty FullName
& "$env:JAVA_HOME\bin\jar.exe" tf $jar | Select-String 'fabric.mod.json|dreamdisplays-natives/|ffmpeg|licenses|oboe'
```

The final artifact must contain the Android ARM64 resources required by the chosen runtime strategy. It must not contain only Windows natives.

### FFmpeg hardware validation

Run against the exact packaged/extracted Android binary on an Android ARM64 environment:

```bash
./ffmpeg -hide_banner -hwaccels
./ffmpeg -hide_banner -decoders
```

The build or packaging validation must fail unless MediaCodec is available.

## Expected Final Report

At the end, report:

1. Files changed.
2. Exact Android ABI and resource key used.
3. Exact FFmpeg source commit/release used.
4. How MediaCodec is verified.
5. Whether Oboe or AudioTrack is used, and why.
6. How audio playback position reaches `AudioMasterClock`.
7. Exact Gradle command run.
8. Exact jar path and size.
9. Jar contents relevant to Android.
10. Any limitation that prevents claiming full Zalith readiness.

Do not claim Zalith support unless the final jar contains or can reliably obtain:

- Android ARM64 native libraries.
- Android FFmpeg executable.
- MediaCodec support.
- Android audio output.
- Synchronized audio/video playback.

## First Action

Before editing:

1. Read `git status --short`.
2. Read `versions/active.txt` and the top-level `versions.json` active value.
3. Inspect the current contents of `PcmLine.kt`, `AudioSink.kt`, `FFmpegBinary.kt`, `NativeMedia.kt`, and the native Gradle/CI files.
4. State one concrete hypothesis about the current packaging failure and one focused check.
5. Make the smallest reversible edit that advances Android native packaging.
6. Run the focused validation immediately after that edit.
