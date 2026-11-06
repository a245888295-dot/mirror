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
 * a small helper bundled in the APK is started through `su`, it locates the
 * controller's hidraw node itself, and we stream the reports to its stdin.
 */
object HdRumbleHidraw {
    private const val TAG = "HdRumbleHidraw"
    private const val HELPER_NAME = "libhidraw_sender.so"
    private const val TMP_HELPER = "/data/local/tmp/hidraw_sender"
    private const val NINTENDO_VID = 0x057E
    private const val RETRY_INTERVAL_MS = 5000L

    private var process: Process? = null
    private var output: OutputStream? = null
    private var lastAttempt = 0L

    @Volatile
    private var available = false

    /**
     * @param vendorId InputDevice vendor of the controller. Only Nintendo
     *                 devices are handled; everything else keeps using the
     *                 Android vibrator.
     */
    @Synchronized
    fun initialize(vendorId: Int) {
        if (available) return
        if (vendorId != NINTENDO_VID) return
        val now = System.currentTimeMillis()
        if (now - lastAttempt < RETRY_INTERVAL_MS) return
        lastAttempt = now

        available = startHelper()
        Log.i(TAG, "initialize: available=$available")
    }

    private fun startHelper(): Boolean {
        val helper = File(YuzuApplication.appContext.applicationInfo.nativeLibraryDir, HELPER_NAME)
        if (!helper.exists()) {
            Log.w(TAG, "helper missing at ${helper.absolutePath}")
            return false
        }

        // SELinux forbids executing binaries inside the app's nativeLibraryDir,
        // so stage a copy in /data/local/tmp (shell_data_file) and exec that.
        val command = "cp '${helper.absolutePath}' $TMP_HELPER && chmod 755 $TMP_HELPER && " +
            "exec $TMP_HELPER"

        val started = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Log.w(TAG, "su unavailable: $e")
            return false
        }

        process = started
        output = started.outputStream
        startLogForwarder(started)
        return true
    }

    /** Surfaces helper diagnostics in logcat and notices when it goes away. */
    private fun startLogForwarder(started: Process) {
        Thread {
            try {
                started.inputStream.bufferedReader().forEachLine { line ->
                    Log.i(TAG, "helper: $line")
                }
            } catch (e: Exception) {
                Log.w(TAG, "helper output closed: $e")
            }

            synchronized(this) {
                if (process === started) {
                    process = null
                    output = null
                    available = false
                }
            }
            Log.i(TAG, "helper exited")
        }.apply {
            name = "HdRumbleHidraw-log"
            isDaemon = true
            start()
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
