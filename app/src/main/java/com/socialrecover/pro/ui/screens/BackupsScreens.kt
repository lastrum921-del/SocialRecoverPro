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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.socialrecover.core.engine.BackupSummary
import com.socialrecover.core.engine.RestoreReport
import com.socialrecover.core.model.Category
import com.socialrecover.core.model.ManifestEntry
import com.socialrecover.pro.ui.AppViewModel
import com.socialrecover.pro.ui.Format
import com.socialrecover.pro.ui.components.BigButton
import com.socialrecover.pro.ui.components.InfoCard
import com.socialrecover.pro.ui.components.PasswordField
import com.socialrecover.pro.ui.theme.Ok

fun Category.icon(): ImageVector = when (this) {
    Category.PHOTOS -> Icons.Filled.Image
    Category.VIDEOS -> Icons.Filled.Movie
    Category.MUSIC -> Icons.Filled.MusicNote
    Category.DOCUMENTS -> Icons.Filled.Description
    else -> Icons.Filled.InsertDriveFile
}

/** Pantalla «Recuperar mis datos»: lista de copias. */
@Composable
fun BackupsScreen(vm: AppViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var askPasswordFor by remember { mutableStateOf<BackupSummary?>(null) }
    var askDelete by remember { mutableStateOf<BackupSummary?>(null) }
    var askMigrate by remember { mutableStateOf<BackupSummary?>(null) }
    val pickBackupFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.setBackupFolder(uri)
    }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            InfoCard(Icons.Filled.Restore, "Copias de la versión anterior",
                "Copias creadas con SocialRecover Pro 1.0 (cada una con su propia contraseña). Puedes restaurarlas aquí o pasarlas a la " +
                    "Máquina del tiempo con el botón del reloj. Recuperar una copia no vuelve a publicar nada en ninguna red social.")
        }
        if (!vm.folderOk) {
            item {
                Column {
                    Text("No hay acceso a la carpeta de copias. Si las copias están en un pendrive o tarjeta SD, conéctalo y vuelve a elegir la carpeta.")
                    BigButton("Elegir carpeta de copias", Icons.Filled.FolderOpen, Modifier.padding(top = 8.dp)) { pickBackupFolder.launch(null) }
                }
            }
        } else if (vm.backups.isEmpty()) {
            item { Text("No hay copias en esta carpeta todavía.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(vm.backups, key = { it.dirName }) { b ->
            val ok = b.status == BackupSummary.Status.OK
            Card(
                onClick = { if (ok) askPasswordFor = b },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ok) Icons.Filled.Lock else Icons.Filled.Warning, null,
                        tint = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.info?.label ?: b.dirName, style = MaterialTheme.typography.titleMedium)
                        val details = when (b.status) {
                            BackupSummary.Status.OK -> {
                                val i = b.info!!
                                "${Format.date(i.createdAt)} · ${i.fileCount} archivo(s) · ${Format.size(context, i.totalBytes)}"
                            }
                            BackupSummary.Status.INCOMPLETE -> "Copia incompleta: se interrumpió mientras se creaba. No se puede restaurar."
                            BackupSummary.Status.UNREADABLE -> "No se puede leer la información de esta copia (dañada o de otra versión)."
                        }
                        Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (ok) IconButton(onClick = { if (vm.pro.unlocked) askMigrate = b else vm.pro.showUnlock = true }) {
                        Icon(Icons.Filled.History, contentDescription = "Pasar a la Máquina del tiempo")
                    }
                    IconButton(onClick = { askDelete = b }) { Icon(Icons.Filled.Delete, contentDescription = "Eliminar copia") }
                }
            }
        }
    }

    askPasswordFor?.let { b ->
        var pw by remember(b.dirName) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPasswordFor = null },
            title = { Text("Abrir «${b.info?.label}»") },
            text = { Column { Text("Introduce la contraseña de esta copia."); Spacer(Modifier.height(8.dp)); PasswordField(pw, { pw = it }, "Contraseña") } },
            confirmButton = {
                TextButton(enabled = pw.isNotEmpty(), onClick = {
                    vm.openBackup(b.dirName, pw.toCharArray()); pw = ""; askPasswordFor = null
                }) { Text("Abrir") }
            },
            dismissButton = { TextButton(onClick = { askPasswordFor = null }) { Text("Cancelar") } },
        )
    }

    askMigrate?.let { b ->
        var pw by remember(b.dirName) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askMigrate = null },
            icon = { Icon(Icons.Filled.History, null) },
            title = { Text("Pasar a la Máquina del tiempo") },
            text = { Column { Text("Se añadirá «${b.info?.label}» a tu bóveda con su fecha original. La copia antigua no se borra."); Spacer(Modifier.height(8.dp)); PasswordField(pw, { pw = it }, "Contraseña de esta copia") } },
            confirmButton = { TextButton(enabled = pw.isNotEmpty(), onClick = { vm.migrateLegacy(b.dirName, pw.toCharArray()); pw = ""; askMigrate = null }) { Text("Migrar") } },
            dismissButton = { TextButton(onClick = { askMigrate = null }) { Text("Cancelar") } },
        )
    }

    askDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text("¿Eliminar esta copia?") },
            text = { Text("Se borrará «${b.info?.label ?: b.dirName}» de la carpeta de copias. Esta acción no se puede deshacer.") },
            confirmButton = { TextButton(onClick = { vm.deleteBackup(b.dirName); askDelete = null }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text("Cancelar") } },
        )
    }
}

/** Contenido de una copia abierta: búsqueda, filtros, selección y restauración. */
@Composable
fun BackupDetailScreen(vm: AppViewModel, modifier: Modifier) {
    val b = vm.opened ?: return
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var catFilter by remember { mutableStateOf<Category?>(null) }
    var checked by remember(b.dirName) { mutableStateOf(b.manifest.entries.map { it.id }.toSet()) }
    val pickDest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.restore(checked, uri)
    }

    val categories = b.manifest.entries.map { it.category }.distinct().sortedBy { it.ordinal }
    val visible = b.manifest.entries.filter { e ->
        (catFilter == null || e.category == catFilter) &&
            (query.isBlank() || e.path.contains(query.trim(), ignoreCase = true))
    }
    val selectedBytes = b.manifest.entries.filter { it.id in checked }.sumOf { it.size }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("${Format.date(b.manifest.createdAt)} · ${b.manifest.deviceName} · ${b.manifest.entries.size} archivo(s) · " +
                Format.size(context, b.manifest.totalBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        vm.restoreReport?.let { r -> item { ReportCard(r) } }
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) }, label = { Text("Buscar por nombre o carpeta") })
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = catFilter == null, onClick = { catFilter = null }, label = { Text("Todo") })
                categories.forEach { c ->
                    FilterChip(selected = catFilter == c, onClick = { catFilter = if (catFilter == c) null else c }, label = { Text(c.label) })
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${checked.size} seleccionado(s) · ${Format.size(context, selectedBytes)}", Modifier.weight(1f))
                TextButton(onClick = { checked = checked + visible.map { it.id } }) { Text("Todos") }
                TextButton(onClick = { checked = checked - visible.map { it.id }.toSet() }) { Text("Ninguno") }
            }
        }
        items(visible, key = { it.id }) { e ->
            EntryRow(e, e.id in checked) { on -> checked = if (on) checked + e.id else checked - e.id }
        }
        item {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Restaurar seleccionados (${checked.size})", Icons.Filled.Restore, enabled = checked.isNotEmpty()) {
                    pickDest.launch(null)
                }
                Text("Elige la carpeta de destino. Se creará dentro una carpeta nueva; nunca se sobrescriben tus archivos. " +
                    "Si quieres más seguridad, elige una unidad distinta de la que contiene la copia.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { vm.verify() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.VerifiedUser, null); Spacer(Modifier.width(8.dp)); Text("Comprobar integridad de la copia")
                }
            }
        }
    }
}

@Composable
private fun EntryRow(e: ManifestEntry, checked: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    Column {
        Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onChange)
            Icon(e.category.icon(), null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, maxLines = 1)
                Text(listOfNotNull(e.folder.takeIf { it.isNotEmpty() }, Format.size(context, e.size)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ReportCard(r: RestoreReport) {
    val wroteFiles = r.results.any { it.path != null }
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (r.allOk) Icons.Filled.CheckCircle else Icons.Filled.Error, null,
                    tint = if (r.allOk) Ok else MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        r.allOk && wroteFiles -> "Restauración completada"
                        r.allOk -> "La copia está íntegra"
                        else -> "Restauración con problemas"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                if (wroteFiles) "${r.okCount} archivo(s) restaurados. Cada uno se ha releído del destino y su huella SHA-256 coincide con la del original."
                else "${r.okCount} archivo(s) comprobados: su contenido coincide con la huella SHA-256 original.",
            )
            r.results.firstOrNull { it.path != null }?.path?.substringBefore('/')?.let {
                Text("Carpeta de destino: $it", style = MaterialTheme.typography.bodyMedium)
            }
            if (r.failedCount > 0) {
                Text("${r.failedCount} archivo(s) con problemas:", color = MaterialTheme.colorScheme.error)
                r.results.filter { !it.ok }.take(8).forEach {
                    Text("• ${it.entry.name}: ${it.message ?: it.status.name}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            r.fatal?.let { Text(it.userMessage, color = MaterialTheme.colorScheme.error) }
        }
    }
}
