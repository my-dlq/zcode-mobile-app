package ai.zcode.remote.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `delay grows exponentially from one second`() {
        assertEquals(1_000L, ReconnectPolicy.delayMs(0))
        assertEquals(2_000L, ReconnectPolicy.delayMs(1))
        assertEquals(4_000L, ReconnectPolicy.delayMs(2))
        assertEquals(8_000L, ReconnectPolicy.delayMs(3))
    }

    @Test
    fun `delay caps at one minute`() {
        assertEquals(60_000L, ReconnectPolicy.delayMs(6))
        assertEquals(60_000L, ReconnectPolicy.delayMs(100))
    }

    @Test
    fun `healthy renderer does not request recovery`() {
        assertEquals(RendererRecoveryState.Action.NONE, RendererRecoveryState().nextAction(true, true))
    }

    @Test
    fun `foreground renderer death requests only one rebuild even offline`() {
        val recovery = RendererRecoveryState()
        assertEquals(true, recovery.markGone())
        assertEquals(false, recovery.markGone())
        assertEquals(true, recovery.pending)
        // 决策不接收网络状态，离线仍须丢弃死 view 并重建。
        assertEquals(RendererRecoveryState.Action.RECREATE, recovery.nextAction(true, true))
        assertEquals(RendererRecoveryState.Action.NONE, recovery.nextAction(true, true))
        assertEquals(RendererRecoveryState.Action.NONE, recovery.nextAction(true, false))
    }

    @Test
    fun `background death hands over only once`() {
        val recovery = RendererRecoveryState()
        recovery.markGone()
        assertEquals(RendererRecoveryState.Action.HANDOVER, recovery.nextAction(true, false))
        assertEquals(RendererRecoveryState.Action.NONE, recovery.nextAction(true, false))
    }

    @Test
    fun `lost ownership cannot recover and does not consume decision`() {
        val recovery = RendererRecoveryState()
        recovery.markGone()
        assertEquals(RendererRecoveryState.Action.NONE, recovery.nextAction(false, true))
        assertEquals(RendererRecoveryState.Action.RECREATE, recovery.nextAction(true, true))
    }

    @Test
    fun `visibility is decided when recovery runs not when renderer dies`() {
        val recovery = RendererRecoveryState()
        recovery.markGone()
        assertEquals(RendererRecoveryState.Action.HANDOVER, recovery.nextAction(true, false))
    }
}
