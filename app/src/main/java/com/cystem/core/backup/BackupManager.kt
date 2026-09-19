package com.cystem.core.backup

import android.content.Context
import android.net.Uri
import com.cystem.core.storage.ConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupManager(
    context: Context,
    private val repository: ConversationRepository,
) {
    private val appContext = context.applicationContext

    suspend fun exportTo(uri: Uri) = withContext(Dispatchers.IO) {
        val json = repository.exportJson()
        appContext.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(json.toByteArray(Charsets.UTF_8))
        } ?: error("Unable to open the selected backup destination.")
    }

    suspend fun importFrom(uri: Uri) = withContext(Dispatchers.IO) {
        val json = appContext.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: error("Unable to open the selected backup.")
        require(json.length <= 20L * 1024L * 1024L) {
            "Backup exceeds the 20 MB safety limit."
        }
        repository.importJson(json)
    }
}
