package com.kindleaf.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kindleaf.data.CatalogStore
import com.kindleaf.data.remote.ItemsRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.Product
import kotlin.coroutines.cancellation.CancellationException

/** Sort orders understood by the `search_items` RPC. */
enum class SortOption(val key: String, val label: String) {
    RELEVANCE("relevance", "Relevance"),
    PRICE_ASC("price_asc", "Price: low to high"),
    PRICE_DESC("price_desc", "Price: high to low"),
    RATING("rating", "Top rated"),
    POPULAR("popular", "Most reviewed"),
    NEWEST("newest", "Newest"),
}

/** Filters shared by Search and Product listing. `category == null` means all categories. */
data class CatalogFilters(
    val category: String? = null,
    val minPrice: Int? = null,
    val maxPrice: Int? = null,
    val inStockOnly: Boolean = false,
    val sort: SortOption = SortOption.RELEVANCE,
) {
    /** Number of active filters (sort excluded) for the "Filters (n)" label. */
    val activeCount: Int
        get() = listOfNotNull(category, minPrice, maxPrice).size + if (inStockOnly) 1 else 0

    companion object {
        val Saver: Saver<CatalogFilters, Any> = listSaver(
            save = {
                listOf(it.category ?: "", it.minPrice ?: -1, it.maxPrice ?: -1, it.inStockOnly, it.sort.name)
            },
            restore = {
                CatalogFilters(
                    category = (it[0] as String).ifBlank { null },
                    minPrice = (it[1] as Int).takeIf { v -> v >= 0 },
                    maxPrice = (it[2] as Int).takeIf { v -> v >= 0 },
                    inStockOnly = it[3] as Boolean,
                    sort = SortOption.valueOf(it[4] as String),
                )
            }
        )
    }
}

/**
 * Paged loader over [ItemsRepository.search]. Call [load] from a LaunchedEffect keyed on the
 * query/filters; call [loadMore] when the list is scrolled near its end.
 */
class ProductPager(private val pageSize: Int = 20) {
    val items = mutableStateListOf<Product>()
    var isLoading by mutableStateOf(false)
        private set
    var isLoadingMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loadMoreError by mutableStateOf<String?>(null)
        private set
    var endReached by mutableStateOf(false)
        private set
    var hasLoaded by mutableStateOf(false)
        private set

    private var generation = 0
    private var currentQuery = ""
    private var currentFilters = CatalogFilters()

    private suspend fun fetch(offset: Int): List<Product> = ItemsRepository.search(
        query = currentQuery,
        category = currentFilters.category,
        minPrice = currentFilters.minPrice,
        maxPrice = currentFilters.maxPrice,
        sort = currentFilters.sort.key,
        inStockOnly = currentFilters.inStockOnly,
        limit = pageSize,
        offset = offset,
    )

    suspend fun load(query: String, filters: CatalogFilters) {
        val gen = ++generation
        currentQuery = query.trim()
        currentFilters = filters
        isLoading = true
        isLoadingMore = false
        error = null
        loadMoreError = null
        endReached = false
        try {
            val page = fetch(0)
            if (gen != generation) return
            items.clear()
            items.addAll(page)
            endReached = page.size < pageSize
            hasLoaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen == generation) {
                items.clear()
                error = e.userMessage("Couldn't load teas. Please try again.")
                hasLoaded = true
            }
        } finally {
            if (gen == generation) isLoading = false
        }
    }

    /** Loads the next page. [force] retries after a failed page. */
    suspend fun loadMore(force: Boolean = false) {
        if (isLoading || isLoadingMore || endReached || !hasLoaded || error != null) return
        if (loadMoreError != null && !force) return
        val gen = generation
        isLoadingMore = true
        loadMoreError = null
        try {
            val page = fetch(items.size)
            if (gen != generation) return
            val seen = items.mapTo(HashSet()) { it.id }
            items.addAll(page.filter { it.id !in seen })
            endReached = page.size < pageSize
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen == generation) loadMoreError = e.userMessage("Couldn't load more teas.")
        } finally {
            if (gen == generation) isLoadingMore = false
        }
    }
}

/** Bottom sheet for category, price range and stock filters. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    current: CatalogFilters,
    onApply: (CatalogFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var category by rememberSaveable { mutableStateOf(current.category) }
    var minText by rememberSaveable { mutableStateOf(current.minPrice?.toString() ?: "") }
    var maxText by rememberSaveable { mutableStateOf(current.maxPrice?.toString() ?: "") }
    var inStockOnly by rememberSaveable { mutableStateOf(current.inStockOnly) }

    val minPrice = minText.toIntOrNull()
    val maxPrice = maxText.toIntOrNull()
    val minInvalid = minText.isNotEmpty() && minPrice == null
    val maxInvalid = maxText.isNotEmpty() && maxPrice == null
    val rangeInvalid = minPrice != null && maxPrice != null && minPrice > maxPrice
    val canApply = !minInvalid && !maxInvalid && !rangeInvalid

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Text("Filters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))

            Text("Category", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CatalogStore.categories.forEach { name ->
                    val isAll = name == CatalogStore.ALL_CATEGORIES
                    val selected = if (isAll) category == null else category.equals(name, ignoreCase = true)
                    FilterChip(
                        selected = selected,
                        onClick = { category = if (isAll) null else name },
                        label = { Text(name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Price range (₹)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = minText,
                    onValueChange = { v -> minText = v.filter { it.isDigit() }.take(6) },
                    label = { Text("Min") },
                    singleLine = true,
                    isError = minInvalid || rangeInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = maxText,
                    onValueChange = { v -> maxText = v.filter { it.isDigit() }.take(6) },
                    label = { Text("Max") },
                    singleLine = true,
                    isError = maxInvalid || rangeInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f)
                )
            }
            if (rangeInvalid) {
                Text(
                    "Min price can't be more than max price",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = inStockOnly, role = Role.Switch, onValueChange = { inStockOnly = it })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("In stock only", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = inStockOnly, onCheckedChange = null)
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        onApply(CatalogFilters(sort = current.sort))
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Reset") }
                Button(
                    onClick = {
                        onApply(
                            current.copy(
                                category = category,
                                minPrice = minPrice,
                                maxPrice = maxPrice,
                                inStockOnly = inStockOnly
                            )
                        )
                        onDismiss()
                    },
                    enabled = canApply,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Apply", color = MaterialTheme.colorScheme.onPrimary) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Bottom sheet listing the sort options as radio buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortSheet(
    current: SortOption,
    options: List<SortOption>,
    onSelect: (SortOption) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .selectableGroup()
        ) {
            Text(
                "Sort by",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = option == current,
                            role = Role.RadioButton,
                            onClick = {
                                onSelect(option)
                                onDismiss()
                            }
                        )
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = option == current, onClick = null)
                    Spacer(Modifier.padding(start = 12.dp))
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** "Filters (n)" and "Sort: …" buttons shown above a product list. */
@Composable
fun FilterSortBar(
    filters: CatalogFilters,
    resultLabel: String?,
    onFiltersClick: () -> Unit,
    onSortClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(onClick = onFiltersClick, shape = RoundedCornerShape(20.dp)) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Outlined.FilterList,
                contentDescription = null,
                modifier = Modifier.padding(end = 6.dp)
            )
            Text(
                if (filters.activeCount > 0) "Filters (${filters.activeCount})" else "Filters",
                style = MaterialTheme.typography.labelLarge
            )
        }
        Text(
            text = resultLabel.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        OutlinedButton(onClick = onSortClick, shape = RoundedCornerShape(20.dp)) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.AutoMirrored.Filled.Sort,
                contentDescription = null,
                modifier = Modifier.padding(end = 6.dp)
            )
            Text(filters.sort.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
