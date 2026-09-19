package com.cystem.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterActivity() {
    private val channelName = "cystem/android"
    private var bridge: MethodChannel? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        bridge = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
        bridge?.setMethodCallHandler { call, result ->
            when (call.method) {
                "getInitialShare" -> result.success(readShareIntent(intent))
                "getDeviceInfo" -> result.success(deviceInfo())
                "openApp" -> {
                    val packageName = call.argument<String>("package") ?: ""
                    result.success(openApp(packageName))
                }
                "openUrl" -> {
                    val url = call.argument<String>("url") ?: ""
                    result.success(openUrl(url))
                }
                "shareText" -> {
                    val text = call.argument<String>("text") ?: ""
                    shareText(text)
                    result.success(true)
                }
                "shareFile" -> {
                    val path = call.argument<String>("path") ?: ""
                    result.success(shareFile(path))
                }
                "openSettings" -> {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bridge?.invokeMethod("shareIntent", readShareIntent(intent))
    }

    private fun readShareIntent(incoming: Intent): Map<String, Any?> {
        val text = incoming.getStringExtra(Intent.EXTRA_TEXT)
        val uris = mutableListOf<Uri>()
        incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
        val copied = uris.distinct().mapNotNull { copyUriToCache(it) }
        return mapOf(
            "text" to (text ?: ""),
            "paths" to copied,
            "mime" to (incoming.type ?: "")
        )
    }

    private fun copyUriToCache(uri: Uri): String? {
        return try {
            val extension = contentResolver.getType(uri)?.substringAfterLast('/', "bin") ?: "bin"
            val file = File(cacheDir, "shared_${System.currentTimeMillis()}.$extension")
            contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun deviceInfo(): Map<String, Any> = mapOf(
        "manufacturer" to Build.MANUFACTURER,
        "brand" to Build.BRAND,
        "model" to Build.MODEL,
        "androidVersion" to Build.VERSION.RELEASE,
        "sdk" to Build.VERSION.SDK_INT,
        "supportedAbis" to Build.SUPPORTED_ABIS.toList()
    )

    private fun openApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
            startActivity(intent)
            true
        } catch (_: Exception) { false }
    }

    private fun openUrl(value: String): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
            true
        } catch (_: Exception) { false }
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share from CYSTEM"))
    }

    private fun shareFile(path: String): Boolean {
        return try {
            val file = File(path)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(uri) ?: "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share backup"))
            true
        } catch (_: Exception) { false }
    }
}
