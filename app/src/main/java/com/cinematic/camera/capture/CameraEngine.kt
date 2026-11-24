@file:androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class, androidx.camera.video.ExperimentalMimeTypeApi::class)

package com.cinematic.camera.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics as Keys
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaCodecList
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.AudioStats
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.cinematic.camera.color.ColorProcessor
import com.cinematic.camera.color.Grade
import com.cinematic.camera.color.toJson
import com.cinematic.camera.media.Clips
import com.cinematic.camera.media.PhotoGrade
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

enum class Pipeline(val label: String) {
    CLEAN("LOOK PREVIEW / CLEAN FILE"),
    BAKED("LOOK BAKED"),
    DIRECT("DIRECT / CLEAN FILE"),
}

enum class VideoProfile(val label: String, val mime: String, val range: DynamicRange) {
    AVC("H.264 · SDR", "video/avc", DynamicRange.SDR),
    HEVC("H.265 · SDR", "video/hevc", DynamicRange.SDR),
    HLG("H.265 · HLG10", "video/hevc", DynamicRange.HLG_10_BIT);
    val hdr get() = this == HLG
}

enum class Take { IDLE, STARTING, RECORDING, STOPPING, VERIFYING }

data class Lens(val id: String, val label: String, val facing: Int)
data class Format(val quality: Quality, val width: Int, val height: Int, val rates: List<Int>) {
    val label: String get() = "${height}p"
}
data class Sensor(
    val iso: Int? = null, val shutter: Long? = null, val focus: Float? = null,
    val awb: Int? = null, val aeLocked: Boolean = false, val af: Int? = null,
    val ev: Int? = null, val eis: Boolean? = null, val awbLocked: Boolean = false,
    val antibanding: Int? = null,
    val aeState: Int? = null,
)
data class Controls(
    val iso: Range<Int>? = null, val shutter: Range<Long>? = null,
    val focus: Float = 0f, val ev: Range<Int>? = null, val evStep: Float = 0f,
    val aeLock: Boolean = false, val wbLock: Boolean = false, val whiteBalance: List<Int> = emptyList(),
    val eis: Boolean = false, val zoom: ClosedFloatingPointRange<Float> = 1f..1f,
    val level: Int? = null, val physicalIds: Set<String> = emptySet(),
    val flash: Boolean = false, val antibandingModes: List<Int> = emptyList(),
    val rawPhoto: Boolean = false,
)
data class Manual(
    val iso: Int? = null, val shutter: Long? = null, val focus: Float? = null,
    val wb: Int? = null, val aeLock: Boolean = false, val wbLock: Boolean = false,
    val antibanding: Int? = null,
)
data class CaptureState(
    val lenses: List<Lens> = emptyList(), val lens: Lens? = null,
    val formats: List<Format> = emptyList(), val format: Format? = null,
    val profile: VideoProfile = VideoProfile.AVC,
    val profileFormats: Map<VideoProfile, List<Format>> = emptyMap(),
    val fps: Int? = null, val bitrate: Int = 12, val pipeline: Pipeline = Pipeline.DIRECT,
    val stabilization: Boolean = false, val lite: Boolean = false, val audio: Boolean = true,
    val ready: Boolean = false, val gpuFailed: Boolean = false, val busy: Take = Take.IDLE,
    val duration: Long = 0, val amplitude: Double? = null, val bytes: Long = 0,
    val thermal: Int = 0, val message: String? = null, val warning: String? = null,
    val controls: Controls = Controls(), val sensor: Sensor = Sensor(), val manual: Manual = Manual(),
    val zoom: Float = 1f, val clipRevision: Int = 0,
    val ev: Int = 0, val audioIssue: Boolean = false,
    val torch: Boolean = false,
    val photoMode: Boolean = false, val rawPhoto: Boolean = false,
    val bracketSpacing: Float = 0f,
    val shot: ShotInfo = ShotInfo(), val gyroAvailable: Boolean = false, val gyroEnabled: Boolean = false,
    val recovering: Boolean = false, val pendingRecovery: Int = 0,
) {
    val active: Boolean get() = busy != Take.IDLE
    val timecode: String get() {
        val seconds = duration / 1_000_000_000
        return "%02d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
    val remainingMinutes: Long get() = ((bytes - 96L * 1024 * 1024).coerceAtLeast(0) * 8 /
        ((bitrate * 1_000_000L + if (audio) 256_000 else 0) * 60))
}

class CameraEngine(context: Context, private val owner: LifecycleOwner) {
    private val app = context.applicationContext
    private val main = ContextCompat.getMainExecutor(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val production = app.getSharedPreferences("production", Context.MODE_PRIVATE)
    private val gyro = GyroRecorder(app)
    private val stateFlow = MutableStateFlow(CaptureState(
        shot = runCatching { ShotInfo.from(JSONObject(production.getString("shot", "{}") ?: "{}")) }.getOrDefault(ShotInfo()),
        gyroAvailable = gyro.available,
        gyroEnabled = gyro.available && production.getBoolean("gyro", false),
        recovering = true,
    ))
    val state = stateFlow.asStateFlow()
    private val scopeFrames = MutableStateFlow<ByteArray?>(null)
    val scopeSamples = scopeFrames.asStateFlow()
    private var scopes = false
    private var autoLookSamples = false
    val clips = Clips(app)
    private var view: PreviewView? = null
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var video: VideoCapture<Recorder>? = null
    private var photo: ImageCapture? = null
    private var recording: Recording? = null
    private var cancelStart = false
    private var cancelPhoto = false
    private var recoveryRunning = false
    private var processor: ColorProcessor? = null
    private var binding = 0
    private var disposed = false
    private var previewPaused = false
    private var lensRevision = 0
    private var lastRequest = 0
    private var grade = Grade()
    private var compare = false
    private var split = false
    private val power = app.getSystemService(PowerManager::class.java)
    private val monitor = scope.launch {
        while (true) {
            val bytes = withContext(Dispatchers.IO) { freeBytes() }
            val heat = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else 0
            val warning = when {
                bytes < stopBytes -> "Storage is too low to continue recording."
                heat >= 4 -> "Device is too hot to continue recording."
                bytes < 250L * 1024 * 1024 -> "Storage is low."
                heat >= 3 -> "Device is hot. Consider Lite preview or direct capture on the next take."
                else -> null
            }
            stateFlow.update { it.copy(bytes = bytes, thermal = heat, warning = warning) }
            if (bytes < stopBytes || heat >= 4) stop()
            delay(1500)
        }
    }

    init { recoverRecordings() }

    fun updateShot(value: ShotInfo) {
        if (state.value.active) return
        val shot = value.sanitized()
        production.edit().putString("shot", shot.json().toString()).apply()
        stateFlow.update { it.copy(shot = shot) }
    }

    fun enableGyro(enabled: Boolean) {
        if (state.value.active || !gyro.available) return
        production.edit().putBoolean("gyro", enabled).apply()
        stateFlow.update { it.copy(gyroEnabled = enabled) }
    }

    fun recoverRecordings() {
        if (state.value.active || recoveryRunning) return
        recoveryRunning = true
        stateFlow.update { it.copy(recovering = true) }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { clips.recoverInterrupted() } }
            val recovered = result.getOrNull() ?: 0
            val unresolved = withContext(Dispatchers.IO) { clips.pendingCount() }
            recoveryRunning = false
            stateFlow.update { it.copy(recovering = false, pendingRecovery = unresolved,
                clipRevision = it.clipRevision + if (recovered > 0) 1 else 0,
                message = when {
                    recovered > 0 -> "Recovered $recovered interrupted recording(s)."
                    result.isFailure -> "Recording recovery could not finish."
                    else -> it.message
                }) }
        }
    }

    fun attach(target: PreviewView) {
        if (disposed || view === target) return
        view = target
        val existing = provider
        if (existing != null) {
            bind()
            return
        }
        val future = ProcessCameraProvider.getInstance(app)
        future.addListener({
            if (disposed) return@addListener
            runCatching {
                provider = future.get()
                val lenses = provider!!.availableCameraInfos.map { info ->
                    val facing = info.lensFacing
                    val id = Camera2CameraInfo.from(info).cameraId
                    val direction = when (facing) {
                        CameraSelector.LENS_FACING_BACK -> "Rear"
                        CameraSelector.LENS_FACING_FRONT -> "Front"
                        else -> "External"
                    }
                    Lens(id, "$direction · $id", facing)
                }.sortedBy { it.facing != CameraSelector.LENS_FACING_BACK }
                stateFlow.update { it.copy(lenses = lenses) }
                val first = lenses.firstOrNull()
                if (first == null) tell("Android reports no usable camera.")
                else selectLens(first, initial = true)
            }.onFailure { problem("Camera initialization failed", it) }
        }, main)
    }

    fun selectLens(lens: Lens, initial: Boolean = false) {
        if (state.value.active || (!initial && lens == state.value.lens)) return
        val info = provider?.availableCameraInfos?.firstOrNull { Camera2CameraInfo.from(it).cameraId == lens.id }
            ?: return
        val revision = ++lensRevision
        stateFlow.update { it.copy(ready = false) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                VideoProfile.entries.associateWith { profile -> runCatching { formats(info, profile) }.getOrDefault(emptyList()) }
                    .filterValues { it.isNotEmpty() } to if (initial) clips.lastVerifiedFormat(lens.id) else null
            } }
                .onSuccess { (formats, verified) ->
                    if (!disposed && revision == lensRevision) installLens(lens, info, formats, verified, initial)
                }.onFailure {
                    if (!disposed && revision == lensRevision) problem("Could not query this camera's video profiles", it)
                }
        }
    }

    private fun installLens(lens: Lens, info: CameraInfo, profiles: Map<VideoProfile, List<Format>>,
                            verified: JSONObject?, initial: Boolean) {
        val oldProfile = state.value.profile
        val profile = oldProfile.takeIf { it in profiles } ?: VideoProfile.AVC.takeIf { it in profiles }
            ?: profiles.keys.firstOrNull() ?: VideoProfile.AVC
        val formats = profiles[profile].orEmpty()
        val format = verified?.optString("requestedResolution")?.let { label -> formats.firstOrNull { it.label == label } }
            ?: formats.firstOrNull { it.quality == state.value.format?.quality } ?: formats.firstOrNull()
        val previousRate = verified?.optInt("requestedFps")?.takeIf { it > 0 } ?: state.value.fps
        val fps = previousRate?.takeIf { it in format?.rates.orEmpty() }
            ?: format?.rates?.firstOrNull { it == 30 }
        val bitrate = verified?.optInt("targetBitrateMbps")?.takeIf { it in 4..32 } ?: state.value.bitrate
        val old = state.value
        val changes = buildList {
            if (!initial && format?.quality != old.format?.quality) add("resolution")
            if (!initial && fps != old.fps) add("frame rate")
            if (!initial && old.stabilization) add("stabilization")
            if (!initial && oldProfile != profile) add("codec / range")
        }
        stateFlow.update {
            it.copy(lens = lens, formats = formats, format = format, fps = fps, bitrate = bitrate, ready = false,
                profile = profile, profileFormats = profiles,
                pipeline = if (profile.hdr) Pipeline.DIRECT else it.pipeline,
                controls = capabilities(info), rawPhoto = false, manual = Manual(), sensor = Sensor(), stabilization = false,
                ev = 0, zoom = 1f, message = if (changes.isEmpty()) null else "Lens switch changed ${changes.joinToString()} for the next take.")
        }
        bind()
    }

    fun flip() {
        val current = state.value.lens
        val next = state.value.lenses.firstOrNull { it.facing != current?.facing }
            ?: state.value.lenses.firstOrNull { it.id != current?.id }
        if (next != null) selectLens(next) else tell("Android exposes one camera.")
    }

    fun photoMode(enabled: Boolean) {
        val current = state.value
        if (current.active || current.photoMode == enabled) return
        stateFlow.update { it.copy(photoMode = enabled, rawPhoto = false, pipeline = Pipeline.DIRECT,
            manual = Manual(), ev = 0, ready = false, message = null) }
        bind()
    }

    fun photoBracket(spacing: Float) {
        val current = state.value
        if (!current.photoMode || current.active || spacing !in listOf(0f, .7f, 1f, 2f)) return
        stateFlow.update { it.copy(bracketSpacing = spacing) }
    }

    fun rawPhoto(enabled: Boolean) {
        val current = state.value
        if (!current.photoMode || current.active || (enabled && !current.controls.rawPhoto) ||
            current.rawPhoto == enabled) return
        stateFlow.update { it.copy(rawPhoto = enabled, ready = false, message = null) }
        bind()
    }

    fun selectVideoProfile(profile: VideoProfile) {
        val current = state.value
        if (current.active || current.photoMode || current.profile == profile) return
        val choices = current.profileFormats[profile].orEmpty()
        if (choices.isEmpty()) { tell("This lens does not advertise ${profile.label}."); return }
        val format = choices.firstOrNull { it.quality == current.format?.quality } ?: choices.first()
        val rate = current.fps?.takeIf { it in format.rates }
        stateFlow.update { it.copy(profile = profile, formats = choices, format = format, fps = rate,
            pipeline = if (profile.hdr) Pipeline.DIRECT else it.pipeline,
            stabilization = false, manual = Manual(), ev = 0, zoom = 1f, ready = false,
            message = "Selected ${profile.label}." + (if (profile.hdr) " SDR looks disabled." else "") +
                (if (current.fps != rate) " Frame rate changed to Auto." else "")) }
        bind()
    }

    fun configure(format: Format? = state.value.format, fps: Int? = state.value.fps,
                  bitrate: Int = state.value.bitrate, pipeline: Pipeline = state.value.pipeline,
                  stabilization: Boolean = state.value.stabilization) {
        val old = state.value
        if (old.profile.hdr && pipeline != Pipeline.DIRECT) {
            tell("Switch to SDR before using the SDR look pipeline.")
            return
        }
        if (old.active || old.photoMode || (fps != null && fps !in format?.rates.orEmpty()) ||
            (format != null && format !in old.formats) || bitrate !in 4..32 ||
            (stabilization && !old.controls.eis)) return
        if (format == old.format && fps == old.fps && bitrate == old.bitrate &&
            pipeline == old.pipeline && stabilization == old.stabilization && old.ready) return
        stateFlow.update { it.copy(format = format, fps = fps, bitrate = bitrate, pipeline = pipeline,
            stabilization = stabilization, manual = Manual(), ev = 0, zoom = 1f,
            message = null, gpuFailed = false) }
        bind()
    }

    fun setAudio(enabled: Boolean) {
        if (!state.value.active) stateFlow.update { it.copy(audio = enabled) }
    }

    fun scopes(enabled: Boolean) {
        scopes = enabled
        processor?.scopeEnabled = enabled
        if (!enabled && !autoLookSamples) scopeFrames.value = null
    }

    fun autoLookSampling(enabled: Boolean) {
        autoLookSamples = enabled
        processor?.autoSampleEnabled = enabled
        if (!enabled && !scopes) scopeFrames.value = null
    }

    fun setLite(enabled: Boolean) {
        if (state.value.active) return
        stateFlow.update { it.copy(lite = enabled) }
        processor?.lite = enabled
    }

    fun render(value: Grade, comparing: Boolean = false, splitView: Boolean = false) {
        if (state.value.active) return
        grade = value
        compare = comparing
        split = splitView
        processor?.apply { grade = value; compare = comparing; split = splitView }
    }

    fun orientation(rotation: Int) {
        if (state.value.active) return
        preview?.targetRotation = rotation
        video?.targetRotation = rotation
        photo?.targetRotation = rotation
    }

    fun pausePreview(paused: Boolean) {
        if (previewPaused == paused || disposed || state.value.active) return
        previewPaused = paused
        if (paused) {
            binding++
            camera?.cameraInfo?.cameraState?.removeObservers(owner)
            camera?.cameraInfo?.torchState?.removeObservers(owner)
            provider?.unbindAll()
            processor?.close()
            processor = null
            camera = null
            video = null
            photo = null
            preview = null
            stateFlow.update { it.copy(ready = false, torch = false) }
        } else bind()
    }

    private fun bind() {
        if (previewPaused) return
        val provider = provider ?: return
        val view = view ?: return
        val lens = state.value.lens ?: return
        val generation = ++binding
        scopeFrames.value = null
        camera?.cameraInfo?.cameraState?.removeObservers(owner)
        camera?.cameraInfo?.torchState?.removeObservers(owner)
        provider.unbindAll()
        processor?.close()
        processor = null
        camera = null
        video = null
        photo = null
        preview = null
        stateFlow.update { it.copy(ready = false, sensor = Sensor(), gpuFailed = false, torch = false) }
        val configuration = state.value
        val selector = CameraSelector.Builder().addCameraFilter { candidates ->
            candidates.filter { Camera2CameraInfo.from(it).cameraId == lens.id }
        }.build()
        try {
            val rotation = view.display?.rotation ?: Surface.ROTATION_0
            val previewBuilder = Preview.Builder().setTargetRotation(rotation)
            if (!configuration.photoMode) previewBuilder.setDynamicRange(configuration.profile.range)
            configuration.format?.let { format ->
                val aspect = if (format.width * 9 == format.height * 16)
                    AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                val previewSize = if (aspect == AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    Size(1280, 720) else Size(960, 720)
                previewBuilder.setResolutionSelector(ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspect)
                    .setResolutionStrategy(ResolutionStrategy(previewSize,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build())
            }
            var restored = false
            val callback = object : CameraCaptureSession.CaptureCallback() {
                private var last = 0L
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - last < 200) return
                    last = now
                    val sample = Sensor(result.get(CaptureResult.SENSOR_SENSITIVITY),
                        result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                        result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                        result.get(CaptureResult.CONTROL_AWB_MODE),
                        result.get(CaptureResult.CONTROL_AE_LOCK) == true,
                        result.get(CaptureResult.CONTROL_AF_STATE),
                        result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION),
                        result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)?.let { it != 0 },
                        result.get(CaptureResult.CONTROL_AWB_LOCK) == true,
                        result.get(CaptureResult.CONTROL_AE_ANTIBANDING_MODE),
                        result.get(CaptureResult.CONTROL_AE_STATE))
                    main.execute {
                        if (!disposed && binding == generation &&
                            owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                            stateFlow.update { it.copy(sensor = sample, ready = (video != null || photo != null) && !it.gpuFailed) }
                            if (!restored) {
                                restored = true
                                if (configuration.manual != Manual()) manual(configuration.manual)
                                if (configuration.zoom != 1f) zoom(configuration.zoom)
                                if (configuration.ev != 0) ev(configuration.ev)
                            }
                        }
                    }
                }
            }
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(callback)
            val surface = previewBuilder.build().also { it.surfaceProvider = view.surfaceProvider }
            val group = UseCaseGroup.Builder().addUseCase(surface)
            val still = if (configuration.photoMode) ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(rotation)
                .setOutputFormat(if (configuration.rawPhoto) ImageCapture.OUTPUT_FORMAT_RAW else ImageCapture.OUTPUT_FORMAT_JPEG)
                .build().also { group.addUseCase(it) } else null
            val capture = configuration.format?.takeUnless { configuration.photoMode }?.let { format ->
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(format.quality))
                    .setVideoMimeType(configuration.profile.mime)
                    .setTargetVideoEncodingBitRate(configuration.bitrate * 1_000_000).build()
                VideoCapture.Builder(recorder).setDynamicRange(configuration.profile.range)
                    .setTargetRotation(rotation).setVideoStabilizationEnabled(configuration.stabilization)
                    .apply { configuration.fps?.let { setTargetFrameRate(Range(it, it)) } }.build()
                    .also { group.addUseCase(it) }
            }
            if (!configuration.photoMode && configuration.pipeline != Pipeline.DIRECT) {
                val effect = ColorProcessor(configuration.pipeline == Pipeline.BAKED, samples = { frame ->
                    main.execute { if (binding == generation && (scopes || autoLookSamples)) scopeFrames.value = frame }
                }) { error ->
                    if (binding == generation && !disposed) {
                        stateFlow.update { it.copy(gpuFailed = true, ready = false) }
                        problem("Color pipeline failed. Choose direct capture or retry", error)
                        stop()
                    }
                }
                effect.grade = grade
                effect.compare = compare
                effect.split = split
                effect.lite = configuration.lite
                effect.scopeEnabled = scopes
                effect.autoSampleEnabled = autoLookSamples
                processor = effect
                group.addEffect(effect.effect)
            }
            val bound = provider.bindToLifecycle(owner, selector, group.build())
            camera = bound
            preview = surface
            video = capture
            photo = still
            bound.cameraInfo.torchState.observe(owner) { value ->
                if (binding == generation) stateFlow.update { it.copy(torch = value == TorchState.ON) }
            }
            bound.cameraInfo.cameraState.observe(owner) { result ->
                if (binding == generation) {
                    result.error?.let { problem("Camera session error ${it.code}", it.cause) }
                    if (result.type != androidx.camera.core.CameraState.Type.OPEN || result.error != null) {
                        stateFlow.update { it.copy(ready = false) }
                    }
                }
            }
        } catch (error: Exception) {
            processor?.close()
            processor = null
            problem("This camera configuration did not bind. Try direct capture or a lower resolution", error)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(selectedGrade: Grade) {
        val current = state.value
        if (current.recovering) { tell("Checking interrupted recordings."); return }
        if (current.photoMode) { capturePhoto(selectedGrade); return }
        val capture = video ?: return
        if (!current.ready || current.active) return
        if (current.bytes < stopBytes || current.thermal >= 4) {
            tell("Recording is blocked by storage or device temperature.")
            return
        }
        if (current.audio && !permission(Manifest.permission.RECORD_AUDIO)) {
            tell("Allow microphone access or turn audio off in Settings.")
            return
        }
        if (Build.VERSION.SDK_INT < 29 && !permission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            tell("Storage permission is required to save this take.")
            return
        }
        grade = selectedGrade
        processor?.apply { grade = selectedGrade; compare = false; split = false }
        val title = "CAM_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$title.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Cinematic Camera")
        }
        val options = MediaStoreOutputOptions.Builder(app.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
        val metadata = JSONObject().apply {
            put("cameraId", current.lens?.id)
            put("pipeline", current.pipeline.name)
            put("requestedResolution", current.format?.label)
            put("requestedFps", current.fps ?: "camera automatic")
            put("targetBitrateMbps", current.bitrate)
            put("audioRequested", current.audio)
            put("stabilizationRequested", current.stabilization)
            put("isoAtStartRequest", current.sensor.iso)
            put("exposureNsAtStartRequest", current.sensor.shutter)
            put("focusDioptersAtStartRequest", current.sensor.focus)
            put("awbModeAtStartRequest", current.sensor.awb)
            put("dynamicRangeRequested", if (current.profile.hdr) "HLG10" else "SDR")
            put("videoMimeRequested", current.profile.mime)
            put("shot", current.shot.json())
            put("gyroRequested", current.gyroEnabled)
            if (current.pipeline != Pipeline.DIRECT) put("look", selectedGrade.toJson())
        }
        stateFlow.update { it.copy(busy = Take.STARTING, duration = 0, amplitude = null,
            message = null, audioIssue = false) }
        cancelStart = false
        scope.launch {
        try {
            withContext(Dispatchers.IO) {
                selectedGrade.look.cube?.takeIf { current.pipeline != Pipeline.DIRECT }?.let { cube ->
                    metadata.put("cube", buildString { cube.writeTo(this) })
                }
                clips.beginJournal(title, metadata)
            }
            if (cancelStart || disposed || !owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                withContext(Dispatchers.IO) { clips.finishJournal(title) }
                stateFlow.update { it.copy(busy = Take.IDLE, message = "Recording start cancelled.") }
                return@launch
            }
            val pending = capture.output.prepareRecording(app, options)
            if (current.audio) pending.withAudioEnabled()
            recording = pending.start(main) { event ->
                val stats = event.recordingStats
                val activeAudio = stats.audioStats.audioState == AudioStats.AUDIO_STATE_ACTIVE
                stateFlow.update {
                    it.copy(duration = stats.recordedDurationNanos,
                        amplitude = stats.audioStats.audioAmplitude.takeIf { activeAudio && it.isFinite() && it >= 0 },
                        audioIssue = it.audioIssue || (current.audio && stats.audioStats.hasError()))
                }
                when (event) {
                    is VideoRecordEvent.Start -> {
                        if (current.gyroEnabled && state.value.busy == Take.STARTING) {
                            if (!gyro.start(title)) metadata.put("gyroError", "Could not start gyroscope.")
                        }
                        stateFlow.update { if (it.busy == Take.STARTING) it.copy(busy = Take.RECORDING) else it }
                        val nextShot = current.shot.copy(take = (current.shot.take + 1).coerceAtMost(9999))
                        production.edit().putString("shot", nextShot.json().toString()).apply()
                        stateFlow.update { it.copy(shot = nextShot) }
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        gyro.stop()?.let { metadata.put("gyro", it) }
                        metadata.put("recorderError", event.error)
                        metadata.put("audioIssue", state.value.audioIssue)
                        metadata.put("recordedBytes", stats.numBytesRecorded)
                        if (event.hasError()) Log.w("CinematicCamera", "Recorder finalized with ${event.error}", event.cause)
                        stateFlow.update { it.copy(busy = Take.VERIFYING, amplitude = null) }
                        val uri = event.outputResults.outputUri
                        scope.launch {
                            val message = withContext(Dispatchers.IO) {
                                if (uri == android.net.Uri.EMPTY) "No video was written (recorder status ${event.error})."
                                else runCatching {
                                    val measured = runCatching { clips.inspect(uri) }.onFailure {
                                        Log.w("CinematicCamera", "Could not inspect $uri", it)
                                    }.getOrNull()
                                    val differences = if (measured == null) emptyList() else buildList {
                                        if (measured.optString("videoMime") != current.profile.mime) add("codec")
                                        val transfer = measured.optInt(android.media.MediaFormat.KEY_COLOR_TRANSFER, -1)
                                        if (current.profile.hdr && transfer != android.media.MediaFormat.COLOR_TRANSFER_HLG) add("HLG transfer")
                                        if (!current.profile.hdr && transfer in listOf(android.media.MediaFormat.COLOR_TRANSFER_HLG, android.media.MediaFormat.COLOR_TRANSFER_ST2084)) add("dynamic range")
                                        current.format?.let { format ->
                                            val width = measured.optInt("width")
                                            val height = measured.optInt("height")
                                            if (maxOf(width, height) != maxOf(format.width, format.height) ||
                                                minOf(width, height) != minOf(format.width, format.height)) add("resolution")
                                        }
                                        val fps = measured.optDouble("averageFps")
                                        if (current.fps != null && (!fps.isFinite() || kotlin.math.abs(fps - current.fps) > 1.0)) add("frame rate")
                                        if (current.audio && !measured.optBoolean("audioTrack")) add("audio")
                                    }
                                    metadata.put("formatDifferences", JSONArray(differences))
                                    clips.save(uri, title, metadata, measured)
                                    clips.finishJournal(title)
                                    when {
                                        measured == null -> "File retained. MP4 tracks could not be verified."
                                        event.hasError() -> "Recovered video after recorder status ${event.error}."
                                        differences.isNotEmpty() -> "Video saved. Measured output differs in ${differences.joinToString()}."
                                        state.value.audioIssue -> "Video saved. The microphone reported an error."
                                        else -> "Clip saved and MP4 tracks verified."
                                    }
                                }.getOrElse { "Video retained in Movies. Look manifest could not be saved." }
                            }
                            val unresolved = withContext(Dispatchers.IO) { clips.pendingCount() }
                            stateFlow.update { it.copy(busy = Take.IDLE, message = message, clipRevision = it.clipRevision + 1, pendingRecovery = unresolved) }
                            processor?.compare = compare
                            processor?.split = split
                        }
                    }
                }
            }
        } catch (error: Exception) {
            stateFlow.update { it.copy(busy = Take.IDLE) }
            processor?.compare = compare
            processor?.split = split
            val unresolved = withContext(Dispatchers.IO) { clips.pendingCount() }
            stateFlow.update { it.copy(pendingRecovery = unresolved) }
            problem("Could not start recording", error)
        }
        }
    }

    private fun capturePhoto(selectedGrade: Grade) {
        val current = state.value
        val capture = photo ?: return
        val bound = camera ?: return
        if (current.active || !current.ready) return
        if (current.bytes < stopBytes || current.thermal >= 4) { tell("Photo capture is blocked by storage or temperature."); return }
        if (Build.VERSION.SDK_INT < 29 && !permission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            tell("Storage permission is required to save a photo."); return
        }
        val range = current.controls.ev
        val bracket = current.bracketSpacing > 0f
        val indices = if (!bracket) listOf(current.ev) else if (range != null &&
            current.manual.iso == null && !current.manual.aeLock) {
            Bracket.indices(current.ev, current.controls.evStep, range.lower, range.upper, current.bracketSpacing)
        } else emptyList()
        if (indices.isEmpty()) { tell("Bracketing requires auto exposure and enough EV range on both sides."); return }
        val raw = current.rawPhoto
        val extension = if (raw) "dng" else "jpg"
        val mime = if (raw) "image/x-adobe-dng" else "image/jpeg"
        val batch = "PHOTO_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        cancelPhoto = false
        stateFlow.update { it.copy(busy = Take.STARTING, message = null) }
        scope.launch {
            val saved = mutableListOf<Pair<String, android.net.Uri>>()
            var copies = 0
            var failure: String? = null
            suspend fun exposure(index: Int) {
                withTimeout(2500L) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val future = bound.cameraControl.setExposureCompensationIndex(index)
                        future.addListener({
                            if (continuation.isActive) runCatching { future.get() }
                                .onSuccess { continuation.resume(Unit) }.onFailure { continuation.resumeWithException(it) }
                        }, main)
                    }
                    while (state.value.sensor.ev != index || state.value.sensor.aeState !in
                        listOf(CaptureResult.CONTROL_AE_STATE_CONVERGED, CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)) {
                        if (disposed || camera !== bound) error("Camera session changed.")
                        delay(100)
                    }
                }
                stateFlow.update { it.copy(ev = index) }
            }
            try {
                for ((number, index) in indices.withIndex()) {
                    if (cancelPhoto || disposed || camera !== bound || state.value.thermal >= 4 || state.value.bytes < stopBytes) {
                        failure = "Capture interrupted."; break
                    }
                    if (bracket) exposure(index)
                    if (cancelPhoto || disposed) { failure = "Capture interrupted."; break }
                    val title = "${batch}_${number + 1}.$extension"
                    val options = ImageCapture.OutputFileOptions.Builder(app.contentResolver,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, title)
                            put(MediaStore.Images.Media.MIME_TYPE, mime)
                            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cinematic Camera")
                        }).build()
                    val uri = withTimeout(30_000L) {
                        suspendCancellableCoroutine<android.net.Uri> { continuation ->
                            capture.takePicture(options, main, object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    if (!continuation.isActive) return
                                    val uri = output.savedUri
                                    if (uri != null) continuation.resume(uri)
                                    else continuation.resumeWithException(IllegalStateException("No photo URI."))
                                }
                                override fun onError(exception: ImageCaptureException) {
                                    if (continuation.isActive) continuation.resumeWithException(exception)
                                }
                            })
                        }
                    }
                    saved += title to uri
                }
            } catch (error: Exception) {
                Log.w("CinematicCamera", "Still capture failed", error)
                failure = "Capture interrupted or exposure could not be confirmed."
            } finally {
                if (bracket && !disposed && camera === bound) {
                    runCatching { exposure(current.ev) }.onFailure {
                        failure = "Could not confirm original EV restored; check exposure."
                    }
                }
            }
            if (!raw && selectedGrade.look.cube != null && selectedGrade.strength > 0f && saved.isNotEmpty()) {
                stateFlow.update { it.copy(busy = Take.VERIFYING, message = "Rendering separate JPEG look copies…") }
                for ((title, uri) in saved) {
                    if (cancelPhoto || disposed || state.value.thermal >= 4) break
                    runCatching { withContext(Dispatchers.IO) { PhotoGrade.export(app, uri, title, selectedGrade) } }
                        .onSuccess { copies++ }.onFailure { failure = "Original JPEG retained; a look copy failed." }
                }
            }
            val message = "${saved.size} original photo(s) saved" + if (copies > 0) " · $copies graded copy/copies" else ""
            stateFlow.update { it.copy(busy = Take.IDLE, message = listOfNotNull(message, failure).joinToString(". ")) }
        }
    }

    fun stop() {
        if (state.value.photoMode && state.value.active) cancelPhoto = true
        val running = recording ?: run {
            if (state.value.busy == Take.STARTING && !state.value.photoMode) cancelStart = true
            return
        }
        if (state.value.busy == Take.STOPPING) return
        stateFlow.update { it.copy(busy = Take.STOPPING) }
        running.stop()
    }

    fun focus(x: Float, y: Float, lock: Boolean) {
        if (state.value.photoMode && state.value.active) return
        val view = view ?: return
        val camera = camera ?: return
        if (state.value.manual.focus != null) {
            tell("Switch optical focus to Auto before using tap focus.")
            return
        }
        val point = view.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .apply {
                if (state.value.manual.iso == null && !state.value.manual.aeLock) addPoint(point, FocusMeteringAction.FLAG_AE)
                if (lock) disableAutoCancel()
            }.build()
        if (!camera.cameraInfo.isFocusMeteringSupported(action)) {
            tell("This camera does not expose focus or exposure metering at this point.")
            return
        }
        val future = camera.cameraControl.startFocusAndMetering(action)
        future.addListener({
            if (this.camera !== camera) return@addListener
            runCatching { future.get() }.onSuccess { result ->
                tell(if (result.isFocusSuccessful) if (lock) "Autofocus held. Use Release AF to restore continuous focus."
                    else "Focus acquired." else "Autofocus did not confirm a lock.")
            }.onFailure {
                if (it.cause !is androidx.camera.core.CameraControl.OperationCanceledException) problem("Metering failed", it)
            }
        }, main)
    }

    fun releaseFocus() { camera?.cameraControl?.cancelFocusAndMetering() }

    fun zoom(value: Float) {
        val camera = camera ?: return
        val ratio = value.coerceIn(state.value.controls.zoom)
        val future = camera.cameraControl.setZoomRatio(ratio)
        future.addListener({
            if (this.camera === camera) runCatching { future.get() }
                .onSuccess { stateFlow.update { it.copy(zoom = ratio) } }
        }, main)
    }

    fun ev(value: Int) {
        if (state.value.photoMode && state.value.active) return
        val camera = camera ?: return
        val control = state.value.controls.ev ?: return
        if (state.value.manual.iso != null || state.value.manual.aeLock) return
        val index = control.clamp(value)
        val future = camera.cameraControl.setExposureCompensationIndex(index)
        future.addListener({
            if (this.camera === camera) runCatching { future.get() }
                .onSuccess { stateFlow.update { it.copy(ev = index) } }
        }, main)
    }

    fun setTorch(enabled: Boolean) {
        val camera = camera ?: return
        val current = state.value
        if (!current.controls.flash || current.active || !current.ready) return
        if (enabled && current.thermal >= 3) {
            tell("Torch is disabled while the device is hot.")
            return
        }
        val future = camera.cameraControl.enableTorch(enabled)
        future.addListener({
            if (this.camera === camera) runCatching { future.get() }
                .onFailure { problem("Camera did not accept the torch request", it) }
        }, main)
    }

    fun manual(value: Manual) {
        if (state.value.photoMode && state.value.active) return
        val camera = camera ?: return
        val current = state.value
        val capabilities = current.controls
        val builder = CaptureRequestOptions.Builder()
        if (value.iso != null && value.shutter != null) {
            val iso = capabilities.iso?.clamp(value.iso) ?: return
            val range = capabilities.shutter ?: return
            val frame = current.fps?.takeUnless { current.photoMode }?.let { 1_000_000_000L / it }
            val shutter = range.clamp(value.shutter)
            if (frame != null && shutter > frame) {
                tell("Exposure time exceeds the selected frame interval.")
                return
            }
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            if (frame != null) builder.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, frame)
        } else if (value.aeLock && capabilities.aeLock) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        if (value.iso == null) value.antibanding?.takeIf { it in capabilities.antibandingModes }?.let {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, it)
        }
        value.focus?.takeIf { capabilities.focus > 0f }?.let {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it.coerceIn(0f, capabilities.focus))
        }
        value.wb?.takeIf { it in capabilities.whiteBalance }?.let {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, it)
        }
        if (value.wbLock && capabilities.wbLock) builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        val token = ++lastRequest
        val future = Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(builder.build())
        future.addListener({
            if (this.camera === camera && token == lastRequest) runCatching { future.get() }
                .onSuccess { stateFlow.update { it.copy(manual = value) } }
                .onFailure { problem("Camera did not accept the manual request", it) }
        }, main)
    }

    fun tell(message: String?) { stateFlow.update { it.copy(message = message) } }

    private fun problem(text: String, error: Throwable?) {
        Log.w("CinematicCamera", text, error)
        tell(text)
    }

    fun close() {
        disposed = true
        stop()
        monitor.cancel()
        camera?.cameraInfo?.cameraState?.removeObservers(owner)
        camera?.cameraInfo?.torchState?.removeObservers(owner)
        provider?.unbindAll()
        processor?.close()
        view = null
    }

    private fun permission(key: String) = ContextCompat.checkSelfPermission(app, key) == PackageManager.PERMISSION_GRANTED
    private fun freeBytes() = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }
        .getOrDefault(0L)

    private fun formats(info: CameraInfo, profile: VideoProfile): List<Format> {
        if (profile.hdr && Build.VERSION.SDK_INT < 33) return emptyList()
        if (profile.mime !in Recorder.getSupportedVideoMimeTypes()) return emptyList()
        val capabilities = Recorder.getVideoCapabilities(info, profile.mime) ?: return emptyList()
        if (profile.range !in capabilities.supportedDynamicRanges) return emptyList()
        val qualities = capabilities.getSupportedQualities(profile.range)
        val characteristics = Camera2CameraInfo.from(info)
        val streams = characteristics.getCameraCharacteristic(Keys.SCALER_STREAM_CONFIGURATION_MAP)
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
            .mapNotNull { runCatching { it.getCapabilitiesForType(profile.mime).videoCapabilities }.getOrNull() }
        return listOf(Quality.FHD, Quality.HD, Quality.SD, Quality.UHD).filter { it in qualities }
            .mapNotNull { quality ->
                val size = QualitySelector.getResolution(info, quality) ?: return@mapNotNull null
                val minDuration = runCatching { streams?.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, size) }
                    .getOrNull() ?: 0
                val rates = info.supportedFrameRateRanges.filter { it.lower == it.upper && it.upper in setOf(24, 25, 30, 50, 60) }
                    .map { it.upper }.filter { fps ->
                        (minDuration == 0L || fps <= (1e9 / minDuration).roundToInt()) &&
                            encoders.any { it.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) }
                    }.distinct().sorted()
                Format(quality, size.width, size.height, rates)
            }
    }

    private fun capabilities(info: CameraInfo): Controls {
        val camera2 = Camera2CameraInfo.from(info)
        val manualSensor = camera2.getCameraCharacteristic(Keys.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(Keys.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val zoom = info.zoomState.value
        val ev = info.exposureState
        return Controls(
            iso = if (manualSensor) camera2.getCameraCharacteristic(Keys.SENSOR_INFO_SENSITIVITY_RANGE) else null,
            shutter = if (manualSensor) camera2.getCameraCharacteristic(Keys.SENSOR_INFO_EXPOSURE_TIME_RANGE) else null,
            focus = camera2.getCameraCharacteristic(Keys.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            ev = ev.exposureCompensationRange.takeIf { ev.isExposureCompensationSupported },
            evStep = ev.exposureCompensationStep.toFloat(),
            aeLock = camera2.getCameraCharacteristic(Keys.CONTROL_AE_LOCK_AVAILABLE) == true,
            wbLock = camera2.getCameraCharacteristic(Keys.CONTROL_AWB_LOCK_AVAILABLE) == true,
            whiteBalance = camera2.getCameraCharacteristic(Keys.CONTROL_AWB_AVAILABLE_MODES)
                ?.filter { it in 2..8 }.orEmpty(),
            eis = Recorder.getVideoCapabilities(info).isStabilizationSupported,
            zoom = (zoom?.minZoomRatio ?: 1f)..(zoom?.maxZoomRatio ?: 1f),
            level = camera2.getCameraCharacteristic(Keys.INFO_SUPPORTED_HARDWARE_LEVEL),
            flash = info.hasFlashUnit(),
            rawPhoto = runCatching { ImageCapture.OUTPUT_FORMAT_RAW in ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats }.getOrDefault(false),
            antibandingModes = camera2.getCameraCharacteristic(Keys.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)?.toList().orEmpty(),
            physicalIds = if (Build.VERSION.SDK_INT >= 28) info.physicalCameraInfos
                .map { Camera2CameraInfo.from(it).cameraId }.toSet() else emptySet(),
        )
    }

    private companion object { const val stopBytes = 96L * 1024 * 1024 }
}
