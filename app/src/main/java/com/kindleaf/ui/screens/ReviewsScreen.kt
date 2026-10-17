package com.kindleaf.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.kindleaf.data.CatalogStore
import com.kindleaf.data.remote.ImageStorageRepository
import com.kindleaf.data.remote.ReviewsRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.Product
import com.kindleaf.models.Review
import com.kindleaf.ui.components.MultiImagePickerField
import com.kindleaf.ui.components.RatingBar
import com.kindleaf.ui.orders.CenteredMessage
import com.kindleaf.ui.orders.OrderItemThumb
import com.kindleaf.ui.orders.currentUserIdOrNull
import com.kindleaf.ui.theme.AccentGold
import kotlinx.coroutines.launch
import java.util.Locale

private enum class ReviewSort(val label: String) {
    LATEST("Latest"), HIGHEST("Highest rated"), LOWEST("Lowest rated"), HELPFUL("Most helpful")
}

private const val MAX_REVIEW_CHARS = 2000
private const val MAX_REVIEW_PHOTOS = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewsScreen(
    productId: String,
    openComposer: Boolean = false,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val userId = currentUserIdOrNull()

    var product by remember(productId) { mutableStateOf(CatalogStore.productById(productId)) }
    var productLoading by remember(productId) { mutableStateOf(product == null) }

    var reviews by remember(productId) { mutableStateOf<List<Review>>(emptyList()) }
    var reviewsLoaded by remember(productId) { mutableStateOf(false) }
    var reviewsError by remember(productId) { mutableStateOf<String?>(null) }
    var myReview by remember(productId) { mutableStateOf<Review?>(null) }
    var myReviewChecked by remember(productId) { mutableStateOf(false) }
    var myVotes by remember(productId) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var votingIds by remember { mutableStateOf(setOf<String>()) }
    var refreshing by remember { mutableStateOf(false) }

    var sort by rememberSaveable { mutableStateOf(ReviewSort.LATEST) }
    var starFilter by rememberSaveable { mutableIntStateOf(0) } // 0 = all
    var photosOnly by rememberSaveable { mutableStateOf(false) }
    var showComposer by rememberSaveable { mutableStateOf(false) }
    var autoOpened by rememberSaveable { mutableStateOf(false) }
    var viewerPhoto by remember { mutableStateOf<String?>(null) }

    suspend fun loadReviews() {
        try {
            val list = ReviewsRepository.fetchForItem(productId)
            reviews = list
            reviewsError = null
            if (userId != null) {
                myReview = runCatching { ReviewsRepository.fetchMine(productId, userId) }.getOrElse { myReview }
                myVotes = runCatching { ReviewsRepository.fetchMyVotes(userId, list.map { it.id }) }.getOrElse { myVotes }
            }
        } catch (e: Exception) {
            reviewsError = e.userMessage("Couldn't load reviews.")
            if (reviews.isNotEmpty()) reviewsError?.let { msg -> scope.launch { snackbar.showSnackbar(msg) } }
        } finally {
            reviewsLoaded = true
            myReviewChecked = true
        }
    }

    suspend fun loadAll() {
        if (product == null) {
            productLoading = true
            product = CatalogStore.loadProduct(productId)
            productLoading = false
        }
        if (product != null) loadReviews()
    }

    LaunchedEffect(productId) { loadAll() }

    // Open the composer automatically (e.g. "Write a review" from an order) once we know about any existing review.
    LaunchedEffect(openComposer, myReviewChecked, product) {
        if (openComposer && !autoOpened && myReviewChecked && product != null) {
            autoOpened = true
            showComposer = true
        }
    }

    fun vote(review: Review, helpful: Boolean) {
        val uid = userId ?: return
        if (review.id in votingIds) return
        val previous = myVotes[review.id]
        val next: Boolean? = if (previous == helpful) null else helpful
        // Optimistic count update.
        fun adjusted(r: Review): Review {
            var h = r.helpfulCount
            var u = r.unhelpfulCount
            if (previous == true) h-- else if (previous == false) u--
            if (next == true) h++ else if (next == false) u++
            return r.copy(helpfulCount = h.coerceAtLeast(0), unhelpfulCount = u.coerceAtLeast(0))
        }
        val before = reviews
        reviews = reviews.map { if (it.id == review.id) adjusted(it) else it }
        myVotes = if (next == null) myVotes - review.id else myVotes + (review.id to next)
        votingIds = votingIds + review.id
        scope.launch {
            try {
                ReviewsRepository.vote(review.id, uid, next)
            } catch (e: Exception) {
                reviews = before
                myVotes = if (previous == null) myVotes - review.id else myVotes + (review.id to previous)
                val msg = e.userMessage("Couldn't save your vote.")
                scope.launch { snackbar.showSnackbar(msg) }
            } finally {
                votingIds = votingIds - review.id
            }
        }
    }

    val visibleReviews = remember(reviews, sort, starFilter, photosOnly) {
        reviews
            .filter { starFilter == 0 || it.rating == starFilter }
            .filter { !photosOnly || it.imageUrls.isNotEmpty() }
            .let { list ->
                when (sort) {
                    ReviewSort.LATEST -> list // server returns newest first
                    ReviewSort.HIGHEST -> list.sortedByDescending { it.rating }
                    ReviewSort.LOWEST -> list.sortedBy { it.rating }
                    ReviewSort.HELPFUL -> list.sortedByDescending { it.helpfulCount - it.unhelpfulCount }
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Reviews & Ratings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            if (product != null && userId != null && myReviewChecked) {
                ExtendedFloatingActionButton(
                    onClick = { showComposer = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Outlined.RateReview, contentDescription = null) },
                    text = { Text(if (myReview != null) "Edit your review" else "Write a review") }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val currentProduct = product
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    loadAll()
                    refreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                productLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                currentProduct == null -> CenteredMessage(
                    icon = Icons.Outlined.SearchOff,
                    title = "Product not found",
                    message = "This product isn't available, or we couldn't reach the server.",
                    actionLabel = "Retry",
                    onAction = { scope.launch { loadAll() } },
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "product") { ProductHeader(currentProduct) }

                    item(key = "summary") { RatingSummary(reviews) }

                    myReview?.let { mine ->
                        item(key = "mine") {
                            MyReviewCard(mine, onEdit = { showComposer = true })
                        }
                    }

                    if (reviews.isNotEmpty()) {
                        item(key = "controls") {
                            ReviewControls(
                                sort = sort,
                                onSort = { sort = it },
                                starFilter = starFilter,
                                onStarFilter = { starFilter = it },
                                photosOnly = photosOnly,
                                onPhotosOnly = { photosOnly = it },
                                counts = (1..5).associateWith { s -> reviews.count { it.rating == s } },
                                hasPhotos = reviews.any { it.imageUrls.isNotEmpty() },
                            )
                        }
                    }

                    when {
                        !reviewsLoaded -> item(key = "loading") {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        reviews.isEmpty() && reviewsError != null -> item(key = "error") {
                            CenteredMessage(
                                icon = Icons.Outlined.CloudOff,
                                title = "Couldn't load reviews",
                                message = reviewsError.orEmpty(),
                                actionLabel = "Retry",
                                onAction = { scope.launch { loadReviews() } },
                                isError = true,
                                fillAndScroll = false
                            )
                        }

                        reviews.isEmpty() -> item(key = "empty") {
                            CenteredMessage(
                                icon = Icons.Outlined.ChatBubbleOutline,
                                title = "No reviews yet",
                                message = "Bought this tea? Share your thoughts once it's delivered.",
                                fillAndScroll = false
                            )
                        }

                        visibleReviews.isEmpty() -> item(key = "no-match") {
                            Text(
                                "No reviews match these filters.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }

                        else -> items(visibleReviews, key = { it.id }) { review ->
                            ReviewCard(
                                review = review,
                                isMine = userId != null && review.userId == userId,
                                myVote = myVotes[review.id],
                                canVote = userId != null && review.id !in votingIds,
                                onVote = { helpful -> vote(review, helpful) },
                                onPhotoClick = { viewerPhoto = it },
                            )
                        }
                    }
                }
            }
        }
    }

    val composerProduct = product
    if (showComposer && composerProduct != null && userId != null) {
        ReviewComposerSheet(
            product = composerProduct,
            userId = userId,
            existing = myReview,
            onDismiss = { showComposer = false },
            onSaved = { saved, message ->
                showComposer = false
                myReview = saved
                scope.launch {
                    snackbar.showSnackbar(message)
                }
                scope.launch {
                    loadReviews()
                    CatalogStore.refreshQuietly()
                    CatalogStore.productById(productId)?.let { product = it }
                }
            },
            onDeleted = {
                showComposer = false
                myReview = null
                scope.launch { snackbar.showSnackbar("Your review was deleted") }
                scope.launch {
                    loadReviews()
                    CatalogStore.refreshQuietly()
                    CatalogStore.productById(productId)?.let { product = it }
                }
            },
        )
    }

    viewerPhoto?.let { url ->
        Dialog(
            onDismissRequest = { viewerPhoto = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.92f))
                    .clickable(onClickLabel = "Close photo") { viewerPhoto = null },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = "Review photo",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentScale = ContentScale.Fit
                )
                FilledTonalIconButton(
                    onClick = { viewerPhoto = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(12.dp)
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close photo")
                }
            }
        }
    }
}

@Composable
private fun ProductHeader(product: Product) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OrderItemThumb(product.imageUrl, contentDescription = product.name, size = 72.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                product.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOf(product.category, product.size).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RatingSummary(reviews: List<Review>) {
    val total = reviews.size
    val average = if (total == 0) 0f else reviews.sumOf { it.rating }.toFloat() / total
    val averageText = if (total == 0) "–" else String.format(Locale.US, "%.1f", average)
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(96.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (total == 0) "No ratings yet"
                        else "Average rating $averageText out of 5 from $total reviews"
                    }
            ) {
                Text(
                    averageText,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                RatingBar(rating = average, starSize = 14.dp)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (total == 1) "1 review" else "$total reviews",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                for (star in 5 downTo 1) {
                    val count = reviews.count { it.rating == star }
                    val fraction = if (total > 0) count.toFloat() / total else 0f
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "$star star: $count"
                            }
                    ) {
                        Text("$star", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(12.dp))
                        Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(12.dp), tint = AccentGold)
                        Spacer(Modifier.width(8.dp))
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            drawStopIndicator = {},
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "$count",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(28.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewControls(
    sort: ReviewSort,
    onSort: (ReviewSort) -> Unit,
    starFilter: Int,
    onStarFilter: (Int) -> Unit,
    photosOnly: Boolean,
    onPhotosOnly: (Boolean) -> Unit,
    counts: Map<Int, Int>,
    hasPhotos: Boolean,
) {
    var sortMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Customer reviews",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Box {
                TextButton(onClick = { sortMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sort: ${sort.label}")
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    ReviewSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                onSort(option)
                                sortMenu = false
                            },
                            trailingIcon = if (option == sort) {
                                { Icon(Icons.Outlined.Check, contentDescription = "Selected") }
                            } else null
                        )
                    }
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(selected = starFilter == 0 && !photosOnly, onClick = {
                    onStarFilter(0); onPhotosOnly(false)
                }, label = { Text("All") })
            }
            if (hasPhotos) {
                item {
                    FilterChip(
                        selected = photosOnly,
                        onClick = { onPhotosOnly(!photosOnly) },
                        label = { Text("With photos") },
                        leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }
            items((5 downTo 1).toList()) { star ->
                FilterChip(
                    selected = starFilter == star,
                    onClick = { onStarFilter(if (starFilter == star) 0 else star) },
                    label = { Text("$star ★ (${counts[star] ?: 0})") },
                )
            }
        }
    }
}

@Composable
private fun MyReviewCard(review: Review, onEdit: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Your review",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onEdit) { Text("Edit") }
            }
            RatingBar(rating = review.rating.toFloat(), starSize = 16.dp)
            if (review.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    review.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!review.isVisible) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Your review is currently hidden by our moderators.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ReviewCard(
    review: Review,
    isMine: Boolean,
    myVote: Boolean?,
    canVote: Boolean,
    onVote: (Boolean) -> Unit,
    onPhotoClick: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) {}
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        review.authorInitial,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isMine) "${review.author} (you)" else review.author,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (review.isVerified) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Verified,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Verified buyer",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                Text(
                    review.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            Box(Modifier.semantics { contentDescription = "Rated ${review.rating} out of 5" }) {
                RatingBar(rating = review.rating.toFloat(), starSize = 14.dp)
            }

            if (review.text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    review.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (review.imageUrls.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(review.imageUrls) { index, url ->
                        AsyncImage(
                            model = url,
                            contentDescription = "Review photo ${index + 1}, tap to enlarge",
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable(role = Role.Image) { onPhotoClick(url) },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isMine) {
                    Text(
                        "${review.helpfulCount} found this helpful",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Helpful?",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    VoteButton(
                        selected = myVote == true,
                        count = review.helpfulCount,
                        helpful = true,
                        enabled = canVote,
                        onClick = { onVote(true) }
                    )
                    VoteButton(
                        selected = myVote == false,
                        count = review.unhelpfulCount,
                        helpful = false,
                        enabled = canVote,
                        onClick = { onVote(false) }
                    )
                }
            }
        }
    }
}

@Composable
private fun VoteButton(selected: Boolean, count: Int, helpful: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = if (helpful) "Helpful" else "Not helpful"
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics {
            contentDescription = "$label, $count"
            stateDescription = if (selected) "Selected" else "Not selected"
        },
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Icon(
            when {
                helpful && selected -> Icons.Filled.ThumbUp
                helpful -> Icons.Outlined.ThumbUp
                selected -> Icons.Filled.ThumbDown
                else -> Icons.Outlined.ThumbDown
            },
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text("$count", style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewComposerSheet(
    product: Product,
    userId: String,
    existing: Review?,
    onDismiss: () -> Unit,
    onSaved: (Review, String) -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var rating by rememberSaveable(existing?.id) { mutableIntStateOf(existing?.rating ?: 0) }
    var text by rememberSaveable(existing?.id) { mutableStateOf(existing?.text.orEmpty()) }
    // Stored newline-joined so it survives rotation with rememberSaveable.
    var photosRaw by rememberSaveable(existing?.id) { mutableStateOf(existing?.imageUrls.orEmpty().joinToString("\n")) }
    val photos = photosRaw.split("\n").filter { it.isNotBlank() }
    var uploading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val busy = submitting || uploading

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { if (!submitting) confirmDelete = false },
            title = { Text("Delete your review?") },
            text = { Text("Your rating and review for ${product.name} will be removed.") },
            confirmButton = {
                TextButton(
                    enabled = !submitting,
                    onClick = {
                        scope.launch {
                            submitting = true
                            try {
                                ReviewsRepository.delete(existing.id)
                                confirmDelete = false
                                onDeleted()
                            } catch (e: Exception) {
                                confirmDelete = false
                                error = e.userMessage("Couldn't delete your review.")
                            } finally {
                                submitting = false
                            }
                        }
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }, enabled = !submitting) { Text("Keep") } }
        )
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (existing != null) "Edit your review" else "Write a review",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OrderItemThumb(product.imageUrl, contentDescription = null, size = 44.dp)
                Spacer(Modifier.width(10.dp))
                Text(product.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            Text("Your rating", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.selectableGroup()) {
                for (star in 1..5) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .selectable(
                                selected = rating == star,
                                enabled = !busy,
                                role = Role.RadioButton,
                                onClick = { rating = star }
                            )
                            .semantics { contentDescription = "$star star${if (star > 1) "s" else ""}" },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (star <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = null,
                            tint = AccentGold,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
            if (attempted && rating == 0) {
                Text("Tap a star to rate", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_REVIEW_CHARS) },
                label = { Text("Your review (optional)") },
                placeholder = { Text("How was the flavour, aroma and freshness?") },
                supportingText = {
                    Text(
                        "${text.length}/$MAX_REVIEW_CHARS",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                },
                minLines = 4,
                maxLines = 8,
                enabled = !submitting,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth()
            )

            MultiImagePickerField(
                imageUrls = photos,
                onImageUrlsChange = { photosRaw = it.joinToString("\n") },
                folder = ImageStorageRepository.reviewFolder(userId),
                label = "Photos",
                maxImages = MAX_REVIEW_PHOTOS,
                enabled = !submitting,
                onUploadingChange = { uploading = it },
            )

            error?.let {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Button(
                onClick = {
                    attempted = true
                    if (rating == 0) return@Button
                    scope.launch {
                        submitting = true
                        error = null
                        try {
                            val saved = if (existing != null) {
                                ReviewsRepository.updateMine(existing.id, rating, text, photos)
                            } else {
                                ReviewsRepository.create(product.id, userId, rating, text, photos)
                            }
                            onSaved(saved, if (existing != null) "Review updated" else "Thanks for your review!")
                        } catch (e: Exception) {
                            error = e.userMessage("Couldn't save your review. Please try again.")
                        } finally {
                            submitting = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        uploading -> "Uploading photos…"
                        existing != null -> "Update review"
                        else -> "Submit review"
                    }
                )
            }
            if (existing != null) {
                TextButton(
                    onClick = { confirmDelete = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete review")
                }
            }
        }
    }
}
