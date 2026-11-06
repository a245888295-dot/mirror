// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.input

import android.util.Log
import org.yuzu.yuzu_emu.YuzuApplication
import java.io.File
import java.io.OutputStream

/**
 * Drives a Nintendo controller's HD rumble actuators through /dev/hidraw.
 *
 * Android registers no force-feedback device for controllers handled by the
 * kernel's hid-nintendo driver, so the only way to reach their LRA motors is
 * to write 0x10 output reports to the hidraw node directly. That needs root:
 * a small helper bundled in the APK is started through `su` and we stream the
 * reports to its stdin.
 */
object HdRumbleHidraw {
    private const val TAG = "HdRumbleHidraw"
    private const val HIDRAW_PATH = "/dev/hidraw0"
    private const val HELPER_NAME = "libhidraw_sender.so"
    private const val NINTENDO_VID = "057E"
    private const val RETRY_INTERVAL_MS = 3000L

    private var process: Process? = null
    private var output: OutputStream? = null
    private var available = false
    private var lastAttempt = 0L

    @Synchronized
    fun initialize() {
        if (available) return
        val now = System.currentTimeMillis()
        if (now - lastAttempt < RETRY_INTERVAL_MS) return
        lastAttempt = now

        val present = isNintendoControllerPresent()
        Log.d(TAG, "initialize: nintendoController=$present")
        if (!present) return

        available = startHelper()
        Log.d(TAG, "initialize: helperStarted=$available")
    }

    private fun isNintendoControllerPresent(): Boolean {
        val uevent = File("/sys/class/hidraw/hidraw0/device/uevent")
        if (!uevent.exists()) return false
        return try {
            uevent.readLines().any { line ->
                line.startsWith("HID_ID=") && line.contains(NINTENDO_VID, ignoreCase = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "uevent read failed: $e")
            false
        }
    }

    private fun startHelper(): Boolean {
        val helper = File(YuzuApplication.appContext.applicationInfo.nativeLibraryDir, HELPER_NAME)
        Log.d(TAG, "helper path=${helper.absolutePath} exists=${helper.exists()}")
        if (!helper.exists()) return false
        return try {
            val proc = ProcessBuilder("su", "-c", "${helper.absolutePath} $HIDRAW_PATH")
                .redirectErrorStream(true)
                .start()
            process = proc
            output = proc.outputStream
            Log.d(TAG, "helper started, pid=${proc.pid()}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "helper start failed: $e")
            false
        }
    }

    fun isAvailable(): Boolean = available

    @Synchronized
    fun write(packet: ByteArray): Boolean {
        if (!available) return false
        val stream = output ?: return false
        return try {
            stream.write(packet)
            stream.flush()
            true
        } catch (e: Exception) {
            Log.w(TAG, "write failed: $e")
            available = false
            false
        }
    }

    @Synchronized
    fun shutdown() {
        try {
            output?.close()
        } catch (_: Exception) {
        }
        process?.destroy()
        process = null
        output = null
        available = false
        lastAttempt = 0L
    }
}
