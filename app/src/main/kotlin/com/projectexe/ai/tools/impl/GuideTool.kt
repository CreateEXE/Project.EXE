package com.projectexe.ai.tools.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.projectexe.ai.tools.Tool
import com.projectexe.ai.tools.ToolDescriptor
import com.projectexe.ai.tools.ToolResult
import org.json.JSONObject

/**
 * "Guide the user" — opens a relevant Android Settings screen so the user can
 * change something themselves. Safer than poking values directly.
 */
class GuideTool(private val ctx: Context) : Tool {
    override val descriptor = ToolDescriptor(
        name = "open_settings_screen",
        description = "Open a specific Android settings screen so the user can adjust it. " +
            "Available targets: wifi, bluetooth, mobile_data, display, battery, sound, " +
            "location, apps, app_details, accessibility, notifications, date_time.",
        parametersJson = """
            {"type":"object","required":["target"],"properties":{
              "target":{"type":"string"}
            },"additionalProperties":false}
        """.trimIndent()
    )

    override suspend fun execute(args: JSONObject): ToolResult {
        val target = args.optString("target").lowercase()
        val action = when (target) {
            "wifi"          -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth"     -> Settings.ACTION_BLUETOOTH_SETTINGS
            "mobile_data"   -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "display"       -> Settings.ACTION_DISPLAY_SETTINGS
            "battery"       -> "android.settings.BATTERY_SAVER_SETTINGS"
            "sound"         -> Settings.ACTION_SOUND_SETTINGS
            "location"      -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps"          -> Settings.ACTION_APPLICATION_SETTINGS
            "app_details"   -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "notifications" -> Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            "date_time"     -> Settings.ACTION_DATE_SETTINGS
            else            -> return ToolResult.err("Unknown target '$target'",
                                "I don't know that settings page.")
        }
        val intent = Intent(action).apply {
            if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                data = Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(intent)
            ToolResult.ok("Opened the $target settings page for you.") { put("target", target) }
        } catch (e: Exception) {
            ToolResult.err(e.message ?: "open failed", "I couldn't open that settings page.")
        }
    }
}
