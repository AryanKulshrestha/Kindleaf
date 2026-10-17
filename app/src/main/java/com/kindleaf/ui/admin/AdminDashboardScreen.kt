package com.kindleaf.ui.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AssignmentReturn
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.CurrencyRupee
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kindleaf.data.remote.AdminRepository
import com.kindleaf.data.remote.OrdersRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.AdminStats
import com.kindleaf.models.DailyStat
import com.kindleaf.models.Order
import com.kindleaf.models.OrderStatusFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val PERIODS = listOf(7, 30, 90)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    onOpenOrders: () -> Unit,
    onOpenOrder: (String) -> Unit,
    onOpenProduct: (String) -> Unit,
    onOpenReturns: () -> Unit,
    onOpenSupport: () -> Unit,
    onLogout: () -> Unit,
) {
    var days by rememberSaveable { mutableIntStateOf(30) }
    var stats by remember { mutableStateOf<AdminStats?>(null) }
    var recent by remember { mutableStateOf<List<Order>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var confirmLogout by remember { mutableStateOf(false) }

    LaunchedEffect(days, reloadKey) {
        if (stats == null) loading = true
        error = null
        attempt {
            coroutineScope {
                val s = async { AdminRepository.stats(days) }
                val r = async { OrdersRepository.fetchAll(limit = 8) }
                s.await() to r.await()
            }
        }.onSuccess { (s, r) ->
            stats = s
            recent = r
        }.onFailure { error = it.userMessage("Couldn't load the dashboard.") }
        loading = false
        refreshing = false
    }

    Scaffold(
        topBar = {
            AdminTopBar(
                title = "Dashboard",
                subtitle = "Kindleaf store overview",
                actions = {
                    IconButton(onClick = { confirmLogout = true }) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "Log out")
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                reloadKey++
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val current = stats
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    loading && current == null -> item { AdminLoading(Modifier.fillParentMaxSize()) }
                    current == null -> item {
                        AdminErrorState(
                            message = error ?: "Couldn't load the dashboard.",
                            onRetry = { loading = true; reloadKey++ },
                            modifier = Modifier.fillParentMaxSize()
                        )
                    }
                    else -> {
                        error?.let { msg -> item { AdminInlineError(msg) } }
                        item {
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                PERIODS.forEachIndexed { index, period ->
                                    SegmentedButton(
                                        selected = days == period,
                                        onClick = {
                                            if (days != period) {
                                                refreshing = true
                                                days = period
                                            }
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(index, PERIODS.size)
                                    ) { Text("$period days") }
                                }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                KpiTile("Revenue (paid)", rupees(current.revenue), Icons.Outlined.CurrencyRupee, Modifier.weight(1f))
                                KpiTile("Orders", "${current.orders}", Icons.Outlined.ShoppingBag, Modifier.weight(1f))
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                KpiTile("Avg order value", rupees(current.avgOrderValue), Icons.Outlined.TrendingUp, Modifier.weight(1f))
                                KpiTile(
                                    "New customers",
                                    "${current.newCustomers}",
                                    Icons.Outlined.PersonAdd,
                                    Modifier.weight(1f),
                                    footnote = "${current.customers} total"
                                )
                            }
                        }
                        item { AdminSectionTitle("Needs attention") }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ActionTile("To fulfil", current.toFulfil, Icons.Outlined.Inventory2, Modifier.weight(1f), onOpenOrders)
                                ActionTile("In transit", current.inTransit, Icons.Outlined.LocalShipping, Modifier.weight(1f), onOpenOrders)
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ActionTile("Awaiting payment", current.awaitingPayment, Icons.Outlined.HourglassEmpty, Modifier.weight(1f), onOpenOrders)
                                ActionTile("Refunds pending", current.refundsPending, Icons.Outlined.Replay, Modifier.weight(1f), onOpenOrders)
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ActionTile("Returns pending", current.returnsPending, Icons.AutoMirrored.Outlined.AssignmentReturn, Modifier.weight(1f), onOpenReturns)
                                ActionTile("Open tickets", current.openTickets, Icons.Outlined.SupportAgent, Modifier.weight(1f), onOpenSupport)
                            }
                        }
                        item { RevenueChartCard(current.daily, current.days) }

                        item { AdminSectionTitle("Top products") }
                        if (current.topProducts.isEmpty()) {
                            item { MutedText("No sales in the last ${current.days} days") }
                        } else {
                            item {
                                Card(shape = RoundedCornerShape(14.dp)) {
                                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                                        current.topProducts.forEachIndexed { index, product ->
                                            if (index > 0) HorizontalDivider()
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "${index + 1}.",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.width(28.dp)
                                                )
                                                Column(Modifier.weight(1f)) {
                                                    Text(product.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(
                                                        "${product.quantity} sold",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Text(rupees(product.revenue), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item { AdminSectionTitle("Low stock") }
                        if (current.lowStock.isEmpty()) {
                            item { MutedText("All active products are well stocked") }
                        } else {
                            items(current.lowStock, key = { "low-${it.id}" }) { item ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenProduct(item.id) },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            item.name,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (item.stockQty <= 0) {
                                            AdminBadge("Out of stock", AdminTone.Error)
                                        } else {
                                            AdminBadge("${item.stockQty} left", AdminTone.Warning)
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AdminSectionTitle("Recent orders")
                                TextButton(onClick = onOpenOrders) { Text("View all") }
                            }
                        }
                        if (recent.isEmpty()) {
                            item { MutedText("No orders yet") }
                        } else {
                            items(recent, key = { "recent-${it.id}" }) { order ->
                                RecentOrderRow(order = order, onClick = { onOpenOrder(order.id) })
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmLogout) {
        AdminConfirmDialog(
            title = "Log out?",
            message = "You will need to sign in again to manage the store.",
            confirmLabel = "Log out",
            onConfirm = {
                confirmLogout = false
                onLogout()
            },
            onDismiss = { confirmLogout = false }
        )
    }
}

@Composable
private fun MutedText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun KpiTile(
    title: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    footnote: String? = null,
) {
    Card(modifier = modifier, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (footnote != null) {
                Text(footnote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ActionTile(
    title: String,
    count: Int,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val highlight = count > 0
    Card(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "Open $title", onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = if (highlight) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text("$count", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val chartLabelFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM")

/** Fills missing days with zero so bars line up with the calendar. */
private fun fillDays(daily: List<DailyStat>, days: Int): List<Pair<LocalDate, Int>> {
    val byDay = daily.mapNotNull { stat ->
        runCatching { LocalDate.parse(stat.day.take(10)) }.getOrNull()?.let { it to stat.revenue }
    }.toMap()
    val lastServerDay = byDay.keys.maxOrNull()
    val today = LocalDate.now()
    val end = if (lastServerDay != null && lastServerDay.isAfter(today)) lastServerDay else today
    val start = end.minusDays((days - 1).toLong().coerceAtLeast(0))
    return (0 until days).map { offset ->
        val day = start.plusDays(offset.toLong())
        day to (byDay[day] ?: 0)
    }
}

@Composable
private fun RevenueChartCard(daily: List<DailyStat>, days: Int) {
    val points = remember(daily, days) { fillDays(daily, days.coerceIn(1, 366)) }
    val max = points.maxOfOrNull { it.second } ?: 0
    val total = points.sumOf { it.second }
    val barColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outline

    Card(shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Daily revenue", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    "Last $days days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            if (max <= 0) {
                Text(
                    "No paid revenue in this period",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                val peak = points.maxByOrNull { it.second }
                Text(
                    "Peak ${rupees(max)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .semantics {
                            contentDescription = "Daily revenue bar chart. ${rupees(total)} over $days days" +
                                (peak?.let { ", highest ${rupees(it.second)} on ${it.first.format(chartLabelFormat)}" } ?: "")
                        }
                ) {
                    val count = points.size
                    val slot = size.width / count
                    val gap = (slot * 0.25f).coerceAtMost(6f)
                    val barWidth = (slot - gap).coerceAtLeast(1f)
                    points.forEachIndexed { index, (_, value) ->
                        if (value > 0) {
                            val h = (value.toFloat() / max) * size.height
                            drawRect(
                                color = barColor,
                                topLeft = Offset(index * slot + gap / 2, size.height - h),
                                size = Size(barWidth, h)
                            )
                        }
                    }
                    drawLine(
                        color = axisColor,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 2f
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        points.first().first.format(chartLabelFormat),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        points.last().first.format(chartLabelFormat),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Total ${rupees(total)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
internal fun RecentOrderRow(order: Order, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("#${order.shortId}", fontWeight = FontWeight.SemiBold)
                Text(
                    listOf(order.deliveryAddress.name, order.placedDate).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                AdminBadge(OrderStatusFlow.label(order.status), orderStatusTone(order.status))
            }
            Text(rupees(order.totalPrice), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}
