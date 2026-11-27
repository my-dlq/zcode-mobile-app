package ai.zcode.remote.data.model

enum class TaskReminderType(val key: String) {
    APPROVAL("approval"),
    QUESTION("question"),
    COMPLETED("completed"),
    FAILED("failed"),
}
