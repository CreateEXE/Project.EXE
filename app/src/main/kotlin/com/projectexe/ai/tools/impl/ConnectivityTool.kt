package com.projectexe.ai.tools.impl

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.projectexe.ai.tools.Tool
import com.projectexe.ai.tools.ToolDescriptor
import com.projectexe.ai.tools.ToolResult
import org.json.JSONObject

class ConnectivityTool(private val ctx: Context) : Tool {
    override val descriptor = ToolDescriptor(
        name = "get_connectivity_status",
        description = "Check whether the device is online, on Wi-Fi, on cellular, or offline. " +
            "Use this before tools that need internet (weather, web).",
        parametersJson = """{"type":"object","properties":{},"additionalProperties":false}"""
    )

    override suspend fun execute(args: JSONObject): ToolResult {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
            ?: return ToolResult.err("ConnectivityManager unavailable")
        val net = cm.activeNetwork
        val caps = if (net != null) cm.getNetworkCapabilities(net) else null
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                     caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val transport = when {
            caps == null                                                  -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)         -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)     -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)     -> "ethernet"
            else                                                          -> "unknown"
        }
        return ToolResult.ok("Status: ${if (online) "online via $transport" else "offline"}.") {
            put("online", online); put("transport", transport)
        }
    }
}
