package app.chessvision

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var btn: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM
            setBackgroundColor(Color.parseColor("#161512"))
            setPadding((24 * d).toInt(), 0, (24 * d).toInt(), (48 * d).toInt())
        }
        root.addView(TextView(this).apply {
            text = "Chess Vision"; textSize = 32f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, 0, 0, (24 * d).toInt())
        })
        btn = Button(this).apply { setOnClickListener { onTap() } }
        root.addView(btn)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        btn.text = if (CaptureService.running) "Остановить захват" else "Начать захват экрана"
    }

    private fun onTap() {
        if (CaptureService.running) { stopService(Intent(this, CaptureService::class.java)); onResume(); return }
        if (!Settings.canDrawOverlays(this)) {   // permission to draw arrows over Lichess / Chess.com
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 7)
    }

    @Deprecated("simple, no androidx needed")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 7 && res == RESULT_OK && data != null) {
            startForegroundService(Intent(this, CaptureService::class.java).putExtra("code", res).putExtra("data", data))
            moveTaskToBack(true)   // user goes straight to Lichess / Chess.com
        }
    }
}
