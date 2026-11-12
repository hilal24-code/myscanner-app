package com.gamepadbridge.myscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val pdfDateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

private fun formatSize(bytes: Long): String =
    if (bytes >= 1_048_576) String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    else "${maxOf(bytes / 1024, 1)} KB"

// ---------- Daftar PDF (menu Files) ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PdfFilesScreen(
    pdfs: List<PdfFile>,
    modifier: Modifier,
    onOpen: (PdfFile) -> Unit,
    onDelete: (List<PdfFile>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Uri>()) }
    var confirm by remember { mutableStateOf(false) }

    val shown = pdfs.filter { it.name.contains(query, ignoreCase = true) }
    val allSelected = shown.isNotEmpty() && selected.size == shown.size

    fun exitSelect() { selecting = false; selected = emptySet() }

    BackHandler(enabled = selecting) { exitSelect() }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))

        if (selecting) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${selected.size} dipilih",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    selected = if (allSelected) emptySet() else shown.map { it.uri }.toSet()
                }) { Text(if (allSelected) "Batal pilih" else "Pilih semua") }
                IconButton(onClick = { confirm = true }, enabled = selected.isNotEmpty()) {
                    Icon(Icons.Default.Delete, "Hapus")
                }
                IconButton(onClick = { exitSelect() }) { Icon(Icons.Default.Close, "Selesai") }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "File PDF",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f)
                )
                if (pdfs.isNotEmpty()) TextButton(onClick = { selecting = true }) { Text("Pilih") }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Cari PDF") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        if (shown.isEmpty()) {
            EmptyState(
                if (pdfs.isEmpty()) "Belum ada PDF.\nScan dokumen lalu tap Simpan PDF."
                else "Tidak ada hasil."
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(shown, key = { it.uri.toString() }) { pdf ->
                    val isSelected = pdf.uri in selected
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth().combinedClickable(
                            onClick = {
                                if (selecting) {
                                    selected = if (isSelected) selected - pdf.uri else selected + pdf.uri
                                } else onOpen(pdf)
                            },
                            onLongClick = {
                                selecting = true
                                selected = selected + pdf.uri
                            }
                        )
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selecting) {
                                Checkbox(checked = isSelected, onCheckedChange = null)
                                Spacer(Modifier.width(12.dp))
                            }
                            Icon(
                                Icons.Default.Description, null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    pdf.name, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${pdfDateFormat.format(Date(pdf.date))}  •  ${formatSize(pdf.size)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Hapus ${selected.size} PDF?") },
            text = { Text("File yang dihapus tidak bisa dikembalikan.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(pdfs.filter { it.uri in selected })
                    confirm = false
                    exitSelect()
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Batal") } }
        )
    }
}

// ---------- Viewer PDF ----------

private class PdfHolder(context: Context, uri: Uri) {
    private val pfd: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(uri, "r")
    private val renderer: PdfRenderer? = try {
        pfd?.let { PdfRenderer(it) }
    } catch (e: Exception) {
        null
    }
    private var closed = false
    val pageCount: Int = renderer?.pageCount ?: 0

    @Synchronized
    fun render(index: Int, width: Int): Bitmap? {
        if (closed || renderer == null) return null
        val page = renderer.openPage(index)
        try {
            val height = (width.toFloat() * page.height / page.width).toInt()
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(AColor.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }

    @Synchronized
    fun close() {
        closed = true
        renderer?.close()
        pfd?.close()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(pdf: PdfFile, onBack: () -> Unit) {
    val context = LocalContext.current
    val holder = remember(pdf.uri) { PdfHolder(context, pdf.uri) }
    DisposableEffect(holder) { onDispose { holder.close() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pdf.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") }
                },
                actions = {
                    IconButton(onClick = { sharePdf(context, pdf.uri) }) {
                        Icon(Icons.Default.Share, "Share")
                    }
                }
            )
        }
    ) { pad ->
        if (holder.pageCount == 0) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("PDF tidak bisa dibuka")
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(pad).fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(holder.pageCount) { index -> PdfPageView(holder, index) }
            }
        }
    }
}

@Composable
private fun PdfPageView(holder: PdfHolder, index: Int) {
    val bmp by produceState<ImageBitmap?>(null, index) {
        value = withContext(Dispatchers.IO) { holder.render(index, 1080)?.asImageBitmap() }
    }
    val image = bmp
    if (image != null) {
        Image(
            bitmap = image, contentDescription = "Halaman ${index + 1}",
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth
        )
    } else {
        Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color.White))
    }
}