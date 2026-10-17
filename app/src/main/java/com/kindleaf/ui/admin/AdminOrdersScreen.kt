package com.kindleaf.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kindleaf.data.remote.OrdersRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.Order
import com.kindleaf.models.OrderStatusFlow
import kotlinx.coroutines.launch

private const val FILTER_ALL = "all"
private const val FILTER_REFUNDS = "refunds"

private suspend fun loadOrdersPage(filter: String, offset: Int): List<Order> = when (filter) {
    FILTER_REFUNDS -> if (offset == 0) OrdersRepository.fetchWithPendingRefunds() else emptyList()
    FILTER_ALL -> OrdersRepository.fetchAll(status = null, limit = OrdersRepository.PAGE_SIZE, offset = offset)
    else -> OrdersRepository.fetchAll(status = filter, limit = OrdersRepository.PAGE_SIZE, offset = offset)
}

private fun Order.matches(query: String): Boolean {
    val q = query.trim().removePrefix("#").lowercase()
    if (q.isEmpty()) return true
    val digits = q.filter { it.isDigit() }
    return id.lowercase().startsWith(q) ||
        deliveryAddress.name.lowercase().contains(q) ||
        (digits.length >= 3 && deliveryAddress.phone.filter { it.isDigit() }.contains(digits))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminOrdersScreen(
    onOpenOrder: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var orders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var moreError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(filter, reloadKey) {
        if (!refreshing) loading = true
        error = null
        moreError = null
        attempt { loadOrdersPage(filter, 0) }
            .onSuccess {
                orders = it
                endReached = filter == FILTER_REFUNDS || it.size < OrdersRepository.PAGE_SIZE
            }
            .onFailure {
                orders = emptyList()
                error = it.userMessage("Couldn't load orders.")
            }
        loading = false
        refreshing = false
    }

    fun loadMore() {
        if (loadingMore || endReached) return
        scope.launch {
            loadingMore = true
            moreError = null
            attempt { loadOrdersPage(filter, orders.size) }
                .onSuccess { page ->
                    val known = orders.map { it.id }.toSet()
                    orders = orders + page.filter { it.id !in known }
                    endReached = page.size < OrdersRepository.PAGE_SIZE
                }
                .onFailure { moreError = it.userMessage("Couldn't load more orders.") }
            loadingMore = false
        }
    }

    val filterOptions = remember {
        listOf(FILTER_ALL to "All", FILTER_REFUNDS to "Refunds") +
            OrderStatusFlow.allStatuses.map { it to OrderStatusFlow.label(it) }
    }
    val visible = orders.filter { it.matches(query) }

    Scaffold(
        topBar = { AdminTopBar(title = "Orders") }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AdminSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Order ID, customer name or phone",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            AdminFilterChips(
                options = filterOptions,
                selected = filter,
                onSelect = { filter = it }
            )
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    reloadKey++
                },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when {
                        loading -> item { AdminLoading(Modifier.fillParentMaxSize()) }
                        error != null -> item {
                            AdminErrorState(
                                message = error.orEmpty(),
                                onRetry = { reloadKey++ },
                                modifier = Modifier.fillParentMaxSize()
                            )
                        }
                        orders.isEmpty() -> item {
                            AdminEmptyState(
                                message = if (filter == FILTER_REFUNDS) "No refunds need attention"
                                else "No orders here yet",
                                icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                                modifier = Modifier.fillParentMaxSize()
                            )
                        }
                        else -> {
                            if (query.isNotBlank() && !endReached) {
                                item {
                                    Text(
                                        "Searching the ${orders.size} loaded orders. Load more to search further back.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (visible.isEmpty()) {
                                item {
                                    Text(
                                        "No loaded orders match \"$query\"",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 24.dp)
                                    )
                                }
                            }
                            items(visible, key = { it.id }) { order ->
                                AdminOrderRow(order = order, onClick = { onOpenOrder(order.id) })
                            }
                            if (!endReached) {
                                item {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        if (loadingMore) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(28.dp),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            OutlinedButton(onClick = { loadMore() }) { Text("Load more") }
                                        }
                                    }
                                }
                            }
                            moreError?.let { msg -> item { AdminInlineError(msg) } }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminOrderRow(order: Order, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open order ${order.shortId}", onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("#${order.shortId}", fontWeight = FontWeight.SemiBold)
                Text(rupees(order.totalPrice), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Text(
                "${order.product.name} · ${order.itemCount} item${if (order.itemCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOf(order.deliveryAddress.name, order.deliveryAddress.phone, order.placedDate)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AdminBadge(OrderStatusFlow.label(order.status), orderStatusTone(order.status))
                AdminBadge(order.paymentStatusLabel, paymentStatusTone(order.paymentStatus))
                adminRefundLabel(order.refundStatus, order.cancelRefundChoice)?.let {
                    AdminBadge(it, refundTone(order.refundStatus))
                }
            }
        }
    }
}
