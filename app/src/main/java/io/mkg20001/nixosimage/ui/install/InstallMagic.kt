package io.mkg20001.nixosimage.ui.install

import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat.startActivity
import io.mkg20001.nixosimage.R
import io.mkg20001.nixosimage.data.GitHubReleaseAsset
import io.mkg20001.nixosimage.data.downloadFile
import io.mkg20001.nixosimage.extra.ExtraImageUtils
import io.mkg20001.nixosimage.install.ImageInstallMethod
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

fun OpenTerminal(applicationContext: Context) {
    val packageName = "com.android.virtualization.terminal"
    val className = "com.android.virtualization.terminal.MainActivity"

    val intent = Intent().apply {
        setClassName(packageName, className)
    }
    intent.flags = FLAG_ACTIVITY_NEW_TASK

    try {
        startActivity(applicationContext, intent, null)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

class InstallMagic(
    val applicationContext: Context,
    val method: ImageInstallMethod,
    val asset: GitHubReleaseAsset? = null,
    val customUrl: String? = null,
    val customDigest: String? = null,
    val customFileSource: String? = null
) {
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done

    private val sourceLabel: String
        get() = when {
            asset != null -> "${asset.version} (${asset.arch})"
            customUrl != null -> customUrl
            customFileSource != null -> Uri.parse(customFileSource).lastPathSegment ?: "local file"
            else -> "unknown"
        }

    suspend fun run() {
        Sentry.addBreadcrumb(Breadcrumb().apply {
            message = "Installing"
            category = "task"
            level = SentryLevel.INFO
            setData("method", method.id)
            setData("source", sourceLabel)
        })

        val extra = ExtraImageUtils()

        updateStatus(R.string.install_step_downloading)
        _progress.tryEmit(0)

        fun installOK() {
            Log.i("Install", "ok")
            _done.tryEmit(true)
            if (method.needsLaunchTerminalAfterwards) {
                OpenTerminal(applicationContext)
            }
        }

        fun installFail(error: Int) {
            Log.e("Install", "failed")
            errorOut(error)
        }

        val file: File? = when {
            asset != null -> {
                Log.i("Download", "Downloading image from GitHub release")
                downloadFile(
                    context = applicationContext,
                    fileUrl = asset.url,
                    digest = asset.digest,
                    fileName = "image-cached-" + asset.id + "@" + asset.updatedAt + "#" + asset.digest
                ) { progress ->
                    if (_progress.value != progress) {
                        Log.d("Download", "Progress: $progress%")
                        _progress.tryEmit(progress)
                    }
                }
            }
            customUrl != null -> {
                Log.i("Download", "Downloading custom image from URL")
                downloadFile(
                    context = applicationContext,
                    fileUrl = customUrl,
                    digest = customDigest,
                    fileName = "custom-" + customUrl.toByteArray().let {
                        MessageDigest.getInstance("MD5").digest(it).joinToString("") { "%02x".format(it) }
                    }
                ) { progress ->
                    if (_progress.value != progress) {
                        Log.d("Download", "Progress: $progress%")
                        _progress.tryEmit(progress)
                    }
                }
            }
            customFileSource != null -> {
                Log.i("Download", "Importing local file")
                importContentUri(customFileSource)
            }
            else -> null
        }

        if (file != null) {
            Log.i("Install", "File ready: ${file.absolutePath}")

            updateStatus(R.string.install_step_installing)

            if (method.needsImageClean) {
                if (!extra.cleanupImage()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, R.string.remove_existing_image, Toast.LENGTH_LONG).show()
                    }
                }
            }

            try {
                val success = method.installImage(applicationContext, file, applicationContext.assets, _progress)

                if (success) {
                    installOK()
                } else {
                    installFail(R.string.install_err_image)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Sentry.captureException(e)
                installFail(R.string.install_err_image)
            }
        } else {
            Log.e("Download", "Failed to get image file")
            errorOut(R.string.install_err_network)
        }
    }

    private fun importContentUri(uriString: String): File? {
        return try {
            val uri = Uri.parse(uriString)
            val fileName = "custom-import-${System.currentTimeMillis()}.tar.gz"
            val destFile = File(applicationContext.cacheDir, fileName)

            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    val total = input.available().toLong()
                    var read = 0L
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        read += n
                        val pct = if (total > 0) ((read * 100) / total).toInt() else 0
                        _progress.tryEmit(pct.coerceIn(0, 100))
                    }
                }
            }

            destFile
        } catch (e: Exception) {
            e.printStackTrace()
            Sentry.captureException(e)
            null
        }
    }

    fun updateStatus(task: Int) {
        val out = buildString {
            append(applicationContext.getString(R.string.install_task))
            append(" ")
            append(applicationContext.getString(task))
            append("\n\n")
            when {
                asset != null -> {
                    append(applicationContext.getString(R.string.install_version))
                    append(" ")
                    append(asset!!.version)
                    append("\n\n")
                    append(applicationContext.getString(R.string.install_architecture))
                    append(" ")
                    append(asset!!.arch)
                }
                customUrl != null -> {
                    append("Source: URL\n")
                    append(customUrl)
                }
                customFileSource != null -> {
                    append("Source: local file\n")
                    append(Uri.parse(customFileSource).lastPathSegment ?: customFileSource)
                }
            }
        }
        _text.tryEmit(out)
    }

    fun errorOut(error: Int) {
        _text.tryEmit("Error! " + applicationContext.getString(error))
    }
}