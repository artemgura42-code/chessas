package app.chessvision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.hypot

class CaptureService : Service() {
    companion object { @Volatile var running = false }

    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: ArrowView? = null
    private var eng: Engine? = null
    private val ui = Handler(Looper.getMainLooper())
    private val vision = BoardVision()
    private var last: Bitmap? = null
    private var board: Rect? = null
    private var prevSig = ByteArray(0); private var doneSig = ByteArray(0); private var stable = 0
    private var prev: CharArray? = null; private var turn = 'w'
    private var w = 0; private var h = 0; private var warned = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        i ?: return START_NOT_STICKY
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("cv", "Chess Vision", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "cv").setContentTitle("Chess Vision").setContentText("Захват экрана включён").setSmallIcon(android.R.drawable.ic_menu_view).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics(); wm.defaultDisplay.getRealMetrics(dm); w = dm.widthPixels; h = dm.heightPixels

        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(i.getIntExtra("code", 0), i.getParcelableExtra<Intent>("data")!!)
        p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, ui)
        proj = p
        val rd = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2); reader = rd
        vd = p.createVirtualDisplay("cv", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, null)

        val ov = ArrowView(this); overlay = ov
        val lp = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(ov, lp)

        running = true
        Thread { while (running) { try { step() } catch (e: Exception) { Log.e("CV", "step", e) }; Thread.sleep(250) } }.start()
        return START_NOT_STICKY
    }

    /** latest screen frame (waits up to [wait] ms for a new one; frames only arrive when the screen changes) */
    private fun frame(wait: Long): Bitmap? {
        val t = System.currentTimeMillis()
        do {
            val img = reader?.acquireLatestImage()
            if (img != null) {
                val pl = img.planes[0]
                val raw = Bitmap.createBitmap(pl.rowStride / pl.pixelStride, h, Bitmap.Config.ARGB_8888)
                raw.copyPixelsFromBuffer(pl.buffer); img.close()
                val bmp = Bitmap.createBitmap(raw, 0, 0, w, h); raw.recycle()
                val old = last; last = bmp; old?.recycle(); return bmp
            }
            Thread.sleep(20)
        } while (System.currentTimeMillis() - t < wait)
        return null
    }

    private fun step() {
        val bmp = frame(0) ?: last ?: return
        val r = board ?: vision.find(bmp)?.also { board = it } ?: return
        if (!vision.still(bmp, r)) { board = null; stable = 0; show(null, r); return }   // left the game screen

        if (!vision.ready) {                       // wait for the starting position, learn the pieces
            if (vision.learn(bmp, r)) { prev = vision.read(bmp, r); turn = 'w' }
            return
        }
        val sig = vision.signature(bmp, r)
        if (sig.contentEquals(prevSig)) stable++ else { prevSig = sig; stable = 0 }
        if (stable < 2 || sig.contentEquals(doneSig)) return      // position still moving, or already handled

        show(null, r); Thread.sleep(180)                           // hide our arrow so it doesn't pollute recognition
        val g = vision.read(frame(400) ?: last ?: return, r)
        track(g)
        var mv: String? = null
        if (turn == (if (vision.flipped) 'b' else 'w')) {
            mv = try { (eng ?: Engine(this).also { eng = it }).best(vision.fen(g, turn)) }
            catch (e: Exception) {
                if (!warned) { warned = true; ui.post { Toast.makeText(this, "Нет движка: положите libstockfish.so в jniLibs", Toast.LENGTH_LONG).show() } }
                null
            }
        }
        show(mv, r); Thread.sleep(180)
        doneSig = vision.signature(frame(400) ?: last ?: return, r); prevSig = doneSig; stable = 0
    }

    /** whose move is it: whoever did NOT just put a piece on a new square */
    private fun track(g: CharArray) {
        val p = prev; prev = g; p ?: return
        var wn = false; var bn = false
        for (i in 0..63) if (g[i] != p[i] && g[i] != ' ') { if (g[i].isUpperCase()) wn = true else bn = true }
        if (wn != bn) turn = if (wn) 'b' else 'w'
    }

    private fun center(sq: String, r: Rect): Pair<Float, Float> {
        val file = sq[0] - 'a'; val rank = sq[1] - '1'; val q = r.width() / 8f
        val col = if (vision.flipped) 7 - file else file; val row = if (vision.flipped) rank else 7 - rank
        return Pair(r.left + (col + .5f) * q, r.top + (row + .5f) * q)
    }

    private fun show(m: String?, r: Rect) {
        val a = if (m != null) { val (x1, y1) = center(m.substring(0, 2), r); val (x2, y2) = center(m.substring(2, 4), r); floatArrayOf(x1, y1, x2, y2) } else null
        val q = r.width() / 8f
        ui.post { overlay?.set(a, q) }
    }

    override fun onDestroy() {
        running = false
        overlay?.let { try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        vd?.release(); reader?.close(); proj?.stop(); eng?.close()
        super.onDestroy()
    }
}

class ArrowView(c: Context) : View(c) {
    private var a: FloatArray? = null; private var q = 0f
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC15781B.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC15781B.toInt() }

    fun set(a: FloatArray?, q: Float) { this.a = a; this.q = q; invalidate() }

    override fun onDraw(c: Canvas) {
        val a = a ?: return
        val dx = a[2] - a[0]; val dy = a[3] - a[1]; val l = hypot(dx, dy); if (l < 1f) return
        val ux = dx / l; val uy = dy / l; val hd = q * .45f
        val ex = a[2] - ux * hd; val ey = a[3] - uy * hd
        line.strokeWidth = q * .17f
        c.drawLine(a[0], a[1], ex, ey, line)
        val head = Path().apply {
            moveTo(a[2], a[3]); lineTo(ex - uy * hd * .6f, ey + ux * hd * .6f); lineTo(ex + uy * hd * .6f, ey - ux * hd * .6f); close()
        }
        c.drawPath(head, fill)
    }
}
