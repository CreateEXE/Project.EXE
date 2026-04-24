package com.projectexe.ai.tools.impl

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.projectexe.ai.tools.Tool
import com.projectexe.ai.tools.ToolDescriptor
import com.projectexe.ai.tools.ToolResult
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Opens the calendar app with a pre-filled INSERT intent (user confirms). */
class CalendarTool(private val ctx: Context) : Tool {
    override val descriptor = ToolDescriptor(
        name = "create_calendar_event",
        description = "Create a calendar event by opening the calendar app with the details pre-filled. " +
            "Times must be ISO-8601 (e.g. 2026-04-25T14:00).",
        parametersJson = """
            {"type":"object","required":["title","start"],"properties":{
              "title":{"type":"string"},
              "start":{"type":"string","description":"ISO-8601 start datetime."},
              "end":{"type":"string","description":"ISO-8601 end datetime; default = start + 1h."},
              "location":{"type":"string"},
              "description":{"type":"string"},
              "all_day":{"type":"boolean"}
            },"additionalProperties":false}
        """.trimIndent()
    )

    private val isoLocal: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    private val isoLocalSec: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    private val dateOnly: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private fun parseMillis(s: String): Long? = try {
        when {
            s.length == 10 -> dateOnly.parse(s)?.time
            s.length >= 19 -> isoLocalSec.parse(s.take(19))?.time
            else           -> isoLocal.parse(s.take(16))?.time
        }
    } catch (_: Exception) { null }

    override suspend fun execute(args: JSONObject): ToolResult {
        val title = args.optString("title").trim()
        val start = args.optString("start").trim()
        if (title.isEmpty()) return ToolResult.err("title is required", "I need a title for the event.")
        val startMs = parseMillis(start) ?: return ToolResult.err(
            "start must be ISO-8601", "I couldn't understand that start time.")
        val endMs = args.optString("end").trim().takeIf { it.isNotEmpty() }?.let { parseMillis(it) }
                    ?: (startMs + 3600_000L)

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            args.optString("location").takeIf { it.isNotBlank() }?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            args.optString("description").takeIf { it.isNotBlank() }?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            if (args.optBoolean("all_day", false))
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(intent)
            ToolResult.ok("Opened calendar with \"$title\" pre-filled.") {
                put("title", title); put("start_ms", startMs); put("end_ms", endMs)
            }
        } catch (e: Exception) {
            ToolResult.err(e.message ?: "no calendar app",
                ctx.getString(com.projectexe.R.string.tool_calendar_no_app))
        }
    }
}
