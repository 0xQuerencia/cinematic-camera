# Verification report

## Reference-board redesign

The rebuilt debug APK uses the three supplied boards for the layout of Simple, Pro, Look Lab, and the looks and media libraries. Emulator screenshots showed a live Simple viewfinder, landscape Pro rails with separate format and exposure strips, a live Golden Hour A/B split, a photographic LUT grid, and MediaStore clip thumbnails and metadata. The redesign keeps native camera readouts and the existing slider controls.

The redesign APK SHA-256 is `8d93779a882276aa3897afd2d25754bb3e247f26e4cdba98055616aa49a10c2d`. The Gradle build, five JVM tests, three Android instrumentation tests, APK signature check, and Android Lint passed. The capture and color measurements below describe the original development build.

## Build and automated checks

- Debug APK and instrumentation APK built with Gradle 8.14.3, AGP 8.13.2, Kotlin 2.2.10, JDK 21, and SDK 36. Android Lint completed with no errors. Android SDK apksigner verified the debug APK signature.
- The local APK SHA-256 is `c4bc246ef1f42c6753dd8e68e4a52075c9347921aa1cfcbe85bb78f968363008`. All five JVM tests and all three Android instrumentation tests passed. The JVM tests cover LUT channel order, interpolation, domain remapping, malformed input, and export/import round trips across the original catalog.
- The camera and offline GLSL variants compiled and linked with glslangValidator. The 2D-input variant also rendered a three-patch SDR chart on desktop OpenGL ES. The largest normalized RGB channel errors at 0%, 40%, and 100% strength were 0.00000, 0.00157, and 0.00235, within the 0.013 test tolerance.

## Emulator recording and color

The Android 15 x86_64 emulator used a virtual rear camera and SwiftShader. The build fingerprint was `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`.

- The UI capture test saved an H.264 MP4 with video and microphone samples. The Android OpenGL test passed the CPU chart comparison and the versioned look and preset test passed serialization round trips.
- Clean preview showed Crushed B&W. Its saved 1280×720 H.264 clip retained the original color, with a mean RGB channel spread of 33.00 on the decoded sample frame. The 720×1280 Baked clip had a mean spread of 0.00 and nonzero brightness. Both files contained AMR-NB audio from the emulator recording profile.
- The offline graded copy contained 60 H.264 video frames and retained the 72 AMR-NB audio samples of the clean source. Decoded mean RGB channel spread was 0.00, with mean brightness 58.44 on a 0–255 scale. The original clip stayed in MediaStore.
- The emulator advertised a 30 fps target but these short recordings averaged about 12.9 fps in Clean and 10.7 fps in Baked. The app reported the frame-rate mismatch for both. These runs do not establish 30 fps performance on phones.
- Revoking camera permission showed the access prompt and disabled recording. Restoring access reopened the camera.
- Injecting critical thermal status during a direct take stopped recording and preserved an inspectable 3.50-second H.264 MP4 with 67 video frames and 55 audio samples.
- Rotating from portrait to landscape during a take preserved the active recorder. Stopping from landscape saved a 9.26-second MP4 with 207 video frames and 145 audio samples, with the original portrait rotation tag. Record and Manual controls were reachable together in landscape.
- The first Android shader test exposed an RGB16F LUT upload that sampled black on this graphics stack. RGBA16F with typed half-float data passed the Android test. Runtime testing also exposed a Baked session that delivered frames without transitioning the app readiness flag and an offline shader that left Media3 on the wrong vertex array. The fixes were verified through encoded output.

## Scope and design decisions

- Source design tokens are checked into the repository. The supplied text specification and tokens were available. The referenced interactive camera prototype, camera images, and example Compose theme were absent from the handoff. The app uses the allowed system type fallback and consistent color-chart swatches for look comparisons.
- CameraX owns camera session configuration, encoding, audio, and MediaStore writing. Resolution and fixed-FPS options are intersected with advertised camera and AVC encoder capabilities. Saved files are inspected for codec, size, sample timestamps, color tags when supplied, and audio samples. A mismatch remains visible in the clip details.
- Direct starts with an unprocessed camera session. Clean adds a preview effect only. Baked uses the documented CameraX Preview + VideoCapture effect target. In Baked, comparison is disabled for the duration of the take because preview and encoding share the rendered stream.
- Lite skips bloom and halation in Clean preview and reduces scope refresh. A clean clip's manifest can be rendered to a new MP4 offline. The source remains in the library. Scopes show downsampled source RGB. Clipping and edge aids appear as sample maps in sensor orientation.
- DataStore holds UI preferences. Versioned local files store looks, presets, and clip manifests. MediaStore owns videos. Identical cubes in clip manifests share a content-addressed local file.

## Remaining product work

- HDR/HLG, HEVC selection, RAW, genuine camera LOG, manual audio routing and gain, and native Kelvin where exposed.
- Multi-point curves, hue curves, clip-based grading and adjustable split position, richer film-effect quality tiers, horizon level, torch control, and full-resolution on-image zebras and peaking.
- Process-death recovery, device-specific diagnostics history, accessible overflow and large-font review across the complete screen inventory, and visual sign-off against the missing reference assets.

## Physical-device acceptance

No physical phone was connected. The master plan's device acceptance gates are open. Run the following on at least one budget phone, one midrange phone, and one flagship before claiming device coverage.

| Test | Pass condition |
| --- | --- |
| Direct, Clean, and Baked capture | Record 30 seconds in each supported mode on rear and front cameras. Decode each MP4 and inspect codec, dimensions, actual frame cadence, microphone signal, tags, rotation, and A/V sync. |
| Preview and output color | Record a locked, static chart with known SDR transforms. Compare preview, clean source, baked decode, and offline export. Establish an error tolerance for each pipeline and check both orientations. |
| Ten-minute take and pressure | Measure frame cadence, A/V sync, heat, and battery while recording. Exercise low storage, critical heat, memory pressure, calls, Home, and mic disconnect. Verify any recovered file. |
| Native controls | Compare requested and reported ISO, shutter, focus, AWB, locks, EV, and stabilization. Verify unsupported controls remain visible and accurately labeled. |
| UI and compatibility | Rotate during preview and capture. Test camera and microphone denial, 200% font scale, TalkBack, and switching cameras. Check every Pro panel remains reachable on the budget phone. |
