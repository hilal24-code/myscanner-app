package com.gamepadbridge.myscanner

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.input.pointer.pointerInput
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List as ListIcon
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamepadbridge.myscanner.ui.theme.MyScannerTheme
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCVLoader.initLocal()
        enableEdgeToEdge()
        setContent { MyScannerTheme { App() } }
    }
}

// ---------- Scanner launcher ----------

@Composable
fun rememberScanner(onPages: (List<Uri>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val uris = scan?.pages?.map { it.imageUri } ?: emptyList()
            if (uris.isNotEmpty()) onPages(uris)
        }
    }
    return {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(context as Activity)
            .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener {
                Toast.makeText(context, "Gagal: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }
}

// ---------- Root ----------

@Composable
fun App() {
    val context = LocalContext.current
    val store = remember { DocStore(context) }
    val scope = rememberCoroutineScope()

    var docs by remember { mutableStateOf(store.list()) }
    var pdfs by remember { mutableStateOf(store.listPdfs()) }
    var tab by remember { mutableIntStateOf(0) }
    var openId by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<PdfFile?>(null) }

    var processing by remember { mutableStateOf(false) }
    var progressDone by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }

    fun reload() {
        docs = store.list()
        pdfs = store.listPdfs()
    }

    val startScan = rememberScanner { uris ->
        val currentId = openId
        scope.launch {
            processing = true
            progressDone = 0
            progressTotal = uris.size
            try {
                val report: (Int, Int) -> Unit = { done, total ->
                    progressDone = done
                    progressTotal = total
                }
                if (currentId == null) {
                    val doc = withContext(Dispatchers.IO) { store.create(uris, report) }
                    reload()
                    openId = doc.id
                } else {
                    withContext(Dispatchers.IO) { store.addPages(currentId, uris, report) }
                    reload()
                }
            } finally {
                processing = false
            }
        }
    }

    BackHandler(enabled = processing) { /* abaikan back selama proses */ }
    BackHandler(enabled = !processing && viewing != null) { viewing = null }
    BackHandler(enabled = !processing && viewing == null && openId != null) { openId = null }

    val openDoc = docs.firstOrNull { it.id == openId }
    val viewingPdf = viewing

    Box(Modifier.fillMaxSize()) {
        if (viewingPdf != null) {
            PdfViewerScreen(pdf = viewingPdf, onBack = { viewing = null })
        } else if (openDoc != null) {
            DetailScreen(
                doc = openDoc,
                store = store,
                scope = scope,
                onBack = { openId = null },
                onAddPage = startScan,
                onChanged = { reload() },
                onExported = { uri, share ->
                    reload()
                    if (!share) viewing = store.listPdfs().firstOrNull { it.uri == uri }
                }
            )
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0 },
                            icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") }
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1; reload() },
                            icon = { Icon(Icons.AutoMirrored.Filled.ListIcon, null) }, label = { Text("Files") }
                        )
                    }
                },
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = startScan,
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text("Scan") }
                    )
                }
            ) { pad ->
                if (tab == 0) {
                    HomeScreen(
                        docs = docs,
                        modifier = Modifier.padding(pad),
                        onOpen = { openId = it.id },
                        onDelete = { items ->
                            items.forEach { store.delete(it.id) }
                            reload()
                            Toast.makeText(context, "${items.size} dokumen dihapus", Toast.LENGTH_SHORT).show()
                        }
                    )
                } else {
                    PdfFilesScreen(
                        pdfs = pdfs,
                        modifier = Modifier.padding(pad),
                        onOpen = { viewing = it },
                        onDelete = { items ->
                            val n = store.deletePdfs(items)
                            reload()
                            Toast.makeText(context, "$n PDF dihapus", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }

        if (processing) {
            ProcessingOverlay(done = progressDone, total = progressTotal)
        }
    }
}

@Composable
fun ProcessingOverlay(done: Int, total: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
            .pointerInput(Unit) { /* tangkap semua sentuhan */ },
        contentAlignment = Alignment.Center
    ) {
        Card(Modifier.padding(32.dp)) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (total > 0) {
                    CircularProgressIndicator(
                        progress = { done.toFloat() / total },
                        modifier = Modifier.size(56.dp)
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(56.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Memproses halaman ${minOf(done + 1, maxOf(total, 1))} dari $total",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Meluruskan dan membersihkan bayangan...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------- Home ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    docs: List<Doc>,
    modifier: Modifier,
    onOpen: (Doc) -> Unit,
    onDelete: (List<Doc>) -> Unit
) {
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }

    val allSelected = docs.isNotEmpty() && selected.size == docs.size

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
                    selected = if (allSelected) emptySet() else docs.map { it.id }.toSet()
                }) { Text(if (allSelected) "Batal pilih" else "Pilih semua") }
                IconButton(onClick = { confirm = true }, enabled = selected.isNotEmpty()) {
                    Icon(Icons.Default.Delete, "Hapus")
                }
                IconButton(onClick = { exitSelect() }) { Icon(Icons.Default.Close, "Selesai") }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("My Scanner", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Scan dokumen jadi PDF",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (docs.isNotEmpty()) TextButton(onClick = { selecting = true }) { Text("Pilih") }
            }
        }
        Spacer(Modifier.height(20.dp))

        if (docs.isEmpty()) {
            EmptyState("Belum ada dokumen.\nTap tombol Scan untuk mulai.")
        } else {
            if (!selecting) {
                Text("Terbaru", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(docs, key = { it.id }) { doc ->
                    val isSelected = doc.id in selected
                    DocCard(
                        doc = doc,
                        selecting = selecting,
                        isSelected = isSelected,
                        onClick = {
                            if (selecting) {
                                selected = if (isSelected) selected - doc.id else selected + doc.id
                            } else onOpen(doc)
                        },
                        onLongClick = {
                            selecting = true
                            selected = selected + doc.id
                        }
                    )
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Hapus ${selected.size} dokumen?") },
            text = { Text("Dokumen dan semua halamannya akan dihapus. PDF yang sudah disimpan di menu Files tidak ikut terhapus.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(docs.filter { it.id in selected })
                    confirm = false
                    exitSelect()
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Batal") } }
        )
    }
}

// ---------- Files / history ----------

@Composable
fun FilesScreen(docs: List<Doc>, modifier: Modifier, onOpen: (Doc) -> Unit) {
    var query by remember { mutableStateOf("") }
    var sortByName by remember { mutableStateOf(false) }

    val shown = docs
        .filter { it.name.contains(query, ignoreCase = true) }
        .let { if (sortByName) it.sortedBy { d -> d.name.lowercase() } else it }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Semua Dokumen", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Cari dokumen") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !sortByName, onClick = { sortByName = false }, label = { Text("Terbaru") })
            FilterChip(selected = sortByName, onClick = { sortByName = true }, label = { Text("Nama") })
        }
        Spacer(Modifier.height(8.dp))

        if (shown.isEmpty()) {
            EmptyState(if (docs.isEmpty()) "Belum ada dokumen." else "Tidak ada hasil.")
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(shown, key = { it.id }) { DocCard(doc = it, onClick = { onOpen(it) }) }
            }
        }
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocCard(
    doc: Doc,
    selecting: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Checkbox(checked = isSelected, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
            }
            PageImage(doc.pages.first(), Modifier.size(56.dp, 74.dp).clip(RoundedCornerShape(6.dp)), 200)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${dateFormat.format(Date(doc.created))}  •  ${doc.pages.size} halaman",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun PageImage(file: File, modifier: Modifier, reqSize: Int = 400) {
    val bmp by produceState<ImageBitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, reqSize)?.asImageBitmap() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

// ---------- Detail ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    doc: Doc,
    store: DocStore,
    scope: CoroutineScope,
    onBack: () -> Unit,
    onAddPage: () -> Unit,
    onChanged: () -> Unit,
    onExported: (Uri, Boolean) -> Unit
) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var pageToDelete by remember { mutableStateOf<File?>(null) }

    fun export(share: Boolean) {
        busy = true
        scope.launch {
            val uri = withContext(Dispatchers.Default) { store.exportPdf(doc) }
            busy = false
            if (uri == null) {
                Toast.makeText(context, "Gagal membuat PDF", Toast.LENGTH_SHORT).show()
            } else {
                if (share) sharePdf(context, uri)
                onExported(uri, share)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") }
                },
                actions = {
                    IconButton(onClick = { showRename = true }) { Icon(Icons.Default.Edit, "Rename") }
                    IconButton(onClick = { showDelete = true }) { Icon(Icons.Default.Delete, "Hapus") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(doc.pages, key = { it.name }) { file ->
                    Box {
                        PageImage(
                            file,
                            Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(10.dp)),
                            600
                        )
                        Text(
                            "${doc.pages.indexOf(file) + 1}",
                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                        IconButton(
                            onClick = { pageToDelete = file },
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) { Icon(Icons.Default.Delete, "Hapus halaman") }
                    }
                }
                item {
                    OutlinedCard(
                        Modifier.fillMaxWidth().aspectRatio(0.72f).clickable(onClick = onAddPage)
                    ) {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Add, null)
                            Text("Tambah halaman")
                        }
                    }
                }
            }

            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (busy) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Memproses...")
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { export(false) }, modifier = Modifier.weight(1f)) { Text("Simpan PDF") }
                            OutlinedButton(onClick = { export(true) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRename) {
        var text by remember { mutableStateOf(doc.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Ganti nama") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { store.rename(doc.id, text); onChanged(); showRename = false }) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Batal") } }
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Hapus dokumen?") },
            text = { Text("\"${doc.name}\" dan semua halamannya akan dihapus.") },
            confirmButton = {
                TextButton(onClick = { store.delete(doc.id); onChanged(); showDelete = false; onBack() }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Batal") } }
        )
    }

    pageToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pageToDelete = null },
            title = { Text("Hapus halaman ini?") },
            confirmButton = {
                TextButton(onClick = {
                    store.deletePage(file)
                    pageToDelete = null
                    onChanged()
                    if (doc.pages.size <= 1) onBack()
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { pageToDelete = null }) { Text("Batal") } }
        )
    }
}

fun sharePdf(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share PDF"))
}