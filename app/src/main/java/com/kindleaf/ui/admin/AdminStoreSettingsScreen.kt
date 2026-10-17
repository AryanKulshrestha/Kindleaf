package com.kindleaf.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kindleaf.data.StoreSettingsStore
import com.kindleaf.data.remote.StoreSettingsRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.StoreSettings
import kotlinx.coroutines.launch

private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
private val PHONE_PATTERN = Regex("^\\+?[0-9 ]{8,16}$")

private fun rangeError(value: String, range: IntRange): String? {
    val v = value.toIntOrNull()
    return if (v == null || v !in range) "Enter ${range.first}–${range.last}" else null
}

private fun urlError(value: String): String? =
    if (value.isNotBlank() && !value.trim().startsWith("https://")) "Must start with https://" else null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminStoreSettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var codEnabled by rememberSaveable { mutableStateOf(true) }
    var onlineEnabled by rememberSaveable { mutableStateOf(true) }
    var earnPercent by rememberSaveable { mutableStateOf("") }
    var redeemPercent by rememberSaveable { mutableStateOf("") }
    var returnDays by rememberSaveable { mutableStateOf("") }
    var expiryMinutes by rememberSaveable { mutableStateOf("") }
    var maxQty by rememberSaveable { mutableStateOf("") }
    var supportEmail by rememberSaveable { mutableStateOf("") }
    var supportPhone by rememberSaveable { mutableStateOf("") }
    var supportWhatsapp by rememberSaveable { mutableStateOf("") }
    var privacyUrl by rememberSaveable { mutableStateOf("") }
    var termsUrl by rememberSaveable { mutableStateOf("") }
    var populated by rememberSaveable { mutableStateOf(false) }

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showErrors by rememberSaveable { mutableStateOf(false) }

    fun fill(s: StoreSettings) {
        codEnabled = s.codEnabled
        onlineEnabled = s.onlinePaymentsEnabled
        earnPercent = s.pointsEarnPercent.toString()
        redeemPercent = s.pointsRedeemMaxPercent.toString()
        returnDays = s.returnWindowDays.toString()
        expiryMinutes = s.unpaidOrderExpiryMinutes.toString()
        maxQty = s.maxQuantityPerItem.toString()
        supportEmail = s.supportEmail
        supportPhone = s.supportPhone
        supportWhatsapp = s.supportWhatsapp
        privacyUrl = s.privacyPolicyUrl
        termsUrl = s.termsUrl
        populated = true
    }

    LaunchedEffect(reloadKey) {
        if (populated && reloadKey == 0) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        // Read straight from the server so a failure is visible, then keep the shared cache in sync.
        attempt { StoreSettingsRepository.fetch() }
            .onSuccess { fresh ->
                fill(fresh)
                StoreSettingsStore.refresh()
            }
            .onFailure { loadError = it.userMessage("Couldn't load store settings.") }
        loading = false
    }

    val earnError = rangeError(earnPercent, 0..100)
    val redeemError = rangeError(redeemPercent, 0..100)
    val returnError = rangeError(returnDays, 0..90)
    val expiryError = rangeError(expiryMinutes, 5..1440)
    val maxQtyError = rangeError(maxQty, 1..999)
    val emailError = if (supportEmail.isNotBlank() && !EMAIL_PATTERN.matches(supportEmail.trim())) "Enter a valid email" else null
    val phoneError = if (supportPhone.isNotBlank() && !PHONE_PATTERN.matches(supportPhone.trim())) "Enter a valid phone number" else null
    val whatsappError = if (supportWhatsapp.isNotBlank() && !PHONE_PATTERN.matches(supportWhatsapp.trim())) "Enter a valid number with country code" else null
    val privacyError = urlError(privacyUrl)
    val termsError = urlError(termsUrl)
    val paymentError = if (!codEnabled && !onlineEnabled) "Keep at least one payment method on, or customers can't order" else null
    val hasErrors = listOf(
        earnError, redeemError, returnError, expiryError, maxQtyError,
        emailError, phoneError, whatsappError, privacyError, termsError, paymentError
    ).any { it != null }

    fun save() {
        showErrors = true
        if (hasErrors) {
            saveError = "Fix the highlighted fields"
            return
        }
        scope.launch {
            saving = true
            saveError = null
            val updated = StoreSettings(
                pointsEarnPercent = earnPercent.toInt(),
                pointsRedeemMaxPercent = redeemPercent.toInt(),
                returnWindowDays = returnDays.toInt(),
                unpaidOrderExpiryMinutes = expiryMinutes.toInt(),
                maxQuantityPerItem = maxQty.toInt(),
                codEnabled = codEnabled,
                onlinePaymentsEnabled = onlineEnabled,
                supportEmail = supportEmail.trim(),
                supportPhone = supportPhone.trim(),
                supportWhatsapp = supportWhatsapp.trim(),
                privacyPolicyUrl = privacyUrl.trim(),
                termsUrl = termsUrl.trim(),
            )
            attempt { StoreSettingsStore.save(updated) }
                .onSuccess { saved ->
                    fill(saved)
                    showErrors = false
                    saving = false
                    snackbar.showSnackbar("Store settings saved")
                }
                .onFailure {
                    saveError = it.userMessage("Couldn't save settings.")
                    saving = false
                }
        }
    }

    Scaffold(
        topBar = { AdminTopBar(title = "Store settings", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        when {
            loading && !populated -> AdminLoading(Modifier.padding(padding))
            loadError != null && !populated -> AdminErrorState(
                loadError.orEmpty(),
                onRetry = { reloadKey++ },
                modifier = Modifier.padding(padding)
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsSection("Payments") {
                    SwitchRow("Cash on Delivery", "Customers can pay when the order arrives", codEnabled) { codEnabled = it }
                    SwitchRow("Online payments", "UPI, cards, netbanking and wallets via Razorpay", onlineEnabled) { onlineEnabled = it }
                    if (showErrors && paymentError != null) {
                        Text(paymentError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    NumberField(
                        value = expiryMinutes,
                        onValueChange = { expiryMinutes = it },
                        label = "Unpaid order expiry (minutes)",
                        help = "Online orders not paid within this time are cancelled (5–1440)",
                        error = expiryError,
                        showErrors = showErrors
                    )
                }
                SettingsSection("Leaf Points") {
                    NumberField(
                        value = earnPercent,
                        onValueChange = { earnPercent = it },
                        label = "Points earned (% of order)",
                        help = "Credited on delivery. 1 point = ₹0.10 (0–100)",
                        error = earnError,
                        showErrors = showErrors
                    )
                    NumberField(
                        value = redeemPercent,
                        onValueChange = { redeemPercent = it },
                        label = "Max redeemable (% of order)",
                        help = "Largest share of an order payable with points (0–100)",
                        error = redeemError,
                        showErrors = showErrors
                    )
                }
                SettingsSection("Orders") {
                    NumberField(
                        value = returnDays,
                        onValueChange = { returnDays = it },
                        label = "Return window (days)",
                        help = "Days after delivery a return can be requested. 0 disables returns (0–90)",
                        error = returnError,
                        showErrors = showErrors
                    )
                    NumberField(
                        value = maxQty,
                        onValueChange = { maxQty = it },
                        label = "Max quantity per item",
                        help = "Per product, per order (1–999)",
                        error = maxQtyError,
                        showErrors = showErrors
                    )
                }
                SettingsSection("Customer support") {
                    TextSettingField(supportEmail, { supportEmail = it }, "Support email", emailError, showErrors, KeyboardType.Email)
                    TextSettingField(supportPhone, { supportPhone = it }, "Support phone", phoneError, showErrors, KeyboardType.Phone)
                    TextSettingField(
                        supportWhatsapp, { supportWhatsapp = it }, "WhatsApp number", whatsappError, showErrors, KeyboardType.Phone,
                        help = "With country code, e.g. +91 98765 43210"
                    )
                    Text(
                        "Leave a contact blank to hide it from customers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SettingsSection("Legal") {
                    TextSettingField(privacyUrl, { privacyUrl = it }, "Privacy policy URL", privacyError, showErrors, KeyboardType.Uri, help = "https://…")
                    TextSettingField(termsUrl, { termsUrl = it }, "Terms of service URL", termsError, showErrors, KeyboardType.Uri, help = "https://…")
                }
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
                        Text("Save settings")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    help: String,
    error: String?,
    showErrors: Boolean,
) {
    val show = showErrors && error != null
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(5)) },
        label = { Text(label) },
        isError = show,
        supportingText = { Text(if (show) error.orEmpty() else help) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun TextSettingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    showErrors: Boolean,
    keyboardType: KeyboardType,
    help: String? = null,
) {
    val show = showErrors && error != null
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(200)) },
        label = { Text(label) },
        isError = show,
        supportingText = when {
            show -> ({ Text(error.orEmpty()) })
            help != null -> ({ Text(help) })
            else -> null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth()
    )
}
