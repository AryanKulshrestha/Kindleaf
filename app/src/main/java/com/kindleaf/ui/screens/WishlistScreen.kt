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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.AddShoppingCart
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kindleaf.data.AppStateManager
import com.kindleaf.data.CatalogStore
import com.kindleaf.data.remote.ItemsRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.Product
import com.kindleaf.ui.catalog.CartIconButton
import com.kindleaf.ui.catalog.EmptyState
import com.kindleaf.ui.catalog.ErrorState
import com.kindleaf.ui.catalog.PriceRow
import com.kindleaf.ui.catalog.addToCartWithFeedback
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** A wishlisted id resolved against the catalog. [product] is null when the item was deleted. */
private data class WishEntry(val id: String, val product: Product?, val available: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishlistScreen(
    onBack: () -> Unit,
    onProductClick: (String) -> Unit,
    onCartClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val wishlistIds = AppStateManager.wishlistIds
    val catalog = CatalogStore.products
    val catalogLoading = CatalogStore.isLoading.value
    val catalogError = CatalogStore.error.value

    // Items not in the active catalog, looked up individually (inactive or deleted).
    val offCatalog = remember { mutableStateMapOf<String, WishEntry>() }
    var lookupError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (catalog.isEmpty() && !CatalogStore.isLoading.value) CatalogStore.refresh()
    }

    val catalogReady = !catalogLoading && (catalog.isNotEmpty() || catalogError == null)
    val idsSnapshot = wishlistIds.toList()
    LaunchedEffect(idsSnapshot, catalogReady, retryKey) {
        if (!catalogReady) return@LaunchedEffect
        lookupError = null
        val missing = idsSnapshot.filter { CatalogStore.productById(it) == null && it !in offCatalog }
        for (id in missing) {
            try {
                val dto = ItemsRepository.fetchItemDto(id)
                offCatalog[id] = WishEntry(id, dto?.toProduct(), available = dto?.isActive == true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lookupError = e.userMessage("Couldn't load some saved teas.")
            }
        }
    }

    val entries = idsSnapshot.mapNotNull { id ->
        CatalogStore.productById(id)?.let { WishEntry(id, it, available = true) } ?: offCatalog[id]
    }
    val pendingLookups = catalogReady && idsSnapshot.size > entries.size && lookupError == null

    fun removeWithUndo(entry: WishEntry) {
        AppStateManager.toggleWishlist(entry.id)
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = "Removed ${entry.product?.name ?: "item"} from wishlist",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed && !AppStateManager.isWishlisted(entry.id)) {
                AppStateManager.toggleWishlist(entry.id)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "My Wishlist",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (idsSnapshot.isNotEmpty()) {
                            Text(
                                if (idsSnapshot.size == 1) "1 item" else "${idsSnapshot.size} items",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { CartIconButton(onClick = onCartClick) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when {
                idsSnapshot.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.FavoriteBorder,
                    title = "Your wishlist is empty",
                    subtitle = "Browse teas and tap the heart to save them here."
                )

                catalog.isEmpty() && catalogLoading ->
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)

                catalog.isEmpty() && catalogError != null -> ErrorState(
                    message = catalogError,
                    onRetry = { scope.launch { CatalogStore.refresh() } }
                )

                else -> PullToRefreshBox(
                    isRefreshing = catalogLoading,
                    onRefresh = {
                        scope.launch {
                            offCatalog.clear()
                            CatalogStore.refresh()
                            retryKey++
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val err = lookupError
                        if (err != null) {
                            item(key = "lookup-error") {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(err, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { retryKey++ }) { Text("Retry") }
                                    }
                                }
                            }
                        }
                        items(entries, key = { it.id }) { entry ->
                            val product = entry.product
                            if (entry.available && product != null) {
                                WishlistItemCard(
                                    product = product,
                                    onClick = { onProductClick(product.id) },
                                    onRemove = { removeWithUndo(entry) },
                                    onAddToCart = {
                                        addToCartWithFeedback(product, snackbarHostState, scope, onViewCart = onCartClick)
                                    }
                                )
                            } else {
                                UnavailableItemCard(entry = entry, onRemove = { removeWithUndo(entry) })
                            }
                        }
                        if (pendingLookups) {
                            item(key = "pending") {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WishlistItemCard(
    product: Product,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onAddToCart: () -> Unit
) {
    val outOfStock = !product.isInStock
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            AsyncImage(
                model = product.imageUrl,
                contentDescription = product.name,
                modifier = Modifier
                    .size(90.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
                alpha = if (outOfStock) 0.5f else 1f
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (product.size.isNotBlank()) {
                    Text(
                        text = product.size,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                PriceRow(product = product)
                when {
                    outOfStock -> Text(
                        "Out of stock",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                    product.isLowStock -> Text(
                        "Only ${product.stockQty} left",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onAddToCart,
                    enabled = !outOfStock,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Outlined.AddShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (outOfStock) "Out of stock" else "Add to cart")
                }
            }

            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "Remove ${product.name} from wishlist",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun UnavailableItemCard(entry: WishEntry, onRemove: () -> Unit) {
    val product = entry.product
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (product != null && product.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = product.imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                    alpha = 0.4f
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product?.name ?: "Removed item",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "No longer available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = onRemove, shape = RoundedCornerShape(12.dp)) {
                Text("Remove")
            }
        }
    }
}
