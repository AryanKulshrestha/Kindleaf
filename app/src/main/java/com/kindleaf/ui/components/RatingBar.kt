package com.kindleaf.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kindleaf.ui.theme.AccentGold

@Composable
fun RatingBar(
    rating: Float,
    modifier: Modifier = Modifier,
    starSize: Dp = 16.dp
) {
    val safe = rating.coerceIn(0f, 5f)
    val label = if (safe % 1f == 0f) "${safe.toInt()}" else "%.1f".format(safe)
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = "Rated $label out of 5" },
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        for (i in 1..5) {
            val icon = when {
                i <= safe.toInt() -> Icons.Filled.Star
                i - 0.5f <= safe -> Icons.AutoMirrored.Filled.StarHalf
                else -> Icons.Filled.StarBorder
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(starSize),
                tint = AccentGold
            )
        }
    }
}
