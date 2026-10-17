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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kindleaf.data.CatalogStore
import com.kindleaf.data.remote.CouponWriteDto
import com.kindleaf.data.remote.CouponsRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.Coupon
import com.kindleaf.models.CouponType
import kotlinx.coroutines.launch
import java.time.LocalDate

private fun CouponType.label(): String = when (this) {
    CouponType.DISCOUNT -> "Discount"
    CouponType.SHIPPING -> "Free shipping"
    CouponType.SPECIAL -> "Special"
}

private fun Coupon.valueLabel(): String = when {
    type == CouponType.SHIPPING -> "Free delivery"
    fixedAmount > 0 -> "${rupees(fixedAmount)} off"
    discountPercent > 0 -> "$discountPercent% off"
    else -> "No discount set"
}

private fun Coupon.isExpired(): Boolean =
    parseIsoDate(validUntil)?.isBefore(LocalDate.now()) == true

private fun Coupon.notStarted(): Boolean =
    parseIsoDate(startsAt)?.isAfter(LocalDate.now()) == true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminCouponsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var coupons by remember { mutableStateOf<List<Coupon>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var pendingToggle by remember { mutableStateOf<Set<String>>(emptySet()) }

    var editorFor by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Coupon?>(null) }
    var deleteBusy by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        if (!refreshing) loading = true
        error = null
        attempt { CouponsRepository.fetchAll() }
            .onSuccess { coupons = it }
            .onFailure { error = it.userMessage("Couldn't load coupons.") }
        loading = false
        refreshing = false
    }

    Scaffold(
        topBar = {
            AdminTopBar(
                title = "Coupons",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { editorFor = "" }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add coupon")
                    }
                }
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
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    loading -> item { AdminLoading(Modifier.fillParentMaxSize()) }
                    error != null && coupons.isEmpty() -> item {
                        AdminErrorState(error.orEmpty(), onRetry = { reloadKey++ }, modifier = Modifier.fillParentMaxSize())
                    }
                    coupons.isEmpty() -> item {
                        AdminEmptyState(
                            "No coupons yet. Tap + to create one.",
                            icon = Icons.Outlined.LocalOffer,
                            modifier = Modifier.fillParentMaxSize()
                        )
                    }
                    else -> {
                        (actionError ?: error)?.let { msg -> item { AdminInlineError(msg) } }
                        items(coupons, key = { it.id }) { coupon ->
                            CouponRow(
                                coupon = coupon,
                                toggling = coupon.id in pendingToggle,
                                onEdit = { editorFor = coupon.id },
                                onDelete = { deleteError = null; deleting = coupon },
                                onToggle = { active ->
                                    val previous = coupons
                                    coupons = coupons.map { if (it.id == coupon.id) it.copy(isActive = active) else it }
                                    pendingToggle = pendingToggle + coupon.id
                                    scope.launch {
                                        attempt { CouponsRepository.setActive(coupon.id, active) }
                                            .onSuccess { saved ->
                                                coupons = coupons.map { if (it.id == saved.id) saved else it }
                                                actionError = null
                                                CatalogStore.refreshCoupons()
                                            }
                                            .onFailure { e ->
                                                coupons = previous
                                                actionError = e.userMessage("Couldn't update ${coupon.code}.")
                                            }
                                        pendingToggle = pendingToggle - coupon.id
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    editorFor?.let { id ->
        CouponEditorDialog(
            existing = coupons.firstOrNull { it.id == id },
            onDismiss = { editorFor = null },
            onSaved = {
                editorFor = null
                reloadKey++
                scope.launch { CatalogStore.refreshCoupons() }
            }
        )
    }

    deleting?.let { coupon ->
        AdminConfirmDialog(
            title = "Delete coupon ${coupon.code}?",
            message = "Customers will no longer be able to use this code. To pause it instead, switch it off.",
            confirmLabel = "Delete",
            busy = deleteBusy,
            error = deleteError,
            onDismiss = { if (!deleteBusy) deleting = null },
            onConfirm = {
                scope.launch {
                    deleteBusy = true
                    deleteError = null
                    attempt { CouponsRepository.delete(coupon.id) }
                        .onSuccess {
                            coupons = coupons.filterNot { it.id == coupon.id }
                            deleting = null
                            CatalogStore.refreshCoupons()
                        }
                        .onFailure { deleteError = it.userMessage("Couldn't delete the coupon.") }
                    deleteBusy = false
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CouponRow(
    coupon: Coupon,
    toggling: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Edit ${coupon.code}", onClick = onEdit),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(coupon.code, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    if (coupon.headline.isNotBlank()) Text(coupon.headline, style = MaterialTheme.typography.bodyMedium)
                }
                if (toggling) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Switch(
                        checked = coupon.isActive,
                        onCheckedChange = onToggle,
                        modifier = Modifier.semantics { contentDescription = "${coupon.code} active" }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AdminBadge(coupon.type.label(), AdminTone.Info)
                AdminBadge(coupon.valueLabel(), AdminTone.Success)
                when {
                    !coupon.isActive -> AdminBadge("Off", AdminTone.Neutral)
                    coupon.isExpired() -> AdminBadge("Expired", AdminTone.Error)
                    coupon.notStarted() -> AdminBadge("Scheduled", AdminTone.Warning)
                }
            }
            Spacer(Modifier.height(6.dp))
            val validity = buildList {
                coupon.startsAt?.let { displayIsoDate(it) }?.takeIf { it.isNotBlank() }?.let { add("From $it") }
                add(displayIsoDate(coupon.validUntil).takeIf { it.isNotBlank() }?.let { "Until $it" } ?: "No end date")
                coupon.usageLimit?.let { add("$it total uses") }
            }.joinToString(" · ")
            Text(validity, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val rules = CouponsRepository.rulesSummary(coupon)
            if (rules.isNotBlank()) {
                Text(rules, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onEdit) { Text("Edit") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete ${coupon.code}")
                }
            }
        }
    }
}

private enum class DiscountMode { PERCENT, FIXED }

@Composable
private fun CouponEditorDialog(
    existing: Coupon?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var type by rememberSaveable { mutableStateOf(existing?.type ?: CouponType.DISCOUNT) }
    var code by rememberSaveable { mutableStateOf(existing?.code.orEmpty()) }
    var headline by rememberSaveable { mutableStateOf(existing?.headline.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(existing?.description.orEmpty()) }
    var mode by rememberSaveable {
        mutableStateOf(if ((existing?.fixedAmount ?: 0) > 0) DiscountMode.FIXED else DiscountMode.PERCENT)
    }
    var percent by rememberSaveable { mutableStateOf(existing?.discountPercent?.takeIf { it > 0 }?.toString().orEmpty()) }
    var fixed by rememberSaveable { mutableStateOf(existing?.fixedAmount?.takeIf { it > 0 }?.toString().orEmpty()) }
    var minOrder by rememberSaveable { mutableStateOf(existing?.minOrder?.takeIf { it > 0 }?.toString().orEmpty()) }
    var maxDiscount by rememberSaveable { mutableStateOf(existing?.maxDiscount?.toString().orEmpty()) }
    var startsAt by rememberSaveable { mutableStateOf(existing?.startsAt?.take(10).orEmpty()) }
    var validUntil by rememberSaveable { mutableStateOf(existing?.validUntil?.take(10).orEmpty()) }
    var usageLimit by rememberSaveable { mutableStateOf(existing?.usageLimit?.toString().orEmpty()) }
    var perUserLimit by rememberSaveable { mutableStateOf(existing?.perUserLimit?.toString().orEmpty()) }
    var isActive by rememberSaveable { mutableStateOf(existing?.isActive ?: true) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val hasValue = type != CouponType.SHIPPING
    val codeError = when {
        code.isBlank() -> "Code is required"
        code.length < 3 -> "At least 3 characters"
        !code.all { it.isLetterOrDigit() } -> "Letters and numbers only"
        else -> null
    }
    val headlineError = if (headline.isBlank()) "Headline is required" else null
    val percentError = if (hasValue && mode == DiscountMode.PERCENT) {
        val v = percent.toIntOrNull()
        if (v == null || v !in 1..100) "Enter 1–100" else null
    } else null
    val fixedError = if (hasValue && mode == DiscountMode.FIXED) {
        val v = fixed.toIntOrNull()
        if (v == null || v <= 0) "Enter an amount above ₹0" else null
    } else null
    val maxDiscountError = if (maxDiscount.isNotBlank() && (maxDiscount.toIntOrNull() ?: 0) <= 0) "Enter an amount above ₹0" else null
    val start = parseIsoDate(startsAt)
    val end = parseIsoDate(validUntil)
    val validUntilError = when {
        end == null -> "Choose an end date"
        start != null && end.isBefore(start) -> "Must be on or after the start date"
        else -> null
    }
    val usageError = if (usageLimit.isNotBlank() && (usageLimit.toIntOrNull() ?: 0) <= 0) "Must be at least 1" else null
    val perUserError = if (perUserLimit.isNotBlank() && (perUserLimit.toIntOrNull() ?: 0) <= 0) "Must be at least 1" else null
    val hasErrors = listOf(codeError, headlineError, percentError, fixedError, maxDiscountError, validUntilError, usageError, perUserError)
        .any { it != null }

    AdminConfirmDialog(
        title = if (existing == null) "New coupon" else "Edit ${existing.code}",
        message = "Customers enter the code at checkout. The server checks every rule.",
        confirmLabel = "Save",
        destructive = false,
        busy = saving,
        error = error ?: if (showErrors && hasErrors) "Fix the highlighted fields" else null,
        onDismiss = { if (!saving) onDismiss() },
        onConfirm = {
            showErrors = true
            if (hasErrors) return@AdminConfirmDialog
            scope.launch {
                saving = true
                error = null
                val dto = CouponWriteDto(
                    type = type.name,
                    headline = headline.trim(),
                    description = description.trim(),
                    code = code.trim().uppercase(),
                    validUntil = validUntil.ifBlank { null },
                    discountPercent = if (hasValue && mode == DiscountMode.PERCENT) percent.toIntOrNull() ?: 0 else 0,
                    isActive = isActive,
                    minOrder = minOrder.toIntOrNull() ?: 0,
                    maxDiscount = if (hasValue && mode == DiscountMode.PERCENT) maxDiscount.toIntOrNull() else null,
                    fixedAmount = if (hasValue && mode == DiscountMode.FIXED) fixed.toIntOrNull() ?: 0 else 0,
                    startsAt = startsAt.ifBlank { null },
                    usageLimit = usageLimit.toIntOrNull(),
                    perUserLimit = perUserLimit.toIntOrNull(),
                )
                attempt {
                    if (existing == null) CouponsRepository.create(dto) else CouponsRepository.update(existing.id, dto)
                }.onSuccess {
                    saving = false
                    onSaved()
                }.onFailure {
                    error = it.userMessage("Couldn't save the coupon.")
                    saving = false
                }
            }
        },
        extraContent = {
            Text("Type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                CouponType.entries.forEachIndexed { index, t ->
                    SegmentedButton(
                        selected = type == t,
                        onClick = { type = t },
                        shape = SegmentedButtonDefaults.itemShape(index, CouponType.entries.size),
                        label = { Text(if (t == CouponType.SHIPPING) "Shipping" else t.label(), maxLines = 1) }
                    )
                }
            }
            CouponField(
                value = code,
                onValueChange = { code = it.uppercase().filter { c -> !c.isWhitespace() }.take(20) },
                label = "Code *",
                error = codeError,
                showErrors = showErrors,
                capitalization = KeyboardCapitalization.Characters,
            )
            CouponField(headline, { headline = it.take(80) }, "Headline *", headlineError, showErrors, capitalization = KeyboardCapitalization.Sentences)
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(300) },
                label = { Text("Description") },
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth()
            )
            if (hasValue) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = mode == DiscountMode.PERCENT,
                        onClick = { mode = DiscountMode.PERCENT },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        label = { Text("Percent %") }
                    )
                    SegmentedButton(
                        selected = mode == DiscountMode.FIXED,
                        onClick = { mode = DiscountMode.FIXED },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        label = { Text("Fixed ₹") }
                    )
                }
                if (mode == DiscountMode.PERCENT) {
                    CouponField(percent, { percent = it.filter(Char::isDigit).take(3) }, "Discount % *", percentError, showErrors, number = true)
                    CouponField(
                        maxDiscount, { maxDiscount = it.filter(Char::isDigit).take(6) }, "Max discount ₹ (optional)",
                        maxDiscountError, true, number = true
                    )
                } else {
                    CouponField(fixed, { fixed = it.filter(Char::isDigit).take(6) }, "Amount off ₹ *", fixedError, showErrors, number = true)
                }
            } else {
                Text(
                    "Free shipping removes the delivery fee. No discount amount is needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CouponField(minOrder, { minOrder = it.filter(Char::isDigit).take(6) }, "Minimum order ₹ (optional)", null, showErrors, number = true)
            AdminDateField(label = "Starts on (optional)", value = startsAt, onValueChange = { startsAt = it })
            AdminDateField(
                label = "Valid until *",
                value = validUntil,
                onValueChange = { validUntil = it },
                clearable = false,
                isError = showErrors && validUntilError != null,
                supportingText = if (showErrors) validUntilError else null,
            )
            CouponField(usageLimit, { usageLimit = it.filter(Char::isDigit).take(6) }, "Total uses (optional)", usageError, true, number = true)
            CouponField(perUserLimit, { perUserLimit = it.filter(Char::isDigit).take(3) }, "Uses per customer (optional)", perUserError, true, number = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Active", modifier = Modifier.weight(1f))
                Switch(checked = isActive, onCheckedChange = { isActive = it })
            }
        }
    )
}

@Composable
private fun CouponField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    showErrors: Boolean,
    number: Boolean = false,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
) {
    val show = showErrors && error != null
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = show,
        supportingText = if (show) ({ Text(error.orEmpty()) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
            capitalization = capitalization,
            imeAction = ImeAction.Next
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
