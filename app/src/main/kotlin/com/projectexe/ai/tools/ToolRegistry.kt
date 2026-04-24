package com.projectexe.ai.tools

import android.util.Log
import org.json.JSONObject

class ToolRegistry(tools: List<Tool>) {
    companion object { private const val TAG = "EXE.ToolRegistry" }
    private val byName = tools.associateBy { it.descriptor.name }
    fun descriptors(): List<ToolDescriptor> = byName.values.map { it.descriptor }

    suspend fun execute(call: ToolCall): ToolResult {
        val tool = byName[call.name] ?: return ToolResult.err(
            "Unknown tool '${call.name}'", "I don't know how to do that yet.")
        val args = try { if (call.argumentsJson.isBlank()) JSONObject() else JSONObject(call.argumentsJson) }
                   catch (e: Exception) {
                       Log.w(TAG, "Bad args for ${call.name}: ${call.argumentsJson}")
                       JSONObject()
                   }
        return try { tool.execute(args) }
        catch (e: Exception) {
            Log.e(TAG, "Tool ${call.name} crashed", e)
            ToolResult.err("${e.javaClass.simpleName}: ${e.message}",
                "Something went wrong with that.")
        }
    }
}
