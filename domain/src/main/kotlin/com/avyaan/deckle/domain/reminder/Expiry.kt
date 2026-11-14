package com.avyaan.deckle.domain.reminder

import com.avyaan.deckle.domain.common.Clock
import com.avyaan.deckle.domain.document.DocumentRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * NV-32: reminders 90, 30 and 7 days before a document expires, and on the day itself.
 *
 * Each document remembers the last stage it was reminded at, so every stage fires once even if the daily
 * check runs late or several times. Setting a date marks the stage it already falls in as done: picking a
 * date 20 days away doesn't notify at once (the user is looking at it); the 7-day reminder still comes.
 */
public object ExpiryPolicy {
    /** Days before expiry, largest first. 0 = the day it expires. */
    public val STAGES: List<Int> = listOf(90, 30, 7, 0)
    /** "Expiring soon" collection and the badge on cards. */
    public const val SOON_DAYS: Int = 90

    /** Whole days from today until the expiry day; negative once it has passed. */
    public fun daysLeft(expiresAt: Instant, now: Instant, zone: ZoneId): Long =
        ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), expiresAt.atZone(zone).toLocalDate())

    /** The stage [daysLeft] falls in (the smallest stage it is within), or null when further away than all. */
    public fun stageFor(daysLeft: Long): Int? = STAGES.lastOrNull { daysLeft <= it }

    /** The stage to notify now, or null. A stage at or above [lastNotified] was already sent. */
    public fun due(daysLeft: Long, lastNotified: Int?): Int? {
        val stage = stageFor(daysLeft) ?: return null
        return stage.takeIf { lastNotified == null || it < lastNotified }
    }

    public fun startOfDay(date: LocalDate, zone: ZoneId): Instant = date.atStartOfDay(zone).toInstant()
}

/** Sets or clears a document's expiry date. */
public class SetExpiryUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val clock: Clock,
) {
    public suspend operator fun invoke(documentId: String, date: LocalDate?) {
        if (date == null) {
            documents.setExpiry(documentId, null, null)
            return
        }
        val zone = clock.zone()
        val at = ExpiryPolicy.startOfDay(date, zone)
        val already = ExpiryPolicy.stageFor(ExpiryPolicy.daysLeft(at, clock.now(), zone))
        documents.setExpiry(documentId, at, already)
    }
}

/** Posts one reminder. Implemented with a local notification; nothing leaves the device. */
public interface ReminderNotifier {
    public fun notifyExpiry(documentId: String, title: String, daysLeft: Long)
}

/** The daily check. Returns how many reminders were posted. */
public class CheckExpiryRemindersUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val notifier: ReminderNotifier,
    private val clock: Clock,
) {
    public suspend operator fun invoke(): Int {
        val now = clock.now()
        val zone = clock.zone()
        var posted = 0
        for (doc in documents.withExpiry()) {
            val expires = doc.expiresAt ?: continue
            val left = ExpiryPolicy.daysLeft(expires, now, zone)
            val stage = ExpiryPolicy.due(left, doc.expiryNotifiedDays) ?: continue
            notifier.notifyExpiry(doc.id, doc.title, left)
            documents.setExpiryNotified(doc.id, stage)
            posted++
        }
        return posted
    }
}
