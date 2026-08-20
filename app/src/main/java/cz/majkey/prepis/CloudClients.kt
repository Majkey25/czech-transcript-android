package cz.majkey.prepis

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

internal class CloudClient(private val resolver: ContentResolver) {
    fun transcribe(provider: CloudProvider, rawKey: String, recording: Recording): String {
        val key = validateApiKey(rawKey)
        val length = recording.size.takeIf { it > 0 } ?: resolver
            .openAssetFileDescriptor(recording.uri, "r")
            ?.use { it.length }
            ?.takeIf { it > 0 }
            ?: throw IOException("The recording size is unavailable")
        val maxBytes = provider.maxUploadBytes()
        // ponytail: direct upload only; add provider-aware audio chunking when real files exceed this limit.
        if (length > maxBytes) {
            throw IOException("The recording exceeds this provider's ${maxBytes / MEGABYTE} MB upload limit")
        }

        return when (provider) {
            CloudProvider.OPENAI -> multipartTranscript(
                OPENAI_URL,
                OPENAI_HOST,
                key,
                recording,
                maxBytes,
                linkedMapOf(
                    "model" to "gpt-4o-transcribe",
                    "language" to CZECH_LANGUAGE,
                    "response_format" to "json",
                ),
            )

            CloudProvider.GROQ -> multipartTranscript(
                GROQ_URL,
                GROQ_HOST,
                key,
                recording,
                maxBytes,
                linkedMapOf(
                    "model" to "whisper-large-v3",
                    "language" to CZECH_LANGUAGE,
                    "response_format" to "json",
                    "temperature" to "0",
                ),
            )

            CloudProvider.XAI -> multipartTranscript(
                XAI_URL,
                XAI_HOST,
                key,
                recording,
                maxBytes,
                linkedMapOf(
                    "format" to "true",
                    "language" to CZECH_LANGUAGE,
                ),
            )

            CloudProvider.GEMINI -> geminiTranscript(key, recording, length, maxBytes)
        }
    }

    private fun multipartTranscript(
        endpoint: String,
        host: String,
        key: String,
        recording: Recording,
        maxBytes: Long,
        fields: LinkedHashMap<String, String>,
    ): String {
        val boundary = "Transcript-${UUID.randomUUID()}"
        val connection = openHttps(endpoint, host).apply {
            requestMethod = "POST"
            doOutput = true
            setChunkedStreamingMode(NETWORK_BUFFER_SIZE)
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        try {
            BufferedOutputStream(connection.outputStream, NETWORK_BUFFER_SIZE).use { output ->
                fields.forEach { (name, value) -> writeField(output, boundary, name, value) }
                writeUtf8(
                    output,
                    "--$boundary\r\n" +
                        "Content-Disposition: form-data; name=\"file\"; filename=\"${safeUploadName(recording.name)}\"\r\n" +
                        "Content-Type: audio/mp4\r\n\r\n",
                )
                resolver.openInputStream(recording.uri)?.use { input ->
                    copyBounded(input, output, maxBytes)
                } ?: throw IOException("The recording cannot be opened")
                writeUtf8(output, "\r\n--$boundary--\r\n")
            }
            return parseCloudText(readResponse(connection))
        } finally {
            connection.disconnect()
        }
    }

    private fun geminiTranscript(
        key: String,
        recording: Recording,
        length: Long,
        maxBytes: Long,
    ): String {
        val uploadUrl = startGeminiUpload(key, recording.name, length)
        val uploaded = uploadGeminiFile(uploadUrl, key, recording.uri, length, maxBytes)
        return try {
            val active = waitForGeminiFile(key, uploaded)
            generateGeminiTranscript(key, active)
        } finally {
            deleteGeminiFile(key, uploaded.name)
        }
    }

    private fun startGeminiUpload(key: String, name: String, length: Long): String {
        val metadata = JSONObject()
            .put("file", JSONObject().put("display_name", safeUploadName(name)))
            .toString()
            .toByteArray(Charsets.UTF_8)
        val connection = openHttps(GEMINI_UPLOAD_URL, GEMINI_HOST).apply {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(metadata.size)
            setRequestProperty("x-goog-api-key", key)
            setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            setRequestProperty("X-Goog-Upload-Command", "start")
            setRequestProperty("X-Goog-Upload-Header-Content-Length", length.toString())
            setRequestProperty("X-Goog-Upload-Header-Content-Type", "audio/mp4")
            setRequestProperty("Content-Type", "application/json")
        }

        try {
            connection.outputStream.use { it.write(metadata) }
            readResponse(connection, allowEmpty = true)
            val uploadUrl = connection.getHeaderField("X-Goog-Upload-URL")
                ?: throw IOException("Gemini did not return an upload URL")
            validateHttpsUrl(uploadUrl, GEMINI_HOST)
            return uploadUrl
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadGeminiFile(
        uploadUrl: String,
        key: String,
        uri: Uri,
        length: Long,
        maxBytes: Long,
    ): GeminiFile {
        val connection = openHttps(uploadUrl, GEMINI_HOST).apply {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(length)
            setRequestProperty("x-goog-api-key", key)
            setRequestProperty("Content-Length", length.toString())
            setRequestProperty("X-Goog-Upload-Offset", "0")
            setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
        }

        try {
            val copied = resolver.openInputStream(uri)?.use { input ->
                BufferedOutputStream(connection.outputStream, NETWORK_BUFFER_SIZE).use { output ->
                    copyBounded(input, output, maxBytes)
                }
            } ?: throw IOException("The recording cannot be opened")
            if (copied != length) throw IOException("The recording changed during upload")
            return parseGeminiFile(JSONObject(readResponse(connection)).getJSONObject("file"))
        } finally {
            connection.disconnect()
        }
    }

    private fun waitForGeminiFile(key: String, initial: GeminiFile): GeminiFile {
        var current = initial
        repeat(GEMINI_PROCESSING_ATTEMPTS) {
            when (current.state.uppercase()) {
                "ACTIVE" -> return current
                "FAILED" -> throw IOException("Gemini could not process the recording")
            }
            Thread.sleep(GEMINI_POLL_INTERVAL_MS)
            current = getGeminiFile(key, current.name)
        }
        throw IOException("Gemini file processing timed out")
    }

    private fun getGeminiFile(key: String, name: String): GeminiFile {
        val safeName = validateGeminiFileName(name)
        val connection = openHttps("$GEMINI_FILES_URL/$safeName", GEMINI_HOST).apply {
            requestMethod = "GET"
            setRequestProperty("x-goog-api-key", key)
        }
        return try {
            parseGeminiFile(JSONObject(readResponse(connection)))
        } finally {
            connection.disconnect()
        }
    }

    private fun generateGeminiTranscript(key: String, file: GeminiFile): String {
        val prompt = "Transcribe the complete audio verbatim in Czech. Preserve punctuation, paragraphs, " +
            "and speaker labels when evident. Return only the transcript."
        val parts = JSONArray()
            .put(
                JSONObject().put(
                    "file_data",
                    JSONObject()
                        .put("mime_type", file.mimeType)
                        .put("file_uri", file.uri),
                ),
            )
            .put(JSONObject().put("text", prompt))
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(JSONObject().put("role", "user").put("parts", parts)),
            )
            .put("generation_config", JSONObject().put("temperature", 0))
            .toString()
            .toByteArray(Charsets.UTF_8)
        val connection = openHttps(GEMINI_GENERATE_URL, GEMINI_HOST).apply {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("x-goog-api-key", key)
            setRequestProperty("Content-Type", "application/json")
        }

        return try {
            connection.outputStream.use { it.write(body) }
            parseGeminiText(readResponse(connection))
        } finally {
            connection.disconnect()
        }
    }

    private fun deleteGeminiFile(key: String, name: String) {
        runCatching {
            val safeName = validateGeminiFileName(name)
            val connection = openHttps("$GEMINI_FILES_URL/$safeName", GEMINI_HOST).apply {
                requestMethod = "DELETE"
                setRequestProperty("x-goog-api-key", key)
            }
            try {
                readResponse(connection, allowEmpty = true)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parseGeminiText(body: String): String {
        val candidates = JSONObject(body).optJSONArray("candidates")
            ?: throw IOException("Gemini returned no transcript")
        val parts = candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw IOException("Gemini returned no transcript")
        return buildList {
            for (index in 0 until parts.length()) {
                parts.optJSONObject(index)?.optString("text")?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            }
        }.joinToString("\n\n").ifBlank { throw IOException("Gemini returned an empty transcript") }
    }

    private fun parseGeminiFile(json: JSONObject): GeminiFile = GeminiFile(
        name = validateGeminiFileName(json.getString("name")),
        uri = json.getString("uri"),
        mimeType = json.optString("mimeType", "audio/mp4"),
        state = json.optString("state", "PROCESSING"),
    )

    private fun validateGeminiFileName(name: String): String {
        require(GEMINI_FILE_NAME.matches(name)) { "Gemini returned an invalid file name" }
        return name
    }

    private data class GeminiFile(
        val name: String,
        val uri: String,
        val mimeType: String,
        val state: String,
    )

    private companion object {
        const val OPENAI_HOST = "api.openai.com"
        const val OPENAI_URL = "https://api.openai.com/v1/audio/transcriptions"
        const val GROQ_HOST = "api.groq.com"
        const val GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val XAI_HOST = "api.x.ai"
        const val XAI_URL = "https://api.x.ai/v1/stt"
        const val GEMINI_HOST = "generativelanguage.googleapis.com"
        const val GEMINI_UPLOAD_URL = "https://generativelanguage.googleapis.com/upload/v1beta/files"
        const val GEMINI_FILES_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val GEMINI_GENERATE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:generateContent"
        const val CZECH_LANGUAGE = "cs"
        const val MEGABYTE = 1024L * 1024L
        const val NETWORK_BUFFER_SIZE = 64 * 1024
        const val GEMINI_PROCESSING_ATTEMPTS = 300
        const val GEMINI_POLL_INTERVAL_MS = 1_000L
        val GEMINI_FILE_NAME = Regex("files/[A-Za-z0-9_-]+")
    }
}

private fun CloudProvider.maxUploadBytes(): Long = when (this) {
    CloudProvider.OPENAI, CloudProvider.GROQ -> 25L * 1024L * 1024L
    CloudProvider.XAI -> 100L * 1024L * 1024L
    CloudProvider.GEMINI -> 500L * 1024L * 1024L
}

private fun openHttps(rawUrl: String, expectedHost: String): HttpsURLConnection {
    val url = validateHttpsUrl(rawUrl, expectedHost)
    return (url.openConnection() as HttpsURLConnection).apply {
        connectTimeout = 30_000
        readTimeout = 300_000
        useCaches = false
        instanceFollowRedirects = false
    }
}

private fun validateHttpsUrl(rawUrl: String, expectedHost: String): URL = URL(rawUrl).also {
    require(it.protocol == "https" && it.host == expectedHost) { "Unexpected provider URL" }
}

private fun writeField(output: OutputStream, boundary: String, name: String, value: String) {
    writeUtf8(
        output,
        "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"$name\"\r\n\r\n" +
            "$value\r\n",
    )
}

private fun writeUtf8(output: OutputStream, value: String) {
    output.write(value.toByteArray(Charsets.UTF_8))
}

private fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long): Long {
    var total = 0L
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return total
        total += count
        if (total > maxBytes) throw IOException("The recording exceeds the upload limit")
        output.write(buffer, 0, count)
    }
}

private fun readResponse(connection: HttpsURLConnection, allowEmpty: Boolean = false): String {
    val code = connection.responseCode
    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
    val body = stream?.use { readBounded(it, 1024 * 1024) }.orEmpty()
    if (code !in 200..299) throw CloudApiException("Provider request failed (HTTP $code)")
    if (!allowEmpty && body.isBlank()) throw CloudApiException("Provider returned an empty response")
    return body
}

private fun readBounded(input: InputStream, maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    BufferedInputStream(input).use { buffered ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = buffered.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) throw IOException("Provider response is too large")
            output.write(buffer, 0, count)
        }
    }
    return output.toString(Charsets.UTF_8.name())
}

internal class CloudApiException(message: String) : IOException(message)
