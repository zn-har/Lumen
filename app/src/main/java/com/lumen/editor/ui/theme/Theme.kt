package com.lumen.editor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/** Dark editor palette (values chosen to match the reference app's look: black loupe, #232323 controls). */
object LColors {
    val Loupe = Color(0xFF000000)
    // panels float over the photo, so they are slightly see-through like the reference app
    val Controls = Color(0xB81C1C1C)
    val ControlsSolid = Color(0xFF1E1E1E)
    val Panel = Color(0xFF1B1B1B)
    val PanelRaised = Color(0xFF2C2C2C)
    val Divider = Color(0xFF3A3A3A)
    val Text = Color(0xFFE6E6E6)
    val TextDim = Color(0xFF9A9A9A)
    val TextFaint = Color(0xFF6B6B6B)
    val Accent = Color(0xFF378EF0)
    val AccentDim = Color(0xFF1D5FA8)
    val Track = Color(0xFF5A5A5A)
    val Thumb = Color(0xFFF2F2F2)
    val Danger = Color(0xFFE34850)
    val Selected = Color(0xFF333333)
    val MaskOverlay = Color(0xFFE5302A)
}

object LType {
    val Label = TextStyle(fontSize = 15.sp, color = LColors.Text)
    val Value = TextStyle(fontSize = 15.sp, color = LColors.Text)
    val Tool = TextStyle(fontSize = 12.sp, color = LColors.TextDim)
    val Header = TextStyle(fontSize = 12.sp, color = LColors.TextDim, letterSpacing = 0.8.sp)
    val Title = TextStyle(fontSize = 16.sp, color = LColors.Text)
}

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = LColors.Accent,
            background = LColors.Loupe,
            surface = LColors.ControlsSolid,
            onSurface = LColors.Text,
            onBackground = LColors.Text,
        ),
        content = content,
    )
}
