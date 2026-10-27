package com.lumen.editor.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumen.editor.ui.theme.LColors
import com.lumen.editor.ui.theme.LType

/** Bottom bar of a full-screen sub-mode (Crop, Curve, Masking…): ✕ · title · ✓. */
@Composable
fun ModeBar(title: String, onCancel: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(56.dp).background(LColors.Controls),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBtn(LIcons.Close, "Cancel", onClick = onCancel)
        BasicText(
            title, style = LType.Title.copy(fontSize = 17.sp, textAlign = TextAlign.Center),
            modifier = Modifier.weight(1f),
        )
        IconBtn(LIcons.Check, "Done", onClick = onDone)
    }
}

/** Outlined pill with icon + text (e.g. "Curve" at the top-right of the Light panel). */
@Composable
fun PillButton(icon: ImageVector?, text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF111111))
            .border(1.dp, LColors.Divider, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = LColors.Text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        BasicText(text, style = LType.Label)
    }
}

/**
 * Icon-above-label item used by the tool strip and category rows.
 * [selectedColor] fills the rounded background when selected (blue for tools, grey for categories).
 */
@Composable
fun StripItem(
    icon: ImageVector, label: String?, selected: Boolean, selectedColor: Color,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) selectedColor else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = if (label == null) 12.dp else 8.dp)
            .widthIn(min = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val tint = if (selected) Color.White else LColors.TextDim
        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
        if (label != null) {
            Spacer(Modifier.height(6.dp))
            BasicText(label, style = LType.Tool.copy(color = tint))
        }
    }
}

/** Horizontally scrolling strip of [StripItem]s with even spacing. */
@Composable
fun Strip(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
