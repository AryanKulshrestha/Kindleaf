package com.kindleaf.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.kindleaf.data.AppStateManager
import com.kindleaf.data.CatalogStore
import com.kindleaf.data.remote.CouponsRepository
import com.kindleaf.models.Address
import com.kindleaf.models.CartQuote
import com.kindleaf.models.PaymentMethod
import com.kindleaf.notifications.OrderNotificationHelper
import com.kindleaf.ui.checkout.CheckoutViewModel
import com.kindleaf.ui.checkout.PlaceOrderResult
import com.kindleaf.ui.theme.PrimaryGreen

/**
 * Checkout: address → items → payment method → coupon → Leaf Points → server-computed total.
 * COD orders go straight to confirmation; online orders continue to [PaymentScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    onBack: () -> Unit,
    onOrderPlaced: (orderId: String) -> Unit,
    onPaymentRequired: (orderId: String) -> Unit,
    onAddAddress: () -> Unit,
    viewModel: CheckoutViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cartItems = AppStateManager.cartItems
    val addresses = AppStateManager.addresses
    val profile by AppStateManager.userProfile
    val quote = state.quote

    LaunchedEffect(addresses.toList()) { viewModel.syncAddresses(addresses.toList()) }

    val selectedAddress = addresses.find { it.id == state.selectedAddressId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Checkout", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            CheckoutBottomBar(
                total = quote?.total,
                loading = state.quoteLoading,
                placing = state.placing,
                enabled = state.canPlaceOrder,
                paymentMethod = state.paymentMethod,
                error = state.placeError,
                hint = when {
                    cartItems.isEmpty() -> "Your cart is empty"
                    selectedAddress == null -> "Add a delivery address to continue"
                    quote?.errors?.isNotEmpty() == true -> quote.errors.first()
                    else -> null
                },
                onPlace = {
                    val address = selectedAddress ?: return@CheckoutBottomBar
                    viewModel.placeOrder(address) { result ->
                        when (result) {
                            is PlaceOrderResult.Placed -> {
                                OrderNotificationHelper.showOrderPlaced(context, result.order.id)
                                onOrderPlaced(result.order.id)
                            }
                            is PlaceOrderResult.NeedsPayment -> onPaymentRequired(result.order.id)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
        ) {
            // ── Delivery address ──
            SectionHeader("Delivery address", Icons.Outlined.LocationOn)
            CheckoutCard {
                if (addresses.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "No addresses saved yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onAddAddress, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Add address")
                        }
                    }
                } else {
                    Column(modifier = Modifier.animateContentSize()) {
                        addresses.forEachIndexed { index, address ->
                            AddressOption(
                                address = address,
                                selected = address.id == state.selectedAddressId,
                                onSelect = { viewModel.selectAddress(address) }
                            )
                            if (index < addresses.lastIndex) CardDivider()
                        }
                        CardDivider()
                        TextButton(
                            onClick = onAddAddress,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, tint = PrimaryGreen)
                            Spacer(Modifier.width(6.dp))
                            Text("Add a new address", color = PrimaryGreen)
                        }
                    }
                }
            }

            // ── Items ──
            SectionHeader("Order summary (${cartItems.sumOf { it.quantity }} items)", Icons.Outlined.Inventory2)
            CheckoutCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    cartItems.forEach { item ->
                        val serverLine = quote?.lines?.find { it.itemId == item.product.id }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = item.product.imageUrl,
                                contentDescription = item.product.name,
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.product.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "Qty ${item.quantity} × ₹${serverLine?.price ?: item.product.price}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "₹${serverLine?.lineTotal ?: (item.product.price * item.quantity)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    quote?.errors?.forEach { message ->
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // ── Payment method ──
            SectionHeader("Payment method", Icons.Outlined.Payments)
            CheckoutCard {
                Column {
                    val onlineEnabled = quote?.onlineEnabled ?: true
                    val codEnabled = quote?.codEnabled ?: true
                    PaymentOption(
                        title = "Pay online",
                        subtitle = "UPI, cards, netbanking & wallets · Secured by Razorpay",
                        icon = Icons.Outlined.QrCode2,
                        selected = state.paymentMethod == PaymentMethod.ONLINE,
                        enabled = onlineEnabled,
                        disabledReason = "Online payment is currently unavailable",
                        onSelect = { viewModel.selectPayment(PaymentMethod.ONLINE) }
                    )
                    CardDivider()
                    PaymentOption(
                        title = "Cash on Delivery",
                        subtitle = "Pay with cash or UPI when your order arrives",
                        icon = Icons.Outlined.Money,
                        selected = state.paymentMethod == PaymentMethod.COD,
                        enabled = codEnabled,
                        disabledReason = "Cash on Delivery is currently unavailable",
                        onSelect = { viewModel.selectPayment(PaymentMethod.COD) }
                    )
                }
            }

            // ── Coupon ──
            SectionHeader("Coupon", Icons.Outlined.LocalOffer)
            CheckoutCard {
                Column(
                    modifier = Modifier.padding(16.dp).animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val applied = state.appliedCoupon
                    if (applied != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = PrimaryGreen)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "$applied applied · you save ₹${quote?.couponDiscount ?: 0}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = PrimaryGreen,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = viewModel::removeCoupon) {
                                Text("Remove", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = state.couponInput,
                            onValueChange = viewModel::onCouponInput,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Enter coupon code") },
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            isError = state.couponError != null,
                            supportingText = state.couponError?.let { { Text(it) } },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { viewModel.applyCoupon() }),
                            trailingIcon = {
                                if (state.couponChecking) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    TextButton(
                                        onClick = { viewModel.applyCoupon() },
                                        enabled = state.couponInput.isNotBlank()
                                    ) { Text("Apply", color = PrimaryGreen) }
                                }
                            }
                        )
                        val available = CatalogStore.coupons
                        if (available.isNotEmpty()) {
                            Text(
                                "Available offers",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            available.forEach { coupon ->
                                val rules = CouponsRepository.rulesSummary(coupon)
                                Surface(
                                    onClick = { viewModel.applyCoupon(coupon.code) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                        Text(
                                            "${coupon.code} · ${coupon.headline}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        val detail = listOf(coupon.description, rules).filter { it.isNotBlank() }.joinToString(" · ")
                                        if (detail.isNotBlank()) {
                                            Text(
                                                detail,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Leaf Points ──
            if (profile.leafPoints > 0) {
                SectionHeader("Leaf Points", Icons.Outlined.Eco)
                CheckoutCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Use Leaf Points",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            val detail = when {
                                state.usePoints && quote != null && quote.pointsApplied > 0 ->
                                    "Using ${quote.pointsApplied} of ${quote.pointsBalance} points · -₹${quote.pointsDiscount}"
                                quote != null && quote.pointsMax < 10 ->
                                    "Not enough order value to redeem points"
                                else ->
                                    "Balance ${profile.leafPoints} points (worth ₹${profile.leafPoints / 10})"
                            }
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.usePoints,
                            onCheckedChange = viewModel::setUsePoints,
                            enabled = quote == null || quote.pointsMax >= 10 || state.usePoints,
                            colors = SwitchDefaults.colors(checkedTrackColor = PrimaryGreen)
                        )
                    }
                }
            }

            // ── Price details (server computed) ──
            SectionHeader("Price details", Icons.Outlined.ReceiptLong)
            CheckoutCard {
                PriceDetails(
                    quote = quote,
                    loading = state.quoteLoading,
                    error = state.quoteError,
                    onRetry = viewModel::refreshQuote
                )
            }
        }
    }
}

@Composable
private fun CheckoutBottomBar(
    total: Int?,
    loading: Boolean,
    placing: Boolean,
    enabled: Boolean,
    paymentMethod: PaymentMethod,
    error: String?,
    hint: String?,
    onPlace: () -> Unit,
) {
    Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.navigationBarsPadding().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (total == null || loading) "…" else "₹$total",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            val message = error ?: hint
            if (message != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onPlace,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen),
                enabled = enabled
            ) {
                if (placing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    val label = when {
                        total == 0 -> "Place order"
                        paymentMethod == PaymentMethod.ONLINE -> "Continue to payment"
                        else -> "Place order"
                    }
                    Text(
                        if (total != null && total > 0 && !loading) "$label · ₹$total" else label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun PriceDetails(quote: CartQuote?, loading: Boolean, error: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            error != null && quote == null -> {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(12.dp)) { Text("Retry") }
            }
            quote == null -> {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            else -> {
                PriceRow("Subtotal", "₹${quote.subtotal}")
                quote.charges.forEach { charge ->
                    val value = if (charge.amount == 0) "FREE" else "₹${charge.amount}"
                    PriceRow(charge.name, value, highlight = charge.amount == 0)
                    if (charge.key == "delivery" && charge.amount > 0 && charge.freeAbove != null) {
                        Text(
                            "Add ₹${charge.freeAbove - quote.subtotal} more for free delivery",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (quote.couponDiscount > 0) {
                    PriceRow("Coupon (${quote.couponCode})", "-₹${quote.couponDiscount}", highlight = true)
                }
                if (quote.pointsDiscount > 0) {
                    PriceRow("Leaf Points (${quote.pointsApplied} pts)", "-₹${quote.pointsDiscount}", highlight = true)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                PriceRow("Total", "₹${quote.total}", bold = true)
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PriceRow(label: String, value: String, bold: Boolean = false, highlight: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (bold) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            color = if (highlight) PrimaryGreen else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PaymentOption(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    disabledReason: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (selected) PrimaryGreen else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            Text(
                if (enabled) subtitle else disabledReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = PrimaryGreen)
        )
    }
}

@Composable
private fun AddressOption(address: Address, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = PrimaryGreen)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(address.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                AssistChip(onClick = onSelect, label = { Text(address.label) }, modifier = Modifier.height(28.dp))
            }
            Text(
                "${address.addressLine}, ${address.city}, ${address.state} - ${address.pincode}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Phone: ${address.phone}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = PrimaryGreen, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CheckoutCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) { content() }
}

@Composable
private fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
    )
}
