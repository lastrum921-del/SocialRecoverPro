package com.socialrecover.pro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import com.socialrecover.pro.ui.App
import com.socialrecover.pro.ui.AppViewModel
import com.socialrecover.pro.ui.theme.SrpTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Al tocar una notificación del detector, abrir directamente «Estado de protección».
        if (savedInstanceState == null && intent?.getStringExtra("open") == "status" && vm.onboardingDone) vm.goPro(com.socialrecover.pro.ui.Screen.STATUS)
        if (savedInstanceState == null) handleShared(intent)
        setContent {
            SrpTheme {
                // Evita que la pantalla se apague durante una copia o restauración larga.
                val busy = vm.busy != null
                LaunchedEffect(busy) {
                    if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                App(vm, onExit = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
    }

    /** Un ZIP de exportación abierto o compartido con la app se importa directamente en «Mis redes sociales». */
    @Suppress("DEPRECATION")
    private fun handleShared(i: Intent?) {
        if (i == null) return
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_VIEW -> listOfNotNull(i.data)
            Intent.ACTION_SEND -> listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        if (!vm.onboardingDone) {
            vm.message = com.socialrecover.pro.ui.UiMessage("Primero configura la app", "Completa el asistente inicial y vuelve a abrir el archivo con SocialRecover Pro.")
            return
        }
        vm.goPro(com.socialrecover.pro.ui.Screen.SOCIAL_HUB)
        vm.pro.receiveShared(uris)
    }

    override fun onResume() {
        super.onResume()
        // El usuario puede haber retirado permisos o desconectado el pendrive mientras la app estaba en segundo plano.
        vm.refresh()
    }
}
