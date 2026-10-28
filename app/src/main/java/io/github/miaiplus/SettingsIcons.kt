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
    val Search = outline("Search") {
        moveTo(17f, 10f); arcTo(7f, 7f, 0f, true, true, 3f, 10f)
        arcTo(7f, 7f, 0f, true, true, 17f, 10f)
        moveTo(15f, 15f); lineTo(21f, 21f)
    }
    val Share = outline("Share") {
        moveTo(12f, 15f); lineTo(12f, 3f)
        moveTo(7f, 8f); lineTo(12f, 3f); lineTo(17f, 8f)
        moveTo(6f, 11f); lineTo(4f, 11f); lineTo(4f, 21f)
        lineTo(20f, 21f); lineTo(20f, 11f); lineTo(18f, 11f)
    }
    val Save = outline("Save") {
        moveTo(12f, 3f); lineTo(12f, 15f)
        moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
        moveTo(4f, 13f); lineTo(4f, 21f); lineTo(20f, 21f); lineTo(20f, 13f)
    }
    val More = outline("More") {
        moveTo(4f, 12f); lineTo(5f, 12f)
        moveTo(11.5f, 12f); lineTo(12.5f, 12f)
        moveTo(19f, 12f); lineTo(20f, 12f)
    }
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
    val Plugins = outline("Plugins") {
        moveTo(9f, 5f); lineTo(9f, 4f); quadTo(9f, 2f, 11f, 2f)
        quadTo(13f, 2f, 13f, 4f); lineTo(13f, 5f); lineTo(18f, 5f)
        quadTo(19f, 5f, 19f, 6f); lineTo(19f, 10f); lineTo(20f, 10f)
        quadTo(22f, 10f, 22f, 12f); quadTo(22f, 14f, 20f, 14f)
        lineTo(19f, 14f); lineTo(19f, 20f); quadTo(19f, 21f, 18f, 21f)
        lineTo(13f, 21f); lineTo(13f, 20f); quadTo(13f, 18f, 11f, 18f)
        quadTo(9f, 18f, 9f, 20f); lineTo(9f, 21f); lineTo(4f, 21f)
        quadTo(3f, 21f, 3f, 20f); lineTo(3f, 14f); lineTo(4f, 14f)
        quadTo(6f, 14f, 6f, 12f); quadTo(6f, 10f, 4f, 10f)
        lineTo(3f, 10f); lineTo(3f, 6f); quadTo(3f, 5f, 4f, 5f); close()
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
