package cz.majkey.prepis

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class Recording(
    val uri: Uri,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val key: String = recordingKey(uri.toString(), size, lastModified),
)

enum class TranscriptSource(val id: String) {
    LOCAL("local"),
    OPENAI("openai"),
    GEMINI("gemini"),
    XAI("xai"),
    GROQ("groq"),
}

fun isM4a(name: String): Boolean = name.endsWith(".m4a", ignoreCase = true)

fun recordingKey(uri: String, size: Long, lastModified: Long): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$uri\n$size\n$lastModified".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

fun formatTranscript(parts: List<String>): String {
    val text = parts.joinToString(" ").replace(WHITESPACE, " ").trim()
    if (text.isEmpty()) return ""

    return text.split(SENTENCE_BOUNDARY)
        .chunked(SENTENCES_PER_PARAGRAPH)
        .joinToString("\n\n") { it.joinToString(" ") }
}

class FolderStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): Uri? = preferences.getString(KEY_FOLDER_URI, null)?.let(Uri::parse)

    fun save(uri: Uri) {
        preferences.edit { putString(KEY_FOLDER_URI, uri.toString()) }
    }

    fun clear() {
        preferences.edit { remove(KEY_FOLDER_URI) }
    }

    private companion object {
        const val PREFERENCES_NAME = "folder"
        const val KEY_FOLDER_URI = "uri"
    }
}

class RecordingScanner(context: Context) {
    private val resolver = context.contentResolver

    fun scan(treeUri: Uri): List<Recording> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val recordings = mutableListOf<Recording>()

        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                if (!isM4a(name)) continue

                recordings += Recording(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex)),
                    name = name,
                    size = cursor.getLong(sizeIndex).coerceAtLeast(0),
                    lastModified = cursor.getLong(modifiedIndex).coerceAtLeast(0),
                )
            }
        }

        return recordings.sortedByDescending(Recording::lastModified)
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

class TranscriptStore(context: Context) {
    private val directory = context.filesDir.resolve("transcripts")

    fun exists(key: String, source: TranscriptSource = TranscriptSource.LOCAL): Boolean =
        file(key, source).isFile

    fun read(key: String, source: TranscriptSource = TranscriptSource.LOCAL): String? =
        file(key, source).takeIf { it.isFile }?.readText(Charsets.UTF_8)

    fun delete(key: String, source: TranscriptSource = TranscriptSource.LOCAL): Boolean =
        !file(key, source).exists() || file(key, source).delete()

    fun write(
        key: String,
        transcript: String,
        source: TranscriptSource = TranscriptSource.LOCAL,
    ) {
        require(transcript.isNotBlank()) { "Transcript is blank" }
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create transcript directory" }

        val target = file(key, source)
        val temporary = directory.resolve("${target.name}.tmp")
        temporary.writeText(transcript, Charsets.UTF_8)
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun file(key: String, source: TranscriptSource) = directory.resolve(
        if (source == TranscriptSource.LOCAL) "$key.txt" else "$key-${source.id}.txt",
    )
}

private val WHITESPACE = Regex("\\s+")
private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+")
private const val SENTENCES_PER_PARAGRAPH = 3
