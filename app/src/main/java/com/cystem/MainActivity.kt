package com.cystem

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.cystem.ui.CystemApp
import com.cystem.ui.CystemViewModel

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<CystemViewModel> {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as CystemApplication
                @Suppress("UNCHECKED_CAST")
                return CystemViewModel(app.container) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        (application as CystemApplication).container.phoneActionDispatcher.attach(this)
        handleIncomingIntent(intent)
        setContent { CystemApp(viewModel) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        (application as CystemApplication).container.phoneActionDispatcher.detach(this)
        super.onDestroy()
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.takeIf { it.isNotBlank() }
                ?.let(viewModel::importSharedText)
        }

        val uris = collectIncomingUris(intent)
        if (uris.isNotEmpty()) {
            viewModel.importSharedUris(uris)
        }
    }

    private fun collectIncomingUris(intent: Intent): List<Uri> {
        val result = ArrayList<Uri>()
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(result::add)
        val clipData: ClipData? = intent.clipData
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index).uri?.let(result::add)
            }
        }
        return result.distinct()
    }
}
