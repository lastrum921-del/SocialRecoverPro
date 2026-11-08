@file:OptIn(ExperimentalMaterial3Api::class)

package com.socialrecover.pro.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.socialrecover.core.model.Category
import com.socialrecover.pro.ui.AppViewModel
import com.socialrecover.pro.ui.Format
import com.socialrecover.pro.ui.components.BigButton
import com.socialrecover.pro.ui.components.InfoCard

/** Descripción de cada categoría para la ventana de configuración. */
fun Category.description(): String = when (this) {
    Category.PHOTOS -> "Fotografías de la cámara, capturas y fotos guardadas."
    Category.VIDEOS -> "Vídeos grabados o descargados, incluidos vídeos grandes."
    Category.DOCUMENTS -> "PDF, Word, Excel, textos y otros documentos."
    Category.DOWNLOADS -> "Cualquier archivo de tu carpeta de descargas."
    Category.SOCIAL_EXPORTS -> "Archivos ZIP descargados con las herramientas oficiales de Instagram, TikTok, Facebook…"
    Category.MUSIC -> "Canciones, notas de voz y otros audios."
    Category.ARCHIVES -> "Archivos ZIP, RAR, 7z y similares."
    Category.PROJECTS -> "Carpetas de trabajos, estudios o proyectos personales."
    Category.BOOKMARKS -> "Marcadores exportados desde tu navegador (archivo HTML)."
    Category.HISTORY -> "Historiales que hayas exportado tú desde otras aplicaciones."
    Category.FORUMS -> "Exportaciones de tus propios comentarios en foros y comunidades."
    Category.APP_FILES -> "Archivos que otras aplicaciones permitan exportar o compartir."
    Category.PASSWORD_EXPORTS -> "Exportaciones que TÚ generes desde tu gestor de contraseñas. Datos muy sensibles."
    Category.CUSTOM_FOLDERS -> "Carpetas completas que elijas tú, con sus subcarpetas."
    Category.OTHER -> "Otros archivos."
}

@Composable
fun OnboardingScreen(vm: AppViewModel) {
    var step by rememberSaveable { mutableStateOf(0) }
    var categoriesShown by rememberSaveable { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) { vm.setBackupFolder(uri); vm.pro.setLocalFolder(uri) }
    }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Paso ${step + 1} de 3", color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (step) {
            0 -> {
                Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(72.dp).align(Alignment.CenterHorizontally))
                Text("SocialRecover Pro", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Text("Tus archivos, siempre protegidos.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.secondary)
                InfoCard(Icons.Filled.CheckCircle, "Qué hace",
                    "Guarda copias cifradas de los archivos que TÚ elijas y te permite recuperarlos aunque borres los originales. " +
                        "Solo tú conoces la contraseña.")
                InfoCard(Icons.Filled.Info, "Qué no hace",
                    "No entra en tus cuentas ni en las de otras personas, no lee mensajes privados y no puede recuperar contenido " +
                        "borrado definitivamente de los servidores de las redes sociales. Solo usa internet si activas la nube.")
                BigButton("Empezar", Icons.Filled.Shield) { step = 1 }
            }
            1 -> {
                Text("¿Dónde guardamos tus copias?", style = MaterialTheme.typography.headlineMedium)
                Text("Elige una carpeta. Para más seguridad, usa una tarjeta SD o un pendrive USB: si pierdes o restableces el teléfono, la copia sigue a salvo.")
                BigButton(if (vm.folderOk) "Cambiar carpeta" else "Elegir carpeta", Icons.Filled.FolderOpen) { pickFolder.launch(null) }
                if (vm.folderOk) {
                    InfoCard(Icons.Filled.CheckCircle, "Carpeta elegida", vm.folderLabel ?: "")
                    BigButton("Continuar", Icons.Filled.CheckCircle) {
                        step = 2
                        if (!categoriesShown) { categoriesShown = true; vm.showCategoriesDialog = true }
                    }
                }
                TextButton(onClick = { step = 0 }) { Text("Atrás") }
            }
            else -> {
                Text("Qué quieres proteger", style = MaterialTheme.typography.headlineMedium)
                Text("Categorías activas: " + vm.enabledCategories.sortedBy { it.ordinal }.joinToString { it.label })
                OutlinedButton(onClick = { vm.showCategoriesDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Cambiar categorías") }
                InfoCard(Icons.Filled.Info, "Copias en el teléfono",
                    "Android no permite que una aplicación lea todos tus archivos a escondidas. Tú eliges los archivos o carpetas " +
                        "de cada copia con el selector del sistema, y puedes revocar el acceso cuando quieras.")
                BigButton("Crear mi bóveda cifrada", Icons.Filled.Shield) { vm.finishOnboarding(); vm.pro.showUnlock = true }
                TextButton(onClick = { step = 1 }) { Text("Atrás") }
            }
        }
    }
}

/** Ventana emergente «¿Quieres proteger más información?». Nunca activa nada sin confirmación. */
@Composable
fun CategoriesDialog(vm: AppViewModel) {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(vm.enabledCategories) }
    var confirmSensitive by remember { mutableStateOf(false) }
    val list = Category.entries.filter { it != Category.OTHER }

    AlertDialog(
        onDismissRequest = { vm.showCategoriesDialog = false },
        title = { Text("¿Quieres proteger más información?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("SocialRecover Pro puede guardar más categorías de archivos. Selecciona únicamente las que quieras proteger. " +
                    "Puedes cambiar esta configuración cuando quieras.")
                Spacer(Modifier.height(8.dp))
                Text("Espacio libre en este dispositivo: ${Format.size(context, vm.repo.freeDeviceBytes())}. " +
                    "El tamaño exacto de cada copia se calcula al elegir los archivos.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(8.dp))
                list.forEach { c ->
                    val on = c in chosen
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (!on && c.sensitive) confirmSensitive = true
                            else chosen = if (on) chosen - c else chosen + c
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(c.label, style = MaterialTheme.typography.titleMedium)
                            Text(c.description(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                }
                TextButton(onClick = { vm.showCategoriesDialog = false }) {
                    Text("Configurar más tarde (Configuración › Categorías)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.saveCategories(chosen); vm.showCategoriesDialog = false }) { Text("Activar protección") }
        },
        dismissButton = {
            TextButton(onClick = { vm.showCategoriesDialog = false }) { Text("Ahora no") }
        },
    )

    if (confirmSensitive) {
        AlertDialog(
            onDismissRequest = { confirmSensitive = false },
            icon = { Icon(Icons.Filled.Warning, null) },
            title = { Text("Datos muy sensibles") },
            text = {
                Text("Esta categoría sirve para guardar exportaciones que tú mismo generes desde tu gestor de contraseñas. " +
                    "SocialRecover Pro nunca captura contraseñas por su cuenta. La copia quedará cifrada con tu contraseña; " +
                    "elige una contraseña larga y no la reutilices. Borra el archivo exportado sin cifrar después de la copia.")
            },
            confirmButton = {
                TextButton(onClick = { chosen = chosen + Category.PASSWORD_EXPORTS; confirmSensitive = false }) { Text("Entiendo, activar") }
            },
            dismissButton = { TextButton(onClick = { confirmSensitive = false }) { Text("Cancelar") } },
        )
    }
}
