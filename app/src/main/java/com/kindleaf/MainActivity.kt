package com.kindleaf

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kindleaf.navigation.DeepLinks
import com.kindleaf.notifications.OrderNotificationHelper
import com.kindleaf.payments.RazorpayCheckoutManager
import com.kindleaf.ui.admin.AdminBottomBar
import com.kindleaf.ui.admin.AdminTab
import com.kindleaf.ui.components.BottomTab
import com.kindleaf.ui.components.KindleafBottomBar
import com.kindleaf.ui.navigation.KindleafNavGraph
import com.kindleaf.ui.theme.KindleafTheme
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener

class MainActivity : ComponentActivity(), PaymentResultWithDataListener {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: ordering works without tray permission */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrderNotificationHelper.ensureChannel(this)
        requestNotificationPermissionIfNeeded()
        RazorpayCheckoutManager.preload(this)
        if (savedInstanceState == null) DeepLinks.capture(intent)
        enableEdgeToEdge()
        setContent {
            KindleafTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val showCustomerBar = currentRoute in BottomTab.entries.map { it.route }
                val showAdminBar = currentRoute in AdminTab.entries.map { it.route }
                val currentTab = BottomTab.entries.find { it.route == currentRoute } ?: BottomTab.Home

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        AnimatedVisibility(
                            visible = showCustomerBar,
                            enter = slideInVertically(initialOffsetY = { it }),
                            exit = slideOutVertically(targetOffsetY = { it })
                        ) {
                            KindleafBottomBar(
                                currentTab = currentTab,
                                onTabSelected = { tab ->
                                    if (tab.route != currentRoute) {
                                        navController.navigate(tab.route) {
                                            popUpTo("home") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                }
                            )
                        }
                        AnimatedVisibility(
                            visible = showAdminBar,
                            enter = slideInVertically(initialOffsetY = { it }),
                            exit = slideOutVertically(targetOffsetY = { it })
                        ) {
                            AdminBottomBar(
                                currentRoute = currentRoute,
                                onTabSelected = { tab ->
                                    if (tab.route != currentRoute) {
                                        navController.navigate(tab.route) {
                                            popUpTo("admin_dashboard") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                }
                            )
                        }
                    }
                ) { innerPadding ->
                    KindleafNavGraph(
                        navController = navController,
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLinks.capture(intent)
    }

    // ── Razorpay Checkout callbacks ──

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        RazorpayCheckoutManager.onPaymentSuccess(razorpayPaymentId, paymentData)
    }

    override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        RazorpayCheckoutManager.onPaymentError(code, response, paymentData)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
