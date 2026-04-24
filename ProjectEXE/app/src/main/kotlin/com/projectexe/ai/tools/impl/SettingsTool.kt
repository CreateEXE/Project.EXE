package com.projectexe.ai.tools.impl

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import com.projectexe.ai.tools.Tool
import com.projectexe.ai.tools.ToolDescriptor
import com.projectexe.ai.tools.ToolResult
import org.json.JSONObject

/**
 * Read or modify a small, vetted allow-list of system settings. WRITE_SETTINGS
 * permission is required for `set` operations and is prompted in the Settings screen.
 */
class SettingsTool(private val ctx: Context) : Tool {
    override val descriptor = ToolDescriptor(
        name = "system_setting",
        description = "Read or write a system setting. Supported keys: " +
            "brightness (0-255), screen_off_timeout_seconds, ringer_mode (silent|vibrate|normal). " +
            "Use action=read to query, action=set to change.",
        parametersJson = """
            {"type":"object","required":["action","key"],"properties":{
              "action":{"type":"string","enum":["read","set"]},
              "key":{"type":"string","enum":["brightness","screen_off_timeout_seconds","ringer_mode"]},
              "value":{"description":"Required for action=set."}
            },"additionalProperties":false}
        """.trimIndent()
    )

    override suspend fun execute(args: JSONObject): ToolResult {
        val action = args.optString("action")
        val key    = args.optString("key")
        val cr     = ctx.contentResolver
        val am     = ctx.getSystemService(AudioManager::class.java)

        return try {
            when (action) {
                "read" -> when (key) {
                    "brightness" -> ToolResult.ok("Brightness: ${Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1)}/255.") {
                        put("value", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1))
                    }
                    "screen_off_timeout_seconds" -> {
                        val ms = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, -1)
                        ToolResult.ok("Screen off in ${ms / 1000}s.") { put("value", ms / 1000) }
                    }
                    "ringer_mode" -> {
                        val v = when (am?.ringerMode) {
                            AudioManager.RINGER_MODE_SILENT  -> "silent"
                            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                            AudioManager.RINGER_MODE_NORMAL  -> "normal"
                            else -> "unknown"
                        }
                        ToolResult.ok("Ringer is $v.") { put("value", v) }
                    }
                    else -> ToolResult.err("Unsupported key '$key'")
                }
                "set" -> {
                    if (!Settings.System.canWrite(ctx))
                        return ToolResult.err("WRITE_SETTINGS not granted",
                            "I need permission to modify system settings. Open Settings → Tool permissions to grant.")
                    when (key) {
                        "brightness" -> {
                            val v = args.optInt("value", -1).coerceIn(0, 255)
                            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v)
                            ToolResult.ok("Brightness set to $v.") { put("value", v) }
                        }
                        "screen_off_timeout_seconds" -> {
                            val s = args.optInt("value", -1).coerceIn(15, 30 * 60)
                            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, s * 1000)
                            ToolResult.ok("Screen will turn off after $s seconds.") { put("value", s) }
                        }
                        "ringer_mode" -> {
                            val mode = when (args.optString("value")) {
                                "silent"  -> AudioManager.RINGER_MODE_SILENT
                                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                                "normal"  -> AudioManager.RINGER_MODE_NORMAL
                                else -> return ToolResult.err("value must be silent|vibrate|normal")
                            }
                            am?.ringerMode = mode
                            ToolResult.ok("Ringer mode updated.") { put("value", args.optString("value")) }
                        }
                        else -> ToolResult.err("Unsupported key '$key'")
                    }
                }
                else -> ToolResult.err("action must be 'read' or 'set'")
            }
        } catch (e: SecurityException) {
            ToolResult.err(e.message ?: "permission denied",
                "I don't have permission for that setting yet.")
        } catch (e: Exception) {
            ToolResult.err(e.message ?: "failed", "I couldn't change that setting.")
        }
    }
}
