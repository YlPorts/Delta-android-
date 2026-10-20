package com.ylports.deltaandroid

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/** Integration tests run on a disposable CI AVD. No commercial ROM/BIOS. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NativePortTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private fun nodes(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
    private fun click(scenario: ActivityScenario<MainActivity>, label: String) {
        scenario.onActivity { activity ->
            val found = nodes(activity.window.decorView).firstOrNull {
                it.contentDescription?.toString() == label || (it is TextView && it.text.toString() == label)
            } ?: error("View missing: $label")
            var target = found
            while (!target.isClickable && target.parent is View) target = target.parent as View
            assertTrue("View is not clickable: $label",target.performClick())
        }
        instrument.waitForIdleSync()
    }
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrument.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
    private fun screenshot(name: String) {
        require(name.matches(Regex("[a-z0-9-]+")))
        instrument.waitForIdleSync()
        // Let native window/back transitions and the first blur shader settle.
        Thread.sleep(1200)
        // UTP may uninstall the tested APK before Gradle returns. Shell-owned
        // captures survive teardown and are pulled before the AVD shuts down.
        shell("mkdir -p /data/local/tmp/delta-screenshots")
        val path = "/data/local/tmp/delta-screenshots/$name.png"
        shell("screencap -p $path")
        assertTrue("Screenshot was not preserved", (shell("stat -c %s $path").trim().toLongOrNull() ?: 0) > 100)
    }
    private fun acknowledgeFullscreenNotice() {
        val until = android.os.SystemClock.uptimeMillis()+5000
        while (android.os.SystemClock.uptimeMillis() < until) {
            val button = instrument.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText("Got it")?.firstOrNull { it.isClickable }
            if (button != null) {
                assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                instrument.waitForIdleSync()
                Thread.sleep(250)
                return
            }
            Thread.sleep(100)
        }
    }
    private fun awaitPauseFrame(scenario: ActivityScenario<MainActivity>) {
        // Back dispatch and RenderThread completion are asynchronous. Waiting for
        // an idle message queue alone can capture the previous gameplay buffer.
        val until = android.os.SystemClock.uptimeMillis()+10000
        var displayed = false
        while (!displayed && android.os.SystemClock.uptimeMillis() < until) {
            scenario.onActivity { activity ->
                displayed = nodes(activity.window.decorView).any {
                    it is TextView && it.text.toString() == "Resume" && it.isShown
                }
            }
            if (!displayed) Thread.sleep(50)
        }
        assertTrue("Back did not present the pause menu",displayed)
        val committed = java.util.concurrent.CountDownLatch(1)
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            if (android.os.Build.VERSION.SDK_INT >= 29 && root.isHardwareAccelerated) {
                root.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                root.postInvalidateOnAnimation()
            } else root.postOnAnimation { root.postOnAnimation { committed.countDown() } }
        }
        assertTrue("Pause frame was not submitted",committed.await(10,java.util.concurrent.TimeUnit.SECONDS))
    }
    private fun fixture(extension: String): File {
        val file = File(context.filesDir,"test-fixtures/Delta Diagnostic.$extension")
        file.parentFile!!.mkdirs()
        instrument.context.assets.open("diagnostic.$extension").use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }
    @Test fun a_originalPaletteAndSkinGeometry() {
        assertEquals(Color.rgb(139,40,247),OriginalDeltaColors.purple)
        assertEquals(Color.rgb(214,188,255),OriginalDeltaColors.lightPurple)
        assertEquals(Color.rgb(38,38,38),OriginalDeltaColors.darkGray)
        for (system in listOf("gbc","nes","gba")) for (landscape in listOf(false,true)) {
            val skin = SkinPackage.load(context,landscape,system)
            assertNotNull(skin.artwork)
            assertTrue(skin.controls.any { "a" in it.inputs })
            val g = skin.geometry(if (landscape) 812f else 390f,if (landscape) 375f else 844f)
            if (!landscape && skin.screens.none { !it.appPlacement }) {
                assertEquals(844f,g.controller.bottom,.01f)
                assertTrue(g.available.height() > 0)
                assertEquals(g.available.bottom,g.controller.top,.01f)
            } else if (system == "gbc") assertTrue("Legacy gameScreenFrame missing",skin.screens.isNotEmpty())
            // An item overrides only its declared edge, inheriting all others.
            val a = skin.controls.first { "a" in it.inputs }
            val sx = g.controller.width()/skin.width
            val sy = g.controller.height()/skin.height
            val inputs = skin.inputAt(g.controller.left+a.frame.centerX()*sx,
                g.controller.top+a.frame.centerY()*sy,g,g.available)
            assertTrue(inputs.contains("a"))
            skin.artwork?.recycle()
        }
    }
    @Test fun a2_libraryPersistenceAndCorruptCatalogProtection() {
        val isolated=File(context.cacheDir,"catalog-test-${System.nanoTime()}").apply { mkdirs() }
        val wrapper=object : android.content.ContextWrapper(context) {
            override fun getFilesDir() = isolated
        }
        try {
            val library=GameLibrary(wrapper)
            val uri=Uri.fromFile(fixture("gb"))
            val first=library.importGame(uri)
            library.toggleFavorite(first.id)
            library.markPlayed(first.id)
            val reopened=GameLibrary(wrapper)
            assertTrue(reopened.games().single().favorite)
            assertTrue(reopened.games().single().lastPlayed > 0)
            assertEquals(first.id,reopened.importGame(uri).id)
            assertEquals(1,reopened.games().size)
            val renamed=File(isolated,"same-content.gbc").apply { writeBytes(first.file.readBytes()) }
            assertEquals(first.file,reopened.importGame(Uri.fromFile(renamed)).file)
            val catalog=File(isolated,"games/library.json")
            catalog.writeText("corrupt-catalog")
            assertTrue(reopened.games().isEmpty())
            assertNotNull(reopened.catalogReadFailure)
            assertTrue(runCatching { reopened.importGame(uri) }.isFailure)
            assertEquals("corrupt-catalog",catalog.readText())
            assertTrue(first.file.isFile)
        } finally { isolated.deleteRecursively() }
    }
    @Test fun b_emptyLibraryAndSettingsCaptures() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            screenshot("01-library-empty")
            click(scenario,"Settings")
            screenshot("02-settings")
            scenario.onActivity { activity ->
                val views = nodes(activity.window.decorView)
                assertTrue(views.any { it is TextView && it.text.toString() == "CONTROLLERS" })
                assertTrue(views.any { it is TextView && it.text.toString() == "Player 1" })
            }
            click(scenario,"Cancel")
        }
    }
    @Test fun c_realJniFramesAudioInputAndStates() {
        for ((extension,lib) in listOf("gb" to "libgambatte_libretro.so","nes" to "libnestopia_libretro.so","gba" to "libvbam_libretro.so")) {
            val rom = fixture(extension)
            val failure = EmulatorBridge.open(File(context.applicationInfo.nativeLibraryDir,lib).absolutePath,
                rom.absolutePath,context.filesDir.absolutePath)
            assertNull(failure)
            try {
                assertTrue(EmulatorBridge.frameRate() in 49.0..61.0)
                val pixels = IntArray(1024*1024)
                val sound = ShortArray(8192)
                var nonSilent = false
                for (i in 0..180) {
                    val count = EmulatorBridge.step(pixels,sound,EmulatorView.A)
                    assertTrue(count > 0)
                    if (sound.take(count).any { it.toInt() != 0 }) nonSilent = true
                }
                assertTrue("Core produced only silence",nonSilent)
                val dimensions = EmulatorBridge.videoSize()
                val count = (dimensions ushr 16)*(dimensions and 0xffff)
                assertTrue(pixels.take(count).distinct().size > 1)
                assertNull(EmulatorBridge.saveState(0))
                assertNull(EmulatorBridge.loadState(0))
                assertNull(EmulatorBridge.flushSave())
                assertTrue(File(rom.absolutePath+".srm").length() > 0)
            } finally { EmulatorBridge.close() }
        }
    }
    @Test fun d_libraryGamePauseRotationAndLifecycleCaptures() {
        val library = GameLibrary(context)
        val game = library.importGame(Uri.fromFile(fixture("gb")))
        library.importGame(Uri.fromFile(fixture("nes")))
        library.importGame(Uri.fromFile(fixture("gba")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            screenshot("03-library-games")
            // Select the GB console via the same native page control used by users.
            scenario.onActivity { activity ->
                nodes(activity.window.decorView).firstOrNull { it.contentDescription == "Game Boy Color" }?.performClick()
            }
            instrument.waitForIdleSync()
            click(scenario,"${game.title}, Game Boy Color")
            Thread.sleep(1000)
            acknowledgeFullscreenNotice()
            screenshot("04-game-portrait")
            instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            instrument.waitForIdleSync()
            awaitPauseFrame(scenario)
            screenshot("05-pause")
            scenario.onActivity { activity ->
                val labels=nodes(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                for (label in listOf("Resume","Main Menu","Save State","Load State","Cheat Codes","Fast Forward","Hold Buttons","Screenshot"))
                    assertTrue("Missing pause action $label",labels.contains(label))
                assertFalse(nodes(activity.window.decorView).filterIsInstance<EmulatorView>().single().isRunning)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertFalse("Game resumed behind pause",nodes(activity.window.decorView).filterIsInstance<EmulatorView>().single().isRunning)
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            Thread.sleep(1000)
            screenshot("06-pause-landscape")
            click(scenario,"Resume")
            Thread.sleep(500)
            screenshot("07-game-landscape")
            scenario.onActivity { activity ->
                val player=nodes(activity.window.decorView).filterIsInstance<EmulatorView>().single()
                assertTrue(player.width > player.height)
                assertTrue(player.isRunning)
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }
}
