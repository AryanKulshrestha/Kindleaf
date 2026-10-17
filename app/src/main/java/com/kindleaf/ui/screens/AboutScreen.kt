package com.kindleaf.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kindleaf.BuildConfig
import com.kindleaf.data.StoreSettingsStore
import com.kindleaf.ui.account.openUrl
import com.kindleaf.ui.account.startSafely
import com.kindleaf.ui.theme.PrimaryGreen
import com.kindleaf.ui.theme.SurfaceWhite
import kotlinx.coroutines.launch

private val openSourceLibraries = listOf(
    "Jetpack Compose & AndroidX" to "Apache License 2.0",
    "Material Components for Compose" to "Apache License 2.0",
    "Supabase-kt" to "MIT License",
    "Ktor" to "Apache License 2.0",
    "kotlinx.serialization & kotlinx.coroutines" to "Apache License 2.0",
    "Coil" to "Apache License 2.0",
    "Razorpay Checkout" to "Razorpay Terms of Use",
    "Google Identity Services (Credential Manager)" to "Apache License 2.0",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = StoreSettingsStore.settings
    val snackbarHostState = remember { SnackbarHostState() }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    val packageName = context.packageName
    val playStoreUrl = "https://play.google.com/store/apps/details?id=$packageName"

    LaunchedEffect(Unit) { StoreSettingsStore.refresh() }

    fun notify(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun rateApp() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        if (!startSafely(context, market) && !openUrl(context, playStoreUrl)) {
            notify("Couldn't open the Play Store")
        }
    }

    fun shareApp() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Kindleaf")
            putExtra(Intent.EXTRA_TEXT, "I've been shopping for wellness teas on Kindleaf. Get the app: $playStoreUrl")
        }
        if (!startSafely(context, Intent.createChooser(send, "Share Kindleaf"))) {
            notify("No app available to share with")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "About Kindleaf",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(PrimaryGreen),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Eco,
                    contentDescription = null,
                    tint = SurfaceWhite,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "Kindleaf",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            AboutCard {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Our Story",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Kindleaf was born from a simple belief — that nature holds the key to wellness. " +
                            "We curate herbal teas from farms across India, bringing you " +
                            "blends that are pure, potent, and crafted with love.\n\n" +
                            "Every cup of Kindleaf tea is a step towards a healthier, more mindful life. " +
                            "From refreshing green teas to calming chamomile blends, we're on a " +
                            "mission to make natural wellness accessible to everyone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            AboutCard {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "What We Stand For",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))

                    val values = listOf(
                        Triple(Icons.Outlined.Eco, "Natural ingredients", "Blends made from real herbs, leaves and spices"),
                        Triple(Icons.Outlined.VerifiedUser, "Quality first", "Carefully sourced and packed fresh"),
                        Triple(Icons.Outlined.Handshake, "Close to our growers", "We work directly with farms we trust"),
                        Triple(Icons.Outlined.Recycling, "Mindful packaging", "Less plastic wherever we can"),
                    )

                    values.forEachIndexed { index, (icon, title, subtitle) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Column {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (index < values.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { rateApp() },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Outlined.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Rate App", style = MaterialTheme.typography.labelLarge)
                }
                Button(
                    onClick = { shareApp() },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen)
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp), tint = SurfaceWhite)
                    Spacer(Modifier.width(6.dp))
                    Text("Share App", style = MaterialTheme.typography.labelLarge, color = SurfaceWhite)
                }
            }

            Spacer(Modifier.height(16.dp))

            val links = buildList<Triple<String, ImageVector, () -> Unit>> {
                if (settings.privacyPolicyUrl.isNotBlank()) {
                    add(Triple("Privacy Policy", Icons.Outlined.PrivacyTip) {
                        if (!openUrl(context, settings.privacyPolicyUrl)) notify("Couldn't open the link")
                    })
                }
                if (settings.termsUrl.isNotBlank()) {
                    add(Triple("Terms of Service", Icons.Outlined.Description) {
                        if (!openUrl(context, settings.termsUrl)) notify("Couldn't open the link")
                    })
                }
                add(Triple("Open Source Licenses", Icons.Outlined.Code) { showLicenses = true })
            }
            AboutCard {
                Column {
                    links.forEachIndexed { index, (title, icon, onClick) ->
                        AboutLinkRow(title, icon, onClick)
                        if (index < links.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "Made with care in India",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "© 2026 Kindleaf. All rights reserved.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Open source licenses") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Kindleaf is built with these open source libraries and SDKs:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    openSourceLibraries.forEach { (name, license) ->
                        Column {
                            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                license,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun AboutCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        content()
    }
}

@Composable
private fun AboutLinkRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
