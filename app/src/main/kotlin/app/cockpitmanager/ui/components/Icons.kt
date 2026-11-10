package app.cockpitmanager.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Line glyphs not in the core Material icon set, drawn in the same stroked style as the other apps. */
object CockpitIcons {
    private fun icon(name: String, stroked: String, filled: String? = null, width: Float = 1.8f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = PathParser().parsePathString(stroked).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            if (filled != null) addPath(pathData = PathParser().parsePathString(filled).toNodes(), fill = SolidColor(Color.Black))
        }.build()

    /** A dial, for the Overview. */
    val Gauge: ImageVector by lazy {
        icon("gauge", "M4.6,17.5 a8.2,8.2 0 1,1 14.8,0 M12,13.5 l3.6,-3.6", filled = "M10.4,13.5 a1.6,1.6 0 1,0 3.2,0 a1.6,1.6 0 1,0 -3.2,0z")
    }

    /** Stacked layers, for services. */
    val Services: ImageVector by lazy { icon("services", "M12,3.5 l8.5,4.5 l-8.5,4.5 l-8.5,-4.5 z M3.5,12.2 l8.5,4.5 l8.5,-4.5 M3.5,16.2 l8.5,4.5 l8.5,-4.5") }

    /** Lines of text, for logs. */
    val Logs: ImageVector by lazy { icon("logs", "M5,5 h14 a1,1 0 0,1 1,1 v12 a1,1 0 0,1 -1,1 h-14 a1,1 0 0,1 -1,-1 v-12 a1,1 0 0,1 1,-1 z M7.5,9 h9 M7.5,12 h9 M7.5,15 h5") }

    /** A prompt, for the terminal. */
    val Terminal: ImageVector by lazy { icon("terminal", "M4.5,4.5 h15 a1.5,1.5 0 0,1 1.5,1.5 v12 a1.5,1.5 0 0,1 -1.5,1.5 h-15 a1.5,1.5 0 0,1 -1.5,-1.5 v-12 a1.5,1.5 0 0,1 1.5,-1.5 z M7,9.5 l3,2.5 l-3,2.5 M12.5,15 h4.5") }

    /** Two racked machines, for servers. */
    val Server: ImageVector by lazy {
        icon(
            "server",
            "M5,4 h14 a1,1 0 0,1 1,1 v4 a1,1 0 0,1 -1,1 h-14 a1,1 0 0,1 -1,-1 v-4 a1,1 0 0,1 1,-1 z M5,14 h14 a1,1 0 0,1 1,1 v4 a1,1 0 0,1 -1,1 h-14 a1,1 0 0,1 -1,-1 v-4 a1,1 0 0,1 1,-1 z",
            filled = "M6.6,7 a1,1 0 1,0 2,0 a1,1 0 1,0 -2,0z M6.6,17 a1,1 0 1,0 2,0 a1,1 0 1,0 -2,0z",
        )
    }

    val Power: ImageVector by lazy { icon("power", "M12,3.5 v8 M7.2,6.6 a7.3,7.3 0 1,0 9.6,0") }

    val Stop: ImageVector by lazy { icon("stop", "M7,7 h10 v10 h-10 z") }

    /** A padlock with a check, for a trusted certificate. */
    val Shield: ImageVector by lazy { icon("shield", "M12,3 l7,3 v5.5 c0,4.3 -3,7.7 -7,9.5 c-4,-1.8 -7,-5.2 -7,-9.5 v-5.5 z M9,12 l2.2,2.2 l4,-4.2") }

    val Key: ImageVector by lazy { icon("key", "M14.5,9.5 m-4.5,0 a4.5,4.5 0 1,0 9,0 a4.5,4.5 0 1,0 -9,0 M10.8,12.7 l-7.3,7.3 M6,17.5 l2.2,2.2 M8,15.5 l1.8,1.8") }

    val Cpu: ImageVector by lazy {
        icon("cpu", "M7,7 h10 v10 h-10 z M10,10 h4 v4 h-4 z M9,3.5 v3.5 M15,3.5 v3.5 M9,17 v3.5 M15,17 v3.5 M3.5,9 h3.5 M3.5,15 h3.5 M17,9 h3.5 M17,15 h3.5")
    }

    val Disk: ImageVector by lazy {
        icon("disk", "M12,5 c4.4,0 8,1.3 8,3 v8 c0,1.7 -3.6,3 -8,3 c-4.4,0 -8,-1.3 -8,-3 v-8 c0,-1.7 3.6,-3 8,-3 z M4,8 c0,1.7 3.6,3 8,3 c4.4,0 8,-1.3 8,-3 M4,12 c0,1.7 3.6,3 8,3 c4.4,0 8,-1.3 8,-3")
    }

    val Network: ImageVector by lazy { icon("network", "M8,4 v14 M5,15 l3,3 l3,-3 M16,20 v-14 M13,9 l3,-3 l3,3") }

    val Live: ImageVector by lazy {
        icon("live", "M6.3,6.3 a8,8 0 0,0 0,11.4 M17.7,6.3 a8,8 0 0,1 0,11.4 M9.2,9.2 a4,4 0 0,0 0,5.6 M14.8,9.2 a4,4 0 0,1 0,5.6", filled = "M10.6,12 a1.4,1.4 0 1,0 2.8,0 a1.4,1.4 0 1,0 -2.8,0z")
    }

    val Logout: ImageVector by lazy { icon("logout", "M10,4.5 h-4 a1.5,1.5 0 0,0 -1.5,1.5 v12 a1.5,1.5 0 0,0 1.5,1.5 h4 M14.5,8 l4,4 l-4,4 M18.5,12 h-9.5") }

    val Swap: ImageVector by lazy { icon("swap", "M7,4 v15 M4,16 l3,3 l3,-3 M17,20 v-15 M14,8 l3,-3 l3,3") }
}
