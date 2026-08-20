package cz.majkey.prepis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdvancedSettingsScreen(
    configuredProviders: Set<CloudProvider>,
    message: String?,
    onBack: () -> Unit,
    onSave: (CloudProvider, String) -> Unit,
    onRemove: (CloudProvider) -> Unit,
) {
    val values = remember { mutableStateMapOf<CloudProvider, String>() }
    val backDescription = stringResource(R.string.back)

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
                title = { Text(stringResource(R.string.advanced_settings)) },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Text(
                    stringResource(R.string.advanced_privacy_notice),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                message?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider()
            }

            items(CloudProvider.entries, key = CloudProvider::id) { provider ->
                val value = values[provider].orEmpty()
                val configured = provider in configuredProviders
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(provider.nameResource()), style = MaterialTheme.typography.titleMedium)
                    Text(providerDescription(provider), style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(
                            if (configured) R.string.configured else R.string.not_configured,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { if (it.length <= MAX_KEY_LENGTH) values[provider] = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.api_key)) },
                        placeholder = {
                            Text(
                                stringResource(
                                    if (configured) R.string.api_key_configured else R.string.api_key_not_configured,
                                ),
                            )
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Row {
                        TextButton(
                            onClick = {
                                onSave(provider, value)
                                values[provider] = ""
                            },
                            enabled = value.trim().length >= MIN_KEY_LENGTH &&
                                value.trim().none(Char::isWhitespace),
                        ) {
                            Text(stringResource(R.string.save))
                        }
                        TextButton(
                            onClick = { onRemove(provider) },
                            enabled = configured,
                        ) {
                            Text(stringResource(R.string.remove))
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun providerDescription(provider: CloudProvider): String = stringResource(
    when (provider) {
        CloudProvider.OPENAI -> R.string.provider_openai_description
        CloudProvider.GEMINI -> R.string.provider_gemini_description
        CloudProvider.XAI -> R.string.provider_xai_description
        CloudProvider.GROQ -> R.string.provider_groq_description
    },
)

private const val MIN_KEY_LENGTH = 8
private const val MAX_KEY_LENGTH = 512
