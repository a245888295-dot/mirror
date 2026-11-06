// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.input

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
    private const val HIDRAW_PATH = "/dev/hidraw0"
    private const val HELPER_NAME = "libhidraw_sender.so"
    private const val NINTENDO_VID = "057E"

    private var process: Process? = null
    private var output: OutputStream? = null
    private var available = false
    private var initialised = false

    @Synchronized
    fun initialize() {
        if (initialised) return
        initialised = true
        if (!isNintendoControllerPresent()) return
        available = startHelper()
    }

    private fun isNintendoControllerPresent(): Boolean {
        val uevent = File("/sys/class/hidraw/hidraw0/device/uevent")
        if (!uevent.exists()) return false
        return try {
            uevent.readLines().any { line ->
                line.startsWith("HID_ID=") && line.contains(NINTENDO_VID, ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun startHelper(): Boolean {
        val helper = File(YuzuApplication.appContext.applicationInfo.nativeLibraryDir, HELPER_NAME)
        if (!helper.exists()) return false
        return try {
            val proc = ProcessBuilder("su", "-c", "${helper.absolutePath} $HIDRAW_PATH")
                .redirectErrorStream(true)
                .start()
            process = proc
            output = proc.outputStream
            true
        } catch (_: Exception) {
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
        } catch (_: Exception) {
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
        initialised = false
    }
}
