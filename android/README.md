# Delta Android — native ARM64 development port

This continues the existing `android/native-bootstrap` branch inside the authentic
Delta repository. It preserves iOS and the original submodule revisions. The
current development version is **0.3.0-dev**, not a stable or complete release.

## Current implementation

- Native Kotlin library, search, favorites/recent games, horizontal console pages,
  local covers, multi-document ROM import and persistent content-addressed library.
- Colors read from the actual `Resources/Assets.xcassets/Colors` catalogs, original
  placeholder artwork and pause icons, 90pt covers and source-derived grid spacing.
- Native inset grouped settings with working opacity, button haptics, silent-mode
  audio, controller selection and Menu quick gestures.
- Original Standard `.deltaskin` archives, PNG/PDF artwork, legacy and modern
  representation nesting, portrait/landscape layouts, inherited hit padding,
  `gameScreenFrame`, cropped screens and app/controller placement. Per-system,
  per-orientation skin selection retains previously imported 0.2 GBC packages.
- Actual **Gambatte GB/GBC** from the existing libretro adapter, now pinned.
- Actual **Nestopia NES**, compiled from the exact source vendored in Delta's
  original NESDeltaCore submodule. It is not a different NES emulator.
- Dynamic native video, the core's actual frame rate, stereo PCM, touchscreen
  multitouch, Bluetooth/USB keys, D-pad and joystick axes, fast forward and held
  buttons. Audio focus loss pauses gameplay.
- Native pause grid with working Resume/Main Menu, Save/Load State, manually
  entered cheats, Fast Forward, Hold Buttons and screenshot export through SAF.
- Atomic SRAM/RTC saves, integrity-checked states bound to ROM/core, legacy raw
  state reading and rollback if a core rejects a state. Autosave on background
  and paused restoration after Activity recreation. This does not guarantee
  recovery if Android kills the process before any lifecycle callback.

## Explicitly pending

VBA-M/GBA, Snes9x/SNES, Genesis Plus GX, mupen64plus/N64 and melonDS/DS remain in
scope and are not declared functional. DS firmware/BIOS, touch/microphone, 3D and
layout/runtime integration are still pending. No commercial ROM or BIOS is shipped.

Delta Sync has no verified interoperability and is unavailable. There are no
pretend cloud services. Animated skins, Core Image filters, graphical thumbsticks,
complete settings/pause save-state browser parity and additional original app
features still need native equivalents. Import rejects unsupported image/filter
features instead of claiming full `.deltaskin` format support.

The reference tree is Delta **2.0b5 (163)**. Initial visual work follows its UIKit
grid presentation before iOS 26, not its new Liquid Glass UI. Android uses native
system type as a redistributable alternative to San Francisco. SF Symbols that
have no redistributable image are represented with native paths; supplied original
artwork is reused byte-for-byte. **No measured 1:1 visual claim is made.**

## Download without a computer

Open [GitHub Actions](https://github.com/YlPorts/Delta-android-/actions/workflows/android-arm64.yml),
select a successful run for `android/native-bootstrap`, then download
`DeltaAndroid-0.3.0-dev-arm64`. Extract and install `app-debug.apk` on ARM64 Android.
It is debug-signed and remains a development build. See `keystore/README.md` for
the public development key and the upgrade limitation from older randomly signed APKs. The same run supplies
corresponding source, test logs, Android screenshots and lint/runtime reports.
Check the entire run, including runtime tests; an APK build alone proves less.

## Reproducible build

Java 17, Gradle 8.9, SDK 35, build-tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1,
Python 3, Git and a C/C++ toolchain are required. With `ANDROID_HOME` configured:

```sh
python3 android/tools/build_cores.py --prepare --abi arm64-v8a
python3 android/tools/import_ios_colors.py
python3 android/tools/import_ios_assets.py
python3 -m unittest discover -s android/tools/tests -p 'test_*.py'
gradle --project-dir android :app:assembleDebug :app:lintDebug
python3 android/tools/verify_apk.py android/app/build/outputs/apk/debug/app-debug.apk
```

The core lock is `tools/core-sources.json`; original core revisions are gitlinks.
The preparer resolves public original Git LFS assets and checks each pointer's
SHA-256/size. Resource provenance is in the APK's `assets/ios/provenance.json`.
Nestopia is built with its original GNU C++98 dialect and the modern NDK C++ STL.
All native ELF LOAD segments must support 16KB pages. Libraries are compressed
and extracted at installation so the existing native host can load and fingerprint
actual files, including on Android 16. No opaque prebuilt engines are downloaded.

## Runtime and visual verification

```sh
python3 android/tools/build_cores.py --prepare --host-tests
python3 android/tools/build_cores.py --abi x86_64
python3 android/tools/tests/diagnostic_roms.py android/app/build/generated/androidTestAssets
gradle --project-dir android -PdeltaAbi=x86_64 :app:connectedDebugAndroidTest
```

Run instrumentation on a **disposable test AVD**. It imports authored diagnostic
programs into the test install. Generated programs are CC0 and contain no Nintendo
logo, commercial game, firmware or BIOS bytes; they are test assets only and are
explicitly excluded from the application APK.

The production C++ host is tested with ASan/UBSan against real cores: CPU-generated
video and audible PCM, input observed by the emulated CPU, SRAM/RTC, save/load,
corrupt and wrong-ROM state rejection, legacy states and failed-load cleanup.
Android instrumentation additionally exercises JNI, PDF skins, library/settings,
pause, rotation and background/resume, and captures actual screens. These tests
are not a game compatibility suite. Sanitizers cover the host; the upstream core
binaries are not themselves sanitizer-instrumented in this first test job.

See `docs/VISUAL-VERIFICATION.md` for source measurements and the comparison gaps.
A real Galaxy A15 SM-A155M / Android 16 / Mali-G57 MC2 is still required to check
ARM64 runtime, sustained speed, audio latency, gamepads, cutouts, thermal behavior,
memory, and process killing. Desktop/emulator checks do not establish those results.

## License and corresponding source

Delta remains AGPLv3; see `COPYING`. Gambatte and Nestopia retain their original
GPL notices; libretro support code retains its own notices. The APK includes
license texts, and Actions publishes source archives with exact revisions,
including the complete source-built Gambatte adapter and original submodules.
No Apple framework or proprietary font is redistributed. Future engines require
individual license/source review before distribution.
