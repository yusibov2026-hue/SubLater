package com.fromsub.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object SubtitleExporter {

    private val INVALID_FILENAME_CHARS = Regex("[^a-zA-Z0-9._\\-а-яА-ЯёЁ ]")

    private fun sanitizeFileName(fileName: String, format: String): String {
        return fileName.replace(INVALID_FILENAME_CHARS, "_").ifBlank {
            "subtitles.${format.ifBlank { "vtt" }}"
        }
    }

    private fun resolveMimeType(format: String): String = when (format.lowercase()) {
        "vtt" -> "text/vtt"
        "srt" -> "application/x-subrip"
        "ass" -> "text/x-ssa"
        else -> "text/plain"
    }

    fun saveSubtitleFile(
        context: Context,
        mainHandler: Handler,
        fileName: String,
        content: String,
        format: String
    ): Boolean {
        val safeName = sanitizeFileName(fileName, format)
        val mimeType = resolveMimeType(format)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val outFile = File(downloadsDir, safeName)
                FileOutputStream(outFile).use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
            mainHandler.post {
                Toast.makeText(context, "Saved to Downloads: $safeName", Toast.LENGTH_LONG).show()
            }
            true
        } catch (e: Exception) {
            mainHandler.post {
                Toast.makeText(context, "Save error: ${e.message}", Toast.LENGTH_LONG).show()
            }
            false
        }
    }

    fun shareSubtitleFile(
        context: Context,
        mainHandler: Handler,
        fileName: String,
        content: String,
        format: String
    ): Boolean {
        val safeName = sanitizeFileName(fileName, format)
        val mimeType = resolveMimeType(format)

        return try {
            val cacheFolder = File(context.cacheDir, "shared_subs")
            if (!cacheFolder.exists()) cacheFolder.mkdirs()
            val shareFile = File(cacheFolder, safeName)
            FileOutputStream(shareFile).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val uri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)
            } catch (_: Exception) {
                null
            }

            mainHandler.post {
                try {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        if (uri != null) {
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        putExtra(Intent.EXTRA_TEXT, content)
                        putExtra(Intent.EXTRA_SUBJECT, safeName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val chooser = Intent.createChooser(shareIntent, "Share subtitles").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                } catch (e: Exception) {
                    Toast.makeText(context, "Could not open share menu: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            true
        } catch (_: Exception) {
            mainHandler.post {
                try {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, content)
                        putExtra(Intent.EXTRA_SUBJECT, safeName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val chooser = Intent.createChooser(shareIntent, "Share subtitles").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                } catch (err: Exception) {
                    Toast.makeText(context, "Share error: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
            false
        }
    }
}
