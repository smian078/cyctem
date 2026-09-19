package com.cystem.core.attachments

import com.cystem.core.model.Attachment
import com.cystem.core.network.ImageSearchItem
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class ImageSearchDownloader(
    private val store: AttachmentStore,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun downloadAll(
        items: List<ImageSearchItem>,
        maxImages: Int = 8,
        maxBytes: Long = 5L * 1024L * 1024L,
    ): List<Attachment> {
        val result = ArrayList<Attachment>()

        for (item in items.distinctBy { it.imageUrl }.take(maxImages)) {
            runCatching {
                val request = Request.Builder()
                    .url(item.imageUrl)
                    .header("Accept", "image/*")
                    .get()
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body ?: return@use

                    val contentLength = body.contentLength()
                    if (contentLength > maxBytes) return@use

                    val temp = File.createTempFile("cystem-image-", ".download")
                    var tooLarge = false
                    try {
                        body.byteStream().use { input ->
                            temp.outputStream().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count <= 0) break
                                    total += count
                                    if (total > maxBytes) {
                                        tooLarge = true
                                        break
                                    }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }

                        if (tooLarge) return@use

                        val contentType = response.header("Content-Type")
                            ?.substringBefore(';')
                            ?.takeIf { it.startsWith("image/") }
                            ?: "image/jpeg"

                        result += store.importFile(
                            file = temp,
                            mimeType = contentType,
                            sourceUrl = item.sourceUrl,
                            sourceTitle = item.title,
                            maxBytes = maxBytes,
                        )
                    } finally {
                        temp.delete()
                    }
                }
            }
        }

        return result.distinctBy { it.id }
    }
}
