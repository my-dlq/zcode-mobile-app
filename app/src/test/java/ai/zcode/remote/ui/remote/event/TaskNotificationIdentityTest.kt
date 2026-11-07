package ai.zcode.remote.ui.remote.event

import org.junit.Assert.*
import org.junit.Test

class TaskNotificationIdentityTest {
    private fun event(source: String) = TaskEventParser.TaskEvent(
        TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST, "shared-task", "审批", "同名设备",
        sourceId = source,
    )

    @Test fun legacyFallbackDoesNotInventASource() {
        assertEquals("", TaskNotificationIdentity.sourceId("", "legacy|设备|任务|类型|标题"))
        assertEquals("", TaskNotificationIdentity.sourceId("", ""))
        assertEquals("", TaskNotificationIdentity.sourceId("", "opaque-key"))
        assertEquals("a", TaskNotificationIdentity.sourceId("", "a|task|request"))
        assertEquals("explicit", TaskNotificationIdentity.sourceId("explicit", "other|task|request"))
    }

    @Test fun fullSourceTagsIsolateHashCollisions() {
        val a = "Aa"
        val b = "BB"
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(TaskNotificationIdentity.belongsToSource(a, a, "$a|task|request", a))
        assertFalse(TaskNotificationIdentity.belongsToSource(b, b, "$b|task|request", a))
        assertFalse(TaskNotificationIdentity.belongsToSource(b, a, "$a|task|request", a))
    }

    @Test fun legacyUntaggedRequiresUnambiguousPositiveOwnership() {
        assertTrue(TaskNotificationIdentity.belongsToSource(null, "a", "", "a"))
        assertTrue(TaskNotificationIdentity.belongsToSource(null, "", "a|task|request", "a"))
        assertTrue(TaskNotificationIdentity.belongsToSource(null, "a", "legacy|设备|task", "a"))
        assertFalse(TaskNotificationIdentity.belongsToSource(null, "", "legacy|设备|task", "legacy"))
        assertFalse(TaskNotificationIdentity.belongsToSource(null, "", "", "a"))
        assertFalse(TaskNotificationIdentity.belongsToSource(null, "a", "b|task|request", "a"))
        assertFalse(TaskNotificationIdentity.belongsToSource(null, "b", "a|task|request", "a"))
        assertFalse(TaskNotificationIdentity.belongsToSource(null, "", "ab|task|request", "a"))
        assertFalse(TaskNotificationIdentity.belongsToSource("a", "b", "a|task|request", "a"))
        assertFalse(TaskNotificationIdentity.belongsToSource("", "", "", ""))
    }

    @Test fun newEmptyTagPairsOnlyExactUnknownSourceInteraction() {
        assertTrue(TaskNotificationIdentity.matchesInteraction("", "", "A", "", "A"))
        assertFalse(TaskNotificationIdentity.matchesInteraction("", "", "A", "", "B"))
        assertFalse(TaskNotificationIdentity.matchesInteraction("", "", "", "", ""))
        assertFalse(TaskNotificationIdentity.matchesInteraction(null, "", "A", "", "A"))
        assertFalse(TaskNotificationIdentity.matchesInteraction("", "other", "A", "", "A"))
        assertFalse(TaskNotificationIdentity.matchesInteraction("", "", "A", "other", "A"))
        assertFalse(TaskNotificationIdentity.matchesInteraction("", "", "other|task|A", "", "other|task|A"))
        assertTrue(TaskNotificationIdentity.matchesInteraction("a", "a", "a|task|A", "a", "a|task|A"))
        assertFalse(TaskNotificationIdentity.matchesInteraction("a", "a", "a|task|A", "a", "a|task|B"))
        assertFalse("配对兼容不能放宽连接清理", TaskNotificationIdentity.belongsToSource("", "", "A", ""))
    }

    @Test fun emptyKeysAreStableWithinSourceAndDistinctAcrossSources() {
        val a = event("a")
        val b = event("b")
        assertEquals(TaskNotificationIdentity.stableKey(a), TaskNotificationIdentity.stableKey(a.copy()))
        assertNotEquals(TaskNotificationIdentity.stableKey(a), TaskNotificationIdentity.stableKey(b))
        assertNotEquals(TaskNotificationIdentity.stableKey(a), TaskNotificationIdentity.stableKey(event("")))
        assertEquals(TaskNotificationIdentity.legacyFallbackKey(a), TaskNotificationIdentity.legacyFallbackKey(b))
        val keyed = a.copy(eventKey = "a|shared-task|request|1")
        assertEquals(keyed.eventKey, TaskNotificationIdentity.stableKey(keyed))
    }
}
