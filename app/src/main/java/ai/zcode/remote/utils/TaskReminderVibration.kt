package ai.zcode.remote.utils

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

internal object TaskReminderVibration {
    // 短震两次，中间留出间隔，便于和持续震动区分。
    fun pattern(): LongArray = longArrayOf(0, 100, 90, 130)

    @Suppress("DEPRECATION") // 旧版 Android 仍使用 Vibrator 和 AudioAttributes。
    fun preview(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        } ?: return
        if (!vibrator.hasVibrator()) return

        val timings = pattern()
        val usage = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> vibrator.vibrate(
                VibrationEffect.createWaveform(timings, -1),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION),
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1), usage)
            else -> vibrator.vibrate(timings, -1, usage)
        }
    }
}
