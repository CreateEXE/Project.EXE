package com.projectexe.ai.tools.impl

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.projectexe.ai.tools.Tool
import com.projectexe.ai.tools.ToolDescriptor
import com.projectexe.ai.tools.ToolResult
import com.projectexe.util.ReminderReceiver
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ReminderTool(private val ctx: Context) : Tool {
    override val descriptor = ToolDescriptor(
        name = "set_reminder",
        description = "Schedule a local reminder notification at an absolute time, or after a delay in minutes.",
        parametersJson = """
            {"type":"object","required":["title"],"properties":{
              "title":{"type":"string"},
              "body":{"type":"string"},
              "at":{"type":"string","description":"ISO-8601 datetime, e.g. 2026-04-25T09:30."},
              "in_minutes":{"type":"integer","minimum":1}
            },"additionalProperties":false}
        """.trimIndent()
    )

    private val isoLocal = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = TimeZone.getDefault() }

    override suspend fun execute(args: JSONObject): ToolResult {
        val title = args.optString("title").trim()
        if (title.isEmpty()) return ToolResult.err("title required")
        val body  = args.optString("body").trim()
        val whenMs: Long = when {
            args.has("in_minutes") -> System.currentTimeMillis() + args.getInt("in_minutes") * 60_000L
            args.optString("at").isNotBlank() -> {
                try { isoLocal.parse(args.getString("at").take(16))!!.time }
                catch (_: Exception) { return ToolResult.err("Bad 'at' format", "I couldn't read that time.") }
            }
            else -> return ToolResult.err("Need 'at' or 'in_minutes'", "Tell me when to remind you.")
        }
        if (whenMs <= System.currentTimeMillis())
            return ToolResult.err("Reminder time is in the past.", "That time has already passed.")

        val am = ctx.getSystemService(AlarmManager::class.java)
            ?: return ToolResult.err("AlarmManager unavailable")

        val id = (whenMs and 0x7fffffff).toInt()
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            putExtra(ReminderReceiver.EXTRA_ID,    id)
            putExtra(ReminderReceiver.EXTRA_TITLE, title)
            putExtra(ReminderReceiver.EXTRA_BODY,  body)
        }
        val pi = PendingIntent.getBroadcast(ctx, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        try {
            if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
            else
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, whenMs, pi)
        }
        val pretty = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(whenMs))
        return ToolResult.ok("Reminder set for $pretty: \"$title\".") {
            put("scheduled_for_ms", whenMs); put("id", id)
        }
    }
}
