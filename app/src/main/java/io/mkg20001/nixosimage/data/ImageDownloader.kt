package io.mkg20001.nixosimage.data

import android.content.Context
import android.util.Log
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.http2.StreamResetException
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest

suspend fun downloadFile(
    context: Context,
    fileUrl: String,
    fileName: String,
    digest: String?,
    onProgress: (percent: Int) -> Unit,
): File? {
    return withContext(Dispatchers.IO) {
        try {
            val file = File(context.cacheDir, fileName)
            var retry = 0

            val digestAlgo: String?
            val expectedHex: String?

            if (digest != null) {
                val digestSplit = digest.split(":", limit = 2)
                if (digestSplit.size != 2) {
                    throw IllegalArgumentException("Invalid digest format: $digest")
                }
                digestAlgo = when (digestSplit[0].lowercase()) {
                    "sha256" -> "SHA-256"
                    "sha512" -> "SHA-512"
                    else -> throw IllegalArgumentException("Unsupported digest algorithm: ${digestSplit[0]}")
                }
                expectedHex = digestSplit[1].lowercase()
                Log.d("DL", "Expected digest=${digest}, algo=${digestAlgo}, expectedHex=${expectedHex}")
            } else {
                digestAlgo = null
                expectedHex = null
                Log.d("DL", "No digest provided, skipping verification")
            }

            while (true) {
                retry++
                Log.d("DL", "Trying download, try $retry/3")

                try {
                    val alreadyDownloadedBytes = if (file.exists()) file.length() else 0L

                    val client = OkHttpClient()
                    val request = Request.Builder().url(fileUrl).apply {
                        if (alreadyDownloadedBytes > 0) {
                            addHeader("Range", "bytes=$alreadyDownloadedBytes-")
                        }
                    }.build()

                    val response = client.newCall(request).execute()
                    val body = response.body ?: return@withContext null

                    if (file.exists() && body.contentLength() == file.length()) {
                        onProgress(100)
                        return@withContext file
                    }

                    if (alreadyDownloadedBytes > 0) {
                        if (response.code != 206) {
                            Log.w("DL", "Server ignored Range request, restarting download")
                            file.delete()
                            continue
                        }
                        val range = response.header("Content-Range")
                        if ((range == null || !range.startsWith("bytes $alreadyDownloadedBytes-"))) {
                            Log.w("DL", "Missmatched range, restarting download")
                            file.delete()
                            continue
                        }
                    }

                    val progressStream = ProgressStream(
                        body.source().inputStream(),
                        body.contentLength().toDouble(),
                        alreadyDownloadedBytes,
                        onProgress,
                    )

                    val outputStream = FileOutputStream(file, true)

                    if (digestAlgo != null) {
                        val md = MessageDigest.getInstance(digestAlgo)
                        if (alreadyDownloadedBytes < 1) {
                            Log.d("DL", "Full download, hash during download")
                            DigestStream(progressStream, md).use { input ->
                                outputStream.use { output ->
                                    input.copyTo(output)
                                }
                            }
                            if (!md.digest().contentEquals(hexToByteArray(expectedHex!!))) {
                                Log.w("DL", "Hashsum mismatch - wanted ${expectedHex}")
                                file.delete()
                                continue
                            }
                        } else {
                            Log.d("DL", "Partial, rehash fully")
                            progressStream.use { input ->
                                outputStream.use { output ->
                                    input.copyTo(output)
                                }
                            }
                            val fullDigest = MessageDigest.getInstance(digestAlgo)
                            DigestStream(file.inputStream(), fullDigest).use { input ->
                                input.copyTo(OutputStream.nullOutputStream())
                            }
                            if (!fullDigest.digest().contentEquals(hexToByteArray(expectedHex!!))) {
                                Log.w("DL", "Hashsum mismatch - wanted ${expectedHex}")
                                file.delete()
                                continue
                            }
                        }
                    } else {
                        progressStream.use { input ->
                            outputStream.use { output ->
                                input.copyTo(output)
                            }
                        }
                    }

                    break
                } catch(e: StreamResetException) {
                    if (retry == 3) {
                        throw e
                    }
                } catch (e: java.net.SocketException) {
                    if (e.message?.contains("Software caused connection abort") != true) {
                        throw e
                    }

                    if (retry == 3) {
                        throw e
                    }
                }
            }

            file
        } catch (e: Exception) {
            e.printStackTrace()
            Sentry.captureException(e)
            null
        }
    }
}