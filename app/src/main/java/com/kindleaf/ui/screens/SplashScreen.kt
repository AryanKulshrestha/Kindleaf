package com.kindleaf.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kindleaf.data.AppStateManager
import com.kindleaf.data.CatalogStore
import com.kindleaf.data.SessionManager
import com.kindleaf.data.SupabaseHelper
import com.kindleaf.data.remote.AuthRepository
import com.kindleaf.ui.theme.PrimaryGreen
import com.kindleaf.ui.theme.SurfaceWhite
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onFinished: (destination: String) -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }
    val alphaAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 800),
        label = "splashAlpha"
    )
    val scaleAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.6f,
        animationSpec = tween(durationMillis = 800),
        label = "splashScale"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        delay(900)
        val loggedIn = if (SupabaseHelper.isConfigured) {
            runCatching { AuthRepository.restoreSession() }.getOrDefault(false)
        } else {
            false
        }
        val destination = if (loggedIn) {
            runCatching { SessionManager.bootstrapAfterAuth() }
            if (AppStateManager.userProfile.value.isAdmin) "admin_dashboard" else "home"
        } else {
            runCatching { CatalogStore.refresh() }
            runCatching { com.kindleaf.data.ChargesStore.refresh() }
            runCatching { com.kindleaf.data.StoreSettingsStore.refresh() }
            "login"
        }
        delay(600)
        onFinished(destination)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryGreen),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .alpha(alphaAnim)
                .scale(scaleAnim),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "🍃",
                fontSize = 64.sp,
                modifier = Modifier.semantics { contentDescription = "Kindleaf logo" }
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Kindleaf",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = SurfaceWhite,
                fontSize = 36.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Wellness in Every Sip",
                style = MaterialTheme.typography.bodyMedium,
                color = SurfaceWhite.copy(alpha = 0.85f)
            )
        }
    }
}
