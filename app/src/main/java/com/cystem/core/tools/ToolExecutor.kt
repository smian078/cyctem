package com.cystem.core.tools

import org.json.JSONObject

class ToolExecutor(
    private val registry: ToolRegistry,
    private val enabledNames: suspend () -> Set<String>,
) {
    suspend fun execute(name: String, argumentsJson: String): ToolResult {
        if (name !in enabledNames()) {
            return ToolResult.Failure("Tool '$name' is disabled in Settings.")
        }

        val args = runCatching {
            JSONObject(argumentsJson.ifBlank { "{}" })
        }.getOrElse {
            return ToolResult.Failure("Tool arguments are not valid JSON.")
        }

        val definition = registry.definition(name)
            ?: return ToolResult.Failure("Unknown tool: $name")

        if (definition.requiresConfirmation) {
            return ToolResult.ConfirmationRequired(
                confirmationId = java.util.UUID.randomUUID().toString(),
                prompt = buildString {
                    append("CYSTEM wants to run ")
                    append(name)
                    append(". Review the supplied arguments and confirm to continue.")
                    if (args.length() > 0) {
                        append("\n\n")
                        append(args.toString())
                    }
                },
            )
        }

        return registry.execute(name, args)
    }
}
