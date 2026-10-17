package com.kindleaf.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import com.kindleaf.data.CatalogStore
import com.kindleaf.ui.catalog.CartIconButton
import com.kindleaf.ui.catalog.CatalogFilters
import com.kindleaf.ui.catalog.EmptyState
import com.kindleaf.ui.catalog.ErrorState
import com.kindleaf.ui.catalog.FilterSheet
import com.kindleaf.ui.catalog.FilterSortBar
import com.kindleaf.ui.catalog.PagedProductGrid
import com.kindleaf.ui.catalog.ProductPager
import com.kindleaf.ui.catalog.SortOption
import com.kindleaf.ui.catalog.SortSheet
import com.kindleaf.ui.catalog.addToCartWithFeedback

/** Sort options that make sense without a search term. */
private val LISTING_SORTS = SortOption.entries.filter { it != SortOption.RELEVANCE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductListingScreen(
    onBack: () -> Unit,
    onProductClick: (String) -> Unit,
    onCartClick: () -> Unit,
    initialCategory: String? = null,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var filters by rememberSaveable(initialCategory, stateSaver = CatalogFilters.Saver) {
        mutableStateOf(
            CatalogFilters(
                category = initialCategory?.takeUnless { it.isBlank() || it == CatalogStore.ALL_CATEGORIES },
                sort = SortOption.POPULAR
            )
        )
    }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showSort by rememberSaveable { mutableStateOf(false) }
    var retryKey by remember { mutableIntStateOf(0) }
    val pager = remember { ProductPager(pageSize = 20) }

    LaunchedEffect(Unit) {
        if (CatalogStore.categories.size <= 1 && !CatalogStore.isLoading.value) CatalogStore.refresh()
    }
    LaunchedEffect(filters, retryKey) {
        pager.load("", filters)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        filters.category ?: CatalogStore.ALL_CATEGORIES,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val categories = CatalogStore.categories.toList()
            if (categories.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categories, key = { it }) { name ->
                        val isAll = name == CatalogStore.ALL_CATEGORIES
                        val selected = if (isAll) filters.category == null
                        else filters.category.equals(name, ignoreCase = true)
                        FilterChip(
                            selected = selected,
                            onClick = { filters = filters.copy(category = if (isAll) null else name) },
                            label = { Text(name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
            }
            FilterSortBar(
                filters = filters,
                resultLabel = when {
                    !pager.hasLoaded || pager.items.isEmpty() -> null
                    pager.endReached -> "${pager.items.size} teas"
                    else -> "${pager.items.size}+ teas"
                },
                onFiltersClick = { showFilters = true },
                onSortClick = { showSort = true }
            )

            val error = pager.error
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                when {
                    pager.items.isEmpty() && (pager.isLoading || !pager.hasLoaded) ->
                        CircularProgressIndicator(
                            modifier = Modifier.padding(top = 48.dp),
                            color = MaterialTheme.colorScheme.primary
                        )

                    error != null -> ErrorState(
                        message = error,
                        onRetry = { retryKey++ },
                        modifier = Modifier.fillMaxWidth()
                    )

                    pager.items.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = "No teas found",
                        subtitle = if (filters.activeCount > 0) "Try removing some filters." else "Check back soon for new blends.",
                        modifier = Modifier.fillMaxWidth(),
                        actionLabel = if (filters.activeCount > 0) "Clear filters" else null,
                        onAction = if (filters.activeCount > 0) {
                            { filters = CatalogFilters(sort = filters.sort) }
                        } else null
                    )

                    else -> PullToRefreshBox(
                        isRefreshing = pager.isLoading,
                        onRefresh = { retryKey++ },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        PagedProductGrid(
                            pager = pager,
                            onProductClick = onProductClick,
                            onAddToCart = { product ->
                                addToCartWithFeedback(product, snackbarHostState, scope, onViewCart = onCartClick)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            current = filters,
            onApply = { filters = it },
            onDismiss = { showFilters = false }
        )
    }
    if (showSort) {
        SortSheet(
            current = filters.sort,
            options = LISTING_SORTS,
            onSelect = { filters = filters.copy(sort = it) },
            onDismiss = { showSort = false }
        )
    }
}
