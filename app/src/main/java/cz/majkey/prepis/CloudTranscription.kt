package cz.majkey.prepis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class CloudProvider(
    val id: String,
    val source: TranscriptSource,
) {
    OPENAI("openai", TranscriptSource.OPENAI),
    GEMINI("gemini", TranscriptSource.GEMINI),
    XAI("xai", TranscriptSource.XAI),
    GROQ("groq", TranscriptSource.GROQ),
    ;

    companion object {
        fun fromId(id: String): CloudProvider = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown cloud provider")
    }
}

internal fun validateApiKey(raw: String): String {
    val key = raw.trim()
    require(key.length in 8..512) { "API key must contain 8 to 512 characters" }
    require(key.all { it.code in 33..126 }) { "API key contains unsupported characters" }
    return key
}

internal fun safeUploadName(name: String): String {
    val sanitized = name.map { character ->
        if (character.code in 32..126 && character != '"' && character != '\\') character else '_'
    }
        .joinToString("")
    val stem = if (sanitized.endsWith(M4A_EXTENSION, ignoreCase = true)) {
        sanitized.dropLast(M4A_EXTENSION.length)
    } else {
        sanitized
    }
    return "${stem.take(MAX_UPLOAD_NAME_LENGTH - M4A_EXTENSION.length).ifBlank { "recording" }}$M4A_EXTENSION"
}

internal fun parseCloudText(body: String): String = JSONObject(body)
    .optString("text")
    .trim()
    .also { require(it.isNotEmpty()) { "Provider returned an empty transcript" } }

private const val MAX_UPLOAD_NAME_LENGTH = 120
private const val M4A_EXTENSION = ".m4a"

class CloudTranscriptionWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val provider = inputData.getString(KEY_PROVIDER)?.let(CloudProvider::fromId)
            ?: return Result.failure()
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return Result.failure()
        val key = inputData.getString(KEY_RECORDING) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val size = inputData.getLong(KEY_SIZE, -1)
        val apiKey = SecretStore(applicationContext).get(provider)
            ?: return Result.success(workDataOf(KEY_ERROR to "API key is not configured"))

        return try {
            setForeground(createForegroundInfo(provider, key))
            val recording = Recording(uri, name, size, lastModified = 0, key = key)
            val transcript = withContext(Dispatchers.IO) {
                CloudClient(applicationContext.contentResolver).transcribe(provider, apiKey, recording)
            }
            withContext(Dispatchers.IO) {
                TranscriptStore(applicationContext).write(key, transcript, provider.source)
            }
            Result.success(workDataOf(KEY_PROVIDER to provider.id))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Result.success(workDataOf(KEY_ERROR to exception.cloudUserMessage()))
        }
    }

    private fun createForegroundInfo(provider: CloudProvider, key: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                applicationContext.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val providerName = applicationContext.getString(provider.nameResource())
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(applicationContext.getString(R.string.notification_cloud, providerName))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        val id = ("$key-${provider.id}".hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    companion object {
        const val GLOBAL_TAG = "cloud-transcriptions"
        const val KEY_ERROR = "error"
        const val KEY_PROVIDER = "provider"

        private const val KEY_URI = "uri"
        private const val KEY_RECORDING = "recording"
        private const val KEY_NAME = "name"
        private const val KEY_SIZE = "size"
        private const val NOTIFICATION_CHANNEL = "transcription"

        fun recordingTag(key: String, provider: CloudProvider) = "cloud-$key-${provider.id}"

        fun input(recording: Recording, provider: CloudProvider) = workDataOf(
            KEY_PROVIDER to provider.id,
            KEY_URI to recording.uri.toString(),
            KEY_RECORDING to recording.key,
            KEY_NAME to recording.name,
            KEY_SIZE to recording.size,
        )
    }
}

class CloudTranscriptionQueue(context: Context) {
    private val manager = WorkManager.getInstance(context.applicationContext)

    fun enqueue(recording: Recording, provider: CloudProvider) {
        val uniqueName = CloudTranscriptionWorker.recordingTag(recording.key, provider)
        val request = OneTimeWorkRequestBuilder<CloudTranscriptionWorker>()
            .setInputData(CloudTranscriptionWorker.input(recording, provider))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(CloudTranscriptionWorker.GLOBAL_TAG)
            .addTag(uniqueName)
            .build()
        manager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
    }

    suspend fun cancelAll() = withContext(Dispatchers.IO) {
        manager.cancelAllWorkByTag(CloudTranscriptionWorker.GLOBAL_TAG).result.get()
    }
}

internal fun CloudProvider.nameResource(): Int = when (this) {
    CloudProvider.OPENAI -> R.string.provider_openai
    CloudProvider.GEMINI -> R.string.provider_gemini
    CloudProvider.XAI -> R.string.provider_xai
    CloudProvider.GROQ -> R.string.provider_groq
}

private fun Exception.cloudUserMessage(): String = when (this) {
    is CloudApiException, is IOException, is IllegalArgumentException ->
        message?.take(300) ?: "Cloud transcription failed"
    else -> "Cloud transcription failed"
}
