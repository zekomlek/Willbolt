package app.willbolt.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Willbolt's own line icons, drawn on a 24-unit grid with one stroke width so they match each other (the
 * Material set in the app's dependencies has no open padlock, no wall and no globe). Tinted like any icon.
 */
object LineIcons {
    private const val STROKE = 1.8f

    private fun icon(name: String, vararg strokes: String, dots: List<String> = emptyList()): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in strokes) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        for (d in dots) b.addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black))
        return b.build()
    }

    private fun rr(x0: Float, y0: Float, x1: Float, y1: Float, r: Float) =
        "M${x0 + r},$y0 H${x1 - r} A$r,$r 0 0 1 $x1,${y0 + r} V${y1 - r} A$r,$r 0 0 1 ${x1 - r},$y1 " +
            "H${x0 + r} A$r,$r 0 0 1 $x0,${y1 - r} V${y0 + r} A$r,$r 0 0 1 ${x0 + r},$y0 Z"

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},$cy A$r,$r 0 1 1 ${cx + r},$cy A$r,$r 0 1 1 ${cx - r},$cy Z"

    private val lockBody = rr(5f, 10.5f, 19f, 20.5f, 2.5f)

    val Lock by lazy { icon("Lock", lockBody, "M8,10.5 V8 A4,4 0 0 1 16,8 V10.5", "M12,14.5 V16.5") }
    val LockOpen by lazy { icon("LockOpen", lockBody, "M8,10.5 V8 A4,4 0 0 1 15.6,6.3", "M12,14.5 V16.5") }
    val Home by lazy {
        icon("Home", "M4.5,10 L12,4 L19.5,10 V19 A1,1 0 0 1 18.5,20 H5.5 A1,1 0 0 1 4.5,19 Z", "M10,20 V14.5 H14 V20")
    }
    val Wall by lazy {
        icon(
            "Wall",
            rr(3.5f, 5.5f, 11f, 10.5f, 1f), rr(13f, 5.5f, 20.5f, 10.5f, 1f),
            rr(3.5f, 13.5f, 7f, 18.5f, 1f), rr(9f, 13.5f, 15f, 18.5f, 1f), rr(17f, 13.5f, 20.5f, 18.5f, 1f),
        )
    }
    val Eye by lazy {
        icon(
            "Eye",
            "M2.5,12 C4.8,7.8 8.2,5.5 12,5.5 C15.8,5.5 19.2,7.8 21.5,12 C19.2,16.2 15.8,18.5 12,18.5 C8.2,18.5 4.8,16.2 2.5,12 Z",
            circle(12f, 12f, 3f),
        )
    }
    val Pulse by lazy { icon("Pulse", "M3,12.5 H7 L9.5,6 L14.5,18.5 L17,12.5 H21") }
    val Globe by lazy {
        icon(
            "Globe", circle(12f, 12f, 8.5f), "M3.5,12 H20.5",
            "M12,3.5 C9.3,6.2 8.3,9 8.3,12 C8.3,15 9.3,17.8 12,20.5",
            "M12,3.5 C14.7,6.2 15.7,9 15.7,12 C15.7,15 14.7,17.8 12,20.5",
        )
    }
    val Timer by lazy { icon("Timer", circle(12f, 13.5f, 7.5f), "M12,13.5 V9.5", "M9.5,3 H14.5", "M12,3 V6") }
    val Hourglass by lazy {
        icon(
            "Hourglass", "M7,3.5 H17", "M7,20.5 H17",
            "M8,3.5 C8,8.5 12,9.5 12,12 C12,14.5 8,15.5 8,20.5",
            "M16,3.5 C16,8.5 12,9.5 12,12 C12,14.5 16,15.5 16,20.5",
        )
    }
    val Clock by lazy { icon("Clock", circle(12f, 12f, 8.5f), "M12,7.5 V12 L15,14") }
    val Plus by lazy { icon("Plus", "M12,5.5 V18.5", "M5.5,12 H18.5") }
    val Minus by lazy { icon("Minus", "M5.5,12 H18.5") }
    val Check by lazy { icon("Check", "M5,12.5 L10,17.5 L19,7") }
    val Alert by lazy { icon("Alert", "M12,4 L21,19.5 H3 Z", "M12,10 V14", dots = listOf(circle(12f, 16.9f, 1.05f))) }
    val Info by lazy { icon("Info", circle(12f, 12f, 8.5f), "M12,11 V16.5", dots = listOf(circle(12f, 7.9f, 1.05f))) }
    val Chevron by lazy { icon("Chevron", "M9.5,6 L15.5,12 L9.5,18") }
    val ChevronDown by lazy { icon("ChevronDown", "M6,9.5 L12,15.5 L18,9.5") }
    val Back by lazy { icon("Back", "M19,12 H5", "M11,6 L5,12 L11,18") }
    val Close by lazy { icon("Close", "M6.5,6.5 L17.5,17.5", "M17.5,6.5 L6.5,17.5") }
    val Trash by lazy {
        icon(
            "Trash", "M4.5,6.5 H19.5", "M9.5,6.5 V4.5 H14.5 V6.5",
            "M6.5,6.5 L7.4,19.6 A1,1 0 0 0 8.4,20.5 H15.6 A1,1 0 0 0 16.6,19.6 L17.5,6.5",
        )
    }

    /** "Settings": two sliders. */
    val Tune by lazy {
        icon("Tune", "M4,7.5 H13", "M19,7.5 H20", circle(16f, 7.5f, 2.5f), "M4,16.5 H5", "M11,16.5 H20", circle(8f, 16.5f, 2.5f))
    }
    /** "Feedback": a speech bubble. */
    val Chat by lazy {
        icon("Chat", "M5,5.5 H19 A1.5,1.5 0 0 1 20.5,7 V15 A1.5,1.5 0 0 1 19,16.5 H11 L7,20 V16.5 H5 A1.5,1.5 0 0 1 3.5,15 V7 A1.5,1.5 0 0 1 5,5.5 Z")
    }
    val Shield by lazy { icon("Shield", "M12,3.5 L19,6.5 V11.5 C19,15.8 16,19 12,20.5 C8,19 5,15.8 5,11.5 V6.5 Z") }
    val Picture by lazy {
        icon("Picture", rr(3.5f, 5f, 20.5f, 19f, 2f), "M6.5,16 L10.5,11.5 L13.5,14.5 L15.5,12.5 L18,15.5", circle(15.5f, 8.8f, 1.4f))
    }
    val Search by lazy { icon("Search", circle(10.5f, 10.5f, 6f), "M15,15 L20,20") }
    /** DNS: a funnel that lets some things through. */
    val Funnel by lazy { icon("Funnel", "M4.5,5 H19.5 L14,12 V18 L10,20 V12 Z") }
    val Coffee by lazy {
        icon(
            "Coffee",
            "M5,9.5 H16 V13.5 A4.5,4.5 0 0 1 11.5,18 H9.5 A4.5,4.5 0 0 1 5,13.5 Z",
            "M16,10.5 H17 A2.5,2.5 0 0 1 17,15.5 H15.6",
            "M4,20.5 H17",
            "M8.5,3.5 C7.8,4.5 9.2,5.5 8.5,6.5", "M12.5,3.5 C11.8,4.5 13.2,5.5 12.5,6.5",
        )
    }
    val Key by lazy { icon("Key", circle(7.5f, 12f, 3.5f), "M11,12 H20.5", "M17.5,12 V15", "M20.5,12 V14.5") }
    val EyeOff by lazy {
        icon(
            "EyeOff",
            "M2.5,12 C4.8,7.8 8.2,5.5 12,5.5 C15.8,5.5 19.2,7.8 21.5,12 C19.2,16.2 15.8,18.5 12,18.5 C8.2,18.5 4.8,16.2 2.5,12 Z",
            circle(12f, 12f, 3f),
            "M4,4 L20,20",
        )
    }
    val Grid by lazy {
        icon("Grid", rr(4f, 4f, 10.5f, 10.5f, 1.5f), rr(13.5f, 4f, 20f, 10.5f, 1.5f), rr(4f, 13.5f, 10.5f, 20f, 1.5f), rr(13.5f, 13.5f, 20f, 20f, 1.5f))
    }
}
