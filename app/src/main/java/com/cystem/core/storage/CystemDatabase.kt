package com.cystem.core.storage

import android.content.ContentValues
import android.content.Context
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
        db.execSQL(
            "CREATE TABLE conversations(" +
                "id TEXT PRIMARY KEY NOT NULL," +
                "title TEXT NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "pinned INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL(
            "CREATE TABLE messages(" +
                "id TEXT PRIMARY KEY NOT NULL," +
                "conversation_id TEXT NOT NULL," +
                "role TEXT NOT NULL," +
                "content TEXT NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "reasoning TEXT," +
                "status TEXT NOT NULL," +
                "model TEXT," +
                "response_id TEXT," +
                "input_tokens INTEGER," +
                "output_tokens INTEGER," +
                "latency_ms INTEGER," +
                "FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE)",
        )
        db.execSQL(
            "CREATE TABLE attachments(" +
                "id TEXT PRIMARY KEY NOT NULL," +
                "local_path TEXT NOT NULL," +
                "mime_type TEXT NOT NULL," +
                "size_bytes INTEGER NOT NULL," +
                "width INTEGER," +
                "height INTEGER," +
                "source_url TEXT," +
                "source_title TEXT)",
        )
        db.execSQL(
            "CREATE TABLE message_attachments(" +
                "message_id TEXT NOT NULL," +
                "attachment_id TEXT NOT NULL," +
                "PRIMARY KEY(message_id, attachment_id)," +
                "FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE," +
                "FOREIGN KEY(attachment_id) REFERENCES attachments(id) ON DELETE CASCADE)",
        )
        db.execSQL(
            "CREATE TABLE sources(" +
                "id TEXT PRIMARY KEY NOT NULL," +
                "message_id TEXT NOT NULL," +
                "title TEXT NOT NULL," +
                "url TEXT NOT NULL," +
                "date TEXT," +
                "snippet TEXT," +
                "FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE)",
        )
        db.execSQL(
            "CREATE TABLE tool_calls(" +
                "id TEXT PRIMARY KEY NOT NULL," +
                "message_id TEXT NOT NULL," +
                "name TEXT NOT NULL," +
                "arguments_json TEXT NOT NULL," +
                "result TEXT," +
                "status TEXT NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "finished_at INTEGER," +
                "FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX idx_messages_conversation_created ON messages(conversation_id, created_at)")
        db.execSQL("CREATE INDEX idx_sources_message ON sources(message_id)")
        db.execSQL("CREATE INDEX idx_tools_message ON tool_calls(message_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        }
    }

    companion object {
        const val DATABASE_VERSION = 2
    }
}

class CystemDatabase(context: Context) {
    private val appContext = context.applicationContext
    private var helper = CystemDbHelper(appContext)

    fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        return try {
            helper.writableDatabase.runInTransaction { block(helper.writableDatabase) }
        } catch (_: SQLiteDatabaseCorruptException) {
            recoverFromCorruption()
            helper.writableDatabase.runInTransaction { block(helper.writableDatabase) }
        }
    }

    fun writable(): SQLiteDatabase = helper.writableDatabase

    fun close() = helper.close()

    private fun recoverFromCorruption() {
        helper.close()
        val original = appContext.getDatabasePath("cystem.db")
        if (original.exists()) {
            original.renameTo(
                File(original.parentFile, "cystem-corrupt-${System.currentTimeMillis()}.db"),
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
            null,
            null,
            null,
            null,
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

    fun listMessages(conversationId: String): List<Message> {
        val result = ArrayList<Message>()
        database.writable().query(
            "messages",
            arrayOf(
                "id", "conversation_id", "role", "content", "created_at",
                "reasoning", "status", "model", "response_id",
                "input_tokens", "output_tokens", "latency_ms",
            ),
            "conversation_id = ?",
            arrayOf(conversationId),
            null,
            null,
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
}

private fun android.database.Cursor.getLongOrNull(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

object ConversationTitles {
    fun fromFirstMessage(text: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return "New system session"
        return clean.removePrefix("/").take(64).trim().ifEmpty { "New system session" }
    }
}
