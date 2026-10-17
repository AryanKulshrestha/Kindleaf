package com.kindleaf.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.kindleaf.data.AppStateManager
import com.kindleaf.data.SessionManager
import com.kindleaf.data.remote.AuthRepository
import com.kindleaf.navigation.DeepLink
import com.kindleaf.navigation.DeepLinks
import com.kindleaf.ui.admin.*
import com.kindleaf.ui.screens.*
import kotlinx.coroutines.launch

private val PUBLIC_ROUTES = setOf("splash", "login", "signup", "forgot_password")

@Composable
fun KindleafNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    fun goLogin() {
        navController.navigate("login") {
            popUpTo(0) { inclusive = true }
        }
    }

    fun logout() {
        scope.launch {
            SessionManager.logout()
            goLogin()
        }
    }

    // After Android kills and recreates the app on a signed-in screen, the in-memory stores are
    // empty: restore the session and reload them (or return to login if the session is gone).
    val profile by AppStateManager.userProfile
    var restoring by remember { mutableStateOf(false) }
    LaunchedEffect(currentRoute, profile.id) {
        if (restoring || currentRoute == null || currentRoute in PUBLIC_ROUTES || profile.id.isNotBlank()) {
            return@LaunchedEffect
        }
        restoring = true
        val signedIn = runCatching { AuthRepository.restoreSession() }.getOrDefault(false)
        if (signedIn) runCatching { SessionManager.bootstrapAfterAuth() } else goLogin()
        restoring = false
    }

    // Open a pending deep link (notification tap, shared product link) once signed in.
    val pendingLink by DeepLinks.pending
    LaunchedEffect(pendingLink, currentRoute) {
        if (pendingLink == null || currentRoute == null || currentRoute in PUBLIC_ROUTES) return@LaunchedEffect
        when (val link = DeepLinks.consume()) {
            is DeepLink.OrderLink -> navController.navigate(
                if (AppStateManager.userProfile.value.isAdmin) "admin_order_detail/${link.orderId}"
                else "order_detail/${link.orderId}"
            )
            is DeepLink.ProductLink -> navController.navigate("product_detail/${link.productId}")
            null -> Unit
        }
    }

    NavHost(
        navController = navController,
        startDestination = "splash",
        modifier = modifier,
    ) {

        // ── Auth ──
        composable("splash") {
            SplashScreen(
                onFinished = { destination ->
                    navController.navigate(destination) {
                        popUpTo("splash") { inclusive = true }
                    }
                }
            )
        }

        composable("login") {
            LoginScreen(
                onLoggedIn = { isAdmin ->
                    navController.navigate(if (isAdmin) "admin_dashboard" else "home") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onGoSignup = { navController.navigate("signup") },
                onForgotPassword = { navController.navigate("forgot_password") }
            )
        }

        composable("signup") {
            SignupScreen(
                // SignupScreen bootstraps the session before calling this.
                onSignedUp = {
                    navController.navigate("home") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onGoLogin = { navController.popBackStack() }
            )
        }

        composable("forgot_password") {
            ForgotPasswordScreen(
                onBack = { navController.popBackStack() },
                onPasswordReset = { goLogin() }
            )
        }

        // ── Shopping ──
        composable("home") {
            HomeScreen(
                onSearchClick = { navController.navigate("search") },
                onProductClick = { id -> navController.navigate("product_detail/$id") },
                onViewAllClick = { navController.navigate("product_listing") },
                onCategoryClick = { name -> navController.navigate("category/${Uri.encode(name)}") },
                onNotificationsClick = { navController.navigate("notifications") },
                onCartClick = { navController.navigate("cart") }
            )
        }

        composable("search") {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onProductClick = { id -> navController.navigate("product_detail/$id") }
            )
        }

        composable("product_listing") {
            ProductListingScreen(
                onBack = { navController.popBackStack() },
                onProductClick = { id -> navController.navigate("product_detail/$id") },
                onCartClick = { navController.navigate("cart") }
            )
        }

        composable(
            route = "category/{category}",
            arguments = listOf(navArgument("category") { type = NavType.StringType })
        ) { entry ->
            ProductListingScreen(
                onBack = { navController.popBackStack() },
                onProductClick = { id -> navController.navigate("product_detail/$id") },
                onCartClick = { navController.navigate("cart") },
                initialCategory = entry.arguments?.getString("category")
            )
        }

        composable(
            route = "product_detail/{productId}",
            arguments = listOf(navArgument("productId") { type = NavType.StringType })
        ) { entry ->
            val productId = entry.arguments?.getString("productId").orEmpty()
            ProductDetailScreen(
                productId = productId,
                onBack = { navController.popBackStack() },
                onCartClick = { navController.navigate("cart") },
                onReviewsClick = { navController.navigate("reviews/$productId") },
                onBuyNow = { navController.navigate("checkout") }
            )
        }

        composable(
            route = "reviews/{productId}?write={write}",
            arguments = listOf(
                navArgument("productId") { type = NavType.StringType },
                navArgument("write") { type = NavType.BoolType; defaultValue = false },
            )
        ) { entry ->
            ReviewsScreen(
                productId = entry.arguments?.getString("productId").orEmpty(),
                openComposer = entry.arguments?.getBoolean("write") ?: false,
                onBack = { navController.popBackStack() }
            )
        }

        composable("cart") {
            CartScreen(
                onBack = { navController.popBackStack() },
                onCheckout = { navController.navigate("checkout") },
                onProductClick = { id -> navController.navigate("product_detail/$id") },
                onStartShopping = {
                    navController.navigate("product_listing") { popUpTo("home") }
                }
            )
        }

        composable("wishlist") {
            WishlistScreen(
                onBack = { navController.popBackStack() },
                onProductClick = { id -> navController.navigate("product_detail/$id") },
                onCartClick = { navController.navigate("cart") }
            )
        }

        // ── Checkout & payment ──
        composable("checkout") {
            CheckoutScreen(
                onBack = { navController.popBackStack() },
                onOrderPlaced = { orderId ->
                    navController.navigate("order_confirmation/$orderId") { popUpTo("home") }
                },
                onPaymentRequired = { orderId ->
                    navController.navigate("payment/$orderId") { popUpTo("home") }
                },
                onAddAddress = { navController.navigate("add_edit_address/new") }
            )
        }

        composable(
            route = "payment/{orderId}",
            arguments = listOf(navArgument("orderId") { type = NavType.StringType })
        ) { entry ->
            PaymentScreen(
                orderId = entry.arguments?.getString("orderId").orEmpty(),
                onPaid = { orderId ->
                    navController.navigate("order_confirmation/$orderId") { popUpTo("home") }
                },
                onBack = { navController.popBackStack() },
                onOrderCancelled = {
                    navController.navigate("home") { popUpTo("home") { inclusive = true } }
                }
            )
        }

        composable(
            route = "order_confirmation/{orderId}",
            arguments = listOf(navArgument("orderId") { type = NavType.StringType })
        ) { entry ->
            OrderConfirmationScreen(
                orderId = entry.arguments?.getString("orderId").orEmpty(),
                onTrackOrder = { orderId ->
                    navController.navigate("order_detail/$orderId") { popUpTo("home") }
                },
                onContinueShopping = {
                    navController.navigate("home") { popUpTo("home") { inclusive = true } }
                }
            )
        }

        // ── Orders ──
        composable("order_tracking") {
            OrderTrackingScreen(
                onBack = { navController.popBackStack() },
                onOpenOrder = { id -> navController.navigate("order_detail/$id") },
                onStartShopping = { navController.navigate("product_listing") { popUpTo("home") } }
            )
        }

        composable(
            route = "order_detail/{orderId}",
            arguments = listOf(navArgument("orderId") { type = NavType.StringType })
        ) { entry ->
            OrderDetailScreen(
                orderId = entry.arguments?.getString("orderId").orEmpty(),
                onBack = { navController.popBackStack() },
                onPayNow = { id -> navController.navigate("payment/$id") },
                onWriteReview = { itemId -> navController.navigate("reviews/$itemId?write=true") },
                onGetHelp = { id -> navController.navigate("help_support?orderId=$id") },
                onProductClick = { id -> navController.navigate("product_detail/$id") },
                onReorderDone = { navController.navigate("cart") }
            )
        }

        // ── Account ──
        composable("profile") {
            ProfileScreen(
                onOrdersClick = { navController.navigate("order_tracking") },
                onCouponsClick = { navController.navigate("coupons") },
                onEditClick = { navController.navigate("edit_profile") },
                onWishlistClick = { navController.navigate("wishlist") },
                onAddressesClick = { navController.navigate("addresses") },
                onNotificationsClick = { navController.navigate("notifications") },
                onHelpClick = { navController.navigate("help_support") },
                onAboutClick = { navController.navigate("about") },
                onPointsClick = { navController.navigate("points_history") },
                onLogout = { logout() },
                onAccountDeleted = { goLogin() }
            )
        }

        composable("points_history") {
            PointsHistoryScreen(onBack = { navController.popBackStack() })
        }

        composable("coupons") {
            CouponsScreen(onBack = { navController.popBackStack() })
        }

        composable("edit_profile") {
            EditProfileScreen(onBack = { navController.popBackStack() })
        }

        composable("addresses") {
            AddressesScreen(
                onBack = { navController.popBackStack() },
                onAddAddress = { navController.navigate("add_edit_address/new") },
                onEditAddress = { addressId -> navController.navigate("add_edit_address/$addressId") }
            )
        }

        composable(
            route = "add_edit_address/{addressId}",
            arguments = listOf(navArgument("addressId") { type = NavType.StringType })
        ) { entry ->
            val addressId = entry.arguments?.getString("addressId")
            AddEditAddressScreen(
                addressId = if (addressId == "new") null else addressId,
                onBack = { navController.popBackStack() }
            )
        }

        composable("notifications") {
            NotificationsScreen(
                onBack = { navController.popBackStack() },
                onOpenOrder = { id -> navController.navigate("order_detail/$id") }
            )
        }

        composable(
            route = "help_support?orderId={orderId}",
            arguments = listOf(
                navArgument("orderId") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { entry ->
            HelpSupportScreen(
                onBack = { navController.popBackStack() },
                orderId = entry.arguments?.getString("orderId")
            )
        }

        composable("about") {
            AboutScreen(onBack = { navController.popBackStack() })
        }

        // ── Admin (the database enforces permissions; this guard keeps customers out of the UI) ──
        adminComposable("admin_dashboard", navController) {
            AdminDashboardScreen(
                onOpenOrders = { navController.navigate("admin_orders") },
                onOpenOrder = { id -> navController.navigate("admin_order_detail/$id") },
                onOpenProduct = { id -> navController.navigate("admin_product_edit/$id") },
                onOpenReturns = { navController.navigate("admin_returns") },
                onOpenSupport = { navController.navigate("admin_support") },
                onLogout = { logout() }
            )
        }

        adminComposable("admin_products", navController) {
            AdminProductsScreen(
                onEdit = { id -> navController.navigate("admin_product_edit/${id ?: "new"}") }
            )
        }

        adminComposable(
            route = "admin_product_edit/{productId}",
            navController = navController,
            arguments = listOf(navArgument("productId") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("productId")
            AdminProductEditScreen(
                productId = if (id == "new") null else id,
                onBack = { navController.popBackStack() }
            )
        }

        adminComposable("admin_categories", navController) {
            AdminCategoriesScreen()
        }

        adminComposable("admin_orders", navController) {
            AdminOrdersScreen(
                onOpenOrder = { id -> navController.navigate("admin_order_detail/$id") }
            )
        }

        adminComposable(
            route = "admin_order_detail/{orderId}",
            navController = navController,
            arguments = listOf(navArgument("orderId") { type = NavType.StringType })
        ) { entry ->
            AdminOrderDetailScreen(
                orderId = entry.arguments?.getString("orderId").orEmpty(),
                onBack = { navController.popBackStack() }
            )
        }

        adminComposable("admin_more", navController) {
            AdminMoreScreen(
                onCustomers = { navController.navigate("admin_customers") },
                onCoupons = { navController.navigate("admin_coupons") },
                onCharges = { navController.navigate("admin_charges") },
                onReviews = { navController.navigate("admin_reviews") },
                onNotifications = { navController.navigate("admin_notifications") },
                onNotificationSettings = { navController.navigate("admin_notification_settings") },
                onReturns = { navController.navigate("admin_returns") },
                onSupport = { navController.navigate("admin_support") },
                onStoreSettings = { navController.navigate("admin_store_settings") },
                onLogout = { logout() }
            )
        }

        val back: () -> Unit = { navController.popBackStack() }
        adminComposable("admin_customers", navController) { AdminCustomersScreen(onBack = back) }
        adminComposable("admin_coupons", navController) { AdminCouponsScreen(onBack = back) }
        adminComposable("admin_charges", navController) { AdminChargesScreen(onBack = back) }
        adminComposable("admin_reviews", navController) { AdminReviewsScreen(onBack = back) }
        adminComposable("admin_notifications", navController) { AdminNotificationsScreen(onBack = back) }
        adminComposable("admin_notification_settings", navController) { AdminNotificationSettingsScreen(onBack = back) }
        adminComposable("admin_returns", navController) { AdminReturnsScreen(onBack = back) }
        adminComposable("admin_support", navController) { AdminSupportScreen(onBack = back) }
        adminComposable("admin_store_settings", navController) { AdminStoreSettingsScreen(onBack = back) }
    }
}

/** Registers an admin-only destination; non-admins are sent back to the storefront. */
private fun NavGraphBuilder.adminComposable(
    route: String,
    navController: NavHostController,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route = route, arguments = arguments) { entry ->
        val profile by AppStateManager.userProfile
        if (profile.isAdmin) {
            content(entry)
        } else if (profile.id.isBlank()) {
            // Profile not loaded yet (e.g. app restored after process death); the graph reloads it.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LaunchedEffect(Unit) {
                navController.navigate("home") { popUpTo(0) { inclusive = true } }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}
