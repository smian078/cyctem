package com.cystem.core.di

import android.content.Context
import com.cystem.core.coordinator.RequestCoordinator
import com.cystem.core.security.SecureKeyStore
import com.cystem.core.settings.SettingsStore
import com.cystem.core.storage.ConversationRepository
import com.cystem.core.storage.CystemDatabase

class AppContainer(context: Context) {
    val secureKeyStore = SecureKeyStore(context)
    val settingsStore = SettingsStore(context, secureKeyStore)
    val database = CystemDatabase(context)
    val conversations = ConversationRepository(database)
    val coordinator = RequestCoordinator(this)
}
