package com.ylports.deltaandroid

import android.content.Context
import android.graphics.*
import android.media.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.max

/** Native frames, real skin assets and one owner thread for core execution. */
class EmulatorView(context: Context, val system: String = "gbc") : View(context) {
    companion object {
        const val B = 1 shl 0
        const val SELECT = 1 shl 2
        const val START = 1 shl 3
        const val UP = 1 shl 4
        const val DOWN = 1 shl 5
        const val LEFT = 1 shl 6
        const val RIGHT = 1 shl 7
        const val A = 1 shl 8
        const val L = 1 shl 10
        const val R = 1 shl 11
        fun mapPhysicalKey(key: Int): Int = when (key) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> UP
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
            android.view.KeyEvent.KEYCODE_BUTTON_A, android.view.KeyEvent.KEYCODE_Z -> A
            android.view.KeyEvent.KEYCODE_BUTTON_B, android.view.KeyEvent.KEYCODE_X -> B
            android.view.KeyEvent.KEYCODE_BUTTON_L1 -> L
            android.view.KeyEvent.KEYCODE_BUTTON_R1 -> R
            android.view.KeyEvent.KEYCODE_BUTTON_START, android.view.KeyEvent.KEYCODE_ENTER -> START
            android.view.KeyEvent.KEYCODE_BUTTON_SELECT, android.view.KeyEvent.KEYCODE_BACKSLASH -> SELECT
            else -> 0
        }
    }
    private var bitmap = Bitmap.createBitmap(160, 144, Bitmap.Config.ARGB_8888)
    private var frameWidth = 160
    private var frameHeight = 144
    private var framePixels = IntArray(160 * 144) { Color.BLACK }
    private val frameLock = Any()
    private val imagePaint = Paint().apply { isFilterBitmap = false }
    private val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val touchMask = AtomicInteger(0)
    private val keyMask = AtomicInteger(0)
    private val axisMask = AtomicInteger(0)
    private val heldMask = AtomicInteger(0)
    private val prefs = context.getSharedPreferences("delta-ios", Context.MODE_PRIVATE)
    private var skin: SkinPresentation? = null
    var onSkinMenu: (() -> Unit)? = null
    var onFailure: ((String) -> Unit)? = null
    var onHoldSelection: (() -> Unit)? = null
    var onQuickSave: (() -> Unit)? = null
    var onQuickLoad: (() -> Unit)? = null
    private var gesturePointer = -1
    private var gestureX = 0f
    private var gestureY = 0f
    private var lastGestureTap = 0L
    private var menuGestureConsumed = false
    private val gestureHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val quickLoadTask = Runnable {
        if (menuWasPressed && gesturePointer >= 0 && !menuGestureConsumed) {
            menuGestureConsumed = true; onQuickLoad?.invoke()
        }
    }
    var selectingHeldButtons = false
    @Volatile var fastForward = false
    @Volatile private var running = false
    val isRunning get() = running
    private var worker: Thread? = null
    private var menuWasPressed = false

    fun refreshSkin() {
        val old = skin
        skin = try { SkinPackage.load(context, width > height, system) }
            catch (e: Exception) { onFailure?.invoke(e.message ?: "Error de skin"); null }
        old?.artwork?.recycle()
        invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshSkin()
    }
    fun pressKey(mask: Int, pressed: Boolean) {
        if (pressed) keyMask.getAndUpdate { it or mask }
        else keyMask.getAndUpdate { it and mask.inv() }
    }
    fun setAxes(x: Float, y: Float) {
        axisMask.set((if (x < -0.4f) LEFT else if (x > 0.4f) RIGHT else 0) or
            (if (y < -0.4f) UP else if (y > 0.4f) DOWN else 0))
    }
    fun clearHeldButtons() { heldMask.set(0); invalidate() }
    fun hasHeldButtons() = heldMask.get() != 0
    fun start() {
        if (running || worker?.isAlive == true || selectingHeldButtons) return
        running = true
        worker = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val workPixels = IntArray(1024 * 1024)
            val sound = ShortArray(8192)
            val sampleRate = EmulatorBridge.sampleRate().coerceIn(8000, 192000)
            val frameNs = (1_000_000_000.0 / EmulatorBridge.frameRate()).toLong()
            val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT)
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            var track: AudioTrack? = null
            try {
                track = AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(max(8192, minBytes * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                track.play()
            } catch (e: Exception) {
                track?.release(); track = null
                post { android.widget.Toast.makeText(context, "Audio: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
            }
            try {
                var due = System.nanoTime()
                val queue = ShortArray(sampleRate) // bounded to 0.5s stereo
                var queued = 0
                var previousFastForward = fastForward
                var lastVolumeCheck = 0L
                var previousVolume = -1f
                fun drainAudio() {
                    if (queued == 0 || track == null) return
                    val written = track.write(queue, 0, queued, AudioTrack.WRITE_NON_BLOCKING)
                    check(written >= 0) { "AudioTrack rechazó los datos ($written)." }
                    if (written > 0) {
                        System.arraycopy(queue, written, queue, 0, queued - written)
                        queued -= written
                    }
                }
                while (running) {
                    if (previousFastForward != fastForward) {
                        track?.pause(); track?.flush(); track?.play()
                        queued = 0; previousFastForward = fastForward
                        due = System.nanoTime()
                    }
                    val length = EmulatorBridge.step(workPixels, sound,
                        touchMask.get() or keyMask.get() or axisMask.get() or heldMask.get())
                    check(length >= 0) { "El núcleo no pudo producir un frame válido." }
                    val dimensions = EmulatorBridge.videoSize()
                    val w = dimensions ushr 16
                    val h = dimensions and 0xffff
                    synchronized(frameLock) {
                        if (w != frameWidth || h != frameHeight) {
                            frameWidth = w; frameHeight = h
                            framePixels = IntArray(w * h)
                        }
                        System.arraycopy(workPixels, 0, framePixels, 0, w * h)
                    }
                    postInvalidateOnAnimation()
                    val now = System.nanoTime()
                    if (now - lastVolumeCheck > 250_000_000) {
                        val volume = if (prefs.getBoolean("respectSilentMode", true) &&
                            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode != AudioManager.RINGER_MODE_NORMAL) 0f else 1f
                        if (volume != previousVolume) track?.setVolume(volume)
                        previousVolume = volume; lastVolumeCheck = now
                    }
                    if (!fastForward && track != null && length > 0) {
                        drainAudio()
                        while (running && queued + length > queue.size) {
                            LockSupport.parkNanos(1_000_000)
                            drainAudio()
                        }
                        if (!running) break
                        System.arraycopy(sound, 0, queue, queued, length)
                        queued += length
                        drainAudio()
                    }
                    val speed = if (fastForward) 2 else 1
                    due += frameNs / speed
                    while (running) {
                        val remaining = due - System.nanoTime()
                        if (remaining <= 0) break
                        LockSupport.parkNanos(minOf(remaining, 2_000_000))
                        drainAudio()
                    }
                    if (System.nanoTime() - due > frameNs * 3) due = System.nanoTime()
                }
            } catch (e: Exception) {
                post { onFailure?.invoke(e.message ?: "Error de emulación") }
            } finally {
                try { track?.pause(); track?.flush() } catch (_: Exception) { }
                track?.release()
                running = false
            }
        }, "Delta-$system").apply { start() }
    }
    fun stop() {
        running = false
        worker?.let { t ->
            if (t !== Thread.currentThread()) {
                LockSupport.unpark(t)
                try { t.join() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
        worker = null
        touchMask.set(0); keyMask.set(0); axisMask.set(0)
        menuWasPressed = false
        gestureHandler.removeCallbacks(quickLoadTask); gesturePointer = -1
    }
    fun screenshot(): Bitmap = synchronized(frameLock) {
        Bitmap.createBitmap(framePixels, frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
    }
    private fun appFrame(available: RectF): RectF =
        SkinPresentation.fit(frameWidth.toFloat(), frameHeight.toFloat(), available)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        synchronized(frameLock) {
            if (bitmap.width != frameWidth || bitmap.height != frameHeight) {
                bitmap.recycle()
                bitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
            }
            bitmap.setPixels(framePixels, 0, frameWidth, 0, 0, frameWidth, frameHeight)
        }
        val current = skin
        if (current == null) {
            canvas.drawBitmap(bitmap, null, appFrame(RectF(0f, 0f, width.toFloat(), height.toFloat())), imagePaint)
            return
        }
        val geometry = current.geometry(width.toFloat(), height.toFloat())
        val app = appFrame(geometry.available)
        val screens = current.screens.ifEmpty { listOf(SkinScreen(null, null, true)) }
        for (screen in screens) {
            val input = screen.input
            val source = if (input == null) Rect(0, 0, frameWidth, frameHeight) else Rect(
                input.left.toInt().coerceIn(0, frameWidth), input.top.toInt().coerceIn(0, frameHeight),
                input.right.toInt().coerceIn(0, frameWidth), input.bottom.toInt().coerceIn(0, frameHeight))
            if (source.isEmpty) continue
            val bounds = if (screen.appPlacement) app else geometry.controller
            val out = screen.output
            val destination = if (out == null) SkinPresentation.fit(source.width().toFloat(), source.height().toFloat(),
                if (screen.appPlacement) geometry.available else bounds)
            else {
                val sx = if (screen.appPlacement) bounds.width() else bounds.width() / current.width
                val sy = if (screen.appPlacement) bounds.height() else bounds.height() / current.height
                RectF(bounds.left + out.left * sx, bounds.top + out.top * sy,
                    bounds.left + out.right * sx, bounds.top + out.bottom * sy)
            }
            canvas.drawBitmap(bitmap, source, destination, imagePaint)
        }
        current.artwork?.let {
            // Delta applies the opacity preference only to translucent representations.
            skinPaint.alpha = if (current.translucent) (prefs.getInt("controllerOpacity", 70) * 255 / 100) else 255
            canvas.drawBitmap(it, null, geometry.controller, skinPaint)
            skinPaint.alpha = 255
        }
    }
    private fun buttonForInput(input: String) = when (input.lowercase()) {
        "a" -> A; "b" -> B; "l" -> L; "r" -> R; "select" -> SELECT; "start" -> START
        "up" -> UP; "down" -> DOWN; "left" -> LEFT; "right" -> RIGHT
        else -> 0
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val current = skin ?: return true
        val geometry = current.geometry(width.toFloat(), height.toFloat())
        val app = appFrame(geometry.available)
        var mask = 0
        var menuPressed = false
        if (action != MotionEvent.ACTION_CANCEL && action != MotionEvent.ACTION_UP) {
            for (index in 0 until event.pointerCount) {
                if (action == MotionEvent.ACTION_POINTER_UP && index == event.actionIndex) continue
                for (input in current.inputAt(event.getX(index), event.getY(index), geometry, app)) {
                    mask = mask or buttonForInput(input)
                    if (input == "menu") menuPressed = true
                }
            }
        }
        if ((mask and touchMask.get().inv()) != 0 && prefs.getBoolean("buttonHaptics", true))
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        touchMask.set(mask)
        if (selectingHeldButtons && mask != 0) heldMask.getAndUpdate { it or mask }
        val wasMenuPressed = menuWasPressed
        if (menuPressed && !wasMenuPressed) menuGestureConsumed = false
        // The original quick gestures require holding Menu while using a second
        // finger: horizontal swipe toggles FF, double-tap saves, long press loads.
        if (prefs.getBoolean("menuGestures",true) && !selectingHeldButtons) {
            if (action == MotionEvent.ACTION_POINTER_DOWN && menuPressed) {
                val i = event.actionIndex
                if ("menu" !in current.inputAt(event.getX(i),event.getY(i),geometry,app)) {
                    gesturePointer = event.getPointerId(i); gestureX = event.getX(i); gestureY = event.getY(i)
                    gestureHandler.postDelayed(quickLoadTask,500)
                }
            }
            val pointer = event.findPointerIndex(gesturePointer)
            if (pointer >= 0 && action == MotionEvent.ACTION_MOVE && !menuGestureConsumed) {
                val dx = event.getX(pointer)-gestureX
                val dy = event.getY(pointer)-gestureY
                if (kotlin.math.abs(dx) > resources.displayMetrics.density*48 && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    fastForward = !fastForward; menuGestureConsumed = true
                    gestureHandler.removeCallbacks(quickLoadTask)
                }
            }
            if (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) == gesturePointer) {
                gestureHandler.removeCallbacks(quickLoadTask)
                if (!menuGestureConsumed) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now-lastGestureTap < 300) { menuGestureConsumed = true; lastGestureTap = 0; onQuickSave?.invoke() }
                    else lastGestureTap = now
                }
                gesturePointer = -1
            }
        }
        menuWasPressed = menuPressed
        if (wasMenuPressed && !menuPressed) {
            gestureHandler.removeCallbacks(quickLoadTask); gesturePointer = -1
            if (action != MotionEvent.ACTION_CANCEL && !menuGestureConsumed) {
                if (selectingHeldButtons) { selectingHeldButtons = false; onHoldSelection?.invoke() }
                else onSkinMenu?.invoke()
            }
        }
        if (action == MotionEvent.ACTION_UP) performClick()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
