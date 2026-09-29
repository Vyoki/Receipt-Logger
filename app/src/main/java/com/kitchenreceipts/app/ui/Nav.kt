package com.kitchenreceipts.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.kitchenreceipts.app.ui.products.FamilyScreen
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
    const val REVIEW_NEW = "review/{jobId}"
    const val EDIT = "edit/{id}"
    const val DOCUMENTS = "documents?sellerId={sellerId}"
    const val DOCUMENT = "document/{id}?auto={auto}"
    const val INVENTORY = "inventory"
    const val VIEWER = "viewer/{id}?job={job}" // id = -1: a document being read ([job]), not saved yet
    const val PRODUCTS = "products"
    const val PRODUCT = "product/{id}"
    const val FAMILY = "family/{id}"
    const val SELLERS = "sellers"
    const val REPORTS = "reports"
    const val SETTINGS = "settings"

    fun edit(id: Long) = "edit/$id"
    fun documents(sellerId: Long? = null) = if (sellerId == null) "documents" else "documents?sellerId=$sellerId"
    fun document(id: Long, auto: Boolean = false) = if (auto) "document/$id?auto=true" else "document/$id"
    fun viewer(id: Long) = "viewer/$id"
    fun viewerForJob(jobId: String) = "viewer/-1?job=$jobId"
    fun review(jobId: String) = "review/$jobId"
    fun product(id: Long) = "product/$id"
    fun family(id: Long) = "family/$id"
}

@Composable
fun AppNavHost(nav: NavHostController = rememberNavController()) {
    val back: () -> Unit = { nav.popBackStack() }
    // Screens opened from a notification ("ready to check", "saved").
    val c = appContainer()
    val route by c.pendingRoute.collectAsState()
    LaunchedEffect(route) {
        val r = route ?: return@LaunchedEffect
        c.pendingRoute.value = null
        runCatching { nav.navigate(r) { launchSingleTop = true } }
    }
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
                onReviewJob = { nav.navigate(Routes.review(it)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = back)
        }
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onBack = back,
                // The document is read in the background: straight back to the home screen, where its progress shows.
                onQueued = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.REVIEW_NEW, arguments = listOf(navArgument("jobId") { type = NavType.StringType })) { entry ->
            val jobId = entry.arguments!!.getString("jobId")!!
            ReviewScreen(
                documentId = null,
                jobId = jobId,
                onBack = back,
                onViewOriginal = { nav.navigate(Routes.viewerForJob(jobId)) },
                onSaved = { id, auto -> nav.navigate(Routes.document(id, auto)) { popUpTo(Routes.HOME) } },
            )
        }
        composable(Routes.EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments!!.getLong("id")
            ReviewScreen(
                documentId = id,
                jobId = null,
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
        composable(
            Routes.VIEWER,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("job") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            ViewerScreen(documentId = entry.arguments!!.getLong("id").takeIf { it > 0 }, jobId = entry.arguments!!.getString("job"), onBack = back)
        }
        composable(Routes.PRODUCTS) {
            ProductsScreen(onBack = back, onOpen = { nav.navigate(Routes.product(it)) }, onOpenFamily = { nav.navigate(Routes.family(it)) })
        }
        composable(Routes.PRODUCT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ProductDetailScreen(
                productId = entry.arguments!!.getLong("id"),
                onBack = back,
                onOpenDocument = { nav.navigate(Routes.document(it)) },
                onOpenFamily = { nav.navigate(Routes.family(it)) },
            )
        }
        composable(Routes.FAMILY, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            FamilyScreen(familyId = entry.arguments!!.getLong("id"), onBack = back, onOpenProduct = { nav.navigate(Routes.product(it)) })
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
