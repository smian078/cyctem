package com.cystem.core.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import com.cystem.core.model.*
import java.io.File

internal class CystemDbHelper(
    context: Context,
) : SQLiteOpenHelper(context, "cystem.db", null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,pinned INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY NOT NULL,conversation_id TEXT NOT NULL,role TEXT NOT NULL,content TEXT NOT NULL,created_at INTEGER NOT NULL,reasoning TEXT,status TEXT NOT NULL,model TEXT,response_id TEXT,input_tokens INTEGER,output_tokens INTEGER,latency_ms INTEGER,FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE attachments(id TEXT PRIMARY KEY NOT NULL,local_path TEXT NOT NULL,mime_type TEXT NOT NULL,size_bytes INTEGER NOT NULL,width INTEGER,height INTEGER,source_url TEXT,source_title TEXT)")
        db.execSQL("CREATE TABLE message_attachments(message_id TEXT NOT NULL,attachment_id TEXT NOT NULL,PRIMARY KEY(message_id,attachment_id),FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE,FOREIGN KEY(attachment_id) REFERENCES attachments(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE sources(id TEXT PRIMARY KEY NOT NULL,message_id TEXT NOT NULL,title TEXT NOT NULL,url TEXT NOT NULL,date TEXT,snippet TEXT,FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE tool_calls(id TEXT PRIMARY KEY NOT NULL,message_id TEXT NOT NULL,name TEXT NOT NULL,arguments_json TEXT NOT NULL,result TEXT,status TEXT NOT NULL,created_at INTEGER NOT NULL,finished_at INTEGER,FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE attachment_analysis(attachment_id TEXT PRIMARY KEY NOT NULL,analysis TEXT NOT NULL,created_at INTEGER NOT NULL,FOREIGN KEY(attachment_id) REFERENCES attachments(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX idx_messages_conversation_created ON messages(conversation_id, created_at)")
        db.execSQL("CREATE INDEX idx_sources_message ON sources(message_id)")
        db.execSQL("CREATE INDEX idx_tools_message ON tool_calls(message_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE attachment_analysis(attachment_id TEXT PRIMARY KEY NOT NULL,analysis TEXT NOT NULL,created_at INTEGER NOT NULL,FOREIGN KEY(attachment_id) REFERENCES attachments(id) ON DELETE CASCADE)")
        }
    }

    companion object {
        const val DATABASE_VERSION = 3
    }
}

class CystemDatabase(context: Context) {
    private val appContext = context.applicationContext
    private var helper = CystemDbHelper(appContext)

    fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        try {
            val db = helper.writableDatabase
            db.beginTransaction()
            val result = try {
                block(db).also { db.setTransactionSuccessful() }
            } finally {
                db.endTransaction()
            }
            return result
        } catch (_: SQLiteDatabaseCorruptException) {
            recoverFromCorruption()
            val db = helper.writableDatabase
            db.beginTransaction()
            return try {
                block(db).also { db.setTransactionSuccessful() }
            } finally {
                db.endTransaction()
            }
        }
    }

    fun writable(): SQLiteDatabase = helper.writableDatabase
    fun close() = helper.close()

    private fun recoverFromCorruption() {
        helper.close()
        val original = appContext.getDatabasePath("cystem.db")
        if (original.exists()) {
            original.renameTo(
                File(original.parentFile, "cystem-corrupt-" + System.currentTimeMillis() + ".db"),
            )
        }
        helper = CystemDbHelper(appContext)
    }
}

class ConversationRepository(private val database: CystemDatabase) {
    fun createConversation(now: Long, title: String = "New system session"): Conversation {
        val conversation = Conversation(
            id = java.util.UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
            pinned = false,
        )
        database.transaction { db ->
            db.insertOrThrow(
                "conversations",
                null,
                ContentValues().apply {
                    put("id", conversation.id)
                    put("title", conversation.title)
                    put("created_at", conversation.createdAt)
                    put("updated_at", conversation.updatedAt)
                    put("pinned", 0)
                },
            )
        }
        return conversation
    }

    fun listConversations(): List<Conversation> {
        val result = ArrayList<Conversation>()
        database.writable().query(
            "conversations",
            arrayOf("id", "title", "created_at", "updated_at", "pinned"),
            null, null, null, null,
            "pinned DESC, updated_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += Conversation(
                    id = cursor.getString(0),
                    title = cursor.getString(1),
                    createdAt = cursor.getLong(2),
                    updatedAt = cursor.getLong(3),
                    pinned = cursor.getInt(4) != 0,
                )
            }
        }
        return result
    }

    fun renameConversation(id: String, title: String, now: Long) {
        database.writable().update(
            "conversations",
            ContentValues().apply {
                put("title", title.take(120))
                put("updated_at", now)
            },
            "id = ?",
            arrayOf(id),
        )
    }

    fun setPinned(id: String, pinned: Boolean, now: Long) {
        database.writable().update(
            "conversations",
            ContentValues().apply {
                put("pinned", if (pinned) 1 else 0)
                put("updated_at", now)
            },
            "id = ?",
            arrayOf(id),
        )
    }

    fun deleteConversation(id: String) {
        database.transaction { db ->
            db.delete("conversations", "id = ?", arrayOf(id))
        }
    }

    fun insertMessage(message: Message) {
        database.writable().insertOrThrow(
            "messages",
            null,
            ContentValues().apply {
                put("id", message.id)
                put("conversation_id", message.conversationId)
                put("role", message.role.name)
                put("content", message.content)
                put("created_at", message.createdAt)
                put("reasoning", message.reasoning)
                put("status", message.status.name)
                put("model", message.model)
                put("response_id", message.responseId)
                message.inputTokens?.let { put("input_tokens", it) }
                message.outputTokens?.let { put("output_tokens", it) }
                message.latencyMs?.let { put("latency_ms", it) }
            },
        )
    }

    fun updateMessage(message: Message) {
        database.writable().update(
            "messages",
            ContentValues().apply {
                put("content", message.content)
                put("reasoning", message.reasoning)
                put("status", message.status.name)
                put("model", message.model)
                put("response_id", message.responseId)
                message.inputTokens?.let { put("input_tokens", it) }
                message.outputTokens?.let { put("output_tokens", it) }
                message.latencyMs?.let { put("latency_ms", it) }
            },
            "id = ?",
            arrayOf(message.id),
        )
    }

    fun listMessages(conversationId: String): List<Message> {
        val result = ArrayList<Message>()
        database.writable().query(
            "messages",
            arrayOf("id","conversation_id","role","content","created_at","reasoning","status","model","response_id","input_tokens","output_tokens","latency_ms"),
            "conversation_id = ?",
            arrayOf(conversationId),
            null, null,
            "created_at ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += Message(
                    id = cursor.getString(0),
                    conversationId = cursor.getString(1),
                    role = MessageRole.valueOf(cursor.getString(2)),
                    content = cursor.getString(3),
                    createdAt = cursor.getLong(4),
                    reasoning = cursor.getString(5),
                    status = MessageStatus.valueOf(cursor.getString(6)),
                    model = cursor.getString(7),
                    responseId = cursor.getString(8),
                    inputTokens = cursor.getLongOrNull(9),
                    outputTokens = cursor.getLongOrNull(10),
                    latencyMs = cursor.getLongOrNull(11),
                )
            }
        }
        return result
    }

    fun saveAttachmentAnalysis(attachmentId: String, analysis: String, now: Long) {
        database.writable().insertWithOnConflict(
            "attachment_analysis",
            null,
            ContentValues().apply {
                put("attachment_id", attachmentId)
                put("analysis", analysis)
                put("created_at", now)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun findAttachmentAnalysis(attachmentId: String): String? {
        database.writable().query(
            "attachment_analysis",
            arrayOf("analysis"),
            "attachment_id = ?",
            arrayOf(attachmentId),
            null, null, null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun insertAttachment(attachment: Attachment) {
        database.writable().insertOrThrow(
            "attachments",
            null,
            ContentValues().apply {
                put("id", attachment.id)
                put("local_path", attachment.localPath)
                put("mime_type", attachment.mimeType)
                put("size_bytes", attachment.sizeBytes)
                attachment.width?.let { put("width", it) }
                attachment.height?.let { put("height", it) }
                attachment.sourceUrl?.let { put("source_url", it) }
                attachment.sourceTitle?.let { put("source_title", it) }
            },
        )
    }

    fun attachToMessage(messageId: String, attachmentId: String) {
        database.writable().insertOrThrow(
            "message_attachments",
            null,
            ContentValues().apply {
                put("message_id", messageId)
                put("attachment_id", attachmentId)
            },
        )
    }

    fun listConversationAttachments(messageId: String): List<Attachment> {
        val result = ArrayList<Attachment>()
        database.writable().rawQuery(
            "SELECT a.id,a.local_path,a.mime_type,a.size_bytes,a.width,a.height,a.source_url,a.source_title " +
                "FROM attachments a INNER JOIN message_attachments ma ON a.id=ma.attachment_id WHERE ma.message_id=?",
            arrayOf(messageId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += Attachment(
                    id = cursor.getString(0),
                    localPath = cursor.getString(1),
                    mimeType = cursor.getString(2),
                    sizeBytes = cursor.getLong(3),
                    width = cursor.getIntOrNull(4),
                    height = cursor.getIntOrNull(5),
                    sourceUrl = cursor.getString(6),
                    sourceTitle = cursor.getString(7),
                )
            }
        }
        return result
    }

    fun insertSource(source: Source) {
        database.writable().insertOrThrow(
            "sources",
            null,
            ContentValues().apply {
                put("id", source.id)
                put("message_id", source.messageId)
                put("title", source.title)
                put("url", source.url)
                put("date", source.date)
                put("snippet", source.snippet)
            },
        )
    }

    fun listSources(messageId: String): List<Source> {
        val result = ArrayList<Source>()
        database.writable().query(
            "sources",
            arrayOf("id","message_id","title","url","date","snippet"),
            "message_id = ?",
            arrayOf(messageId),
            null, null,
            "rowid ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += Source(
                    id = cursor.getString(0),
                    messageId = cursor.getString(1),
                    title = cursor.getString(2),
                    url = cursor.getString(3),
                    date = cursor.getString(4),
                    snippet = cursor.getString(5),
                )
            }
        }
        return result
    }

    fun exportJson(): String {
        return database.transaction { db ->
            val root = org.json.JSONObject()
            root.put("version", 1)

            val conversations = org.json.JSONArray()
            db.query(
                "conversations",
                null,
                null,
                null,
                null,
                null,
                "updated_at ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    conversations.put(
                        org.json.JSONObject()
                            .put("id", cursor.getString(cursor.getColumnIndexOrThrow("id")))
                            .put("title", cursor.getString(cursor.getColumnIndexOrThrow("title")))
                            .put("created_at", cursor.getLong(cursor.getColumnIndexOrThrow("created_at")))
                            .put("updated_at", cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
                            .put("pinned", cursor.getInt(cursor.getColumnIndexOrThrow("pinned")) != 0),
                    )
                }
            }

            val messages = org.json.JSONArray()
            db.query(
                "messages",
                null,
                null,
                null,
                null,
                null,
                "created_at ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    messages.put(
                        org.json.JSONObject()
                            .put("id", cursor.getString(cursor.getColumnIndexOrThrow("id")))
                            .put("conversation_id", cursor.getString(cursor.getColumnIndexOrThrow("conversation_id")))
                            .put("role", cursor.getString(cursor.getColumnIndexOrThrow("role")))
                            .put("content", cursor.getString(cursor.getColumnIndexOrThrow("content")))
                            .put("created_at", cursor.getLong(cursor.getColumnIndexOrThrow("created_at")))
                            .put("reasoning", cursor.getStringOrNull("reasoning"))
                            .put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")))
                            .put("model", cursor.getStringOrNull("model"))
                            .put("response_id", cursor.getStringOrNull("response_id"))
                            .put("input_tokens", cursor.getLongOrNull(cursor.getColumnIndexOrThrow("input_tokens")))
                            .put("output_tokens", cursor.getLongOrNull(cursor.getColumnIndexOrThrow("output_tokens")))
                            .put("latency_ms", cursor.getLongOrNull(cursor.getColumnIndexOrThrow("latency_ms"))),
                    )
                }
            }

            val attachments = org.json.JSONArray()
            db.query("attachments", null, null, null, null, null, "rowid ASC").use { cursor ->
                while (cursor.moveToNext()) {
                    attachments.put(
                        org.json.JSONObject()
                            .put("id", cursor.getString(cursor.getColumnIndexOrThrow("id")))
                            .put("local_path", cursor.getString(cursor.getColumnIndexOrThrow("local_path")))
                            .put("mime_type", cursor.getString(cursor.getColumnIndexOrThrow("mime_type")))
                            .put("size_bytes", cursor.getLong(cursor.getColumnIndexOrThrow("size_bytes")))
                            .put("width", cursor.getIntOrNull(cursor.getColumnIndexOrThrow("width")))
                            .put("height", cursor.getIntOrNull(cursor.getColumnIndexOrThrow("height")))
                            .put("source_url", cursor.getStringOrNull("source_url"))
                            .put("source_title", cursor.getStringOrNull("source_title")),
                    )
                }
            }

            val messageAttachments = org.json.JSONArray()
            db.rawQuery("SELECT message_id,attachment_id FROM message_attachments", null).use { cursor ->
                while (cursor.moveToNext()) {
                    messageAttachments.put(
                        org.json.JSONObject()
                            .put("message_id", cursor.getString(0))
                            .put("attachment_id", cursor.getString(1)),
                    )
                }
            }

            val sources = org.json.JSONArray()
            db.query("sources", null, null, null, null, null, "rowid ASC").use { cursor ->
                while (cursor.moveToNext()) {
                    sources.put(
                        org.json.JSONObject()
                            .put("id", cursor.getString(cursor.getColumnIndexOrThrow("id")))
                            .put("message_id", cursor.getString(cursor.getColumnIndexOrThrow("message_id")))
                            .put("title", cursor.getString(cursor.getColumnIndexOrThrow("title")))
                            .put("url", cursor.getString(cursor.getColumnIndexOrThrow("url")))
                            .put("date", cursor.getStringOrNull("date"))
                            .put("snippet", cursor.getStringOrNull("snippet")),
                    )
                }
            }

            val toolCalls = org.json.JSONArray()
            db.query("tool_calls", null, null, null, null, null, "created_at ASC").use { cursor ->
                while (cursor.moveToNext()) {
                    toolCalls.put(
                        org.json.JSONObject()
                            .put("id", cursor.getString(cursor.getColumnIndexOrThrow("id")))
                            .put("message_id", cursor.getString(cursor.getColumnIndexOrThrow("message_id")))
                            .put("name", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                            .put("arguments_json", cursor.getString(cursor.getColumnIndexOrThrow("arguments_json")))
                            .put("result", cursor.getStringOrNull("result"))
                            .put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")))
                            .put("created_at", cursor.getLong(cursor.getColumnIndexOrThrow("created_at")))
                            .put("finished_at", cursor.getLongOrNull(cursor.getColumnIndexOrThrow("finished_at"))),
                    )
                }
            }

            val analysis = org.json.JSONArray()
            db.query("attachment_analysis", null, null, null, null, null, "created_at ASC").use { cursor ->
                while (cursor.moveToNext()) {
                    analysis.put(
                        org.json.JSONObject()
                            .put("attachment_id", cursor.getString(cursor.getColumnIndexOrThrow("attachment_id")))
                            .put("analysis", cursor.getString(cursor.getColumnIndexOrThrow("analysis")))
                            .put("created_at", cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))),
                    )
                }
            }

            root.put("conversations", conversations)
            root.put("messages", messages)
            root.put("attachments", attachments)
            root.put("message_attachments", messageAttachments)
            root.put("sources", sources)
            root.put("tool_calls", toolCalls)
            root.put("attachment_analysis", analysis)
            root.toString()
        }
    }

    fun importJson(json: String) {
        val root = org.json.JSONObject(json)
        require(root.optInt("version", -1) == 1) { "Unsupported CYSTEM backup version." }

        database.transaction { db ->
            db.delete("attachment_analysis", null, null)
            db.delete("tool_calls", null, null)
            db.delete("sources", null, null)
            db.delete("message_attachments", null, null)
            db.delete("attachments", null, null)
            db.delete("messages", null, null)
            db.delete("conversations", null, null)

            val conversations = root.optJSONArray("conversations") ?: org.json.JSONArray()
            for (index in 0 until conversations.length()) {
                val item = conversations.getJSONObject(index)
                db.insertOrThrow(
                    "conversations",
                    null,
                    ContentValues().apply {
                        put("id", item.getString("id"))
                        put("title", item.getString("title"))
                        put("created_at", item.getLong("created_at"))
                        put("updated_at", item.getLong("updated_at"))
                        put("pinned", if (item.optBoolean("pinned")) 1 else 0)
                    },
                )
            }

            val messages = root.optJSONArray("messages") ?: org.json.JSONArray()
            for (index in 0 until messages.length()) {
                val item = messages.getJSONObject(index)
                db.insertOrThrow(
                    "messages",
                    null,
                    ContentValues().apply {
                        put("id", item.getString("id"))
                        put("conversation_id", item.getString("conversation_id"))
                        put("role", item.getString("role"))
                        put("content", item.getString("content"))
                        put("created_at", item.getLong("created_at"))
                        item.optString("reasoning").takeIf { it.isNotBlank() }?.let { put("reasoning", it) }
                        put("status", item.getString("status"))
                        item.optString("model").takeIf { it.isNotBlank() }?.let { put("model", it) }
                        item.optString("response_id").takeIf { it.isNotBlank() }?.let { put("response_id", it) }
                        if (!item.isNull("input_tokens")) put("input_tokens", item.getLong("input_tokens"))
                        if (!item.isNull("output_tokens")) put("output_tokens", item.getLong("output_tokens"))
                        if (!item.isNull("latency_ms")) put("latency_ms", item.getLong("latency_ms"))
                    },
                )
            }

            val attachments = root.optJSONArray("attachments") ?: org.json.JSONArray()
            for (index in 0 until attachments.length()) {
                val item = attachments.getJSONObject(index)
                db.insertOrThrow(
                    "attachments",
                    null,
                    ContentValues().apply {
                        put("id", item.getString("id"))
                        put("local_path", item.getString("local_path"))
                        put("mime_type", item.getString("mime_type"))
                        put("size_bytes", item.getLong("size_bytes"))
                        if (!item.isNull("width")) put("width", item.getInt("width"))
                        if (!item.isNull("height")) put("height", item.getInt("height"))
                        item.optString("source_url").takeIf { it.isNotBlank() }?.let { put("source_url", it) }
                        item.optString("source_title").takeIf { it.isNotBlank() }?.let { put("source_title", it) }
                    },
                )
            }

            val links = root.optJSONArray("message_attachments") ?: org.json.JSONArray()
            for (index in 0 until links.length()) {
                val item = links.getJSONObject(index)
                db.insertOrThrow(
                    "message_attachments",
                    null,
                    ContentValues().apply {
                        put("message_id", item.getString("message_id"))
                        put("attachment_id", item.getString("attachment_id"))
                    },
                )
            }

            val sources = root.optJSONArray("sources") ?: org.json.JSONArray()
            for (index in 0 until sources.length()) {
                val item = sources.getJSONObject(index)
                db.insertOrThrow(
                    "sources",
                    null,
                    ContentValues().apply {
                        put("id", item.getString("id"))
                        put("message_id", item.getString("message_id"))
                        put("title", item.getString("title"))
                        put("url", item.getString("url"))
                        item.optString("date").takeIf { it.isNotBlank() }?.let { put("date", it) }
                        item.optString("snippet").takeIf { it.isNotBlank() }?.let { put("snippet", it) }
                    },
                )
            }

            val toolCalls = root.optJSONArray("tool_calls") ?: org.json.JSONArray()
            for (index in 0 until toolCalls.length()) {
                val item = toolCalls.getJSONObject(index)
                db.insertOrThrow(
                    "tool_calls",
                    null,
                    ContentValues().apply {
                        put("id", item.getString("id"))
                        put("message_id", item.getString("message_id"))
                        put("name", item.getString("name"))
                        put("arguments_json", item.getString("arguments_json"))
                        item.optString("result").takeIf { it.isNotBlank() }?.let { put("result", it) }
                        put("status", item.getString("status"))
                        put("created_at", item.getLong("created_at"))
                        if (!item.isNull("finished_at")) put("finished_at", item.getLong("finished_at"))
                    },
                )
            }

            val analysis = root.optJSONArray("attachment_analysis") ?: org.json.JSONArray()
            for (index in 0 until analysis.length()) {
                val item = analysis.getJSONObject(index)
                db.insertOrThrow(
                    "attachment_analysis",
                    null,
                    ContentValues().apply {
                        put("attachment_id", item.getString("attachment_id"))
                        put("analysis", item.getString("analysis"))
                        put("created_at", item.getLong("created_at"))
                    },
                )
            }
        }
    }

    fun insertToolCall(record: ToolCallRecord) {
        database.writable().insertOrThrow(
            "tool_calls",
            null,
            ContentValues().apply {
                put("id", record.id)
                put("message_id", record.messageId)
                put("name", record.name)
                put("arguments_json", record.argumentsJson)
                put("result", record.result)
                put("status", record.status)
                put("created_at", record.createdAt)
                record.finishedAt?.let { put("finished_at", it) }
            },
        )
    }

    fun updateToolCall(record: ToolCallRecord) {
        database.writable().update(
            "tool_calls",
            ContentValues().apply {
                put("result", record.result)
                put("status", record.status)
                record.finishedAt?.let { put("finished_at", it) }
            },
            "id = ?",
            arrayOf(record.id),
        )
    }
}

private fun Cursor.getLongOrNull(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun Cursor.getIntOrNull(index: Int): Int? =
    if (isNull(index)) null else getInt(index)

object ConversationTitles {
    fun fromFirstMessage(text: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return "New system session"
        return clean.removePrefix("/").take(64).trim().ifEmpty { "New system session" }
    }
}

private fun Cursor.getStringOrNull(columnName: String): String? {
    val index = getColumnIndexOrThrow(columnName)
    return if (isNull(index)) null else getString(index)
}
