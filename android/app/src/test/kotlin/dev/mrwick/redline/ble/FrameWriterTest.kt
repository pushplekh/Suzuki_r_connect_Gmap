package dev.mrwick.redline.ble

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWriterTest {
    @Test fun enqueueDoesNotReportDeliveryBeforeWriterCompletes() = runTest {
        val writer = FrameWriter()
        val completion = CompletableDeferred<Boolean>()
        assertTrue(writer.enqueue(FrameWriter.Entry(
            FrameWriter.Priority.URGENT, byteArrayOf(6), completion = completion,
        )))
        assertFalse(completion.isCompleted)
        writer.take().completion?.complete(false)
        assertFalse(completion.await())
    }

    @Test fun fullUrgentQueueExplicitlyRejectsAndCompletesFailure() = runTest {
        val writer = FrameWriter()
        repeat(32) { writer.enqueue(FrameWriter.Entry(FrameWriter.Priority.URGENT, byteArrayOf(6))) }
        val completion = CompletableDeferred<Boolean>()
        assertFalse(writer.enqueue(FrameWriter.Entry(
            FrameWriter.Priority.URGENT, byteArrayOf(6), completion = completion,
        )))
        assertFalse(completion.await())
    }

    @Test fun periodicNavigationCanRepeatIdenticalBytes() = runTest {
        val writer = FrameWriter()
        val frame = ByteArray(30) { it.toByte() }
        val entry = FrameWriter.Entry(FrameWriter.Priority.NAV, frame, repeat = true)
        writer.enqueue(entry)
        assertArrayEquals(frame, writer.take().frame)
        writer.enqueue(entry)
        assertArrayEquals(frame, writer.take().frame)
    }

    @Test fun urgentIdentityStillPrecedesHeartbeatAndNav() = runTest {
        val writer = FrameWriter()
        writer.enqueue(FrameWriter.Entry(FrameWriter.Priority.NAV, byteArrayOf(1), repeat = true))
        writer.enqueue(FrameWriter.Entry(FrameWriter.Priority.HEARTBEAT, byteArrayOf(3)))
        writer.enqueue(FrameWriter.Entry(FrameWriter.Priority.URGENT, byteArrayOf(6)))
        assertArrayEquals(byteArrayOf(6), writer.take().frame)
        assertArrayEquals(byteArrayOf(3), writer.take().frame)
        assertArrayEquals(byteArrayOf(1), writer.take().frame)
    }
}
