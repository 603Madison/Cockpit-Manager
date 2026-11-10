package app.cockpitmanager

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ContextThemeWrapper
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
    /**
     * The app draws its own bold Lexend (Settings → Appearance → Bold text),
     * so Android's Bold text setting, which adds 300 to every font weight, is
     * switched off here: otherwise the two would stack, and the look would
     * depend on a system setting. Applies to this activity's dialogs and menus too.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        ignoreSystemBoldText(this)
    }

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

            CockpitTheme(darkTheme = dark, dynamicColor = settings.dynamicColor, boldText = settings.boldText) {
                AppRoot(vm)
            }
        }
    }

    companion object {
        /** Switches Android's font weight adjustment (Bold text) off; call from attachBaseContext. */
        fun ignoreSystemBoldText(activity: ContextThemeWrapper) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity.applyOverrideConfiguration(Configuration().apply { fontWeightAdjustment = 0 })
            }
        }
    }
}
