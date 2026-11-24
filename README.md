# Cinematic Camera

An offline, video-first Android camera. Simple and Pro share the same CameraX session. Pro shows camera-reported telemetry and keeps unsupported controls discoverable. The UI uses the Instrument palette, spacing, and typography from [the supplied design system](DESIGN.md).

## Build and install

Use JDK 17 or 21, Android SDK Platform 36, and Android SDK Build Tools. Set `ANDROID_HOME` or use Android Studio to generate `local.properties`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The package is `com.cinematic.camera`. The minimum OS is Android 8.0, API 26. Android 8–9 asks for shared-storage access when saving. The app uses CameraX 1.5.3, Compose Material 3, Camera2 interop, OpenGL ES 3, Media3 Transformer, and DataStore. The APK runs offline.

## Record and grade

1. Allow camera access to open the live viewfinder. Tap the record button and allow microphone access, or turn off **Record microphone** under **Settings → Microphone**.
2. Open **Output** to select a camera-reported resolution and a fixed FPS candidate, when available. The HUD displays the selected target. The clip library inspects the saved file and reports the measured FPS, codec, dimensions, and audio track. Manual ISO and shutter require a fixed frame rate and the Camera2 manual sensor capability.
3. Choose an output mode before recording. Direct uses unprocessed CameraX capture. Clean runs the grade on the preview and saves an ungraded clip with its look manifest. Baked passes graded frames to the encoder through CameraX. Look edits and output settings are locked while a take is active.
4. Open **Look Lab** for the live split, color controls, LUT strength, and film effects. Choose **Looks → Import** for a Rec.709 3D `.cube` or a versioned Cinematic Camera JSON. The app accepts cube grids from 2 through 33 on each axis. Confirm the LUT's expected source in its documentation before importing.
5. In **Media library**, open a clip to play or share it, export the manifest or its cube, or delete the video with confirmation. Use **Render graded copy** on a clean clip to apply its look with Media3 Transformer. It publishes a separate MP4 and keeps the original.
6. **Settings → Export device report** saves the current camera's capabilities and last sensor readouts. Files land in MediaStore under **Movies/Cinematic Camera** on Android 10 and later.

## Auto Look (experimental)

Tap **Auto Look** in Simple or Pro mode to recommend a cinematic look from the live viewfinder. The app uses a small locally sampled SDR frame to estimate brightness, color balance, and scene colorfulness; no frames are uploaded and no model download is required. This is a creative recommendation, not calibrated automatic scene detection.

Auto Look activates Clean preview if Direct was selected. It waits for three consistent scene samples and holds a recommendation for at least six seconds before changing to another look. The selected look and its strength remain **fixed while recording**. In Clean mode, the source clip retains its original colors with a look manifest; choose Baked before recording if you want the look in the saved video. Selecting a look, preset, or entering Look Lab disables Auto Look.

The original Rec.709 look catalog now includes 29 looks. Import external .cube files only when you know their expected input color space. On slower phones, turn off Auto Look, enable Lite preview, or select Direct capture if preview or recorded FPS is unstable.

## Precision grading

Look Lab now provides five-knot master and individual red, green and blue curves with three adjustable interior points. Curves are evaluated by the same shader in preview, baked recording and graded export. Skin-tone protection is a warm-hue chroma mask, not a person detector; it preserves some color from before the creative LUT and curves and can affect similarly colored objects. Set it to zero for intentional monochrome or stylized skin. Existing saved looks load with identity curves and protection off.

## Verification

```sh
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

Run Android tests on a dedicated test device. The capture smoke test grants the app camera and microphone permissions, records a short clip through the real UI, verifies video and audio samples, and deletes its own clip. The color integration test compares the OpenGL LUT against a CPU reference at 0%, 40%, and 100% strength and checks look and preset serialization. See [the verification report](VERIFICATION.md) for executed checks and remaining device tests.

## Android references

- [CameraX release notes](https://developer.android.com/jetpack/androidx/releases/camera)
- [CameraX CameraEffect targets](https://developer.android.com/reference/androidx/camera/core/CameraEffect)
- [CameraX video capture](https://developer.android.com/media/camera/camerax/video-capture)
- [Camera2 capabilities](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)
- [Media3 Transformer](https://developer.android.com/media/media3/transformer/getting-started)
