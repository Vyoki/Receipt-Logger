package com.kitchenreceipts.app.ui

import com.kitchenreceipts.app.ui.theme.Motion
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
    /** id = -1: a document being read ([job]), not saved yet, or the file of an import of many invoices ([bundle]); [page] from 0. */
    const val VIEWER = "viewer/{id}?job={job}&bundle={bundle}&page={page}"
    const val PRODUCTS = "products"
    const val PRODUCT = "product/{id}"
    const val FAMILY = "family/{id}"
    const val SELLERS = "sellers"
    const val REPORTS = "reports"
    const val SETTINGS = "settings"
    const val BACKUP = "backup"
    const val CHECKS = "checks"
    const val LOTS = "lots"
    const val FOOD = "food"
    const val RECIPE = "recipe/{id}"
    const val ORDERS = "orders"
    /**
     * The import of a file of many invoices; id = its plan (the file's SHA-256, see BundleStore). [prices]: its list
     * shows only the invoices whose prices changed (from the "Prices changed on N invoices" message).
     */
    const val BUNDLE = "bundle/{id}?prices={prices}"

    fun recipe(id: Long) = "recipe/$id"
    fun bundle(planId: String, prices: Boolean = false) = if (prices) "bundle/$planId?prices=true" else "bundle/$planId"

    fun edit(id: Long) = "edit/$id"
    fun documents(sellerId: Long? = null) = if (sellerId == null) "documents" else "documents?sellerId=$sellerId"
    fun document(id: Long, auto: Boolean = false) = if (auto) "document/$id?auto=true" else "document/$id"
    fun viewer(id: Long) = "viewer/$id"
    fun viewerForJob(jobId: String) = "viewer/-1?job=$jobId"
    fun viewerForBundle(planId: String, page: Int) = "viewer/-1?bundle=$planId&page=$page"
    fun review(jobId: String) = "review/$jobId"
    fun product(id: Long) = "product/$id"
    fun family(id: Long) = "family/$id"
}

@Composable
fun AppNavHost(nav: NavHostController = rememberNavController()) {
    // A fast double tap pops once: only the screen on top (fully shown) may go back.
    val back: () -> Unit = {
        if (nav.currentBackStackEntry?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED) nav.popBackStack()
    }
    // A fast double tap opens a screen once: only the screen on top (fully shown) may open another. Two review screens
    // of one document would each keep a draft and could save it twice.
    fun resumed() = nav.currentBackStackEntry?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
    val go: (String) -> Unit = { r -> if (resumed()) nav.navigate(r) }
    fun goWith(r: String, options: androidx.navigation.NavOptionsBuilder.() -> Unit) { if (resumed()) nav.navigate(r, options) }
    // Screens opened from a notification ("ready to check", "saved").
    val c = appContainer()
    val route by c.pendingRoute.collectAsState()
    LaunchedEffect(route) {
        val r = route ?: return@LaunchedEffect
        c.pendingRoute.value = null
        runCatching { nav.navigate(r) { launchSingleTop = true } }
    }
    // Screens slide in a little and fade, and slide back out when closed: where the operator came from stays clear.
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = {
            androidx.compose.animation.fadeIn(Motion.enter()) +
                androidx.compose.animation.slideInHorizontally(Motion.enter()) { it / 5 }
        },
        exitTransition = {
            androidx.compose.animation.fadeOut(Motion.exit()) +
                androidx.compose.animation.slideOutHorizontally(Motion.enter()) { -it / 12 }
        },
        popEnterTransition = {
            androidx.compose.animation.fadeIn(Motion.enter()) +
                androidx.compose.animation.slideInHorizontally(Motion.enter()) { -it / 12 }
        },
        popExitTransition = {
            androidx.compose.animation.fadeOut(Motion.exit()) +
                androidx.compose.animation.slideOutHorizontally(Motion.enter()) { it / 5 }
        },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onScan = { go(Routes.CAPTURE) },
                onDocuments = { go(Routes.documents()) },
                onProducts = { go(Routes.PRODUCTS) },
                onReports = { go(Routes.REPORTS) },
                onOpenDocument = { go(Routes.document(it)) },
                onSettings = { go(Routes.SETTINGS) },
                onInventory = { go(Routes.INVENTORY) },
                onReviewJob = { go(Routes.review(it)) },
                onOpenBundle = { go(Routes.bundle(it)) },
                onBackup = { go(Routes.BACKUP) },
                onChecks = { go(Routes.CHECKS) },
                onLots = { go(Routes.LOTS) },
                onFoodCost = { go(Routes.FOOD) },
                onOrders = { go(Routes.ORDERS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = back, onBackup = { go(Routes.BACKUP) })
        }
        composable(Routes.CHECKS) {
            com.kitchenreceipts.app.ui.checks.ChecksScreen(
                onBack = back,
                onOpenDocument = { go(Routes.document(it)) },
                onOpenProduct = { go(Routes.product(it)) },
            )
        }
        composable(Routes.LOTS) {
            com.kitchenreceipts.app.ui.checks.LotsScreen(onBack = back, onOpenDocument = { go(Routes.document(it)) })
        }
        composable(Routes.FOOD) {
            com.kitchenreceipts.app.ui.food.FoodCostScreen(onBack = back, onOpenDish = { go(Routes.recipe(it)) })
        }
        composable(Routes.RECIPE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            com.kitchenreceipts.app.ui.food.RecipeScreen(recipeId = entry.arguments!!.getLong("id"), onBack = back)
        }
        composable(Routes.ORDERS) {
            com.kitchenreceipts.app.ui.food.OrderScreen(onBack = back)
        }
        composable(Routes.BACKUP) {
            com.kitchenreceipts.app.ui.settings.BackupScreen(onBack = back)
        }
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onBack = back,
                // The document is read in the background: straight back to the home screen, where its progress shows.
                onQueued = { nav.popBackStack(Routes.HOME, inclusive = false) },
                // A file of many invoices: its import, in place of Capture (Back goes home).
                onBundle = { id -> goWith(Routes.bundle(id)) { popUpTo(Routes.HOME) } },
            )
        }
        composable(
            Routes.BUNDLE,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("prices") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val planId = entry.arguments!!.getString("id")!!
            com.kitchenreceipts.app.ui.bundle.BundleScreen(
                planId = planId,
                pricesOnly = entry.arguments!!.getBoolean("prices"),
                onBack = back,
                onHome = { if (!nav.popBackStack(Routes.HOME, inclusive = false)) nav.navigate(Routes.HOME) },
                onReview = { go(Routes.review(it)) },
                onOpenDocument = { go(Routes.document(it)) },
                onSeePages = { page -> go(Routes.viewerForBundle(planId, page)) },
            )
        }
        composable(Routes.REVIEW_NEW, arguments = listOf(navArgument("jobId") { type = NavType.StringType })) { entry ->
            val jobId = entry.arguments!!.getString("jobId")!!
            ReviewScreen(
                documentId = null,
                jobId = jobId,
                onBack = back,
                onViewOriginal = { go(Routes.viewerForJob(jobId)) },
                onSaved = { id, auto -> goWith(Routes.document(id, auto)) { popUpTo(Routes.HOME) } },
                // An invoice of a file of many: the next one to check takes this one's place; after the last, the
                // file's list (the one below when it is there, so Back never comes back to a saved invoice).
                onChain = { next, planId ->
                    if (nav.currentBackStackEntry === entry) {
                        if (next != null) {
                            nav.navigate(Routes.review(next)) { popUpTo(Routes.REVIEW_NEW) { inclusive = true } }
                        } else {
                            val listBelow = runCatching { nav.getBackStackEntry(Routes.BUNDLE) }.isSuccess
                            nav.navigate(Routes.bundle(planId)) { popUpTo(if (listBelow) Routes.BUNDLE else Routes.REVIEW_NEW) { inclusive = true } }
                        }
                    }
                },
            )
        }
        composable(Routes.EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments!!.getLong("id")
            ReviewScreen(
                documentId = id,
                jobId = null,
                onBack = back,
                onViewOriginal = { go(Routes.viewer(id)) },
                onSaved = { _, _ -> nav.popBackStack() },
            )
        }
        composable(
            Routes.DOCUMENTS,
            arguments = listOf(navArgument("sellerId") { type = NavType.LongType; defaultValue = -1L }),
        ) { entry ->
            val sellerId = entry.arguments!!.getLong("sellerId").takeIf { it > 0 }
            DocumentsScreen(
                initialSellerId = sellerId, onBack = back, onOpen = { go(Routes.document(it)) },
                onSuppliers = if (sellerId == null) ({ go(Routes.SELLERS) }) else null,
            )
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
                onEdit = { go(Routes.edit(id)) },
                onViewOriginal = { go(Routes.viewer(id)) },
                onOpenProduct = { go(Routes.product(it)) },
                onOpenDocument = { go(Routes.document(it)) },
            )
        }
        composable(
            Routes.VIEWER,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("job") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("bundle") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("page") { type = NavType.IntType; defaultValue = 0 },
            ),
        ) { entry ->
            val args = entry.arguments!!
            ViewerScreen(
                documentId = args.getLong("id").takeIf { it > 0 },
                jobId = args.getString("job"),
                bundleId = args.getString("bundle"),
                initialPage = args.getInt("page"),
                onBack = back,
            )
        }
        composable(Routes.PRODUCTS) {
            ProductsScreen(onBack = back, onOpen = { go(Routes.product(it)) }, onOpenFamily = { go(Routes.family(it)) })
        }
        composable(Routes.PRODUCT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ProductDetailScreen(
                productId = entry.arguments!!.getLong("id"),
                onBack = back,
                onOpenDocument = { go(Routes.document(it)) },
                onOpenFamily = { go(Routes.family(it)) },
            )
        }
        composable(Routes.FAMILY, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            FamilyScreen(familyId = entry.arguments!!.getLong("id"), onBack = back, onOpenProduct = { go(Routes.product(it)) })
        }
        composable(Routes.SELLERS) {
            SellersScreen(onBack = back, onOpenSeller = { go(Routes.documents(it)) })
        }
        composable(Routes.INVENTORY) {
            InventoryScreen(onBack = back, onOpenProduct = { go(Routes.product(it)) })
        }
        composable(Routes.REPORTS) {
            ReportsScreen(onBack = back)
        }
    }
}
