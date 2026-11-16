package com.nexorith.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexorith.browser.ui.home.HomeScreen
import com.nexorith.browser.ui.home.HomeViewModel
import com.nexorith.browser.ui.theme.NexorithTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NexorithApp).container

        setContent {
            NexorithTheme {
                val homeViewModel: HomeViewModel = viewModel(
                    factory = HomeViewModel.factory(container.appInfoRepository),
                )
                HomeScreen(viewModel = homeViewModel)
            }
        }
    }
}
