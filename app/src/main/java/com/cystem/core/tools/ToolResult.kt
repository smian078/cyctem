package com.cystem.core.tools

sealed interface ToolResult {
    data class Success(val output: String) : ToolResult
    data class Failure(val message: String) : ToolResult
    data class ConfirmationRequired(
        val confirmationId: String,
        val prompt: String,
    ) : ToolResult
}
