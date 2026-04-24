package com.projectexe.ai.tools.impl

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.projectexe.ai.tools.Tool
import com.projectexe.ai.tools.ToolDescriptor
import com.projectexe.ai.tools.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight on-device "threat sweep". Not a real AV — just heuristics:
 *   * apps from unknown installer sources
 *   * apps holding multiple sensitive runtime permissions
 *   * apps requesting accessibility / device-admin / overlay together (a common malware signal)
 */
class VirusScanTool(private val ctx: Context) : Tool {
    override val descriptor = ToolDescriptor(
        name = "scan_for_threats",
        description = "Scan installed apps for suspicious permission combinations and flag potential risks. " +
            "Returns a small list of flagged packages and explanations.",
        parametersJson = """{"type":"object","properties":{},"additionalProperties":false}"""
    )

    private val SUSPICIOUS_PERMS = setOf(
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.BIND_DEVICE_ADMIN",
        "android.permission.READ_SMS",
        "android.permission.SEND_SMS",
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_CONTACTS",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.REQUEST_INSTALL_PACKAGES"
    )
    private val TRUSTED_INSTALLERS = setOf(
        "com.android.vending", "com.google.android.feedback",
        "com.amazon.venezia", "com.huawei.appmarket",
        "com.samsung.android.app.omcagent", "com.sec.android.app.samsungapps",
        null  // pre-installed
    )

    override suspend fun execute(args: JSONObject): ToolResult {
        val pm = ctx.packageManager
        val installed: List<PackageInfo> = try {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) {
            return ToolResult.err(e.message ?: "scan failed", "Couldn't enumerate apps for scanning.")
        }

        val flagged = JSONArray()
        var checked = 0
        for (info in installed) {
            checked++
            val app = info.applicationInfo ?: continue
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            val perms = info.requestedPermissions?.toSet().orEmpty()
            val risky = perms.intersect(SUSPICIOUS_PERMS)
            val installer = try {
                if (android.os.Build.VERSION.SDK_INT >= 30)
                    pm.getInstallSourceInfo(info.packageName).installingPackageName
                else @Suppress("DEPRECATION") pm.getInstallerPackageName(info.packageName)
            } catch (_: Exception) { null }

            val reasons = mutableListOf<String>()
            if (risky.size >= 3) reasons += "${risky.size} sensitive permissions"
            if (installer !in TRUSTED_INSTALLERS) reasons += "installed from ${installer ?: "unknown source"}"
            if ("android.permission.BIND_ACCESSIBILITY_SERVICE" in risky &&
                "android.permission.SYSTEM_ALERT_WINDOW" in risky)
                reasons += "accessibility + overlay (high risk combo)"

            if (reasons.isNotEmpty()) {
                flagged.put(JSONObject().apply {
                    put("package", info.packageName)
                    put("label", app.loadLabel(pm).toString())
                    put("reasons", JSONArray(reasons))
                })
                if (flagged.length() >= 12) break
            }
        }

        val summary = if (flagged.length() == 0)
            "Scanned $checked apps. Nothing suspicious."
        else
            "Scanned $checked apps. Flagged ${flagged.length()} for review."
        return ToolResult.ok(summary) {
            put("checked", checked); put("flagged", flagged)
        }
    }
}
