package ai.zcode.remote.ui.remote.event

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 每例只访问 UUID 数据库，不读写真实连接、偏好或系统通知。 */
@RunWith(AndroidJUnit4::class)
class TaskNotificationInboxTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val states = listOf("PENDING", "PROCESSING", "DELIVERED", "DISCARDED")

    private fun event(source: String, key: String = "") = TaskEventParser.TaskEvent(
        TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST, "shared-task", "相同标题", "同名设备", key, source,
    )

    private fun withDatabase(action: (String) -> Unit) {
        val name = "notification-inbox-${UUID.randomUUID()}.db"
        try { action(name) } finally { context.deleteDatabase(name) }
    }

    private fun seed(
        db: SQLiteDatabase,
        event: TaskEventParser.TaskEvent,
        key: String,
        state: String,
        createdAt: Long = System.currentTimeMillis(),
    ) {
        assertNotEquals(-1L, db.insertOrThrow("event_inbox", null, ContentValues().apply {
            put("event_key", key)
            put("task_id", event.taskId)
            put("event_type", event.type.name)
            put("task_title", event.taskTitle)
            put("device_name", event.deviceName)
            put("source_id", event.sourceId)
            put("state", state)
            put("lease_until", if (state == "PROCESSING") System.currentTimeMillis() + 60_000 else 0L)
            put("next_attempt_at", 0L)
            put("created_at", createdAt)
        }))
    }

    private fun state(inbox: TaskNotificationInbox, key: String): String? = inbox.readableDatabase.query(
        "event_inbox", arrayOf("state"), "event_key = ?", arrayOf(key), null, null, null,
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    @Test fun blankKeysSeparateSourcesAndSurviveReopen() = withDatabase { name ->
        val a = event("a")
        val b = event("b")
        TaskNotificationInbox(context, name).use { inbox ->
            assertTrue(inbox.enqueue(a))
            assertTrue(inbox.enqueue(b))
            assertFalse(inbox.enqueue(a))
            val first = inbox.claimNext()!!
            assertEquals("a", first.event.sourceId)
            inbox.markDelivered(first.rowId)
            val second = inbox.claimNext()!!
            assertEquals("b", second.event.sourceId)
            inbox.markDiscarded(second.rowId)
        }
        TaskNotificationInbox(context, name).use { inbox ->
            assertFalse(inbox.enqueue(a))
            assertFalse(inbox.enqueue(b))
            assertNull(inbox.claimNext())
            assertEquals(2, inbox.readableDatabase.version)
        }
    }

    @Test fun sameSourceOldFallbackSuppressesEveryStateAfterReopen() {
        for (oldState in states) withDatabase { name ->
            val a = event("a")
            TaskNotificationInbox(context, name).use { inbox ->
                seed(inbox.writableDatabase, a, TaskNotificationIdentity.legacyFallbackKey(a), oldState)
            }
            TaskNotificationInbox(context, name).use { inbox ->
                assertFalse("旧 $oldState 不能因换 key 重放", inbox.enqueue(a))
                assertEquals(oldState, state(inbox, TaskNotificationIdentity.legacyFallbackKey(a)))
                assertNull(state(inbox, TaskNotificationIdentity.stableKey(a)))
                assertTrue("相同设备和任务但不同连接必须独立", inbox.enqueue(event("b")))
            }
        }
    }

    @Test fun unknownSourceHistoryCannotSwallowExplicitSource() {
        for (oldState in states) withDatabase { name ->
            val unknown = event("")
            TaskNotificationInbox(context, name).use { inbox ->
                seed(inbox.writableDatabase, unknown, TaskNotificationIdentity.legacyFallbackKey(unknown), oldState)
                assertTrue(inbox.enqueue(event("a")))
                assertFalse(inbox.enqueue(unknown))
                assertEquals(oldState, state(inbox, TaskNotificationIdentity.legacyFallbackKey(unknown)))
            }
        }
    }

    @Test fun sourceDiscardUsesKeyPrefixOnlyForEmptyIdentityColumn() = withDatabase { name ->
        val same = event("a", "a|shared-task|request|same")
        val conflict = event("b", "a|shared-task|request|conflict")
        val historical = event("", "a|shared-task|request|historical")
        val different = event("ab", "ab|shared-task|request|different")
        TaskNotificationInbox(context, name).use { inbox ->
            for (e in listOf(same, conflict, historical, different)) assertTrue(inbox.enqueue(e))
            inbox.discardSource("a")
            assertEquals("DISCARDED", state(inbox, same.eventKey))
            assertEquals("DISCARDED", state(inbox, historical.eventKey))
            assertEquals("PENDING", state(inbox, conflict.eventKey))
            assertEquals("PENDING", state(inbox, different.eventKey))
        }
    }

    @Test fun resolvedPairsFullKeyAcrossSourcesAndPreservesNewerInteraction() = withDatabase { name ->
        val a = event("a", "a|shared-task|request|old")
        val newerA = event("a", "a|shared-task|request|new")
        val b = event("b", "b|shared-task|request|old")
        TaskNotificationInbox(context, name).use { inbox ->
            assertTrue(inbox.enqueue(a))
            val claimed = inbox.claimNext()!!
            assertTrue(inbox.enqueue(newerA))
            assertTrue(inbox.enqueue(b))
            inbox.discardPendingInteraction(a.copy(type = TaskEventParser.TaskEvent.Type.RESOLVED))
            assertFalse(inbox.isProcessing(claimed.rowId))
            assertEquals("PENDING", state(inbox, newerA.eventKey))
            assertEquals("PENDING", state(inbox, b.eventKey))
            assertFalse(inbox.enqueue(a))
        }
    }

    @Test fun retentionAndProcessingLeaseRemainUnchanged() = withDatabase { name ->
        val recent = event("a", "a|recent")
        val expired = event("a", "a|expired")
        val leased = event("a", "a|leased")
        val old = System.currentTimeMillis() - 91L * 24 * 60 * 60 * 1000
        TaskNotificationInbox(context, name).use { inbox ->
            seed(inbox.writableDatabase, recent, recent.eventKey, "DELIVERED")
            seed(inbox.writableDatabase, expired, expired.eventKey, "DISCARDED", old)
            seed(inbox.writableDatabase, leased, leased.eventKey, "PROCESSING", old)
            assertFalse(inbox.enqueue(recent))
            assertTrue(inbox.enqueue(expired))
            assertFalse("未过期租约不能被90天清理删除", inbox.enqueue(leased))
            inbox.writableDatabase.execSQL("UPDATE event_inbox SET lease_until = 0 WHERE event_key = ?", arrayOf(leased.eventKey))
            val recovered = inbox.claimNext()!!
            assertEquals(leased.eventKey, recovered.event.eventKey)
            val now = System.currentTimeMillis()
            inbox.readableDatabase.rawQuery("SELECT lease_until FROM event_inbox WHERE row_id = ?", arrayOf(recovered.rowId.toString())).use {
                assertTrue(it.moveToFirst())
                assertTrue("继续使用一分钟租约", it.getLong(0) in (now + 55_000)..(now + 60_000))
            }
        }
    }

    @Test fun v1UnknownFallbackMigrationDoesNotBlockExplicitSource() = withDatabase { name ->
        val unknown = event("")
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE event_inbox (row_id INTEGER PRIMARY KEY AUTOINCREMENT, event_key TEXT NOT NULL UNIQUE, task_id TEXT NOT NULL, event_type TEXT NOT NULL, task_title TEXT NOT NULL, device_name TEXT NOT NULL, state TEXT NOT NULL, lease_until INTEGER NOT NULL, next_attempt_at INTEGER NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO event_inbox (event_key, task_id, event_type, task_title, device_name, state, lease_until, next_attempt_at, created_at) VALUES (?, ?, ?, ?, ?, 'DELIVERED', 0, 0, ?)",
                arrayOf(TaskNotificationIdentity.legacyFallbackKey(unknown), unknown.taskId, unknown.type.name, unknown.taskTitle, unknown.deviceName, System.currentTimeMillis()))
            db.version = 1
        }
        TaskNotificationInbox(context, name).use { inbox ->
            assertTrue(inbox.enqueue(event("a")))
            assertFalse(inbox.enqueue(unknown))
            assertEquals(2, inbox.readableDatabase.version)
        }
    }
}
