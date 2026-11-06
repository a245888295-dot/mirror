// SPDX-FileCopyrightText: 2024 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.features.input

import android.view.InputDevice
import androidx.annotation.Keep
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.utils.InputHandler.getGUID

@Keep
interface YuzuInputDevice {
    fun getName(): String

    fun getGUID(): String

    fun getPort(): Int

    fun getSupportsVibration(): Boolean

    fun vibrate(intensity: Float)

    fun vibrateHd(
        lowAmplitude: Float,
        lowFrequency: Float,
        highAmplitude: Float,
        highFrequency: Float
    ) {
        vibrate((lowAmplitude + highAmplitude) / 2f)
    }

    /**
     * Raw 0x10 HD rumble output report, for controllers that only accept
     * hidraw writes. The default falls back to the decoded amplitude.
     */
    fun vibrateHdRaw(data: ByteArray) {
        if (data.size < 10) return
        val high = (data[3].toInt() and 0xFF) / 100f
        val low = (data[5].toInt() and 0xFF) / 100f
        vibrate((high + low) / 2f)
    }

    fun getAxes(): Array<Int> = arrayOf()
    fun hasKeys(keys: IntArray): BooleanArray = BooleanArray(0)
}

class YuzuPhysicalDevice(
    private val device: InputDevice,
    private val port: Int,
    useSystemVibrator: Boolean
) : YuzuInputDevice {
    private val vibrator = if (useSystemVibrator) {
        YuzuVibrator.getSystemVibrator()
    } else {
        YuzuVibrator.getControllerVibrator(device)
    }

    init {
        HdRumbleHidraw.initialize()
    }

    override fun getName(): String {
        return device.name
    }

    override fun getGUID(): String {
        return device.getGUID()
    }

    override fun getPort(): Int {
        return port
    }

    override fun getSupportsVibration(): Boolean {
        return vibrator.supportsVibration()
    }

    override fun vibrate(intensity: Float) {
        vibrator.vibrate(intensity)
    }

    override fun vibrateHd(
        lowAmplitude: Float,
        lowFrequency: Float,
        highAmplitude: Float,
        highFrequency: Float
    ) {
        vibrator.vibrateHd(lowAmplitude, lowFrequency, highAmplitude, highFrequency)
    }

    override fun vibrateHdRaw(data: ByteArray) {
        if (HdRumbleHidraw.isAvailable() && HdRumbleHidraw.write(data)) {
            return
        }
        if (data.size < 10) return
        val high = (data[3].toInt() and 0xFF) / 100f
        val low = (data[5].toInt() and 0xFF) / 100f
        vibrator.vibrate((high + low) / 2f)
    }

    override fun getAxes(): Array<Int> = device.motionRanges.map { it.axis }.toTypedArray()
    override fun hasKeys(keys: IntArray): BooleanArray = device.hasKeys(*keys)
}

class YuzuInputOverlayDevice(
    private val vibration: Boolean,
    private val port: Int
) : YuzuInputDevice {
    private val vibrator = YuzuVibrator.getSystemVibrator()

    override fun getName(): String {
        return YuzuApplication.appContext.getString(R.string.input_overlay)
    }

    override fun getGUID(): String {
        return "00000000000000000000000000000000"
    }

    override fun getPort(): Int {
        return port
    }

    override fun getSupportsVibration(): Boolean {
        if (vibration) {
            return vibrator.supportsVibration()
        }
        return false
    }

    override fun vibrate(intensity: Float) {
        if (vibration) {
            vibrator.vibrate(intensity)
        }
    }

    override fun vibrateHd(
        lowAmplitude: Float,
        lowFrequency: Float,
        highAmplitude: Float,
        highFrequency: Float
    ) {
        if (vibration) {
            vibrator.vibrateHd(lowAmplitude, lowFrequency, highAmplitude, highFrequency)
        }
    }

    override fun vibrateHdRaw(data: ByteArray) {
        if (!vibration || data.size < 10) return
        val high = (data[3].toInt() and 0xFF) / 100f
        val low = (data[5].toInt() and 0xFF) / 100f
        vibrator.vibrate((high + low) / 2f)
    }
}
