package com.kindleaf.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kindleaf.data.CatalogStore
import com.kindleaf.data.remote.CategoriesRepository
import com.kindleaf.data.remote.ItemDto
import com.kindleaf.data.remote.ItemWriteDto
import com.kindleaf.data.remote.ItemsRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.Category
import com.kindleaf.ui.components.ImagePickerField
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val ALL_CATEGORY_KEY = "__all__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminProductsScreen(
    onEdit: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<ItemDto>>(emptyList()) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var categoryFilter by rememberSaveable { mutableStateOf(ALL_CATEGORY_KEY) }
    var pendingToggle by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(reloadKey) {
        if (!refreshing) loading = true
        error = null
        attempt {
            coroutineScope {
                val i = async { ItemsRepository.fetchAllItems() }
                val c = async { attempt { CategoriesRepository.fetchAll() }.getOrDefault(emptyList()) }
                i.await() to c.await()
            }
        }.onSuccess { (i, c) ->
            items = i
            categories = c
        }.onFailure { error = it.userMessage("Couldn't load products.") }
        loading = false
        refreshing = false
    }

    val categoryOptions = remember(items, categories) {
        val names = (categories.map { it.name } + items.map { it.category })
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        listOf(ALL_CATEGORY_KEY to "All") + names.map { it to it }
    }
    val visible = items.filter { item ->
        (categoryFilter == ALL_CATEGORY_KEY || item.category.equals(categoryFilter, ignoreCase = true)) &&
            (query.isBlank() || item.name.contains(query.trim(), ignoreCase = true) ||
                item.tags.any { it.contains(query.trim(), ignoreCase = true) })
    }

    Scaffold(
        topBar = { AdminTopBar(title = "Products", subtitle = if (items.isNotEmpty()) "${items.size} products" else null) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(null) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add product") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AdminSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search products",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            if (categoryOptions.size > 2) {
                AdminFilterChips(options = categoryOptions, selected = categoryFilter, onSelect = { categoryFilter = it })
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { refreshing = true; reloadKey++ },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when {
                        loading -> item { AdminLoading(Modifier.fillParentMaxSize()) }
                        error != null && items.isEmpty() -> item {
                            AdminErrorState(error.orEmpty(), onRetry = { reloadKey++ }, modifier = Modifier.fillParentMaxSize())
                        }
                        items.isEmpty() -> item {
                            AdminEmptyState(
                                "No products yet. Tap \"Add product\" to create one.",
                                icon = Icons.Outlined.Inventory2,
                                modifier = Modifier.fillParentMaxSize()
                            )
                        }
                        else -> {
                            (actionError ?: error)?.let { msg -> item { AdminInlineError(msg) } }
                            if (visible.isEmpty()) {
                                item {
                                    Text(
                                        "No products match your filters",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 24.dp)
                                    )
                                }
                            }
                            items(visible, key = { it.id }) { item ->
                                ProductRow(
                                    item = item,
                                    toggling = item.id in pendingToggle,
                                    onClick = { onEdit(item.id) },
                                    onToggleActive = { enabled ->
                                        val previous = items
                                        items = items.map { if (it.id == item.id) it.copy(isActive = enabled) else it }
                                        pendingToggle = pendingToggle + item.id
                                        scope.launch {
                                            attempt { ItemsRepository.setActive(item.id, enabled) }
                                                .onSuccess { saved ->
                                                    items = items.map { if (it.id == saved.id) saved else it }
                                                    actionError = null
                                                    CatalogStore.refreshQuietly()
                                                }
                                                .onFailure { e ->
                                                    items = previous
                                                    actionError = e.userMessage("Couldn't update ${item.name}.")
                                                }
                                            pendingToggle = pendingToggle - item.id
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProductRow(
    item: ItemDto,
    toggling: Boolean,
    onClick: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Edit ${item.name}", onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = item.imageUrl.ifBlank { null },
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(rupees(item.price))
                        item.mrp?.takeIf { it > item.price }?.let { append(" (MRP ${rupees(it)})") }
                        if (item.category.isNotBlank()) append(" · ${item.category}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when {
                        item.stockQty <= 0 -> AdminBadge("Out of stock", AdminTone.Error)
                        item.stockQty <= item.lowStockThreshold -> AdminBadge("Low: ${item.stockQty}", AdminTone.Warning)
                        else -> AdminBadge("${item.stockQty} in stock", AdminTone.Neutral)
                    }
                    if (!item.isActive) AdminBadge("Hidden", AdminTone.Neutral)
                    if (item.reviewCount > 0) AdminBadge("★ ${"%.1f".format(item.rating)} (${item.reviewCount})", AdminTone.Info)
                }
            }
            if (toggling) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Switch(
                    checked = item.isActive,
                    onCheckedChange = onToggleActive,
                    modifier = Modifier.semantics {
                        contentDescription = if (item.isActive) "${item.name} is visible in the store" else "${item.name} is hidden"
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminProductEditScreen(
    productId: String?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var size by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var mrp by rememberSaveable { mutableStateOf("") }
    var stockQty by rememberSaveable { mutableStateOf("0") }
    var lowStock by rememberSaveable { mutableStateOf("5") }
    var imageUrl by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryName by rememberSaveable { mutableStateOf("") }
    var isActive by rememberSaveable { mutableStateOf(true) }
    var populated by rememberSaveable { mutableStateOf(productId == null) }
    var showErrors by rememberSaveable { mutableStateOf(false) }

    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var notFound by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(productId, reloadKey) {
        loading = true
        loadError = null
        attempt {
            coroutineScope {
                val c = async { CategoriesRepository.fetchAll() }
                val i = async { if (productId != null && !populated) ItemsRepository.fetchItemDto(productId) else null }
                c.await() to i.await()
            }
        }.onSuccess { (c, item) ->
            categories = c
            if (productId != null && !populated) {
                if (item == null) {
                    notFound = true
                } else {
                    name = item.name
                    description = item.description
                    tags = item.tags.joinToString(", ")
                    size = item.size
                    price = item.price.toString()
                    mrp = item.mrp?.toString().orEmpty()
                    stockQty = item.stockQty.toString()
                    lowStock = item.lowStockThreshold.toString()
                    imageUrl = item.imageUrl
                    categoryId = item.categoryId
                        ?: c.firstOrNull { it.name.equals(item.category, ignoreCase = true) }?.id
                    categoryName = item.category
                    isActive = item.isActive
                    populated = true
                }
            }
        }.onFailure { loadError = it.userMessage("Couldn't load this product.") }
        loading = false
    }

    // Validation
    val priceValue = price.toIntOrNull()
    val mrpValue = mrp.toIntOrNull()
    val stockValue = stockQty.toIntOrNull()
    val lowStockValue = lowStock.toIntOrNull()
    val nameError = if (name.isBlank()) "Name is required" else null
    val priceError = if (priceValue == null || priceValue <= 0) "Enter a price above ₹0" else null
    val mrpError = when {
        mrp.isBlank() -> null
        mrpValue == null -> "Enter a whole number"
        priceValue != null && mrpValue < priceValue -> "MRP must be at least the selling price"
        else -> null
    }
    val stockError = if (stockValue == null || stockValue < 0) "Enter 0 or more" else null
    val lowStockError = if (lowStockValue == null || lowStockValue < 0) "Enter 0 or more" else null
    val categoryError = if (categories.isNotEmpty() && categoryId == null) "Choose a category" else null
    val hasErrors = listOf(nameError, priceError, mrpError, stockError, lowStockError, categoryError).any { it != null }

    fun save() {
        if (loading) {
            saveError = "Still loading categories, try again in a moment"
            return
        }
        showErrors = true
        if (hasErrors) {
            saveError = "Fix the highlighted fields"
            return
        }
        scope.launch {
            saving = true
            saveError = null
            val selected = categories.firstOrNull { it.id == categoryId }
            val dto = ItemWriteDto(
                name = name.trim(),
                description = description.trim(),
                tags = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                size = size.trim(),
                price = priceValue ?: 0,
                mrp = mrpValue,
                imageUrl = imageUrl.trim(),
                category = selected?.name ?: categoryName.ifBlank { CatalogStore.ALL_CATEGORIES },
                categoryId = selected?.id,
                isActive = isActive,
                stockQty = stockValue ?: 0,
                lowStockThreshold = lowStockValue ?: 0,
            )
            attempt {
                if (productId == null) ItemsRepository.createItem(dto) else ItemsRepository.updateItem(productId, dto)
            }.onSuccess {
                CatalogStore.refreshQuietly()
                saving = false
                onBack()
            }.onFailure {
                saveError = it.userMessage("Couldn't save the product.")
                saving = false
            }
        }
    }

    Scaffold(
        topBar = {
            AdminTopBar(
                title = if (productId == null) "Add product" else "Edit product",
                onBack = onBack,
                actions = {
                    if (productId != null && populated) {
                        IconButton(onClick = { deleteError = null; confirmDelete = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete product")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        when {
            loading && !populated -> AdminLoading(Modifier.padding(padding))
            loadError != null && !populated -> AdminErrorState(
                loadError.orEmpty(),
                onRetry = { reloadKey++ },
                modifier = Modifier.padding(padding)
            )
            notFound -> AdminEmptyState("This product no longer exists.", modifier = Modifier.padding(padding))
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name *") },
                    isError = showErrors && nameError != null,
                    supportingText = errorText(showErrors, nameError),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth()
                )
                ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = it }) {
                    OutlinedTextField(
                        value = categories.firstOrNull { it.id == categoryId }?.name
                            ?: categoryName.ifBlank {
                                when {
                                    loading -> "Loading…"
                                    categories.isEmpty() -> "No categories yet"
                                    else -> ""
                                }
                            },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category *") },
                        isError = showErrors && categoryError != null,
                        supportingText = errorText(showErrors, categoryError),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(if (category.isActive) category.name else "${category.name} (hidden)") },
                                onClick = {
                                    categoryId = category.id
                                    categoryName = category.name
                                    categoryExpanded = false
                                }
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it.filter(Char::isDigit).take(7) },
                        label = { Text("Price (₹) *") },
                        isError = showErrors && priceError != null,
                        supportingText = errorText(showErrors, priceError),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = mrp,
                        onValueChange = { mrp = it.filter(Char::isDigit).take(7) },
                        label = { Text("MRP (₹)") },
                        isError = mrpError != null,
                        supportingText = { Text(mrpError ?: "Optional") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = stockQty,
                        onValueChange = { stockQty = it.filter(Char::isDigit).take(6) },
                        label = { Text("Stock qty *") },
                        isError = showErrors && stockError != null,
                        supportingText = errorText(showErrors, stockError),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lowStock,
                        onValueChange = { lowStock = it.filter(Char::isDigit).take(5) },
                        label = { Text("Low-stock alert at") },
                        isError = showErrors && lowStockError != null,
                        supportingText = { Text(if (showErrors && lowStockError != null) lowStockError else "Units") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = size,
                    onValueChange = { size = it },
                    label = { Text("Pack size (e.g. 100 g)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags") },
                    supportingText = { Text("Comma separated, used for search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ImagePickerField(
                    imageUrl = imageUrl,
                    onImageUrlChange = { imageUrl = it },
                    folder = "products",
                    label = "Product image"
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Visible in store", fontWeight = FontWeight.Medium)
                        Text(
                            "Hidden products can't be found or bought",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = isActive, onCheckedChange = { isActive = it })
                }
                Text(
                    "Ratings and review counts are calculated from customer reviews.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                saveError?.let { AdminInlineError(it) }
                Button(
                    onClick = { save() },
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(if (productId == null) "Create product" else "Save changes")
                    }
                }
            }
        }
    }

    if (confirmDelete && productId != null) {
        AdminConfirmDialog(
            title = "Delete product?",
            message = "\"${name.ifBlank { "This product" }}\" will be removed from the store, customers' carts " +
                "and wishlists, and its reviews will be deleted. Past orders keep their copy of it. To keep it but stop selling it, turn off \"Visible in store\" instead.",
            confirmLabel = "Delete",
            busy = deleting,
            error = deleteError,
            onDismiss = { if (!deleting) confirmDelete = false },
            onConfirm = {
                scope.launch {
                    deleting = true
                    deleteError = null
                    attempt { ItemsRepository.deleteItem(productId) }
                        .onSuccess {
                            CatalogStore.refreshQuietly()
                            deleting = false
                            confirmDelete = false
                            onBack()
                        }
                        .onFailure {
                            deleteError = it.userMessage("Couldn't delete the product.")
                            deleting = false
                        }
                }
            }
        )
    }
}

private fun errorText(show: Boolean, error: String?): (@Composable () -> Unit)? =
    if (show && error != null) ({ Text(error) }) else null
