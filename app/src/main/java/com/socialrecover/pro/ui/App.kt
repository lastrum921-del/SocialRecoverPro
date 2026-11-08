@file:OptIn(ExperimentalMaterial3Api::class)

package com.socialrecover.pro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.socialrecover.pro.ui.screens.BackupDetailScreen
import com.socialrecover.pro.ui.screens.BackupsScreen
import com.socialrecover.pro.ui.screens.CategoriesDialog
import com.socialrecover.pro.ui.screens.CreateBackupScreen
import com.socialrecover.pro.ui.screens.DevicesScreen
import com.socialrecover.pro.ui.screens.HomeScreen
import com.socialrecover.pro.ui.screens.OnboardingScreen
import com.socialrecover.pro.ui.screens.SettingsScreen
import com.socialrecover.pro.ui.screens.SocialScreen
import com.socialrecover.pro.ui.screens.SocialWizardScreen
import com.socialrecover.pro.ui.screens.StorageScreen
import com.socialrecover.pro.ui.screens.GuidedRecoveryScreen
import com.socialrecover.pro.ui.screens.SmartCloudScreen
import com.socialrecover.pro.ui.screens.SocialHubScreen
import com.socialrecover.pro.ui.screens.StatusScreen
import com.socialrecover.pro.ui.screens.TimeMachineScreen
import com.socialrecover.pro.ui.screens.UnlockDialog
import com.socialrecover.pro.ui.screens.VersionsScreen

@Composable
fun App(vm: AppViewModel, onExit: () -> Unit) {
    if (!vm.onboardingDone) {
        OnboardingScreen(vm)
    } else {
        BackHandler { if (!vm.back()) onExit() }
        val title = when (vm.screen) {
            Screen.HOME -> null
            Screen.CREATE -> "Crear copia"
            Screen.BACKUPS -> "Copias de la versión 1.0"
            Screen.BACKUP_DETAIL -> vm.opened?.manifest?.label ?: "Copia"
            Screen.SOCIAL -> "Redes sociales"
            Screen.SOCIAL_WIZARD -> "Importar de ${vm.wizardNetwork?.label ?: ""}"
            Screen.DEVICES -> "Mis dispositivos"
            Screen.STORAGE -> "Almacenamiento"
            Screen.SETTINGS -> "Configuración"
            Screen.TIME_MACHINE -> "Máquina del tiempo"
            Screen.SOCIAL_HUB -> "Mis redes sociales"
            Screen.STATUS -> "Estado de protección"
            Screen.SMART_CLOUD -> "Nube inteligente"
            Screen.GUIDED -> "Recuperar mis datos"
            Screen.VERSIONS -> "Versiones del archivo"
        }
        Scaffold(
            topBar = {
                if (title != null) {
                    TopAppBar(
                        title = { Text(title, maxLines = 1) },
                        navigationIcon = {
                            IconButton(onClick = { vm.back() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            val m = Modifier.fillMaxSize().padding(padding)
            when (vm.screen) {
                Screen.HOME -> HomeScreen(vm, m)
                Screen.CREATE -> CreateBackupScreen(vm, m)
                Screen.BACKUPS -> BackupsScreen(vm, m)
                Screen.BACKUP_DETAIL -> BackupDetailScreen(vm, m)
                Screen.SOCIAL -> SocialScreen(vm, m)
                Screen.SOCIAL_WIZARD -> SocialWizardScreen(vm, m)
                Screen.DEVICES -> DevicesScreen(vm, m)
                Screen.STORAGE -> StorageScreen(vm, m)
                Screen.SETTINGS -> SettingsScreen(vm, m)
                Screen.TIME_MACHINE -> TimeMachineScreen(vm, m)
                Screen.SOCIAL_HUB -> SocialHubScreen(vm, m)
                Screen.STATUS -> StatusScreen(vm, m)
                Screen.SMART_CLOUD -> SmartCloudScreen(vm, m)
                Screen.GUIDED -> GuidedRecoveryScreen(vm, m)
                Screen.VERSIONS -> VersionsScreen(vm, m)
            }
        }
    }

    if (vm.showCategoriesDialog) CategoriesDialog(vm)
    UnlockDialog(vm)
    BusyDialog(vm)
    vm.message?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.message = null },
            confirmButton = { TextButton(onClick = { vm.message = null }) { Text("Aceptar") } },
            title = { Text(msg.title) },
            text = { Text(msg.text) },
            iconContentColor = if (msg.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun BusyDialog(vm: AppViewModel) {
    val b = vm.busy ?: return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(b.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val p = b.progress
                val total = p?.bytesTotal
                if (p != null && total != null && total > 0) {
                    val f = (p.bytesDone.toDouble() / total).toFloat().coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                    Text("${(f * 100).toInt()} % · ${Format.size(context, p.bytesDone)} de ${Format.size(context, total)}")
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (p != null && p.currentName.isNotEmpty()) {
                    Text("Archivo ${minOf(p.filesDone + 1, p.filesTotal)} de ${p.filesTotal}: ${p.currentName}",
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                }
                Text("Mantén la aplicación abierta hasta que termine.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            if (b.cancellable) TextButton(onClick = { vm.cancel() }) { Text("Cancelar") }
        },
    )
}
