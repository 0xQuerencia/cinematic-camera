# Cinematic Camera — Design System 1.0

**Working product name:** Cinematic Camera (placeholder; trademark/store check before branding)  
**Design direction:** Precision instrument × editorial restraint  
**Platform:** Android, portrait and landscape; Kotlin / Jetpack Compose  
**Source:** Cinematic Camera Master Plan v3 (19 Nov 2026) and approved second visual direction  
**Status:** Design specification and interactive UI reference. Not an Android APK or tested implementation.

## 01 · North star

**A filmmaking instrument that disappears when you're capturing and becomes precise when you need to adjust.**

1. **The image is the hero.** No permanently floating cards over the viewfinder. Show only essential telemetry in the capture state; secondary controls live in an immediately reachable drawer.
2. **One Pro experience, every phone.** The same control labels, order, panels and import/export workflows on budget and flagship models. A control can be Native, Assisted or Unavailable based on the selected *camera mode*, never the price of the device.
3. **Truth over theatrics.** Display actual codec, resolution, frame rate, shutter/exposure time, capture color pipeline and whether a look is baked in or preview-only. Do not simulate sensor capabilities with misleading sliders.
4. **Muscle memory over decoration.** Numbers stay in fixed positions; sliders show current values; the record button cannot jump when the layout changes.
5. **Capture safety.** Monitoring, LUT calculations and animation are expendable; video integrity isn't. Performance warnings recommend changes for the *next* clip, not silent mid-clip downgrades.
6. **Reachability.** Full Pro panels and editing in portrait and landscape. Touch regions are at least 48dp, even when their icons are smaller. Left-handed layout setting and accessibility text scaling supported.

## 02 · Visual character

Dark, neutral, optically quiet. Slightly warm graphite surfaces, clean typography, clinical dividers, purposeful amber and a single red record state. No glows, gradient cards, frosted glass, oversized pill menus, emoji navigation, skeuomorphic dials or animated decoration competing with the live image.

### Color primitives

| Token | Hex | Usage |
| --- | --- | --- |
| `ink.950` | `#090A0B` | Root / camera black |
| `ink.900` | `#111315` | Tool surfaces |
| `ink.850` | `#17191B` | Raised sheets |
| `ink.800` | `#222528` | Resting control fields |
| `ink.700` | `#33383B` | Outlines and dividers |
| `paper.000` | `#F4F3F0` | Primary text, crucial telemetry |
| `paper.200` | `#D0D2D0` | Secondary text |
| `paper.400` | `#969B9B` | Tertiary annotations |
| `amber.500` | `#F2B65A` | Selected controls, focus and adjustment focus |
| `amber.100` | `#2D2418` | Low-contrast selected backgrounds |
| `red.500` | `#E94B43` | Recording and destructive confirmation **only** |
| `green.500` | `#77C99A` | Verified Native / healthy state |
| `blue.500` | `#79B8D4` | Informational data (e.g. clean capture) |
| `gray.500` | `#A7ADAE` | Unsupported / unknown state |

**Semantic rules:** amber does **not** mean recording. Red means recording, take-at-risk or destructive actions. Green means *verified* native control, never merely advertised support. Never communicate the three capability states with color alone: always pair a status word and icon. Avoid pure white filled panels near the live preview.

### On-image legibility

- Use a linear black scrim (`#000` at 70% to transparent) **only** under HUD telemetry, not over the entire image.
- Text on top of image needs a backed label, dark 1px shadow or strong scrim; if the image is brighter than the contrast threshold, increase the scrim instead of shrinking text.
- Guidelines use white at 65% opacity; active manual focus rectangle uses amber; clipping/zebra overlays are user-selectable and always preview-only.
- Keep scope overlays out of safe composition guides when possible.

## 03 · Type, iconography, spacing

**Primary family:** Inter preferred in design tools; Android implementation uses bundled Inter (license checked) or Roboto as the documented system fallback. **Readouts:** tabular numerals (same family with `tnum`) and optional IBM Plex Mono for timecode / engineering telemetry. Avoid an all-monospaced UI.

| Role | Size / line | Weight | Behavior |
| --- | --- | --- | --- |
| Display (design docs only) | 36 / 42 sp | 600 | Never over camera |
| Screen title | 22 / 28 sp | 600 | Look Lab, Library, Settings |
| Panel heading | 18 / 24 sp | 600 | Manual Controls, Output |
| Body | 15 / 22 sp | 400 | Explanations and settings |
| Control label | 14 / 20 sp | 500 | Tap target label |
| Value | 16 / 22 sp | 600, tabular | Exposure, WB, shutter, ISO |
| HUD numeric | 14 / 18 sp | 600, tabular | Stable readouts in capture |
| Caption | 12 / 16 sp | 500 | Status/hints, never sole control label |
| Micro | 11 / 14 sp | 500 | Optional nonessential diagnostics |

**Density:** 4dp baseline grid; spacing `4 / 8 / 12 / 16 / 20 / 24 / 32 / 40 / 48`. The app is compact but not cramped. 1dp dividers; border radii `6` (instrument controls), `10` (sheets), `14` (large dialogs) and `50%` (record). No 24dp+ card rounding in the Pro workspace.

**Icons:** a consistent 20dp stroke family (1.75–2dp stroke), no mixed filled/outlined iconography. 24dp for primary mode icons, 18dp for compact diagnostics. Every icon-only action has an accessible label and 48dp+ hitbox. Critical actions have labels or unmistakable metaphors.

## 04 · Layout grid and orientation

- **Android edge-to-edge:** respect status/navigation insets and cutouts, but do not permanently reserve blank bands where system bars are hidden in immersive capture.
- **Portrait:** 16dp side-safe margin, 8dp inter-control gap; bottom record dock anchored above system gesture area; full-height live view where aspect permits. Avoid expanding filter rail until preview becomes unusable.
- **Landscape:** 12–16dp safe inset; telemetry in a stable top instrument bar; record, gallery and recording status on a right rail; quick monitoring switches on left; viewfinder in the middle.
- **Width classes:** compact `<600dp`, medium `600–839dp`, expanded `>=840dp`. Medium/expanded panels can dock beside the viewfinder; compact opens an overlaid bottom sheet/drawer.
- **Do not scale controls proportionally with screen pixels.** Respect dp, density and font scale independently. The UI should remain touchable on a 5.8-inch phone and a tablet.
- **Landscape-only precision tools:** not allowed. When width is tight, tabs scroll and controls stack, while keeping every tool discoverable.
- **Viewfinder:** preserve real preview aspect ratio; letterbox intentionally instead of stretching the picture or cropping silently. The composition guide is independent of encoded crop.

## 05 · Anatomy of the camera

### Simple capture

`Top: Settings · [Simple | Pro] · output summary`  
`Center: actual viewfinder · tap focus · active guide · optional level`  
`Bottom of preview: look intensity (on demand), minimal exposure summary`  
`Look rail: 4 visible previews, horizontal scroll, current look named`  
`Dock: Gallery · Record · Flip camera (or lens selector) · tools`

- Open to a visible camera frame, never onboarding before record. Camera/microphone permissions should be requested with a clear explanation at point of need.
- Default to the last verified recording configuration with a conservative fallback (1080p30 SDR where available), not a best-guess 4K.
- Long-press the viewfinder for AE/AF lock where physically supported; otherwise explain active mechanism.
- A/B compare is **press-and-hold** on the preview compare button. Release returns to the selected look. A voice accessibility action also exists.
- Swiping the look rail does not change the look accidentally; tapping a thumbnail previews/commits, then optionally adjusts intensity.

### Pro capture

`Top: actual RES · FPS · shutter/exposure · ISO · WB · active lens · codec/profile`  
`Left: guides · scopes · peaking · zebras · more`  
`Viewfinder: scene + only requested overlays`  
`Right: red REC / STOP · clip browser · primary manual drawer`  
`Bottom: meters · remaining storage/time estimate · look/output mode status`

- Tapping a telemetry value opens its anchored precision drawer. **One value is editable at a time.**
- Tapping `PRO` does not force manual control or change exposure. It changes visibility only.
- When shooting starts, freeze topology of the HUD. Never move the record button because a heat warning appears.
- Status during record is timecode `REC 00:00:12` with a high-contrast red dot; no decorative flashing animation beyond a subtle status pulse.
- State `LOOK BAKED` vs `LOOK PREVIEW / CLEAN FILE` is visible before record and in clip metadata.

### Manual control sheet

Use a shared labeled row: icon, full name, status (`Native / Assisted / Unavailable`), verified current value, caret. A chosen row expands to a horizontal slider or calibrated rotary precision input (sliders default for accessibility). Provide `Auto / Lock / Manual` modes only when each mode has a verified real effect. Numeric input is available for long ranges.

**Important:** software exposure trim, Kelvin/Tint adjustment, and digital zoom are named as such; don't label these as physical sensor changes. If shutter speed can't be set, show `Auto · 1/50s reported` and a clear explanation rather than a nonfunctional `1/48` manual slider. An unavailable state stays visible with an explanation and a suggestion.

### Look Lab

A clean preview vs edited split, undo/redo, target input-color profile, clip source vs preset source, LUT loading and strength, grade controls, film effects, reset and save. Organize into `Light / Color / Curves / LUT / Film`, with **a visible processing label** `Live · Lite · Export only` for the selected effect. Render comparison on the same source frame for honesty.

### Looks & Presets

Distinguish **Look** (color + effects) from **Capture Preset** (look + capture configuration + composition/audio). Separate tabs, plus Favorites, My Looks and Imported. Images should have consistent reference content when comparing the *same* color effect; do not compare different scenes and suggest color grading is solely responsible. Import validates `.cube` against declared input/output color spaces.

## 06 · Reusable components (component contract)

| Component | Anatomy | Interaction, states and constraints |
| --- | --- | --- |
| `ModeSwitch` | Simple / Pro | Always shown; switching modes retains live capture settings |
| `RecordButton` | 64–76dp circular hitbox, white ring, red center | Idle / starting / recording / stopping / failed; never moves; read `Recording, stop` via TalkBack |
| `TelemetryCell` | label (11–12sp), numeric (14–16sp), optional status dot | Taps open corresponding control; `—` instead of fabricated telemetry |
| `PrecisionSlider` | name, value, [-/+], track, ticks optional | 48dp touch field, hysteresis, coarse/fine swipe; live preview; reset & confirm when destructive |
| `CapabilityMark` | Native / Assisted / Unavailable + icon | Includes plain-language help; per lens and mode; never relies on color alone |
| `ControlRow` | icon, title, value, capability, caret | min 56dp height, expanding details, keeps focus in sheet |
| `LookThumbnail` | consistent test image, name, active outline | 72–96dp visual rail; tap select, long press details; visual compare remains separate |
| `LookIntensity` | 0–100% amount, neutral reference | 0% neutral, 100% target; avoid changing capture bit depth |
| `FrameGuides` | safe area + horizon + thirds | Compositing overlay only, never encoded |
| `ExposureScope` | luma waveform/histogram, color mode label | Low-end device reduces refresh/samples, never claims full precision |
| `AudioMeter` | 2-channel bars/dBFS, mic route | clipping indication, clear input label; not just dancing decoration |
| `OutputBadge` | SDR/HDR, 8/10-bit, codec, baked/clean | Measured or active mode only, not aspirational |
| `AlertStrip` | state icon, one-line cause, action | heat, storage, mic disconnect; no unsolicited in-recording settings changes |
| `BottomSheet` | anchored tool title, close, scroll content | capped height for remaining viewfinder visibility; portrait/landscape layouts |
| `Toast` | concise action and optional undo | 2–4 seconds for noncritical actions; persistent banner for capture-risk issues |
| `PrimaryAction` | verb, optional trailing icon | default 48–56dp; visually calm amber, not red except recording |

## 07 · Capability states: hard behavioral rule

| State | Presentation | Effective behavior |
| --- | --- | --- |
| **Native** | Green dot + `Native` | Camera API reports and verified test supports physical operation in current mode |
| **Assisted** | Amber dot + `Assisted` + actual method | Exposes genuine software transform or verified alternate method; informs user of tradeoffs |
| **Unavailable** | Gray slash + `Unavailable` | Row remains visible; opens a factual explanation; slider/action is disabled, not a placebo |
| **Checking** | Neutral progress + `Checking` | No capability promise until verification completes |
| **Temporarily unavailable** | Neutral explanation + `Try again` | Other camera stream/recorder/thermal condition prevents action right now |

**Example:** On a fixed-focus budget phone, `Focus` remains in the same row. Tapping it shows `Fixed-focus lens · focus distance cannot be changed`; focus peaking may still run as a separate visual aid. The UI must not imply a simulated focus rack is optically focusing.

## 08 · Screen inventory (implementation-ready)

1. **Permission explainer:** camera and audio only when necessary; offline-first; no account gate.
2. **Simple capture:** full viewfinder, looks, record, quick settings.
3. **Pro capture landscape:** central viewfinder, fixed rails, readout bar, monitoring tray.
4. **Pro capture portrait:** stacked controls with horizontal telemetry scroll; all tools present.
5. **Manual controls:** Exposure, Focus, WB, Camera, Audio tabs; capability marks.
6. **Monitoring:** histogram, waveform, vectorscope, false color, zebras, peaking, safe guides.
7. **Look Lab:** split preview, grade, LUT, film stack and save.
8. **Looks Library:** curated packs, imported `.cube`, favorites, input-profile compatibility.
9. **Capture Presets:** capture + look + audio + framing; per-device adaptation list.
10. **Media Library:** clips, baked-vs-clean status, verified output metadata, playback and export.
11. **Device Report:** current lens/mode support, tested/untested, export diagnostics.
12. **Settings:** recording, storage, audio, preview, accessibility, left-hand mode, privacy.

## 09 · Motion and feedback

| Feedback | Duration | Pattern |
| --- | --- | --- |
| Tap | 80–120ms | pressed state, subtle surface darkening |
| Open precision drawer | 160–200ms | simple vertical motion + fade |
| Mode switch | 120–180ms | no camera-session rebuild unless settings actually change |
| Look preview | 100–160ms | gentle crossfade *if* GPU can afford it; otherwise hard swap |
| Record start | System dependent | confirmation is actual recorder state, never optimistic red UI |
| Scope refresh | 2–15 fps per capability | independent of encoder and main UI frame budget |
| Reduce motion | 0ms preferred | settings, OS accessibility flag |

Haptics: restrained tactile response on record start/stop and slider detent (when supported and enabled). No continuous buzz while scrubbing. Audio clicks must be opt-in and never contaminate clips.

## 10 · Accessibility, visibility, localization

- Minimum 48×48dp interactive targets; no 12sp text as the sole critical input label; no crucial overlay without backplate/scrim.
- Body text target WCAG AA contrast on surfaces; on dynamic imagery use additional black backing.
- Respect font scale and display size; use ellipsis only for nonessential descriptors, never essential numeric settings.
- Test with TalkBack, Switch Access, large fonts (up to 200%), landscape rotation, high sunlight and high ambient noise.
- Make `Native`, `Assisted`, `Unavailable` textual. Provide textual feedback for false-color and zebra overlays.
- Support high-contrast HUD and left-handed control rails, and a way to hide busy scope overlays without disabling them.
- Don't enforce fixed English-only formatting: metric/imperial optional, decimal comma and localization-safe numerical presentation.

## 11 · Responsive and adaptive behavior

**Universal Pro acceptance scenario:** a budget phone must show every Pro section and opening control. Available native shutter can be edited; assisted white balance may apply a LUT color trim; unavailable focus adjustment remains discoverable with an explanation. Look Lab still works, with effects marked `Lite` or `Export only`. The same files and presets can be transferred to and from flagship devices.

Performance affects **rendering**, not the information architecture:

- **Live / Full:** full-rate preview and record processing when verified.
- **Live / Lite:** inexpensive LUT and lower-frequency scopes; encoded result remains honestly labeled.
- **Export only:** capture clean + manifest; postprocess look later with a clear progress step. Before record, explain that the live preview is an approximation if necessary.
- **Unsupported physical control:** retain location, explain constraint, avoid fake values.

The app should never silently change sensor mode, frame rate, resolution, color profile or baked/clean file mode during a take.

## 12 · Design QA and deliverables

### Visual acceptance

- Same icon language and typography across all 12 screens.
- Every normal action reachable within two taps from capture; critical advanced actions at most three taps.
- A user can tell whether record is active, what real capture format is used, and whether a look is baked, at a glance.
- A budget-phone screenshot can show exactly the same Pro navigation and panels as a flagship screenshot, with different accurate capability states.
- No false native status and no dead sliders. No camera UI elements drift or change order mid-capture.

### Handoff

- `index.html`: locally viewable interactive visual system. The camera itself is simulated; there is no functional Android capture.
- `design-tokens.json`: semantic palette, type, dimensions, spacing, layout, motion and state rules.
- `assets/`: illustrative image crops from earlier *concept* designs; not final licensed camera look samples or production LUTs.
- `DESIGN_SYSTEM.md`: this source specification.
- `AndroidTheme.kt`: minimal Compose token example, not a compiled app.

### Build sequencing

1. Implement reusable typography, tokens, icon system and sheets.
2. Build actual Simple and Pro camera shells bound to **real** CameraX state; no sample readouts shipped as actual metadata.
3. Add capability state model and precision controls.
4. Integrate LUT/capture pipeline and confirm preview/encoded match.
5. Add Look Lab, looks, presets and scope overlays with instrumentation.
6. Test usability and captured output on a genuinely low-cost Android device before polishing advanced animation.

**Non-goals:** copying Blackmagic trade dress, representing concept photos as camera output, promising universal hardware features, or shipping an APK from a visual prototype alone.
