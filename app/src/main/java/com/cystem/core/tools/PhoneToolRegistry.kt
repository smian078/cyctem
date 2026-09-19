package com.cystem.core.tools

import android.content.Context
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject
import java.time.Instant

class PhoneToolRegistry(
    context: Context,
    private val dispatcher: PhoneActionDispatcher,
) {
    private val appContext = context.applicationContext

    val registry = ToolRegistry(
        mapOf(
            "device_info" to ToolRegistry.Entry(
                definition = ToolDefinition(
                    name = "device_info",
                    description = "Read basic Android device information without changing device state.",
                    properties = emptyList(),
                ),
                execute = { deviceInfo() },
            ),
            "battery_status" to ToolRegistry.Entry(
                definition = ToolDefinition(
                    name = "battery_status",
                    description = "Read current battery percentage.",
                    properties = emptyList(),
                ),
                execute = { batteryStatus() },
            ),
            "connectivity" to ToolRegistry.Entry(
                definition = ToolDefinition(
                    name = "connectivity",
                    description = "Read whether the device currently has an active network.",
                    properties = emptyList(),
                ),
                execute = { connectivity() },
            ),
            "current_time" to ToolRegistry.Entry(
                definition = ToolDefinition(
                    name = "current_time",
                    description = "Read the device clock as an ISO-8601 timestamp.",
                    properties = emptyList(),
                ),
                execute = { ToolResult.Success(Instant.now().toString()) },
            ),
            "open_supported_app" to ToolRegistry.Entry(
                definition = ToolDefinition(
                    name = "open_supported_app",
                    description = "Open a specific installed Android app by package name.",
                    properties = listOf(
                        ToolProperty(
                            name = "packageName",
                            type = ToolValueType.STRING,
                            required = true,
                            maxStringLength = 200,
                            description = "Android package name, for example com.android.settings.",
                        ),
                    ),
                    requiresConfirmation = true,
                ),
                execute = { args ->
                    dispatcher.openSupportedApp(args.getString("packageName"))
                },
            ),
            "open_settings" to ToolRegistry.Entry(
                definition = ToolDefinition(
                    name = "open_settings",
                    description = "Open the Android Settings application.",
                    properties = listOf(
                        ToolProperty(
                            name = "section",
                            type = ToolValueType.STRING,
                            required = false,
                            maxStringLength = 120,
                            description = "Optional section hint. CYSTEM currently opens the main Settings screen.",
                        ),
                    ),
                ),
                execute = {
                    dispatcher.openSettings()
                },
            ),
            "set_timer" to ToolRegistry.Entry(
                definition = ToolDefinition(
                    name = "set_timer",
                    description = "Prepare an Android timer. The user must confirm before the side effect.",
                    properties = listOf(
                        ToolProperty(
                            name = "seconds",
                            type = ToolValueType.INTEGER,
                            required = true,
                            minNumber = 1.0,
                            maxNumber = 86_400.0,
                            description = "Timer duration in seconds.",
                        ),
                        ToolProperty(
                            name = "label",
                            type = ToolValueType.STRING,
                            maxStringLength = 120,
                            description = "Optional timer label.",
                        ),
                    ),
                    requiresConfirmation = true,
                ),
                execute = { args ->
                    dispatcher.setTimer(
                        seconds = args.getInt("seconds"),
                        label = args.optString("label").ifBlank { null },
                    )
                },
            ),
            "set_alarm" to ToolRegistry.Entry(
                definition = ToolDefinition(
                    name = "set_alarm",
                    description = "Prepare an Android alarm. The user must confirm before the side effect.",
                    properties = listOf(
                        ToolProperty(
                            name = "hour",
                            type = ToolValueType.INTEGER,
                            required = true,
                            minNumber = 0.0,
                            maxNumber = 23.0,
                            description = "Alarm hour in 24-hour time.",
                        ),
                        ToolProperty(
                            name = "minute",
                            type = ToolValueType.INTEGER,
                            required = true,
                            minNumber = 0.0,
                            maxNumber = 59.0,
                            description = "Alarm minute.",
                        ),
                        ToolProperty(
                            name = "message",
                            type = ToolValueType.STRING,
                            maxStringLength = 120,
                            description = "Optional alarm label.",
                        ),
                    ),
                    requiresConfirmation = true,
                ),
                execute = { args ->
                    dispatcher.setAlarm(
                        hour = args.getInt("hour"),
                        minute = args.getInt("minute"),
                        message = args.optString("message").ifBlank { null },
                    )
                },
            ),
            "share_text" to ToolRegistry.Entry(
                definition = ToolDefinition(
                    name = "share_text",
                    description = "Open the Android share sheet with user-specified text.",
                    properties = listOf(
                        ToolProperty(
                            name = "text",
                            type = ToolValueType.STRING,
                            required = true,
                            maxStringLength = 4_000,
                            description = "Text to share.",
                        ),
                    ),
                    requiresConfirmation = true,
                ),
                execute = { args ->
                    dispatcher.shareText(args.getString("text"))
                },
            ),
        ),
    )

    fun executeConfirmed(
        name: String,
        arguments: JSONObject,
    ): ToolResult = registry.execute(name, arguments)

    private fun deviceInfo(): ToolResult.Success =
        ToolResult.Success(
            "Manufacturer=" + Build.MANUFACTURER +
                "; model=" + Build.MODEL +
                "; Android " + Build.VERSION.RELEASE +
                " (API " + Build.VERSION.SDK_INT + ")",
        )

    private fun batteryStatus(): ToolResult.Success {
        val manager = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val percentage = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return ToolResult.Success(
            if (percentage in 0..100) {
                "Battery: " + percentage + "%"
            } else {
                "Battery percentage unavailable."
            },
        )
    }

    private fun connectivity(): ToolResult.Success {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return ToolResult.Success(
            if (manager.activeNetwork != null) {
                "An active network is available."
            } else {
                "No active network is available."
            },
        )
    }
}
