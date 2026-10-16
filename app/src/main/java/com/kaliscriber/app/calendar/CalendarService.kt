package com.kaliscriber.app.calendar

import android.util.Log
import com.kaliscriber.app.data.ExportSecret
import com.kaliscriber.app.data.SecretStore
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.calendar.CalDavCalendar
import com.kaliscriber.core.calendar.CalendarEvent
import com.kaliscriber.core.calendar.MeetingPrep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * Today's events from the CalDAV calendar set up for export (e.g. Baikal) and, when allowed, the
 * calendars on the phone, so recordings get the event's title and invitees, the home screen can
 * help prepare for the next meeting and meetings can be recorded by themselves. CalDAV events are
 * fetched at most every few minutes; the phone's are read each time, which is quick.
 */
class CalendarService(
    private val http: OkHttpClient,
    private val settings: () -> AppSettings,
    private val secrets: SecretStore,
    private val phone: PhoneCalendar,
) {
    private val lock = Mutex()
    private var cached: List<CalendarEvent> = emptyList()
    private var cachedAt = 0L
    private var cachedFor = ""

    /** False when the last CalDAV read failed, so an empty answer means "unknown" rather than "no events". */
    @Volatile var lastReadOk: Boolean = true
        private set

    /** True when a calendar is set up and calendar lookups are on. */
    val enabled: Boolean
        get() = caldavEnabled || phoneEnabled

    private val caldavEnabled: Boolean
        get() = settings().export.let { it.calendarLookup && it.caldavUrl.isNotBlank() } &&
            !secrets.secret(ExportSecret.CALDAV_PASSWORD).isNullOrBlank()

    /** The phone's calendars are read when the user turned them on and allowed it. */
    val phoneEnabled: Boolean
        get() = settings().phoneCalendar && phone.permitted

    /** Events from a few hours ago until tomorrow; empty when calendars are off or unreachable. */
    suspend fun events(force: Boolean = false): List<CalendarEvent> {
        val remote = caldavEvents(force)
        if (!phoneEnabled) return remote
        val now = System.currentTimeMillis()
        val local = withContext(Dispatchers.IO) { phone.events(now - 12 * HOUR, now + 24 * HOUR) }
        // A CalDAV calendar synced to the phone (e.g. with DAVx⁵) would otherwise show each event twice.
        val seen = remote.map { it.title.trim().lowercase() to it.start }.toSet()
        return (remote + local.filter { (it.title.trim().lowercase() to it.start) !in seen }).sortedBy { it.start }
    }

    private suspend fun caldavEvents(force: Boolean): List<CalendarEvent> = lock.withLock {
        if (!caldavEnabled) {
            lastReadOk = true
            return@withLock emptyList()
        }
        val e = settings().export
        val key = "${e.caldavUrl}|${e.caldavUser}"
        val now = System.currentTimeMillis()
        if (!force && key == cachedFor && now - cachedAt < CACHE_MS) return@withLock cached
        try {
            val calendar = CalDavCalendar(http, e.caldavUrl, e.caldavUser.trim(), secrets.secret(ExportSecret.CALDAV_PASSWORD).orEmpty())
            cached = calendar.events(now - 12 * HOUR, now + 24 * HOUR)
            lastReadOk = true
            cachedAt = now
            cachedFor = key
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Log.w("KaliScriber", "Calendar: couldn't read events: ${ex.message}")
            lastReadOk = false
            // Keep what was read before; try again on the next request.
            if (key != cachedFor) cached = emptyList()
        }
        cached
    }

    /** The event happening now (or starting within a few minutes). */
    suspend fun eventNow(): CalendarEvent? = MeetingPrep.eventAt(events(), System.currentTimeMillis())

    /** The event in progress or the next one today. */
    suspend fun nextEvent(force: Boolean = false): CalendarEvent? = MeetingPrep.next(events(force), System.currentTimeMillis())

    private companion object {
        const val HOUR = 3_600_000L
        const val CACHE_MS = 5 * 60_000L
    }
}
