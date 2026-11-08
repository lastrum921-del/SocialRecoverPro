package com.socialrecover.pro

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

    override fun onResume() {
        super.onResume()
        // El usuario puede haber retirado permisos o desconectado el pendrive mientras la app estaba en segundo plano.
        vm.refresh()
    }
}
