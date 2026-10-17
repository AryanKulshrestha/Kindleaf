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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kindleaf.data.AppStateManager
import com.kindleaf.data.CartItem
import com.kindleaf.data.CatalogStore
import com.kindleaf.data.ChargesStore
import com.kindleaf.models.Product
import com.kindleaf.ui.catalog.EmptyState
import kotlinx.coroutines.launch

/** A cart line joined with the freshest known copy of its product. */
private data class CartLine(
    val item: CartItem,
    val product: Product,
    /** Product no longer in the active catalog. */
    val unavailable: Boolean,
    val maxQty: Int,
) {
    val outOfStock: Boolean get() = unavailable || !product.isInStock
    val overStock: Boolean get() = !outOfStock && item.quantity > maxQty
    val blocksCheckout: Boolean get() = outOfStock || overStock
    val lineTotal: Int get() = product.price * item.quantity
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartScreen(
    onBack: () -> Unit,
    onCheckout: () -> Unit,
    onProductClick: (String) -> Unit,
    onStartShopping: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val cartItems = AppStateManager.cartItems

    // Refresh stock/prices so warnings reflect the server, not the copy saved in the cart.
    LaunchedEffect(Unit) {
        CatalogStore.refreshQuietly()
        if (ChargesStore.charges.isEmpty()) ChargesStore.refresh()
    }

    val catalogLoaded = CatalogStore.products.isNotEmpty()
    val lines = cartItems.map { item ->
        val fresh = CatalogStore.productById(item.product.id)
        val product = fresh ?: item.product
        CartLine(
            item = item,
            product = product,
            unavailable = catalogLoaded && fresh == null,
            maxQty = AppStateManager.maxQuantityFor(product)
        )
    }
    val subtotal = lines.sumOf { it.lineTotal }
    val chargeLines = ChargesStore.appliedLines(subtotal).ifEmpty {
        ChargesStore.deliveryCharge()?.let { listOf(it to it.amountFor(subtotal)) } ?: emptyList()
    }
    val chargesTotal = if (chargeLines.isEmpty()) ChargesStore.deliveryFeeFor(subtotal) else chargeLines.sumOf { it.second }
    val total = subtotal + chargesTotal
    val blocked = lines.filter { it.blocksCheckout }
    val itemCount = cartItems.sumOf { it.quantity }

    fun removeWithUndo(line: CartLine) {
        val removed = line.item
        AppStateManager.removeFromCart(removed.product.id)
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = "Removed ${removed.product.name}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                AppStateManager.addToCart(line.product, removed.quantity)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "My Cart",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (itemCount > 0) {
                            Text(
                                if (itemCount == 1) "1 item" else "$itemCount items",
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (lines.isNotEmpty()) {
                CartSummary(
                    subtotal = subtotal,
                    chargeLines = chargeLines.map { (charge, amount) ->
                        val label = if (charge.key.equals("delivery", ignoreCase = true)) "Delivery" else charge.name
                        Triple("$label (estimated)", amount, charge.freeAbove)
                    }.ifEmpty { listOf(Triple("Delivery (estimated)", chargesTotal, null)) },
                    total = total,
                    blockedCount = blocked.size,
                    onCheckout = onCheckout
                )
            }
        }
    ) { padding ->
        if (lines.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.Outlined.ShoppingCart,
                    title = "Your cart is empty",
                    subtitle = "Add some teas to get started.",
                    actionLabel = "Start shopping",
                    onAction = onStartShopping
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(lines, key = { it.item.product.id }) { line ->
                    CartItemCard(
                        line = line,
                        onClick = { onProductClick(line.product.id) },
                        onQuantityChange = { AppStateManager.updateCartQuantity(line.product.id, it) },
                        // addToCart also swaps in the fresh product, so the stock cap is current.
                        onIncrease = { AppStateManager.addToCart(line.product, 1) },
                        onRemove = { removeWithUndo(line) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CartSummary(
    subtotal: Int,
    chargeLines: List<Triple<String, Int, Int?>>,
    total: Int,
    blockedCount: Int,
    onCheckout: () -> Unit,
) {
    Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.padding(16.dp)) {
            SummaryRow("Subtotal", "₹$subtotal")
            chargeLines.forEach { (label, amount, freeAbove) ->
                Spacer(Modifier.height(4.dp))
                SummaryRow(label, if (amount == 0) "FREE" else "₹$amount", highlight = amount == 0)
                if (amount > 0 && freeAbove != null && subtotal < freeAbove) {
                    Text(
                        "Add ₹${freeAbove - subtotal} more for free ${label.substringBefore(" (").lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Estimated total",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "₹$total",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                "Coupons and Leaf Points are applied at checkout, where the final amount is confirmed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (blockedCount > 0) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (blockedCount == 1) "1 item needs attention before checkout. Remove it or reduce the quantity."
                        else "$blockedCount items need attention before checkout. Remove them or reduce the quantity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onCheckout,
                enabled = blockedCount == 0,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text("Proceed to checkout", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, highlight: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CartItemCard(
    line: CartLine,
    onClick: () -> Unit,
    onQuantityChange: (Int) -> Unit,
    onIncrease: () -> Unit,
    onRemove: () -> Unit,
) {
    val product = line.product
    val quantity = line.item.quantity
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
                    .size(80.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
                alpha = if (line.outOfStock) 0.5f else 1f
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = listOf(product.size, "₹${product.price} each").filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (line.item.product.price != product.price) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "₹${line.item.product.price}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textDecoration = TextDecoration.LineThrough
                        )
                    }
                }

                val warning = when {
                    line.unavailable -> "No longer available — please remove it"
                    !product.isInStock -> "Out of stock — please remove it"
                    line.overStock -> "Only ${line.maxQty} available — reduce the quantity"
                    product.isLowStock -> "Only ${product.stockQty} left in stock"
                    else -> null
                }
                if (warning != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (line.blocksCheckout) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (line.overStock) {
                    TextButton(onClick = { onQuantityChange(line.maxQty) }) {
                        Text("Set quantity to ${line.maxQty}")
                    }
                }

                if (!line.outOfStock) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedIconButton(
                            onClick = { onQuantityChange(quantity - 1) },
                            enabled = quantity > 1,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.Remove, contentDescription = "Decrease quantity of ${product.name}")
                        }
                        Text(
                            "$quantity",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .widthIn(min = 32.dp)
                                .clearAndSetSemantics { contentDescription = "Quantity $quantity" }
                        )
                        OutlinedIconButton(
                            onClick = onIncrease,
                            enabled = quantity < line.maxQty,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Increase quantity of ${product.name}")
                        }
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Remove ${product.name} from cart",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "₹${line.lineTotal}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 8.dp, top = 8.dp)
                )
            }
        }
    }
}
