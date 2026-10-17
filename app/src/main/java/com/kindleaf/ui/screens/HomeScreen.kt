package com.kindleaf.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kindleaf.data.AppStateManager
import com.kindleaf.data.CatalogStore
import com.kindleaf.models.Coupon
import com.kindleaf.models.CouponType
import com.kindleaf.models.Product
import com.kindleaf.ui.catalog.CartIconButton
import com.kindleaf.ui.catalog.EmptyState
import com.kindleaf.ui.catalog.ErrorState
import com.kindleaf.ui.catalog.addToCartWithFeedback
import com.kindleaf.ui.components.ProductCard
import kotlinx.coroutines.launch

private const val ROW_SIZE = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSearchClick: () -> Unit,
    onProductClick: (String) -> Unit,
    onViewAllClick: () -> Unit,
    onCategoryClick: (String) -> Unit,
    onNotificationsClick: () -> Unit,
    onCartClick: () -> Unit,
) {
    val products = CatalogStore.products
    val categories = CatalogStore.categories
    val isLoading = CatalogStore.isLoading.value
    val error = CatalogStore.error.value
    val profile = AppStateManager.userProfile.value
    val firstName = profile.name.trim().split(" ").firstOrNull().orEmpty().ifBlank { "there" }
    val unread = AppStateManager.unreadNotificationCount

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val snapshot = products.toList()
    val popular = remember(snapshot) {
        snapshot.sortedWith(
            compareByDescending<Product> { it.reviewCount }.thenByDescending { it.rating }
        ).take(ROW_SIZE)
    }
    val topRated = remember(snapshot) {
        snapshot.filter { it.reviewCount > 0 }
            .sortedWith(compareByDescending<Product> { it.rating }.thenByDescending { it.reviewCount })
            .take(ROW_SIZE)
    }
    // Catalog is fetched oldest-first, so the tail holds the newest teas.
    val newArrivals = remember(snapshot) { snapshot.asReversed().take(ROW_SIZE) }
    val heroCoupon = bestCoupon(CatalogStore.coupons.toList())

    LaunchedEffect(Unit) {
        if (products.isEmpty() && !CatalogStore.isLoading.value) CatalogStore.refresh()
        AppStateManager.refreshNotifications()
    }

    fun addToCart(product: Product) =
        addToCartWithFeedback(product, snackbarHostState, scope, onViewCart = onCartClick)

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        PullToRefreshBox(
            isRefreshing = isLoading && products.isNotEmpty(),
            onRefresh = { scope.launch { CatalogStore.refresh() } },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    HomeHeader(
                        firstName = firstName,
                        unread = unread,
                        onNotificationsClick = onNotificationsClick,
                        onCartClick = onCartClick
                    )
                }
                item {
                    Card(
                        onClick = onSearchClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Search teas, ingredients, benefits...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (heroCoupon != null) {
                    item {
                        Spacer(Modifier.height(20.dp))
                        HeroBanner(coupon = heroCoupon, onShopNow = onViewAllClick)
                    }
                }

                if (categories.size > 1) {
                    item {
                        Spacer(Modifier.height(24.dp))
                        SectionHeader(title = "Shop by category", onViewAll = onViewAllClick)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(categories.toList(), key = { it }) { category ->
                                SuggestionChip(
                                    onClick = {
                                        if (category == CatalogStore.ALL_CATEGORIES) onViewAllClick()
                                        else onCategoryClick(category)
                                    },
                                    label = { Text(category, style = MaterialTheme.typography.labelLarge) },
                                    shape = RoundedCornerShape(20.dp)
                                )
                            }
                        }
                    }
                }

                when {
                    products.isEmpty() && isLoading -> item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    products.isEmpty() && error != null -> item {
                        ErrorState(
                            message = error,
                            onRetry = { scope.launch { CatalogStore.refresh() } },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    products.isEmpty() -> item {
                        EmptyState(
                            icon = Icons.Outlined.Spa,
                            title = "No teas available right now",
                            subtitle = "New blends are on their way. Pull down to refresh.",
                            modifier = Modifier.fillMaxWidth(),
                            actionLabel = "Refresh",
                            onAction = { scope.launch { CatalogStore.refresh() } }
                        )
                    }

                    else -> {
                        if (error != null) {
                            item {
                                Spacer(Modifier.height(16.dp))
                                InlineError(message = error, onRetry = { scope.launch { CatalogStore.refresh() } })
                            }
                        }
                        item {
                            Spacer(Modifier.height(24.dp))
                            SectionHeader(title = "Popular teas", onViewAll = onViewAllClick)
                            ProductRow(popular, onProductClick, ::addToCart)
                        }
                        if (topRated.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(24.dp))
                                SectionHeader(title = "Top rated", onViewAll = onViewAllClick)
                                ProductRow(topRated, onProductClick, ::addToCart)
                            }
                        }
                        item {
                            Spacer(Modifier.height(24.dp))
                            SectionHeader(title = "New arrivals", onViewAll = onViewAllClick)
                            ProductRow(newArrivals, onProductClick, ::addToCart)
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

/** Picks the most attractive active coupon for the hero banner, or null when there is none. */
private fun bestCoupon(coupons: List<Coupon>): Coupon? =
    coupons.filter { it.isActive && it.code.isNotBlank() }
        .sortedWith(
            compareBy<Coupon> { if (it.type == CouponType.DISCOUNT) 0 else 1 }
                .thenByDescending { it.discountPercent }
                .thenByDescending { it.fixedAmount }
                .thenBy { it.minOrder }
        )
        .firstOrNull()

private fun couponOffer(coupon: Coupon): String = when {
    coupon.fixedAmount > 0 -> "Flat ₹${coupon.fixedAmount} off"
    coupon.discountPercent > 0 -> "${coupon.discountPercent}% off" +
        (coupon.maxDiscount?.takeIf { it > 0 }?.let { " (up to ₹$it)" } ?: "")
    coupon.type == CouponType.SHIPPING -> "Free delivery"
    else -> ""
}

@Composable
private fun HomeHeader(
    firstName: String,
    unread: Int,
    onNotificationsClick: () -> Unit,
    onCartClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Hello, $firstName",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Find your perfect tea",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onNotificationsClick) {
            BadgedBox(
                badge = {
                    if (unread > 0) {
                        Badge { Text(if (unread > 99) "99+" else "$unread") }
                    }
                }
            ) {
                Icon(
                    Icons.Outlined.Notifications,
                    contentDescription = if (unread > 0) "Notifications, $unread unread" else "Notifications"
                )
            }
        }
        CartIconButton(onClick = onCartClick)
    }
}

@Composable
private fun HeroBanner(coupon: Coupon, onShopNow: () -> Unit) {
    val offer = couponOffer(coupon)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LocalOffer, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = coupon.headline.ifBlank { offer.ifBlank { "Special offer" } },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val detail = listOf(offer, coupon.description)
                .filter { it.isNotBlank() && it != coupon.headline }
                .joinToString(" · ")
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (coupon.minOrder > 0) {
                Text(
                    text = "On orders above ₹${coupon.minOrder}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f),
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(
                        text = "Code: ${coupon.code}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onShopNow,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Shop now", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, onViewAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )
        TextButton(onClick = onViewAll) {
            Text("View all", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ProductRow(
    products: List<Product>,
    onProductClick: (String) -> Unit,
    onAddToCart: (Product) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(products, key = { it.id }) { product ->
            ProductCard(
                product = product,
                onClick = { onProductClick(product.id) },
                onAddToCart = { onAddToCart(product) },
                modifier = Modifier.width(164.dp)
            )
        }
    }
}

@Composable
private fun InlineError(message: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
