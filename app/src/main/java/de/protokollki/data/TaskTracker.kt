package de.protokollki.data

/** Aufgaben über alle Besprechungen hinweg. */
object TaskTracker {

    /** Alle offenen Aufgaben aller Besprechungen, neueste Besprechung zuerst. */
    fun openTasks(): List<Pair<Meeting, TaskItem>> =
        allTasks().filter { !it.second.done }

    /** Alle Aufgaben (auch erledigte), neueste Besprechung zuerst. */
    fun allTasks(): List<Pair<Meeting, TaskItem>> =
        MeetingRepository.meetings.value
            .sortedByDescending { it.createdAt }
            .flatMap { m -> m.tasks.map { m to it } }

    fun setDone(meetingId: String, taskId: String, done: Boolean, note: String? = null) {
        MeetingRepository.update(meetingId) { m ->
            m.copy(tasks = m.tasks.map {
                if (it.id == taskId) it.copy(done = done, doneNote = if (done) note else null) else it
            })
        }
    }

    /**
     * Offene Aufgaben aus früheren Besprechungen, die in einer neuen Besprechung
     * abgeglichen werden sollen (max. [limit], aus den letzten [days] Tagen).
     */
    fun candidatesFor(meeting: Meeting, limit: Int = 20, days: Int = 90): List<Pair<Meeting, TaskItem>> {
        val from = meeting.createdAt - days * 24L * 3600 * 1000
        return openTasks()
            .filter { (m, _) -> m.id != meeting.id && m.createdAt < meeting.createdAt && m.createdAt >= from }
            .take(limit)
    }
}
