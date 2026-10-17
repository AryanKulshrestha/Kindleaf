package com.kindleaf.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.kindleaf.ui.components.RatingBar
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private sealed interface DetailState {
    data object Loading : DetailState
    data object NotFound : DetailState
    data class Failed(val message: String) : DetailState
    data class Loaded(val product: Product) : DetailState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(
    productId: String,
    onBack: () -> Unit,
    onCartClick: () -> Unit,
    onReviewsClick: () -> Unit,
    onBuyNow: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var reloadKey by remember { mutableIntStateOf(0) }
    var state by remember(productId) {
        mutableStateOf<DetailState>(
            CatalogStore.productById(productId)?.let { DetailState.Loaded(it) } ?: DetailState.Loading
        )
    }

    // Cached copy shows instantly; the server copy refreshes price/stock (or reveals it's gone).
    LaunchedEffect(productId, reloadKey) {
        val hadCache = state is DetailState.Loaded
        if (!hadCache) state = DetailState.Loading
        try {
            val dto = ItemsRepository.fetchItemDto(productId)
            state = if (dto == null || !dto.isActive) DetailState.NotFound
            else DetailState.Loaded(dto.toProduct())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!hadCache) state = DetailState.Failed(e.userMessage("Couldn't load this tea."))
        }
    }

    val product = (state as? DetailState.Loaded)?.product

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (product != null) {
                        IconButton(onClick = {
                            val text = "Check out ${product.name} (₹${product.price}) on Kindleaf!\n" +
                                "kindleaf://product/${product.id}"
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, product.name)
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            try {
                                context.startActivity(Intent.createChooser(send, "Share ${product.name}"))
                            } catch (_: ActivityNotFoundException) {
                                scope.launch { snackbarHostState.showSnackbar("No app available to share with") }
                            }
                        }) {
                            Icon(Icons.Outlined.Share, contentDescription = "Share")
                        }
                        val wishlisted = AppStateManager.isWishlisted(product.id)
                        IconButton(onClick = { AppStateManager.toggleWishlist(product.id) }) {
                            Icon(
                                if (wishlisted) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (wishlisted) "Remove from wishlist" else "Add to wishlist",
                                tint = if (wishlisted) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    CartIconButton(onClick = onCartClick)
                },
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
            when (val s = state) {
                DetailState.Loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                DetailState.NotFound -> EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = "Product not found",
                    subtitle = "This tea may have been removed or is no longer available.",
                    actionLabel = "Go back",
                    onAction = onBack
                )
                is DetailState.Failed -> ErrorState(message = s.message, onRetry = { reloadKey++ })
                is DetailState.Loaded -> ProductDetailContent(
                    product = s.product,
                    onReviewsClick = onReviewsClick,
                    onAddToCart = { qty ->
                        val added = AppStateManager.addToCart(s.product, qty)
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            if (added) {
                                val result = snackbarHostState.showSnackbar(
                                    message = "Added to cart",
                                    actionLabel = "View cart",
                                    withDismissAction = true
                                )
                                if (result == SnackbarResult.ActionPerformed) onCartClick()
                            } else {
                                snackbarHostState.showSnackbar("You already have the maximum quantity in your cart")
                            }
                        }
                    },
                    onBuyNow = { qty ->
                        if (qty > 0) AppStateManager.addToCart(s.product, qty)
                        onBuyNow()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProductDetailContent(
    product: Product,
    onReviewsClick: () -> Unit,
    onAddToCart: (Int) -> Unit,
    onBuyNow: (Int) -> Unit,
) {
    val maxQty = AppStateManager.maxQuantityFor(product)
    val inCart = AppStateManager.cartItems.firstOrNull { it.product.id == product.id }?.quantity ?: 0
    val canAdd = (maxQty - inCart).coerceAtLeast(0)
    var quantity by rememberSaveable(product.id) { mutableIntStateOf(1) }
    val qty = quantity.coerceIn(1, canAdd.coerceAtLeast(1))
    val outOfStock = !product.isInStock

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            AsyncImage(
                model = product.imageUrl,
                contentDescription = product.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(horizontal = 32.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(20.dp)),
                contentScale = ContentScale.Crop,
                alpha = if (outOfStock) 0.5f else 1f
            )

            Spacer(Modifier.height(16.dp))

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (product.category.isNotBlank()) {
                    Text(
                        text = product.category,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (product.size.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = product.size,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Rating row → reviews
                Row(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClickLabel = "Open reviews", onClick = onReviewsClick)
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (product.reviewCount > 0) {
                        RatingBar(rating = product.rating)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "%.1f".format(product.rating),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "(${product.reviewCount} ${if (product.reviewCount == 1) "review" else "reviews"})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            "No reviews yet · See reviews",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                PriceRow(product = product, large = true)
                Text(
                    "Inclusive of all taxes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))
                StockStatus(product = product, inCart = inCart, canAdd = canAdd)

                if (!outOfStock && canAdd > 0) {
                    Spacer(Modifier.height(12.dp))
                    QuantityStepper(
                        quantity = qty,
                        max = canAdd,
                        onChange = { quantity = it }
                    )
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                Spacer(Modifier.height(16.dp))

                if (product.description.isNotBlank()) {
                    Text(
                        text = "Description",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = product.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                }

                if (product.tags.isNotEmpty()) {
                    Text(
                        text = "Highlights",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(8.dp))
                    // Informational labels only (not clickable).
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        product.tags.filter { it.isNotBlank() }.forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ) {
                                Text(
                                    tag,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // Bottom action bar
        Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { onAddToCart(qty) },
                    enabled = !outOfStock && canAdd > 0,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Icon(Icons.Outlined.ShoppingCart, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (outOfStock) "Out of stock" else "Add to cart",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Button(
                    onClick = { onBuyNow(if (canAdd > 0) qty else 0) },
                    enabled = !outOfStock && (canAdd > 0 || inCart > 0),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Text("Buy now", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun StockStatus(product: Product, inCart: Int, canAdd: Int) {
    val (text, color) = when {
        !product.isInStock -> "Out of stock" to MaterialTheme.colorScheme.error
        product.isLowStock -> "Only ${product.stockQty} left — order soon" to MaterialTheme.colorScheme.error
        else -> "In stock" to MaterialTheme.colorScheme.primary
    }
    Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
        if (inCart > 0) {
            Text(
                if (canAdd > 0) "$inCart already in your cart"
                else "$inCart in your cart — that's the maximum for this item",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QuantityStepper(quantity: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Quantity",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        OutlinedIconButton(
            onClick = { onChange(quantity - 1) },
            enabled = quantity > 1,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.Remove, contentDescription = "Decrease quantity")
        }
        Text(
            text = "$quantity",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(min = 40.dp)
                .clearAndSetSemantics { contentDescription = "Quantity $quantity" },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        OutlinedIconButton(
            onClick = { onChange(quantity + 1) },
            enabled = quantity < max,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Increase quantity")
        }
    }
    if (quantity >= max) {
        Text(
            "You can add up to $max",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
