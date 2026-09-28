package com.kitchenreceipts.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kitchenreceipts.app.AppContainer
import com.kitchenreceipts.app.KitchenReceiptsApp

/** Creates a ViewModel with access to the app's dependency container. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val app = LocalContext.current.applicationContext as KitchenReceiptsApp
    return viewModel(key = key, factory = viewModelFactory { initializer { create(app.container) } })
}

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as KitchenReceiptsApp).container
