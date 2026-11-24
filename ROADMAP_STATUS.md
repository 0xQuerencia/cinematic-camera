# Alpha scope and verification

This milestone implements the final redesign's principal Android capture and grading workflows. The code is capability-gated: advertised hardware support is not proof of working capture.

| Area | Implementation |
| --- | --- |
| Capture | Simple/Pro, manual exposure and focus, WB presets/lock, FPS/resolution, shutter-angle lock with ISO matching, anti-banding, torch |
| Recording | Direct, clean and baked SDR; H.264/H.265 and advertised HLG10 direct; inspect saved dimensions, FPS, codec, transfer, audio, frame pacing |
| Grading | Original look catalog, cube import/export, curves, color wheels, selective HSL, skin-tone protection, diffusion, adaptive LUT and reference statistics |
| Monitoring | Scopes, preview-only overlays, horizon, configurable HUD |
| Photography | JPEG, capability-gated DNG, three-shot EV bracket, separate JPEG LUT export |
| Production | Slates, notes, take numbers, raw gyro CSV, journal recovery, media library |
| Remote | Bonded Bluetooth foreground commands |
| Performance | Direct capture, Lite preview, low-load/balanced choices, thermal and storage warnings/stops |

## Verification gates

The APK workflow compiles app and instrumentation APKs, runs JVM tests and lint, and publishes a debug APK with checksum. A separate emulator workflow executes Android instrumentation tests and saves reports. Consult the run for the exact commit; passing compilation does not imply hardware validation. Physical device validation is explicitly outside this implementation milestone.

## Limits and deferred experiments

No universal vendor LOG or RAW video, continuous auto ISO, native microphone gain or guaranteed route selection, frame-synchronized gyro stabilization, live remote video, or in-camera RAW grading is claimed. JPEG grading is a separate bounded-size copy. APV, external SSD capture, streaming, iOS, marketplace and Play publication are separate experimental/release projects, not implemented here. Hardware-dependent combinations and perceptual AV sync still need physical-device validation. Unfinished tests and failures must remain visible; do not label an alpha production-ready.
