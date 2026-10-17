package com.kindleaf.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kindleaf.data.CatalogStore
import com.kindleaf.data.remote.CouponsRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.CouponType
import com.kindleaf.ui.components.CouponCard
import com.kindleaf.ui.theme.PrimaryGreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CouponsScreen(
    onBack: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val coupons = CatalogStore.coupons
    var loading by remember { mutableStateOf(coupons.isEmpty()) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Same as CatalogStore.refreshCoupons(), but surfaces failures so we can show an error state.
    suspend fun load() {
        try {
            val remote = CouponsRepository.fetchActive()
            CatalogStore.coupons.clear()
            CatalogStore.coupons.addAll(remote)
            error = null
        } catch (e: Exception) {
            error = e.userMessage("Couldn't load coupons.")
        }
    }

    LaunchedEffect(Unit) {
        load()
        loading = false
    }

    val filterTabs = listOf(
        "All" to null,
        "Discount" to CouponType.DISCOUNT,
        "Shipping" to CouponType.SHIPPING,
        "Special" to CouponType.SPECIAL
    )
    val visibleTabs = filterTabs.filter { (_, type) -> type == null || coupons.any { it.type == type } }
    val selected = visibleTabs.getOrElse(selectedTab) { visibleTabs.first() }
    val filtered = if (selected.second == null) coupons.toList() else coupons.filter { it.type == selected.second }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Coupons & Offers",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    load()
                    refreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PrimaryGreen)
                }

                error != null && coupons.isEmpty() -> CouponsMessage(
                    icon = Icons.Outlined.CloudOff,
                    title = "Couldn't load coupons",
                    message = error!!,
                    actionLabel = "Retry",
                    onAction = {
                        scope.launch {
                            loading = true
                            load()
                            loading = false
                        }
                    }
                )

                coupons.isEmpty() -> CouponsMessage(
                    icon = Icons.Outlined.LocalOffer,
                    title = "No coupons right now",
                    message = "Check back soon. New offers will show up here."
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (visibleTabs.size > 2) {
                        item {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                visibleTabs.forEachIndexed { index, (label, type) ->
                                    val count = if (type == null) coupons.size else coupons.count { it.type == type }
                                    FilterChip(
                                        selected = selected == visibleTabs[index],
                                        onClick = { selectedTab = index },
                                        label = { Text("$label ($count)", style = MaterialTheme.typography.labelMedium) },
                                        shape = RoundedCornerShape(20.dp),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            "Apply a code at checkout. Only one coupon can be used per order.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(filtered, key = { it.id }) { coupon ->
                        CouponCard(
                            coupon = coupon,
                            onCopy = { code ->
                                clipboard.setText(AnnotatedString(code))
                                scope.launch {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    snackbarHostState.showSnackbar("Code $code copied")
                                }
                            }
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun CouponsMessage(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Spacer(Modifier.height(64.dp))
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onAction) { Text(actionLabel, color = PrimaryGreen) }
        }
    }
}
