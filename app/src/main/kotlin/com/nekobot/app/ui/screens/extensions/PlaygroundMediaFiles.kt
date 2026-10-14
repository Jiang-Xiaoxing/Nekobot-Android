package com.nekobot.app.ui.screens.extensions

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.nekobot.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal suspend fun readPlaygroundFile(context: Context, uri: Uri, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { context.getString(R.string.playground_file_too_large, maxBytes / 1024 / 1024) }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error(context.getString(R.string.imggen_file_not_found))
    require(bytes.isNotEmpty()) { context.getString(R.string.playground_empty_file) }
    bytes
}

/** 导出和分享都放在 IO 线程；共享副本只放入 FileProvider 已开放的 cache 目录。 */
@Composable
internal fun PlaygroundMediaActions(url: String, mimeType: String, image: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var pendingSource by rememberSaveable { mutableStateOf<String?>(null) }
    val extension = when (mimeType) { "audio/mpeg" -> "mp3"; "image/jpeg" -> "jpg"; else -> mimeType.substringAfter('/') }
    val fileName = "nekobot_${System.currentTimeMillis()}.$extension"
    fun task(block: suspend () -> Unit) {
        if (working) return
        working = true
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(context, e.message ?: context.getString(R.string.imggen_save_failed), Toast.LENGTH_SHORT).show() }
            finally { working = false }
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { destination ->
        val source = pendingSource
        pendingSource = null
        if (destination != null && source != null) task {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    context.contentResolver.openInputStream(Uri.parse(source))?.use { it.copyTo(output) }
                        ?: error(context.getString(R.string.imggen_file_not_found))
                } ?: error(context.getString(R.string.imggen_save_failed))
            }
            Toast.makeText(context, context.getString(R.string.playground_saved), Toast.LENGTH_SHORT).show()
        }
    }
    TextButton(enabled = !working, onClick = {
        if (image && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) task {
            withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Nekobot")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val destination = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error(context.getString(R.string.imggen_save_failed))
                try {
                    resolver.openOutputStream(destination)?.use { output ->
                        resolver.openInputStream(Uri.parse(url))?.use { it.copyTo(output) }
                            ?: error(context.getString(R.string.imggen_file_not_found))
                    } ?: error(context.getString(R.string.imggen_save_failed))
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(destination, values, null, null)
                } catch (e: Exception) {
                    resolver.delete(destination, null, null)
                    throw e
                }
            }
            Toast.makeText(context, context.getString(R.string.imggen_saved_to_gallery), Toast.LENGTH_SHORT).show()
        } else {
            pendingSource = url
            saveLauncher.launch(fileName)
        }
    }) { Text(stringResource(if (image) R.string.playground_save_image else R.string.playground_save_audio)) }
    IconButton(enabled = !working, onClick = {
        task {
            val sharedUri = withContext(Dispatchers.IO) {
                val file = File(context.cacheDir, "media_export/$fileName").apply { parentFile?.mkdirs() }
                context.contentResolver.openInputStream(Uri.parse(url))?.use { input -> file.outputStream().use { input.copyTo(it) } }
                    ?: error(context.getString(R.string.imggen_file_not_found))
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, sharedUri)
                clipData = ClipData.newRawUri(fileName, sharedUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.common_share)))
        }
    }) { Icon(Icons.Default.Share, stringResource(R.string.common_share)) }
}
