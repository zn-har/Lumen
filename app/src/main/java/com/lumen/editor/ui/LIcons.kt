package com.lumen.editor.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Original line icons on a 24x24 grid (stroke 1.6). Build new ones with [lineIcon]; feature
 * files may define their own private icons with it instead of editing this file.
 */
fun lineIcon(name: String, vararg paths: String, filled: Boolean = false): ImageVector {
    val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    for (p in paths) {
        b.addPath(
            pathData = addPathNodes(p),
            fill = if (filled) SolidColor(Color.White) else null,
            stroke = if (filled) null else SolidColor(Color.White),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}

object LIcons {
    val Back = lineIcon("back", "M15 5L8 12L15 19")
    val Close = lineIcon("close", "M6 6L18 18M18 6L6 18")
    val Check = lineIcon("check", "M5 12.5L10 17.5L19 7")
    val Undo = lineIcon("undo", "M9 7L5 11L9 15", "M5 11H14A5 5 0 0 1 14 21H11")
    val Redo = lineIcon("redo", "M15 7L19 11L15 15", "M19 11H10A5 5 0 0 0 10 21H13")
    val Compare = lineIcon("compare", "M4 5H20V19H4Z", "M12 3V21", "M12 5L20 19H12", filled = false)
    val More = lineIcon("more", "M12 5h0.01M12 12h0.01M12 19h0.01")
    val MoreCircle = lineIcon("morecircle", "M12 3A9 9 0 1 1 12 21A9 9 0 1 1 12 3Z", "M8 12h0.01M12 12h0.01M16 12h0.01")
    val Share = lineIcon("share", "M17 4.5A2.5 2.5 0 1 1 17 9.5A2.5 2.5 0 1 1 17 4.5Z", "M7 9.5A2.5 2.5 0 1 1 7 14.5A2.5 2.5 0 1 1 7 9.5Z", "M17 14.5A2.5 2.5 0 1 1 17 19.5A2.5 2.5 0 1 1 17 14.5Z", "M9.2 10.8L14.8 8.2M9.2 13.2L14.8 15.8")
    val Download = lineIcon("download", "M12 4V15", "M7.5 10.5L12 15L16.5 10.5", "M5 19H19")
    val Plus = lineIcon("plus", "M12 5V19M5 12H19")
    val Minus = lineIcon("minus", "M5 12H19")
    val Eye = lineIcon("eye", "M2 12C5 6.5 19 6.5 22 12C19 17.5 5 17.5 2 12Z", "M12 9.5A2.5 2.5 0 1 1 12 14.5A2.5 2.5 0 1 1 12 9.5Z")
    val EyeOff = lineIcon("eyeoff", "M2 12C5 6.5 19 6.5 22 12C19 17.5 5 17.5 2 12Z", "M4 4L20 20")
    val Trash = lineIcon("trash", "M4 7H20", "M9 7V4H15V7", "M6 7L7 20H17L18 7")
    val Reset = lineIcon("reset", "M4 12A8 8 0 1 0 6.5 6.2", "M4 4V9H9")
    val Image = lineIcon("image", "M3 5H21V19H3Z", "M3 16L9 10L14 15L17 12L21 16")

    // bottom tool bar
    val Presets = lineIcon("presets", "M4 4H10V10H4Z", "M14 4H20V10H14Z", "M4 14H10V20H4Z", "M14 14H20V20H14Z")
    val Crop = lineIcon("crop", "M6 2V18H22", "M2 6H18V22")
    val Edit = lineIcon("edit", "M4 7H20M4 17H20", "M9 4.5V9.5M15 14.5V19.5")
    val Masking = lineIcon("masking", "M12 3A9 9 0 1 1 12 21A9 9 0 1 1 12 3Z", "M12 3V21", "M12 7H17M12 11H20M12 15H19M12 19H15")
    val Versions = lineIcon("versions", "M12 3A9 9 0 1 1 3.5 9", "M12 7V12L15 14")
    val Auto = lineIcon("auto", "M5 19L12 4L19 19", "M8 13H16")

    // edit tabs
    val Light = lineIcon("light", "M12 8A4 4 0 1 1 12 16A4 4 0 1 1 12 8Z", "M12 2V4M12 20V22M2 12H4M20 12H22M4.9 4.9L6.3 6.3M17.7 17.7L19.1 19.1M4.9 19.1L6.3 17.7M17.7 6.3L19.1 4.9")
    val Color = lineIcon("color", "M12 3A9 9 0 1 1 12 21A9 9 0 1 1 12 3Z", "M12 3V12L19.8 16.5", "M12 12L4.2 16.5")
    val Effects = lineIcon("effects", "M12 3A9 9 0 1 1 12 21A9 9 0 1 1 12 3Z", "M12 7A5 5 0 1 1 12 17A5 5 0 1 1 12 7Z")
    val Detail = lineIcon("detail", "M12 4L21 20H3Z", "M12 10L16 17H8Z")
    val Optics = lineIcon("optics", "M12 3A9 9 0 1 1 12 21A9 9 0 1 1 12 3Z", "M12 3L16 12L12 21M3 12H21")
    val Curve = lineIcon("curve", "M3 3H21V21H3Z", "M3 21C9 21 9 3 21 3")

    // masks
    val Subject = lineIcon("subject", "M12 4A3 3 0 1 1 12 10A3 3 0 1 1 12 4Z", "M6 20C6 15 8 12 12 12C16 12 18 15 18 20")
    val Sky = lineIcon("sky", "M7 17H17A4 4 0 0 0 16 9.2A5 5 0 0 0 6.5 10.5A3.3 3.3 0 0 0 7 17Z")
    val Background = lineIcon("background", "M3 5H21V19H3Z", "M12 9A2 2 0 1 1 12 13A2 2 0 1 1 12 9Z", "M8 19C8 16 9.5 14.5 12 14.5C14.5 14.5 16 16 16 19")
    val People = lineIcon("people", "M9 5A2.5 2.5 0 1 1 9 10A2.5 2.5 0 1 1 9 5Z", "M16 6A2 2 0 1 1 16 10A2 2 0 1 1 16 6Z", "M4 19C4 15 6 13 9 13C12 13 14 15 14 19", "M14 13.5C17 13 20 14.5 20 19")
    val Brush = lineIcon("brush", "M20 4L11 13", "M11 13C8 13 7 15 7 17C7 19 5 20 4 20C9 21 13 19 13 15Z")
    val Linear = lineIcon("linear", "M3 7H21", "M3 12H21", "M3 17H21", "M12 4V20")
    val Radial = lineIcon("radial", "M12 5A7 7 0 1 1 12 19A7 7 0 1 1 12 5Z", "M12 9A3 3 0 1 1 12 15A3 3 0 1 1 12 9Z")
    val ColorRange = lineIcon("colorrange", "M14 4L20 10L11 19H5V13Z", "M12 6L18 12")
    val LumRange = lineIcon("lumrange", "M12 3A9 9 0 1 1 12 21Z", "M12 3A9 9 0 1 0 12 21")
    val Invert = lineIcon("invert", "M12 3A9 9 0 1 1 12 21A9 9 0 1 1 12 3Z", "M12 3A9 9 0 0 1 12 21Z")
}

@Suppress("unused")
private val keepParser = PathParser()
