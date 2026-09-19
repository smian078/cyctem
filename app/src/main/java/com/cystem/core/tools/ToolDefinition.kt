package com.cystem.core.tools

import org.json.JSONArray
import org.json.JSONObject

enum class ToolValueType { STRING, NUMBER, INTEGER, BOOLEAN, OBJECT, ARRAY }

sealed interface ToolValidationResult {
    data object Valid : ToolValidationResult
    data class Invalid(val reason: String) : ToolValidationResult
}

data class ToolProperty(
    val name: String,
    val type: ToolValueType,
    val required: Boolean = false,
    val description: String = "",
    val enumValues: Set<String> = emptySet(),
    val minNumber: Double? = null,
    val maxNumber: Double? = null,
    val maxStringLength: Int? = null,
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val properties: List<ToolProperty>,
    val requiresConfirmation: Boolean = false,
    val timeoutMs: Long = 15_000,
    val enabledByDefault: Boolean = true,
) {
    private val propertyMap = properties.associateBy { it.name }

    fun validate(args: JSONObject, maxBytes: Int = 16_384): ToolValidationResult {
        if (args.toString().length > maxBytes) {
            return ToolValidationResult.Invalid("Arguments exceed size limit")
        }

        val unknown = args.keys().asSequence().firstOrNull { it !in propertyMap }
        if (unknown != null) {
            return ToolValidationResult.Invalid("Unknown argument: " + unknown)
        }

        properties.forEach { property ->
            if (property.required && !args.has(property.name)) {
                throw RequiredToolArgumentMissing(property.name)
            }
            if (!args.has(property.name)) return@forEach

            val value = args.get(property.name)
            val validType = when (property.type) {
                ToolValueType.STRING -> value is String
                ToolValueType.NUMBER -> value is Number
                ToolValueType.INTEGER -> value is Int || value is Long
                ToolValueType.BOOLEAN -> value is Boolean
                ToolValueType.OBJECT -> value is JSONObject
                ToolValueType.ARRAY -> value is JSONArray
            }
            if (!validType) {
                return ToolValidationResult.Invalid(
                    "Argument " + property.name + " has invalid type",
                )
            }
            if (property.enumValues.isNotEmpty() &&
                value is String &&
                value !in property.enumValues
            ) {
                return ToolValidationResult.Invalid(
                    "Argument " + property.name + " has invalid value",
                )
            }
            if (value is String &&
                property.maxStringLength != null &&
                value.length > property.maxStringLength
            ) {
                return ToolValidationResult.Invalid(
                    "Argument " + property.name + " is too long",
                )
            }
            if (value is Number) {
                val number = value.toDouble()
                if (property.minNumber != null && number < property.minNumber) {
                    return ToolValidationResult.Invalid(
                        "Argument " + property.name + " is below minimum",
                    )
                }
                if (property.maxNumber != null && number > property.maxNumber) {
                    return ToolValidationResult.Invalid(
                        "Argument " + property.name + " is above maximum",
                    )
                }
            }
        }

        return ToolValidationResult.Valid
    }

    fun asOpenAiTool(): JSONObject {
        val parameters = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
        val propertiesJson = JSONObject()
        val requiredJson = JSONArray()

        properties.forEach { property ->
            val typeName = when (property.type) {
                ToolValueType.STRING -> "string"
                ToolValueType.NUMBER -> "number"
                ToolValueType.INTEGER -> "integer"
                ToolValueType.BOOLEAN -> "boolean"
                ToolValueType.OBJECT -> "object"
                ToolValueType.ARRAY -> "array"
            }
            val json = JSONObject()
                .put("type", typeName)
                .put("description", property.description)

            if (property.enumValues.isNotEmpty()) {
                json.put("enum", JSONArray(property.enumValues.toList()))
            }
            property.minNumber?.let { json.put("minimum", it) }
            property.maxNumber?.let { json.put("maximum", it) }

            propertiesJson.put(property.name, json)
            if (property.required) requiredJson.put(property.name)
        }

        parameters.put("properties", propertiesJson)
        parameters.put("required", requiredJson)

        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )
    }
}

class RequiredToolArgumentMissing(val propertyName: String) : IllegalArgumentException(
    "Missing required argument: " + propertyName,
)
