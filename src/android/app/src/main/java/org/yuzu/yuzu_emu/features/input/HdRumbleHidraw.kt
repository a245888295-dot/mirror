// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.input

import android.util.Log
import org.yuzu.yuzu_emu.YuzuApplication
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    private const val READY_TIMEOUT_MS = 10_000L
    private const val IDLE_CHECK_MS = 100L
    private const val IDLE_STOP_MS = 300L

    /// Neutral 0x10 report: zero amplitude, 160 Hz / 320 Hz.
    private val STOP_PACKET = byteArrayOf(
        0x10, 0x00,
        0x00, 0x01, 0x40, 0x40, 0x00, 0x01, 0x40, 0x40
    )

    private var process: Process? = null
    private var output: OutputStream? = null
    private var lastAttempt = 0L
    private var lastWriteAt = 0L
    private var lastWasStop = true

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
        val staged = "cp '${helper.absolutePath}' $TMP_HELPER && chmod 755 $TMP_HELPER && " +
            "exec $TMP_HELPER"

        if (launch(listOf("su", "-c", staged), null)) {
            return true
        }

        // Some su builds hand out a root shell on stdin instead of taking -c.
        Log.i(TAG, "su -c gave no READY line, retrying with a root shell")
        return launch(listOf("su"), "$staged\n")
    }

    /**
     * Starts the helper and waits for it to report the node it opened.
     * [stdinCommand] is written to the child before any rumble data, for the
     * su variants that run whatever arrives on stdin.
     */
    private fun launch(argv: List<String>, stdinCommand: String?): Boolean {
        val started = try {
            ProcessBuilder(argv)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Log.w(TAG, "cannot run ${argv.first()}: $e")
            return false
        }

        val ready = CountDownLatch(1)
        Thread {
            try {
                started.inputStream.bufferedReader().forEachLine { line ->
                    Log.i(TAG, "helper: $line")
                    if (line.startsWith("READY")) {
                        ready.countDown()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "helper output closed: $e")
            }

            Log.i(TAG, "helper exited")
            synchronized(this@HdRumbleHidraw) {
                if (process === started) {
                    process = null
                    output = null
                    available = false
                }
            }
        }.apply {
            name = "HdRumbleHidraw-log"
            isDaemon = true
            start()
        }

        val stream = started.outputStream
        if (stdinCommand != null) {
            try {
                stream.write(stdinCommand.toByteArray())
                stream.flush()
            } catch (e: Exception) {
                Log.w(TAG, "cannot write the command to su: $e")
            }
        }

        val readySeen = try {
            ready.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "interrupted while waiting for the helper: $e")
            false
        }

        if (!readySeen) {
            Log.w(TAG, "helper not ready, command=${argv.drop(1).joinToString(" ")}")
            try {
                stream.close()
            } catch (_: Exception) {
            }
            started.destroy()
            return false
        }

        synchronized(this) {
            process = started
            output = stream
            available = true
            lastWriteAt = System.currentTimeMillis()
            lastWasStop = true
        }
        startIdleWatchdog(started)

        // Every write must be exactly one 10-byte output report: the helper
        // reads fixed-size frames, so anything else would shift the stream and
        // turn all later reports into garbage.
        return true
    }

    private fun isStopPacket(packet: ByteArray): Boolean =
        packet.size >= 6 &&
            packet[2] == 0.toByte() && packet[3] == 1.toByte() &&
            packet[4] == 0x40.toByte() && packet[5] == 0x40.toByte()

    /**
     * The controller latches the last report, so a game that stops sending while
     * a vibration is active would leave the motors running. Push a neutral report
     * once the stream has been quiet for a moment.
     */
    private fun startIdleWatchdog(started: Process) {
        Thread {
            while (true) {
                Thread.sleep(IDLE_CHECK_MS)
                var detached = false
                synchronized(this) {
                    if (process !== started || !available) {
                        detached = true
                    } else if (!lastWasStop &&
                        System.currentTimeMillis() - lastWriteAt > IDLE_STOP_MS
                    ) {
                        try {
                            output?.write(STOP_PACKET)
                            output?.flush()
                            lastWasStop = true
                        } catch (e: Exception) {
                            Log.w(TAG, "idle stop failed: $e")
                        }
                    }
                }
                if (detached) return@Thread
            }
        }.apply {
            name = "HdRumbleHidraw-idle"
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
            lastWriteAt = System.currentTimeMillis()
            lastWasStop = isStopPacket(packet)
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
