package io.github.miaiplus

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small outline icons matching the weight of Miuix's built-in navigation icons. */
internal object SettingsIcons {
    val Home = outline("Home") {
        moveTo(3f, 10f); lineTo(12f, 3f); lineTo(21f, 10f)
        moveTo(5f, 9f); lineTo(5f, 20f); lineTo(10f, 20f); lineTo(10f, 14f)
        lineTo(14f, 14f); lineTo(14f, 20f); lineTo(19f, 20f); lineTo(19f, 9f)
    }
    val Model = outline("Model") {
        moveTo(5f, 4f); lineTo(19f, 4f); quadTo(21f, 4f, 21f, 6f)
        lineTo(21f, 15f); quadTo(21f, 17f, 19f, 17f)
        lineTo(10f, 17f); lineTo(5f, 21f); lineTo(5f, 17f)
        quadTo(3f, 17f, 3f, 15f); lineTo(3f, 6f); quadTo(3f, 4f, 5f, 4f)
        moveTo(7f, 9f); lineTo(17f, 9f); moveTo(7f, 13f); lineTo(13f, 13f)
    }
    val Control = outline("Control") {
        moveTo(15f, 21f); lineTo(6f, 21f); quadTo(4f, 21f, 4f, 19f)
        lineTo(4f, 5f); quadTo(4f, 3f, 6f, 3f); lineTo(15f, 3f)
        quadTo(17f, 3f, 17f, 5f); lineTo(17f, 9f)
        moveTo(9f, 18f); lineTo(11f, 18f)
        moveTo(13f, 11f); lineTo(21f, 15f); lineTo(17.5f, 16f)
        lineTo(16f, 20f); close()
    }
    val Module = outline("Module") {
        moveTo(12f, 2f); lineTo(21f, 6f); lineTo(21f, 12f)
        quadTo(21f, 18f, 12f, 22f); quadTo(3f, 18f, 3f, 12f)
        lineTo(3f, 6f); close()
    }

    private fun outline(name: String, draw: PathBuilder.() -> Unit) = ImageVector.Builder(
        name, 24.dp, 24.dp, 24f, 24f,
    ).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
    }.build()
}
