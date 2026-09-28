package com.kitchenreceipts.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kitchenreceipts.app.ui.AppNavHost
import com.kitchenreceipts.app.ui.theme.KitchenReceiptsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KitchenReceiptsTheme {
                AppNavHost()
            }
        }
    }
}
