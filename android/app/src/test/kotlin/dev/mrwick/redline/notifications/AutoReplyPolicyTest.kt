package dev.mrwick.redline.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReplyPolicyTest {
    private val on = AutoReplyPolicy.Config(enabled = true, rejectCall = false, bikeConnected = true)

    @Test
    fun missedCallSendsOneSmsDespiteDuplicateBroadcasts() {
        val p = AutoReplyPolicy()
        assertTrue(p.on(TelephonyCallState.RINGING, null, on).isEmpty())
        assertTrue(p.on(TelephonyCallState.RINGING, "+911234", on).isEmpty())
        assertEquals(listOf(AutoReplyPolicy.Action.SendSms("+911234")), p.on(TelephonyCallState.IDLE, null, on))
        assertTrue(p.on(TelephonyCallState.IDLE, "+911234", on).isEmpty())
    }

    @Test
    fun answeredCallSendsNothing() {
        val p = AutoReplyPolicy()
        p.on(TelephonyCallState.RINGING, "+911234", on)
        p.on(TelephonyCallState.OFFHOOK, null, on)
        assertTrue(p.on(TelephonyCallState.IDLE, null, on).isEmpty())
    }

    @Test
    fun rejectModeRejectsAndTextsOnce() {
        val p = AutoReplyPolicy()
        val cfg = on.copy(rejectCall = true)
        assertTrue(p.on(TelephonyCallState.RINGING, null, cfg).isEmpty())
        assertEquals(
            listOf(AutoReplyPolicy.Action.RejectCall, AutoReplyPolicy.Action.SendSms("555")),
            p.on(TelephonyCallState.RINGING, "555", cfg),
        )
        assertTrue(p.on(TelephonyCallState.IDLE, null, cfg).isEmpty())
    }

    @Test
    fun disabledOrDisconnectedOrUnknownNumberSendsNothing() {
        for (cfg in listOf(on.copy(enabled = false), on.copy(bikeConnected = false))) {
            val p = AutoReplyPolicy()
            p.on(TelephonyCallState.RINGING, "555", cfg)
            assertTrue(p.on(TelephonyCallState.IDLE, null, cfg).isEmpty())
        }
        val p = AutoReplyPolicy()
        p.on(TelephonyCallState.RINGING, null, on)
        assertTrue(p.on(TelephonyCallState.IDLE, null, on).isEmpty())
    }

    @Test
    fun nextCallIsHandledIndependently() {
        val p = AutoReplyPolicy()
        p.on(TelephonyCallState.RINGING, "1", on)
        p.on(TelephonyCallState.IDLE, null, on)
        p.on(TelephonyCallState.RINGING, "2", on)
        assertEquals(listOf(AutoReplyPolicy.Action.SendSms("2")), p.on(TelephonyCallState.IDLE, null, on))
    }
}

class CalendarAlertsTest {
    @Test
    fun frameMatchesOemCalendarLayout() {
        val f = CalendarAlerts.frame()
        assertEquals(0x35, f[1].toInt() and 0xFF)
        assertEquals(0xFF, f[2].toInt() and 0xFF)
        assertEquals(0x59, f[3].toInt() and 0xFF)
        assertEquals(0, f[4].toInt())
        for (i in 5..24) assertEquals(0, f[i].toInt())
        assertEquals(0x58, f[25].toInt() and 0xFF)
        assertEquals(0xFF, f[26].toInt() and 0xFF)
        assertEquals(0xFF, f[27].toInt() and 0xFF)
    }

    @Test
    fun packageMatching() {
        assertTrue(CalendarAlerts.isCalendarAlert("com.google.android.calendar", null))
        assertTrue(CalendarAlerts.isCalendarAlert("com.samsung.android.calendar", "msg"))
        assertTrue(CalendarAlerts.isCalendarAlert("us.zoom.videomeetings", "event"))
        assertFalse(CalendarAlerts.isCalendarAlert("com.microsoft.teams", "msg"))
        assertFalse(CalendarAlerts.isCalendarAlert("com.whatsapp", "event"))
    }

    @Test
    fun gateBlocksSamePackageWithinFiveSecondsAndRepeatedKeys() {
        val g = CalendarAlerts.Gate()
        assertTrue(g.accept("cal", "k1", 0))
        assertFalse(g.accept("cal", "k2", 4_999))
        assertTrue(g.accept("cal", "k2", 5_000))
        assertFalse(g.accept("cal", "k1", 20_000))
        assertTrue(g.accept("other", "k3", 20_001))
    }
}
