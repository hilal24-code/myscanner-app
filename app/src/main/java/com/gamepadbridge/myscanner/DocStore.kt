package com.gamepadbridge.myscanner

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import android.graphics.Paint

data class Doc(
    val id: String,
    val name: String,
    val created: Long,
    val pages: List<File>
)

data class PdfFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val date: Long
)

class DocStore(private val context: Context) {

    private val root = File(context.filesDir, "docs").apply { mkdirs() }

    fun list(): List<Doc> =
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val pages = dir.listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name }
                ?: emptyList()
            if (pages.isEmpty()) {
                dir.deleteRecursively()
                return@mapNotNull null
            }
            val nameFile = File(dir, "name.txt")
            Doc(
                id = dir.name,
                name = if (nameFile.exists()) nameFile.readText() else dir.name,
                created = dir.name.toLongOrNull() ?: dir.lastModified(),
                pages = pages
            )
        }?.sortedByDescending { it.created } ?: emptyList()

    fun create(uris: List<Uri>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Doc {
        val id = System.currentTimeMillis().toString()
        val dir = File(root, id).apply { mkdirs() }
        val name = "Scan " + java.text.SimpleDateFormat("dd-MM-yyyy HH.mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        File(dir, "name.txt").writeText(name)
        copyPages(dir, uris, onProgress)
        return list().first { it.id == id }
    }

    fun addPages(id: String, uris: List<Uri>, onProgress: (Int, Int) -> Unit = { _, _ -> }) =
        copyPages(File(root, id), uris, onProgress)

    private fun copyPages(dir: File, uris: List<Uri>, onProgress: (Int, Int) -> Unit) {
        val stamp = System.currentTimeMillis()
        val origDir = File(dir, "orig").apply { mkdirs() }
        onProgress(0, uris.size)
        uris.forEachIndexed { i, uri ->
            val fileName = "page_${stamp}_${i.toString().padStart(3, '0')}.jpg"
            val target = File(dir, fileName)
            val backup = File(origDir, fileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(backup).use { input.copyTo(it) }
            }

            val ok = try {
                val bmp = decodeSampled(backup, 3000)
                if (bmp != null) {
                    val straight = DocumentAligner.straighten(bmp)
                    val clean = DocumentEnhancer.enhance(straight)
                    FileOutputStream(target).use { clean.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    true
                } else false
            } catch (e: Throwable) {
                e.printStackTrace()
                false
            }

            if (!ok) backup.copyTo(target, overwrite = true)
            onProgress(i + 1, uris.size)
        }
    }
    fun deletePage(file: File) {
        file.delete()
        File(File(file.parentFile, "orig"), file.name).delete()
    }

    fun rename(id: String, name: String) =
        File(File(root, id), "name.txt").writeText(name.trim().ifEmpty { "Tanpa nama" })

    fun delete(id: String) { File(root, id).deleteRecursively() }

    /** Daftar PDF yang tersimpan di Documents/MyScanner. */
    fun listPdfs(): List<PdfFile> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?"
        val args = arrayOf("Documents/MyScanner%", "application/pdf")
        val result = mutableListOf<PdfFile>()
        context.contentResolver.query(
            collection, projection, selection, args,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (c.moveToNext()) {
                result += PdfFile(
                    uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                    name = c.getString(nameCol),
                    size = c.getLong(sizeCol),
                    date = c.getLong(dateCol) * 1000
                )
            }
        }
        return result
    }

    /** Hapus PDF, mengembalikan jumlah yang berhasil dihapus. */
    fun deletePdfs(items: List<PdfFile>): Int {
        var count = 0
        items.forEach {
            try {
                if (context.contentResolver.delete(it.uri, null, null) > 0) count++
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return count
    }

    /** Rakit PDF dari semua halaman (dengan filter), simpan ke Documents/MyScanner. */
    fun exportPdf(doc: Doc): Uri? {
        val pdf = PdfDocument()
        try {
            val pageW = 595
            val pageH = 842
            val a4 = pageH.toFloat() / pageW
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)

            doc.pages.forEachIndexed { index, file ->
                val bitmap = decodeSampled(file, 2400) ?: return@forEachIndexed
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, index + 1).create())
                val canvas = page.canvas
                canvas.drawColor(android.graphics.Color.WHITE)

                val ratio = bitmap.height.toFloat() / bitmap.width
                val dest = if (Math.abs(ratio - a4) / a4 <= 0.05f) {
                    // hampir A4: isi penuh
                    Rect(0, 0, pageW, pageH)
                } else {
                    // beda jauh: muat di dalam A4, tanpa gepeng
                    val scale = minOf(pageW.toFloat() / bitmap.width, pageH.toFloat() / bitmap.height)
                    val w = (bitmap.width * scale).toInt()
                    val h = (bitmap.height * scale).toInt()
                    val left = (pageW - w) / 2
                    val top = (pageH - h) / 2
                    Rect(left, top, left + w, top + h)
                }
                canvas.drawBitmap(bitmap, null, dest, paint)
                pdf.finishPage(page)
            }

            val safeName = doc.name.replace(Regex("[^A-Za-z0-9 _-]"), "_")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$safeName.pdf")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/MyScanner")
            }
            val target = context.contentResolver
                .insert(MediaStore.Files.getContentUri("external"), values) ?: return null
            context.contentResolver.openOutputStream(target)?.use { pdf.writeTo(it) }
            return target
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            pdf.close()
        }
    }
}

/** Decode gambar dengan ukuran terbatas supaya hemat memori. */
fun decodeSampled(file: File, maxSize: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSize) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.path, opts)
}