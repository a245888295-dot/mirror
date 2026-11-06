// SPDX-FileCopyrightText: 2024 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.features.input

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import org.yuzu.yuzu_emu.YuzuApplication
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

@Keep
@Suppress("DEPRECATION")
interface YuzuVibrator {
    fun supportsVibration(): Boolean

    fun vibrate(intensity: Float)

    /**
     * The four channels a Switch HD rumble packet actually carries. The
     * default folds them back into the legacy single-intensity call so
     * callers that only know the old API keep working.
     */
    fun vibrateHd(
        lowAmplitude: Float,
        lowFrequency: Float,
        highAmplitude: Float,
        highFrequency: Float
    ) {
        vibrate((lowAmplitude + highAmplitude) / 2f)
    }

    companion object {
        fun getControllerVibrator(device: InputDevice): YuzuVibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                YuzuVibratorManager(device.vibratorManager)
            } else {
                YuzuVibratorManagerCompat(device.vibrator)
            }

        fun getSystemVibrator(): YuzuVibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = YuzuApplication.appContext
                    .getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                YuzuVibratorManager(vibratorManager)
            } else {
                val vibrator = YuzuApplication.appContext
                    .getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                YuzuVibratorManagerCompat(vibrator)
            }

        fun getVibrationEffect(intensity: Float): VibrationEffect? {
            if (intensity > 0f) {
                return VibrationEffect.createOneShot(
                    50,
                    (255.0 * intensity).toInt().coerceIn(1, 255)
                )
            }
            return null
        }

        /**
         * Perceptual shaping so quiet HD rumble detail (footsteps, water) is
         * not swallowed by the motor's dead zone.
         */
        fun shapeAmplitude(amplitude: Float): Float {
            val x = amplitude.coerceIn(0f, 1f)
            if (x < 0.01f) return 0f
            return x.pow(0.7f)
        }

        /**
         * Fallback for devices without haptic composition primitives: turn the
         * two HD bands into a short pulse train whose density follows the band
         * frequencies and whose envelope follows the amplitudes.
         */
        fun buildHdWaveform(
            low: Float,
            lowFrequency: Float,
            high: Float,
            highFrequency: Float
        ): VibrationEffect {
            val stepMs = 5L
            val steps = 12
            val timings = LongArray(steps) { stepMs }
            val amplitudes = IntArray(steps) { index ->
                val t = index * stepMs / 1000.0
                val lowWave = max(0.0, sin(2.0 * PI * lowFrequency * t)).toFloat()
                val highWave = max(0.0, sin(2.0 * PI * highFrequency * t)).toFloat()
                val amplitude = max(low * lowWave, high * highWave)
                (amplitude * 255f).toInt().coerceIn(0, 255)
            }
            return VibrationEffect.createWaveform(timings, amplitudes, -1)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
class YuzuVibratorManager(private val vibratorManager: VibratorManager) : YuzuVibrator {
    private val vibrator = vibratorManager.defaultVibrator

    private val supportsComposition: Boolean by lazy {
        vibrator.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            VibrationEffect.Composition.PRIMITIVE_TICK
        )
    }

    override fun supportsVibration(): Boolean {
        return vibratorManager.vibratorIds.isNotEmpty()
    }

    override fun vibrate(intensity: Float) {
        val vibration = YuzuVibrator.getVibrationEffect(intensity) ?: return
        vibratorManager.vibrate(CombinedVibration.createParallel(vibration))
    }

    override fun vibrateHd(
        lowAmplitude: Float,
        lowFrequency: Float,
        highAmplitude: Float,
        highFrequency: Float
    ) {
        val low = YuzuVibrator.shapeAmplitude(lowAmplitude)
        val high = YuzuVibrator.shapeAmplitude(highAmplitude)

        if (low <= 0f && high <= 0f) {
            vibrator.cancel()
            return
        }

        // Composition primitives are the closest stock approximation of the
        // Switch's two bands: LOW_TICK carries the weight, TICK the texture.
        if (supportsComposition) {
            val composition = VibrationEffect.startComposition()
            if (low > 0f) {
                composition.addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    low.coerceIn(0f, 1f)
                )
            }
            if (high > 0f) {
                composition.addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_TICK,
                    high.coerceIn(0f, 1f)
                )
            }
            vibrator.vibrate(composition.compose())
            return
        }

        vibrator.vibrate(
            YuzuVibrator.buildHdWaveform(low, lowFrequency, high, highFrequency)
        )
    }
}

class YuzuVibratorManagerCompat(private val vibrator: Vibrator) : YuzuVibrator {
    override fun supportsVibration(): Boolean {
        return vibrator.hasVibrator()
    }

    override fun vibrate(intensity: Float) {
        val vibration = YuzuVibrator.getVibrationEffect(intensity) ?: return
        vibrator.vibrate(vibration)
    }

    override fun vibrateHd(
        lowAmplitude: Float,
        lowFrequency: Float,
        highAmplitude: Float,
        highFrequency: Float
    ) {
        val peak = max(
            YuzuVibrator.shapeAmplitude(lowAmplitude),
            YuzuVibrator.shapeAmplitude(highAmplitude)
        )
        if (peak <= 0f) {
            vibrator.cancel()
            return
        }
        vibrator.vibrate(
            VibrationEffect.createOneShot(
                50,
                (255f * peak).toInt().coerceIn(1, 255)
            )
        )
    }
}
