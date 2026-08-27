package com.managerdownloader.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.managerdownloader.app.data.DownloadKind
import com.managerdownloader.app.data.DownloadRepository
import com.managerdownloader.app.data.DownloadStatus
import com.managerdownloader.app.data.DownloadTask
import com.managerdownloader.app.data.QueueMode
import com.managerdownloader.app.data.SettingsRepository
import com.managerdownloader.app.data.StorageRepository
import com.managerdownloader.app.download.DownloadService
import com.managerdownloader.app.security.SecurityUrlPolicy

private enum class DownloadFilter(val label: String) {
    ALL("Todas"),
    ACTIVE("Activas"),
    PAUSED("Pausa"),
    COMPLETED("Completadas"),
    FAILED("Errores"),
    TORRENTS("Torrent")
}

@Composable
fun DownloadsScreen(
    contentPadding: PaddingValues,
    onAdd: (String, String?, String?, String?, String?) -> Unit,
    onOpenTorrentFile: () -> Unit,
    onMoveCompleted: (String) -> Unit
) {
    val downloads by DownloadRepository.downloads.collectAsState()
    val settings by SettingsRepository.settings.collectAsState()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    var refreshTask by remember { mutableStateOf<DownloadTask?>(null) }
    var deleteTask by remember { mutableStateOf<DownloadTask?>(null) }

    val active = downloads.count { it.status == DownloadStatus.ACTIVE }
    val completed = downloads.count { it.status == DownloadStatus.COMPLETED }
    val paused = downloads.count { it.status == DownloadStatus.PAUSED }
    val sorted = downloads.sortedBy { it.order }
    val filtered = sorted.filter { item ->
        val matchesQuery = query.isBlank() ||
            item.filename.contains(query, ignoreCase = true) ||
            item.url.contains(query, ignoreCase = true) ||
            item.detail.orEmpty().contains(query, ignoreCase = true)
        val matchesFilter = when (filter) {
            DownloadFilter.ALL -> true
            DownloadFilter.ACTIVE -> item.status == DownloadStatus.ACTIVE || item.status == DownloadStatus.QUEUED
            DownloadFilter.PAUSED -> item.status == DownloadStatus.PAUSED
            DownloadFilter.COMPLETED -> item.status == DownloadStatus.COMPLETED
            DownloadFilter.FAILED -> item.status == DownloadStatus.FAILED
            DownloadFilter.TORRENTS -> item.kind == DownloadKind.TORRENT
        }
        matchesQuery && matchesFilter
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = contentPadding.calculateTopPadding() + 20.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = "MANAGER",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.8.sp
                    )
                    Text(text = "Descargas", fontSize = 31.sp, fontWeight = FontWeight.Bold)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onOpenTorrentFile, shape = RoundedCornerShape(14.dp)) {
                        Text(".torrent")
                    }
                    Button(onClick = { showAddDialog = true }, shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Nueva")
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                SummaryCard("Activas", active, Primary, Modifier.weight(1f))
                SummaryCard("Completadas", completed, Success, Modifier.weight(1f))
                SummaryCard("En pausa", paused, Warning, Modifier.weight(1f))
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { DownloadService.pauseAll(context) },
                    enabled = downloads.any { it.status == DownloadStatus.ACTIVE || it.status == DownloadStatus.QUEUED },
                    modifier = Modifier.weight(1f)
                ) { Text("Pausar todas") }
                OutlinedButton(
                    onClick = { DownloadService.resumeAll(context) },
                    enabled = downloads.any { it.status == DownloadStatus.PAUSED },
                    modifier = Modifier.weight(1f)
                ) { Text("Reanudar") }
            }
        }

        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Buscar descargas") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DownloadFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) }
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(2.dp))
            Text("Cola de descargas", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (settings.queueMode == QueueMode.SEQUENTIAL) {
                    "Uno por uno · hasta ${settings.segmentsPerFile} conexiones por archivo."
                } else {
                    "Simultáneo · hasta ${settings.maxParallelDownloads} archivos y ${settings.segmentsPerFile} conexiones por archivo."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }

        if (downloads.isEmpty()) {
            item { EmptyQueueCard(onAdd = { showAddDialog = true }) }
        } else if (filtered.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Text(
                        "No hay descargas que coincidan con el filtro.",
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(filtered, key = { it.id }) { item ->
                val globalIndex = sorted.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                DownloadCard(
                    item = item,
                    position = globalIndex + 1,
                    canMoveUp = globalIndex > 0,
                    canMoveDown = globalIndex < sorted.lastIndex,
                    context = context,
                    onRefreshLink = { refreshTask = item },
                    onMoveCompleted = { onMoveCompleted(item.id) },
                    onDeleteFile = { deleteTask = item }
                )
            }
        }
    }

    if (showAddDialog) {
        AddDownloadDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { url, sha ->
                onAdd(url, null, null, null, sha)
                showAddDialog = false
            }
        )
    }

    refreshTask?.let { task ->
        RefreshLinkDialog(
            task = task,
            onDismiss = { refreshTask = null },
            onApply = { newUrl ->
                DownloadRepository.updateUrl(task.id, newUrl)
                DownloadService.process(context)
                refreshTask = null
            }
        )
    }

    deleteTask?.let { task ->
        AlertDialog(
            onDismissRequest = { deleteTask = null },
            title = { Text("Eliminar archivo") },
            text = { Text("Se eliminará el archivo descargado y también se quitará del historial. Esta acción no se puede deshacer.") },
            confirmButton = {
                Button(onClick = {
                    StorageRepository.deleteCompleted(context, task)
                    DownloadRepository.remove(task.id)
                    deleteTask = null
                }) { Text("Eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTask = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun SummaryCard(
    label: String,
    value: Int,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(13.dp)) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Download, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(value.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyQueueCard(onAdd: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Download, contentDescription = null, tint = Primary, modifier = Modifier.size(38.dp))
            Spacer(Modifier.height(12.dp))
            Text("Tu cola está vacía", fontWeight = FontWeight.SemiBold)
            Text(
                "Añade una URL, un magnet, un .torrent local o usa el navegador.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onAdd) { Text("Añadir descarga") }
        }
    }
}

@Composable
private fun DownloadCard(
    item: DownloadTask,
    position: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    context: Context,
    onRefreshLink: () -> Unit,
    onMoveCompleted: () -> Unit,
    onDeleteFile: () -> Unit
) {
    val accent = when (item.status) {
        DownloadStatus.COMPLETED -> Success
        DownloadStatus.PAUSED -> Warning
        DownloadStatus.FAILED -> Danger
        DownloadStatus.QUEUED -> Violet
        DownloadStatus.ACTIVE -> Primary
    }
    var menuExpanded by remember(item.id) { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (item.status) {
                            DownloadStatus.COMPLETED -> Icons.Default.CheckCircle
                            DownloadStatus.FAILED -> Icons.Default.Error
                            DownloadStatus.QUEUED -> Icons.Default.Schedule
                            else -> Icons.Default.Download
                        },
                        contentDescription = null,
                        tint = accent
                    )
                }

                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("$position. ${item.filename}", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        item.detail ?: statusLabel(item.status),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 2
                    )
                }

                if (item.status != DownloadStatus.ACTIVE && item.status != DownloadStatus.COMPLETED) {
                    IconButton(enabled = canMoveUp, onClick = { DownloadRepository.move(item.id, -1) }) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Subir prioridad")
                    }
                    IconButton(enabled = canMoveDown, onClick = { DownloadRepository.move(item.id, 1) }) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Bajar prioridad")
                    }
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Más opciones")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        when (item.status) {
                            DownloadStatus.ACTIVE -> {
                                DropdownMenuItem(
                                    text = { Text("Pausar") },
                                    leadingIcon = { Icon(Icons.Default.Pause, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        DownloadService.pause(context, item.id)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Cancelar descarga") },
                                    leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        DownloadService.cancel(context, item.id)
                                    }
                                )
                            }
                            DownloadStatus.QUEUED -> {
                                DropdownMenuItem(
                                    text = { Text("Priorizar ahora") },
                                    leadingIcon = { Icon(Icons.Default.VerticalAlignTop, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        DownloadRepository.moveToTop(item.id)
                                        DownloadService.process(context)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Cancelar") },
                                    leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        DownloadService.cancel(context, item.id)
                                    }
                                )
                            }
                            DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                                DropdownMenuItem(
                                    text = { Text(if (item.status == DownloadStatus.FAILED) "Reintentar" else "Continuar") },
                                    leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        DownloadService.resume(context, item.id)
                                    }
                                )
                                if (item.kind == DownloadKind.HTTP) {
                                    DropdownMenuItem(
                                        text = { Text("Actualizar enlace") },
                                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                                        onClick = {
                                            menuExpanded = false
                                            onRefreshLink()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Cancelar") },
                                    leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        DownloadService.cancel(context, item.id)
                                    }
                                )
                            }
                            DownloadStatus.COMPLETED -> {
                                if (item.kind == DownloadKind.HTTP) {
                                    DropdownMenuItem(
                                        text = { Text("Abrir archivo") },
                                        leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
                                        onClick = {
                                            menuExpanded = false
                                            StorageRepository.openCompleted(context, item)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Compartir") },
                                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                        onClick = {
                                            menuExpanded = false
                                            StorageRepository.shareCompleted(context, item)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Mover a otra carpeta") },
                                        leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                                        onClick = {
                                            menuExpanded = false
                                            onMoveCompleted()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Eliminar archivo") },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                        onClick = {
                                            menuExpanded = false
                                            onDeleteFile()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Quitar del historial") },
                                    leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        DownloadRepository.remove(item.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            if (item.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.fillMaxWidth().height(7.dp),
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            } else if (item.status == DownloadStatus.ACTIVE) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(7.dp),
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (item.totalBytes > 0) "${formatBytes(item.bytesDownloaded)} / ${formatBytes(item.totalBytes)}"
                    else formatBytes(item.bytesDownloaded),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (item.speedBytesPerSecond > 0) {
                        val eta = if (item.totalBytes > item.bytesDownloaded) {
                            formatEta((item.totalBytes - item.bytesDownloaded) / item.speedBytesPerSecond.coerceAtLeast(1L))
                        } else ""
                        "${formatBytes(item.speedBytesPerSecond)}/s" + if (eta.isNotBlank()) " · $eta" else ""
                    } else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Danger, fontSize = 12.sp)
            }

            item.actualSha256?.let { hash ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "SHA-256: $hash",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when (item.status) {
                    DownloadStatus.ACTIVE -> {
                        TextButton(onClick = { DownloadService.pause(context, item.id) }) {
                            Icon(Icons.Default.Pause, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Pausar")
                        }
                    }
                    DownloadStatus.PAUSED,
                    DownloadStatus.FAILED -> {
                        TextButton(onClick = { DownloadService.resume(context, item.id) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (item.status == DownloadStatus.FAILED) "Reintentar" else "Continuar")
                        }
                    }
                    DownloadStatus.QUEUED -> {
                        Text(
                            "Esperando turno",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                            fontSize = 12.sp
                        )
                    }
                    DownloadStatus.COMPLETED -> {
                        if (item.kind == DownloadKind.HTTP) {
                            TextButton(onClick = { StorageRepository.openCompleted(context, item) }) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Abrir")
                            }
                        }
                    }
                }

                if (item.status != DownloadStatus.COMPLETED) {
                    TextButton(onClick = { DownloadService.cancel(context, item.id) }) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Cancelar")
                    }
                }
            }
        }
    }
}

@Composable
private fun AddDownloadDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String?) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var shaError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva descarga") },
        text = {
            Column {
                Text("Pega una URL directa o magnet. Para páginas que generan el enlace después, usa Navegador.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = url,
                    onValueChange = { url = it; error = false },
                    singleLine = true,
                    isError = error,
                    label = { Text("URL / magnet") },
                    placeholder = { Text("https://servidor.com/archivo.zip") }
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = sha,
                    onValueChange = { sha = it; shaError = false },
                    singleLine = true,
                    isError = shaError,
                    label = { Text("SHA-256 esperado (opcional)") },
                    supportingText = { Text("Si lo indicas, se valida antes de marcar la descarga como completada.") }
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val normalized = normalizeUrl(url)
                val cleanSha = sha.trim().lowercase().replace(" ", "")
                val validSha = cleanSha.isBlank() || cleanSha.matches(Regex("^[0-9a-f]{64}$"))
                if (normalized == null) error = true
                if (!validSha) shaError = true
                if (normalized != null && validSha) onAdd(normalized, cleanSha.ifBlank { null })
            }) { Text("Añadir a la cola") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun RefreshLinkDialog(
    task: DownloadTask,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    var url by remember(task.id) { mutableStateOf(task.url) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Actualizar enlace") },
        text = {
            Column {
                Text("Cambia una URL caducada sin borrar los segmentos ya descargados.")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = url,
                    onValueChange = { url = it; error = false },
                    isError = error,
                    label = { Text("Nueva URL") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val normalized = normalizeUrl(url)
                if (normalized == null || normalized.startsWith("magnet:", true)) error = true
                else onApply(normalized)
            }) { Text("Actualizar y continuar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

private fun normalizeUrl(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.startsWith("magnet:", true)) return trimmed
    if (trimmed.startsWith("http://", true)) return null
    val candidate = if (trimmed.startsWith("https://", true)) trimmed else "https://$trimmed"
    return candidate.takeIf { SecurityUrlPolicy.isSafePublicHttps(it) }
}

private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.QUEUED -> "En cola"
    DownloadStatus.ACTIVE -> "Descargando"
    DownloadStatus.PAUSED -> "En pausa"
    DownloadStatus.COMPLETED -> "Completada"
    DownloadStatus.FAILED -> "Error"
}

private fun formatEta(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    return when {
        safe < 60 -> "${safe}s"
        safe < 3600 -> "${safe / 60}m ${safe % 60}s"
        else -> "${safe / 3600}h ${(safe % 3600) / 60}m"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}
