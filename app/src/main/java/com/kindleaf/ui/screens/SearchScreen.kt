package com.kindleaf.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kindleaf.data.CatalogStore
import com.kindleaf.ui.catalog.CatalogFilters
import com.kindleaf.ui.catalog.EmptyState
import com.kindleaf.ui.catalog.ErrorState
import com.kindleaf.ui.catalog.FilterSheet
import com.kindleaf.ui.catalog.FilterSortBar
import com.kindleaf.ui.catalog.PagedProductGrid
import com.kindleaf.ui.catalog.ProductPager
import com.kindleaf.ui.catalog.RecentSearches
import com.kindleaf.ui.catalog.SortOption
import com.kindleaf.ui.catalog.SortSheet
import com.kindleaf.ui.catalog.addToCartWithFeedback
import com.kindleaf.ui.components.SearchChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SEARCH_DEBOUNCE_MS = 300L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onProductClick: (String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val recentStore = remember { RecentSearches(context) }
    var recent by remember { mutableStateOf(recentStore.load()) }
    var query by rememberSaveable { mutableStateOf("") }
    var filters by rememberSaveable(stateSaver = CatalogFilters.Saver) { mutableStateOf(CatalogFilters()) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showSort by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var retryKey by remember { mutableIntStateOf(0) }
    val pager = remember { ProductPager() }

    val isSearching = query.isNotBlank() || filters.activeCount > 0

    LaunchedEffect(Unit) {
        if (CatalogStore.categories.size <= 1 && !CatalogStore.isLoading.value) CatalogStore.refresh()
    }
    LaunchedEffect(Unit) {
        if (query.isEmpty()) runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(query, filters, retryKey) {
        if (!isSearching) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        pager.load(query, filters)
    }

    fun submit(term: String = query) {
        if (term.isNotBlank()) recent = recentStore.add(term)
        focusManager.clearFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showFilters = true }) {
                        BadgedBox(
                            badge = { if (filters.activeCount > 0) Badge { Text("${filters.activeCount}") } }
                        ) {
                            Icon(
                                Icons.Outlined.Tune,
                                contentDescription = if (filters.activeCount > 0)
                                    "Filters, ${filters.activeCount} active" else "Filters"
                            )
                        }
                    }
                },
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(100) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .focusRequester(focusRequester),
                placeholder = {
                    Text("Search teas, ingredients, benefits...", style = MaterialTheme.typography.bodyMedium)
                },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            if (!isSearching) {
                IdleSearchContent(
                    recent = recent,
                    categories = CatalogStore.categories.filter { it != CatalogStore.ALL_CATEGORIES },
                    onRecentClick = { term ->
                        query = term
                        submit(term)
                    },
                    onRecentRemove = { recent = recentStore.remove(it) },
                    onClearAll = { confirmClear = true },
                    onCategoryClick = { name -> filters = filters.copy(category = name) }
                )
            } else {
                Spacer(Modifier.height(4.dp))
                FilterSortBar(
                    filters = filters,
                    resultLabel = when {
                        pager.isLoading || !pager.hasLoaded -> null
                        pager.items.isEmpty() -> null
                        pager.endReached -> "${pager.items.size} results"
                        else -> "${pager.items.size}+ results"
                    },
                    onFiltersClick = { showFilters = true },
                    onSortClick = { showSort = true }
                )
                val firstLoad = pager.items.isEmpty() && (pager.isLoading || !pager.hasLoaded)
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    val error = pager.error
                    when {
                        firstLoad -> CircularProgressIndicator(
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
                            title = if (query.isNotBlank()) "No teas match \"${query.trim()}\"" else "No teas match these filters",
                            subtitle = "Try a different word or loosen the filters.",
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
                                onProductClick = { id ->
                                    submit()
                                    onProductClick(id)
                                },
                                onAddToCart = { product ->
                                    addToCartWithFeedback(product, snackbarHostState, scope, onViewCart = null)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
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
            options = SortOption.entries,
            onSelect = { filters = filters.copy(sort = it) },
            onDismiss = { showSort = false }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear recent searches?") },
            text = { Text("This removes your search history from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    recent = recentStore.clear()
                    confirmClear = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleSearchContent(
    recent: List<String>,
    categories: List<String>,
    onRecentClick: (String) -> Unit,
    onRecentRemove: (String) -> Unit,
    onClearAll: () -> Unit,
    onCategoryClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent searches",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                TextButton(onClick = onClearAll) {
                    Text("Clear all", color = MaterialTheme.colorScheme.primary)
                }
            }
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                recent.forEach { term ->
                    SearchChip(
                        text = term,
                        onClick = { onRecentClick(term) },
                        onRemove = { onRecentRemove(term) }
                    )
                }
            }
        }

        if (categories.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Browse by category",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .semantics { heading() }
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { name ->
                    SuggestionChip(
                        onClick = { onCategoryClick(name) },
                        label = { Text(name) },
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }
        }

        if (recent.isEmpty() && categories.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Search,
                title = "Search the Kindleaf collection",
                subtitle = "Try a tea name, an ingredient like ginger, or a benefit like sleep.",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
