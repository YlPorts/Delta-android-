package com.ylports.deltaandroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * GB/GBC pixel-perfect canvas, multitouch gamepad and native emulation worker.
 * Non-GB systems remain unported; this is not a fake/placeholder emulator.
 */
class EmulatorView(context: Context) : View(context) {
    companion object {
        const val B = 1 shl 0
        const val SELECT = 1 shl 2
        const val START = 1 shl 3
        const val UP = 1 shl 4
        const val DOWN = 1 shl 5
        const val LEFT = 1 shl 6
        const val RIGHT = 1 shl 7
        const val A = 1 shl 8
        fun mapPhysicalKey(key: Int): Int = when (key) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> UP
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
            android.view.KeyEvent.KEYCODE_BUTTON_A, android.view.KeyEvent.KEYCODE_Z -> A
            android.view.KeyEvent.KEYCODE_BUTTON_B, android.view.KeyEvent.KEYCODE_X -> B
            android.view.KeyEvent.KEYCODE_BUTTON_START, android.view.KeyEvent.KEYCODE_ENTER -> START
            android.view.KeyEvent.KEYCODE_BUTTON_SELECT, android.view.KeyEvent.KEYCODE_BACKSLASH -> SELECT
            else -> 0
        }
    }

    private val bitmap = Bitmap.createBitmap(160, 144, Bitmap.Config.ARGB_8888)
    private val framePixels = IntArray(160 * 144) { Color.BLACK }
    private val frameLock = Any()
    private val imagePaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val controlsPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val touchMask = AtomicInteger(0)
    private var skin: SkinPresentation? = null
    var onSkinMenu: (() -> Unit)? = null

    fun refreshSkin() {
        skin = SkinPackage.load(context, width > height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshSkin()
    }

    private fun buttonForDeltaInput(name: String): Int = when (name.lowercase()) {
        "a" -> A
        "b" -> B
        "start" -> START
        "select" -> SELECT
        "up" -> UP
        "down" -> DOWN
        "left" -> LEFT
        "right" -> RIGHT
        else -> 0
    }

    private val keyMask = AtomicInteger(0)
    @Volatile private var running = false
    private var worker: Thread? = null

    fun pressKey(mask: Int, pressed: Boolean) {
        if (pressed) keyMask.getAndUpdate { it or mask }
        else keyMask.getAndUpdate { it and mask.inv() }
    }

    fun start() {
        if (running) return
        running = true
        worker = Thread({
            val workPixels = IntArray(160 * 144)
            val sound = ShortArray(8192)
            val sampleRate = EmulatorBridge.sampleRate().coerceIn(8000, 192000)
            val minBytes = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val audioBytes = max(16384, if (minBytes > 0) minBytes * 2 else 16384)
            var track: AudioTrack? = null
            try {
                track = AudioTrack(
                    AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, audioBytes, AudioTrack.MODE_STREAM
                )
                if (track.state == AudioTrack.STATE_INITIALIZED) track.play()
                else { track.release(); track = null }
            } catch (_: Exception) { track = null }
            try {
                var due = System.nanoTime()
                // Real GB/GBC refresh rate is approximately 59.7275 Hz.
                val frameNs = 16_742_706L
                while (running) {
                    val length = EmulatorBridge.step(
                        workPixels, sound, touchMask.get() or keyMask.get()
                    )
                    if (length < 0) break
                    synchronized(frameLock) {
                        System.arraycopy(workPixels, 0, framePixels, 0, workPixels.size)
                    }
                    postInvalidate()
                    if (track != null && length > 0) track.write(sound, 0, length)
                    due += frameNs
                    val delay = due - System.nanoTime()
                    if (delay > 0) {
                        SystemClock.sleep(delay / 1_000_000L)
                    } else if (delay < -frameNs * 2) due = System.nanoTime()
                }
            } finally {
                track?.let {
                    try { it.pause(); it.flush() } catch (_: Exception) {}
                    it.release()
                }
                running = false
            }
        }, "Delta-GB-Emulation").apply { start() }
    }

    fun stop() {
        running = false
        val t = worker
        if (t != null && t !== Thread.currentThread()) {
            try { t.join() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        worker = null
        touchMask.set(0)
        keyMask.set(0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(12, 15, 19))
        val w = width.toFloat()
        val h = height.toFloat()
        synchronized(frameLock) {
            bitmap.setPixels(framePixels, 0, 160, 0, 0, 160, 144)
        }

        val currentSkin = skin
        if (currentSkin != null) {
            val skinScale = min(w / currentSkin.width, h / currentSkin.height)
            val ox = (w - currentSkin.width * skinScale) / 2f
            val oy = (h - currentSkin.height * skinScale) / 2f
            val destinations = if (currentSkin.screens.isEmpty())
                listOf(SkinScreen(null, RectF(
                    currentSkin.width * 0.05f,
                    currentSkin.height * 0.08f,
                    currentSkin.width * 0.95f,
                    currentSkin.height * 0.55f
                ))) else currentSkin.screens
            for (screen in destinations) {
                val sourceRect = screen.input?.let {
                    Rect(it.left.toInt().coerceIn(0, 159),
                        it.top.toInt().coerceIn(0, 143),
                        it.right.toInt().coerceIn(1, 160),
                        it.bottom.toInt().coerceIn(1, 144))
                } ?: Rect(0, 0, 160, 144)
                if (sourceRect.isEmpty) continue
                val p = screen.output
                val dest = RectF(ox + p.left * skinScale, oy + p.top * skinScale,
                    ox + p.right * skinScale, oy + p.bottom * skinScale)
                // Render native game pixels, then the original skin artwork over the frame.
                canvas.drawBitmap(bitmap, sourceRect, dest, imagePaint)
            }
            controlsPaint.alpha = if (currentSkin.translucent) 210 else 255
            canvas.drawBitmap(currentSkin.artwork, null,
                RectF(ox, oy, ox + currentSkin.width * skinScale,
                    oy + currentSkin.height * skinScale), controlsPaint)
            controlsPaint.alpha = 255
            return
        }
        val maxVideoW = w * 0.96f
        val maxVideoH = h * 0.65f
        val scale = min(maxVideoW / 160f, maxVideoH / 144f)
        val left = (w - scale * 160) / 2f
        val top = max(8f, (maxVideoH - scale * 144) / 2f)
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + scale * 160, top + scale * 144), imagePaint)

        controlsPaint.textAlign = Paint.Align.CENTER
        controlsPaint.textSize = min(w, h) * 0.035f
        fun control(x: Float, y: Float, size: Float, label: String) {
            controlsPaint.color = Color.argb(96, 212, 217, 232)
            canvas.drawRoundRect(RectF(x - size, y - size, x + size, y + size),
                size * 0.38f, size * 0.38f, controlsPaint)
            controlsPaint.color = Color.WHITE
            canvas.drawText(label, x, y + controlsPaint.textSize * 0.32f, controlsPaint)
        }
        val d = min(w * 0.095f, h * 0.09f)
        val dx = w * 0.23f
        val dy = h * 0.83f
        control(dx, dy - d * 1.6f, d * 0.73f, "▲")
        control(dx, dy + d * 1.6f, d * 0.73f, "▼")
        control(dx - d * 1.6f, dy, d * 0.73f, "◀")
        control(dx + d * 1.6f, dy, d * 0.73f, "▶")
        control(w * 0.79f, h * 0.78f, d, "A")
        control(w * 0.66f, h * 0.88f, d, "B")
        control(w * 0.44f, h * 0.96f, d * 0.57f, "SEL")
        control(w * 0.56f, h * 0.96f, d * 0.57f, "START")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            touchMask.set(0)
            return true
        }
        var mask = 0
        val customSkin = skin
        if (customSkin != null) {
            val factor = min(width.toFloat() / customSkin.width,
                height.toFloat() / customSkin.height)
            val sx = (width - customSkin.width * factor) / 2f
            val sy = (height - customSkin.height * factor) / 2f
            for (index in 0 until event.pointerCount) {
                if (action == MotionEvent.ACTION_POINTER_UP && index == event.actionIndex) continue
                val x = (event.getX(index) - sx) / factor
                val y = (event.getY(index) - sy) / factor
                for (key in customSkin.inputAt(x, y)) {
                    mask = mask or buttonForDeltaInput(key)
                    if (key.equals("menu", ignoreCase = true) &&
                        (action == MotionEvent.ACTION_DOWN ||
                            action == MotionEvent.ACTION_POINTER_DOWN) &&
                        index == event.actionIndex) {
                        onSkinMenu?.invoke()
                    }
                }
            }
            touchMask.set(mask)
            return true
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val d = min(w * 0.095f, h * 0.09f)
        for (index in 0 until event.pointerCount) {
            if (action == MotionEvent.ACTION_POINTER_UP && index == event.actionIndex) continue
            val x = event.getX(index)
            val y = event.getY(index)
            val dx = x - w * 0.23f
            val dy = y - h * 0.83f
            if (kotlin.math.abs(dx) <= d * 2.5f && kotlin.math.abs(dy) <= d * 2.5f) {
                if (dx < -d * 0.45f) mask = mask or LEFT
                if (dx > d * 0.45f) mask = mask or RIGHT
                if (dy < -d * 0.45f) mask = mask or UP
                if (dy > d * 0.45f) mask = mask or DOWN
            }
            fun near(cx: Float, cy: Float, radius: Float) =
                kotlin.math.abs(x - cx) < radius && kotlin.math.abs(y - cy) < radius
            if (near(w * 0.79f, h * 0.78f, d * 1.1f)) mask = mask or A
            if (near(w * 0.66f, h * 0.88f, d * 1.1f)) mask = mask or B
            if (near(w * 0.44f, h * 0.96f, d * 0.7f)) mask = mask or SELECT
            if (near(w * 0.56f, h * 0.96f, d * 0.7f)) mask = mask or START
        }
        touchMask.set(mask)
        return true
    }
}
