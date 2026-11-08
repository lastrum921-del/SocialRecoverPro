@file:OptIn(ExperimentalMaterial3Api::class)

package com.socialrecover.pro.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.socialrecover.core.social.SocialNetwork
import com.socialrecover.pro.ui.AppViewModel
import com.socialrecover.pro.ui.Format
import com.socialrecover.pro.ui.components.BigButton
import com.socialrecover.pro.ui.components.InfoCard

@Composable
fun SocialScreen(vm: AppViewModel, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard(Icons.Filled.Info, "Cómo funciona",
            "Cada red social te permite descargar una copia de TUS datos con su herramienta oficial. SocialRecover Pro te guía para " +
                "pedirla y la guarda cifrada. No accede a tu cuenta, no usa tu contraseña de la red social y no puede recuperar " +
                "contenido que la plataforma haya borrado definitivamente de sus servidores.")
        InfoCard(Icons.Filled.Warning, "Recuperar ≠ volver a publicar",
            "Recuperar una copia te devuelve los archivos a tu teléfono. Volver a publicarlos en la red social es un paso aparte que haces tú desde la aplicación oficial.")
        SocialNetwork.entries.forEach { n ->
            Card(
                onClick = { if (n.wizardAvailable) vm.startWizard(n) },
                enabled = n.wizardAvailable,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (n.wizardAvailable) Icons.Filled.Groups else Icons.Filled.Schedule, null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.label, style = MaterialTheme.typography.titleMedium)
                        Text(if (n.wizardAvailable) "Asistente de importación disponible" else "Próximamente",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun steps(n: SocialNetwork): List<String> = when (n) {
    SocialNetwork.INSTAGRAM -> listOf(
        "Abre Instagram y ve a tu perfil.",
        "Toca el menú (☰) › Centro de cuentas › Tu información y permisos › Descargar tu información.",
        "Elige tu cuenta y «Descargar en el dispositivo». Formato recomendado: JSON. Calidad multimedia: alta.",
        "Meta te avisará cuando el archivo esté listo (puede tardar desde minutos hasta días). Descárgalo: puede ser uno o varios ZIP.",
        "Vuelve aquí y elige el archivo o archivos ZIP descargados.",
    )
    SocialNetwork.FACEBOOK -> listOf(
        "Abre Facebook y toca el menú › Configuración y privacidad › Configuración.",
        "Entra en Centro de cuentas › Tu información y permisos › Descargar tu información.",
        "Elige tu perfil, el periodo y «Descargar en el dispositivo». Formato recomendado: JSON. Calidad multimedia: alta.",
        "Cuando Meta te avise, descarga el archivo (puede venir dividido en varios ZIP).",
        "Vuelve aquí y elige el archivo o archivos ZIP descargados.",
    )
    SocialNetwork.TIKTOK -> listOf(
        "Abre TikTok y ve a tu perfil › menú (☰) › Configuración y privacidad.",
        "Entra en Cuenta › Descargar tus datos.",
        "Elige el formato (JSON recomendado) y los datos que quieres, y toca «Solicitar datos».",
        "Cuando esté listo, descárgalo desde la pestaña «Descargar datos» (el enlace caduca tras unos días).",
        "Vuelve aquí y elige el archivo ZIP descargado.",
    )
    else -> emptyList()
}

@Composable
fun SocialWizardScreen(vm: AppViewModel, modifier: Modifier) {
    val n = vm.wizardNetwork ?: return
    val context = LocalContext.current
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        vm.analyzeExports(uris)
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("1. Pide tu copia oficial a ${n.label}", style = MaterialTheme.typography.titleLarge)
        steps(n).forEachIndexed { i, s -> Text("${i + 1}. $s") }
        Text("Los nombres de los menús pueden cambiar con las actualizaciones de ${n.label}.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("2. Elige el archivo descargado", style = MaterialTheme.typography.titleLarge)
        BigButton("Elegir archivo ZIP", Icons.Filled.Download) {
            pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }

        vm.exportItems.forEach { item ->
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    val a = item.analysis
                    if (a == null) {
                        Text(item.error ?: "No se pudo analizar", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(when (a.detected) {
                            null -> "No reconocemos la estructura, pero se puede guardar igualmente."
                            n -> "Exportación de ${n.label} reconocida (formato ${a.format})."
                            else -> "Atención: parece una exportación de ${a.detected!!.label}, no de ${n.label}."
                        })
                        Text("${a.totalEntries} archivos dentro · ${a.photos} fotos · ${a.videos} vídeos · ${a.dataFiles} archivos de datos")
                        if (a.messageFiles > 0) Text("Incluye ${a.messageFiles} archivo(s) de tus mensajes exportados.")
                        if (a.knownUncompressedBytes > 0) Text("Tamaño descomprimido aproximado: ${Format.size(context, a.knownUncompressedBytes)}")
                    }
                }
            }
        }

        if (vm.exportItems.any { it.error == null }) {
            Text("3. Guárdalo cifrado", style = MaterialTheme.typography.titleLarge)
            InfoCard(Icons.Filled.CheckCircle, "Qué se guarda",
                "Se guardan cifrados todos los archivos de la exportación (fotos y vídeos recuperables uno a uno) y se indexan tus publicaciones, " +
                    "comentarios y «Me gusta» para que puedas buscarlos en «Mis redes sociales». Después puedes borrar el ZIP original sin cifrar.")
            BigButton("Guardar cifrado y analizar", Icons.Filled.Lock) {
                if (!vm.pro.unlocked) vm.pro.showUnlock = true
                else vm.pro.importExports(vm.exportItems.filter { it.error == null }.map { it.uri }, vm.wizardNetwork)
            }
        }
    }
}
