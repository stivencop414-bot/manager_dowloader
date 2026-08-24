package com.managerdownloader.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.managerdownloader.app.browser.ContentBlocker
import com.managerdownloader.app.data.QueueMode
import com.managerdownloader.app.data.SettingsRepository

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onTransferSettingsChanged: () -> Unit
) {
    val settings by SettingsRepository.settings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = contentPadding.calculateTopPadding() + 20.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("AJUSTES", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Rendimiento", fontSize = 31.sp, fontWeight = FontWeight.Bold)
        Text(
            "Controla cuántos archivos y conexiones usa el motor. Más conexiones no siempre significan más velocidad.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )

        SettingsCard(title = "Modo de cola") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = settings.queueMode == QueueMode.SEQUENTIAL,
                    onClick = {
                        SettingsRepository.setQueueMode(QueueMode.SEQUENTIAL)
                        onTransferSettingsChanged()
                    },
                    label = { Text("Uno por uno") }
                )
                FilterChip(
                    selected = settings.queueMode == QueueMode.PARALLEL,
                    onClick = {
                        SettingsRepository.setQueueMode(QueueMode.PARALLEL)
                        onTransferSettingsChanged()
                    },
                    label = { Text("Todos / simultáneos") }
                )
            }

            if (settings.queueMode == QueueMode.PARALLEL) {
                Spacer(Modifier.height(10.dp))
                Text("Máximo simultáneo: ${settings.maxParallelDownloads}", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = settings.maxParallelDownloads.toFloat(),
                    onValueChange = { SettingsRepository.setMaxParallelDownloads(it.toInt()) },
                    onValueChangeFinished = onTransferSettingsChanged,
                    valueRange = 2f..6f,
                    steps = 3
                )
            }
        }

        SettingsCard(title = "Aceleración HTTP") {
            Text(
                "Conexiones por archivo: ${settings.segmentsPerFile}",
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "El motor usa HTTP Range automáticamente cuando el servidor lo permite y conserva cada segmento para reanudarlo.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Slider(
                value = settings.segmentsPerFile.toFloat(),
                onValueChange = { SettingsRepository.setSegmentsPerFile(it.toInt()) },
                valueRange = 1f..8f,
                steps = 6
            )
        }

        SettingsCard(title = "Navegador y bloqueo") {
            ToggleRow(
                title = "Bloquear publicidad",
                subtitle = "Intercepta peticiones de anuncios dentro del WebView.",
                checked = settings.adBlockEnabled,
                onCheckedChange = SettingsRepository::setAdBlockEnabled
            )
            ToggleRow(
                title = "Bloquear rastreadores",
                subtitle = "Añade reglas de privacidad; desactívalo si una web deja de funcionar.",
                checked = settings.blockTrackers,
                enabled = settings.adBlockEnabled,
                onCheckedChange = SettingsRepository::setBlockTrackers
            )
            Text(
                "${ContentBlocker.ruleCount()} dominios cargados",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            OutlinedButton(onClick = { ContentBlocker.refreshIfStale(force = true) }) {
                Text("Actualizar listas")
            }
        }

        Text(
            "Torrent: magnet y archivos .torrent usan libtorrent. Las descargas torrent se detienen al completar para no dejar la app sembrando indefinidamente.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}
