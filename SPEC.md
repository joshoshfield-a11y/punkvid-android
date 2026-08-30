# PunkVid APK — Product & Engineering Specification
## "PunkVid Studio: Native Edition" — v2.0 Feature-Rich Rebuild

**Document purpose:** This is the authoritative build spec for an agent swarm tasked with turning the PunkVid TikTok Edition HTML prototype into a production Android APK. It describes the current prototype, its measured limitations, the full target feature set, the recommended architecture, a data model, and a phased build roadmap with acceptance criteria per phase.

**Source artifacts analyzed:**
- `PunkVid_TikTok_Edition (2).html` — desktop/tablet variant, ~107 KB single-file app
- `Tiktokviz1.html` — "S25_OPT" mobile-optimized variant of the same engine, ~220 KB

---

## 1. Product Vision

PunkVid is a **punk-aesthetic, audio-reactive music video generator**: you drop in a track, tune a wall of reactive visual effects, and export a vertical video ready for TikTok/Reels/Shorts. The prototype proves the concept is compelling — but it is trapped in a browser sandbox: exports happen in real time (you must sit through the whole song), rendering is CPU-bound Canvas 2D, nothing is saved between sessions, and there is no path to the device's music library or share sheet.

**PunkVid Studio: Native Edition** keeps the raw punk UI identity and the entire effect vocabulary, and rebuilds the foundation so the app is:

1. **Fast** — GPU shader rendering at a locked 60 fps preview, hardware-encoded export **faster than real time**.
2. **Deep** — multi-band audio analysis, beat detection, keyframe automation, scenes, layers, lyrics.
3. **Persistent** — projects, presets, and autosave survive sessions; everything is a shareable file.
4. **Native** — device music library, background export with progress notification, one-tap share to TikTok.
5. **Honest** — a performance governor that adapts quality to the device instead of stuttering.

---

## 2. Current State Analysis (Baseline)

### 2.1 What the prototype already does (feature inventory — preserve all of it)

| Module | Existing capability |
|---|---|
| Audio input | File upload (WAV/MP3), play/pause, seek slider, volume |
| Audio analysis | Web Audio `AnalyserNode`, `fftSize=256`, `smoothingTimeConstant=0.8`, derived bass/mid/treble energies, bass smoothing control, global reactivity multiplier (0.1–5×) |
| Core visuals | Grid floor (opacity), particle field (0–1500), bloom (0–100%), CRT scanlines, radial spectrum, waveform rings, 3D-extruded reactive text |
| Chaos Engine FX | Bass shockwave, mandala kaleidoscope (2–12 segments + spin), frame feedback (decay), wireframe cube, glitch blocks (datamosh), film grain, bass zoom punch, oscilloscope, EQ towers, RGB chromatic split |
| CPU post-FX | Chromatic aberration, VHS tracking, pixelate (gated behind "Enable CPU Pixel Effects"; actually implemented as `drawImage` compositing hacks — only the logo chroma-key uses `getImageData`), invert, mirror (ungated) |
| Theming | 6 palettes (Cyberpunk, Synthwave, Acid, Monochrome, Toxic, Sunset), custom primary/secondary/background colors, hue auto-cycle (0–10 speed) |
| Backgrounds | 6 procedural modes: Starfield, Nebula, Matrix rain, Plasma, Tunnel, Void + intensity slider |
| Text | Multi-line text, size 20–300 px, color, X/Y position |
| Image overlay | Logo upload, chroma-key BG removal (white/black key + tolerance), scale, X/Y |
| Beat logic | Crude bass-threshold beat flag (`smoothedBass > 0.65 ∧ bass > 1.3×mid`, 0.25 s cooldown) driving shockwave spawns, particle bursts, white beat-flash overlay; always-on vignette |
| Export | `canvas.captureStream()` + `MediaRecorder` → MP4 H.264 (fallback WebM), four 9:16 vertical resolutions — 720×1280, 1080×1920, 1152×2048, 1440×2560 (mislabeled in UI as "720p/1080p/1440p/4K"), 30/60 fps, Std/High/Ultra bitrate presets (4/8/12 Mbps base × resolution multiplier), max-duration cap, estimated file size, "Reset to TikTok Optimized" button |
| Note | A safe-zone guide rect (`strokeRect`) is drawn **onto the canvas itself** — burned into every export. The WebGL port must move guides to a non-rendered overlay layer |
| Performance | Live FPS counter, S25 variant: manual "Mobile Boost" + auto-degrade when FPS collapses |

### 2.2 Measured limitations (the problems v2.0 must fix)

1. **Real-time-only export.** `MediaRecorder` captures the live preview stream. A 3-minute song = 3 minutes of babysitting an export that can drop frames if anything hitches. No pause/resume, no background.
2. **`fftSize=256` → 128 frequency bins.** Bass/mid/treble separation is crude; no sub-bass isolation, no per-band control. Beat detection is a single bass-threshold flag (see §2.1) — no spectral-flux onsets, no BPM, no beat grid.
3. **Canvas 2D CPU rendering.** The app itself warns that chromatic/VHS/pixelate "read back every pixel from GPU" and gates them behind a SLOW checkbox. Particle counts in the thousands tank frame rate.
4. **Zero persistence.** No `localStorage`, no project files. Refresh = lose everything.
5. **No media library access.** Upload-only file picker; can't pull from the device's Music library; no audio trimming.
6. **Single static text layer, single static image layer.** No animation, no fonts, no keyframes, no lyrics.
7. **No scene/timeline logic.** One look for the whole video; the only temporal dynamics are audio reactivity and hue cycling.
8. **Codec lottery.** MP4/H.264 in `MediaRecorder` works on Chrome/Android, silently falls back to WebM elsewhere; the download is named `.mp4` regardless of actual container — a real correctness bug.
9. **No native sharing.** Produces a browser download; user must manually move it into TikTok.
10. **Manual performance management.** Users must discover why their phone is lagging.
11. **Hardwired 9:16 aspect — no landscape mode at all.** All four resolution presets are vertical (720×1280 → 1440×2560); the "1440p"/"4K" labels overstate the real pixel counts; the button with id `btn-yt-preset` is actually labeled "Reset to TikTok Optimized" and only resets bitrate/fps/duration. TikTok (9:16) is genuinely covered; YouTube landscape (16:9) and proper Shorts handling are not.

---

## 3. Design Pillars

- **Punk UI, pro plumbing.** Keep the black/fuchsia/cyan hard-shadow brutalist look (it *is* the brand), but every subsystem underneath is engineered properly.
- **GPU-first.** Anything that touches pixels runs in a shader. CPU post-FX as a category is abolished.
- **Offline-render truth.** What you preview is a real-time approximation; the exporter renders deterministically frame-by-frame, so exports are *always* clean even if preview stuttered.
- **Everything is a preset.** Every slider, toggle, and layer state serializes to JSON. Shareable, versionable, autosavable.
- **No dead ends.** If a device can't do 4K/60, the app says so up front and offers the best it *can* do — it never silently produces garbage.

---

## 4. Target Feature Set

### 4.A Render Engine (WebGL2 shader pipeline)

Replace Canvas 2D with a **WebGL2 fragment-shader pipeline**: each effect is a shader pass or instanced draw, chained through framebuffers (ping-pong FBOs).

- **Port the entire existing effect vocabulary to GLSL**: grid floor, radial spectrum, waveform rings, particles (instanced quads/points — raise ceiling from 1,500 to **≥20,000 GPU particles sustained; ceiling TBD by Phase-2 profiling**), bloom (proper mip-chain gaussian, not fake shadowBlur), scanlines, shockwave, kaleidoscope, frame feedback (FBO history), wireframe cube (GL lines), glitch blocks, film grain, bass zoom, oscilloscope, EQ towers, RGB split, chromatic aberration, VHS tracking, pixelate, invert, mirror.
- **New shader effects** (net-new for v2.0):
  - *Fluid/smoke field* (simplified Navier–Stokes or curl-noise advection driven by bass)
  - *Slit-scan / time-displacement* (uses frame-history buffer)
  - *ASCII/halftone rasterizer*
  - *Barrel distortion + fisheye* (beat-punched)
  - *Neon edge-detect* (Sobel glow on the whole frame)
  - *Strobe gate* (audio-gated, with seizure-safe 3 Hz hard cap — see §4.K)
- **Post-FX stack is a reorderable chain** — users drag effect order (e.g., feedback *before* vs *after* kaleidoscope produces radically different looks).
- **Deterministic engine contract (mandatory for Turbo Render).** The prototype calls `Math.random()` in 41 places (nebula lightning, matrix mutation, glitch blocks, grain, particle respawn, text/overlay shake) and integrates wall-clock `dt`. The WebGL2 port must route **all randomness through a seeded PRNG** and accept an **explicit time/frame-index parameter** instead of wall-clock. Turbo Render re-seeds from the frame index so every export of the same project is bit-identical. This refactor is part of Phase 2, not optional.
- Preview runs on a **frame-budget scheduler**: effects auto-shed in defined priority order when the 16.6 ms budget is missed (generalization of the S25 "Mobile Boost").

### 4.B Audio Engine

- **Configurable FFT: 512–16384** (default 4096), per-session smoothing per band.
- **8 analysis bands**: sub-bass (20–60), bass (60–250), low-mid (250–500), mid (500–2k), high-mid (2k–4k), presence (4k–6k), brilliance (6k–12k), air (12k+).
- **Beat/onset detection**: spectral-flux onset detector with adaptive thresholding → **beat pulse**, **downbeat estimation**, **BPM detection with confidence**, and a **beat grid** the rest of the app can snap to.
- **Reactivity mapping matrix**: any band (or beat pulse) can drive any numeric parameter of any effect, with per-mapping curve (linear/log/exp), min/max range, and attack/release smoothing. Ship 10 curated mapping presets ("Bass Drives Everything", "Treble Sparkle", etc.).
- **Audio utilities**: waveform overview strip with trim handles (choose the 15–180 s segment to export — TikTok-native workflow), loop mode, gain/normalize, mono/stereo metering.
- **Input sources**: device Music library via MediaStore (SAF fallback), file picker, **microphone live mode** (visualize live input, export disabled or clearly marked), and line-in where supported.

### 4.C Timeline, Keyframes & Scenes

- **Keyframe automation** on any numeric parameter: timeline lanes, cubic-bezier easing, snap-to-beat-grid, copy/paste keyframes.
- **Modulators** as an alternative to hand-keyframing: per-parameter LFO (sine/tri/square/random, rate in Hz *or* beat fractions like 1/4, 1/8), audio-follower, and envelope-on-beat.
- **Scenes**: named snapshots of the *entire* visual state (theme + background + FX chain + layers). Arranged on a scene track — switch at timestamps, on detected drops, or every N beats with crossfade/glitch-cut transitions.
- **Song structure assist**: auto-segment the track by energy (intro/verse/drop) and suggest scene switch points; user accepts/edits.

### 4.D Text, Lyrics & Overlay Layers

- **Unlimited layers** (text or image or video), each with: z-order, blend mode (normal/add/screen/multiply/overlay), opacity, position/scale/rotation, per-layer keyframes, per-layer audio reactivity.
- **Text**: TTF/OTF font import, stroke/shadow/glow, per-line styling; animated presets: typewriter, bounce-in, glitch-reveal, karaoke highlight.
- **Lyrics mode**: import `.lrc` or paste plain lyrics → auto-distribute lines across the beat grid → user nudges timing on the timeline → renders as synced karaoke text. This is a *killer* TikTok feature.
- **Image overlays**: the existing chroma-key (white/black + tolerance) ported to GPU, plus luma-key and spill suppression.
- **Video overlays/backgrounds**: import a clip as background plate or picture-in-picture layer (decoded via MediaCodec, frame-synced), with the same blend/reactivity options.
- **Camera layer** (stretch): front-camera PiP with edge-glow frame — reaction-style visualizers.

### 4.E Media Import & Library

- Device music browser (MediaStore: title/artist/album art/duration, search). **DRM-protected / undecodable library tracks are detected at selection time and rejected with a clear explanation** — many purchased/streaming-cached tracks cannot be decoded by `MediaExtractor`; the app must never crash or produce silent exports on them.
- Recent-files list, project-linked media re-linking with graceful "file moved" resolution.
- Album art auto-import as an optional overlay layer; dominant-color extraction to suggest a theme palette from the artwork (nice touch, cheap to build).

### 4.F Export & Sharing (the flagship fix)

- **Hardware encoding via MediaCodec + MediaMuxer**: H.264 (baseline→high) and **HEVC/H.265** where the device supports it; AAC 192/256/320 kbps.
- **Two render modes**:
  1. **Turbo Render (offline, deterministic)** — render frame-by-frame offscreen at a fixed timestep (seeded PRNG per frame index, see §4.A), encode to H.264/HEVC, mux. **Audio pipeline (explicit, resolves the trim/loop/gain conflict):** `MediaExtractor`/`MediaCodec` decode → apply trim/loop/gain/normalize on PCM → AAC encode → `MediaMuxer`. Never bitstream-copy the source: any edit invalidates the original stream. The same decoded PCM feeds the offline analysis pass, so audio-reactive frames are computed against exactly the samples being muxed. Not bound by real time; frame-perfect regardless of preview performance. **Throughput target: ≥1× real time at 1080p30 with default FX on the reference device (acceptance floor); up to 2–4× at 720p with light FX (best case, not a guarantee).** This is the default mode.
  2. **Live Capture** — the classic real-time mode, kept for "perform the visuals live with sliders" recordings.
- **Background export**: foreground service + persistent notification with progress %, ETA, pause/cancel. App can be backgrounded; export survives.
- **Platform format matrix (both TikTok and YouTube are first-class):**

  | Profile | Aspect | Resolutions | FPS | Duration cap | Codec / bitrate |
  |---|---|---|---|---|---|
  | **TikTok** | 9:16 vertical | 720×1280 / 1080×1920 / 2160×3840 *(4K device-dependent, gated by codec probe)* | 30 / 60 | 10 min (default 3) | H.264 High, 8–16 Mbps |
  | **YouTube (landscape)** | 16:9 | 1280×720 / 1920×1080 / 2560×1440 / 3840×2160 *(device-dependent)* | 24 / 30 / 60 | full track | H.264 High or HEVC, 12–45 Mbps (VBR) |
  | **YouTube Shorts** | 9:16 vertical | 1080×1920 | 30 / 60 | 3 min (default 60 s) | H.264 High, 8–16 Mbps |
  | **Instagram Reels** | 9:16 vertical | 1080×1920 | 30 / 60 | 3 min (default 90 s) | H.264 High, ~10 Mbps |
  | **Square (feed)** | 1:1 | 1080×1080 | 30 / 60 | 3 min | H.264 High |
  | **Custom** | 9:16 / 16:9 / 1:1 / 4:5 | 720p–4K | 24/30/60 | user-set | CBR/VBR, bitrate slider + live size estimate (port the existing estimator) |

- **Multi-aspect reframe engine.** All scene/layer coordinates are stored in **normalized space (0–1), not pixels**, so one project renders at any aspect. Switching aspect ratio re-frames rather than crops: per-aspect overrides for layer position/scale (set once, remembered per profile), platform **safe-area guides** (TikTok UI overlays, YouTube end-screen zone), and a landscape auto-compose that fans radial elements horizontally instead of vertically.
- **Batch export: "Render once, publish everywhere."** Queue one project into multiple profiles (e.g., TikTok 9:16 + YouTube 16:9 + Shorts cut) — the Turbo Renderer renders each sequentially in the background and drops them in the share sheet as a set.
- **Direct share sheet** to TikTok/Instagram/YouTube/Messages/files via Android `Intent` — no manual file shuffling. Save-to-gallery via MediaStore.
- **Fix the container bug**: extension always matches the real muxed container; if H.264 is unavailable the UI says so instead of lying in the filename.
- Bonus formats: **looping GIF** (short, palette-quantized) and **WebM/VP9** for the nerds.

### 4.G Projects, Presets & Persistence

- **Project file** (`.punkvid`, JSON + media references): full app state — audio ref + trim, every layer, keyframes, scenes, FX chain order, mappings, export profile.
- **Preset system at three granularities**: FX-chain presets, reactivity-mapping presets, full-look presets (theme+bg+fx). Built-in pack (the 6 legacy themes + 12 new looks), user presets, **export/import preset as file** for community sharing.
- **Autosave** every 30 s + on background; crash recovery prompt.
- **Undo/redo** (command stack, ≥50 steps).
- Onboarding: 3 sample projects with bundled demo audio so first launch is instant gratification.

### 4.H UI / UX

- **Native shell**: keep the punk design language (black `#0a0a0a`, fuchsia `#d946ef`, cyan `#06b6d4`, hard offset shadows, uppercase condensed type) rebuilt as native components.
- **Portrait-first one-hand layout**: canvas top, transport center, tabbed inspector bottom-sheet (drag to expand to full height). The 7 prototype tabs (Audio / Visuals / FX / Theme / Text / Img / Export) become 8: **Audio / Visuals / FX / Map / Scenes / Layers / Presets / Export**.
- Sliders: big touch targets, long-press for numeric entry, double-tap to reset, haptic detents at snap points (beat-synced values, theme defaults).
- Waveform strip with trim handles directly under the canvas.
- **Aspect-ratio switcher on the preview** (9:16 / 16:9 / 1:1 / 4:5 chips) with safe-area guide overlay, so users compose for TikTok and YouTube before exporting.
- Full-screen "perform mode": transport + 4 user-assignable macro sliders only (map any params) for live capture.
- Accessibility: content descriptions, scalable text, reduced-flash mode (see §4.K), colorblind-safe status colors (never red/green-only signaling).

### 4.I Performance Governor

- Device capability probe at first run (GPU tier, codec support matrix, max texture size) → suggests a quality tier; user can override.
- Live HUD: FPS, frame-time graph, dropped-frame count, thermal state.
- Adaptive degradation ladder (auto, announce-and-log): particle count → bloom mips → feedback resolution → background complexity → preview render scale. **Preview is degraded; Turbo Render never silently degrades** — instead, unaffordable export profiles (e.g., 4K FBO chains exceeding GPU memory: a 2160×3840 ping-pong + feedback + bloom chain is 200+ MB) are hidden or labeled up front based on the capability probe, consistent with §3's "No dead ends" pillar.
- Thermal-aware: on `THERMAL_STATUS_SERIOUS+`, pause Turbo Render with resume prompt instead of cooking the phone.

### 4.J Platform Extras (post-MVP stretch goals, in priority order)

1. **Live wallpaper** export (render engine as a wallpaper service, mic-reactive).
2. **Quick-share tile** / share-target: "Share audio → PunkVid" opens a new project with that track.
3. **Preset community**: in-app browser for shared `.punkvid` preset files (defer backend; file-based first).
4. **MIDI/controller input** for perform mode (USB OTG).
5. **Android Auto / media-session** metadata display. (Low priority.)

### 4.K Safety & Compliance (non-negotiable)

- **Photosensitive epilepsy guard**: strobe/flash effects hard-capped at 3 flashes/sec in both preview and export (WCAG 2.3.1); reduced-flash global toggle in settings that also flattens bass-zoom punch.
- Microphone permission only requested on entering live mode; rationale dialog first.
- Foreground-service notification is honest (real progress, cancelable).
- No analytics/tracking in v1 unless explicitly added later; everything on-device.

---

## 5. Architecture Recommendation

**Recommended: hybrid-first (WebView engine + native Android shell), migrating the render core to native GL only if profiling demands it.**

Rationale: the entire existing visual engine is portable JavaScript. Wrapping it in **Capacitor** (or a hand-rolled WebView shell) ships a working APK in Phase 1 with zero rendering rewrites. Native Kotlin layers then replace the browser's weaknesses (export, media library, persistence, background work) via a JS↔native bridge. The WebGL2 upgrade happens *inside* the web layer where the effects already live.

```
┌────────────────────────────────────────────────────┐
│                 Kotlin Native Shell                 │
│  MediaStore browser · Share sheet · Foreground      │
│  export service (MediaCodec/Muxer) · Room DB        │
│  (projects/presets/autosave) · SAF file access      │
├────────────────────────────────────────────────────┤
│              JS ↔ Native Bridge (Capacitor plugins) │
├────────────────────────────────────────────────────┤
│               WebView (Chromium)                    │
│  WebGL2 shader render engine (ported from Canvas2D) │
│  Web Audio analysis (or native PCM via bridge)      │
│  UI: ported tabbed inspector, punk design system    │
└────────────────────────────────────────────────────┘
```

**Turbo Render path (critical):** the offline renderer must NOT depend on WebView real-time capture. Two acceptable implementations:
1. **Headless WebView render**: render frames offscreen at fixed timestep, read pixels, feed frames to MediaCodec encoder in Kotlin. Simpler, reuses engine.
2. **Native GL duplicate of core passes**: faster, but doubles maintenance. Only if spike proves (1) too slow.

**The Phase-1 spike (option 1) must answer three make-or-break questions explicitly, with measured numbers:**
- **(a) Frame driving.** `requestAnimationFrame` is throttled/suspended in a hidden or backgrounded WebView. The spike must demonstrate manually-pumped frames (MessageChannel/setTimeout loop + explicit render calls, or OffscreenCanvas in a worker) sustaining a fixed timestep with the WebView non-visible.
- **(b) Frame handoff to MediaCodec.** Choose and benchmark: encoder-input **Surface** (zero-copy but requires a shared EGL/GL context across the WebView boundary — hard) vs. **byte-buffer input** (requires RGBA→YUV conversion — budget the CPU cost at 1080p and 4K).
- **(c) Readback throughput.** `gl.readPixels` at 1080p–4K on a midrange Adreno is typically 10–40 ms/frame, which alone can cap throughput near 1× real time. The spike must measure it on the reference device and report achieved render+readback+encode fps.
**Spike success criterion:** ≥1× real time end-to-end at 1080p30 on the reference device. If it fails, fall back to pre-decided option 2 (native GL core) — no third attempt.

**Alternative (if swarm prefers fully native):** Kotlin + OpenGL ES 3.1, GLSL port of all shaders, Jetpack Compose UI. Longer build, cleaner performance ceiling. Only choose this if the team is confident; the hybrid path de-risks delivery.

---

## 6. Tech Stack

| Layer | Choice | Notes |
|---|---|---|
| Shell | Kotlin, Android SDK 26+ (min), target latest | Foreground service for export; `MediaCodec`+`MediaMuxer`; `MediaStore` for music + gallery save |
| Bridge | Capacitor 6+ (or thin custom WebView bridge) | Custom plugins: AudioPicker, HardwareExporter, ShareSheet, ProjectStore, ThermalProbe |
| Render | WebGL2 + GLSL ES 3.0, ping-pong FBO post-chain | Port of every §4.A effect; instanced particles |
| Audio | Web Audio API (preview) + native PCM decode via MediaExtractor for offline render determinism | Beat detection in JS (spectral flux); validate against `aubio`-style reference |
| Persistence | Room (project index) + JSON project files on disk (SAF) | Autosave to internal storage |
| Fonts | TTF/OTF via FontFace API in WebView | — |
| Build | Gradle, GitHub Actions CI: assembleDebug + assembleRelease per PR | Signing via debug key in CI; release signing by user |

### 6.1 Permissions & manifest (mandatory — background export crashes without these at targetSdk 34+)

| Permission / declaration | Why | Gating |
|---|---|---|
| `FOREGROUND_SERVICE_MEDIA_PROCESSING` + service `foregroundServiceType="mediaProcessing"` | Background Turbo Render (§4.F) | Mandatory |
| `POST_NOTIFICATIONS` | Export progress notification | Runtime request, API 33+ |
| `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` | MediaStore music browser (§4.E) | API 33+ / API ≤32 |
| `RECORD_AUDIO` | Mic live mode (§4.B) | Runtime, only on entering live mode, rationale first (§4.K) |
| `Thermal API` (`PowerManager.getThermalHeadroom`) | Performance governor (§4.I) | API 29+; runtime-gated, gracefully absent on 26–28 |

minSdk stays 26 with runtime gates; if the spike shows WebGL2/codec gaps below API 29 are unmanageable, raise minSdk to 29 and note the device coverage cost.

### 6.2 JS↔Native bridge API (the contract Phases 2–4 consume — freeze at end of Phase 1)

```ts
interface PunkVidBridge {
  AudioPicker: {
    browse(filter?: { query?: string }): Promise<TrackMeta[]>;       // MediaStore
    decodeToPcm(uri: string): Promise<PcmHandle>;                     // for offline analysis
    isDecodable(uri: string): Promise<boolean>;                       // DRM/protection check
  };
  HardwareExporter: {
    capabilities(): Promise<{ codecs: string[]; maxResolution: [number, number] }>;
    start(config: ExportConfig): Promise<{ jobId: string }>;
    onProgress(jobId: string, cb: (pct: number, etaSec: number) => void): void;
    cancel(jobId: string): Promise<void>;
    // frame pump: native pulls RGBA frames from JS via getFrame(i) OR JS pushes via pushFrame()
    // — decided by Phase-1 spike §5(b), but the callback registry is frozen either way
  };
  ShareSheet: { share(uris: string[], mime: string): Promise<void> };  // TikTok/IG/YouTube intents
  ProjectStore: {
    save(project: ProjectJson): Promise<{ id: string }>;
    load(id: string): Promise<ProjectJson>;
    list(): Promise<ProjectMeta[]>; autosave(blob: ProjectJson): Promise<void>;
  };
  ThermalProbe: { status(): Promise<"nominal" | "light" | "moderate" | "severe"> };
}
```

All bridge calls are async; all expose typed error codes (`DRM_PROTECTED`, `CODEC_UNSUPPORTED`, `STORAGE_FULL`, `ENCODER_FAILED`, `PERMISSION_DENIED`) so the WebView UI can render honest failure states.

---

## 7. Data Model (project JSON — versioned)

```jsonc
{
  "schema": "punkvid.project/v1",
  "audio": { "source": "mediastore://… | file://…", "trimStart": 12.4, "trimEnd": 72.0, "gain": 1.0 },
  "render": { "aspect": "9:16", "theme": "cyberpunk", "background": { "mode": "nebula", "intensity": 0.5 }, "colorShiftSpeed": 0 },
  "analysis": { "fftSize": 4096, "bandSmoothing": { "bass": 0.8, "mid": 0.7, "treble": 0.6 }, "reactivity": 1.5 },
  "mappings": [ { "band": "bass", "target": "fx.shockwave.intensity", "curve": "exp", "min": 0, "max": 2 } ],
  "fxChain": [ { "id": "kaleidoscope", "enabled": true, "params": { "segments": 6, "spin": 1.0 }, "keyframes": [] } ],
  "layers": [ { "type": "text", "content": "PUNK IS DEAD", "font": "…", "pos": { "x": 0.5, "y": 0.5 }, "keyframes": [], "perAspect": { "16:9": { "pos": { "x": 0.5, "y": 0.2 } } } } ],
  "scenes": [ { "name": "Drop", "at": 32.0, "snapshot": { "render": { }, "fxChain": [], "layers": [] }, "transition": "glitch-cut" } ],
  "exports": [ { "profile": "tiktok" }, { "profile": "youtube-landscape" } ]
}
```

Presets reuse the same schema at subset granularity (`fxChain`, `mappings`, or `render`).

**Canonical parameter-path registry (Phase 1 deliverable):** `mappings[].target`, keyframe lanes, and perform-mode macro sliders all address parameters via one shared dotted-path registry (`fx.<id>.<param>`, `layer[n].pos.x`, `render.background.intensity`, …). A single `params.ts` module is the source of truth, exported to the bridge docs; no subsystem invents its own addressing.

---

## 8. Phased Swarm Build Roadmap

**Reference device for all acceptance criteria: Pixel 6a-class (Tensor G1 / Mali) or Snapdragon 695-class ("midrange").** All FPS/export targets below are measured on this class.

| Phase | Deliverable | Swarm agents | Acceptance criteria |
|---|---|---|---|
| **0 — Shell spike** | Existing HTML running in a Capacitor APK, full-screen, no console errors | 1 coder | APK installs; track plays; preview renders ≥30 fps on reference device |
| **1 — Native export** | MediaCodec Turbo Render (spike questions §5 a–c answered with measurements), background service + notification, share sheet, save-to-gallery, real extension/container, **bridge API §6.2 frozen**, param registry §7 drafted | 2 coders (bridge, encoder) | 60 s 1080p30 clip exports in ≤60 s on reference device; export completes with app backgrounded; share intent fires with video attached; file accepted by YouTube and TikTok upload flows |
| **2 — WebGL2 port** | All legacy effects as GPU shaders against the **formal parity checklist (§2.1 table, promoted)**; seeded-PRNG refactor done; CPU post-FX abolished; safe-zone guide moved off the render surface | 2 coders (shaders, engine) | Every §2.1 row checked off visually; FPS HUD ≥55 sustained with 5 FX enabled; 20k particles @60 fps; two Turbo Renders of same project are bit-identical |
| **3 — Audio+** | FFT 4096 default, 8 bands, spectral-flux beat detection, BPM, mapping matrix, waveform trim UI, MediaStore picker | 2 coders | Beat grid aligns with reference track within ±30 ms; any mappable param demonstrably driven by any band |
| **4 — Persistence** | Project JSON v1, Room index, autosave, undo/redo, preset packs (export/import) | 1 coder | Kill app mid-edit → restore exact state; preset file round-trips between two installs |
| **5 — Scenes, keyframes, layers, lyrics** | Timeline UI, keyframe lanes, LFOs, scene track, multi-layer text/image/video, LRC lyrics | 2–3 coders | 3-scene project with 2 keyframed params + synced lyrics exports correctly in both render modes |
| **6 — Multi-format** | Aspect switcher, reframe engine, safe areas, full format matrix (TikTok/YouTube/Shorts/Reels/Square), batch export | 1–2 coders | One project exports 9:16 + 16:9 + 1:1 without re-editing; guides match platform safe areas |
| **7 — Polish** | Performance governor, epilepsy guard, onboarding + sample projects, haptics, accessibility pass, CI release APK | 1 coder + 1 reviewer | No strobes >3 Hz possible; cold-start to first render <3 s; release APK <40 MB |

**Parallelization rule for the swarm:** Phases 0→1 are sequential (shell before bridge). Phases 2, 3, 4 can run in parallel worktrees once Phase 1 freezes the §6.2 bridge API. Phases 5–6 depend on 2–4. Phase 7 is the final gate with a reviewer agent signing off against this spec's acceptance criteria.

### 8.1 Error-behavior table (test-plan requirements — every row needs an automated or scripted test)

| Failure | Required behavior |
|---|---|
| Encoder dies mid-export | Notification shows failure + retry; partial file deleted; project untouched |
| Storage fills during mux | Abort cleanly, free partial file, tell user how many MB short |
| DRM-protected/undecodable MediaStore track | Blocked at selection with explanation (`isDecodable` pre-check, §6.2) |
| Permission denied (audio/notifications/mic) | Feature degrades gracefully with a one-tap path to settings; no crash loops |
| 4K profile on incapable device | Hidden/labeled up front via capability probe — never attempted |
| Thermal severe during Turbo Render | Pause + resume prompt (§4.I); job state survives process death |
| WebView GPU context lost | Recreate context, restore FBOs from project state, resume |

---

## 9. Risks & Non-Goals

- **WebView GL variability** across devices → mitigate with capability probe + degradation ladder (§4.I); Turbo Render determinism unaffected.
- **Scoped storage friction** for music import → MediaStore-first design, SAF fallback; never assume raw paths.
- **Codec gaps** on low-end devices (no HEVC, limited H.264 levels) → query `MediaCodecList` at first run and hide/label unavailable options (fixes prototype limitation #5 honestly).
- **Scope creep**: explicitly **non-goals for v1** — video editing/trimming of overlay clips beyond start offset, social account auto-posting APIs, iOS port, cloud render farm, AI-generated visuals.
