@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.socialrecover.pro.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.socialrecover.core.model.Category
import com.socialrecover.core.recovery.QueryParser
import com.socialrecover.core.vault.DiffLine
import com.socialrecover.core.vault.TimeMachine
import com.socialrecover.pro.ui.AppViewModel
import com.socialrecover.pro.ui.Format
import com.socialrecover.pro.ui.Screen
import com.socialrecover.pro.ui.components.BigButton
import com.socialrecover.pro.ui.components.InfoCard
import com.socialrecover.pro.ui.components.PasswordField
import com.socialrecover.pro.ui.theme.Ok
import java.time.LocalDate

/** Diálogo para abrir (o crear la primera vez) la bóveda cifrada. */
@Composable
fun UnlockDialog(vm: AppViewModel) {
    val pro = vm.pro
    if (!pro.showUnlock) return
    val firstTime = !(pro.localConfigured || pro.cloudConfigured)
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    // Copias automáticas activadas por defecto: el usuario puede desactivarlas.
    var keep by remember { mutableStateOf(true) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> if (uri != null) pro.setLocalFolder(uri) }
    // Comprobar si la bóveda ya existe implica leer el almacenamiento: fuera del hilo principal.
    val exists by androidx.compose.runtime.produceState<Boolean?>(null, pro.localConfigured, pro.cloudConfigured) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pro.svc.localExists() || pro.svc.cloudExists() }
    }
    val creating = exists == false
    AlertDialog(
        onDismissRequest = { pro.showUnlock = false },
        icon = { Icon(Icons.Filled.Lock, null) },
        title = { Text(if (creating) "Crea tu bóveda cifrada" else "Abre tu bóveda") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (firstTime) {
                    Text("Elige dónde guardar la bóveda (recomendado: tarjeta SD o pendrive). La nube se activa en «Nube inteligente».")
                    TextButton(onClick = { pickFolder.launch(null) }) { Text("Elegir carpeta para la bóveda") }
                } else {
                    Text(if (creating) "Todas tus copias se guardarán aquí, cifradas con esta contraseña. Nadie más puede abrirlas."
                    else "Escribe la contraseña de tu bóveda.")
                    PasswordField(pw, { pw = it }, "Contraseña de la bóveda")
                    if (creating) PasswordField(pw2, { pw2 = it }, "Repítela")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = keep, onCheckedChange = { keep = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Copias automáticas en este dispositivo (la clave se guarda en el chip de seguridad del teléfono)", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            val ok = !firstTime && exists != null && pw.length >= 8 && (!creating || pw == pw2)
            TextButton(enabled = ok, onClick = { pro.unlock(pw.toCharArray(), keep); pw = ""; pw2 = "" }) { Text(if (creating) "Crear bóveda" else "Abrir") }
        },
        dismissButton = { TextButton(onClick = { pro.showUnlock = false }) { Text("Cancelar") } },
    )
}

/** Tarjeta que aparece en las pantallas premium mientras la bóveda está cerrada. */
@Composable
fun LockedCard(vm: AppViewModel) {
    Column {
        InfoCard(Icons.Filled.Lock, "Bóveda cerrada", "Abre tu bóveda cifrada para ver tus copias. Si aún no tienes, elige dónde crearla.")
        BigButton("Abrir o crear bóveda", Icons.Filled.Lock, Modifier.padding(top = 8.dp)) { vm.pro.showUnlock = true }
    }
}

/** Máquina del tiempo digital: calendario con los días que tienen copias. */
@Composable
fun TimeMachineScreen(vm: AppViewModel, modifier: Modifier) {
    val pro = vm.pro
    val lib = pro.library
    val context = LocalContext.current
    if (!pro.unlocked || lib == null) { Column(modifier.padding(16.dp)) { LockedCard(vm) }; return }
    val tm = remember(lib) { TimeMachine(lib) }
    var selected by remember(pro.day) { mutableStateOf(setOf<String>()) }
    val dest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val d = pro.day ?: return@rememberLauncherForActivityResult
            pro.restore(tm.recoverableOn(d, pro.filter).filter { key(it.loaded.ref.name, it.file.path) in selected }.map { it.loaded to it.file }, uri)
        }
    }
    val days = tm.daysWithBackups(pro.month, pro.filter)
    val items = pro.day?.let { tm.filesOn(it, pro.filter) }.orEmpty()

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { pro.month = pro.month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Mes anterior") }
                Text("${QueryParser.monthName(pro.month.monthValue).replaceFirstChar { it.uppercase() }} ${pro.month.year}",
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                IconButton(onClick = { pro.month = pro.month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Mes siguiente") }
            }
        }
        item { CalendarGrid(pro.month.year, pro.month.monthValue, days, pro.day) { pro.day = it } }
        item {
            val years = tm.years()
            if (years.isNotEmpty()) Text("Años con copias: " + years.joinToString(" · ") { y -> "$y (${tm.monthsWithBackups(y).values.sum()})" },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null, Category.PHOTOS, Category.VIDEOS, Category.DOCUMENTS, Category.SOCIAL_EXPORTS).forEach { c ->
                    FilterChip(selected = pro.filter.category == c, onClick = { pro.filter = pro.filter.copy(category = c) }, label = { Text(c?.label ?: "Todo") })
                }
                tm.devices().forEach { d ->
                    FilterChip(selected = pro.filter.device == d, onClick = { pro.filter = pro.filter.copy(device = if (pro.filter.device == d) null else d) }, label = { Text(d) })
                }
            }
        }
        val d = pro.day
        if (d == null) {
            item { Text("Elige un día marcado para ver lo que se guardó.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            item {
                val snaps = tm.snapshotsOn(d, pro.filter)
                Text("${d.dayOfMonth}/${d.monthValue}/${d.year}: ${snaps.size} copia(s) · " +
                    snaps.joinToString { Format.date(it.ref.createdAt).substringAfter(' ').ifEmpty { "" } + " " + it.storage.label },
                    style = MaterialTheme.typography.titleMedium)
            }
            if (items.isEmpty()) item { Text("Ese día no hay copias con estos filtros.") }
            items(items, key = { key(it.loaded.ref.name, it.file.path) + it.loaded.storage }) { it ->
                val k = key(it.loaded.ref.name, it.file.path)
                Row(Modifier.fillMaxWidth().clickable(enabled = it.available) { selected = if (k in selected) selected - k else selected + k },
                    verticalAlignment = Alignment.CenterVertically) {
                    if (it.available) Checkbox(checked = k in selected, onCheckedChange = { on -> selected = if (on) selected + k else selected - k })
                    else Icon(Icons.Filled.Block, "No disponible", tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(it.file.name, maxLines = 1)
                        Text(listOfNotNull(it.file.folder.takeIf { f -> f.isNotEmpty() }, Format.size(context, it.file.size), it.file.platform?.label,
                            it.loaded.snapshot.device, if (it.available) null else "datos no disponibles").joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = if (it.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, maxLines = 2)
                    }
                    IconButton(onClick = { pro.openVersions(it.file.path); vm.go(Screen.VERSIONS) }) { Icon(Icons.Filled.History, "Versiones de ${it.file.name}") }
                }
                HorizontalDivider()
            }
            item {
                Column(Modifier.padding(vertical = 12.dp)) {
                    BigButton("Recuperar (${selected.size})", Icons.Filled.Restore, enabled = selected.isNotEmpty()) { dest.launch(null) }
                    TextButton(onClick = { selected = items.filter { it.available }.map { key(it.loaded.ref.name, it.file.path) }.toSet() }) { Text("Seleccionar todo lo disponible") }
                }
            }
        }
    }
}

private fun key(snap: String, path: String) = "$snap|$path"

@Composable
private fun CalendarGrid(year: Int, month: Int, days: Map<Int, Int>, selected: LocalDate?, onPick: (LocalDate) -> Unit) {
    val first = LocalDate.of(year, month, 1)
    val offset = first.dayOfWeek.value - 1
    val len = first.lengthOfMonth()
    Column {
        Row { listOf("L", "M", "X", "J", "V", "S", "D").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge) } }
        for (week in 0 until (offset + len + 6) / 7) {
            Row {
                for (dow in 0 until 7) {
                    val n = week * 7 + dow - offset + 1
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (n in 1..len) {
                            val has = (days[n] ?: 0) > 0
                            val date = LocalDate.of(year, month, n)
                            val isSel = date == selected
                            Box(
                                Modifier.size(44.dp)
                                    .background(
                                        when { isSel -> MaterialTheme.colorScheme.primary; has -> MaterialTheme.colorScheme.secondaryContainer; else -> Color.Transparent },
                                        CircleShape,
                                    )
                                    .clickable(enabled = has) { onPick(date) }
                                    .semantics { contentDescription = "Día $n" + if (has) ", ${days[n]} copias" else ", sin copias" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$n", fontWeight = if (has) FontWeight.Bold else FontWeight.Normal,
                                    color = when { isSel -> MaterialTheme.colorScheme.onPrimary; has -> MaterialTheme.colorScheme.onSecondaryContainer; else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Historial y comparación de versiones de un archivo. */
@Composable
fun VersionsScreen(vm: AppViewModel, modifier: Modifier) {
    val pro = vm.pro
    val context = LocalContext.current
    var a by remember { mutableStateOf<Int?>(null) }
    var b by remember { mutableStateOf<Int?>(null) }
    val vs = pro.versions
    val dest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val i = b ?: a ?: return@rememberLauncherForActivityResult
        val lib = pro.library ?: return@rememberLauncherForActivityResult
        if (uri != null) pro.restore(listOf(lib.snapshots.first { it.ref == vs[i].snapshot } to vs[i].file), uri)
    }
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(pro.versionPath ?: "", style = MaterialTheme.typography.titleMedium) }
        item { Text("Marca dos versiones para compararlas, o una para recuperarla.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(vs.indices.toList()) { i ->
            val v = vs[i]
            val on = i == a || i == b
            Card(onClick = {
                when { i == a -> a = null; i == b -> b = null; a == null -> a = i; else -> b = i }
            }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = on, onCheckedChange = null)
                    Column(Modifier.weight(1f)) {
                        Text(Format.date(v.snapshot.createdAt) + " · " + v.snapshot.storage.label)
                        Text("${Format.size(context, v.file.size)} · " + if (v.changed) "contenido nuevo" else "sin cambios respecto a la anterior",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = a != null && b != null, onClick = { val x = minOf(a!!, b!!); val y = maxOf(a!!, b!!); pro.compare(vs[x], vs[y]) }) { Text("Comparar") }
                TextButton(enabled = (a != null) xor (b != null), onClick = { dest.launch(null) }) { Text("Recuperar esta versión") }
            }
        }
        pro.comparison?.let { c ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (c.identical) "Las dos versiones son idénticas." else "Las versiones son distintas (${if (c.sizeDelta >= 0) "+" else ""}${Format.size(context, kotlin.math.abs(c.sizeDelta))}).",
                            style = MaterialTheme.typography.titleMedium)
                        c.textDiff?.take(400)?.forEach { l ->
                            val (prefix, col) = when (l) {
                                is DiffLine.Added -> "+ " to Ok
                                is DiffLine.Removed -> "− " to MaterialTheme.colorScheme.error
                                else -> "  " to MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(prefix + l.text, color = col, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (!c.identical && c.textDiff == null) Text("La comparación línea a línea solo está disponible para documentos de texto.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** Campo de búsqueda reutilizable. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}
