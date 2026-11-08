@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.socialrecover.pro.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.socialrecover.core.model.Category
import com.socialrecover.core.recovery.Availability
import com.socialrecover.core.recovery.LostKind
import com.socialrecover.core.social.SocialItemType
import com.socialrecover.core.social.SocialNetwork
import com.socialrecover.core.status.Severity
import com.socialrecover.core.vault.Destination
import com.socialrecover.core.vault.HybridPolicy
import com.socialrecover.core.vault.RetentionPolicy
import com.socialrecover.pro.ui.AppViewModel
import com.socialrecover.pro.ui.Format
import com.socialrecover.pro.ui.Screen
import com.socialrecover.pro.ui.components.BigButton
import com.socialrecover.pro.ui.components.InfoCard
import com.socialrecover.pro.ui.components.PasswordField
import com.socialrecover.pro.ui.components.SectionTitle
import com.socialrecover.pro.ui.theme.Ok
import java.time.LocalDate
import java.time.ZoneId

private val zipTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

// =============================================================================== Recuperación guiada

@Composable
fun GuidedRecoveryScreen(vm: AppViewModel, modifier: Modifier) {
    val pro = vm.pro
    val context = LocalContext.current
    var selected by remember(pro.guidedOutcome) { mutableStateOf(setOf<Int>()) }
    var detail by remember { mutableStateOf<Int?>(null) }
    val outcome = pro.guidedOutcome
    val dest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val o = outcome ?: return@rememberLauncherForActivityResult
        if (uri != null) pro.restore(selected.mapNotNull { i -> o.results[i].file?.let { o.results[i].loaded to it } }, uri)
    }
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Paso ${pro.guidedStep} de 3", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (pro.guidedStep == 1 || outcome == null) {
            item { Text("¿Qué has perdido?", style = MaterialTheme.typography.headlineMedium) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LostKind.entries.forEach { k ->
                        FilterChip(selected = pro.guidedKind == k, onClick = { pro.guidedKind = if (pro.guidedKind == k) null else k },
                            label = { Text(k.label, style = MaterialTheme.typography.titleMedium) }, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
            item {
                OutlinedTextField(pro.guidedText, { pro.guidedText = it }, Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text("Descríbelo con tus palabras (opcional)") },
                    placeholder = { Text("«Quiero recuperar un vídeo de TikTok de septiembre»") })
            }
            item {
                Text("Ejemplos: «Busco una foto que tenía en mi ordenador» · «Quiero encontrar mis comentarios antiguos de Instagram»",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                BigButton("Buscar en mis copias", Icons.Filled.Search, enabled = pro.guidedKind != null || pro.guidedText.isNotBlank()) { pro.guidedSearch() }
            }
            item { Text("La búsqueda se hace en tu teléfono: el texto no se envía a ningún servidor.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            item {
                Text(if (pro.guidedStep == 2) "Esto es lo que hemos encontrado" else "Recuperar", style = MaterialTheme.typography.headlineMedium)
                if (pro.guidedUnderstood.isNotEmpty()) Text("Hemos entendido: " + pro.guidedUnderstood.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            }
            outcome.explanations.forEach { e -> item { InfoCard(Icons.Filled.Info, "Para tener en cuenta", e) } }
            if (outcome.results.isEmpty()) {
                item { OutlinedButton(onClick = { pro.resetGuided() }, modifier = Modifier.fillMaxWidth()) { Text("Buscar otra cosa") } }
            }
            items(outcome.results.indices.toList()) { i ->
                val r = outcome.results[i]
                val restorable = r.availability == Availability.AVAILABLE && r.file != null
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.clickable { detail = i }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (restorable) Checkbox(checked = i in selected, onCheckedChange = { on -> selected = if (on) selected + i else selected - i })
                        Column(Modifier.weight(1f)) {
                            Text(r.title, maxLines = 2)
                            Text("${r.kind.label} · ${Format.date(r.date)} · ${r.subtitle}", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            AvailabilityLabel(r.availability)
                        }
                    }
                }
            }
            if (outcome.results.isNotEmpty()) item {
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton("Recuperar seleccionados (${selected.size})", Icons.Filled.Restore, enabled = selected.isNotEmpty()) {
                        pro.guidedStep = 3; dest.launch(null)
                    }
                    Text("Elige la carpeta de destino. Cada archivo se comprueba después de recuperarlo.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { pro.resetGuided() }, modifier = Modifier.fillMaxWidth()) { Text("Nueva búsqueda") }
                }
            }
        }
    }
    detail?.let { i ->
        val r = outcome?.results?.getOrNull(i) ?: return@let
        AlertDialog(onDismissRequest = { detail = null }, confirmButton = { TextButton(onClick = { detail = null }) { Text("Cerrar") } },
            title = { Text(r.kind.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(r.social?.text?.ifBlank { null } ?: r.title)
                    Text("Fecha: ${Format.date(r.date)}")
                    r.file?.let { Text("Archivo: ${it.path} · ${Format.size(context, it.size)}") }
                    r.social?.url?.let { Text("Enlace: $it") }
                    Text("Copia del ${Format.date(r.loaded.ref.createdAt)} · ${r.storage.label} · ${r.loaded.snapshot.device}")
                    AvailabilityLabel(r.availability)
                    if (r.availability == Availability.RECORD_ONLY) Text("Solo existe el registro de esta actividad. El archivo no venía en la exportación oficial y no se puede recuperar desde aquí.")
                    if (r.availability == Availability.DATA_MISSING) Text("Esta copia ya no contiene los datos de este archivo. Prueba con otra fecha en la Máquina del tiempo.")
                }
            })
    }
}

@Composable
private fun AvailabilityLabel(a: Availability) {
    val (icon, color) = when (a) {
        Availability.AVAILABLE, Availability.TEXT_AVAILABLE -> Icons.Filled.CheckCircle to Ok
        Availability.RECORD_ONLY -> Icons.Filled.Info to MaterialTheme.colorScheme.secondary
        Availability.DATA_MISSING -> Icons.Filled.Error to MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color); Spacer(Modifier.width(4.dp))
        Text(a.label, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

// =============================================================================== Mis redes sociales

@Composable
fun SocialHubScreen(vm: AppViewModel, modifier: Modifier) {
    val pro = vm.pro
    var network by remember { mutableStateOf<SocialNetwork?>(null) }
    var picked by remember { mutableStateOf(setOf<Int>()) }
    val importZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> -> pro.importExports(uris, network) }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? -> if (uri != null) pro.exportResults(uri, html = false) }
    val exportHtml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri: Uri? -> if (uri != null) pro.exportResults(uri, html = true) }
    val dest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) pro.restore(picked.mapNotNull { pro.socialResults.getOrNull(it)?.let { h -> pro.mediaFor(h) } }, uri)
    }
    LaunchedEffect(pro.library) { if (pro.library != null) pro.searchSocial() }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            InfoCard(Icons.Filled.Info, "Rescate de recuerdos",
                "Importa la copia oficial que cada red te deja descargar. Guardamos todos sus archivos cifrados y te dejamos buscar tus publicaciones, " +
                    "comentarios, «Me gusta» y fotos. No accedemos a tu cuenta ni podemos recuperar lo que la red haya borrado de sus servidores.")
        }
        item { SectionTitle("1. Tus redes") }
        item {
            Text("Las redes no permiten que ninguna app saque tus datos sola. Toca «Pedir copia», confirma en la página oficial y, " +
                "cuando te llegue el archivo, tócalo y elige «Abrir con SocialRecover Pro»: se importa automáticamente.",
                style = MaterialTheme.typography.bodyMedium)
        }
        items(SocialNetwork.entries.filter { it != SocialNetwork.OTHER }) { n ->
            val (req, imp) = pro.netTick.let { pro.svc.networkRecord(n) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(n.label, style = MaterialTheme.typography.titleMedium)
                    Text(when {
                        imp > 0 -> "Última copia importada: ${Format.date(imp)}"
                        req > 0 -> "Pedida el ${Format.date(req)}: espera el aviso de ${n.label}"
                        else -> "Sin copia todavía"
                    }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val link = officialExportUrl(n)
                TextButton(onClick = { pro.requestExport(n); runCatching { uriHandler.openUri(link) } }) { Text(if (imp > 0) "Pedir otra" else "Pedir copia") }
            }
            HorizontalDivider()
        }
        item { SectionTitle("Importar un archivo ya descargado") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SocialNetwork.entries.forEach { n ->
                    AssistChip(onClick = { network = n; importZip.launch(zipTypes) }, label = { Text(n.label) }, leadingIcon = { Icon(Icons.Filled.Download, null) })
                }
            }
        }
        item {
            TextButton(onClick = { vm.go(Screen.SOCIAL) }) { Text("¿Cómo pido mi exportación? Ver instrucciones paso a paso") }
        }
        if (!pro.unlocked || pro.library == null) { item { LockedCard(vm) }; return@LazyColumn }
        item { SectionTitle("2. Buscar en tus recuerdos") }
        item {
            SearchField(pro.socialQuery.text, { pro.socialQuery = pro.socialQuery.copy(text = it); pro.searchSocial() }, "Palabra, persona, lugar…")
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val nets = pro.library!!.allSocial().map { it.second.network }.distinct()
                nets.forEach { n ->
                    val on = n in pro.socialQuery.networks
                    FilterChip(selected = on, onClick = { pro.socialQuery = pro.socialQuery.copy(networks = if (on) pro.socialQuery.networks - n else pro.socialQuery.networks + n); pro.searchSocial() }, label = { Text(n.label) })
                }
                listOf(SocialItemType.PHOTO, SocialItemType.VIDEO, SocialItemType.POST, SocialItemType.COMMENT, SocialItemType.LIKE, SocialItemType.SAVED).forEach { t ->
                    val on = t in pro.socialQuery.types
                    FilterChip(selected = on, onClick = { pro.socialQuery = pro.socialQuery.copy(types = if (on) pro.socialQuery.types - t else pro.socialQuery.types + t); pro.searchSocial() }, label = { Text(t.label) })
                }
                listOf("Este año" to 0L, "Año pasado" to 1L).forEach { (label, back) ->
                    val y = LocalDate.now().year - back.toInt()
                    val from = LocalDate.of(y, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val to = LocalDate.of(y, 12, 31).atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val on = pro.socialQuery.from == from
                    FilterChip(selected = on, onClick = { pro.socialQuery = if (on) pro.socialQuery.copy(from = null, to = null) else pro.socialQuery.copy(from = from, to = to); pro.searchSocial() }, label = { Text(label) })
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${pro.socialResults.size} resultado(s)", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(enabled = pro.socialResults.isNotEmpty(), onClick = { exportCsv.launch("recuerdos.csv") }) { Text("CSV") }
                TextButton(enabled = pro.socialResults.isNotEmpty(), onClick = { exportHtml.launch("recuerdos.html") }) { Text("HTML") }
            }
        }
        items(pro.socialResults.indices.toList()) { i ->
            val h = pro.socialResults[i]
            val media = h.item.mediaPath != null
            Column(Modifier.fillMaxWidth().clickable(enabled = media) { picked = if (i in picked) picked - i else picked + i }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (media) Checkbox(checked = i in picked, onCheckedChange = { on -> picked = if (on) picked + i else picked - i })
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(h.item.text.ifBlank { h.item.type.label }, maxLines = 3)
                        Text(listOfNotNull(h.item.network.label, h.item.type.label, if (h.item.timestamp > 0) Format.date(h.item.timestamp) else null,
                            if (media) "archivo guardado" else "solo registro").joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        h.item.url?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 1) }
                    }
                }
                HorizontalDivider()
            }
        }
        if (picked.isNotEmpty()) item {
            BigButton("Recuperar fotos y vídeos (${picked.size})", Icons.Filled.Restore, Modifier.padding(vertical = 12.dp)) { dest.launch(null) }
        }
    }
}

// =============================================================================== Estado de protección

@Composable
fun StatusScreen(vm: AppViewModel, modifier: Modifier) {
    val pro = vm.pro
    var cat by remember { mutableStateOf(Category.PHOTOS) }
    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> if (uri != null) pro.addWatched(uri, cat) }
    fun suggested(path: String): Uri = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$path")
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { pro.refreshProtection(scan = pro.unlocked) }
    val rep = pro.protection
    var monitor by remember { mutableStateOf(pro.svc.monitor) }
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val (icon, text) = when (rep?.level) {
                null -> Icons.Filled.Info to "Calculando…"
                else -> (if (rep!!.level.name == "PROTECTED") Icons.Filled.VerifiedUser else Icons.Filled.Warning) to rep.level.label
            }
            InfoCard(icon, text, if (rep?.alerts.isNullOrEmpty()) "No hay avisos. Tus carpetas supervisadas están protegidas." else "${rep!!.alerts.size} aviso(s)")
        }
        rep?.alerts?.let { list ->
            items(list) { a ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (a.severity) { Severity.CRITICAL -> Icons.Filled.Error; Severity.WARNING -> Icons.Filled.Warning; else -> Icons.Filled.Info }, null,
                                tint = when (a.severity) { Severity.CRITICAL -> MaterialTheme.colorScheme.error; Severity.WARNING -> MaterialTheme.colorScheme.secondary; else -> MaterialTheme.colorScheme.onSurfaceVariant })
                            Spacer(Modifier.width(8.dp)); Text(a.title, style = MaterialTheme.typography.titleMedium)
                        }
                        Text(a.message)
                        when (a.action.name) {
                            "BACKUP_NOW" -> TextButton(onClick = { if (pro.unlocked) pro.backupWatchedNow() else pro.showUnlock = true }) { Text(a.action.label) }
                            "VERIFY" -> TextButton(onClick = { if (pro.unlocked) pro.checkNow(full = true) else pro.showUnlock = true }) { Text(a.action.label) }
                            "REAUTHORIZE" -> TextButton(onClick = { addFolder.launch(null) }) { Text(a.action.label) }
                            "FREE_SPACE" -> TextButton(onClick = { vm.go(Screen.SMART_CLOUD) }) { Text(a.action.label) }
                            "RETRY_SYNC" -> TextButton(onClick = { pro.backupWatchedNow() }) { Text(a.action.label) }
                            else -> {}
                        }
                    }
                }
            }
        }
        item { SectionTitle("Carpetas supervisadas") }
        item { Text("Solo se revisan las carpetas que elijas aquí. Puedes quitarlas cuando quieras.", style = MaterialTheme.typography.bodyMedium) }
        items(pro.svc.watched) { w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.label, style = MaterialTheme.typography.titleMedium)
                    Text("${w.category.label} · última copia: ${Format.date(w.lastSuccessAt ?: 0)}", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { pro.removeWatched(w.id) }) { Text("Quitar") }
            }
        }
        item {
            Text("Toca cada una y después «Usar esta carpeta» › «Permitir». Solo hace falta una vez.", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Carpetas habituales: el selector se abre directamente en ellas.
                listOf(
                    Triple("Cámara", Category.PHOTOS, "DCIM"),
                    Triple("Fotos e imágenes", Category.PHOTOS, "Pictures"),
                    Triple("Documentos", Category.DOCUMENTS, "Documents"),
                    Triple("Vídeos", Category.VIDEOS, "Movies"),
                    Triple("WhatsApp", Category.CUSTOM_FOLDERS, "Android/media/com.whatsapp/WhatsApp/Media"),
                    Triple("Otra carpeta", Category.CUSTOM_FOLDERS, ""),
                ).forEach { (label, c, path) ->
                    val added = pro.svc.watched.any { it.label.equals(path.substringAfterLast('/').ifEmpty { "-" }, true) }
                    AssistChip(onClick = { cat = c; addFolder.launch(if (path.isEmpty()) null else suggested(path)) },
                        label = { Text(if (added) "$label ✓" else "Añadir: $label") }, leadingIcon = { Icon(Icons.Filled.Add, null) })
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Copiar ahora las carpetas supervisadas", Icons.Filled.Backup, enabled = pro.svc.watched.isNotEmpty()) {
                    if (pro.unlocked) pro.backupWatchedNow() else pro.showUnlock = true
                }
                OutlinedButton(onClick = { if (pro.unlocked) pro.checkNow(full = true) else pro.showUnlock = true }, modifier = Modifier.fillMaxWidth()) { Text("Comprobar integridad de todas las copias") }
            }
        }
        item { SectionTitle("Avisos y periodicidad") }
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Notificaciones", Modifier.weight(1f))
                    Switch(checked = monitor.notificationsEnabled, onCheckedChange = {
                        monitor = monitor.copy(notificationsEnabled = it); pro.saveMonitor(monitor)
                        if (it && android.os.Build.VERSION.SDK_INT >= 33) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    })
                }
                Text("Avisar si una carpeta lleva ${monitor.staleDays} días sin copia")
                Slider(value = monitor.staleDays.toFloat(), onValueChange = { monitor = monitor.copy(staleDays = it.toInt()) }, onValueChangeFinished = { pro.saveMonitor(monitor) }, valueRange = 1f..30f)
                Text("Revisar cada ${monitor.checkEveryHours} horas")
                Slider(value = monitor.checkEveryHours.toFloat(), onValueChange = { monitor = monitor.copy(checkEveryHours = it.toInt()) }, onValueChangeFinished = { pro.saveMonitor(monitor) }, valueRange = 6f..72f)
                Text("Categorías supervisadas", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Category.PHOTOS, Category.VIDEOS, Category.DOCUMENTS, Category.DOWNLOADS, Category.CUSTOM_FOLDERS, Category.SOCIAL_EXPORTS).forEach { c ->
                        val on = c in monitor.monitored
                        FilterChip(selected = on, onClick = { monitor = monitor.copy(monitored = if (on) monitor.monitored - c else monitor.monitored + c); pro.saveMonitor(monitor) }, label = { Text(c.label) })
                    }
                }
            }
        }
    }
}

// =============================================================================== Nube inteligente

@Composable
fun SmartCloudScreen(vm: AppViewModel, modifier: Modifier) {
    val pro = vm.pro
    val context = LocalContext.current
    var email by remember { mutableStateOf(pro.svc.cloudEmail ?: "") }
    var pw by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(pro.svc.cloudUrl) }
    var confirmDelete by remember { mutableStateOf(false) }
    var retention by remember { mutableStateOf(pro.retention) }
    var confirmPrune by remember { mutableStateOf<Int?>(null) }
    val pickLocal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> if (uri != null) { pro.setLocalFolder(uri); pro.showUnlock = true } }
    LaunchedEffect(pro.cloudConfigured) { if (pro.cloudConfigured) pro.refreshUsage() }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("Dónde se guardan tus copias") }
        item {
            InfoCard(Icons.Filled.Info, "Local, nube o ambas",
                "Las copias se cifran en tu teléfono antes de salir de él. La nube solo recibe datos ilegibles, deduplicados y comprimidos cuando conviene. " +
                    "Las copias locales no tienen límite: solo el espacio de tu dispositivo.")
        }
        item {
            Text("Bóveda local: " + (pro.svc.localTree?.let { "configurada" } ?: "sin configurar"), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { pickLocal.launch(null) }) { Text(if (pro.localConfigured) "Cambiar carpeta" else "Elegir carpeta (recomendado: SD o pendrive)") }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Cloud, null); Spacer(Modifier.width(8.dp)); Text("Nube SocialRecover Pro", style = MaterialTheme.typography.titleMedium) }
                    if (pro.cloudConfigured) {
                        val u = pro.usage
                        Text("Cuenta: ${pro.svc.cloudEmail ?: ""}")
                        if (u != null && u.quotaBytes != null && u.quotaBytes!! > 0) {
                            LinearProgressIndicator(progress = { (u.usedBytes.toFloat() / u.quotaBytes!!).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("${Format.size(context, u.usedBytes)} de ${Format.size(context, u.quotaBytes!!)} usados")
                        }
                        Text("Plan anual: 29,99 € con 5 GB de nube. Puedes ampliar el espacio con planes opcionales.", style = MaterialTheme.typography.bodyMedium)
                        Row {
                            TextButton(onClick = { pro.cloudSignOut() }) { Text("Cerrar sesión") }
                            TextButton(onClick = { confirmDelete = true }) { Text("Eliminar cuenta y datos", color = MaterialTheme.colorScheme.error) }
                        }
                    } else {
                        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Correo") }, singleLine = true)
                        PasswordField(pw, { pw = it }, "Contraseña de la cuenta (no la de la bóveda)")
                        OutlinedTextField(totp, { totp = it.filter { c -> c.isDigit() }.take(6) }, Modifier.fillMaxWidth(), label = { Text("Código de verificación (si lo activaste)") }, singleLine = true)
                        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Servidor") }, singleLine = true)
                        Row {
                            TextButton(enabled = email.contains('@') && pw.length >= 10, onClick = { pro.svc.cloudUrl = url; pro.cloudSignIn(email, pw, totp, register = false); pw = "" }) { Text("Iniciar sesión") }
                            TextButton(enabled = email.contains('@') && pw.length >= 10, onClick = { pro.svc.cloudUrl = url; pro.cloudSignIn(email, pw, null, register = true); pw = "" }) { Text("Crear cuenta") }
                        }
                    }
                }
            }
        }
        item { SectionTitle("Qué va a cada sitio") }
        item { Text("Por ejemplo: vídeos grandes en local y documentos importantes también en la nube.", style = MaterialTheme.typography.bodyMedium) }
        items(listOf(Category.PHOTOS, Category.VIDEOS, Category.DOCUMENTS, Category.SOCIAL_EXPORTS, Category.DOWNLOADS, Category.CUSTOM_FOLDERS, Category.PASSWORD_EXPORTS)) { c ->
            Column {
                Text(c.label, style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Destination.entries.forEach { d ->
                        val cur = pro.policy.byCategory[c] ?: pro.policy.default
                        FilterChip(selected = cur == d, onClick = { pro.savePolicy(pro.policy.copy(byCategory = pro.policy.byCategory + (c to d))) }, label = { Text(d.label) })
                    }
                }
            }
        }
        item {
            val mb = (pro.policy.maxCloudFileBytes shr 20).toInt()
            Text(if (mb == 0) "Sin límite de tamaño para la nube" else "Archivos de más de $mb MB: solo en local")
            Slider(value = mb.toFloat(), onValueChange = { pro.savePolicy(pro.policy.copy(maxCloudFileBytes = it.toLong() shl 20)) }, valueRange = 0f..2048f)
            TextButton(onClick = { pro.savePolicy(HybridPolicy.SUGGESTED) }) { Text("Usar la configuración recomendada") }
        }
        item { SectionTitle("Conservación de versiones") }
        item {
            Column {
                Text("Actual: ${pro.retention.describe()}", style = MaterialTheme.typography.bodyMedium)
                Text("Nunca se borra ninguna versión sin que aceptes una política.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = retention.keepAll, onClick = { retention = RetentionPolicy.KEEP_ALL }, label = { Text("Conservar todo") })
                    FilterChip(selected = retention == RetentionPolicy.RECOMMENDED, onClick = { retention = RetentionPolicy.RECOMMENDED }, label = { Text("Recomendada") })
                    FilterChip(selected = retention == RetentionPolicy(keepLast = 5, keepMonthly = 12), onClick = { retention = RetentionPolicy(keepLast = 5, keepMonthly = 12) }, label = { Text("Ahorro máximo") })
                }
                Text(retention.describe(), style = MaterialTheme.typography.bodyMedium)
                TextButton(enabled = pro.unlocked, onClick = {
                    val n = pro.previewRetention(retention)
                    if (n == 0 || retention.keepAll) pro.applyRetention(retention, confirmed = false) else confirmPrune = n
                }) { Text("Guardar política") }
            }
        }
    }

    confirmPrune?.let { n ->
        AlertDialog(onDismissRequest = { confirmPrune = null }, icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text("¿Eliminar $n versiones antiguas?") },
            text = { Text("Con la política «${retention.describe()}» se eliminarán $n copias antiguas para liberar espacio. Las versiones que se conserven no se tocan. Esta acción no se puede deshacer.") },
            confirmButton = { TextButton(onClick = { pro.applyRetention(retention, confirmed = true); confirmPrune = null }) { Text("Aceptar y aplicar") } },
            dismissButton = { TextButton(onClick = { pro.applyRetention(retention, confirmed = false); confirmPrune = null }) { Text("Guardar sin borrar nada") } })
    }
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false }, icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text("¿Eliminar tu cuenta?") },
            text = { Text("Se borrarán tu cuenta y TODAS tus copias en la nube de forma permanente. Las copias locales no se tocan.") },
            confirmButton = { TextButton(onClick = { pro.deleteCloudAccount(); confirmDelete = false }) { Text("Eliminar definitivamente", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } })
    }
}

/** Página oficial donde cada red permite descargar una copia de TUS datos (los menús pueden cambiar). */
fun officialExportUrl(n: SocialNetwork): String = when (n) {
    SocialNetwork.INSTAGRAM, SocialNetwork.THREADS -> "https://accountscenter.instagram.com/info_and_permissions/dyi/"
    SocialNetwork.FACEBOOK -> "https://accountscenter.facebook.com/info_and_permissions/dyi/"
    SocialNetwork.TIKTOK -> "https://www.tiktok.com/setting/download-your-data"
    SocialNetwork.YOUTUBE -> "https://takeout.google.com/"
    SocialNetwork.X -> "https://x.com/settings/download_your_data"
    SocialNetwork.REDDIT -> "https://www.reddit.com/settings/data-request"
    SocialNetwork.PINTEREST -> "https://www.pinterest.com/settings/privacy"
    SocialNetwork.SNAPCHAT -> "https://accounts.snapchat.com/"
    SocialNetwork.OTHER -> "https://www.google.com/search?q=descargar+mis+datos"
}
