package com.kindleaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kindleaf.data.remote.CouponsRepository
import com.kindleaf.data.remote.formatDate
import com.kindleaf.models.Coupon
import com.kindleaf.models.CouponType

@Composable
fun CouponCard(
    coupon: Coupon,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = when (coupon.type) {
        CouponType.DISCOUNT -> MaterialTheme.colorScheme.primary
        CouponType.SHIPPING -> MaterialTheme.colorScheme.tertiary
        CouponType.SPECIAL -> MaterialTheme.colorScheme.secondary
    }
    val typeLabel = when (coupon.type) {
        CouponType.DISCOUNT -> "Discount"
        CouponType.SHIPPING -> "Shipping"
        CouponType.SPECIAL -> "Special"
    }
    val rules = CouponsRepository.rulesSummary(coupon)
    val validity = coupon.validUntil.takeIf { it.isNotBlank() }?.let { "Valid till ${formatDate(it)}" } ?: "No expiry date"

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentColor.copy(alpha = 0.12f))
                    .padding(horizontal = 4.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = typeLabel.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = coupon.headline,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                if (coupon.description.isNotBlank()) {
                    Text(
                        text = coupon.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    text = coupon.code,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
                if (rules.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = rules,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = validity,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(4.dp))

            IconButton(onClick = { onCopy(coupon.code) }) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "Copy code ${coupon.code}",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
