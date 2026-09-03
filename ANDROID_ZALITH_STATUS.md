# Android Zalith Fabric 26.2 Status and Handoff

## Executive Summary

Dream Displays is being adapted for Minecraft Java Fabric 26.2 running inside Zalith Launcher on Android ARM64.

The target device tested so far is:

- Device: Samsung SM-X810
- Architecture: ARM64
- Android API: 34
- Launcher: Zalith Launcher 2 Plus 2.4.9_hotfix1
- Java runtime: Internal-25
- Minecraft: 26.2
- Fabric Loader: 0.19.3
- Fabric API: 0.157.0+26.2

The Fabric mod jar builds successfully with Java 25 and contains an Android ARM64 FFmpeg executable plus FFmpeg shared libraries.

However, playback is still not working. The current failure is:

```text
Android FFmpeg MediaCodec probe failed (exit=0). Output:
Hardware acceleration methods:

Android FFmpeg does not provide the required MediaCodec hardware backend.
FFmpeg binary not available.
```

This means the packaged Android FFmpeg executable launches successfully, but its `-hwaccels` output is empty. The current blocker is therefore the FFmpeg build configuration or the selected FFmpeg artifact, not the Fabric jar path.

## Target Architecture

The intended runtime is:

```text
Zalith Launcher
    |
    | Java 25 Fabric client
    v
Dream Displays Fabric mod jar
    |
    +-- Android ARM64 FFmpeg executable
    +-- Android FFmpeg shared libraries
    +-- Android audio output
    v
FFmpeg process with MediaCodec
    |
    | video frames and PCM audio
    v
AudioTrack / Android audio backend
    |
    | actual played frame position
    v
AudioMasterClock
    |
    v
Video frame pacing
```

The mod is a Fabric JVM jar, not an Android APK. Android-specific native artifacts are built separately and packaged into the Fabric jar as resources.

## Important Repository Links

### Dream Displays

- Main Dream Displays repository: use the current local repository origin.
- Local Dream Displays workspace:
  `C:\Users\Hennri Cortez\dreamdisplays\dreamdisplays`

### Android FFmpeg Builder

- User fork: [HennriCortez/ffmpeg-android-maker](https://github.com/HennriCortez/ffmpeg-android-maker)
- User fork Actions: [HennriCortez/ffmpeg-android-maker/actions](https://github.com/HennriCortez/ffmpeg-android-maker/actions)
- Upstream builder: [Javernaut/ffmpeg-android-maker](https://github.com/Javernaut/ffmpeg-android-maker)
- Latest user fork commit observed during this work: `922c553` for `scripts/ffmpeg/build.sh`
- Earlier successful Android payload workflow commit: `6c7bc77`

### Android Audio

- Oboe: [google/oboe](https://github.com/google/oboe)
- Oboe documentation: [Getting Started](https://github.com/google/oboe/blob/main/docs/GettingStarted.md)

### FFmpeg

- FFmpeg source project: [FFmpeg/FFmpeg](https://github.com/FFmpeg/FFmpeg)
- FFmpeg MediaCodec documentation: [FFmpeg codecs documentation](https://ffmpeg.org/ffmpeg-codecs.html)

## Local Dream Displays Directories

Repository root:

```text
C:\Users\Hennri Cortez\dreamdisplays\dreamdisplays
```

Relevant project modules:

```text
api/
core/
media/
media/audio/
media/player/
media/runtime/
media/source/
native/
platform/
platform/client/common/
platform/client/fabric/
platform/client/neoforge/
platform/resources/
platform/server/
util/
versions/
gradle/
```

Relevant media player source directory:

```text
media/player/src/main/kotlin/com/dreamdisplays/media/player
```

Pipeline directory:

```text
media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline
```

Pipeline files:

```text
AudioMasterClock.kt
AudioSink.kt
FramePipe.kt
FramePrebuffer.kt
FrameSurface.kt
NativeVideoFramePipe.kt
PlaybackClock.kt
VideoFramePipe.kt
PcmLine.kt
```

Process directory:

```text
media/player/src/main/kotlin/com/dreamdisplays/media/player/process
```

Process files:

```text
FFmpegBinary.kt
FFmpegCapabilities.kt
HlsAudioFeeder.kt
HlsSeekPlaylist.kt
HwAccelBackend.kt
MediaProcess.kt
```

Native bridge directory:

```text
media/player/src/main/kotlin/com/dreamdisplays/media/player/nativebridge
```

Native bridge files of interest:

```text
LavFfmpeg.kt
NativeMedia.kt
```

Rust native directories:

```text
native/
native/src/
native/lav/
native/lav/src/
native/logging/
```

Native build files:

```text
native/Cargo.toml
native/build.gradle.kts
native/src/lib.rs
native/src/session.rs
native/lav/Cargo.toml
native/lav/src/lib.rs
native/lav/src/session.rs
```

Native resource convention files:

```text
gradle/src/main/kotlin/support/natives/DreamDisplaysNativeResources.kt
gradle/src/main/kotlin/conventions/dreamdisplays.native-resources.gradle.kts
```

Fabric module:

```text
platform/client/fabric/
platform/client/fabric/build.gradle.kts
```

Fabric build output:

```text
build/libs/dreamdisplays-fabric-26.2-1.10.0-dev.jar
```

Local Android FFmpeg staging directory:

```text
native/build/android-ffmpeg/arm64-v8a/
```

Expected local Android FFmpeg staging layout:

```text
native/build/android-ffmpeg/arm64-v8a/
    ffmpeg
    lib/
        libavcodec.so
        libavdevice.so
        libavfilter.so
        libavformat.so
        libavutil.so
        libswresample.so
        libswscale.so
```

The `include/` directory is only needed for compiling against FFmpeg. It is not needed at runtime by the external FFmpeg executable.

## Version Configuration

The active Minecraft target is controlled by both:

```text
versions/active.txt
versions.json
```

Both must contain `26.2` as the active target.

The relevant `versions.json` entry is:

```json
"26.2": {
  "minecraft.version": "26.2",
  "java.version": "25",
  "loom.version": "1.17.13",
  "fabric.loader.version": "0.19.3",
  "fabric.api.version": "0.157.0+26.2",
  "fabric.minecraft.version": "26.2",
  "fabric.minecraft.dependency": "26.2"
}
```

The repository previously resolved the wrong 1.21.11 dependencies when `versions.json` and `versions/active.txt` disagreed. That was fixed by aligning both active selectors to 26.2.

## Changes Made in Dream Displays

### Android detection

File:

```text
util/src/main/kotlin/com/dreamdisplays/util/OsInfo.kt
```

Android detection checks:

- `java.runtime.name`
- `java.vm.name`
- `os.version`
- Presence of `android.os.Build`

This is necessary because Zalith reports:

```text
-Dos.name=Linux
-Dos.version=Android-14
```

Checking only `os.name` incorrectly classified Zalith as GNU/Linux.

### MediaCodec backend

File:

```text
media/player/src/main/kotlin/com/dreamdisplays/media/player/process/HwAccelBackend.kt
```

Added:

```text
MEDIACODEC("mediacodec", null, 6)
```

Android selects `MEDIACODEC` as its default backend.

Android does not select VAAPI, because VAAPI is a Linux desktop acceleration API and is not the correct Android backend.

### Hardware-only policy

File:

```text
media/player/src/main/kotlin/com/dreamdisplays/media/player/MediaPlayer.kt
```

Android always keeps hardware decoding enabled. The normal software fallback path is disabled on Android.

The intended policy is:

```text
MediaCodec available: continue playback
MediaCodec missing: fail playback
```

The mod must not silently switch Android playback to `HwAccelBackend.NONE`.

### FFmpeg binary resolution

File:

```text
media/player/src/main/kotlin/com/dreamdisplays/media/player/process/FFmpegBinary.kt
```

Android resolution supports:

1. Explicit system property:
   ```text
   dreamdisplays.ffmpeg.android.path
   ```
2. Packaged jar resource:
   ```text
   /dreamdisplays-ffmpeg/android-arm64/ffmpeg
   ```
3. Fatal unavailability.

Android must never download a BtbN GNU/Linux FFmpeg build.

The packaged executable is extracted to:

```text
./dreamdisplays/ffmpeg/android-arm64/ffmpeg
```

The Android shared libraries are extracted beside it into:

```text
./dreamdisplays/ffmpeg/android-arm64/lib/
```

The FFmpeg process environment receives:

```text
LD_LIBRARY_PATH=<extracted Android FFmpeg lib directory>
```

### MediaCodec probing

The current probe runs:

```text
ffmpeg -hide_banner -hwaccels
```

It runs with the extracted FFmpeg library directory in `LD_LIBRARY_PATH`.

The probe requires an exact line:

```text
mediacodec
```

If absent, `FFmpegBinary.getPath()` returns null and playback reports:

```text
FFmpeg binary not available.
```

### FFmpeg process environment

The Android library path is applied to external FFmpeg processes from:

```text
FFmpegBinary.configureProcess(ProcessBuilder)
```

This is used by:

- `MediaProcess`
- `FFmpegCapabilities`
- MediaCodec probing

### Android LAV behavior

File:

```text
media/player/src/main/kotlin/com/dreamdisplays/media/player/nativebridge/NativeMedia.kt
```

The in-process LAV path is disabled on Android because the current LAV implementation downloads and loads desktop FFmpeg libraries from BtbN.

This prevents Android from attempting to load incompatible GNU/Linux FFmpeg libraries.

### Android audio output

Files:

```text
media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/PcmLine.kt
media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AudioSink.kt
```

Desktop uses Java Sound.

Android uses reflective `android.media.AudioTrack`, avoiding Android SDK compile-time dependencies in the Fabric JVM module.

The format is:

```text
44,100 Hz
stereo
signed 16-bit little-endian PCM
```

AudioTrack playback position is derived from the actual playback head, not merely from bytes written.

### Synchronization

The existing synchronization architecture is preserved:

```text
AudioTrack playback head
    |
    v
AudioSink.sampleClock()
    |
    v
AudioMasterClock
    |
    v
Video frame pacing
```

The existing system also preserves:

- Audio session epochs
- Content start timestamps
- Catch-up skipping
- Audio resync requests
- Stall takeover
- Track switching
- Cached audio bridge behavior
- Video pacing against the audio clock

## Android FFmpeg Fork Changes

Fork:

```text
https://github.com/HennriCortez/ffmpeg-android-maker
```

Important fork files:

```text
ffmpeg-android-maker.sh
scripts/check-host-machine.sh
scripts/common-functions.sh
scripts/parse-arguments.sh
scripts/export-host-variables.sh
scripts/export-build-variables.sh
scripts/ffmpeg/download.sh
scripts/ffmpeg/build.sh
.github/workflows/compilability_check.yml
```

The fork builds FFmpeg 8.1.2 for Android and supports:

- `arm64-v8a`
- `armeabi-v7a`
- `x86`
- `x86_64`

The target build is:

```bash
./ffmpeg-android-maker.sh \
  --target-abis=arm64-v8a \
  --android-api-level=21
```

This must be run from the root of the FFmpeg builder fork under Bash, Linux, WSL, Docker, or GitHub Actions.

It cannot be run as a normal PowerShell script.

### Important fork fixes already encountered

The top-level builder originally failed under `set -u` because `scripts/common-functions.sh` accessed an optional `$3` argument.

The fix is:

```bash
NEED_EXTRA_DIRECTORY="${3:-false}"
```

The host checker must not reference `ANDROID_ABI`. That variable is only defined later by:

```bash
source "${SCRIPTS_DIR}/export-build-variables.sh" "$abi"
```

Invalid FFmpeg configure options were encountered and must not be used:

```text
--enable-android
--enable-mediandk
```

The valid relevant FFmpeg options are:

```text
--target-os=android
--enable-jni
--enable-mediacodec
```

### Cross-compile execution issue

The generated binary is Android ARM64. GitHub Actions runners are normally Linux x86_64.

This fails and must not be used:

```bash
./build/ffmpeg/arm64-v8a/bin/ffmpeg -hide_banner -hwaccels
```

The error is:

```text
cannot execute binary file: Exec format error
```

GitHub Actions should validate Android FFmpeg statically using:

- ELF architecture
- FFmpeg generated configuration files
- Binary strings
- Build logs

The binary should only be executed on an actual Android ARM64 environment.

## What Worked

### Fabric dependency resolution

This succeeded:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.2'
./gradlew.bat :platform:client:fabric:dependencies `
  --configuration compileClasspath `
  --no-configuration-cache `
  --no-daemon
```

Expected coordinates were confirmed:

```text
net.minecraft:minecraft-merged-...:26.2
net.fabricmc.fabric-api:fabric-api:0.157.0+26.2
net.fabricmc:fabric-loader:0.19.3
```

### Kotlin compilation

This succeeded:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.2'
./gradlew.bat :media:player:compileKotlin `
  --no-configuration-cache `
  --no-daemon
```

### Fabric jar build

This succeeded:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.2'
./gradlew.bat :platform:client:fabric:publishJar `
  --no-configuration-cache `
  --no-daemon
```

### Jar contents

The generated jar contained:

```text
dreamdisplays-ffmpeg/android-arm64/ffmpeg
dreamdisplays-ffmpeg/android-arm64/lib/libavcodec.so
dreamdisplays-ffmpeg/android-arm64/lib/libavdevice.so
dreamdisplays-ffmpeg/android-arm64/lib/libavfilter.so
dreamdisplays-ffmpeg/android-arm64/lib/libavformat.so
dreamdisplays-ffmpeg/android-arm64/lib/libavutil.so
dreamdisplays-ffmpeg/android-arm64/lib/libswresample.so
dreamdisplays-ffmpeg/android-arm64/lib/libswscale.so
```

Observed artifact path:

```text
build/libs/dreamdisplays-fabric-26.2-1.10.0-dev.jar
```

Observed size was approximately 34 MB after including the Android FFmpeg payload.

## What Did Not Work

### Original Android detection

Zalith reported `os.name=Linux`, causing the mod to select:

```text
linux-aarch64
VAAPI
Java Sound
```

This produced errors such as:

```text
No device available for decoder: device type vaapi needed for codec h264.
no jsound in system library path
```

This was fixed by checking `os.version=Android-14`.

### Running Android FFmpeg on GitHub Actions

This failed with:

```text
Exec format error
```

because the binary is Android AArch64 and the runner is Linux x86_64.

### `--enable-android`

This failed because FFmpeg 8.1.2 does not recognize it:

```text
Unknown option "--enable-android"
```

It was removed.

### `--enable-mediandk`

This failed because FFmpeg 8.1.2 does not recognize it:

```text
Unknown option "--enable-mediandk"
```

It was removed.

### `-decoders` as the MediaCodec capability check

This was incorrect. FFmpeg's `-decoders` output lists codecs such as `h264`, not necessarily a MediaCodec hardware backend name.

The correct capability command is:

```bash
ffmpeg -hide_banner -hwaccels
```

### `stats/arm64-v8a-hwaccels.txt`

These files were referenced by packaging but were not generated because the Android binary cannot execute on the x86_64 GitHub runner:

```text
stats/arm64-v8a-hwaccels.txt
stats/arm64-v8a-decoders.txt
```

They should not be copied unless the workflow explicitly creates them through static analysis or runs on Android ARM64 emulation.

### Previous packaged FFmpeg binary

The packaged binary successfully launched on Android but reported:

```text
Hardware acceleration methods:
```

with no entries.

Therefore the binary was built without a usable MediaCodec hardware accelerator, despite the build script containing:

```text
--enable-jni
--enable-mediacodec
```

This proves the flags alone are insufficient if FFmpeg's dependency checks disable MediaCodec.

## Current Zalith Logs

### Previous log

Log:

[Fabric 26.2 Client Log](https://mclo.gs/mpC1cBk)

The mod incorrectly used Linux behavior and produced:

```text
Downloading ... linuxarm64 ...
device type vaapi needed for codec h264
no jsound in system library path
```

### Android detection-fixed log

Log:

[Fabric 26.2 Client Log](https://mclo.gs/3LlTBUT)

This confirmed Android detection was fixed, but the packaged FFmpeg validator rejected the binary because it initially checked `-decoders`.

### Current log

Log:

[Fabric 26.2 Client Log](https://mclo.gs/WMmVWnb)

The current log shows:

```text
Android FFmpeg MediaCodec probe failed (exit=0). Output: Decoders:
```

The binary runs, but the decoder output does not contain the expected MediaCodec text. After correcting the validator to use `-hwaccels`, the current remaining problem is that the binary's hardware acceleration output is empty:

```text
Hardware acceleration methods:
```

The current log does not show successful Dream Displays playback.

## Current Failure Diagnosis

The immediate failure is not caused by:

- Fabric version
- Java version
- Jar filename
- Android detection
- Missing FFmpeg shared-library path
- Wrong Linux/Android path selection

The immediate failure is:

```text
The packaged FFmpeg binary reports no hardware acceleration methods.
```

Possible causes in the FFmpeg fork:

1. `--enable-mediacodec` is present in the script but not reaching `configure`.
2. `mediacodec` is later disabled by FFmpeg's dependency resolution.
3. Android JNI headers are not found.
4. Android `mediandk` headers or library checks fail.
5. `pthreads` is not enabled or detected.
6. The FFmpeg binary copied into Dream Displays is from an earlier failed build.
7. The GitHub Actions packaging step copied the wrong output file.
8. The static validation checks strings but does not prove configured capability.

FFmpeg 8.1.2 defines the MediaCodec dependency internally as requiring Android-related components, JNI, Media NDK support, and pthreads. `mediandk` is an internal dependency name, not a valid configure command-line flag.

## Required Next Debugging Steps

Do these in the FFmpeg fork workflow, immediately after the FFmpeg configure step and after compilation.

### 1. Print the exact configure command

The workflow must show that the command includes:

```text
--target-os=android
--enable-jni
--enable-mediacodec
```

### 2. Upload configuration files

After FFmpeg configure completes, locate and upload:

```bash
find . -type f \
  \( -name config.h -o -name config_components.h -o -name config.log -o -name config.mak \) \
  -print
```

Inspect:

```bash
grep -RniE \
  'CONFIG_(MEDIACODEC|JNI|ANDROID|MEDIANDK|PTHREADS)' \
  .
```

Expected relevant results include:

```text
CONFIG_MEDIACODEC 1
CONFIG_JNI 1
CONFIG_PTHREADS 1
```

For the component list, expect an entry equivalent to:

```text
CONFIG_H264_MEDIACODEC_DECODER 1
```

The exact generated macro naming should be taken from the current FFmpeg version's `config_components.h`.

### 3. Inspect configure log when MediaCodec is disabled

Search:

```bash
grep -RniE \
  'mediacodec|mediandk|jni|pthread|android' \
  build sources output
```

If MediaCodec is disabled, the configure log should explain which dependency failed.

### 4. Confirm JNI headers

Run in the workflow:

```bash
find "$ANDROID_NDK_HOME" \
  -path '*sysroot/usr/include/jni.h' \
  -print
```

Also check:

```bash
find "$ANDROID_NDK_HOME" \
  -iname 'media/NdkMediaFormat.h' \
  -print
```

### 5. Confirm exact Android libraries

Inspect the link command and NDK sysroot for:

```text
libmediandk.so
libandroid.so
liblog.so
```

The FFmpeg build must link against Android NDK system libraries correctly.

### 6. Do not use `strings` as the only proof

This is insufficient:

```bash
strings ffmpeg | grep -i mediacodec
```

The decisive proof is:

```text
CONFIG_MEDIACODEC=1
```

and a runtime Android check:

```bash
ffmpeg -hide_banner -hwaccels
```

## GitHub Actions Strategy

Use the FFmpeg fork workflow manually with:

```yaml
on:
  workflow_dispatch:
```

Do not add `push` if manual execution is desired.

The workflow should:

1. Install Android SDK.
2. Install Android NDK `29.0.14206865`.
3. Install Linux build tools.
4. Build only `arm64-v8a`.
5. Use Android API 21 or a higher supported API.
6. Build FFmpeg 8.1.2.
7. Print the exact configure output.
8. Upload `config.h`, `config_components.h`, and `config.log`.
9. Validate MediaCodec statically.
10. Upload `ffmpeg`, `lib/*.so`, and license metadata.

The Android executable cannot be executed directly on the Ubuntu x86_64 runner.

## Dream Displays Build Steps

After downloading the successful FFmpeg artifact:

1. Copy the executable:

```text
arm64-v8a/bin/ffmpeg
```

to:

```text
native/build/android-ffmpeg/arm64-v8a/ffmpeg
```

2. Copy the contents of:

```text
arm64-v8a/lib/
```

to:

```text
native/build/android-ffmpeg/arm64-v8a/lib/
```

3. Do not copy `include/` for runtime-only packaging.

4. Build the Fabric jar:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.2'
./gradlew.bat :platform:client:fabric:publishJar `
  --no-configuration-cache `
  --no-daemon
```

5. Inspect the jar:

```powershell
$jar = Get-ChildItem 'build\libs\dreamdisplays-fabric-26.2-*.jar' |
  Select-Object -First 1 -ExpandProperty FullName

& "$env:JAVA_HOME\bin\jar.exe" tf $jar |
  Select-String 'dreamdisplays-ffmpeg/android-arm64/(ffmpeg|lib/)'
```

Expected entries:

```text
dreamdisplays-ffmpeg/android-arm64/ffmpeg
dreamdisplays-ffmpeg/android-arm64/lib/libavcodec.so
dreamdisplays-ffmpeg/android-arm64/lib/libavformat.so
dreamdisplays-ffmpeg/android-arm64/lib/libavutil.so
```

## Zalith Test Expectations

Replace the old jar completely in the Fabric 26.2 instance.

Remove stale extracted mod/cache files if Zalith does not refresh the jar cleanly.

On startup, expected Dream Displays log:

```text
Using Android FFmpeg with MediaCodec: .../dreamdisplays/ffmpeg/android-arm64/ffmpeg
```

Expected behavior:

- No BtbN Linux FFmpeg download
- No VAAPI selection
- No `jsound` error from Dream Displays audio
- No software fallback
- Audio output through Android AudioTrack
- Video paced from the audio playback clock

If the log still says:

```text
Android FFmpeg MediaCodec probe failed
```

capture the complete probe output. If it says:

```text
Hardware acceleration methods:
```

with no `mediacodec`, rebuild the FFmpeg fork and do not reuse the artifact.

## Separate Unrelated Warnings

The Zalith logs also contain warnings from other mods or launcher components. These are not the current Dream Displays blocker:

### Simple Voice Chat

Errors such as:

```text
Could not load libopus4j natives for linux-aarch64
Could not load librnnoise4j
Could not load libspeex4j natives
Could not load liblame4j natives
library "libm.so.6" not found
```

belong to Simple Voice Chat's desktop Linux native artifacts. They are separate from Dream Displays' FFmpeg playback path.

### Authentication

HTTP 401 errors involving:

```text
/player/attributes
/player/certificates
Realms
```

are account/authentication issues from the local launcher account and are not the reason Dream Displays reports FFmpeg unavailable.

### C2ME

A warning about missing:

```text
linux-aarch_64-libc2me-opts-natives-math.so
```

belongs to C2ME and is unrelated to Dream Displays FFmpeg.

### Cacio/Cacio Java 2D

The following may be expected in the Zalith compatibility environment:

```text
ClassNotFoundException: sun/java2d/SurfaceManagerFactory
```

It is not the direct media failure in the current logs.

## Oboe Status

Oboe was researched as a possible Android audio backend.

Oboe repository:

```text
https://github.com/google/oboe
```

Oboe is a C++ Android NDK library using AAudio or OpenSL ES. It requires:

- CMake or ndk-build integration
- Oboe dependency
- C++ native code
- JNI bridge
- PCM ring buffer
- Lifecycle handling
- Playback position bridge
- Android ARM64 `.so` packaging

The first implementation uses reflective `AudioTrack` instead because this project is a Fabric JVM jar, not an Android application. AudioTrack is sufficient for the current blocking PCM pipeline and can expose actual playback position through `getPlaybackHeadPosition()`.

Oboe should only be added later if AudioTrack latency or underruns are proven unacceptable.

## Current Completion Status

### Complete

- Fabric 26.2 configuration
- Java 25 build
- Android runtime detection for Zalith
- MediaCodec backend selection
- Android software fallback disabled
- Android AudioTrack adapter
- Audio playback position integration
- FFmpeg Android resource extraction
- FFmpeg shared-library extraction
- `LD_LIBRARY_PATH` process setup
- Android LAV disabled
- Fabric jar packaging hook
- Fabric jar build

### Not complete

- FFmpeg binary with confirmed `mediacodec` capability
- Final runtime MediaCodec success on the Samsung device
- Android Rust native libraries
- Oboe backend
- Full Zalith acceptance test

## Recommended Immediate Action

Do not modify the Dream Displays jar again yet. First fix and prove the FFmpeg fork build.

In the FFmpeg fork workflow:

1. Remove invalid flags:
   ```text
   --enable-android
   --enable-mediandk
   ```
2. Keep:
   ```text
   --target-os=android
   --enable-jni
   --enable-mediacodec
   ```
3. Upload generated configuration files.
4. Confirm `CONFIG_MEDIACODEC=1`.
5. Package a new ARM64 FFmpeg artifact.
6. Replace both `ffmpeg` and `lib/*.so` in Dream Displays.
7. Rebuild the Fabric jar.
8. Retest in Zalith.

The current issue cannot be solved by renaming `libffmpeg.so`, changing the jar filename, or using the `POJAV_FFMPEG_PATH` value. That Pojav/Zalith library is a separate shared library and is not the standalone FFmpeg executable used by Dream Displays' `ProcessBuilder` pipeline.
