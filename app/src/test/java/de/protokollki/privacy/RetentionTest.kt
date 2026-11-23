package de.protokollki.privacy

import de.protokollki.data.Meeting
import de.protokollki.data.MeetingStatus
import de.protokollki.data.Segment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test fun zeroOrNegativeDaysNeverExpire() {
        assertFalse(isExpired(now - 1000 * day, now, 0))
        assertFalse(isExpired(now - 1000 * day, now, -5))
    }

    @Test fun boundaryIsInclusive() {
        assertFalse(isExpired(now - 30 * day + 1, now, 30))
        assertTrue(isExpired(now - 30 * day, now, 30))
        assertTrue(isExpired(now - 31 * day, now, 30))
    }

    @Test fun oneDay() {
        assertFalse(isExpired(now - day + 60_000, now, 1))
        assertTrue(isExpired(now - day, now, 1))
    }

    @Test fun futureOrMissingTimestampsNeverExpire() {
        assertFalse(isExpired(now + day, now, 1))
        assertFalse(isExpired(0L, now, 1))
    }

    @Test fun largeValuesDoNotOverflow() {
        assertFalse(isExpired(now - day, now, 365_000))
        assertTrue(isExpired(now - 3651 * day, now, 3650))
    }

    private fun meeting(
        status: MeetingStatus = MeetingStatus.DONE,
        ageDays: Long = 40,
        segments: Boolean = true,
        audioDeleted: Boolean = false,
    ) = Meeting(
        id = "m", title = "t", createdAt = now - ageDays * day, audioFile = "/x/audio.wav", status = status,
        segments = if (segments) listOf(Segment(0, 0f, 1f, "Hallo")) else emptyList(),
        audioDeleted = audioDeleted,
    )

    @Test fun deletesOnlyProcessedExpiredAudio() {
        assertTrue(shouldDeleteAudio(meeting(), now, 30))
        assertFalse(shouldDeleteAudio(meeting(ageDays = 10), now, 30))
        assertFalse(shouldDeleteAudio(meeting(), now, 0))
        assertFalse(shouldDeleteAudio(meeting(audioDeleted = true), now, 30))
    }

    @Test fun neverTouchesUnprocessedOrActiveMeetings() {
        for (s in MeetingStatus.values().filter { it != MeetingStatus.DONE }) {
            assertFalse(s.name, shouldDeleteAudio(meeting(status = s), now, 30))
        }
        assertFalse(shouldDeleteAudio(meeting(segments = false), now, 30))
    }
}
