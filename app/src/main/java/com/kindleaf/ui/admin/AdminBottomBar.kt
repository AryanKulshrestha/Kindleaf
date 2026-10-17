package com.kindleaf.ui.admin

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

enum class AdminTab(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("admin_dashboard", "Home", Icons.Outlined.Dashboard),
    Products("admin_products", "Products", Icons.Outlined.Inventory2),
    Categories("admin_categories", "Categories", Icons.Outlined.Category),
    Orders("admin_orders", "Orders", Icons.Outlined.ReceiptLong),
    More("admin_more", "More", Icons.Outlined.MoreHoriz),
}

@Composable
fun AdminBottomBar(
    currentRoute: String?,
    onTabSelected: (AdminTab) -> Unit,
) {
    val selected = AdminTab.entries.find { it.route == currentRoute } ?: AdminTab.Dashboard
    NavigationBar {
        AdminTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onTabSelected(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
            )
        }
    }
}
