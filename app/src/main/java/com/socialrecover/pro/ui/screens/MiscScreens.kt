@file:OptIn(ExperimentalMaterial3Api::class)

package com.socialrecover.pro.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.socialrecover.pro.BuildConfig
import com.socialrecover.pro.ui.AppViewModel
import com.socialrecover.pro.ui.Format
import com.socialrecover.pro.ui.components.BigButton
import com.socialrecover.pro.ui.components.InfoCard
import com.socialrecover.pro.ui.components.SectionTitle

@Composable
private fun Page(modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
fun DevicesScreen(vm: AppViewModel, modifier: Modifier) = Page(modifier) {
    InfoCard(Icons.Filled.Smartphone, "Este dispositivo", "${vm.repo.deviceName}\nCopias locales activas.")
    InfoCard(Icons.Filled.CloudOff, "Sincronización entre dispositivos",
        "Llegará con la cuenta de usuario y la nube cifrada (fase 3). Mientras tanto, puedes llevar tus copias a otro " +
            "dispositivo copiando la carpeta de copias (por ejemplo, en un pendrive): se abren con la misma contraseña.")
}

@Composable
fun StorageScreen(vm: AppViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.setBackupFolder(uri)
    }
    Page(modifier) {
        SectionTitle("Modalidad")
        ModeRow("Local", "Copias cifradas en el teléfono, una tarjeta SD o un pendrive.", selected = vm.pro.localConfigured && !vm.pro.cloudConfigured, enabled = true)
        ModeRow("Nube", "Copias cifradas en tu dispositivo antes de subirlas.", selected = vm.pro.cloudConfigured && !vm.pro.localConfigured, enabled = true)
        ModeRow("Híbrido", "Una copia local y otra en la nube, según tus preferencias.", selected = vm.pro.localConfigured && vm.pro.cloudConfigured, enabled = true)
        BigButton("Configurar Nube inteligente", Icons.Filled.FolderOpen) { vm.go(com.socialrecover.pro.ui.Screen.SMART_CLOUD) }
        androidx.compose.material3.TextButton(onClick = { vm.go(com.socialrecover.pro.ui.Screen.BACKUPS) }) { Text("Copias de la versión anterior (1.0)") }

        SectionTitle("Carpeta de copias de la versión 1.0")
        Text(if (vm.folderOk) vm.folderLabel ?: "" else "Sin carpeta o sin permiso de acceso")
        BigButton("Cambiar carpeta", Icons.Filled.FolderOpen) { pick.launch(null) }
        Text("Al cambiar de carpeta, las copias anteriores siguen en la carpeta antigua. Puedes volver a elegirla cuando quieras.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionTitle("Espacio")
        Text("Ocupado por las copias: ${Format.size(context, vm.backups.sumOf { it.bytesOnDisk ?: 0L })}")
        Text("Libre en el dispositivo: ${Format.size(context, vm.repo.freeDeviceBytes())}")
    }
}

@Composable
private fun ModeRow(title: String, text: String, selected: Boolean, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, onClick = {}), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier) = Page(modifier) {
    BigButton("Categorías protegidas", Icons.Filled.Category) { vm.showCategoriesDialog = true }
    InfoCard(Icons.Filled.Lock, "Seguridad",
        "Cifrado AES-256-GCM por bloques. La clave se deriva de tu contraseña con PBKDF2-HMAC-SHA256 (600 000 iteraciones). " +
            "La contraseña nunca se guarda. Cada archivo lleva una huella SHA-256 que se comprueba al restaurar.")
    InfoCard(Icons.Filled.PrivacyTip, "Privacidad",
        "Solo se conecta a internet si activas la Nube inteligente, y lo que envía ya está cifrado en tu teléfono. Solo accede a los archivos y carpetas que tú eliges, " +
            "y puedes retirar ese acceso desde los ajustes de Android. La búsqueda en lenguaje natural funciona sin conexión.")
    InfoCard(Icons.Filled.Info, "Limitaciones de esta versión",
        "• Las copias automáticas se ejecutan cuando Android lo permite (WorkManager): no a una hora exacta.\n" +
            "• Mantén la aplicación abierta mientras se crea o restaura una copia manual.\n" +
            "• Sin vista previa de imágenes dentro de la copia (sí comparación de documentos de texto).")
    Text("SocialRecover Pro ${BuildConfig.VERSION_NAME} · versión gratuita de prueba",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
