package com.kaliscriber.app.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.kaliscriber.core.calendar.CalendarEvent

/** Events from the calendars on this phone (Google, Exchange, DAVx⁵ and others), through Android's calendar provider. */
class PhoneCalendar(private val context: Context) {

    val permitted: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Occurrences overlapping [from]..[to] in visible calendars, without cancelled or declined ones. */
    fun events(from: Long, to: Long): List<CalendarEvent> {
        if (!permitted) return emptyList()
        return try {
            read(from, to)
        } catch (e: Exception) {
            Log.w("KaliScriber", "Phone calendar: couldn't read events: ${e.message}")
            emptyList()
        }
    }

    private fun read(from: Long, to: Long): List<CalendarEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val columns = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.VISIBLE,
        )
        val out = mutableListOf<CalendarEvent>()
        context.contentResolver.query(uri, columns, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(8) == 0) continue
                if (c.getInt(6) == CalendarContract.Events.STATUS_CANCELED) continue
                if (c.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                val eventId = c.getLong(0)
                out += CalendarEvent(
                    uid = "phone:$eventId",
                    title = c.getString(1)?.trim().orEmpty().ifEmpty { "Meeting" },
                    start = c.getLong(2),
                    end = c.getLong(3),
                    attendees = attendees(eventId),
                    allDay = c.getInt(4) != 0,
                    location = c.getString(5)?.takeIf { it.isNotBlank() },
                )
            }
        }
        return out
    }

    /** Names (or addresses) of the people invited, without those who declined. */
    private fun attendees(eventId: Long): List<String> {
        val columns = arrayOf(
            CalendarContract.Attendees.ATTENDEE_NAME,
            CalendarContract.Attendees.ATTENDEE_EMAIL,
            CalendarContract.Attendees.ATTENDEE_STATUS,
        )
        val out = mutableListOf<String>()
        CalendarContract.Attendees.query(context.contentResolver, eventId, columns)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(2) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                val name = c.getString(0)?.trim().orEmpty().ifEmpty { c.getString(1)?.trim().orEmpty() }
                if (name.isNotEmpty()) out += name
            }
        }
        return out.distinctBy { it.lowercase() }
    }
}
