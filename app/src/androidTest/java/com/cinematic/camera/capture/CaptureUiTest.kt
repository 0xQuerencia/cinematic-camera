package com.cinematic.camera.capture

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cinematic.camera.media.Clips
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureUiTest {
    @Test fun recordsWithMicrophoneAndPublishesAnInspectableMp4() {
        val test = InstrumentationRegistry.getInstrumentation()
        val context = test.targetContext
        val app = context.packageName
        val automation = test.uiAutomation
        automation.grantRuntimePermission(app, android.Manifest.permission.CAMERA)
        automation.grantRuntimePermission(app, android.Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT < 29) {
            automation.grantRuntimePermission(app, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val clips = Clips(context)
        val before = clips.list().map { it.uri }.toSet()
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(app))
        context.startActivity(launch)
        val record = find("Record video", 20_000)
        assertTrue(record.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        find("Recording, stop", 15_000)
        SystemClock.sleep(2_000)
        assertTrue(find("Recording, stop", 5_000).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val until = SystemClock.elapsedRealtime() + 20_000
        var newClip = clips.list().firstOrNull { it.uri !in before }
        while (newClip == null && SystemClock.elapsedRealtime() < until) {
            SystemClock.sleep(200)
            newClip = clips.list().firstOrNull { it.uri !in before }
        }
        assertNotNull("A new clip manifest should be published", newClip)
        val captured = checkNotNull(newClip)
        try {
            val measured = clips.inspect(captured.uri)
            assertEquals("video/avc", measured.getString("videoMime"))
            assertTrue("Microphone audio samples are present", measured.getBoolean("audioTrack"))
            assertTrue(measured.getInt("videoSamples") > 10)
            assertTrue(measured.getInt("width") > 0 && measured.getInt("height") > 0)
            assertEquals("DIRECT", captured.details.getJSONObject("capture").getString("pipeline"))
        } finally {
            clips.delete(captured)
        }
    }

    private fun find(label: String, timeout: Long): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            val node = automation.rootInActiveWindow?.let { findEnabled(it, label) }
            if (node != null) return node
            SystemClock.sleep(200)
        }
        error("Could not find enabled camera control '$label'.")
    }

    private fun findEnabled(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if (node.isEnabled && node.isClickable && node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findEnabled(child, label)
            if (found != null) return found
        }
        return null
    }
}
