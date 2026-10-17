package com.kindleaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kindleaf.models.OrderStatus
import com.kindleaf.ui.theme.AccentGold
import com.kindleaf.ui.theme.PrimaryGreen
import com.kindleaf.ui.theme.Success

/**
 * Vertical order progress timeline.
 *
 * @param timestamps optional formatted time per step (same index as [statuses]); null/blank hides the line.
 */
@Composable
fun OrderTimeline(
    statuses: List<OrderStatus>,
    modifier: Modifier = Modifier,
    timestamps: List<String?> = emptyList(),
) {
    if (statuses.isEmpty()) return

    // Single terminal/special states (awaiting payment, cancelled, returned) get a banner-style row.
    if (statuses.size == 1) {
        val status = statuses.first()
        val (icon, tint) = specialStyle(status.title)
        val time = timestamps.firstOrNull()?.takeIf { it.isNotBlank() }
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = listOfNotNull(status.title, status.subtitle, time).joinToString(". ")
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    status.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    status.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (time != null) {
                    Text(
                        time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    Column(modifier = modifier) {
        statuses.forEachIndexed { index, status ->
            val time = timestamps.getOrNull(index)?.takeIf { it.isNotBlank() }
            val stateText = when {
                status.isCurrent -> "current step"
                status.isCompleted -> "completed"
                else -> "pending"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clearAndSetSemantics {
                        contentDescription = listOfNotNull(
                            "Step ${index + 1} of ${statuses.size}: ${status.title}",
                            stateText,
                            status.subtitle.takeIf { it != "Completed" && it != "Current" && it != "Pending" },
                            time,
                        ).joinToString(", ")
                    },
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(32.dp)
                        .fillMaxHeight()
                ) {
                    val dotColor = when {
                        status.isCurrent -> PrimaryGreen
                        status.isCompleted -> Success
                        else -> MaterialTheme.colorScheme.outline
                    }
                    Box(
                        modifier = Modifier
                            .size(if (status.isCurrent) 20.dp else 18.dp)
                            .clip(CircleShape)
                            .background(
                                if (status.isCompleted || status.isCurrent) dotColor
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (status.isCompleted) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        } else {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                        }
                    }
                    if (index < statuses.lastIndex) {
                        val nextDone = statuses[index + 1].isCompleted
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .weight(1f)
                                .defaultMinSize(minHeight = 24.dp)
                                .background(
                                    if (nextDone) Success.copy(alpha = 0.6f)
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.padding(bottom = if (index < statuses.lastIndex) 16.dp else 0.dp)) {
                    Text(
                        text = status.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (status.isCompleted || status.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (status.isCompleted || status.isCurrent) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val subtitle = if (time != null && (status.subtitle == "Completed" || status.subtitle == "Current")) {
                        time
                    } else {
                        listOfNotNull(status.subtitle, time).joinToString(" · ")
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun specialStyle(title: String): Pair<ImageVector, Color> = when {
    title.contains("cancel", ignoreCase = true) -> Icons.Outlined.Cancel to MaterialTheme.colorScheme.error
    title.contains("return", ignoreCase = true) -> Icons.AutoMirrored.Outlined.KeyboardReturn to AccentGold
    title.contains("payment", ignoreCase = true) -> Icons.Outlined.HourglassTop to AccentGold
    else -> Icons.Filled.Check to PrimaryGreen
}
