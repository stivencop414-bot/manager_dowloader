package com.managerdownloader.app.ui

import android.webkit.CookieManager
import android.webkit.WebStorage
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.managerdownloader.app.browser.ContentBlocker
import com.managerdownloader.app.data.AdBlockMode
import com.managerdownloader.app.data.QueueMode
import com.managerdownloader.app.data.SearchEngine
import com.managerdownloader.app.data.SettingsRepository
import com.managerdownloader.app.data.StorageRepository
import com.managerdownloader.app.data.ThemeMode
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onTransferSettingsChanged: () -> Unit,
    onSelectDownloadFolder: () -> Unit
) {
    val settings by SettingsRepository.settings.collectAsState()
    var parallelDraft by remember(settings.maxParallelDownloads) { mutableFloatStateOf(settings.maxParallelDownloads.toFloat()) }
    var segmentsDraft by remember(settings.segmentsPerFile) { mutableFloatStateOf(settings.segmentsPerFile.toFloat()) }
    var retriesDraft by remember(settings.segmentRetryCount) { mutableFloatStateOf(settings.segmentRetryCount.toFloat()) }
    var bandwidthDraft by remember(settings.bandwidthLimitMbps) { mutableFloatStateOf(settings.bandwidthLimitMbps.coerceIn(0, 100).toFloat()) }

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
        Text(
            "AJUSTES",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Manager Downloader", fontSize = 31.sp, fontWeight = FontWeight.Bold)
        Text(
            "Rendimiento, red, navegador, privacidad y apariencia.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )

        SettingsCard(title = "Almacenamiento y organización") {
            Text("Carpeta de destino", fontWeight = FontWeight.SemiBold)
            Text(
                StorageRepository.selectedLabel(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Text(
                "Los archivos terminados se ordenan en Videos, Imágenes, Audio, Comprimidos, Programas, Documentos, Torrents y Otros.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            OutlinedButton(onClick = onSelectDownloadFolder) {
                Text("Elegir / cambiar carpeta")
            }
        }

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
                    label = { Text("Simultáneos") }
                )
            }

            if (settings.queueMode == QueueMode.PARALLEL) {
                Spacer(Modifier.height(10.dp))
                Text("Máximo simultáneo: ${parallelDraft.roundToInt()}", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = parallelDraft,
                    onValueChange = { parallelDraft = it },
                    onValueChangeFinished = {
                        SettingsRepository.setMaxParallelDownloads(parallelDraft.roundToInt())
                        onTransferSettingsChanged()
                    },
                    valueRange = 2f..6f,
                    steps = 3
                )
            }
        }

        SettingsCard(title = "Aceleración y ancho de banda") {
            ToggleRow(
                title = "Modo Turbo",
                subtitle = "Usa segmentación más agresiva, buffers mayores y más conexiones en archivos grandes. Algunos servidores pueden rendir mejor con Turbo desactivado.",
                checked = settings.turboMode,
                onCheckedChange = {
                    SettingsRepository.setTurboMode(it)
                    onTransferSettingsChanged()
                }
            )

            Text("Conexiones máximas por archivo: ${segmentsDraft.roundToInt()}", fontWeight = FontWeight.SemiBold)
            Text(
                "Se usan solo cuando el servidor acepta HTTP Range. En modo Turbo el motor puede llegar hasta 16 segmentos en archivos grandes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Slider(
                value = segmentsDraft,
                onValueChange = { segmentsDraft = it },
                onValueChangeFinished = {
                    SettingsRepository.setSegmentsPerFile(segmentsDraft.roundToInt())
                    onTransferSettingsChanged()
                },
                valueRange = 1f..16f,
                steps = 14
            )

            Spacer(Modifier.height(8.dp))
            Text("Reintentos por conexión: ${retriesDraft.roundToInt()}", fontWeight = FontWeight.SemiBold)
            Text(
                "Si una conexión se corta, el segmento intenta continuar desde los bytes ya guardados.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Slider(
                value = retriesDraft,
                onValueChange = { retriesDraft = it },
                onValueChangeFinished = {
                    SettingsRepository.setSegmentRetryCount(retriesDraft.roundToInt())
                    onTransferSettingsChanged()
                },
                valueRange = 0f..5f,
                steps = 4
            )

            Spacer(Modifier.height(8.dp))
            Text(
                if (bandwidthDraft.roundToInt() == 0) "Límite de velocidad: sin límite"
                else "Límite global: ${((bandwidthDraft / 5f).roundToInt() * 5)} MB/s",
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = bandwidthDraft,
                onValueChange = { bandwidthDraft = it },
                onValueChangeFinished = {
                    val snapped = ((bandwidthDraft / 5f).roundToInt() * 5).coerceIn(0, 100)
                    bandwidthDraft = snapped.toFloat()
                    SettingsRepository.setBandwidthLimitMbps(snapped)
                    onTransferSettingsChanged()
                },
                valueRange = 0f..100f,
                steps = 19
            )
            ToggleRow(
                title = "Solo Wi‑Fi",
                subtitle = "Evita iniciar nuevas descargas HTTP o torrent usando datos móviles.",
                checked = settings.wifiOnly,
                onCheckedChange = {
                    SettingsRepository.setWifiOnly(it)
                    onTransferSettingsChanged()
                }
            )

            Text(
                "Para máxima velocidad en un solo archivo, usa Uno por uno + Turbo. El rendimiento final también depende del servidor y de tu red.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }

        SettingsCard(title = "Navegador") {
            Text("Motor de búsqueda", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.searchEngine == SearchEngine.DUCKDUCKGO,
                    onClick = { SettingsRepository.setSearchEngine(SearchEngine.DUCKDUCKGO) },
                    label = { Text("DuckDuckGo") }
                )
                FilterChip(
                    selected = settings.searchEngine == SearchEngine.GOOGLE,
                    onClick = { SettingsRepository.setSearchEngine(SearchEngine.GOOGLE) },
                    label = { Text("Google") }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.searchEngine == SearchEngine.BING,
                    onClick = { SettingsRepository.setSearchEngine(SearchEngine.BING) },
                    label = { Text("Bing") }
                )
                FilterChip(
                    selected = settings.searchEngine == SearchEngine.BRAVE,
                    onClick = { SettingsRepository.setSearchEngine(SearchEngine.BRAVE) },
                    label = { Text("Brave") }
                )
            }

            ToggleRow(
                title = "Compatibilidad tipo Chrome",
                subtitle = "Aplica el User-Agent compatible antes de la primera carga; ayuda con Google y sitios que rechazan el marcador WebView.",
                checked = settings.chromeCompatUserAgent,
                onCheckedChange = SettingsRepository::setChromeCompatUserAgent
            )
            ToggleRow(
                title = "Cookies de terceros",
                subtitle = "Desactivadas por defecto por privacidad. Actívalas si un inicio de sesión o sitio integrado no funciona.",
                checked = settings.thirdPartyCookies,
                onCheckedChange = SettingsRepository::setThirdPartyCookies
            )
            ToggleRow(
                title = "Detector de medios",
                subtitle = "Detecta únicamente video y audio. Omite imágenes, iconos y recursos decorativos para reducir falsos positivos y consumo de memoria.",
                checked = settings.mediaSnifferEnabled,
                onCheckedChange = SettingsRepository::setMediaSnifferEnabled
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                }) { Text("Borrar cookies") }
                OutlinedButton(onClick = { WebStorage.getInstance().deleteAllData() }) {
                    Text("Borrar datos web")
                }
            }
        }

        SettingsCard(title = "Adblock y privacidad") {
            ToggleRow(
                title = "Bloquear publicidad",
                subtitle = "Interruptor principal. También se puede cambiar desde el navegador.",
                checked = settings.adBlockEnabled,
                onCheckedChange = SettingsRepository::setAdBlockEnabled
            )
            ToggleRow(
                title = "Bloquear rastreadores",
                subtitle = "Añade reglas de privacidad; se puede apagar sin desactivar todo el AdBlock.",
                checked = settings.blockTrackers,
                enabled = settings.adBlockEnabled,
                onCheckedChange = SettingsRepository::setBlockTrackers
            )

            Text("Nivel de bloqueo", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.adBlockMode == AdBlockMode.STANDARD,
                    onClick = { SettingsRepository.setAdBlockMode(AdBlockMode.STANDARD) },
                    enabled = settings.adBlockEnabled,
                    label = { Text("Estándar") }
                )
                FilterChip(
                    selected = settings.adBlockMode == AdBlockMode.STRICT,
                    onClick = { SettingsRepository.setAdBlockMode(AdBlockMode.STRICT) },
                    enabled = settings.adBlockEnabled,
                    label = { Text("Estricto") }
                )
            }
            Text(
                "Estándar nunca bloquea el documento principal, peticiones POST ni recursos del mismo sitio. Es el recomendado para evitar páginas en blanco. Estricto bloquea más subrecursos y puede romper sitios.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Text(
                "${ContentBlocker.ruleCount()} dominios · ${ContentBlocker.blockedCount()} peticiones bloqueadas en esta sesión",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            OutlinedButton(onClick = { ContentBlocker.refreshIfStale(force = true) }) {
                Text("Actualizar listas")
            }
        }

        SettingsCard(title = "Apariencia") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.themeMode == ThemeMode.SYSTEM,
                    onClick = { SettingsRepository.setThemeMode(ThemeMode.SYSTEM) },
                    label = { Text("Sistema") }
                )
                FilterChip(
                    selected = settings.themeMode == ThemeMode.LIGHT,
                    onClick = { SettingsRepository.setThemeMode(ThemeMode.LIGHT) },
                    label = { Text("Claro") }
                )
                FilterChip(
                    selected = settings.themeMode == ThemeMode.DARK,
                    onClick = { SettingsRepository.setThemeMode(ThemeMode.DARK) },
                    label = { Text("Oscuro") }
                )
            }
        }

        Text(
            "Torrent: magnet, .torrent local y .torrent web usan libtorrent. Manager Downloader detiene la siembra al completar.",
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
