package app.cockpitmanager

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.cockpitmanager.ui.AppRoot
import app.cockpitmanager.ui.MainViewModel
import app.cockpitmanager.ui.theme.CockpitTheme
import app.cockpitmanager.ui.theme.isAppInDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as CockpitApp).container
        setContent {
            val vm: MainViewModel = viewModel(factory = MainViewModel.factory(container))
            val settings by vm.settings.collectAsStateWithLifecycle()
            val dark = isAppInDarkTheme(settings.themeMode)

            // Keep status/navigation bar icons legible when the in-app theme differs from the system's.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            CockpitTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                AppRoot(vm)
            }
        }
    }
}
