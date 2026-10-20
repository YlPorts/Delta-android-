# Delta Android – native port in progress

This directory begins an **actual Android port inside the Delta source repository**.
The original Delta iOS code and its submodules remain intact. This stage does
NOT claim to have ported DeltaCore, UIKit, controller skins, Delta Sync, or all
console systems.

## 0.1.0-dev scope

- Android Kotlin Activity + full-screen pixel-perfect GB/GBC video renderer.
- ARM64 C++ JNI libretro host, loading the **real Gambatte GB/GBC engine**.
- Real GB/GBC input (multitouch and physical gamepads), stereo PCM audio.
- GB/GBC battery-backed SRAM import/export to per-ROM .srm files.
- Android Storage Access Framework game import, no storage permission.
- GitHub Actions workflow to build Gambatte from upstream source and make a
  *debug-signed installable development APK*.
- No ROMs or firmware included.

Unsupported at present: NES, SNES, GBA, N64, Nintendo DS, Genesis; save
states, cheat databases, artwork, skins and cloud sync. The Android UI is an
initial host interface, **not yet the Delta iPhone UI port**.

## Build without a PC

Open GitHub > Actions > "Delta Android ARM64 (development)" >
Run workflow (branch `android/native-bootstrap`). If the job succeeds,
download the `DeltaAndroid-0.1.0-dev-arm64` artifact and extract the
APK inside. Install it on ARM64 Android. Do not assume the job succeeded
until you inspect its logs.

The workflow downloads source for
[libretro Gambatte](https://github.com/libretro/gambatte-libretro)
and compiles it with the Android NDK; the native bridge itself is compiled
via CMake. No compiled emulation cores are checked into Git.

## Local Gradle build

Requires Java 17, Android SDK platform 35, build-tools 35.0.0, NDK
27.2.12479018 and CMake 3.22.1. First build Gambatte with ndk-build and copy
its ARM64 libretro .so to `app/src/main/jniLibs/arm64-v8a/libgambatte_libretro.so`.
Then run `gradle --project-dir android :app:assembleDebug`.

## License and source provenance

Original Delta source: [rileytestut/Delta](https://github.com/rileytestut/Delta),
AGPL-3.0; see repository COPYING. Gambatte libretro is downloaded from the
libretro project's upstream repository and retains its respective licenses.
Preserve existing notices and provide complete corresponding source for
distributed builds. No original Apple proprietary frameworks are redistributed.

## Development verification checklist

1. GitHub Actions builds and packages both ARM64 shared libraries.
2. APK installs and starts on the Samsung Galaxy A15 (Android 16).
3. Import a user-provided legally obtained .gb or .gbc; verify image/audio.
4. Check simultaneous touchscreen buttons and Bluetooth controller.
5. Verify .srm across closing and relaunching the app.
6. Test app pause/resume, screen rotation and fast inputs.
7. Audit CPU/GPU frame pacing and audio latency; optimize if necessary.

A successful compilation does not establish that all runtime tests passed.
