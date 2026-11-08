@file:OptIn(ExperimentalMaterial3Api::class)

package com.socialrecover.pro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.socialrecover.core.engine.BackupSummary
import com.socialrecover.pro.ui.AppViewModel
import com.socialrecover.pro.ui.Format
import com.socialrecover.pro.ui.Screen
import com.socialrecover.pro.ui.components.TileCard
import com.socialrecover.pro.ui.components.BigButton
import com.socialrecover.pro.ui.theme.Navy
import com.socialrecover.pro.ui.theme.NavyLight
import com.socialrecover.pro.ui.theme.TurquoiseLight

@Composable
fun HomeScreen(vm: AppViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val vaultSnaps = vm.pro.library?.snapshots.orEmpty()
    val okBackups = vm.backups.filter { it.status == BackupSummary.Status.OK } + vaultSnaps.map { BackupSummary(it.ref.name, null, BackupSummary.Status.OK, null) }
    val used = vm.backups.sumOf { it.bytesOnDisk ?: 0L }
    val (stateText, stateOk) = when {
        !vm.folderOk && !vm.pro.localConfigured && !vm.pro.cloudConfigured -> "Elige dónde guardar tus copias" to false
        okBackups.isEmpty() -> "Aún no tienes ninguna copia" to false
        else -> "Protegido · ${okBackups.size} copia(s) disponible(s)" to true
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        // Cabecera
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Navy, NavyLight)), RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shield, null, tint = TurquoiseLight, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("SocialRecover Pro", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Text("Tus archivos, siempre protegidos.", color = TurquoiseLight)
                }
            }
            Spacer(Modifier.size(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (stateOk) Icons.Filled.Shield else Icons.Filled.Warning, null,
                    tint = if (stateOk) TurquoiseLight else Color(0xFFFBBF24))
                Spacer(Modifier.width(8.dp))
                Text(stateText, color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.size(12.dp))
            StatusLine("Última copia", Format.date(maxOf(vm.lastBackupAt, vaultSnaps.maxOfOrNull { it.ref.createdAt } ?: 0L)))
            StatusLine("Espacio utilizado", Format.size(context, used + vaultSnaps.sumOf { it.snapshot.stats.bytesStored }))
            StatusLine("Dispositivos conectados", (vaultSnaps.map { it.snapshot.device }.toSet() + vm.pro.svc.device).size.toString())
            StatusLine("Sincronización", when {
                !vm.pro.cloudConfigured -> "Nube desactivada · solo local"
                vm.pro.svc.lastSyncError != null -> "Error: reintentar"
                vm.pro.svc.lastSyncAt > 0 -> "Nube: " + Format.date(vm.pro.svc.lastSyncAt)
                else -> "Nube conectada"
            })
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Lo más importante, lo más grande: recuperar algo perdido.
            // Un solo botón para protegerlo todo (y cada día automáticamente si está activado).
            BigButton("Proteger todo ahora", Icons.Filled.Shield) {
                vm.pro.protectAll(onNoFolders = {
                    vm.message = com.socialrecover.pro.ui.UiMessage("Elige qué proteger",
                        "Añade una vez tus carpetas (cámara, fotos, documentos, WhatsApp…). Después este botón, y la copia automática diaria, las protegerán todas.")
                    vm.goPro(Screen.STATUS)
                })
            }
            BigButton("Recuperar archivos (guiado)", Icons.Filled.Restore) { vm.pro.resetGuided(); vm.goPro(Screen.GUIDED) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TileCard("Crear copia", "Elige qué proteger", Icons.Filled.Backup, Modifier.weight(1f)) { vm.goPro(Screen.CREATE) }
                TileCard("Máquina del tiempo", "Recupera por fecha", Icons.Filled.History, Modifier.weight(1f)) { vm.goPro(Screen.TIME_MACHINE) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TileCard("Mis redes sociales", "Importa y busca recuerdos", Icons.Filled.Groups, Modifier.weight(1f)) { vm.goPro(Screen.SOCIAL_HUB) }
                TileCard("Estado de protección", "Avisos y carpetas", Icons.Filled.VerifiedUser, Modifier.weight(1f)) { vm.goPro(Screen.STATUS) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TileCard("Nube inteligente", "Local, nube o ambas", Icons.Filled.Cloud, Modifier.weight(1f)) { vm.go(Screen.SMART_CLOUD) }
                TileCard("Mis dispositivos", "Este teléfono", Icons.Filled.Devices, Modifier.weight(1f)) { vm.go(Screen.DEVICES) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TileCard("Almacenamiento", "Copias y espacio", Icons.Filled.Storage, Modifier.weight(1f)) { vm.go(Screen.STORAGE) }
                TileCard("Configuración", "Categorías y seguridad", Icons.Filled.Settings, Modifier.weight(1f)) { vm.go(Screen.SETTINGS) }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.weight(1f))
        Text(value, color = Color.White)
    }
}
