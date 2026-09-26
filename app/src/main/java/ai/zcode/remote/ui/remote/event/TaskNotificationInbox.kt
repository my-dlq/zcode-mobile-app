package ai.zcode.remote.ui.remote.event

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 通知事件的持久化信箱。远端流量可能重放、应用也可能在发布通知前被系统冻结，
 * 因此不能以“收到一次回调就直接 notify”为唯一保障。每个 eventKey 只会入库一次，
 * 已投递记录会保留一段时间，避免用户划掉的历史通知在重连快照中再次出现。
 */
internal class TaskNotificationInbox(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    data class Record(
        val rowId: Long,
        val event: TaskEventParser.TaskEvent,
    )

    @Synchronized
    fun enqueue(event: TaskEventParser.TaskEvent): Boolean {
        val now = System.currentTimeMillis()
        writableDatabase.delete(
            TABLE,
            "$COL_STATE IN (?, ?) AND $COL_CREATED_AT < ?",
            arrayOf(STATE_DELIVERED, STATE_DISCARDED, (now - RETENTION_MS).toString()),
        )
        val values = ContentValues().apply {
            put(COL_EVENT_KEY, stableKey(event))
            put(COL_TASK_ID, event.taskId)
            put(COL_TYPE, event.type.name)
            put(COL_TITLE, event.taskTitle)
            put(COL_DEVICE, event.deviceName)
            put(COL_STATE, STATE_PENDING)
            put(COL_LEASE_UNTIL, 0L)
            put(COL_NEXT_ATTEMPT_AT, now)
            put(COL_CREATED_AT, now)
        }
        return writableDatabase.insertWithOnConflict(
            TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
    }

    /** 事务内领取最早的可消费事件；处理租约过期的记录会自动回到待消费状态。 */
    @Synchronized
    fun claimNext(): Record? {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE $TABLE SET $COL_STATE = ?, $COL_LEASE_UNTIL = 0 " +
                    "WHERE $COL_STATE = ? AND $COL_LEASE_UNTIL < ?",
                arrayOf(STATE_PENDING, STATE_PROCESSING, now),
            )
            val cursor = db.query(
                TABLE,
                arrayOf(COL_ROW_ID, COL_TASK_ID, COL_TYPE, COL_TITLE, COL_DEVICE, COL_EVENT_KEY),
                "$COL_STATE = ? AND $COL_NEXT_ATTEMPT_AT <= ?",
                arrayOf(STATE_PENDING, now.toString()),
                null, null, "$COL_ROW_ID ASC", "1",
            )
            cursor.use {
                if (!it.moveToFirst()) return null
                val rowId = it.getLong(0)
                val changed = db.update(
                    TABLE,
                    ContentValues().apply {
                        put(COL_STATE, STATE_PROCESSING)
                        put(COL_LEASE_UNTIL, now + LEASE_MS)
                    },
                    "$COL_ROW_ID = ? AND $COL_STATE = ?",
                    arrayOf(rowId.toString(), STATE_PENDING),
                )
                if (changed != 1) return null
                val type = runCatching {
                    TaskEventParser.TaskEvent.Type.valueOf(it.getString(2))
                }.getOrNull() ?: return null
                db.setTransactionSuccessful()
                return Record(
                    rowId,
                    TaskEventParser.TaskEvent(
                        type = type,
                        taskId = it.getString(1).orEmpty(),
                        taskTitle = it.getString(3).orEmpty(),
                        deviceName = it.getString(4).orEmpty(),
                        eventKey = it.getString(5).orEmpty(),
                    ),
                )
            }
        } finally {
            db.endTransaction()
        }
    }

    /** 返回下一次需要唤醒消费者的等待时间；null 表示信箱已空。 */
    @Synchronized
    fun nextWakeDelayMs(): Long? {
        val now = System.currentTimeMillis()
        val cursor = readableDatabase.rawQuery(
            "SELECT MIN(CASE WHEN $COL_STATE = ? THEN $COL_NEXT_ATTEMPT_AT " +
                "ELSE $COL_LEASE_UNTIL END) FROM $TABLE " +
                "WHERE $COL_STATE IN (?, ?)",
            arrayOf(STATE_PENDING, STATE_PENDING, STATE_PROCESSING),
        )
        cursor.use {
            if (!it.moveToFirst() || it.isNull(0)) return null
            return (it.getLong(0) - now).coerceAtLeast(0L)
        }
    }

    @Synchronized
    fun markDelivered(rowId: Long) = updateState(rowId, STATE_DELIVERED, 0L)

    @Synchronized
    fun markDiscarded(rowId: Long) = updateState(rowId, STATE_DISCARDED, 0L)

    @Synchronized
    fun markRetry(rowId: Long, delayMs: Long) {
        updateState(rowId, STATE_PENDING, System.currentTimeMillis() + delayMs)
    }

    /**
     * resolved 只应撤回与之对应的那一次交互，绝不能按 taskId 全量清理。
     * 同一任务可能连续出现多次审批/提问，若旧 resolved 按 taskId 删除所有
     * 尚未投递的 pending，刚入队的新审批会被一并误伤，表现为“几次后不再通知”。
     */
    @Synchronized
    fun discardPendingInteraction(eventKey: String) {
        if (eventKey.isBlank()) return
        writableDatabase.update(
            TABLE,
            ContentValues().apply {
                put(COL_STATE, STATE_DISCARDED)
                put(COL_LEASE_UNTIL, 0L)
            },
            "$COL_EVENT_KEY = ? AND $COL_STATE IN (?, ?)",
            arrayOf(eventKey, STATE_PENDING, STATE_PROCESSING),
        )
    }

    private fun updateState(rowId: Long, state: String, nextAttemptAt: Long) {
        writableDatabase.update(
            TABLE,
            ContentValues().apply {
                put(COL_STATE, state)
                put(COL_LEASE_UNTIL, 0L)
                put(COL_NEXT_ATTEMPT_AT, nextAttemptAt)
            },
            "$COL_ROW_ID = ?",
            arrayOf(rowId.toString()),
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "$COL_ROW_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COL_EVENT_KEY TEXT NOT NULL UNIQUE, " +
                "$COL_TASK_ID TEXT NOT NULL, " +
                "$COL_TYPE TEXT NOT NULL, " +
                "$COL_TITLE TEXT NOT NULL, " +
                "$COL_DEVICE TEXT NOT NULL, " +
                "$COL_STATE TEXT NOT NULL, " +
                "$COL_LEASE_UNTIL INTEGER NOT NULL, " +
                "$COL_NEXT_ATTEMPT_AT INTEGER NOT NULL, " +
                "$COL_CREATED_AT INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX idx_task_notification_inbox_ready ON $TABLE " +
                "($COL_STATE, $COL_NEXT_ATTEMPT_AT, $COL_ROW_ID)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun stableKey(event: TaskEventParser.TaskEvent): String =
        event.eventKey.ifBlank {
            // 旧格式的显式事件缺少交互 ID 时仍需跨重连幂等；会话状态事件会带 generation。
            "legacy|${event.deviceName}|${event.taskId}|${event.type}|${event.taskTitle}"
        }

    private companion object {
        const val DATABASE_NAME = "zcode_task_notifications.db"
        const val DATABASE_VERSION = 1
        const val TABLE = "event_inbox"
        const val COL_ROW_ID = "row_id"
        const val COL_EVENT_KEY = "event_key"
        const val COL_TASK_ID = "task_id"
        const val COL_TYPE = "event_type"
        const val COL_TITLE = "task_title"
        const val COL_DEVICE = "device_name"
        const val COL_STATE = "state"
        const val COL_LEASE_UNTIL = "lease_until"
        const val COL_NEXT_ATTEMPT_AT = "next_attempt_at"
        const val COL_CREATED_AT = "created_at"
        const val STATE_PENDING = "PENDING"
        const val STATE_PROCESSING = "PROCESSING"
        const val STATE_DELIVERED = "DELIVERED"
        const val STATE_DISCARDED = "DISCARDED"
        const val LEASE_MS = 60_000L
        const val RETENTION_MS = 90L * 24L * 60L * 60L * 1000L
    }
}
