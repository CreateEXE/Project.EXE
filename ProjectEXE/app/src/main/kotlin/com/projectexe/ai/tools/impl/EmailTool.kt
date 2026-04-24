package com.projectexe.ai.tools.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.projectexe.ai.tools.Tool
import com.projectexe.ai.tools.ToolDescriptor
import com.projectexe.ai.tools.ToolResult
import org.json.JSONObject

/**
 * Opens the user's email app pre-filled with a draft. We never send mail directly —
 * the user always confirms by tapping Send in their own client.
 */
class EmailTool(private val ctx: Context) : Tool {
    override val descriptor = ToolDescriptor(
        name = "compose_email",
        description = "Open the user's email app with a pre-filled draft (to / subject / body). " +
            "The user reviews and sends manually.",
        parametersJson = """
            {"type":"object","required":["to"],"properties":{
              "to":{"type":"string","description":"Recipient email address."},
              "subject":{"type":"string"},
              "body":{"type":"string"},
              "cc":{"type":"string"},
              "bcc":{"type":"string"}
            },"additionalProperties":false}
        """.trimIndent()
    )

    override suspend fun execute(args: JSONObject): ToolResult {
        val to = args.optString("to").trim()
        if (to.isEmpty() || !to.contains("@"))
            return ToolResult.err("'to' must be an email address.", "I need a valid email address.")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$to")).apply {
            args.optString("subject").takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            args.optString("body")   .takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_TEXT, it) }
            args.optString("cc")     .takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_CC,  arrayOf(it)) }
            args.optString("bcc")    .takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_BCC, arrayOf(it)) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(intent)
            ToolResult.ok("Opened your email app with a draft to $to.") { put("to", to) }
        } catch (e: Exception) {
            ToolResult.err(e.message ?: "no email app",
                ctx.getString(com.projectexe.R.string.tool_email_no_app))
        }
    }
}
