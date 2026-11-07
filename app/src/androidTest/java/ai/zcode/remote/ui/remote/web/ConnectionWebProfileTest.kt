package ai.zcode.remote.ui.remote.web

import android.content.Context
import android.content.ContextWrapper
import ai.zcode.remote.data.model.ConnectionLoginSpace
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 所有读写重定向到独立偏好文件，不访问或清理真实连接的展示标记。 */
@RunWith(AndroidJUnit4::class)
class ConnectionWebProfileTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferencesName = "connection-web-profile-test-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val storageContext = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String?, mode: Int) = preferences
    }

    @After
    fun cleanup() {
        context.deleteSharedPreferences(preferencesName)
    }

    private fun connectionKey(space: ConnectionLoginSpace) = "introduction_connection:${space.connectionId}"

    @Test
    fun currentLegacyProfileMigratesOnlyTrueFlag() {
        val space = ConnectionLoginSpace("connection-a", 3)
        preferences.edit().putBoolean(space.profileName, true).commit()

        assertTrue(ConnectionWebProfile.hasShownIntroduction(storageContext, space))
        assertTrue(preferences.getBoolean(connectionKey(space), false))
        assertTrue(preferences.getBoolean(space.profileName, false))
    }

    @Test
    fun anyHistoricalGenerationMigratesForSameConnection() {
        val oldSpace = ConnectionLoginSpace("connection-a", 27)
        val currentSpace = oldSpace.copy(generation = 104)
        preferences.edit()
            .putBoolean(oldSpace.profileName, true)
            .putBoolean(currentSpace.profileName, false)
            .commit()

        assertTrue(ConnectionWebProfile.hasShownIntroduction(storageContext, currentSpace))
        assertTrue(preferences.getBoolean(connectionKey(currentSpace), false))
    }

    @Test
    fun falseAndAbsentLegacyFlagsDoNotCreateConnectionMarker() {
        val space = ConnectionLoginSpace("connection-a", 5)
        assertFalse(ConnectionWebProfile.hasShownIntroduction(storageContext, space))
        assertFalse(preferences.contains(connectionKey(space)))
        preferences.edit().putBoolean(space.copy(generation = 2).profileName, false)
            .putBoolean(space.profileName, false).commit()

        assertFalse(ConnectionWebProfile.hasShownIntroduction(storageContext, space))
        assertFalse(preferences.contains(connectionKey(space)))
    }

    @Test
    fun newMarkerTakesPrecedenceAndSurvivesGenerationReset() {
        val space = ConnectionLoginSpace("connection-a", 3)
        ConnectionWebProfile.markIntroductionShown(storageContext, space)

        assertTrue(preferences.getBoolean(connectionKey(space), false))
        assertFalse(preferences.contains(space.profileName))
        assertTrue(ConnectionWebProfile.hasShownIntroduction(storageContext, space.copy(generation = 4)))
        assertTrue(ConnectionWebProfile.hasShownIntroduction(storageContext, space.copy(generation = 100)))
    }

    @Test
    fun removingLegacyProfileDoesNotRemoveMigratedConnectionMarker() {
        val space = ConnectionLoginSpace("connection-a", 3)
        preferences.edit().putBoolean(space.profileName, true).commit()
        assertTrue(ConnectionWebProfile.hasShownIntroduction(storageContext, space))
        // 与旧 Profile 清理一致，只删除 Profile 名，连接级标记必须独立存活。
        preferences.edit().remove(space.profileName).commit()

        assertTrue(preferences.getBoolean(connectionKey(space), false))
        assertTrue(ConnectionWebProfile.hasShownIntroduction(storageContext, space.copy(generation = 4)))
    }

    @Test
    fun startupCleanupMigratesResetConnectionsBeforeRemovingLegacyFlags() {
        val firstOld = ConnectionLoginSpace("connection-a", 27)
        val secondOld = ConnectionLoginSpace("connection-b", 5)
        val firstCurrent = firstOld.copy(generation = 104)
        val secondCurrent = secondOld.copy(generation = 6)
        preferences.edit().putBoolean(firstOld.profileName, true)
            .putBoolean(secondOld.profileName, true).commit()

        // 不提前查询展示状态，直接模拟升级后冷启动先执行清理的顺序。
        ConnectionWebProfile.cleanIntroductionProfiles(
            storageContext,
            listOf(firstCurrent, secondCurrent),
            setOf(firstOld.profileName, secondOld.profileName),
        )

        assertFalse(preferences.contains(firstOld.profileName))
        assertFalse(preferences.contains(secondOld.profileName))
        assertTrue(preferences.getBoolean(connectionKey(firstCurrent), false))
        assertTrue(preferences.getBoolean(connectionKey(secondCurrent), false))
        assertTrue(ConnectionWebProfile.hasShownIntroduction(storageContext, firstCurrent))
        assertTrue(ConnectionWebProfile.hasShownIntroduction(storageContext, secondCurrent))
    }

    @Test
    fun startupCleanupDoesNotMigrateFalseOrDeletedConnectionsOrRemovePendingFlags() {
        val unreadOld = ConnectionLoginSpace("unread", 2)
        val deletedOld = ConnectionLoginSpace("deleted", 4)
        val pendingOld = ConnectionLoginSpace("pending", 6)
        preferences.edit().putBoolean(unreadOld.profileName, false)
            .putBoolean(deletedOld.profileName, true)
            .putBoolean(pendingOld.profileName, true).commit()

        ConnectionWebProfile.cleanIntroductionProfiles(
            storageContext,
            listOf(unreadOld.copy(generation = 3), pendingOld.copy(generation = 7)),
            setOf(unreadOld.profileName, deletedOld.profileName),
        )

        assertFalse(preferences.contains(connectionKey(unreadOld)))
        assertFalse(preferences.contains(connectionKey(deletedOld)))
        assertFalse(preferences.contains(unreadOld.profileName))
        assertFalse(preferences.contains(deletedOld.profileName))
        assertTrue(preferences.getBoolean(pendingOld.profileName, false))
        assertTrue(preferences.getBoolean(connectionKey(pendingOld), false))
    }

    @Test
    fun markersDoNotLeakBetweenConnectionsWithSimilarIdentifiers() {
        val first = ConnectionLoginSpace("connection-a", 3)
        val second = ConnectionLoginSpace("connection-a-extra", 8)
        preferences.edit().putBoolean(first.copy(generation = 1).profileName, true).commit()

        assertFalse(ConnectionWebProfile.hasShownIntroduction(storageContext, second))
        assertFalse(preferences.contains(connectionKey(second)))
        assertTrue(ConnectionWebProfile.hasShownIntroduction(storageContext, first))
        assertFalse(ConnectionWebProfile.hasShownIntroduction(storageContext, second))
        ConnectionWebProfile.markIntroductionShown(storageContext, second)
        assertTrue(ConnectionWebProfile.hasShownIntroduction(storageContext, second))
    }
}
