package com.managerdownloader.app.ui

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.managerdownloader.app.data.DownloadRepository
import com.managerdownloader.app.data.DownloadStatus
import com.managerdownloader.app.data.DownloadTask
import com.managerdownloader.app.download.DownloadService

@Composable
fun DownloadsScreen(
    contentPadding: PaddingValues,
    onAdd: (String, String?, String?, String?) -> Unit
) {
    val downloads by DownloadRepository.downloads.collectAsState()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }

    val active = downloads.count { it.status == DownloadStatus.ACTIVE }
    val completed = downloads.count { it.status == DownloadStatus.COMPLETED }
    val paused = downloads.count { it.status == DownloadStatus.PAUSED }

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
                    Text(
                        text = "Descargas",
                        fontSize = 31.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = { showAddDialog = true },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Nueva")
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
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Cola de descargas",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Solo se descarga un archivo a la vez. Reordena los pendientes con ↑ y ↓.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }

        if (downloads.isEmpty()) {
            item {
                EmptyQueueCard(onAdd = { showAddDialog = true })
            }
        } else {
            itemsIndexed(
                items = downloads.sortedBy { it.order },
                key = { _, item -> item.id }
            ) { index, item ->
                DownloadCard(
                    item = item,
                    position = index + 1,
                    canMoveUp = index > 0,
                    canMoveDown = index < downloads.lastIndex,
                    context = context
                )
            }
        }
    }

    if (showAddDialog) {
        AddDownloadDialog(
            onDismiss = { showAddDialog = false },
            onAdd = {
                onAdd(it, null, null, null)
                showAddDialog = false
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
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
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
            Icon(
                Icons.Default.Download,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(38.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text("Tu cola está vacía", fontWeight = FontWeight.SemiBold)
            Text(
                "Añade una URL directa o usa el mini navegador.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onAdd) {
                Text("Añadir descarga")
            }
        }
    }
}

@Composable
private fun DownloadCard(
    item: DownloadTask,
    position: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    context: Context
) {
    val accent = when (item.status) {
        DownloadStatus.COMPLETED -> Success
        DownloadStatus.PAUSED -> Warning
        DownloadStatus.FAILED -> Danger
        DownloadStatus.QUEUED -> Violet
        DownloadStatus.ACTIVE -> Primary
    }

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
                    Text(
                        text = "$position. ${item.filename}",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = statusLabel(item.status),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }

                if (item.status != DownloadStatus.ACTIVE &&
                    item.status != DownloadStatus.COMPLETED
                ) {
                    IconButton(
                        enabled = canMoveUp,
                        onClick = { DownloadRepository.move(item.id, -1) }
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Subir prioridad")
                    }
                    IconButton(
                        enabled = canMoveDown,
                        onClick = { DownloadRepository.move(item.id, 1) }
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Bajar prioridad")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (item.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp),
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            } else if (item.status == DownloadStatus.ACTIVE) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp),
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (item.totalBytes > 0) {
                        "${formatBytes(item.bytesDownloaded)} / ${formatBytes(item.totalBytes)}"
                    } else {
                        formatBytes(item.bytesDownloaded)
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (item.speedBytesPerSecond > 0) {
                        "${formatBytes(item.speedBytesPerSecond)}/s"
                    } else {
                        ""
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Danger, fontSize = 12.sp)
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                when (item.status) {
                    DownloadStatus.ACTIVE -> {
                        TextButton(onClick = {
                            DownloadService.pause(context, item.id)
                        }) {
                            Icon(Icons.Default.Pause, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Pausar")
                        }
                    }

                    DownloadStatus.PAUSED,
                    DownloadStatus.FAILED -> {
                        TextButton(onClick = {
                            DownloadService.resume(context, item.id)
                        }) {
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

                    DownloadStatus.COMPLETED -> Unit
                }

                TextButton(onClick = {
                    DownloadService.cancel(context, item.id)
                }) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (item.status == DownloadStatus.COMPLETED) "Quitar" else "Cancelar")
                }
            }
        }
    }
}

@Composable
private fun AddDownloadDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva descarga") },
        text = {
            Column {
                Text(
                    "Pega una URL directa. Si el enlace abre una página antes de descargar, usa la pestaña Navegador."
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = url,
                    onValueChange = {
                        url = it
                        error = false
                    },
                    singleLine = true,
                    isError = error,
                    label = { Text("URL") },
                    placeholder = { Text("https://servidor.com/archivo.zip") }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val normalized = normalizeUrl(url)
                    if (normalized == null) error = true else onAdd(normalized)
                }
            ) {
                Text("Añadir a la cola")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

private fun normalizeUrl(value: String): String? {
    val trimmed = value.trim()
    val candidate = if (
        trimmed.startsWith("http://", true) ||
        trimmed.startsWith("https://", true)
    ) trimmed else "https://$trimmed"

    return candidate.takeIf {
        runCatching { java.net.URI(it).host != null }.getOrDefault(false)
    }
}

private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.QUEUED -> "En cola"
    DownloadStatus.ACTIVE -> "Descargando"
    DownloadStatus.PAUSED -> "En pausa"
    DownloadStatus.COMPLETED -> "Completada"
    DownloadStatus.FAILED -> "Error"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}
