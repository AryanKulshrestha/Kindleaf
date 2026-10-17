package com.kindleaf.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kindleaf.data.CatalogStore
import com.kindleaf.data.remote.CategoriesRepository
import com.kindleaf.data.remote.CategoryWriteDto
import com.kindleaf.data.userMessage
import com.kindleaf.models.Category
import com.kindleaf.ui.components.ImagePickerField
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminCategoriesScreen() {
    val scope = rememberCoroutineScope()
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var pendingToggle by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Editor: null = closed, "" = new, otherwise the category id being edited.
    var editorFor by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    var deleteBusy by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        if (!refreshing) loading = true
        error = null
        attempt { CategoriesRepository.fetchAll() }
            .onSuccess { categories = it }
            .onFailure { error = it.userMessage("Couldn't load categories.") }
        loading = false
        refreshing = false
    }

    Scaffold(
        topBar = { AdminTopBar(title = "Categories") },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editorFor = "" },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add category") }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; reloadKey++ },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    loading -> item { AdminLoading(Modifier.fillParentMaxSize()) }
                    error != null && categories.isEmpty() -> item {
                        AdminErrorState(error.orEmpty(), onRetry = { reloadKey++ }, modifier = Modifier.fillParentMaxSize())
                    }
                    categories.isEmpty() -> item {
                        AdminEmptyState(
                            "No categories yet. Add one to organise your teas.",
                            icon = Icons.Outlined.Category,
                            modifier = Modifier.fillParentMaxSize()
                        )
                    }
                    else -> {
                        (actionError ?: error)?.let { msg -> item { AdminInlineError(msg) } }
                        items(categories, key = { it.id }) { category ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClickLabel = "Edit ${category.name}") { editorFor = category.id },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (category.imageUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = category.imageUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        )
                                        Spacer(Modifier.width(12.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(category.name, fontWeight = FontWeight.SemiBold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                "Position ${category.sortOrder}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (!category.isActive) AdminBadge("Hidden")
                                        }
                                    }
                                    if (category.id in pendingToggle) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    } else {
                                        Switch(
                                            checked = category.isActive,
                                            modifier = Modifier.semantics {
                                                contentDescription = "${category.name} visible in store"
                                            },
                                            onCheckedChange = { active ->
                                                val previous = categories
                                                categories = categories.map { if (it.id == category.id) it.copy(isActive = active) else it }
                                                pendingToggle = pendingToggle + category.id
                                                scope.launch {
                                                    attempt { CategoriesRepository.setActive(category.id, active) }
                                                        .onSuccess {
                                                            actionError = null
                                                            CatalogStore.refresh()
                                                        }
                                                        .onFailure { e ->
                                                            categories = previous
                                                            actionError = e.userMessage("Couldn't update ${category.name}.")
                                                        }
                                                    pendingToggle = pendingToggle - category.id
                                                }
                                            }
                                        )
                                    }
                                    IconButton(onClick = { deleteError = null; deleting = category }) {
                                        Icon(Icons.Outlined.Delete, contentDescription = "Delete ${category.name}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editorFor?.let { id ->
        val existing = categories.firstOrNull { it.id == id }
        CategoryEditorDialog(
            existing = existing,
            defaultSortOrder = (categories.maxOfOrNull { it.sortOrder } ?: 0) + 1,
            onDismiss = { editorFor = null },
            onSaved = {
                editorFor = null
                reloadKey++
                scope.launch { CatalogStore.refresh() }
            }
        )
    }

    deleting?.let { category ->
        AdminConfirmDialog(
            title = "Delete \"${category.name}\"?",
            message = "Products in this category are kept but will no longer be linked to it. " +
                "To hide it from customers without deleting, turn it off instead.",
            confirmLabel = "Delete",
            busy = deleteBusy,
            error = deleteError,
            onDismiss = { if (!deleteBusy) deleting = null },
            onConfirm = {
                scope.launch {
                    deleteBusy = true
                    deleteError = null
                    attempt { CategoriesRepository.delete(category.id) }
                        .onSuccess {
                            categories = categories.filterNot { it.id == category.id }
                            deleting = null
                            CatalogStore.refresh()
                        }
                        .onFailure { deleteError = it.userMessage("Couldn't delete the category.") }
                    deleteBusy = false
                }
            }
        )
    }
}

@Composable
private fun CategoryEditorDialog(
    existing: Category?,
    defaultSortOrder: Int,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var sortOrder by rememberSaveable { mutableStateOf((existing?.sortOrder ?: defaultSortOrder).toString()) }
    var imageUrl by rememberSaveable { mutableStateOf(existing?.imageUrl.orEmpty()) }
    var isActive by rememberSaveable { mutableStateOf(existing?.isActive ?: true) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val nameError = if (name.isBlank()) "Name is required" else null

    AdminConfirmDialog(
        title = if (existing == null) "Add category" else "Edit category",
        message = if (existing != null && name.trim() != existing.name && name.isNotBlank()) {
            "Renaming updates every product in this category."
        } else {
            "Categories group products in the store."
        },
        confirmLabel = "Save",
        destructive = false,
        busy = saving,
        error = error,
        onDismiss = { if (!saving) onDismiss() },
        onConfirm = {
            showErrors = true
            if (nameError != null) return@AdminConfirmDialog
            scope.launch {
                saving = true
                error = null
                val dto = CategoryWriteDto(
                    name = name.trim(),
                    sortOrder = sortOrder.toIntOrNull() ?: 0,
                    isActive = isActive,
                    imageUrl = imageUrl.trim(),
                )
                attempt {
                    if (existing == null) CategoriesRepository.create(dto) else CategoriesRepository.update(existing.id, dto)
                }.onSuccess {
                    saving = false
                    onSaved()
                }.onFailure {
                    error = it.userMessage("Couldn't save the category.")
                    saving = false
                }
            }
        },
        extraContent = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text("Name *") },
                isError = showErrors && nameError != null,
                supportingText = if (showErrors && nameError != null) ({ Text(nameError) }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = sortOrder,
                onValueChange = { sortOrder = it.filter(Char::isDigit).take(4) },
                label = { Text("Position") },
                supportingText = { Text("Lower numbers appear first") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            ImagePickerField(
                imageUrl = imageUrl,
                onImageUrlChange = { imageUrl = it },
                folder = "categories",
                label = "Category image",
                compact = true
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Visible in store", modifier = Modifier.weight(1f))
                Switch(checked = isActive, onCheckedChange = { isActive = it })
            }
        }
    )
}
