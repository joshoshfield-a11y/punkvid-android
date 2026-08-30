# Plan — Build PunkVid APK (from PunkVid_APK_Spec v2.0)

Goal: deliver an installable, signed Android APK of PunkVid per the spec, Phase 0/1 scope:
the full audio-reactive visualizer engine (Canvas/JS, feature parity with §2.1) running in a
native Kotlin WebView shell, with in-app export (MediaRecorder → MP4) saved to the device
via a JS↔native bridge + share sheet.

Note: the original HTML prototypes referenced in the spec were not uploaded — the engine
must be recreated from the spec's feature inventory (§2.1 / §4).

## Stage 0 — Environment setup (main agent)
- Download Android cmdline-tools + Gradle (dl.google.com / services.gradle.org reachable).
- Install platform android-34, build-tools 34.0.0, accept licenses.

## Stage 1 — Web engine (coder subagent)
- Single-file `index.html` recreating the PunkVid engine per spec §2.1:
  audio upload/play/seek, AnalyserNode bands, themes (6 palettes), 6 backgrounds,
  full FX vocabulary (grid, particles, bloom, scanlines, spectrum, rings, reactive text,
  shockwave, kaleidoscope, feedback, cube, glitch, grain, zoom punch, oscilloscope,
  EQ towers, RGB split, chromatic, VHS, pixelate, invert, mirror), logo overlay w/ chroma-key,
  beat detection, export UI (4 vertical resolutions, 30/60fps, bitrate presets),
  safe-zone guide NOT burned into export, FPS counter + auto-degrade.
- Bridge hooks: `PunkVidNative.saveVideo(base64, name)`, `shareVideo(path)`, no-op in browser.
- Output: /mnt/agents/output/punkvid-android/app/src/main/assets/www/index.html

## Stage 2 — Native shell (main agent)
- Kotlin WebView shell: fullscreen, file chooser for audio/logo, JS bridge
  (save MP4 to MediaStore/Movies, Android share intent), permissions, immersive mode.
- Gradle project: AGP 8.x, compileSdk 34, minSdk 26.

## Stage 3 — Build & sign (main agent)
- `gradle assembleDebug`, debug-signed APK, validate with aapt/unzip.

## Stage 4 — Deliver
- APK + source zip in /mnt/agents/output/, KIMI_REF tags.
- Optional: push source to GitHub using user's PAT (append-only, new repo).
