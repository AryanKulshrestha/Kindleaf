package com.kindleaf.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kindleaf.data.AppStateManager
import com.kindleaf.models.Product
import com.kindleaf.ui.components.ProductCard
import kotlinx.coroutines.launch

/**
 * Two-column product grid backed by a [ProductPager]; requests the next page when the
 * user scrolls within a few rows of the end.
 */
@Composable
fun PagedProductGrid(
    pager: ProductPager,
    onProductClick: (String) -> Unit,
    onAddToCart: (Product) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    header: (LazyGridScope.() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val nearEnd by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, pager.items.size) {
        if (nearEnd) pager.loadMore()
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        header?.invoke(this)
        items(pager.items, key = { it.id }) { product ->
            ProductCard(
                product = product,
                onClick = { onProductClick(product.id) },
                onAddToCart = { onAddToCart(product) }
            )
        }
        if (pager.isLoadingMore || pager.loadMoreError != null) {
            item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                LoadMoreFooter(
                    isLoading = pager.isLoadingMore,
                    error = pager.loadMoreError,
                    onRetry = { scope.launch { pager.loadMore(force = true) } }
                )
            }
        }
    }
}

/** Adds one unit to the cart and reports the result in a snackbar (with "View cart" when possible). */
fun addToCartWithFeedback(
    product: Product,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    onViewCart: (() -> Unit)?,
) {
    val added = AppStateManager.addToCart(product, 1)
    scope.launch {
        snackbarHostState.currentSnackbarData?.dismiss()
        val message = when {
            added -> "${product.name} added to cart"
            !product.isInStock -> "${product.name} is out of stock"
            else -> "You already have the maximum quantity of ${product.name} in your cart"
        }
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = if (added && onViewCart != null) "View cart" else null,
            withDismissAction = true
        )
        if (result == SnackbarResult.ActionPerformed) onViewCart?.invoke()
    }
}
