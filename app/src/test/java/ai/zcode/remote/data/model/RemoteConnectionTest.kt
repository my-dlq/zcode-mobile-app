package ai.zcode.remote.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RemoteConnectionTest {

    @Test
    fun `default id is a valid UUID`() {
        val connection = RemoteConnection(name = "测试设备", url = "https://example.com")
        // 不抛异常即为合法 UUID
        UUID.fromString(connection.id)
    }

    @Test
    fun `each instance gets a unique id`() {
        val a = RemoteConnection(name = "A", url = "https://example.com")
        val b = RemoteConnection(name = "B", url = "https://example.com")
        assertTrue(a.id != b.id)
    }

    @Test
    fun `lastConnectedTime defaults to current time`() {
        val before = System.currentTimeMillis()
        val connection = RemoteConnection(name = "测试设备", url = "https://example.com")
        val after = System.currentTimeMillis()
        assertTrue(connection.lastConnectedTime in before..after)
    }

    @Test
    fun `mutable fields can be updated`() {
        val connection = RemoteConnection(name = "旧名称", url = "https://old.example.com")
        connection.name = "新名称"
        connection.url = "https://new.example.com"
        assertEquals("新名称", connection.name)
        assertEquals("https://new.example.com", connection.url)
    }
}
