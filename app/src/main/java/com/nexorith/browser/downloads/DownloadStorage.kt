package com.nexorith.browser.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

data class PublishedFile(val uri: String, val name: String)

/** Where partial files live and how a finished file reaches the user's Downloads folder. */
interface DownloadStorage {
    /** App-private file that receives the bytes while the download runs. */
    fun partFile(id: Long): File

    /** Deletes partial files whose download id is not in [keep]. */
    fun retainParts(keep: Set<Long>)

    /** Moves a finished file to the public Downloads folder. */
    @Throws(IOException::class)
    fun publish(part: File, fileName: String, mimeType: String?): PublishedFile

    fun deletePublished(uri: String)
}

class AndroidDownloadStorage(private val context: Context) : DownloadStorage {

    private val partsDir = File(context.filesDir, "downloads").apply { mkdirs() }

    override fun partFile(id: Long): File = File(partsDir, "$id.part")

    override fun retainParts(keep: Set<Long>) {
        partsDir.listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".part").toLongOrNull()
            if (file.name.endsWith(".part") && (id == null || id !in keep)) file.delete()
        }
    }

    override fun publish(part: File, fileName: String, mimeType: String?): PublishedFile {
        // Derive the type from the extension so MediaStore does not append its own extension.
        val mime = mimeFromName(fileName) ?: mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToMediaStore(part, fileName, mime)
        } else {
            publishToAppFolder(part, fileName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToMediaStore(part: File, fileName: String, mime: String): PublishedFile {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the file in Downloads")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open the file in Downloads")
            out.use { stream -> part.inputStream().use { it.copyTo(stream, 256 * 1024) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: IOException) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        val finalName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        return PublishedFile(uri.toString(), finalName ?: fileName)
    }

    /** Android 8-9: no scoped storage; the file stays in the app's own Downloads folder. */
    private fun publishToAppFolder(part: File, fileName: String): PublishedFile {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("No storage available")
        dir.mkdirs()
        val target = uniqueFile(dir, fileName)
        part.copyTo(target, overwrite = false)
        return PublishedFile(Uri.fromFile(target).toString(), target.name)
    }

    override fun deletePublished(uri: String) {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") {
            parsed.path?.let { File(it).delete() }
        } else {
            try {
                context.contentResolver.delete(parsed, null, null)
            } catch (e: SecurityException) {
                // The entry belongs to another install of the app; leave it.
            }
        }
    }

    private fun mimeFromName(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n)$ext")
            n++
        }
        return candidate
    }
}
