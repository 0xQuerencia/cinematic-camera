package com.cinematic.camera

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cinematic.camera.capture.CameraEngine
import com.cinematic.camera.ui.CameraApp
import com.cinematic.camera.ui.InstrumentTheme

class MainActivity : ComponentActivity() {
    private lateinit var engine: CameraEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = CameraEngine(this, this)
        setContent { InstrumentTheme { CameraApp(engine) } }
    }

    override fun onStop() {
        engine.stop()
        super.onStop()
    }

    override fun onDestroy() {
        engine.close()
        super.onDestroy()
    }
}
