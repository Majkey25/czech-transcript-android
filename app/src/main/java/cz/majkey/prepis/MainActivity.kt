package cz.majkey.prepis

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PrepisTheme {
                PrepisApp(model)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val folders = FolderStore(app)
    private val scanner = RecordingScanner(app)
    private val transcripts = TranscriptStore(app)
    private val queue = TranscriptionQueue(app)
    private val cloudQueue = CloudTranscriptionQueue(app)
    private val secrets = SecretStore(app)
    private val workManager = WorkManager.getInstance(app)
    private val recordings = MutableStateFlow<List<Recording>>(emptyList())
    private val workInfos = workManager.getWorkInfosByTagFlow(TranscriptionWorker.GLOBAL_TAG)
    private var refreshJob: Job? = null

    private val _folderUri = MutableStateFlow(folders.load())
    val folderUri: StateFlow<Uri?> = _folderUri.asStateFlow()

    private val _loading = MutableStateFlow(_folderUri.value != null)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _folderError = MutableStateFlow<String?>(null)
    val folderError: StateFlow<String?> = _folderError.asStateFlow()

    private val _configuredProviders = MutableStateFlow<Set<CloudProvider>>(emptySet())
    val configuredProviders: StateFlow<Set<CloudProvider>> = _configuredProviders.asStateFlow()

    private val _settingsMessage = MutableStateFlow<String?>(null)
    val settingsMessage: StateFlow<String?> = _settingsMessage.asStateFlow()

    val cloudWorkInfos: StateFlow<List<WorkInfo>> = workManager
        .getWorkInfosByTagFlow(CloudTranscriptionWorker.GLOBAL_TAG)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rows: StateFlow<List<RecordingRow>> = combine(recordings, workInfos) { files, work ->
        withContext(Dispatchers.IO) {
            files.map { recording -> recording.toRow(work, transcripts.exists(recording.key)) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refreshConfiguredProviders()
    }

    fun selectFolder(uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            _folderError.value = app.getString(R.string.folder_permission_failed)
            return
        }

        viewModelScope.launch {
            try {
                val previous = folders.load()
                if (previous != uri) {
                    queue.cancelAll()
                    cloudQueue.cancelAll()
                    folders.save(uri)
                    previous?.let {
                        runCatching {
                            app.contentResolver.releasePersistableUriPermission(
                                it,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                    }
                }
                _folderUri.value = uri
                _folderError.value = null
                refresh()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                _folderError.value = app.getString(R.string.folder_change_failed)
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val uri = folders.load()
            _folderUri.value = uri
            if (uri == null) {
                recordings.value = emptyList()
                _loading.value = false
                return@launch
            }

            _loading.value = true
            _folderError.value = null
            try {
                val files = withContext(Dispatchers.IO) { scanner.scan(uri) }
                recordings.value = files
                queue.enqueueMissing(files)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: SecurityException) {
                folders.clear()
                _folderUri.value = null
                recordings.value = emptyList()
                _folderError.value = app.getString(R.string.folder_permission_lost)
            } catch (_: Exception) {
                _folderError.value = app.getString(R.string.folder_read_failed)
            } finally {
                _loading.value = false
            }
        }
    }

    fun enqueue(recording: Recording, replace: Boolean = false) {
        viewModelScope.launch { queue.enqueue(recording, replace) }
    }

    fun enqueueCloud(recording: Recording, provider: CloudProvider) {
        if (provider in _configuredProviders.value) cloudQueue.enqueue(recording, provider)
    }

    fun saveApiKey(provider: CloudProvider, key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _settingsMessage.value = try {
                secrets.put(provider, key)
                _configuredProviders.value = secrets.configuredProviders()
                app.getString(R.string.api_key_saved, app.getString(provider.nameResource()))
            } catch (_: IllegalArgumentException) {
                app.getString(R.string.api_key_invalid)
            } catch (_: Exception) {
                app.getString(R.string.api_key_save_failed)
            }
        }
    }

    fun removeApiKey(provider: CloudProvider) {
        viewModelScope.launch(Dispatchers.IO) {
            secrets.remove(provider)
            _configuredProviders.value = secrets.configuredProviders()
            _settingsMessage.value = app.getString(
                R.string.api_key_removed,
                app.getString(provider.nameResource()),
            )
        }
    }

    fun clearSettingsMessage() {
        _settingsMessage.value = null
    }

    suspend fun readTranscripts(key: String): Map<TranscriptSource, String> =
        withContext(Dispatchers.IO) {
            TranscriptSource.entries.mapNotNull { source ->
                transcripts.read(key, source)?.let { source to it }
            }.toMap()
        }

    private fun refreshConfiguredProviders() {
        viewModelScope.launch(Dispatchers.IO) {
            _configuredProviders.value = secrets.configuredProviders()
        }
    }

    private fun Recording.toRow(work: List<WorkInfo>, transcriptExists: Boolean): RecordingRow {
        if (transcriptExists) return RecordingRow(this, RecordingStatus.DONE)

        val tagged = work.filter { TranscriptionWorker.recordingTag(key) in it.tags }
        val active = tagged.lastOrNull { it.state == WorkInfo.State.RUNNING } ?:
            tagged.lastOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        if (active != null) {
            val phase = active.progress.getString(TranscriptionWorker.KEY_PHASE)
            return RecordingRow(
                this,
                when (phase) {
                    TranscriptionWorker.PHASE_MODEL -> RecordingStatus.MODEL
                    TranscriptionWorker.PHASE_TRANSCRIBING -> RecordingStatus.TRANSCRIBING
                    else -> RecordingStatus.WAITING
                },
            )
        }

        val error = tagged.lastOrNull { it.state == WorkInfo.State.SUCCEEDED }
            ?.outputData
            ?.getString(TranscriptionWorker.KEY_ERROR)
        return RecordingRow(this, if (error == null) RecordingStatus.WAITING else RecordingStatus.ERROR)
    }
}

data class RecordingRow(
    val recording: Recording,
    val status: RecordingStatus,
)

enum class RecordingStatus {
    WAITING,
    MODEL,
    TRANSCRIBING,
    DONE,
    ERROR,
}

@Composable
private fun PrepisApp(model: MainViewModel) {
    val folderUri by model.folderUri.collectAsState()
    val loading by model.loading.collectAsState()
    val folderError by model.folderError.collectAsState()
    val rows by model.rows.collectAsState()
    val configuredProviders by model.configuredProviders.collectAsState()
    val cloudWorkInfos by model.cloudWorkInfos.collectAsState()
    val settingsMessage by model.settingsMessage.collectAsState()
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val selected = rows.firstOrNull { it.recording.key == selectedKey }
    val picker = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        uri?.let(model::selectFolder)
    }

    when {
        settingsOpen -> AdvancedSettingsScreen(
            configuredProviders = configuredProviders,
            message = settingsMessage,
            onBack = {
                model.clearSettingsMessage()
                settingsOpen = false
            },
            onSave = model::saveApiKey,
            onRemove = model::removeApiKey,
        )

        selected != null -> TranscriptScreen(
            row = selected,
            configuredProviders = configuredProviders,
            cloudWorkInfos = cloudWorkInfos,
            readTranscripts = model::readTranscripts,
            onBack = { selectedKey = null },
            onRetranscribe = {
                model.enqueue(selected.recording, replace = true)
                selectedKey = null
            },
            onCloudTranscribe = { provider -> model.enqueueCloud(selected.recording, provider) },
        )

        folderUri == null -> FolderScreen(
            message = folderError,
            onPickFolder = { picker.launch(null) },
        )

        else -> RecordingListScreen(
            rows = rows,
            loading = loading,
            error = folderError,
            onPickFolder = { picker.launch(folderUri) },
            onAdvancedSettings = { settingsOpen = true },
            onRetry = model::refresh,
            onRecording = { row ->
                if (row.status == RecordingStatus.DONE) {
                    selectedKey = row.recording.key
                } else {
                    model.enqueue(row.recording)
                }
            },
        )
    }
}

@Composable
private fun FolderScreen(message: String?, onPickFolder: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.choose_folder_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                message ?: stringResource(R.string.choose_folder_description),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onPickFolder) {
                Text(stringResource(R.string.choose_folder_action))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingListScreen(
    rows: List<RecordingRow>,
    loading: Boolean,
    error: String?,
    onPickFolder: () -> Unit,
    onAdvancedSettings: () -> Unit,
    onRetry: () -> Unit,
    onRecording: (RecordingRow) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val menuDescription = stringResource(R.string.menu)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.semantics {
                            contentDescription = menuDescription
                        },
                    ) {
                        Text("⋮", fontSize = 28.sp)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.change_folder)) },
                            onClick = {
                                menuOpen = false
                                onPickFolder()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.advanced_settings)) },
                            onClick = {
                                menuOpen = false
                                onAdvancedSettings()
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> CenteredProgress(padding)
            error != null -> CenteredMessage(error, stringResource(R.string.try_again), padding, onRetry)
            rows.isEmpty() -> CenteredMessage(stringResource(R.string.empty_folder), null, padding, null)
            else -> LazyColumn(contentPadding = padding) {
                items(rows, key = { it.recording.key }) { row ->
                    RecordingItem(row, onRecording)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RecordingItem(row: RecordingRow, onRecording: (RecordingRow) -> Unit) {
    val unknownDate = stringResource(R.string.unknown_date)
    val date = remember(row.recording.lastModified, unknownDate) {
        if (row.recording.lastModified > 0) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(row.recording.lastModified))
        } else {
            unknownDate
        }
    }
    val status = statusText(row.status)
    val description = stringResource(
        R.string.recording_description,
        row.recording.name,
        date,
        status,
    )
    ListItem(
        headlineContent = {
            Text(
                row.recording.displayName(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text("$date · $status") },
        trailingContent = {
            when (row.status) {
                RecordingStatus.MODEL, RecordingStatus.TRANSCRIBING ->
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                RecordingStatus.DONE -> Text("✓", style = MaterialTheme.typography.titleLarge)
                RecordingStatus.ERROR -> Text("!", style = MaterialTheme.typography.titleLarge)
                RecordingStatus.WAITING -> Text("…", style = MaterialTheme.typography.titleLarge)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRecording(row) }
            .semantics {
                contentDescription = description
            },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptScreen(
    row: RecordingRow,
    configuredProviders: Set<CloudProvider>,
    cloudWorkInfos: List<WorkInfo>,
    readTranscripts: suspend (String) -> Map<TranscriptSource, String>,
    onBack: () -> Unit,
    onRetranscribe: () -> Unit,
    onCloudTranscribe: (CloudProvider) -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var cloudDialogOpen by remember { mutableStateOf(false) }
    var transcripts by remember(row.recording.key) {
        mutableStateOf<Map<TranscriptSource, String>>(emptyMap())
    }
    var selectedSource by rememberSaveable(row.recording.key) { mutableStateOf(TranscriptSource.LOCAL) }
    var requestedProvider by remember(row.recording.key) { mutableStateOf<CloudProvider?>(null) }
    var loaded by remember(row.recording.key) { mutableStateOf(false) }
    val backDescription = stringResource(R.string.back)
    val menuDescription = stringResource(R.string.menu)
    val relevantWork = cloudWorkInfos.filter { info ->
        CloudProvider.entries.any { provider ->
            CloudTranscriptionWorker.recordingTag(row.recording.key, provider) in info.tags
        }
    }
    val activeWork = relevantWork.lastOrNull {
        it.state == WorkInfo.State.RUNNING ||
            it.state == WorkInfo.State.ENQUEUED ||
            it.state == WorkInfo.State.BLOCKED
    }
    val activeProvider = activeWork?.let { info ->
        CloudProvider.entries.firstOrNull { provider ->
            CloudTranscriptionWorker.recordingTag(row.recording.key, provider) in info.tags
        }
    }
    val requestedError = requestedProvider?.let { provider ->
        relevantWork.lastOrNull {
            CloudTranscriptionWorker.recordingTag(row.recording.key, provider) in it.tags &&
                it.state == WorkInfo.State.SUCCEEDED
        }?.outputData?.getString(CloudTranscriptionWorker.KEY_ERROR)
    }

    LaunchedEffect(row.recording.key, cloudWorkInfos, requestedProvider) {
        transcripts = readTranscripts(row.recording.key)
        if (selectedSource !in transcripts) selectedSource = TranscriptSource.LOCAL
        requestedProvider?.takeIf { it.source in transcripts }?.let {
            selectedSource = it.source
            requestedProvider = null
        }
        loaded = true
    }
    val transcript = transcripts[selectedSource] ?: transcripts[TranscriptSource.LOCAL]

    if (cloudDialogOpen) {
        AlertDialog(
            onDismissRequest = { cloudDialogOpen = false },
            title = { Text(stringResource(R.string.cloud_transcription)) },
            text = {
                Column {
                    Text(stringResource(R.string.cloud_upload_notice))
                    Spacer(Modifier.height(8.dp))
                    CloudProvider.entries.filter { it in configuredProviders }.forEach { provider ->
                        TextButton(
                            onClick = {
                                requestedProvider = provider
                                onCloudTranscribe(provider)
                                cloudDialogOpen = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(provider.nameResource()))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { cloudDialogOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backDescription },
                    ) {
                        Text("‹", fontSize = 36.sp)
                    }
                },
                title = {
                    Text(
                        row.recording.displayName(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.semantics { contentDescription = menuDescription },
                    ) {
                        Text("⋮", fontSize = 28.sp)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_text)) },
                            enabled = transcript != null,
                            onClick = {
                                menuOpen = false
                                copyTranscript(context, transcript.orEmpty())
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.transcribe_again)) },
                            onClick = {
                                menuOpen = false
                                onRetranscribe()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cloud_transcription)) },
                            enabled = configuredProviders.isNotEmpty() && activeProvider == null,
                            onClick = {
                                menuOpen = false
                                cloudDialogOpen = true
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            !loaded -> CenteredProgress(padding)
            transcript == null -> CenteredMessage(
                stringResource(R.string.transcript_read_failed),
                stringResource(R.string.back),
                padding,
                onBack,
            )
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (transcripts.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.source), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { sourceMenuOpen = true }) {
                                Text(sourceName(selectedSource))
                            }
                            DropdownMenu(
                                expanded = sourceMenuOpen,
                                onDismissRequest = { sourceMenuOpen = false },
                            ) {
                                TranscriptSource.entries.filter { it in transcripts }.forEach { source ->
                                    DropdownMenuItem(
                                        text = { Text(sourceName(source)) },
                                        onClick = {
                                            selectedSource = source
                                            sourceMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                if (activeProvider != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(
                            R.string.cloud_in_progress,
                            stringResource(activeProvider.nameResource()),
                        ),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (requestedError != null) {
                    Text(
                        requestedError,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = transcript.orEmpty(),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun sourceName(source: TranscriptSource): String = when (source) {
    TranscriptSource.LOCAL -> stringResource(R.string.source_local)
    TranscriptSource.OPENAI -> stringResource(R.string.provider_openai)
    TranscriptSource.GEMINI -> stringResource(R.string.provider_gemini)
    TranscriptSource.XAI -> stringResource(R.string.provider_xai)
    TranscriptSource.GROQ -> stringResource(R.string.provider_groq)
}

@Composable
private fun CenteredProgress(padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredMessage(
    message: String,
    action: String?,
    padding: PaddingValues,
    onAction: (() -> Unit)?,
) {
    Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            if (action != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
private fun statusText(status: RecordingStatus): String = stringResource(
    when (status) {
        RecordingStatus.WAITING -> R.string.status_waiting
        RecordingStatus.MODEL -> R.string.status_model
        RecordingStatus.TRANSCRIBING -> R.string.status_transcribing
        RecordingStatus.DONE -> R.string.status_done
        RecordingStatus.ERROR -> R.string.status_error
    },
)

private fun copyTranscript(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.transcript), text))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}

private fun Recording.displayName(): String = if (isM4a(name)) name.dropLast(4) else name

@Composable
private fun PrepisTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
