package ai.zcode.remote.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class LoginNoticeTimerTest {
    @Test
    fun `idle timer has no remaining time`() {
        val timer = LoginNoticeTimer()
        assertEquals(0L, timer.deadlineElapsedMs)
        assertEquals(0L, timer.remainingMillis(0L))
        assertEquals(0L, timer.remainingMillis(5_000L))
    }

    @Test
    fun `start sets ten seconds and repeated starts keep original deadline`() {
        val timer = LoginNoticeTimer()
        timer.start(5_000L)
        assertEquals(10_000L, LoginNoticeTimer.DURATION_MS)
        assertEquals(15_000L, timer.deadlineElapsedMs)
        assertEquals(10_000L, timer.remainingMillis(5_000L))
        timer.start(8_000L)
        assertEquals(15_000L, timer.deadlineElapsedMs)
        assertEquals(7_000L, timer.remainingMillis(8_000L))
    }

    @Test
    fun `countdown reaches zero without restarting after expiry`() {
        val timer = LoginNoticeTimer()
        timer.start(0L)
        assertEquals(1L, timer.remainingMillis(9_999L))
        assertEquals(0L, timer.remainingMillis(10_000L))
        assertEquals(0L, timer.remainingMillis(12_000L))
        timer.start(12_000L)
        assertEquals(10_000L, timer.deadlineElapsedMs)
        assertEquals(0L, timer.remainingMillis(12_000L))
    }

    @Test
    fun `constructor restores original deadline rather than a new duration`() {
        val original = LoginNoticeTimer()
        original.start(5_000L)
        val restored = LoginNoticeTimer(original.deadlineElapsedMs)
        restored.start(9_000L)
        assertEquals(15_000L, restored.deadlineElapsedMs)
        assertEquals(6_000L, restored.remainingMillis(9_000L))
        assertEquals(0L, LoginNoticeTimer(15_000L).remainingMillis(20_000L))
    }

    @Test
    fun `dismiss stops countdown and prevents restarting even after restoration`() {
        val timer = LoginNoticeTimer()
        timer.start(5_000L)
        timer.dismiss()
        assertEquals(0L, timer.remainingMillis(0L))
        assertEquals(0L, timer.remainingMillis(5_000L))
        val dismissedDeadline = timer.deadlineElapsedMs
        timer.start(8_000L)
        assertEquals(dismissedDeadline, timer.deadlineElapsedMs)
        assertEquals(0L, timer.remainingMillis(8_000L))
        val restored = LoginNoticeTimer(dismissedDeadline)
        restored.start(9_000L)
        assertEquals(dismissedDeadline, restored.deadlineElapsedMs)
        assertEquals(0L, restored.remainingMillis(9_000L))
    }

    @Test
    fun `dismiss before start permanently suppresses instance`() {
        val timer = LoginNoticeTimer()
        timer.dismiss()
        timer.start(0L)
        assertEquals(0L, timer.remainingMillis(0L))
        timer.start(5_000L)
        assertEquals(0L, timer.remainingMillis(5_000L))
    }
}
