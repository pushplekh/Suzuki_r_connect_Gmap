package dev.mrwick.redline.ble

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameStreamTest {
    @Test fun inspectorCanSeeAnEventEmittedBeforeItOpened() = runTest {
        val stream = FrameStream()
        val event = FrameEvent(FrameEvent.Direction.TX, byteArrayOf(1), note = "nav-preview")
        stream.emit(event)
        assertEquals(event, stream.events.first())
    }
}
