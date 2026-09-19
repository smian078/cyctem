package com.cystem.core.tools

import android.app.Activity
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import java.util.Locale

class PhoneActionDispatcher {
    @Volatile
    private var activity: Activity? = null

    fun attach(activity: Activity) {
        this.activity = activity
    }

    fun detach(activity: Activity) {
        if (this.activity === activity) this.activity = null
    }

    fun openSettings(): ToolResult {
        val host = activity ?: return ToolResult.Failure("CYSTEM is not in the foreground.")
        return runCatching {
            host.startActivity(Intent(Settings.ACTION_SETTINGS))
            ToolResult.Success("Opened Android Settings.")
        }.getOrElse { ToolResult.Failure("Unable to open Android Settings.") }
    }

    fun openSupportedApp(packageName: String): ToolResult {
        val host = activity ?: return ToolResult.Failure("CYSTEM is not in the foreground.")
        val intent = host.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ToolResult.Failure("No launchable app was found for $packageName.")
        return runCatching {
            host.startActivity(intent)
            ToolResult.Success("Opened $packageName.")
        }.getOrElse { ToolResult.Failure("Unable to open $packageName.") }
    }

    fun setTimer(seconds: Int, label: String?): ToolResult {
        val host = activity ?: return ToolResult.Failure("CYSTEM is not in the foreground.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        label?.takeIf(String::isNotBlank)?.let {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, it)
        }
        return runCatching {
            host.startActivity(intent)
            ToolResult.Success("Timer prepared for $seconds seconds.")
        }.getOrElse { ToolResult.Failure("Unable to open the timer action.") }
    }

    fun setAlarm(hour: Int, minute: Int, message: String?): ToolResult {
        val host = activity ?: return ToolResult.Failure("CYSTEM is not in the foreground.")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
        message?.takeIf(String::isNotBlank)?.let {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, it)
        }
        return runCatching {
            host.startActivity(intent)
            ToolResult.Success(
                String.format(Locale.US, "Alarm prepared for %02d:%02d.", hour, minute),
            )
        }.getOrElse { ToolResult.Failure("Unable to open the alarm action.") }
    }

    fun shareText(text: String): ToolResult {
        val host = activity ?: return ToolResult.Failure("CYSTEM is not in the foreground.")
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        return runCatching {
            host.startActivity(Intent.createChooser(intent, "Share with"))
            ToolResult.Success("Share sheet opened.")
        }.getOrElse { ToolResult.Failure("Unable to open the share sheet.") }
    }
}
