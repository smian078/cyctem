package com.cystem.core.coordinator

data class SearchIntent(
    val needsWeb: Boolean,
    val needsImages: Boolean,
)

object SearchIntentDetector {
    private val webSignals = listOf(
        "latest",
        "today",
        "current",
        "news",
        "search",
        "look up",
        "research",
        "source",
        "sources",
        "price",
        "weather",
        "2026",
    )

    private val imageSignals = listOf(
        "image",
        "images",
        "photo",
        "photos",
        "picture",
        "pictures",
        "visual",
        "show me",
    )

    fun detect(
        text: String,
        forceSearch: Boolean = false,
        forceImageSearch: Boolean = false,
    ): SearchIntent {
        val normalized = text.lowercase()
        val images = forceImageSearch || imageSignals.any(normalized::contains)
        val web = forceSearch || forceImageSearch || webSignals.any(normalized::contains)
        return SearchIntent(
            needsWeb = web,
            needsImages = images && web,
        )
    }
}
