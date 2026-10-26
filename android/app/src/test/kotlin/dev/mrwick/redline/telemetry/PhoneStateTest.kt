package dev.mrwick.redline.telemetry

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PhoneStateTest {
    @Test fun screenEventsDoNotResetBatteryAndTemperatureIsCapturedWithoutFabricatedDefault() {
        val context = RuntimeEnvironment.getApplication()
        val state = PhoneState(context)
        state.start()
        try {
            context.sendBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(BatteryManager.EXTRA_LEVEL, 73)
                putExtra(BatteryManager.EXTRA_SCALE, 100)
                putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
                putExtra(BatteryManager.EXTRA_TEMPERATURE, 387)
            })
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(state.diagnosticSummary().contains("batteryTempTenthsC=387"))
            assertTrue(state.diagnosticSummary().contains("batteryPercent=73"))
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(state.diagnosticSummary().contains("batteryPercent=73"))
            assertFalse(state.isCharging.value)
            context.sendBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(state.diagnosticSummary().contains("batteryTempTenthsC=null"))
        } finally {
            state.stop()
        }
    }
}
