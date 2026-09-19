package com.cystem.core.tools

import org.json.JSONObject

class ToolRegistry(
    private val entries: Map<String, Entry>,
) {
    data class Entry(
        val definition: ToolDefinition,
        val execute: (JSONObject) -> ToolResult,
    )

    fun definitions(enabledNames: Set<String>): List<JSONObject> =
        enabledNames.mapNotNull { entries[it]?.definition?.asOpenAiTool() }

    fun execute(name: String, args: JSONObject): ToolResult {
        val entry = entries[name] ?: return ToolResult.Failure("Unknown tool: $name")
        return when (val validation = entry.definition.validate(args)) {
            ToolValidationResult.Valid -> runCatching { entry.execute(args) }
                .getOrElse { ToolResult.Failure(it.message ?: "Tool execution failed.") }
            is ToolValidationResult.Invalid -> ToolResult.Failure(validation.reason)
        }
    }

    fun definition(name: String): ToolDefinition? = entries[name]?.definition

    fun isConfirmationRequired(name: String): Boolean =
        entries[name]?.definition?.requiresConfirmation == true
}
