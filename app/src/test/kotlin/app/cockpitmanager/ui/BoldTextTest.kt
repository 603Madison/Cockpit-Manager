package app.cockpitmanager.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.cockpitmanager.MainActivity
import app.cockpitmanager.R
import app.cockpitmanager.ui.theme.withFamily
import app.cockpitmanager.ui.theme.CockpitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's Bold text is meant to look exactly like the app did under
 * Android's Bold text setting (the look the owner chose), but without
 * depending on that setting.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w400dp-h600dp-xxhdpi")
class BoldTextTest {
    @get:Rule
    val compose = createComposeRule()

    /** The Lexend family 1.1.0 shipped (Light to Bold), which the owner saw with Bold text on. */
    private val lexendAsIn110 = FontFamily(
        listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { w ->
            Font(R.font.lexend, weight = w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))
        },
    )

    @Composable
    private fun Sample() {
        Column(Modifier.padding(16.dp)) {
            Text("Beacon", style = MaterialTheme.typography.titleLarge)
            Text("beacon", style = MaterialTheme.typography.headlineMedium)
            Text("Ubuntu 24.04.5 LTS · up 2 days", style = MaterialTheme.typography.bodyMedium)
            Text("Usage", style = MaterialTheme.typography.titleSmall)
            Text("4%", style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Light))
            Text("32 cores", style = MaterialTheme.typography.bodySmall)
            Text("Restart", style = MaterialTheme.typography.labelLarge)
        }
    }

    @Test
    fun matchesAndroidsBoldTextSetting() {
        compose.setContent {
            // Left: the app's own bold. Right: the regular font with Android's Bold text (+300) on.
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val withSystemBold = createFontFamilyResolver(
                context.createConfigurationContext(Configuration(configuration).apply { fontWeightAdjustment = 300 }),
            )
            Column {
                CockpitTheme(darkTheme = false, boldText = true) {
                    Column(Modifier.testTag("app")) { Sample() }
                }
                CompositionLocalProvider(LocalFontFamilyResolver provides withSystemBold) {
                    CockpitTheme(darkTheme = false, boldText = false) {
                        MaterialTheme(typography = MaterialTheme.typography.withFamily(lexendAsIn110)) {
                            Column(Modifier.testTag("system")) { Sample() }
                        }
                    }
                }
            }
        }
        val app = compose.onNodeWithTag("app").captureToImage().asAndroidBitmap()
        val system = compose.onNodeWithTag("system").captureToImage().asAndroidBitmap()
        val dir = java.io.File("build/outputs/screenshots").apply { mkdirs() }
        listOf("bold_app" to app, "bold_system" to system).forEach { (name, bitmap) ->
            java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue(app.sameAs(system), "the app's bold text draws exactly like Android's Bold text setting")
    }

    @Test
    fun activityIgnoresTheSystemSetting() {
        // A phone with Android's Bold text turned on...
        val res = ApplicationProvider.getApplicationContext<Application>().resources
        val config = Configuration(res.configuration).apply { fontWeightAdjustment = 300 }
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)

        // ...still gives the app's own look: the activity switches the system adjustment off.
        val activity = Robolectric.buildActivity(ProbeActivity::class.java).create().get()
        assertEquals(0, activity.resources.configuration.fontWeightAdjustment)
    }
}

/** MainActivity's configuration handling, without its app container. */
class ProbeActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        MainActivity.ignoreSystemBoldText(this)
    }
}
