package com.kindleaf.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.kindleaf.data.AppStateManager
import com.kindleaf.data.ChargesStore
import com.kindleaf.data.StoreSettingsStore
import com.kindleaf.data.remote.AuthRepository
import com.kindleaf.data.remote.SupportRepository
import com.kindleaf.data.remote.formatDateTime
import com.kindleaf.data.userMessage
import com.kindleaf.models.StoreSettings
import com.kindleaf.models.SupportTicket
import com.kindleaf.ui.account.startSafely
import com.kindleaf.ui.theme.PrimaryGreen
import com.kindleaf.ui.theme.SurfaceWhite
import kotlinx.coroutines.launch

private const val SUBJECT_MAX = 200
private const val MESSAGE_MAX = 5000
private const val MESSAGE_MIN = 10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSupportScreen(
    onBack: () -> Unit,
    orderId: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = StoreSettingsStore.settings
    val snackbarHostState = remember { SnackbarHostState() }

    val orderShortId = remember(orderId) {
        orderId?.let { id -> AppStateManager.orders.find { it.id == id }?.shortId ?: id.take(8).uppercase() }
    }
    var subject by rememberSaveable { mutableStateOf(orderShortId?.let { "Help with order #$it" } ?: "") }
    var message by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }

    var tickets by remember { mutableStateOf<List<SupportTicket>>(emptyList()) }
    var ticketsLoading by remember { mutableStateOf(true) }
    var ticketsError by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    val signedInUserId = AppStateManager.userProfile.value.id.ifBlank { null } ?: AuthRepository.currentUser()?.id

    suspend fun loadTickets() {
        val userId = AppStateManager.userProfile.value.id.ifBlank { null } ?: AuthRepository.currentUser()?.id
        if (userId == null) {
            tickets = emptyList()
            ticketsError = null
            return
        }
        try {
            tickets = SupportRepository.fetchMine(userId)
            ticketsError = null
        } catch (e: Exception) {
            ticketsError = e.userMessage("Couldn't load your messages.")
        }
    }

    LaunchedEffect(Unit) {
        launch { StoreSettingsStore.refresh() }
        if (ChargesStore.charges.isEmpty() || ChargesStore.deliveryCharge()?.id == FALLBACK_CHARGE_ID) {
            launch { runCatching { ChargesStore.refresh() } }
        }
        loadTickets()
        ticketsLoading = false
    }

    val subjectError = when {
        !submitted -> null
        subject.isBlank() -> "Enter a subject"
        subject.trim().length > SUBJECT_MAX -> "Keep the subject under $SUBJECT_MAX characters"
        else -> null
    }
    val messageError = when {
        !submitted -> null
        message.trim().length < MESSAGE_MIN -> "Tell us a bit more (at least $MESSAGE_MIN characters)"
        message.trim().length > MESSAGE_MAX -> "Keep the message under $MESSAGE_MAX characters"
        else -> null
    }

    fun send() {
        submitted = true
        val valid = subject.isNotBlank() && subject.trim().length <= SUBJECT_MAX &&
            message.trim().length in MESSAGE_MIN..MESSAGE_MAX
        if (!valid) return
        val userId = signedInUserId ?: run {
            sendError = "Please sign in to send us a message."
            return
        }
        val email = AppStateManager.userProfile.value.email.ifBlank { AuthRepository.currentUser()?.email.orEmpty() }
        scope.launch {
            sending = true
            sendError = null
            try {
                val ticket = SupportRepository.create(userId, email, subject.trim(), message.trim(), orderId)
                tickets = listOf(ticket) + tickets.filterNot { it.id == ticket.id }
                subject = ""
                message = ""
                submitted = false
                snackbarHostState.showSnackbar("Message sent. We'll reply here and by email.")
            } catch (e: Exception) {
                sendError = e.userMessage("Couldn't send your message. Please try again.")
            } finally {
                sending = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Help & Support",
                        style = MaterialTheme.typography.headlineSmall,
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
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    StoreSettingsStore.refresh()
                    loadTickets()
                    refreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
            ) {
                // ── Contact ──
                HelpSectionTitle("Contact us")
                val contacts = buildContactOptions(settings)
                SupportCard {
                    if (contacts.isEmpty()) {
                        Text(
                            "Our support team is available through the message form below. We usually reply within one business day.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        contacts.forEachIndexed { index, contact ->
                            HelpContactRow(
                                icon = contact.icon,
                                label = contact.label,
                                value = contact.value,
                                onClick = {
                                    if (!startSafely(context, contact.intent)) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("No app found to open ${contact.label.lowercase()}")
                                        }
                                    }
                                }
                            )
                            if (index < contacts.lastIndex) SupportDivider()
                        }
                    }
                }

                // ── Message form ──
                Spacer(Modifier.height(24.dp))
                HelpSectionTitle("Send us a message")
                SupportCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (signedInUserId == null) {
                            Text(
                                "Sign in to send us a message and track our replies.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            if (orderShortId != null) {
                                Text(
                                    "About order #$orderShortId",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = PrimaryGreen
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            OutlinedTextField(
                                value = subject,
                                onValueChange = { subject = it.take(SUBJECT_MAX); sendError = null },
                                label = { Text("Subject") },
                                isError = subjectError != null,
                                supportingText = subjectError?.let { { Text(it) } },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                enabled = !sending,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = message,
                                onValueChange = { message = it.take(MESSAGE_MAX); sendError = null },
                                label = { Text("Your message") },
                                isError = messageError != null,
                                supportingText = {
                                    Text(messageError ?: "${message.length}/$MESSAGE_MAX")
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                minLines = 4,
                                maxLines = 8,
                                enabled = !sending,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                            )
                            sendError?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { send() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen),
                                enabled = !sending
                            ) {
                                if (sending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = SurfaceWhite,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.Send,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = SurfaceWhite
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Send message", color = SurfaceWhite)
                                }
                            }
                        }
                    }
                }

                // ── My tickets ──
                if (signedInUserId != null) {
                    Spacer(Modifier.height(24.dp))
                    HelpSectionTitle("My messages")
                    when {
                        ticketsLoading -> Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator(color = PrimaryGreen) }

                        ticketsError != null -> SupportCard {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    ticketsError!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    scope.launch {
                                        ticketsLoading = true
                                        loadTickets()
                                        ticketsLoading = false
                                    }
                                }) { Text("Retry", color = PrimaryGreen) }
                            }
                        }

                        tickets.isEmpty() -> Text(
                            "You haven't contacted us yet. Messages you send will appear here with our replies.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        else -> Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            tickets.forEach { TicketCard(it) }
                        }
                    }
                }

                // ── FAQ ──
                Spacer(Modifier.height(24.dp))
                HelpSectionTitle("Frequently asked questions")
                val faqs = buildFaqs(settings)
                SupportCard {
                    Column(modifier = Modifier.animateContentSize()) {
                        faqs.forEachIndexed { index, (question, answer) ->
                            HelpFaqItem(question = question, answer = answer)
                            if (index < faqs.lastIndex) SupportDivider()
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/** Id of the placeholder delivery charge ChargesStore inserts when the server can't be reached. */
private const val FALLBACK_CHARGE_ID = "local-delivery"

private data class ContactOption(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val intent: Intent,
)

private fun buildContactOptions(settings: StoreSettings): List<ContactOption> = buildList {
    val email = settings.supportEmail.trim()
    if (email.isNotBlank()) {
        add(
            ContactOption(
                Icons.Outlined.Email, "Email", email,
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
            )
        )
    }
    val phone = settings.supportPhone.trim()
    if (phone.isNotBlank()) {
        add(
            ContactOption(
                Icons.Outlined.Phone, "Phone", phone,
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' }))
            )
        )
    }
    val whatsapp = settings.supportWhatsapp.trim()
    if (whatsapp.isNotBlank()) {
        var digits = whatsapp.filter { it.isDigit() }
        if (digits.length == 10) digits = "91$digits"
        if (digits.isNotEmpty()) {
            add(
                ContactOption(
                    Icons.AutoMirrored.Outlined.Chat, "WhatsApp", whatsapp,
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits"))
                )
            )
        }
    }
}

private fun buildFaqs(settings: StoreSettings): List<Pair<String, String>> {
    val delivery = ChargesStore.deliveryCharge()?.takeIf { it.id != FALLBACK_CHARGE_ID }
    val deliveryAnswer = when {
        delivery == null ->
            "Any delivery fee is shown in your cart and at checkout before you pay."
        !delivery.isEnabled || delivery.amount <= 0 ->
            "Delivery is currently free on all orders."
        delivery.freeAbove != null && delivery.freeAbove > 0 ->
            "Delivery is free on orders of ₹${delivery.freeAbove} or more. Below that, a delivery fee of ₹${delivery.amount} applies."
        else -> "A delivery fee of ₹${delivery.amount} applies to each order."
    }
    val paymentAnswer = when {
        settings.onlinePaymentsEnabled && settings.codEnabled ->
            "Pay securely online with UPI, credit/debit cards, netbanking or wallets via Razorpay, or choose Cash on Delivery."
        settings.onlinePaymentsEnabled ->
            "Pay securely online with UPI, credit/debit cards, netbanking or wallets via Razorpay."
        settings.codEnabled -> "Orders are currently paid by Cash on Delivery."
        else -> "Available payment options are shown at checkout."
    }
    return listOf(
        "How do I place an order?" to
            "Browse the teas, add items to your cart and tap Checkout. Choose a delivery address and payment method, then confirm your order.",
        "What are the delivery charges?" to deliveryAnswer,
        "Which payment methods can I use?" to paymentAnswer,
        "How can I track my order?" to
            "Open My Orders from your profile and tap an order to see its status, from confirmation to delivery, plus courier tracking details once it ships.",
        "Can I cancel my order?" to
            "Yes, you can cancel from the order details page until your order is packed. If you already paid online, you can choose a refund to your original payment method or as Leaf Points.",
        "What is the return policy?" to
            "You can request a return within ${settings.returnWindowDays} days of delivery from the order details page, for example if an item arrived damaged, wrong or missing. Once approved, we refund you to your original payment method or as Leaf Points.",
        "How do Leaf Points work?" to
            "You earn ${settings.pointsEarnPercent}% of your order value as Leaf Points when an order is delivered. 1 point = ₹0.10. At checkout you can redeem points for up to ${settings.pointsRedeemMaxPercent}% of an order.",
    )
}

@Composable
private fun HelpSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
    )
}

@Composable
private fun SupportCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun SupportDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    )
}

@Composable
private fun TicketCard(ticket: SupportTicket) {
    val (statusLabel, statusColor) = when (ticket.status) {
        "resolved" -> "Resolved" to PrimaryGreen
        "in_progress" -> "In progress" to MaterialTheme.colorScheme.tertiary
        else -> "Open" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ticket.subject,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Text(
                formatDateTime(ticket.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                ticket.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (ticket.adminNote.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)) {
                        Text(
                            "Reply from Kindleaf",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            ticket.adminNote,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpFaqItem(question: String, answer: String) {
    var expanded by rememberSaveable(question) { mutableStateOf(false) }

    Surface(
        onClick = { expanded = !expanded },
        color = Color.Transparent,
        modifier = Modifier.semantics {
            stateDescription = if (expanded) "Expanded" else "Collapsed"
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .animateContentSize()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = question,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = answer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HelpContactRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = PrimaryGreen
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
