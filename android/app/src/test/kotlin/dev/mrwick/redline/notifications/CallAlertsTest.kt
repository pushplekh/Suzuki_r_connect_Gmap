package dev.mrwick.redline.notifications

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import android.text.SpannableString
import dev.mrwick.redline.ble.HeartbeatLoop
import dev.mrwick.redline.protocol.CallFrame
import dev.mrwick.redline.protocol.ClusterProtocol
import dev.mrwick.redline.protocol.MissedCallFrame
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CallAlertsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearNotifications() {
        CallAlerts.tracker.clearForTest()
    }

    @Test fun structuredIncomingUsesCallerPersonWithoutEnglishText() {
        val answer = PendingIntent.getBroadcast(context, 1, Intent("test.answer"), PendingIntent.FLAG_IMMUTABLE)
        val decline = PendingIntent.getBroadcast(context, 2, Intent("test.decline"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, "calls")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setStyle(Notification.CallStyle.forIncomingCall(
                Person.Builder().setName("TEST CALLER").setUri("tel:1234567890").build(), decline, answer,
            ))
            .build()
        assertTrue(CallAlerts.posted(sbn(notification)))
        val frame = CallAlerts.tracker.state.value.frames.single() as CallFrame
        assertEquals("TEST CALLER", frame.number)
        assertEquals('1'.code, frame.state)
        assertFalse(frame.isWhatsapp)
        assertTrue(CallAlerts.tracker.state.value.inCall)
    }

    @Test fun charSequenceCallerAndMissedCategoryDoNotRequireEnglishText() {
        val notification = notification(SpannableString("TEST CALLER"), "", Notification.CATEGORY_MISSED_CALL)
        notification.number = 3
        assertTrue(CallAlerts.posted(sbn(notification)))
        val frame = CallAlerts.tracker.state.value.frames.single() as MissedCallFrame
        assertEquals("TEST CALLER", frame.name)
        assertEquals(3, frame.missedCount)
        assertFalse(CallAlerts.tracker.state.value.cleared)
    }

    @Test fun genericMissedTitleFallsBackToNumberInText() {
        assertTrue(CallAlerts.posted(sbn(notification("Missed call", "+1234567890", Notification.CATEGORY_MISSED_CALL))))
        assertEquals("+1234567890", (CallAlerts.tracker.state.value.frames.single() as MissedCallFrame).name)
    }

    @Test fun nonAsciiNameFallsBackToStructuredPhoneNumber() {
        val notification = notification("\u0928\u093e\u092e", "", Notification.CATEGORY_CALL)
        notification.extras.putInt(Notification.EXTRA_CALL_TYPE, Notification.CallStyle.CALL_TYPE_INCOMING)
        notification.extras.putParcelable(Notification.EXTRA_CALL_PERSON,
            Person.Builder().setName("\u0928\u093e\u092e").setUri("tel:1234567890").build())
        assertTrue(CallAlerts.posted(sbn(notification)))
        assertEquals("1234567890", (CallAlerts.tracker.state.value.frames.single() as CallFrame).number)
    }

    @Test fun whatsappCallsAreNotSmsAndUseWhatsappSourceByte() {
        val alert = sbn(notification("TEST CALLER", "Incoming voice call"), pkg = "com.whatsapp")
        assertTrue(CallAlerts.posted(alert))
        val frame = CallAlerts.tracker.state.value.frames.single() as CallFrame
        assertTrue(frame.isWhatsapp)
        assertEquals('W'.code.toByte(), frame.encode()[22])
    }

    @Test fun outgoingAndOrdinaryChatDoNotBecomeIncomingCalls() {
        assertTrue(CallAlerts.posted(sbn(notification("TEST CALLER", "Calling", Notification.CATEGORY_CALL))))
        assertTrue(CallAlerts.tracker.state.value.frames.isEmpty())
        val ongoing = notification("TEST CALLER", "", Notification.CATEGORY_CALL)
        ongoing.extras.putInt(Notification.EXTRA_CALL_TYPE, Notification.CallStyle.CALL_TYPE_ONGOING)
        assertTrue(CallAlerts.posted(sbn(ongoing)))
        assertTrue(CallAlerts.tracker.state.value.frames.isEmpty())
        assertFalse(CallAlerts.posted(sbn(notification("TEST CALLER", "I missed calls yesterday"), pkg = "com.whatsapp")))
        assertTrue(CallAlerts.tracker.state.value.frames.isEmpty())
    }

    @Test fun notificationTimestampRefreshDoesNotRepeatRingingAlert() {
        val notification = notification("TEST CALLER", "Incoming call", Notification.CATEGORY_CALL)
        CallAlerts.posted(sbn(notification))
        val revision = CallAlerts.tracker.state.value.revision
        notification.`when` = 2_000
        CallAlerts.posted(sbn(notification))
        assertEquals(revision, CallAlerts.tracker.state.value.revision)
    }

    @Test fun dispatcherRoutesCallsAndRemovalClearsMissedIdentity() {
        val notification = sbn(notification("TEST CALLER", "", Notification.CATEGORY_MISSED_CALL))
        NotificationDispatcher.onPosted(context, notification)
        assertEquals("TEST CALLER", (CallAlerts.tracker.state.value.frames.single() as MissedCallFrame).name)
        NotificationDispatcher.onRemoved(notification)
        assertEquals(0, (CallAlerts.tracker.state.value.frames.single() as MissedCallFrame).missedCount)
        assertTrue(CallAlerts.tracker.state.value.cleared)
    }

    @Test fun ongoingUpdateUsesStateTwoAndRemovalNeverInventsMissedCall() {
        val incoming = notification("TEST CALLER", "Incoming call", Notification.CATEGORY_CALL)
        val first = sbn(incoming)
        CallAlerts.posted(first)
        val ongoing = notification("TEST CALLER", "", Notification.CATEGORY_CALL)
        ongoing.extras.putInt(Notification.EXTRA_CALL_TYPE, Notification.CallStyle.CALL_TYPE_ONGOING)
        CallAlerts.posted(sbn(ongoing))
        assertEquals('2'.code, (CallAlerts.tracker.state.value.frames.single() as CallFrame).state)
        CallAlerts.tracker.removed(first.key)
        assertTrue(CallAlerts.tracker.state.value.frames.isEmpty())
        assertFalse(CallAlerts.tracker.state.value.inCall)
    }

    @Test @Config(sdk = [29])
    fun legacyIncomingTextWorksWithoutCallStyle() {
        assertTrue(CallAlerts.posted(sbn(notification("TEST CALLER", "Incoming call"))))
        assertEquals("TEST CALLER", (CallAlerts.tracker.state.value.frames.single() as CallFrame).number)
    }

    @Test fun duplicatePostIsStableButSeparateCallsWithSameCallerAreNotDeduplicated() {
        val tracker = CallAlertTracker()
        val first = CallAlert("first", "TEST", CallPhase.RINGING, false)
        tracker.posted(first)
        val revision = tracker.state.value.revision
        tracker.posted(first)
        assertEquals(revision, tracker.state.value.revision)
        tracker.removed("first")
        tracker.posted(first.copy(key = "second"))
        assertEquals(revision + 2, tracker.state.value.revision)
    }

    @Test fun missedCountsAggregateClearAndReplayWithoutFabricatedPrefixOnBothProtocols() {
        val tracker = CallAlertTracker()
        tracker.posted(CallAlert("one", "Y1 CALLER", CallPhase.MISSED, false, count = 2))
        tracker.posted(CallAlert("two", "ABCDEFGHIJKLMNOPQRST", CallPhase.MISSED, false))
        val alert = tracker.state.value.frames.single() as MissedCallFrame
        assertEquals(3, alert.missedCount)
        for (protocol in ClusterProtocol.entries) {
            val wire = protocol.toWire(alert.encode())
            assertEquals("ABCDEFGHIJKLMNOPQRST", String(wire, 4, 20, Charsets.US_ASCII))
            assertEquals(alert.name, (protocol.decode(wire) as MissedCallFrame).name)
        }
        tracker.removed("two")
        assertEquals("Y1 CALLER", (tracker.state.value.frames.single() as MissedCallFrame).name)
        tracker.removed("one")
        val clear = tracker.state.value.frames.single() as MissedCallFrame
        assertEquals(0, clear.missedCount)
        assertEquals("", clear.name)
        assertArrayEquals(ByteArray(20), clear.encode().copyOfRange(4, 24))
        assertTrue(tracker.state.value.cleared)
        // A reconnect reads this same current snapshot, not a historical ringing event.
        assertEquals(listOf(clear), tracker.state.value.frames)
    }

    @Test fun groupedMissedSummaryDoesNotDoubleCountChildren() {
        val tracker = CallAlertTracker()
        tracker.posted(CallAlert("summary", "Missed calls", CallPhase.MISSED, false, count = 2, group = "g", summary = true))
        tracker.posted(CallAlert("one", "CALLER A", CallPhase.MISSED, false, group = "g"))
        tracker.posted(CallAlert("two", "CALLER B", CallPhase.MISSED, false, group = "g"))
        assertEquals(2, (tracker.state.value.frames.single() as MissedCallFrame).missedCount)
    }

    @Test fun clearedSourcesStayClearedOnLaterUpdatesAndActiveCallIsSentLast() {
        val tracker = CallAlertTracker()
        tracker.posted(CallAlert("cell", "A", CallPhase.MISSED, false))
        tracker.removed("cell")
        tracker.posted(CallAlert("wa", "B", CallPhase.MISSED, true))
        tracker.posted(CallAlert("ring", "C", CallPhase.RINGING, false))
        val frames = tracker.state.value.frames
        assertEquals(0, frames.filterIsInstance<MissedCallFrame>().single { !it.isWhatsapp }.missedCount)
        assertEquals("C", (frames.last() as CallFrame).number)
    }

    @Test fun sourcesClearIndependentlyAndHeartbeatUsesOemClearPolarity() {
        val tracker = CallAlertTracker()
        tracker.posted(CallAlert("cell", "A", CallPhase.MISSED, false))
        tracker.posted(CallAlert("wa", "B", CallPhase.MISSED, true))
        val heartbeat = HeartbeatLoop(
            { 100 to false }, { 3 }, { false }, { tracker.state.value.cleared }, { 1 to 0 },
        )
        assertEquals("N", heartbeat.buildFrame().callPending)
        tracker.removed("cell")
        assertFalse(tracker.state.value.cleared)
        val frames = tracker.state.value.frames.filterIsInstance<MissedCallFrame>()
        assertEquals(0, frames.single { !it.isWhatsapp }.missedCount)
        assertEquals(1, frames.single { it.isWhatsapp }.missedCount)
        tracker.retainKeys(emptySet())
        assertTrue(tracker.state.value.cleared)
        for (protocol in ClusterProtocol.entries) {
            assertEquals('Y'.code.toByte(), protocol.toWire(heartbeat.buildFrame().encode())[15])
        }
    }

    @Test fun burstSendsExactlyThreeTimesAtTwoHundredMillisecondIntervals() = runTest {
        val frame = CallFrame("TEST")
        val times = mutableListOf<Long>()
        CallAlertSnapshot(frames = listOf(frame)).sendBurst {
            assertEquals(frame, it)
            times.add(testScheduler.currentTime)
        }
        assertEquals(listOf(200L, 400L, 600L), times)
    }

    @Test fun obsoleteBurstCanBeCancelledBeforeAnotherSend() = runTest {
        var sent = 0
        val job = launch { CallAlertSnapshot(frames = listOf(CallFrame("TEST"))).sendBurst { sent++ } }
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, sent)
        job.cancel()
        advanceTimeBy(1_000)
        assertEquals(1, sent)
    }

    @Test fun ringingHoldsNavigationUntilAnsweredOrRemovedAndIsBounded() {
        var now = 1_000L
        val tracker = CallAlertTracker { now }
        tracker.posted(CallAlert("ring", "TEST", CallPhase.RINGING, true))
        assertEquals(1_000L, tracker.state.value.ringingSinceMs)
        assertTrue(tracker.state.value.holdsNavigation(now))
        now += 5_000
        tracker.posted(CallAlert("missed", "OTHER", CallPhase.MISSED, false))
        assertEquals(1_000L, tracker.state.value.ringingSinceMs)
        assertFalse(tracker.state.value.holdsNavigation(1_000L + CallAlertSnapshot.MAX_RING_REFRESH_MS))
        tracker.posted(CallAlert("ring", "TEST", CallPhase.ONGOING, true))
        assertNull(tracker.state.value.ringingSinceMs)
        assertFalse(tracker.state.value.holdsNavigation(now))
        tracker.posted(CallAlert("ring2", "TEST", CallPhase.RINGING, false))
        assertTrue(tracker.state.value.holdsNavigation(now))
        tracker.removed("ring2")
        assertFalse(tracker.state.value.holdsNavigation(now))
    }

    @Test fun ringingCallIsResentEverySecondUntilBoundButAnsweredCallIsNot() = runTest {
        val ringing = CallFrame("TEST", isWhatsapp = true, state = '1'.code)
        val missed = MissedCallFrame("OTHER", 1, false)
        val snapshot = CallAlertSnapshot(frames = listOf(missed, ringing), ringingSinceMs = 0)
        val sent = mutableListOf<Pair<Long, Any>>()
        snapshot.sendAndRefreshWhileRinging({ testScheduler.currentTime }) { sent.add(testScheduler.currentTime to it) }
        assertEquals(6, sent.count { it.first <= 600 })
        val refreshes = sent.filter { it.first > 600 }
        assertTrue(refreshes.all { it.second == ringing })
        assertEquals(listOf(1_600L, 2_600L), refreshes.take(2).map { it.first })
        assertTrue(refreshes.last().first < CallAlertSnapshot.MAX_RING_REFRESH_MS)
        assertEquals((CallAlertSnapshot.MAX_RING_REFRESH_MS - 600) / 1_000, refreshes.size.toLong())

        var answeredSends = 0
        CallAlertSnapshot(frames = listOf(CallFrame("TEST", state = '2'.code)))
            .sendAndRefreshWhileRinging({ testScheduler.currentTime }) { answeredSends++ }
        assertEquals(3, answeredSends)
    }

    @Test fun telephonyRingingShowsCallerUntilAnsweredThenOngoing() {
        val tracker = CallAlertTracker { 0 }
        tracker.telephony(TelephonyCallState.RINGING, null)
        tracker.telephony(TelephonyCallState.RINGING, "Mom")
        tracker.telephony(TelephonyCallState.RINGING, null)
        val ringing = tracker.state.value.frames.single() as CallFrame
        assertEquals("Mom", ringing.number)
        assertEquals('1'.code, ringing.state)
        assertFalse(ringing.isWhatsapp)
        assertTrue(tracker.state.value.holdsNavigation(0))

        tracker.telephony(TelephonyCallState.OFFHOOK, "Mom")
        assertEquals('2'.code, (tracker.state.value.frames.single() as CallFrame).state)
        assertFalse(tracker.state.value.holdsNavigation(0))

        tracker.telephony(TelephonyCallState.IDLE, null)
        assertTrue(tracker.state.value.frames.isEmpty())
        assertFalse(tracker.state.value.inCall)
    }

    @Test fun telephonyUnansweredCallBecomesMissedAndCountsUntilCleared() {
        val tracker = CallAlertTracker { 0 }
        repeat(2) {
            tracker.telephony(TelephonyCallState.RINGING, "+911234567890")
            tracker.telephony(TelephonyCallState.IDLE, null)
        }
        val missed = tracker.state.value.frames.single() as MissedCallFrame
        assertEquals("+911234567890", missed.name)
        assertEquals(2, missed.missedCount)
        assertFalse(missed.isWhatsapp)
        assertFalse(tracker.state.value.inCall)

        tracker.clearTelephonyMissed()
        assertEquals(0, (tracker.state.value.frames.single() as MissedCallFrame).missedCount)
        assertTrue(tracker.state.value.cleared)
    }

    @Test fun outgoingTelephonyCallIsNotShown() {
        val tracker = CallAlertTracker { 0 }
        tracker.telephony(TelephonyCallState.OFFHOOK, null)
        tracker.telephony(TelephonyCallState.IDLE, null)
        assertTrue(tracker.state.value.frames.isEmpty())
        assertEquals(0, tracker.state.value.revision)
    }

    @Test fun telephonyReplacesDialerRingingAndDialerMissedReplacesTelephonyMissed() {
        val tracker = CallAlertTracker { 0 }
        tracker.posted(CallAlert("dialer", "Dialer name", CallPhase.RINGING, false))
        tracker.telephony(TelephonyCallState.RINGING, "Contact")
        assertEquals("Contact", (tracker.state.value.frames.single() as CallFrame).number)
        tracker.posted(CallAlert("wa", "WA", CallPhase.RINGING, true))
        assertEquals(2, tracker.state.value.frames.size)
        tracker.removed("wa")
        tracker.removed("dialer")

        tracker.telephony(TelephonyCallState.IDLE, null)
        tracker.posted(CallAlert("dialer-missed", "Contact", CallPhase.MISSED, false, count = 1))
        assertEquals(1, (tracker.state.value.frames.single() as MissedCallFrame).missedCount)
        tracker.removed("dialer-missed")
        assertEquals(0, (tracker.state.value.frames.single() as MissedCallFrame).missedCount)
        assertTrue(tracker.state.value.cleared)
    }

    @Test fun listenerReconnectDoesNotDropTelephonyCall() {
        val tracker = CallAlertTracker { 0 }
        tracker.telephony(TelephonyCallState.RINGING, "Mom")
        tracker.retainKeys(emptySet())
        assertEquals("Mom", (tracker.state.value.frames.single() as CallFrame).number)
    }

    @Test fun telephonyCallerPrefersAsciiContactNameThenNumber() {
        assertEquals("Mom", CallAlerts.telephonyCaller("Mom", "+911234567890"))
        assertEquals("+911234567890", CallAlerts.telephonyCaller("\u0928\u093e\u092e", "+911234567890"))
        assertNull(CallAlerts.telephonyCaller(null, null))
    }

    @Test fun removedMissedDialerNotificationIsRecognizedForClearing() {
        assertTrue(CallAlerts.looksMissed(sbn(notification("Missed call", "Mom"))))
        assertTrue(CallAlerts.looksMissed(sbn(notification("Mom", "", Notification.CATEGORY_MISSED_CALL))))
        assertFalse(CallAlerts.looksMissed(sbn(notification("Mom", "Incoming call"))))
    }

    private fun notification(
        title: CharSequence,
        text: String,
        category: String? = null,
    ): Notification = Notification.Builder(context, "calls")
        .setSmallIcon(android.R.drawable.sym_call_incoming)
        .setContentTitle(title).setContentText(text).setCategory(category).setWhen(1_000)
        .build()

    private fun sbn(
        notification: Notification,
        pkg: String = "com.google.android.dialer",
    ) = StatusBarNotification(pkg, pkg, 1, null, 1, 1, 0, notification, Process.myUserHandle(), 1_000)
}
