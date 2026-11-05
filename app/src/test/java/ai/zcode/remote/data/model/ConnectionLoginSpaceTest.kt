package ai.zcode.remote.data.model

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ConnectionLoginSpaceTest {
    @Test
    fun `unsafe identifiers cannot collide after profile name encoding`() {
        val first = ConnectionLoginSpace("电脑/A")
        val second = ConnectionLoginSpace("电脑?A")
        assertNotEquals(first.profileName, second.profileName)
        assertTrue(first.profileName.matches(Regex("[A-Za-z0-9_-]+")))
        assertFalse(first.profileName.contains("电脑"))
        assertEquals(first.profileName, ConnectionLoginSpace("电脑/A").profileName)
    }

    @Test
    fun `reset changes both storage and notification identity`() {
        val first = ConnectionLoginSpace("connection")
        val reset = first.copy(generation = 1)
        assertNotEquals(first.profileName, reset.profileName)
        assertNotEquals(first.sourceId, reset.sourceId)
        assertEquals("connection", first.sourceId)
        assertEquals(reset, ConnectionLoginSpace.fromSourceId(reset.sourceId))
        assertEquals(first, ConnectionLoginSpace.fromSourceId(first.sourceId))
    }

    @Test
    fun `urls and malformed generations are not login identities`() {
        assertNull(ConnectionLoginSpace.fromSourceId("https://example.com/remote"))
        assertNull(ConnectionLoginSpace.fromSourceId("id:login:-1"))
        assertNull(ConnectionLoginSpace.fromSourceId("id:login:invalid"))
        assertNull(ConnectionLoginSpace.fromSourceId(":login:1"))
        assertNull(ConnectionLoginSpace.fromSourceId(""))
    }

    @Test
    fun `old saved connections migrate to first generation without changing id`() {
        val connection = Gson().fromJson(
            """{"id":"saved-id","name":"旧连接","url":"https://example.com"}""",
            RemoteConnection::class.java,
        )
        assertEquals(0L, connection.loginGeneration)
        assertEquals(ConnectionLoginSpace("saved-id"), connection.loginSpace)
    }

    @Test
    fun `renaming and reordering metadata do not change login space`() {
        val connection = RemoteConnection(name = "A", url = "https://example.com")
        val space = connection.loginSpace
        connection.name = "B"
        connection.lastConnectedTime = 100
        connection.lastTaskId = "task"
        assertEquals(space, connection.loginSpace)
    }
}
