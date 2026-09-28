package com.kitchenreceipts.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kitchenreceipts.app.ui.capture.CaptureScreen
import com.kitchenreceipts.app.ui.documents.DocumentDetailScreen
import com.kitchenreceipts.app.ui.documents.DocumentsScreen
import com.kitchenreceipts.app.ui.home.HomeScreen
import com.kitchenreceipts.app.ui.inventory.InventoryScreen
import com.kitchenreceipts.app.ui.products.ProductDetailScreen
import com.kitchenreceipts.app.ui.products.ProductsScreen
import com.kitchenreceipts.app.ui.reports.ReportsScreen
import com.kitchenreceipts.app.ui.review.ReviewScreen
import com.kitchenreceipts.app.ui.sellers.SellersScreen
import com.kitchenreceipts.app.ui.settings.SettingsScreen
import com.kitchenreceipts.app.ui.viewer.ViewerScreen

object Routes {
    const val HOME = "home"
    const val CAPTURE = "capture"
    const val REVIEW_NEW = "review"
    const val EDIT = "edit/{id}"
    const val DOCUMENTS = "documents?sellerId={sellerId}"
    const val DOCUMENT = "document/{id}?auto={auto}"
    const val INVENTORY = "inventory"
    const val VIEWER = "viewer/{id}" // id = -1: the pending (not yet saved) import
    const val PRODUCTS = "products"
    const val PRODUCT = "product/{id}"
    const val SELLERS = "sellers"
    const val REPORTS = "reports"
    const val SETTINGS = "settings"

    fun edit(id: Long) = "edit/$id"
    fun documents(sellerId: Long? = null) = if (sellerId == null) "documents" else "documents?sellerId=$sellerId"
    fun document(id: Long, auto: Boolean = false) = if (auto) "document/$id?auto=true" else "document/$id"
    fun viewer(id: Long) = "viewer/$id"
    fun product(id: Long) = "product/$id"
}

@Composable
fun AppNavHost(nav: NavHostController = rememberNavController()) {
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onScan = { nav.navigate(Routes.CAPTURE) },
                onDocuments = { nav.navigate(Routes.documents()) },
                onSellers = { nav.navigate(Routes.SELLERS) },
                onProducts = { nav.navigate(Routes.PRODUCTS) },
                onReports = { nav.navigate(Routes.REPORTS) },
                onOpenDocument = { nav.navigate(Routes.document(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onInventory = { nav.navigate(Routes.INVENTORY) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = back)
        }
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onBack = back,
                onReady = { nav.navigate(Routes.REVIEW_NEW) { popUpTo(Routes.CAPTURE) { inclusive = true } } },
            )
        }
        composable(Routes.REVIEW_NEW) {
            ReviewScreen(
                documentId = null,
                onBack = back,
                onViewOriginal = { nav.navigate(Routes.viewer(-1)) },
                onSaved = { id, auto -> nav.navigate(Routes.document(id, auto)) { popUpTo(Routes.HOME) } },
            )
        }
        composable(Routes.EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments!!.getLong("id")
            ReviewScreen(
                documentId = id,
                onBack = back,
                onViewOriginal = { nav.navigate(Routes.viewer(id)) },
                onSaved = { _, _ -> nav.popBackStack() },
            )
        }
        composable(
            Routes.DOCUMENTS,
            arguments = listOf(navArgument("sellerId") { type = NavType.LongType; defaultValue = -1L }),
        ) { entry ->
            val sellerId = entry.arguments!!.getLong("sellerId").takeIf { it > 0 }
            DocumentsScreen(initialSellerId = sellerId, onBack = back, onOpen = { nav.navigate(Routes.document(it)) })
        }
        composable(
            Routes.DOCUMENT,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("auto") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val id = entry.arguments!!.getLong("id")
            DocumentDetailScreen(
                documentId = id,
                autoSaved = entry.arguments!!.getBoolean("auto"),
                onBack = back,
                onEdit = { nav.navigate(Routes.edit(id)) },
                onViewOriginal = { nav.navigate(Routes.viewer(id)) },
                onOpenProduct = { nav.navigate(Routes.product(it)) },
            )
        }
        composable(Routes.VIEWER, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ViewerScreen(documentId = entry.arguments!!.getLong("id").takeIf { it > 0 }, onBack = back)
        }
        composable(Routes.PRODUCTS) {
            ProductsScreen(onBack = back, onOpen = { nav.navigate(Routes.product(it)) })
        }
        composable(Routes.PRODUCT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ProductDetailScreen(
                productId = entry.arguments!!.getLong("id"),
                onBack = back,
                onOpenDocument = { nav.navigate(Routes.document(it)) },
            )
        }
        composable(Routes.SELLERS) {
            SellersScreen(onBack = back, onOpenSeller = { nav.navigate(Routes.documents(it)) })
        }
        composable(Routes.INVENTORY) {
            InventoryScreen(onBack = back, onOpenProduct = { nav.navigate(Routes.product(it)) })
        }
        composable(Routes.REPORTS) {
            ReportsScreen(onBack = back)
        }
    }
}
