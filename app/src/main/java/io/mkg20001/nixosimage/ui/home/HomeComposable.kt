package io.mkg20001.nixosimage.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.content.ContextCompat.startActivity
import io.mkg20001.nixosimage.BuildConfig
import io.mkg20001.nixosimage.R
import io.mkg20001.nixosimage.data.GitHubReleaseAsset
import io.mkg20001.nixosimage.data.GitHubReleaseClient
import io.mkg20001.nixosimage.install.ImageInstallMethod
import io.mkg20001.nixosimage.install.InstallMethods
import io.mkg20001.nixosimage.ui.ExtItem
import io.mkg20001.nixosimage.ui.MyDropdown
import io.mkg20001.nixosimage.ui.install.Install
import io.sentry.Sentry
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

sealed class HomeUiValues {
    object Loading : HomeUiValues()
    object Error : HomeUiValues()
    class Success(val methods: List<ImageInstallMethod>, val versions: List<GitHubReleaseAsset>) : HomeUiValues()
}

@Composable
fun HomeComposable() {
    var trigger by remember { mutableStateOf(0) }

    val stateMethod: MutableState<ExtItem?> = remember { mutableStateOf(null) }
    val stateVersion: MutableState<ExtItem?> = remember { mutableStateOf(null) }

    val customUrl = remember { mutableStateOf("") }
    val customDigest = remember { mutableStateOf("") }
    val selectedFileUri = remember { mutableStateOf<Uri?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        selectedFileUri.value = uri
    }

    val v by produceState<HomeUiValues>(initialValue = HomeUiValues.Loading, key1 = trigger) {
        value = HomeUiValues.Loading

        try {
            stateMethod.value = null
            stateVersion.value = null

            val methods =
                if (BuildConfig.ALLOW_ANY_METHOD) InstallMethods.methods
                else InstallMethods.availableMethods()

            val rel = GitHubReleaseClient.getReleases()
            if (rel == null) {
                value = HomeUiValues.Error
            } else {
                val releases = rel.map { it.getSupported() }.flatten()
                value = HomeUiValues.Success(methods, releases)
            }
        } catch(e: Exception) {
            e.printStackTrace()
            Sentry.captureException(e)
            value = HomeUiValues.Error
        }
    }

    val baseModifier = Modifier.fillMaxWidth()

    val headingModifer = Modifier.padding(0.dp, 10.dp)
    val headingStyle = TextStyle(color = MaterialTheme.colorScheme.primary, fontSize = 4.em)

    val listModifier = baseModifier.background(MaterialTheme.colorScheme.primaryContainer)
    val listStyle = TextStyle(color = MaterialTheme.colorScheme.onPrimaryContainer)

    val context = LocalContext.current

    val refresh = @Composable {
        Button(
            onClick = {
                trigger++
            },
            modifier = Modifier.padding(6.dp).testTag("refresh")
        ) {
            Text(stringResource(R.string.refresh))
        }
    }

    fun doInstall(r: GitHubReleaseAsset, m: ImageInstallMethod) {
        if (m.needsExternalStorage) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:" + context.packageName)
                context.startActivity(intent)
                Toast.makeText(context, context.getString(R.string.toast_external_storage), Toast.LENGTH_LONG).show()
                return
            }
        }

        val intent = Intent(context, Install::class.java)
        val b = Bundle()
        b.putSerializable("image", r)
        b.putString("method", m.id)
        intent.putExtras(b)
        startActivity(context, intent, null)
    }

    fun doCustomInstall(m: ImageInstallMethod) {
        val intent = Intent(context, Install::class.java)
        val b = Bundle()
        b.putString("method", m.id)

        if (selectedFileUri.value != null) {
            b.putString("custom_file_source", selectedFileUri.value.toString())
        } else if (customUrl.value.isNotBlank()) {
            b.putString("custom_url", customUrl.value.trim())
            if (customDigest.value.isNotBlank()) {
                b.putString("custom_digest", customDigest.value.trim())
            }
        }

        intent.putExtras(b)
        startActivity(context, intent, null)
    }

    Column(
        modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.version) + " " + BuildConfig.VERSION_NAME, modifier = baseModifier.padding(0.dp, 10.dp).testTag("loaded_ui"))
        when (val s = v) {
            is HomeUiValues.Loading ->
                @Composable {
                    Text(stringResource(R.string.introduction_loading), modifier = baseModifier)
                    CircularProgressIndicator(modifier = Modifier.padding(12.dp).testTag("loading_ui"))
                }
            is HomeUiValues.Error ->
                @Composable {
                    Text(stringResource(R.string.introduction_error), modifier = baseModifier)
                    refresh()
                }
            is HomeUiValues.Success -> {
                Text(stringResource(R.string.introduction), modifier = baseModifier.padding(0.dp, 10.dp).testTag("loaded_ui"))
                Text(stringResource(R.string.menu_method), modifier = headingModifer, style = headingStyle)
                MenuInstallMethods(modifier = listModifier, style = listStyle, selectedItem = stateMethod.value, methods = s.methods) {
                    stateMethod.value = it
                }
                Text(stringResource(R.string.menu_version), modifier = headingModifer, style = headingStyle)
                MenuReleaseAsssets(modifier = listModifier, style = listStyle, selectedItem = stateVersion.value, assets = s.versions) {
                    stateVersion.value = it
                }
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.padding(6.dp)) {
                    Button(
                        onClick = {
                            doInstall(
                                s.versions.filter { it.id == stateVersion.value!!.id }.getOrNull(0)!!,
                                InstallMethods.getMethod(stateMethod.value!!.id)!!
                            )
                        },
                        enabled = stateMethod.value?.real == true && stateVersion.value?.real == true,
                        modifier = Modifier.padding(6.dp).testTag("install")
                    ) {
                        Text(stringResource(R.string.install))
                    }

                    refresh()
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                Text(
                    stringResource(R.string.custom_image_header),
                    modifier = headingModifer,
                    style = headingStyle
                )

                OutlinedTextField(
                    value = customUrl.value,
                    onValueChange = { customUrl.value = it },
                    label = { Text(stringResource(R.string.custom_image_url_label)) },
                    placeholder = { Text(stringResource(R.string.custom_image_url_hint)) },
                    modifier = baseModifier.padding(bottom = 8.dp),
                    singleLine = true,
                    enabled = selectedFileUri.value == null
                )

                OutlinedTextField(
                    value = customDigest.value,
                    onValueChange = { customDigest.value = it },
                    label = { Text(stringResource(R.string.custom_image_digest_label)) },
                    modifier = baseModifier.padding(bottom = 8.dp),
                    singleLine = true,
                    enabled = selectedFileUri.value == null
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = baseModifier.padding(bottom = 8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            filePickerLauncher.launch(arrayOf("application/gzip", "application/x-gzip", "application/x-tar", "*/*"))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.custom_image_pick_file))
                    }

                    if (selectedFileUri.value != null) {
                        Button(onClick = { selectedFileUri.value = null }) {
                            Text("Clear")
                        }
                    }
                }

                if (selectedFileUri.value != null) {
                    Text(
                        stringResource(R.string.custom_image_selected_file, selectedFileUri.value!!.lastPathSegment ?: "file"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = baseModifier.padding(bottom = 8.dp)
                    )
                }

                val hasCustomSource = customUrl.value.isNotBlank() || selectedFileUri.value != null

                Button(
                    onClick = {
                        val method = InstallMethods.getMethod(stateMethod.value!!.id)
                        if (method != null) {
                            doCustomInstall(method)
                        }
                    },
                    enabled = stateMethod.value?.real == true && hasCustomSource,
                    modifier = Modifier.padding(6.dp)
                ) {
                    Text(stringResource(R.string.custom_image_install))
                }
            }
        }
    }

}

@Composable
fun MenuInstallMethods(methods: List<ImageInstallMethod>, modifier: Modifier, style: TextStyle, selectedItem: ExtItem?, onValueChange: (ExtItem) -> Unit) {
    val items = if (methods.isEmpty()) listOf(ExtItem(stringResource(R.string.no_method)))
        else methods.map {
            if (!it.isAvailable()) {
                ExtItem(it.id, "#DEBUG# " + stringResource(it.display))
            } else {
                ExtItem(it.id, stringResource(it.display))
            }
        }

    MyDropdown(modifier = modifier, items = items, style = style, selectedItem = selectedItem) { onValueChange(it) }
}

fun formatDateTimeLocaleAware(dateTime: LocalDateTime): String {
    val currentLocale = Locale.getDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(currentLocale)
    return dateTime.format(formatter)
}

@Composable
fun MenuReleaseAsssets(assets: List<GitHubReleaseAsset>, modifier: Modifier, style: TextStyle, selectedItem: ExtItem?, onValueChange: (ExtItem) -> Unit) {
    val items = if (assets.isEmpty()) listOf(ExtItem(stringResource(R.string.no_compat)))
    else assets.map {
        val date = LocalDateTime.parse(it.updatedAt as String, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        ExtItem(it.id, stringResource(R.string.format_version_info, it.version, formatDateTimeLocaleAware(date), it.arch))
    }

    MyDropdown(modifier = modifier, items = items, style = style, selectedItem = selectedItem) { onValueChange(it) }
}
