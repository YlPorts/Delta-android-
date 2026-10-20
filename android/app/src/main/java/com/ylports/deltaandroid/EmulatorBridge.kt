package com.ylports.deltaandroid

/** JNI entry points for the native libretro host. All calls must be serialized. */
object EmulatorBridge {
    init { System.loadLibrary("delta_android") }

    /** Returns null if loading succeeds, otherwise an actionable error message. */
    external fun open(corePath: String, romPath: String, saveDirectory: String): String?
    /** One frame at native GB timing; returns number of interleaved stereo PCM shorts. */
    external fun step(frame: IntArray, audio: ShortArray, buttons: Int): Int
    external fun sampleRate(): Int
    /** Persist battery-backed SRAM before Android suspends the process. */
    external fun flushSave()
    external fun close()
}
