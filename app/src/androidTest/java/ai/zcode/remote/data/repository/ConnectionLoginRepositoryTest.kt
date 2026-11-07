package ai.zcode.remote.data.repository

import android.content.Context
import android.content.ContextWrapper
import ai.zcode.remote.data.model.RemoteConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 使用独立偏好文件，不修改应用真实连接；待设备可用后运行。 */
@RunWith(AndroidJUnit4::class)
class ConnectionLoginRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("login-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private val storageContext = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String?, mode: Int) = preferences
    }
    private val repository = ConnectionRepository(storageContext)

    @After fun cleanup() { preferences.edit().clear().commit() }

    private fun connection(mid: String = "computer-a", sid: String = "session-a", host: String = "example.com") =
        RemoteConnection(name = mid, mid = mid, sid = sid, url = "https://$host/remote?mid=$mid&sid=$sid")

    @Test fun resetInvalidatesOldSourceAndDoesNotAffectOtherConnection() {
        val first = repository.saveConnection(connection())
        val second = repository.saveConnection(connection(mid = "computer-b"))
        val updated = repository.resetLogin(first.id)!!
        assertNotEquals(first.loginSpace, updated.loginSpace)
        assertNull(repository.findBySourceId(first.loginSpace.sourceId))
        assertTrue(repository.isSourceDeleted(first.loginSpace.sourceId))
        assertEquals(updated.id, repository.findBySourceId(updated.loginSpace.sourceId)?.id)
        assertEquals(second.loginSpace, repository.findById(second.id)?.loginSpace)
        assertTrue(first.loginSpace.profileName in repository.pendingLoginProfileDeletions())
    }

    @Test fun editsKeepSpaceForSameComputerButReplaceItForAnotherTarget() {
        val original = repository.saveConnection(connection())
        val refreshed = repository.saveConnection(original.copy(name = "改名", sid = "new-session",
            url = "https://example.com/remote?sid=new-session&mid=computer-a"))
        assertEquals(original.loginSpace, refreshed.loginSpace)
        val moved = repository.saveConnection(refreshed.copy(mid = "computer-b",
            url = "https://example.com/remote?sid=new-session&mid=computer-b"))
        assertNotEquals(original.loginSpace, moved.loginSpace)
        val otherSite = repository.saveConnection(moved.copy(url = "https://other.example/remote?mid=computer-b&sid=new-session"))
        assertNotEquals(moved.loginSpace, otherSite.loginSpace)
    }

    @Test fun duplicateScanReturnsPersistedIdButOtherOriginRemainsSeparate() {
        val original = repository.saveConnection(connection())
        val duplicate = repository.saveConnection(connection())
        assertEquals(original.id, duplicate.id)
        val other = repository.saveConnection(connection(host = "other.example"))
        assertNotEquals(original.id, other.id)
        repository.saveOrder(listOf(other.id, original.id))
        assertEquals(original.loginSpace, repository.findById(original.id)?.loginSpace)
    }

    @Test fun deletingConnectionQueuesOnlyItsOwnProfile() {
        val first = repository.saveConnection(connection())
        val second = repository.saveConnection(connection(mid = "computer-b"))
        repository.deleteConnection(first.id)
        assertNull(repository.findBySourceId(first.loginSpace.sourceId))
        assertTrue(repository.isSourceDeleted(first.loginSpace.sourceId))
        assertEquals(setOf(first.loginSpace.profileName), repository.pendingLoginProfileDeletions())
        assertEquals(second.loginSpace, repository.findById(second.id)?.loginSpace)
    }

    @Test fun activeConnectionSurvivesReloadAndDeletionOfAnotherCardWithSameUrl() {
        val first = repository.saveConnection(connection())
        val second = repository.saveConnection(connection(mid = "computer-b"))
        val edited = repository.saveConnection(second.copy(url = first.url, mid = first.mid, sid = first.sid))
        assertEquals(second.id, edited.id)
        repository.updateLastConnected(edited.id)
        val reloaded = ConnectionRepository(storageContext)
        assertEquals(edited.id, reloaded.getLastActiveConnection()?.id)
        reloaded.deleteConnection(first.id)
        assertEquals(edited.id, reloaded.getLastActiveConnection()?.id)
    }

    @Test fun sameSpaceUrlEditResolvesOldNotificationSourceToLatestAddress() {
        val original = repository.saveConnection(connection())
        val oldSource = original.loginSpace.sourceId
        val updated = repository.saveConnection(original.copy(sid = "fresh-session",
            url = "https://example.com/remote?mid=computer-a&sid=fresh-session"))
        assertEquals(oldSource, updated.loginSpace.sourceId)
        assertFalse(repository.isSourceDeleted(oldSource))
        assertEquals(updated.url, ConnectionRepository(storageContext).findBySourceId(oldSource)?.url)
        assertNotEquals(original.url, updated.url)
    }

    @Test fun deletedSameUrlCardDoesNotInvalidateSurvivingSource() {
        val removed = repository.saveConnection(connection())
        val second = repository.saveConnection(connection(mid = "computer-b"))
        val surviving = repository.saveConnection(second.copy(url = removed.url,
            mid = removed.mid, sid = removed.sid))
        repository.deleteConnection(removed.id)
        assertTrue(repository.isSourceDeleted(removed.url))
        assertTrue(repository.isSourceDeleted(removed.loginSpace.sourceId))
        assertFalse(repository.isSourceDeleted(surviving.loginSpace.sourceId))
        assertEquals(surviving.id, repository.findBySourceId(surviving.loginSpace.sourceId)?.id)
    }

    @Test fun editedLaterGenerationKeepsCurrentSourceButNeverRevivesPreviousGeneration() {
        val original = repository.saveConnection(connection())
        val reset = repository.resetLogin(original.id)!!
        val updated = repository.saveConnection(reset.copy(sid = "fresh-session",
            url = "https://example.com/remote?mid=computer-a&sid=fresh-session"))
        assertEquals(reset.loginSpace.sourceId, updated.loginSpace.sourceId)
        assertEquals(updated.url, repository.findBySourceId(reset.loginSpace.sourceId)?.url)
        assertNull(repository.findBySourceId(original.loginSpace.sourceId))
        val moved = repository.saveConnection(updated.copy(mid = "computer-b",
            url = "https://example.com/remote?mid=computer-b&sid=fresh-session"))
        assertNotEquals(updated.loginSpace, moved.loginSpace)
        assertNull(repository.findBySourceId(updated.loginSpace.sourceId))
    }

    @Test fun editingLastActiveTargetUpdatesRecoveryEvenAfterRepositoryReload() {
        val first = repository.saveConnection(connection())
        repository.updateLastConnected(first.id)
        val reloaded = ConnectionRepository(storageContext)
        val edited = reloaded.saveConnection(first.copy(mid = "computer-b",
            url = "https://example.com/remote?mid=computer-b&sid=session-a"))
        assertEquals(edited.url, reloaded.getLastActiveUrl())
        assertEquals(edited.loginSpace, reloaded.getLastActiveConnection()?.loginSpace)
    }
}
