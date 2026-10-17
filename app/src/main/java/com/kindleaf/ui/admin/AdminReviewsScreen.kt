package com.kindleaf.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Reviews
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kindleaf.data.CatalogStore
import com.kindleaf.data.remote.ItemsRepository
import com.kindleaf.data.remote.ReviewsRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.Review
import com.kindleaf.ui.theme.AccentGold
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val REVIEWS_ALL = "all"
private const val REVIEWS_HIDDEN = "hidden"
private const val REVIEWS_UNVERIFIED = "unverified"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminReviewsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reviews by remember { mutableStateOf<List<Review>>(emptyList()) }
    var productNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(REVIEWS_ALL) }
    var pending by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deleting by remember { mutableStateOf<Review?>(null) }
    var deleteBusy by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        if (!refreshing) loading = true
        error = null
        attempt {
            coroutineScope {
                val r = async { ReviewsRepository.fetchAll() }
                val names = async {
                    attempt { ItemsRepository.fetchAllItems().associate { it.id to it.name } }
                        .getOrElse { CatalogStore.products.associate { it.id to it.name } }
                }
                r.await() to names.await()
            }
        }.onSuccess { (r, names) ->
            reviews = r
            productNames = names
        }.onFailure { error = it.userMessage("Couldn't load reviews.") }
        loading = false
        refreshing = false
    }

    fun update(review: Review, visible: Boolean, verified: Boolean) {
        val previous = reviews
        reviews = reviews.map { if (it.id == review.id) it.copy(isVisible = visible, isVerified = verified) else it }
        pending = pending + review.id
        scope.launch {
            attempt { ReviewsRepository.updateAdmin(review.id, isVisible = visible, isVerified = verified) }
                .onSuccess { saved ->
                    reviews = reviews.map { if (it.id == saved.id) saved else it }
                    actionError = null
                }
                .onFailure { e ->
                    reviews = previous
                    actionError = e.userMessage("Couldn't update the review.")
                }
            pending = pending - review.id
        }
    }

    val visible = when (filter) {
        REVIEWS_HIDDEN -> reviews.filter { !it.isVisible }
        REVIEWS_UNVERIFIED -> reviews.filter { !it.isVerified }
        else -> reviews
    }

    Scaffold(
        topBar = { AdminTopBar(title = "Reviews", onBack = onBack) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AdminFilterChips(
                options = listOf(
                    REVIEWS_ALL to "All (${reviews.size})",
                    REVIEWS_HIDDEN to "Hidden (${reviews.count { !it.isVisible }})",
                    REVIEWS_UNVERIFIED to "Unverified (${reviews.count { !it.isVerified }})",
                ),
                selected = filter,
                onSelect = { filter = it }
            )
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { refreshing = true; reloadKey++ },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when {
                        loading -> item { AdminLoading(Modifier.fillParentMaxSize()) }
                        error != null && reviews.isEmpty() -> item {
                            AdminErrorState(error.orEmpty(), onRetry = { reloadKey++ }, modifier = Modifier.fillParentMaxSize())
                        }
                        visible.isEmpty() -> item {
                            AdminEmptyState(
                                when (filter) {
                                    REVIEWS_HIDDEN -> "No hidden reviews"
                                    REVIEWS_UNVERIFIED -> "Every review is verified"
                                    else -> "No reviews yet"
                                },
                                icon = Icons.Outlined.Reviews,
                                modifier = Modifier.fillParentMaxSize()
                            )
                        }
                        else -> {
                            (actionError ?: error)?.let { msg -> item { AdminInlineError(msg) } }
                            items(visible, key = { it.id }) { review ->
                                ReviewCard(
                                    review = review,
                                    productName = productNames[review.itemId]
                                        ?: CatalogStore.productById(review.itemId)?.name
                                        ?: "Deleted product",
                                    busy = review.id in pending,
                                    onToggleVisible = { update(review, !review.isVisible, review.isVerified) },
                                    onToggleVerified = { update(review, review.isVisible, !review.isVerified) },
                                    onDelete = { deleteError = null; deleting = review }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { review ->
        AdminConfirmDialog(
            title = "Delete review?",
            message = "The review by ${review.author.ifBlank { "this customer" }} will be permanently removed and the product rating recalculated. " +
                "To keep it but hide it from the store, use \"Visible\" instead.",
            confirmLabel = "Delete",
            busy = deleteBusy,
            error = deleteError,
            onDismiss = { if (!deleteBusy) deleting = null },
            onConfirm = {
                scope.launch {
                    deleteBusy = true
                    deleteError = null
                    attempt { ReviewsRepository.delete(review.id) }
                        .onSuccess {
                            reviews = reviews.filterNot { it.id == review.id }
                            deleting = null
                        }
                        .onFailure { deleteError = it.userMessage("Couldn't delete the review.") }
                    deleteBusy = false
                }
            }
        )
    }
}

@Composable
private fun ReviewCard(
    review: Review,
    productName: String,
    busy: Boolean,
    onToggleVisible: () -> Unit,
    onToggleVerified: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                productName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(review.author.ifBlank { "Customer" }, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "★".repeat(review.rating.coerceIn(0, 5)) + "☆".repeat((5 - review.rating).coerceIn(0, 5)),
                            color = AccentGold,
                            modifier = Modifier.semantics { contentDescription = "${review.rating} out of 5 stars" }
                        )
                        Text(
                            "  ${review.date}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete review")
                }
            }
            if (review.text.isNotBlank()) {
                Text(review.text, style = MaterialTheme.typography.bodyMedium)
            }
            if (review.imageUrls.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(review.imageUrls) { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = "Review photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                }
            }
            if (review.helpfulCount > 0 || review.unhelpfulCount > 0) {
                Text(
                    "${review.helpfulCount} found helpful · ${review.unhelpfulCount} not helpful",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = review.isVisible,
                    enabled = !busy,
                    onClick = onToggleVisible,
                    label = { Text(if (review.isVisible) "Visible" else "Hidden") }
                )
                FilterChip(
                    selected = review.isVerified,
                    enabled = !busy,
                    onClick = onToggleVerified,
                    label = { Text(if (review.isVerified) "Verified purchase" else "Unverified") }
                )
                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}
