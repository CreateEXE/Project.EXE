package com.projectexe.ai.tools.impl

import android.content.Context
import android.content.Intent
import android.os.Build
import com.projectexe.ai.tools.Tool
import com.projectexe.ai.tools.ToolDescriptor
import com.projectexe.ai.tools.ToolResult
import com.projectexe.focus.FocusService
import org.json.JSONObject

class FocusTool(private val ctx: Context) : Tool {
    override val descriptor = ToolDescriptor(
        name = "start_focus_session",
        description = "Start (or stop) a focus / pomodoro timer with a foreground notification.",
        parametersJson = """
            {"type":"object","properties":{
              "minutes":{"type":"integer","minimum":1,"maximum":240,"default":25},
              "label":{"type":"string"},
              "stop":{"type":"boolean","description":"If true, stop the running session instead."}
            },"additionalProperties":false}
        """.trimIndent()
    )

    override suspend fun execute(args: JSONObject): ToolResult {
        return if (args.optBoolean("stop", false)) {
            try {
                ctx.startService(Intent(ctx, FocusService::class.java).setAction(FocusService.ACTION_STOP))
                ToolResult.ok("Focus session stopped.")
            } catch (e: Exception) { ToolResult.err(e.message ?: "stop failed") }
        } else {
            val minutes = args.optInt("minutes", 25).coerceIn(1, 240)
            val intent = Intent(ctx, FocusService::class.java).apply {
                action = FocusService.ACTION_START
                putExtra(FocusService.EXTRA_MINUTES, minutes)
                putExtra(FocusService.EXTRA_LABEL, args.optString("label"))
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
                else ctx.startService(intent)
                ToolResult.ok("Focus session started — $minutes minutes.") {
                    put("minutes", minutes); put("ends_at_ms", System.currentTimeMillis() + minutes * 60_000L)
                }
            } catch (e: Exception) { ToolResult.err(e.message ?: "could not start") }
        }
    }
}
