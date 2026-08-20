package cz.majkey.prepis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TranscriptionWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return Result.failure()
        val key = inputData.getString(KEY_RECORDING) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val models = ModelStore(applicationContext)
        var modelReady = models.isReady()

        return try {
            updateState(PHASE_MODEL, applicationContext.getString(R.string.notification_model))
            val modelDirectory = withContext(Dispatchers.IO) { models.ensureInstalled() }
            modelReady = true

            updateState(
                PHASE_TRANSCRIBING,
                applicationContext.getString(R.string.notification_transcribing, name),
            )
            val transcript = withContext(Dispatchers.IO) {
                AudioTranscriber(applicationContext.contentResolver, modelDirectory).transcribe(uri)
            }
            withContext(Dispatchers.IO) { TranscriptStore(applicationContext).write(key, transcript) }
            Result.success()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (!modelReady && exception is IOException &&
                exception !is ModelIntegrityException && runAttemptCount < MAX_DOWNLOAD_RETRIES
            ) {
                Result.retry()
            } else {
                Result.success(workDataOf(KEY_ERROR to exception.userMessage()))
            }
        }
    }

    private suspend fun updateState(phase: String, notificationText: String) {
        setProgress(workDataOf(KEY_PHASE to phase))
        setForeground(createForegroundInfo(notificationText))
    }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                applicationContext.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        val notificationId = (inputData.getString(KEY_RECORDING).hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val GLOBAL_TAG = "prepis-transcriptions"
        const val KEY_PHASE = "phase"
        const val KEY_ERROR = "error"
        const val PHASE_MODEL = "model"
        const val PHASE_TRANSCRIBING = "transcribing"

        private const val KEY_URI = "uri"
        private const val KEY_RECORDING = "recording"
        private const val KEY_NAME = "name"
        private const val NOTIFICATION_CHANNEL = "transcription"
        private const val MAX_DOWNLOAD_RETRIES = 4

        fun recordingTag(key: String) = "recording-$key"

        fun input(recording: Recording) = workDataOf(
            KEY_URI to recording.uri.toString(),
            KEY_RECORDING to recording.key,
            KEY_NAME to recording.name,
        )
    }
}

class TranscriptionQueue(context: Context) {
    private val appContext = context.applicationContext
    private val manager = WorkManager.getInstance(appContext)
    private val transcripts = TranscriptStore(appContext)

    suspend fun enqueueMissing(recordings: List<Recording>) {
        for (recording in recordings) {
            if (!transcripts.exists(recording.key)) enqueue(recording, automatic = true)
        }
    }

    suspend fun enqueue(
        recording: Recording,
        replace: Boolean = false,
        automatic: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val tag = TranscriptionWorker.recordingTag(recording.key)
        val existing = manager.getWorkInfosByTag(tag).get()
        val active = existing.any { it.state.isActive() }
        if (active) return@withContext
        if (automatic && existing.any {
                it.state == WorkInfo.State.SUCCEEDED &&
                    it.outputData.getString(TranscriptionWorker.KEY_ERROR) != null
            }
        ) {
            return@withContext
        }

        if (replace && !transcripts.delete(recording.key)) {
            throw IOException("The previous transcript cannot be removed")
        }

        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(TranscriptionWorker.input(recording))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TranscriptionWorker.GLOBAL_TAG)
            .addTag(tag)
            .build()
        manager.beginUniqueWork(QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request).enqueue()
    }

    suspend fun cancelAll() = withContext(Dispatchers.IO) {
        manager.cancelUniqueWork(QUEUE_NAME).result.get()
    }

    private fun WorkInfo.State.isActive() =
        this == WorkInfo.State.ENQUEUED || this == WorkInfo.State.RUNNING || this == WorkInfo.State.BLOCKED

    private companion object {
        const val QUEUE_NAME = "transcription-queue"
    }
}

internal class ModelStore(context: Context) {
    private val directory = context.filesDir.resolve("models/whisper-small-int8")
    private val readyFile = directory.resolve(".ready")

    fun isReady(): Boolean = runCatching {
        readyFile.readTextOrNull() == MODEL_REVISION &&
            MODEL_FILES.all { directory.resolve(it.name).length() == it.size }
    }.getOrDefault(false)

    fun ensureInstalled(): File {
        if (isReady()) return directory
        check(directory.mkdirs() || directory.isDirectory) { "The model directory cannot be created" }

        MODEL_FILES.forEach(::install)
        readyFile.writeText(MODEL_REVISION, Charsets.UTF_8)
        return directory
    }

    private fun install(model: ModelFile) {
        val target = directory.resolve(model.name)
        if (target.length() == model.size && target.sha256() == model.sha256) return
        if (target.exists() && !target.delete()) throw IOException("Nelze nahradit ${model.name}")

        val partial = directory.resolve("${model.name}.part")
        if (partial.length() > model.size && !partial.delete()) {
            throw IOException("The partial download for ${model.name} cannot be repaired")
        }
        if (partial.length() < model.size) download(model, partial)

        if (partial.length() != model.size || partial.sha256() != model.sha256) {
            partial.delete()
            throw ModelIntegrityException("Kontrola modelu ${model.name} selhala")
        }
        Files.move(
            partial.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun download(model: ModelFile, partial: File) {
        val offset = partial.length()
        val connection = URL("$MODEL_BASE_URL/${model.name}").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "Prepis-Android/0.1.0")
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")

        try {
            val response = connection.responseCode
            val append = offset > 0 && response == HttpURLConnection.HTTP_PARTIAL
            if (response != HttpURLConnection.HTTP_OK && !append) {
                throw IOException("Downloading ${model.name} failed: HTTP $response")
            }
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(partial, append).buffered().use { output ->
                    input.copyTo(output, DOWNLOAD_BUFFER_SIZE)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).buffered().use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextOrNull(): String? = if (isFile) readText(Charsets.UTF_8) else null

    private data class ModelFile(
        val name: String,
        val size: Long,
        val sha256: String,
    )

    private companion object {
        const val MODEL_REVISION = "8f3c18b358db4d1f2fc1eae49d75cd20989e4309"
        const val MODEL_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/$MODEL_REVISION"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
        const val DOWNLOAD_BUFFER_SIZE = 1024 * 1024
        const val HASH_BUFFER_SIZE = 1024 * 1024

        val MODEL_FILES = listOf(
            ModelFile(
                AudioTranscriber.ENCODER_FILE,
                112_442_483L,
                "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
            ),
            ModelFile(
                AudioTranscriber.DECODER_FILE,
                262_226_114L,
                "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee",
            ),
            ModelFile(
                AudioTranscriber.TOKENS_FILE,
                816_730L,
                "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
            ),
        )
    }
}

internal class ModelIntegrityException(message: String) : IOException(message)

private fun Exception.userMessage(): String = message?.take(500) ?: "Transcription failed"
