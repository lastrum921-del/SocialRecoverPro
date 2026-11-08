@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.socialrecover.pro.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.socialrecover.core.model.Category
import com.socialrecover.pro.ui.AppViewModel
import com.socialrecover.pro.ui.Format
import com.socialrecover.pro.ui.components.BigButton
import com.socialrecover.pro.ui.components.InfoCard
import com.socialrecover.pro.ui.components.SectionTitle

/** Tipos MIME que abre el selector del sistema para cada categoría. */
fun Category.mimeTypes(): Array<String> = when (this) {
    Category.PHOTOS -> arrayOf("image/*")
    Category.VIDEOS -> arrayOf("video/*")
    Category.MUSIC -> arrayOf("audio/*")
    Category.DOCUMENTS -> arrayOf(
        "application/pdf", "text/*", "application/msword", "application/rtf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
        "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
    )
    Category.SOCIAL_EXPORTS, Category.ARCHIVES -> arrayOf(
        "application/zip", "application/x-zip-compressed", "application/x-7z-compressed",
        "application/x-rar-compressed", "application/vnd.rar", "application/gzip", "application/octet-stream",
    )
    Category.BOOKMARKS -> arrayOf("text/html", "application/json")
    else -> arrayOf("*/*")
}

@Composable
fun CreateBackupScreen(vm: AppViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var pendingCategory by remember { mutableStateOf<Category?>(null) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        vm.addFiles(uris, pendingCategory)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.addFolder(uri)
    }

    val total = vm.selected.sumOf { it.sizeHint ?: 0L }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionTitle("1. Elige qué proteger") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.enabledCategories.sortedBy { it.ordinal }.forEach { c ->
                    AssistChip(
                        onClick = {
                            if (c == Category.CUSTOM_FOLDERS || c == Category.PROJECTS) {
                                pickFolder.launch(null)
                            } else {
                                pendingCategory = c
                                pickFiles.launch(c.mimeTypes())
                            }
                        },
                        label = { Text(c.label) },
                        leadingIcon = { Icon(Icons.Filled.Add, null) },
                    )
                }
                AssistChip(onClick = { pickFolder.launch(null) }, label = { Text("Carpeta completa") },
                    leadingIcon = { Icon(Icons.Filled.FolderOpen, null) })
            }
        }
        if (vm.selected.isEmpty()) {
            item { Text("Todavía no has elegido archivos.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            item {
                Text("${vm.selected.size} archivo(s) · ${Format.size(context, total)}", style = MaterialTheme.typography.titleMedium)
            }
            items(vm.selected.toList(), key = { it.uri.toString() }) { s ->
                Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.displayName, maxLines = 1)
                        Text(
                            listOfNotNull(s.category.label, s.folder.takeIf { it.isNotEmpty() }, s.sizeHint?.let { Format.size(context, it) }).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                    }
                    IconButton(onClick = { vm.removeSelected(s) }) { Icon(Icons.Filled.Close, contentDescription = "Quitar ${s.displayName}") }
                }
                HorizontalDivider()
                }
            }
        }

        item { SectionTitle("2. Nombre de la copia (opcional)") }
        item {
            OutlinedTextField(
                value = vm.backupLabel, onValueChange = { vm.backupLabel = it.take(80) },
                label = { Text("Ej.: Fotos del verano") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("El nombre, los archivos y sus nombres se guardan cifrados.") },
            )
        }
        item { SectionTitle("3. Guardar en tu bóveda cifrada") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!vm.pro.unlocked) {
                    LockedCard(vm)
                } else {
                    val p = vm.pro.policy
                    InfoCard(Icons.Filled.Lock, "Copia incremental y cifrada",
                        "Solo se guarda lo nuevo o lo modificado; los duplicados se guardan una vez. " +
                            (if (vm.pro.cloudConfigured) "Destino según tu Nube inteligente (p. ej. vídeos: ${(p.byCategory[Category.VIDEOS] ?: p.default).label.lowercase()})." else "Destino: bóveda local."))
                    BigButton("Crear copia", Icons.Filled.Lock, Modifier.padding(vertical = 8.dp), enabled = vm.selected.isNotEmpty()) { vm.backupToVault() }
                }
            }
        }
    }
}
