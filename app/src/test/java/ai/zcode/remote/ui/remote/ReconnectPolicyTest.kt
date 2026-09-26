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
}
