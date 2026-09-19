package com.cystem.core.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import com.cystem.core.model.Attachment
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.math.max

class AttachmentStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "attachments").apply { mkdirs() }

    fun importUri(
        uri: Uri,
        displayName: String? = null,
        maxBytes: Long = 15L * 1024L * 1024L,
    ): Attachment {
        val resolver = appContext.contentResolver
        val mime = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                displayName?.substringAfterLast('.', missingDelimiterValue = ""),
            )
            ?: "application/octet-stream"

        val temp = File.createTempFile("import-", ".bin", root)
        try {
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    var total = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        total += count
                        require(total <= maxBytes) {
                            "Attachment exceeds the 15 MB limit."
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Unable to read the selected attachment.")

            val id = sha256(temp)
            val finalFile = File(
                root,
                id + extensionFor(mime),
            )

            if (mime.startsWith("image/")) {
                prepareImage(temp, finalFile)
            } else {
                temp.copyTo(finalFile, overwrite = true)
            }

            val dimensions = imageDimensions(finalFile)
            return Attachment(
                id = id,
                localPath = finalFile.absolutePath,
                mimeType = if (mime.startsWith("image/")) "image/jpeg" else mime,
                sizeBytes = finalFile.length(),
                width = dimensions.first,
                height = dimensions.second,
                sourceUrl = null,
                sourceTitle = displayName,
            )
        } finally {
            temp.delete()
        }
    }

    fun importFile(
        file: File,
        mimeType: String,
        sourceUrl: String? = null,
        sourceTitle: String? = null,
        maxBytes: Long = 15L * 1024L * 1024L,
    ): Attachment {
        require(file.length() <= maxBytes) {
            "Attachment exceeds the 15 MB limit."
        }

        val id = sha256(file)
        val finalFile = File(root, id + extensionFor(mimeType))
        if (!finalFile.exists()) {
            if (mimeType.startsWith("image/")) {
                prepareImage(file, finalFile)
            } else {
                file.copyTo(finalFile, overwrite = true)
            }
        }

        val dimensions = imageDimensions(finalFile)
        return Attachment(
            id = id,
            localPath = finalFile.absolutePath,
            mimeType = if (mimeType.startsWith("image/")) "image/jpeg" else mimeType,
            sizeBytes = finalFile.length(),
            width = dimensions.first,
            height = dimensions.second,
            sourceUrl = sourceUrl,
            sourceTitle = sourceTitle,
        )
    }

    private fun prepareImage(
        source: File,
        target: File,
    ) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        FileInputStream(source).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            source.copyTo(target, overwrite = true)
            return
        }

        val sample = calculateSample(max(bounds.outWidth, bounds.outHeight), 2048)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val bitmap = FileInputStream(source).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: run {
            source.copyTo(target, overwrite = true)
            return
        }

        target.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                "Unable to encode image attachment."
            }
        }
        bitmap.recycle()
    }

    private fun imageDimensions(file: File): Pair<Int?, Int?> {
        if (!file.exists()) return null to null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        FileInputStream(file).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null to null
        }
    }

    private fun calculateSample(maxDimension: Int, target: Int): Int {
        var sample = 1
        while (maxDimension / sample > target) {
            sample *= 2
        }
        return sample
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp" -> ".jpg"
        "text/plain" -> ".txt"
        "text/markdown" -> ".md"
        "application/json" -> ".json"
        "application/pdf" -> ".pdf"
        else -> ".bin"
    }
}
